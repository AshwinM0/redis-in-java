package redis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import redis.core.CommandProcessor;
import redis.core.Database;
import redis.persistence.AofManager.FsyncPolicy;
import redis.resp.Protocol;
import redis.resp.RespWriter;
import redis.resp.Value;

/**
 * Group commit: writes share fsyncs, a reply waits for what its policy promises, and nobody
 * waits on the disk who does not have to.
 *
 * <p>The disk is stood in for by {@link GatedFile}, a real file whose {@code force()} can be
 * held shut, counted, or made to fail -- none of which a real disk does on cue.
 */
class AofGroupCommitTest {

    /** How long "promptly" may take. A correct implementation is nowhere near it. */
    private static final long PROMPTLY_SECONDS = 2;

    @TempDir
    Path dir;

    private final RespWriter writer = new RespWriter();

    // Daemon threads, so a test that hangs fails instead of keeping the JVM alive.
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    /** The file the manager under test writes to. Replaced whenever the manager reopens it. */
    private volatile GatedFile file;
    private Session session;

    private record Session(Database db, AofManager aof, CommandProcessor processor) {
    }

    /**
     * A real file with a gate in front of {@code force()}. A force counts itself, records that
     * it was entered, waits for the gate to open, and then either fails or reaches the disk.
     */
    private static final class GatedFile implements LogFile {
        private final LogFile disk;
        final AtomicInteger forces = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch gate = new CountDownLatch(0); // open
        volatile IOException failWith;

        GatedFile(LogFile disk) {
            this.disk = disk;
        }

        void hold() {
            gate = new CountDownLatch(1);
        }

        void release() {
            gate.countDown();
        }

        @Override
        public void append(byte[] bytes) throws IOException {
            disk.append(bytes);
        }

        @Override
        public void force() throws IOException {
            forces.incrementAndGet();
            entered.countDown();
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted at the gate");
            }
            if (failWith != null) {
                throw failWith;
            }
            disk.force();
        }

        @Override
        public void close() throws IOException {
            disk.close();
        }
    }

    private Session open(FsyncPolicy policy) throws IOException {
        Database db = new Database();
        AofManager aof = new AofManager(dir.resolve("a.aof"), writer, policy,
                path -> file = new GatedFile(new FileLogFile(path)));
        session = new Session(db, aof, new CommandProcessor(null, aof));
        return session;
    }

    @AfterEach
    void tearDown() throws Exception {
        // Open the gate first, so anything still waiting on the disk can finish.
        if (file != null) {
            file.release();
        }
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
        if (session != null) {
            try {
                session.aof().close();
            } catch (IOException expected) {
                // a test that broke the log on purpose
            }
            session.db().shutdown();
        }
    }

    private static Value command(String... parts) {
        List<Value> args = new ArrayList<>();
        for (String p : parts) {
            args.add(new Value("bulk", p));
        }
        return new Value("array", args);
    }

    private String run(Session s, String... parts) throws IOException {
        return run(s, new ByteArrayOutputStream(), parts);
    }

    private String run(Session s, ByteArrayOutputStream reply, String... parts) throws IOException {
        s.processor().executeCommand(parts[0].toUpperCase(Locale.ROOT), s.db(), writer, reply,
                command(parts).array, false);
        return reply.toString(Protocol.WIRE);
    }

    private Future<String> runAsync(Session s, String... parts) {
        return pool.submit(() -> run(s, parts));
    }

    private static <T> T within(Future<T> task, String whatHung) throws Exception {
        try {
            return task.get(PROMPTLY_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return fail(whatHung);
        }
    }

    private void awaitForceEntered(long seconds, String why) throws InterruptedException {
        assertTrue(file.entered.await(seconds, TimeUnit.SECONDS), why);
    }

    @Test
    @DisplayName("ALWAYS: nothing reaches the client until its write is on disk")
    void replyWaitsForForce() throws Exception {
        Session s = open(FsyncPolicy.ALWAYS);
        file.hold();

        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        Future<String> set = pool.submit(() -> run(s, reply, "SET", "k", "v"));
        awaitForceEntered(PROMPTLY_SECONDS, "the write never reached force()");

        assertThrows(TimeoutException.class, () -> set.get(200, TimeUnit.MILLISECONDS),
                "SET returned while its write was still being forced");
        assertEquals(0, reply.size(), "reply bytes reached the client before the write was durable");

        file.release();
        assertEquals("+OK\r\n", within(set, "SET never returned once force() completed"));
    }

    @Test
    @DisplayName("ALWAYS: a write waiting on the disk does not hold up readers")
    void writeWaitingOnForceDoesNotStallReaders() throws Exception {
        Session s = open(FsyncPolicy.ALWAYS);
        file.hold();

        Future<String> set = runAsync(s, "SET", "k", "v");
        // The SET has applied and queued its entry, and is now waiting for this force.
        awaitForceEntered(PROMPTLY_SECONDS, "the write never reached force()");

        assertEquals("$-1\r\n", within(runAsync(s, "GET", "unrelated"),
                "a GET on an unrelated key waited for another client's fsync"));

        file.release();
        assertEquals("+OK\r\n", within(set, "SET never returned once force() completed"));
    }

    @Test
    @DisplayName("EVERYSEC: the once-a-second fsync does not hold up readers")
    void everysecForceDoesNotStallReaders() throws Exception {
        Session s = open(FsyncPolicy.EVERYSEC);
        file.hold();

        // Leave something unforced, then let the writer come round to force it.
        assertEquals("+OK\r\n", run(s, "SET", "k", "v"));
        awaitForceEntered(3, "EVERYSEC never forced");

        // A write arriving now queues behind that fsync, since one thread does both. It must
        // not hold anything a reader needs while it waits. Poll until it has applied: once it
        // has, any design that kept the database locked while waiting blocks the next read.
        Future<String> set = runAsync(s, "SET", "k2", "v2");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROMPTLY_SECONDS);
        while (!"$2\r\nv2\r\n".equals(within(runAsync(s, "GET", "k2"), "a GET waited for the fsync"))) {
            assertTrue(System.nanoTime() < deadline, "the second SET never applied");
        }

        file.release();
        assertEquals("+OK\r\n", within(set, "SET never returned once force() completed"));
    }

    @Test
    @DisplayName("ALWAYS: writes that arrive during one fsync share the next one")
    void writesDuringAForceShareTheNext() throws Exception {
        Session s = open(FsyncPolicy.ALWAYS);
        file.hold();

        List<Future<String>> sets = new ArrayList<>();
        sets.add(runAsync(s, "SET", "k0", "v"));
        awaitForceEntered(PROMPTLY_SECONDS, "the first write never reached force()");

        int writers = 50;
        for (int i = 1; i < writers; i++) {
            sets.add(runAsync(s, "SET", "k" + i, "v"));
        }
        // A key that is visible has applied, and so has queued its entry: both happen in one
        // hold of the database lock. Checked off this thread, because a design that waited on
        // the disk while holding that lock would block the check itself.
        within(pool.submit(() -> {
            for (int i = 1; i < writers; i++) {
                while (!s.db().keyExists("k" + i)) {
                    Thread.sleep(1);
                }
            }
            return null;
        }), "writes could not apply while another write's fsync was in progress");

        file.release();
        for (Future<String> set : sets) {
            assertEquals("+OK\r\n", within(set, "a SET never returned"));
        }
        // One force for the first write, and one for everything queued behind it.
        assertTrue(file.forces.get() <= 2,
                writers + " writes took " + file.forces.get() + " fsyncs; expected at most 2");
    }

    @Test
    @DisplayName("ALWAYS: a failed fsync means no OK, and every later write is refused")
    void failedForceRefusesWrites() throws Exception {
        Session s = open(FsyncPolicy.ALWAYS);
        file.failWith = new IOException("disk on fire");

        String reply = run(s, "SET", "k", "v");
        assertTrue(reply.startsWith("-ERR AOF write failed"),
                "a write the log does not hold was acknowledged: " + reply);
        assertTrue(s.aof().failed());

        // Refused before it runs, so the database never sees it. Reads still work.
        assertTrue(run(s, "SET", "k2", "v").startsWith("-ERR AOF write failed"));
        assertEquals("$-1\r\n", run(s, "GET", "k2"), "a refused write still changed the database");

        // And close() admits that entries never made it.
        assertThrows(AofWriteFailedException.class, s.aof()::close);
    }

    @Test
    @DisplayName("EVERYSEC: fsyncs at most once a second, however many writes")
    void everysecDoesNotForcePerWrite() throws Exception {
        long start = System.nanoTime();
        Session s = open(FsyncPolicy.EVERYSEC);

        for (int i = 0; i < 1000; i++) {
            assertEquals("+OK\r\n", run(s, "SET", "k" + i, "v"));
        }
        int forces = file.forces.get();
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);

        assertTrue(forces <= seconds + 1, "1000 writes took " + forces + " fsyncs in about " + seconds + "s");
    }

    @Test
    @DisplayName("EVERYSEC: still forces, about a second after a write")
    void everysecStillForces() throws Exception {
        Session s = open(FsyncPolicy.EVERYSEC);
        assertEquals("+OK\r\n", run(s, "SET", "k", "v"));

        awaitForceEntered(3, "a write under EVERYSEC was never forced");
    }

    @Test
    @DisplayName("NO: never forces while running, and forces once on close")
    void noForcesOnlyOnClose() throws Exception {
        Session s = open(FsyncPolicy.NO);
        for (int i = 0; i < 10; i++) {
            assertEquals("+OK\r\n", run(s, "SET", "k" + i, "v"));
        }
        assertEquals(0, file.forces.get());

        s.aof().close();
        assertEquals(1, file.forces.get(), "a clean shutdown must be durable even under NO");
    }

    @Test
    @DisplayName("Offsets keep growing across a rewrite, and writes after it are still acknowledged")
    void offsetsSurviveRewrite() throws Exception {
        Session s = open(FsyncPolicy.ALWAYS);
        long before = s.aof().enqueue(command("SET", "a", "1"));
        s.aof().awaitAcknowledgeable(before);

        s.aof().rewrite(s.db());

        // The same size of entry: if offsets restarted with the new file, these would be equal.
        long after = s.aof().enqueue(command("SET", "b", "2"));
        assertTrue(after > before, "offsets restarted after the rewrite: " + before + " then " + after);
        within(pool.submit(() -> {
            s.aof().awaitAcknowledgeable(after);
            return null;
        }), "a write after the rewrite was never acknowledged");
    }

    @Test
    @DisplayName("close() writes out every queued entry, including ones nobody waited for")
    void closeDrainsTheQueue() throws Exception {
        Session s = open(FsyncPolicy.ALWAYS);
        file.hold();

        s.aof().enqueue(command("SET", "k0", "v0"));
        awaitForceEntered(PROMPTLY_SECONDS, "the first entry never reached force()");
        // The writer is held inside the first force, so these are still queued at close.
        for (int i = 1; i < 100; i++) {
            s.aof().enqueue(command("SET", "k" + i, "v" + i));
        }
        Future<?> closed = pool.submit(() -> {
            s.aof().close();
            return null;
        });
        file.release();
        within(closed, "close() never finished");

        Database restored = new Database();
        try {
            AofManager reopened = new AofManager(dir.resolve("a.aof"), writer, FsyncPolicy.NO);
            reopened.replay(restored, new CommandProcessor(), writer);
            reopened.close();
            for (int i = 0; i < 100; i++) {
                assertEquals("v" + i, restored.get("k" + i), "entry " + i + " was lost at close");
            }
        } finally {
            restored.shutdown();
        }
    }
}
