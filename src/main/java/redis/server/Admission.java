package redis.server;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

import redis.time.Clock;

/**
 * Decides which connections the server takes on, and how long a stranger may keep one.
 *
 * <p>Every connection holds a thread and its buffers until it closes, so connections are
 * what has to be rationed. All of them count against {@code maxClients}. One that has not
 * authenticated also counts against {@code maxUnauthenticated}, a much smaller budget, and
 * is closed once its deadline passes. However many connections strangers open, they hold
 * at most that budget, each slot only briefly, and authenticated clients keep the rest.
 *
 * <p>When the budget is full, a new connection evicts the oldest unauthenticated one rather
 * than being refused. Refusing would hand an attacker the lockout: keep the budget full and
 * every client that connects afterwards is turned away. With eviction, the attacker has to
 * open a whole budget of connections in the time a legitimate client takes to send AUTH.
 *
 * <p>With no password, every connection counts as authenticated from the start and only
 * {@code maxClients} applies: a server without a password trusts whoever can reach it.
 *
 * <p>This class is bookkeeping only and starts no threads. The server calls
 * {@link #expireOverdue} on a schedule; tests call it with a fake clock.
 */
public final class Admission {

    /** A connection's hold on the server's capacity, from accept until it closes. */
    public interface Ticket {

        /** For a connection that no Admission tracks: authenticated, and holding nothing. */
        Ticket UNTRACKED = new Ticket() {
            @Override
            public boolean authenticated() {
                return true;
            }

            @Override
            public void release() {
            }
        };

        /**
         * Records that the connection has authenticated: it leaves the unauthenticated
         * budget, and neither the deadline nor eviction applies to it any more.
         *
         * @return false if it was evicted first, in which case its socket is being closed
         *         and the connection must not carry on
         */
        boolean authenticated();

        /** Records that the connection has closed, giving back what it held. Safe to repeat. */
        void release();
    }

    private enum State { UNAUTHENTICATED, AUTHENTICATED, EVICTED, RELEASED }

    private final int maxClients;
    private final int maxUnauthenticated;
    private final long authTimeoutMillis; // 0 for no deadline
    private final boolean passwordRequired;
    private final Clock clock;

    // Both guarded by this. The set is in arrival order, and every deadline is the arrival
    // time plus the same timeout, so its first entry is both the oldest connection and the
    // next to expire. That one fact serves eviction and the deadline alike.
    private final LinkedHashSet<Entry> unauthenticated = new LinkedHashSet<>();
    private int connected;

    public Admission(int maxClients, int maxUnauthenticated, int authTimeoutSeconds,
            boolean passwordRequired, Clock clock) {
        if (maxUnauthenticated < 1) {
            throw new IllegalArgumentException(
                    "maxUnauthenticated must be at least 1, was " + maxUnauthenticated);
        }
        this.maxClients = maxClients;
        this.maxUnauthenticated = maxUnauthenticated;
        this.authTimeoutMillis = authTimeoutSeconds * 1000L;
        this.passwordRequired = passwordRequired;
        this.clock = clock;
    }

    /**
     * Takes on a new connection, or returns null when the server already holds
     * {@code maxClients}. If the unauthenticated budget is full, the oldest unauthenticated
     * connection is evicted to make room: its socket is closed, which ends its handler.
     */
    public Ticket admit(Socket socket) {
        Entry evicted = null;
        Entry admitted;
        synchronized (this) {
            if (connected >= maxClients) {
                return null;
            }
            connected++;
            if (!passwordRequired) {
                return new Entry(socket, State.AUTHENTICATED, 0);
            }
            if (unauthenticated.size() >= maxUnauthenticated) {
                evicted = unauthenticated.removeFirst();
                evicted.state = State.EVICTED;
            }
            admitted = new Entry(socket, State.UNAUTHENTICATED, clock.nowMillis() + authTimeoutMillis);
            unauthenticated.add(admitted);
        }
        // Closing is I/O, so it waits until the lock is released: every AUTH and every close
        // passes through that lock, and none of them should queue behind a socket.
        if (evicted != null) {
            evicted.closeSocket();
        }
        return admitted;
    }

    /**
     * Closes every unauthenticated connection whose deadline has passed. The deadline is
     * counted from connecting, not from the last byte received, so a client trickling a
     * byte at a time cannot hold its slot past it.
     */
    public void expireOverdue() {
        if (authTimeoutMillis == 0) {
            return;
        }
        List<Entry> overdue = new ArrayList<>();
        synchronized (this) {
            long now = clock.nowMillis();
            Iterator<Entry> oldestFirst = unauthenticated.iterator();
            while (oldestFirst.hasNext()) {
                Entry entry = oldestFirst.next();
                if (entry.deadline > now) {
                    break; // every later entry arrived later, so it expires later too
                }
                oldestFirst.remove();
                entry.state = State.EVICTED;
                overdue.add(entry);
            }
        }
        for (Entry entry : overdue) {
            entry.closeSocket();
        }
    }

    /** Connections open now. An evicted one counts until its handler has exited. */
    public synchronized int connected() {
        return connected;
    }

    /** Connections open now that have not authenticated. */
    public synchronized int unauthenticated() {
        return unauthenticated.size();
    }

    private final class Entry implements Ticket {

        private final Socket socket;
        private final long deadline;
        private State state; // guarded by Admission.this

        Entry(Socket socket, State state, long deadline) {
            this.socket = socket;
            this.state = state;
            this.deadline = deadline;
        }

        @Override
        public boolean authenticated() {
            synchronized (Admission.this) {
                // Eviction and AUTH can arrive together. Both decide under this lock, so
                // exactly one of them moves the connection out of UNAUTHENTICATED.
                if (state == State.UNAUTHENTICATED) {
                    unauthenticated.remove(this);
                    state = State.AUTHENTICATED;
                }
                return state == State.AUTHENTICATED;
            }
        }

        @Override
        public void release() {
            synchronized (Admission.this) {
                if (state == State.RELEASED) {
                    return;
                }
                unauthenticated.remove(this); // a no-op unless it never authenticated
                state = State.RELEASED;
                connected--;
            }
        }

        private void closeSocket() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing is the point; a socket that fails to close cleanly is closed anyway.
            }
        }
    }
}
