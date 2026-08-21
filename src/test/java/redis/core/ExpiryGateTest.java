package redis.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import redis.mocks.FakeClock;

/**
 * A logically expired key must look absent from every angle, not just the few that
 * happened to check. Each of these went the other way before the expiry gate existed.
 */
class ExpiryGateTest {

    private FakeClock clock;
    private Database db;

    @BeforeEach
    void setUp() {
        clock = new FakeClock(1_700_000_000_000L);
        db = new Database(clock);
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    private void expiredStringKey(String key) {
        db.set(key, "v");
        db.expire(key, 10);
        clock.advanceSeconds(3600);
    }

    @Nested
    @DisplayName("A dead key is dead to every reader")
    class DeadToEveryReader {

        @Test
        void typeReportsNone() {
            expiredStringKey("k");
            assertNull(db.getKeyType("k"), "TYPE answered from the store without checking expiry");
        }

        @Test
        void existenceChecksSayNo() {
            expiredStringKey("k");
            assertFalse(db.keyExists("k"));
            assertFalse(db.stringStoreContainsKey("k"));
        }

        @Test
        void deleteReportsNothingRemoved() {
            expiredStringKey("k");
            assertEquals(0, db.del("k"), "DEL claimed to remove a key that was already gone");
        }

        @Test
        void ttlAndGetAgreeOnTheBoundaryMillisecond() {
            db.set("k", "v");
            db.expire("k", 10);

            clock.advanceMillis(10_000); // exactly at the deadline: still alive
            assertEquals("v", db.get("k"));
            assertEquals(0, db.pttl("k"));

            clock.advanceMillis(1); // one past it: gone, by both accounts
            assertNull(db.get("k"));
            assertEquals(-2, db.pttl("k"));
        }
    }

    @Nested
    @DisplayName("A dead key cannot be revived by a write")
    class NotRevivable {

        @Test
        void expireOnADeadKeyDoesNotBringItBack() {
            expiredStringKey("k");

            assertFalse(db.expire("k", 100), "EXPIRE returned 1 for a key that no longer exists");
            assertEquals(-2, db.ttl("k"));
            assertNull(db.get("k"));
        }

        @Test
        void hsetOnAnExpiredHashDoesNotResurrectItsFields() {
            db.hset("h", "f1", "v1");
            db.hset("h", "f2", "v2");
            db.expire("h", 10);
            clock.advanceSeconds(3600);

            // Nobody read the key in between, so only the write itself can notice.
            db.hset("h", "f3", "v3");

            assertEquals(1, db.hlen("h"), "the pre-expiry fields came back");
            assertNull(db.hashget("h", "f1"));
            assertEquals("v3", db.hashget("h", "f3"));
            assertEquals(-1, db.ttl("h"), "the new hash inherited the dead key's TTL");
        }
    }

    @Nested
    @DisplayName("Sub-second and far-future deadlines survive intact")
    class DeadlinePrecision {

        @Test
        void subSecondTtlIsNotTruncatedToZero() {
            assertTrue(db.set("k", "v", false, false, false, clock.nowMillis() + 500));

            assertEquals("v", db.get("k"), "a 500ms TTL deleted the key outright");
            assertEquals(500, db.pttl("k"));

            clock.advanceMillis(501);
            assertNull(db.get("k"));
        }

        @Test
        void aCenturyOutDoesNotOverflow() {
            long deadline = clock.nowMillis() + 100L * 365 * 24 * 3600 * 1000;
            assertTrue(db.set("k", "v", false, false, false, deadline));

            assertEquals("v", db.get("k"), "a far-future deadline wrapped and deleted the key");
            assertEquals(deadline, db.getExpiry("k"));
        }

        @Test
        void expireZeroStillDeletesImmediately() {
            db.set("k", "v");
            assertTrue(db.expire("k", 0));
            assertNull(db.get("k"));
        }
    }
}
