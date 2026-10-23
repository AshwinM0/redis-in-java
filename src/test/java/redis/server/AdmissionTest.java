package redis.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Socket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import redis.mocks.FakeClock;

/**
 * The bookkeeping behind who gets a connection, driven by a fake clock. The sockets are never
 * connected: closing one is how Admission ends a connection, and isClosed() shows whether it
 * did.
 */
class AdmissionTest {

    private final FakeClock clock = new FakeClock(0);

    private Admission withPassword(int maxUnauthenticated, int authTimeoutSeconds) {
        return new Admission(100, maxUnauthenticated, authTimeoutSeconds, true, clock);
    }

    @Test
    @DisplayName("Without a password, only maxClients applies")
    void withoutAPasswordOnlyMaxClientsApplies() {
        Admission admission = new Admission(2, 1, 10, false, clock);

        Admission.Ticket first = admission.admit(new Socket());
        assertNotNull(admission.admit(new Socket()));
        assertNull(admission.admit(new Socket()), "a third client got in past maxClients of 2");
        assertEquals(0, admission.unauthenticated());

        first.release();
        assertNotNull(admission.admit(new Socket()), "a released slot was not given back");
    }

    @Test
    @DisplayName("A full budget evicts the oldest unauthenticated connection, not the newcomer")
    void fullBudgetEvictsTheOldest() {
        Admission admission = withPassword(2, 0);
        Socket oldest = new Socket();
        Socket middle = new Socket();

        admission.admit(oldest);
        admission.admit(middle);
        Admission.Ticket newest = admission.admit(new Socket());

        assertNotNull(newest, "refusing the newcomer lets strangers who hold the budget lock everyone out");
        assertTrue(oldest.isClosed(), "the oldest unauthenticated connection was not evicted");
        assertFalse(middle.isClosed());
        assertEquals(2, admission.unauthenticated());
    }

    @Test
    @DisplayName("An authenticated connection leaves the budget and is never evicted")
    void authenticatedConnectionsAreNeverEvicted() {
        Admission admission = withPassword(1, 0);
        Socket client = new Socket();

        assertTrue(admission.admit(client).authenticated());
        admission.admit(new Socket());
        admission.admit(new Socket()); // the budget of one is full again, so this evicts

        assertFalse(client.isClosed(), "an authenticated client was evicted to make room for a stranger");
    }

    /** The race that matters: if eviction wins, the client must not hear OK. */
    @Test
    @DisplayName("A connection that has been evicted cannot then authenticate")
    void evictionBeatsALateAuth() {
        Admission admission = withPassword(1, 0);
        Admission.Ticket first = admission.admit(new Socket());

        admission.admit(new Socket()); // evicts first

        assertFalse(first.authenticated());
    }

    @Test
    @DisplayName("The deadline closes a connection that has not authenticated in time, and only that one")
    void deadlineClosesOverdueConnections() {
        Admission admission = withPassword(128, 10);
        Socket early = new Socket();
        Socket late = new Socket();

        admission.admit(early); // deadline at 10 s
        clock.advanceSeconds(5);
        admission.admit(late); // deadline at 15 s
        clock.advanceSeconds(5);
        admission.expireOverdue();

        assertTrue(early.isClosed(), "a connection past its deadline was left open");
        assertFalse(late.isClosed(), "a connection was closed before its deadline");
        assertEquals(1, admission.unauthenticated());
    }

    @Test
    @DisplayName("The deadline no longer applies once a connection has authenticated")
    void deadlineSparesAuthenticatedConnections() {
        Admission admission = withPassword(128, 10);
        Socket client = new Socket();

        assertTrue(admission.admit(client).authenticated());
        clock.advanceSeconds(60);
        admission.expireOverdue();

        assertFalse(client.isClosed());
    }

    @Test
    @DisplayName("Releasing gives back a connection's slots exactly once")
    void releaseGivesBackSlotsOnce() {
        Admission admission = withPassword(1, 0);
        Admission.Ticket evicted = admission.admit(new Socket());
        Admission.Ticket current = admission.admit(new Socket()); // evicts the first

        assertEquals(2, admission.connected(), "an evicted connection holds its slot until its handler exits");
        evicted.release();
        evicted.release();
        assertEquals(1, admission.connected());

        current.release();
        assertEquals(0, admission.connected());
        assertEquals(0, admission.unauthenticated());
    }

    @Test
    void budgetMustAdmitAtLeastOneConnection() {
        assertThrows(IllegalArgumentException.class, () -> new Admission(100, 0, 10, true, clock));
    }
}
