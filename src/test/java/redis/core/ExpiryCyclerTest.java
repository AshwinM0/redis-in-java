package redis.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import redis.mocks.FakeClock;

/**
 * Active expiration: the background cycle that reclaims keys nobody reads again.
 *
 * <p>A cycle that throws is fatal in a way that leaves no trace —
 * {@code scheduleAtFixedRate} cancels the task and never reports it — so these tests
 * cover both the reclaiming itself and the cycle's tolerance of repeated runs.
 */
class ExpiryCyclerTest {

    private Database db;

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.shutdown();
        }
    }

    private int pendingExpiries(String prefix, int count) {
        int pending = 0;
        for (int i = 0; i < count; i++) {
            if (db.getExpiry(prefix + i) != null) {
                pending++;
            }
        }
        return pending;
    }

    @Test
    @DisplayName("a cycle over an all-expired sample completes without throwing")
    void cycleSurvivesAnAllExpiredSample() {
        FakeClock clock = new FakeClock(1_000);
        db = new Database(clock);

        for (int i = 0; i < 50; i++) {
            db.set("k" + i, "v");
            db.expire("k" + i, 1);
        }
        clock.advanceSeconds(5);

        // Every sampled key is expired, which is the case that used to blow up once the
        // first pass had already deleted them.
        assertDoesNotThrow(() -> db.removeSampledKeysIfExpired());
    }

    @Test
    @DisplayName("repeated cycles keep reclaiming, and reclaim everything")
    void repeatedCyclesReclaimEverything() {
        FakeClock clock = new FakeClock(1_000);
        db = new Database(clock);

        for (int i = 0; i < 60; i++) {
            db.set("k" + i, "v");
            db.expire("k" + i, 1);
        }
        assertEquals(60, pendingExpiries("k", 60));

        clock.advanceSeconds(5);
        for (int cycle = 0; cycle < 10; cycle++) {
            db.removeSampledKeysIfExpired();
        }

        assertEquals(0, pendingExpiries("k", 60), "every expired key should be reclaimed");
        for (int i = 0; i < 60; i++) {
            assertNull(db.get("k" + i));
        }
    }

    @Test
    @DisplayName("a later batch is still reclaimed after an earlier productive cycle")
    void staysHealthyAcrossBatches() {
        FakeClock clock = new FakeClock(1_000);
        db = new Database(clock);

        for (int i = 0; i < 40; i++) {
            db.set("first" + i, "v");
            db.expire("first" + i, 1);
        }
        clock.advanceSeconds(5);
        for (int cycle = 0; cycle < 10; cycle++) {
            db.removeSampledKeysIfExpired();
        }
        assertEquals(0, pendingExpiries("first", 40));

        // The regression was that the cycle died here and never ran again.
        for (int i = 0; i < 40; i++) {
            db.set("second" + i, "v");
            db.expire("second" + i, 1);
        }
        clock.advanceSeconds(5);
        for (int cycle = 0; cycle < 10; cycle++) {
            db.removeSampledKeysIfExpired();
        }
        assertEquals(0, pendingExpiries("second", 40), "the cycle must still be working");
    }

    @Test
    @DisplayName("unexpired keys are left alone")
    void leavesLiveKeysAlone() {
        FakeClock clock = new FakeClock(1_000);
        db = new Database(clock);

        for (int i = 0; i < 20; i++) {
            db.set("live" + i, "v");
            db.expire("live" + i, 600);
        }
        clock.advanceSeconds(5);
        db.removeSampledKeysIfExpired();

        assertEquals(20, pendingExpiries("live", 20));
        assertEquals("v", db.get("live0"));
    }

    @Test
    @DisplayName("a cycle on an empty keyspace is a no-op")
    void emptyKeyspaceIsFine() {
        db = new Database(new FakeClock(1_000));
        assertDoesNotThrow(() -> db.removeSampledKeysIfExpired());
    }

    @Test
    @DisplayName("the scheduled cleaner is still reclaiming after its first productive run")
    void scheduledCleanerSurvives() throws Exception {
        db = new Database(); // real clock: exercises the actual scheduled task

        for (int i = 0; i < 60; i++) {
            db.set("batch1_" + i, "v");
            db.expire("batch1_" + i, 1);
        }
        Thread.sleep(8_000); // initial delay is 5s, then one tick per second

        for (int i = 0; i < 60; i++) {
            db.set("batch2_" + i, "v");
            db.expire("batch2_" + i, 1);
        }
        Thread.sleep(5_000);

        assertEquals(0, pendingExpiries("batch2_", 60),
                "the cleaner must survive an earlier productive cycle");
    }
}
