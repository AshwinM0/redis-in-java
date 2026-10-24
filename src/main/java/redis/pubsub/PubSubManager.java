package redis.pubsub;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import redis.resp.RespWriter;
import redis.utils.GlobMatcher;

public class PubSubManager {

    private static final Logger LOG = Logger.getLogger(PubSubManager.class.getName());

    /** Redis's hard limit for subscribers: {@code client-output-buffer-limit pubsub 32mb}. */
    public static final long DEFAULT_OUTPUT_LIMIT_BYTES = 32L * 1024 * 1024;

    /** Channel -> the connections listening on it. */
    private final Map<String, Set<Subscriber>> channelSubscribers = new ConcurrentHashMap<>();

    /** Pattern -> the connections listening on it. */
    private final Map<String, Set<Subscriber>> patternSubscribers = new ConcurrentHashMap<>();

    /**
     * One Subscriber per connection, from its first write until it closes, so everything
     * written to a connection shares that connection's single write lock.
     */
    private final Map<OutputStream, Subscriber> connections = new ConcurrentHashMap<>();

    private final RespWriter writer = new RespWriter();
    private final long outputLimitBytes;
    private final Executor writers;

    public PubSubManager() {
        this(DEFAULT_OUTPUT_LIMIT_BYTES);
    }

    public PubSubManager(long outputLimitBytes) {
        this(outputLimitBytes, newWriterPool());
    }

    /**
     * @param outputLimitBytes how many bytes of messages may wait for one subscriber before
     *                         it is cut off; 0 for no limit
     * @param writers          runs each subscriber's writer task
     */
    public PubSubManager(long outputLimitBytes, Executor writers) {
        this.outputLimitBytes = outputLimitBytes;
        this.writers = writers;
    }

    /**
     * Platform threads, created as needed: a writer blocked on a client that stopped reading
     * holds a thread of its own, never one a healthy subscriber is waiting for. Not virtual
     * threads, because the writer blocks inside {@code synchronized}, which on JDK 21 pins
     * the carrier thread, and enough stuck subscribers would pin them all.
     */
    private static Executor newWriterPool() {
        return Executors.newCachedThreadPool(task -> {
            Thread t = new Thread(task, "pubsub-writer");
            t.setDaemon(true);
            return t;
        });
    }

    private Subscriber connection(OutputStream out) {
        return connections.computeIfAbsent(out,
                o -> new Subscriber(o, outputLimitBytes, writers,
                        sub -> cutOff(sub, false)));
    }

    /**
     * Registers a connection, and how to end it if it falls too far behind.
     *
     * <p>That has to be the socket, not the stream: closing a buffered stream flushes it
     * first, and a flush to a client that has stopped reading blocks for good. Under TLS it
     * has to be the TCP socket beneath, because closing the TLS socket sends a close_notify,
     * which waits behind the blocked write.
     */
    public void connect(OutputStream out, Closeable connection) {
        connection(out).connectedTo(connection);
    }

    /**
     * The connection is closing: nothing more is delivered to it, and whatever was queued is
     * dropped. Its socket is left alone, for the caller to close as it chooses.
     */
    public void closing(OutputStream out) {
        Subscriber sub = connections.get(out);
        if (sub != null) {
            sub.markCutOff();
            removeEverywhere(sub, sub.clear());
        }
    }

    /** The connection has closed. Drops every trace of it, so it leaves nothing behind. */
    public void disconnected(OutputStream out) {
        closing(out);
        connections.remove(out);
    }

    /**
     * The monitor that serialises writes to {@code out}.
     *
     * <p>A subscribed connection has two writers: its own thread, replying to commands, and
     * the writer task sending it messages. Both go through this, or a reply and a message
     * interleave mid-frame and desynchronise the stream.
     */
    public Object lockFor(OutputStream out) {
        return connection(out).lock;
    }

    /**
     * Subscribe a client to a channel.
     * Returns the number of channels and patterns this client is now subscribed to.
     */
    public int subscribe(OutputStream out, String channel) {
        Subscriber sub = connection(out);
        if (sub.subscribe(false, channel)) {
            add(channelSubscribers, channel, sub);
        }
        return sub.count();
    }

    /**
     * Subscribe a client to every channel matching a glob-style pattern.
     * Returns the number of channels and patterns this client is now subscribed to.
     */
    public int psubscribe(OutputStream out, String pattern) {
        Subscriber sub = connection(out);
        if (sub.subscribe(true, pattern)) {
            add(patternSubscribers, pattern, sub);
        }
        return sub.count();
    }

    /**
     * Check if a client is currently subscribed to any channel or pattern.
     */
    public boolean isSubscribed(OutputStream out) {
        return subscriptionCount(out) > 0;
    }

    public int subscriptionCount(OutputStream out) {
        Subscriber sub = connections.get(out);
        return sub == null ? 0 : sub.count();
    }

    /** The channels this client is subscribed to, not counting patterns. */
    public List<String> channelsOf(OutputStream out) {
        Subscriber sub = connections.get(out);
        return sub == null ? List.of() : sub.subscriptions(false);
    }

    /** The patterns this client is subscribed to. */
    public List<String> patternsOf(OutputStream out) {
        Subscriber sub = connections.get(out);
        return sub == null ? List.of() : sub.subscriptions(true);
    }

    /**
     * Unsubscribe a client from a channel.
     * Returns the number of channels and patterns this client is still subscribed to.
     */
    public int unsubscribe(OutputStream out, String channel) {
        Subscriber sub = connections.get(out);
        if (sub == null) {
            return 0;
        }
        if (sub.unsubscribe(false, channel)) {
            remove(channelSubscribers, channel, sub);
        }
        return sub.count();
    }

    /**
     * Unsubscribe a client from a pattern.
     * Returns the number of channels and patterns this client is still subscribed to.
     */
    public int punsubscribe(OutputStream out, String pattern) {
        Subscriber sub = connections.get(out);
        if (sub == null) {
            return 0;
        }
        if (sub.unsubscribe(true, pattern)) {
            remove(patternSubscribers, pattern, sub);
        }
        return sub.count();
    }

    /**
     * Unsubscribe a client from all channels and patterns. The connection stays registered,
     * with the same lock: it is still open.
     */
    public void unsubscribeAll(OutputStream out) {
        Subscriber sub = connections.get(out);
        if (sub != null) {
            removeEverywhere(sub, sub.clear());
        }
    }

    private void removeEverywhere(Subscriber sub, Subscriber.Subscriptions ended) {
        for (String channel : ended.channels()) {
            remove(channelSubscribers, channel, sub);
        }
        for (String pattern : ended.patterns()) {
            remove(patternSubscribers, pattern, sub);
        }
    }

    private static void add(Map<String, Set<Subscriber>> index, String name, Subscriber sub) {
        index.compute(name, (k, subs) -> {
            if (subs == null) {
                subs = ConcurrentHashMap.newKeySet();
            }
            subs.add(sub);
            return subs;
        });
    }

    /**
     * Atomic for each name. Removing the subscriber, then dropping the set if it looked
     * empty, as two steps, let a subscribe from another connection land in between, and its
     * subscription vanished along with the set.
     */
    private static void remove(Map<String, Set<Subscriber>> index, String name, Subscriber sub) {
        index.computeIfPresent(name, (k, subs) -> {
            subs.remove(sub);
            return subs.isEmpty() ? null : subs;
        });
    }

    /**
     * Publish a message to a channel: to each subscriber of the channel, then to each
     * subscriber of a pattern that matches it. A client subscribed both ways gets it once
     * for each, as in Redis.
     *
     * <p>Returns the number of deliveries queued. Nothing here waits on a client: a
     * subscriber's socket is written by its own writer task.
     */
    public int publish(String channel, String message) {
        int receivers = 0;

        Set<Subscriber> subscribers = channelSubscribers.get(channel);
        if (subscribers != null && !subscribers.isEmpty()) {
            // Encoded once, whatever the number of subscribers. Redis Pub/Sub message
            // format: ["message", channel, data]
            byte[] frame = encode("message", null, channel, message);
            for (Subscriber sub : subscribers) {
                if (deliver(sub, frame, false, channel)) {
                    receivers++;
                }
            }
        }

        for (Map.Entry<String, Set<Subscriber>> entry : patternSubscribers.entrySet()) {
            String pattern = entry.getKey();
            if (!GlobMatcher.matches(pattern, channel)) {
                continue;
            }
            // ["pmessage", pattern, channel, data]
            byte[] frame = encode("pmessage", pattern, channel, message);
            for (Subscriber sub : entry.getValue()) {
                if (deliver(sub, frame, true, pattern)) {
                    receivers++;
                }
            }
        }

        return receivers;
    }

    private boolean deliver(Subscriber sub, byte[] frame, boolean pattern, String name) {
        switch (sub.offer(frame, pattern, name)) {
            case QUEUED:
                return true;
            case OVER_LIMIT:
                cutOff(sub, true);
                return false;
            default:
                return false;
        }
    }

    /**
     * Ends a subscriber that has fallen too far behind, or whose socket has failed.
     *
     * <p>Waiting for it would stall every publisher, and queuing without limit would end in
     * an out-of-memory. Dropping its messages and carrying on would leave it believing it
     * had seen everything. Closing tells it the truth: it missed messages, and has to
     * reconnect and resync.
     */
    private void cutOff(Subscriber sub, boolean overLimit) {
        if (!sub.markCutOff()) {
            return; // already done, or the connection is closing
        }
        removeEverywhere(sub, sub.clear());
        if (overLimit) {
            LOG.warning(() -> "Disconnecting a subscriber with over " + outputLimitBytes
                    + " bytes of messages waiting: " + sub.describe());
        }
        sub.closeConnection();
    }

    private byte[] encode(String kind, String pattern, String channel, String message) {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        try {
            writer.writeArrayHeader(frame, pattern == null ? 3 : 4);
            writer.writeBulk(frame, kind);
            if (pattern != null) {
                writer.writeBulk(frame, pattern);
            }
            writer.writeBulk(frame, channel);
            writer.writeBulk(frame, message);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // a ByteArrayOutputStream never throws
        }
        return frame.toByteArray();
    }

    /** PUBSUB CHANNELS: channels with at least one subscriber, optionally matching a pattern. */
    public List<String> activeChannels(String pattern) {
        List<String> active = new ArrayList<>();
        for (String channel : channelSubscribers.keySet()) {
            if (pattern == null || GlobMatcher.matches(pattern, channel)) {
                active.add(channel);
            }
        }
        return active;
    }

    /** PUBSUB NUMSUB: subscribers of the channel itself, not counting pattern subscribers. */
    public int numSub(String channel) {
        Set<Subscriber> subscribers = channelSubscribers.get(channel);
        return subscribers == null ? 0 : subscribers.size();
    }

    /** PUBSUB NUMPAT: how many distinct patterns are subscribed to. */
    public int numPat() {
        return patternSubscribers.size();
    }
}
