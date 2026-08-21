package redis.pubsub;

import java.io.OutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import redis.resp.RespWriter;

public class PubSubManager {

    /** Channel -> the connections listening on it. */
    private final Map<String, Set<Subscriber>> channelSubscribers = new ConcurrentHashMap<>();

    /** Which channels each connection is subscribed to. */
    private final Map<OutputStream, Set<String>> clientChannels = new ConcurrentHashMap<>();

    /**
     * One Subscriber per connection, so every channel a connection listens on shares that
     * connection's single write lock.
     */
    private final Map<OutputStream, Subscriber> connections = new ConcurrentHashMap<>();

    private final RespWriter writer = new RespWriter();

    private Subscriber connection(OutputStream out) {
        return connections.computeIfAbsent(out, Subscriber::new);
    }

    /**
     * The monitor that serialises writes to {@code out}.
     *
     * <p>A subscribed connection has two writers: its own thread, replying to commands,
     * and any thread delivering a published message. Both must go through this, or a
     * reply and a message interleave mid-frame and desynchronise the stream.
     */
    public Object lockFor(OutputStream out) {
        return connection(out).lock;
    }

    /**
     * Subscribe a client to a channel.
     * Returns the total number of channels this client is subscribed to.
     */
    public Integer subscribe(OutputStream out, String channel) {
        Subscriber subscriber = connection(out);

        channelSubscribers.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet()).add(subscriber);
        clientChannels.computeIfAbsent(out, k -> ConcurrentHashMap.newKeySet()).add(channel);

        return clientChannels.get(out).size();
    }

    /**
     * Check if a client is currently subscribed to any channels.
     */
    public boolean isSubscribed(OutputStream out) {
        Set<String> channels = clientChannels.get(out);
        return channels != null && !channels.isEmpty();
    }

    /**
     * Unsubscribe a client from a channel.
     * Returns the remaining number of channels this client is subscribed to.
     */
    public Integer unsubscribe(OutputStream out, String channel) {
        Subscriber subscriber = connection(out);

        Set<Subscriber> subscribers = channelSubscribers.get(channel);
        if (subscribers != null) {
            subscribers.remove(subscriber);
            if (subscribers.isEmpty()) {
                channelSubscribers.remove(channel);
            }
        }

        Set<String> channels = clientChannels.get(out);
        if (channels != null) {
            channels.remove(channel);
            if (channels.isEmpty()) {
                forget(out);
                return 0;
            }
            return channels.size();
        }
        forget(out);
        return 0;
    }

    /**
     * Unsubscribe a client from ALL channels.
     */
    public void unsubscribeAll(OutputStream out) {
        Set<String> channels = clientChannels.remove(out);
        if (channels != null) {
            Subscriber subscriber = connection(out);
            for (String channel : channels) {
                Set<Subscriber> subs = channelSubscribers.get(channel);
                if (subs != null) {
                    subs.remove(subscriber);
                    if (subs.isEmpty()) {
                        channelSubscribers.remove(channel);
                    }
                }
            }
        }
        forget(out);
    }

    /** Drops every trace of a connection, so a closed socket leaves nothing behind. */
    private void forget(OutputStream out) {
        clientChannels.remove(out);
        connections.remove(out);
    }

    /**
     * Publish a message to a channel.
     * Returns the number of clients that received the message.
     */
    public int publish(String channel, String message) {
        Set<Subscriber> subscribers = channelSubscribers.get(channel);
        if (subscribers == null || subscribers.isEmpty()) {
            return 0;
        }

        int delivered = 0;
        List<Subscriber> dead = new ArrayList<>();

        for (Subscriber sub : subscribers) {
            // One connection's lock at a time: taken, used, released before the next is
            // touched. Holding two would let two publishers deadlock on each other.
            try {
                synchronized (sub.lock) {
                    // Redis Pub/Sub message format: ["message", channel, data]
                    writer.writeArrayHeader(sub.out, 3);
                    writer.writeBulk(sub.out, "message");
                    writer.writeBulk(sub.out, channel);
                    writer.writeBulk(sub.out, message);
                    sub.out.flush();
                }
                delivered++;
            } catch (IOException e) {
                dead.add(sub);
            }
        }

        // A broken connection is broken for every channel, not just the one that noticed.
        // Reaping it from this channel alone left it registered on the others, where it
        // would be retried on every publish for the life of the process.
        for (Subscriber gone : dead) {
            unsubscribeAll(gone.out);
        }

        return delivered;
    }
}
