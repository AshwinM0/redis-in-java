package redis.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Read-modify-write commands under contention.
 *
 * <p>Each of these does its read, its decision and its write in one step. Splitting them
 * across separate calls lets two threads read the same value and both write back, so one
 * update disappears - which is what these tests are here to catch.
 */
class AtomicityTest {

    private static final int THREADS = 8;
    private static final int PER_THREAD = 2_000;

    private Database db;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        db = new Database();
        pool = Executors.newFixedThreadPool(THREADS);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        db.shutdown();
    }

    /** Releases every thread at the same moment, so they collide on purpose. */
    private <T> List<T> race(int threads, Callable<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    @Nested
    @DisplayName("counters do not lose updates")
    class Counters {

        @Test
        @DisplayName("concurrent INCR lands every increment")
        void concurrentIncr() throws Exception {
            race(THREADS, () -> {
                for (int i = 0; i < PER_THREAD; i++) {
                    db.incrBy("counter", 1);
                }
                return null;
            });
            assertEquals(THREADS * PER_THREAD, Long.parseLong(db.get("counter")));
        }

        @Test
        @DisplayName("concurrent DECR lands every decrement")
        void concurrentDecr() throws Exception {
            race(THREADS, () -> {
                for (int i = 0; i < PER_THREAD; i++) {
                    db.incrBy("counter", -1);
                }
                return null;
            });
            assertEquals(-(long) THREADS * PER_THREAD, Long.parseLong(db.get("counter")));
        }

        @Test
        @DisplayName("interleaved INCRBY and DECRBY settle on the right total")
        void mixedIncrementsBalance() throws Exception {
            AtomicInteger which = new AtomicInteger();
            race(THREADS, () -> {
                long delta = (which.getAndIncrement() % 2 == 0) ? 3 : -1;
                for (int i = 0; i < PER_THREAD; i++) {
                    db.incrBy("counter", delta);
                }
                return null;
            });
            long expected = (THREADS / 2L) * PER_THREAD * 3 - (THREADS / 2L) * PER_THREAD;
            assertEquals(expected, Long.parseLong(db.get("counter")));
        }

        @Test
        @DisplayName("every returned value is distinct, so no two callers saw the same state")
        void returnedValuesAreUnique() throws Exception {
            List<List<Long>> perThread = race(THREADS, () -> {
                List<Long> seen = new ArrayList<>();
                for (int i = 0; i < 500; i++) {
                    seen.add(db.incrBy("counter", 1));
                }
                return seen;
            });

            List<Long> all = new ArrayList<>();
            perThread.forEach(all::addAll);
            assertEquals(all.size(), all.stream().distinct().count(),
                    "two callers received the same counter value");
        }
    }

    @Nested
    @DisplayName("check-and-set has exactly one winner")
    class CheckAndSet {

        @Test
        @DisplayName("concurrent SETNX on the same key")
        void setnxHasOneWinner() throws Exception {
            List<Boolean> results = race(16, () -> db.setIfAbsent("lock", "held"));
            assertEquals(1, results.stream().filter(Boolean::booleanValue).count(),
                    "SETNX is a locking primitive: exactly one caller may win");
        }

        @Test
        @DisplayName("concurrent SET ... NX on the same key")
        void setNxHasOneWinner() throws Exception {
            List<Boolean> results = race(16,
                    () -> db.set("lock", "held", true, false, false, null));
            assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        }
    }

    @Nested
    @DisplayName("APPEND keeps every write")
    class Append {

        @Test
        @DisplayName("concurrent APPEND produces a value of the full combined length")
        void concurrentAppend() throws Exception {
            race(THREADS, () -> {
                for (int i = 0; i < 500; i++) {
                    db.appendTo("log", "x");
                }
                return null;
            });
            assertEquals(THREADS * 500, db.get("log").length());
        }
    }

    @Nested
    @DisplayName("single-threaded semantics")
    class Semantics {

        @Test
        void incrByStartsFromZero() {
            assertEquals(1, db.incrBy("fresh", 1));
            assertEquals(6, db.incrBy("fresh", 5));
        }

        @Test
        void incrByRejectsNonNumericValues() {
            db.set("word", "hello");
            CommandException e = assertThrows(CommandException.class, () -> db.incrBy("word", 1));
            assertEquals("value is not an integer or out of range", e.getMessage());
        }

        @Test
        void incrByRejectsAHashKey() {
            db.hset("h", "f", "v");
            CommandException e = assertThrows(CommandException.class, () -> db.incrBy("h", 1));
            assertEquals("WRONGTYPE", e.code());
        }

        @Test
        @DisplayName("overflow is reported instead of silently wrapping")
        void incrByDetectsOverflow() {
            db.set("big", Long.toString(Long.MAX_VALUE));
            CommandException e = assertThrows(CommandException.class, () -> db.incrBy("big", 1));
            assertEquals("increment or decrement would overflow", e.getMessage());
            assertEquals(Long.toString(Long.MAX_VALUE), db.get("big"), "value must be unchanged");
        }

        @Test
        void getSetReturnsThePreviousValue() {
            db.set("k", "old");
            assertEquals("old", db.getSet("k", "new"));
            assertEquals("new", db.get("k"));
        }

        @Test
        void appendToReturnsTheNewLength() {
            assertEquals(3, db.appendTo("k", "abc"));
            assertEquals(5, db.appendTo("k", "de"));
            assertEquals("abcde", db.get("k"));
        }

        @Test
        void msetAppliesEveryPair() {
            db.mset(List.of("a", "1", "b", "2", "c", "3"));
            assertEquals("1", db.get("a"));
            assertEquals("2", db.get("b"));
            assertEquals("3", db.get("c"));
        }
    }
}
