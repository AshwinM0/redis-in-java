package redis.pubsub;

import java.io.OutputStream;

/**
 * One connected client, identified by its output stream.
 *
 * <p>There is exactly one Subscriber per connection, however many channels that
 * connection is subscribed to, because {@link #lock} is what serialises writes to the
 * socket. A per-subscription instance would hand two publishers two different locks over
 * the same stream and let them interleave inside a single reply.
 *
 * <p>The lock is only ever held alone. Nothing acquires a second connection's lock while
 * holding one, which is what keeps two clients publishing to each other from deadlocking.
 */
public class Subscriber {
    public final OutputStream out;
    public final Object lock = new Object();

    public Subscriber(OutputStream out) {
        this.out = out;
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
