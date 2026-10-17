package redis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import redis.core.CommandProcessor;
import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

/**
 * The log under concurrent writers.
 *
 * <p>Replay rebuilds memory only if the log holds the writes in the order they were
 * applied. These tests run many connections' worth of writes at once, then check that
 * replaying what was logged lands on exactly the value the server held.
 */
class AofConcurrencyTest {

    private static final int THREADS = 8;
    private static final int PER_THREAD = 500;

    @TempDir
    Path dir;

    private final RespWriter writer = new RespWriter();

    // Daemon threads, so a deadlock fails the test instead of keeping the JVM alive.
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    private record Session(Database db, AofManager aof, CommandProcessor processor) {
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private Session open(Path aofPath) throws IOException {
        Database db = new Database();
        AofManager aof = new AofManager(aofPath, writer, AofManager.FsyncPolicy.NO);
        return new Session(db, aof, new CommandProcessor(null, aof));
    }

    private void run(Session s, String... parts) throws IOException {
        List<Value> args = new ArrayList<>();
        for (String p : parts) {
            args.add(new Value("bulk", p));
        }
        s.processor().executeCommand(parts[0].toUpperCase(Locale.ROOT), s.db(), writer,
                new ByteArrayOutputStream(), args, false);
    }

    private void close(Session s) throws IOException {
        s.aof().close();
        s.db().shutdown();
    }

    /** What a restart would rebuild for {@code key} from the log. */
    private String replayed(Path aofPath, String key) throws IOException {
        Session restarted = open(aofPath);
        try {
            restarted.aof().replay(restarted.db(), restarted.processor(), writer);
            return restarted.db().get(key);
        } finally {
            close(restarted);
        }
    }

    private static void finish(Future<?> task, String whatHung) throws Exception {
        try {
            task.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            fail(whatHung);
        }
    }

    @Test
    @DisplayName("Concurrent writes are logged in the order they were applied")
    void logOrderMatchesApplyOrder() throws Exception {
        Path aofPath = dir.resolve("a.aof");
        Session s = open(aofPath);

        // APPEND is order-sensitive: the value in memory spells out the order the writes
        // were applied in, and a replay spells out the order they were logged in.
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> writers = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            String token = Character.toString('a' + t);
            writers.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < PER_THREAD; i++) {
                    run(s, "APPEND", "log", token);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> w : writers) {
            finish(w, "a writer never finished");
        }

        String inMemory = s.db().get("log");
        close(s);

        assertEquals(THREADS * PER_THREAD, inMemory.length());
        assertTrue(inMemory.equals(replayed(aofPath, "log")),
                "replay rebuilt a different value than the server held: writes were logged out of order");
    }

    @Test
    @DisplayName("A write racing BGREWRITEAOF cannot deadlock it")
    void writesDoNotDeadlockAgainstRewrite() throws Exception {
        Path aofPath = dir.resolve("a.aof");
        Session s = open(aofPath);

        // A write holds the database lock while it appends, and a rewrite needs both locks.
        // If the rewrite took them in the opposite order, each side could end up holding the
        // lock the other is waiting for, and neither would ever finish.
        AtomicBoolean rewriting = new AtomicBoolean(true);
        Future<?> writes = pool.submit(() -> {
            for (int i = 0; rewriting.get(); i++) {
                run(s, "SET", "k", Integer.toString(i));
            }
            return null;
        });
        Future<?> rewrites = pool.submit(() -> {
            try {
                for (int i = 0; i < 50; i++) {
                    run(s, "BGREWRITEAOF");
                }
            } finally {
                rewriting.set(false);
            }
            return null;
        });

        finish(rewrites, "BGREWRITEAOF never finished: it deadlocked against a concurrent write");
        finish(writes, "a write never finished: it deadlocked against BGREWRITEAOF");

        String inMemory = s.db().get("k");
        close(s);

        assertEquals(inMemory, replayed(aofPath, "k"), "the rewritten log lost or reordered a write");
    }
}
