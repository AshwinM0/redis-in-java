package redis.pubsub;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * One connected client, identified by its output stream: what it subscribes to, and the
 * messages on their way to it.
 *
 * <p>There is exactly one Subscriber per connection, for as long as the connection is open,
 * however many channels and patterns it subscribes to, because {@link #lock} is what
 * serialises writes to the socket. Two instances over one stream would hand two writers two
 * different locks and let them interleave inside a single reply.
 *
 * <p>Publishers never write to the socket. They queue a message here and move on, and a
 * writer task sends the queue. A client that stops reading therefore stalls its own writer
 * and nothing else, and once its queue passes the limit it is cut off. Redis does the same
 * with {@code client-output-buffer-limit pubsub}.
 *
 * <p>Locks: {@link #lock} is held across socket writes; this object's monitor guards the
 * subscriptions and the queue, and is never held across I/O. Code holding the monitor never
 * takes {@code lock}, so the two cannot deadlock.
 */
public class Subscriber {

    /** What became of a message offered to this subscriber. */
    enum Offer {
        QUEUED,
        /** Not subscribed to what the message was sent for, or already cut off. */
        NOT_SUBSCRIBED,
        /** Queuing it would pass the limit. Nothing was queued. */
        OVER_LIMIT
    }

    /** A connection's subscriptions, as a snapshot. */
    record Subscriptions(List<String> channels, List<String> patterns) {
    }

    /** An encoded message, and the subscription it was sent for. */
    private record Frame(byte[] bytes, boolean pattern, String name) {
    }

    public final OutputStream out;
    public final Object lock = new Object();

    private final long limitBytes;
    private final Executor writers;
    private final Consumer<Subscriber> onWriteFailure;

    /** How to end the connection; set once it registers. A no-op until then. */
    private volatile Closeable connection = () -> {
    };

    // Guarded by this.
    private final Set<String> channels = new HashSet<>();
    private final Set<String> patterns = new HashSet<>();
    private final ArrayDeque<Frame> queue = new ArrayDeque<>();
    private long queuedBytes;
    private boolean writing; // a writer task is scheduled or running
    private boolean cutOff;

    /** A subscriber with no queue limit, whose messages are written on the publishing thread. */
    public Subscriber(OutputStream out) {
        this(out, 0, Runnable::run, s -> {
        });
    }

    /**
     * @param limitBytes     how many bytes may wait in the queue, 0 for no limit
     * @param writers        runs the writer task
     * @param onWriteFailure called once writing to the client has failed
     */
    Subscriber(OutputStream out, long limitBytes, Executor writers, Consumer<Subscriber> onWriteFailure) {
        this.out = out;
        this.limitBytes = limitBytes;
        this.writers = writers;
        this.onWriteFailure = onWriteFailure;
    }

    void connectedTo(Closeable connection) {
        this.connection = connection;
    }

    /** Ends the connection without waiting on it. */
    void closeConnection() {
        try {
            connection.close();
        } catch (IOException e) {
            // Closing is the point; a socket that fails to close cleanly is closed anyway.
        }
    }

    /** The connection, for log lines. */
    String describe() {
        return connection.toString();
    }

    /** @return whether this is a new subscription */
    synchronized boolean subscribe(boolean pattern, String name) {
        return (pattern ? patterns : channels).add(name);
    }

    /** @return whether there was such a subscription */
    synchronized boolean unsubscribe(boolean pattern, String name) {
        return (pattern ? patterns : channels).remove(name);
    }

    /** Channels and patterns together, which is the count Redis puts in every (un)subscribe reply. */
    synchronized int count() {
        return channels.size() + patterns.size();
    }

    synchronized List<String> subscriptions(boolean pattern) {
        return List.copyOf(pattern ? patterns : channels);
    }

    /** Ends every subscription, and returns what they were. */
    synchronized Subscriptions clear() {
        Subscriptions ended = new Subscriptions(List.copyOf(channels), List.copyOf(patterns));
        channels.clear();
        patterns.clear();
        return ended;
    }

    /**
     * Stops all delivery for good and drops whatever is still queued.
     *
     * @return false if this had already happened
     */
    synchronized boolean markCutOff() {
        if (cutOff) {
            return false;
        }
        cutOff = true;
        queue.clear();
        queuedBytes = 0;
        return true;
    }

    /**
     * Queues a message, unless the subscription it was sent for has ended or the queue is
     * full. Never blocks on the client: the socket is only touched by the writer task.
     */
    Offer offer(byte[] frame, boolean pattern, String name) {
        synchronized (this) {
            // Checked under the same monitor the unsubscribe takes, so a message is either
            // queued while the subscription stands or not at all.
            if (cutOff || !(pattern ? patterns : channels).contains(name)) {
                return Offer.NOT_SUBSCRIBED;
            }
            if (limitBytes > 0 && queuedBytes + frame.length > limitBytes) {
                return Offer.OVER_LIMIT;
            }
            queue.add(new Frame(frame, pattern, name));
            queuedBytes += frame.length;
            if (writing) {
                return Offer.QUEUED; // the running writer will get to it
            }
            writing = true;
        }
        // Started outside the monitor. A writer run on this thread, as tests arrange it,
        // takes the lock, and the monitor must never be held while taking the lock.
        writers.execute(this::write);
        return Offer.QUEUED;
    }

    /** The writer task: sends what is queued until the queue is empty, then ends. */
    private void write() {
        try {
            while (true) {
                synchronized (lock) {
                    Frame frame;
                    while ((frame = next()) != null) {
                        out.write(frame.bytes());
                        sent(frame.bytes().length);
                    }
                    out.flush();
                }
                synchronized (this) {
                    // A message queued after the last next() found the writer still marked
                    // as running and started none, so it is picked up here.
                    if (queue.isEmpty()) {
                        writing = false;
                        return;
                    }
                }
            }
        } catch (IOException e) {
            synchronized (this) {
                writing = false;
            }
            onWriteFailure.accept(this);
        }
    }

    /**
     * The next frame to send, or null when there is none.
     *
     * <p>The writer holds {@link #lock} here, and UNSUBSCRIBE runs under that lock too, so a
     * frame for a subscription that has ended since it was queued is dropped rather than
     * arriving after the client was told the subscription is over.
     */
    private synchronized Frame next() {
        Frame frame;
        while ((frame = queue.poll()) != null) {
            if ((frame.pattern() ? patterns : channels).contains(frame.name())) {
                return frame;
            }
            queuedBytes -= frame.bytes().length;
        }
        return null;
    }

    /**
     * A frame counts against the limit until its write returns, not just while it waits:
     * a write blocked on a full socket still holds the frame in memory.
     */
    private synchronized void sent(int bytes) {
        if (!cutOff) { // cutting off already zeroed the count
            queuedBytes -= bytes;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof Subscriber))
            return false;
        return out.equals(((Subscriber) o).out);
    }

    @Override
    public int hashCode() {
        return out.hashCode();
    }
}
