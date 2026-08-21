package redis.pubsub;

import java.io.OutputStream;

/**
 * A connected client, identified by its output stream.
 *
 * <p>{@link #lock} serialises writes to that stream: a publishing thread and the
 * subscriber's own connection thread can otherwise interleave inside a single reply and
 * corrupt the byte stream.
 */
public class Subscriber {
    public final OutputStream out;
    public final Object lock; // For thread-safe writes

    public Subscriber(OutputStream out) {
        this.out = out;
        this.lock = new Object();
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
