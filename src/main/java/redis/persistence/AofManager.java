package redis.persistence;

import redis.resp.Value;
import redis.core.CommandProcessor;
import redis.core.Database;
import redis.resp.RespParser;
import redis.resp.RespWriter;
import redis.resp.RespParseException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The append-only file, written with group commit.
 *
 * <p>A write reaches the log in three steps, and only the first runs under the database
 * lock:
 * <ol>
 *   <li>The writing thread, still holding the database monitor, {@link #enqueue}s its entry:
 *       the entry is encoded into an in-memory buffer and given the offset where it ends.
 *       That is memory work only, so the database lock is never held across disk I/O, and
 *       the log holds entries in exactly the order the database applied them.
 *   <li>A single {@code aof-writer} thread takes everything buffered so far, writes it, forces
 *       it to disk when the policy calls for that, and publishes how far it got.
 *   <li>The writing thread, having let go of the database, {@link #awaitAcknowledgeable
 *       waits} until the published offset covers its entry, and only then replies.
 * </ol>
 *
 * <p>Batching falls out of step 2. While the writer is inside one fsync, other writes keep
 * queueing behind it, and its next pass takes them all at once. Under ALWAYS, one fsync
 * acknowledges every write that arrived during the previous one, so the fsync rate stays
 * near what the disk can sustain however many clients are writing.
 *
 * <p>Offsets are logical: they count bytes ever enqueued, not positions in the current file,
 * so they keep growing across a {@link #rewrite} that shrinks the file.
 *
 * <p>Locks are always taken in this order, never the other way round: the database monitor,
 * then {@link #fileLock}, then {@link #state}. {@code state} is only ever held for memory
 * work, never across I/O. {@code fileLock} is held across I/O, but only by the writer thread,
 * {@link #rewrite} and {@link #close} -- never by a thread serving a client.
 */
public class AofManager implements Closeable {
    public enum FsyncPolicy {
        ALWAYS, EVERYSEC, NO
    }

    private static final long EVERYSEC_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final Path aofPath;
    private final RespWriter writer;
    private final FsyncPolicy policy;
    private final LogFile.Opener opener;

    // ---- Guarded by `state`: held for memory work only, never across I/O. ----

    private final ReentrantLock state = new ReentrantLock();

    /** The writer thread waits on this for entries to write, or for close. Nothing else does. */
    private final Condition workArrived = state.newCondition();

    /** Waiting writes, and rewrite, wait on this for the offsets to move or the log to fail. */
    private final Condition progressed = state.newCondition();

    private ByteArrayOutputStream pending = new ByteArrayOutputStream(); // encoded, not yet taken
    private long enqueuedOffset; // where the last enqueued entry ends
    private long writtenOffset;  // everything before this has been handed to the OS
    private long durableOffset;  // everything before this has been forced to disk
    private long lastForceNanos;
    private IOException failure; // set once; nothing is written after it
    private boolean closing;
    // Invariant: durableOffset <= writtenOffset <= enqueuedOffset.

    // ---- Guarded by fileLock: held across I/O, by the writer thread, rewrite and close. ----

    private final Object fileLock = new Object();
    private LogFile file;

    private final Thread writerThread;

    public AofManager(Path aofPath, RespWriter writer, FsyncPolicy policy) throws IOException {
        this(aofPath, writer, policy, FileLogFile::new);
    }

    /** Lets a test stand in for the file, so that force() can be slow, blocked or failing. */
    AofManager(Path aofPath, RespWriter writer, FsyncPolicy policy, LogFile.Opener opener) throws IOException {
        this.aofPath = aofPath;
        this.writer = writer;
        this.policy = policy;
        this.opener = opener;

        Files.createDirectories(aofPath.getParent() == null ? Path.of(".") : aofPath.getParent());
        this.file = opener.open(aofPath);
        this.lastForceNanos = System.nanoTime();

        // A daemon, so a manager nobody closed cannot keep the JVM alive. Started last:
        // Thread.start() makes everything assigned above visible to the new thread.
        this.writerThread = new Thread(this::writeLoop, "aof-writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    /**
     * Adds an entry to the log, in memory, and returns the offset where it ends -- the value
     * to pass to {@link #awaitAcknowledgeable}.
     *
     * <p>Callers hold the database monitor. That is what puts entries in the order the
     * database applied them, and what lets {@link #rewrite} assume nothing new arrives while
     * it holds that monitor itself. So this must never wait on I/O: it encodes into memory
     * and wakes the writer thread, nothing more.
     *
     * @throws AofWriteFailedException if the log has failed or been closed, so the entry
     *                                 would never be written
     */
    public long enqueue(Value entry) throws IOException {
        state.lock();
        try {
            if (failure != null) {
                throw new AofWriteFailedException(failure);
            }
            if (closing) {
                throw new AofWriteFailedException("the AOF is closed");
            }
            int before = pending.size();
            writer.writeRequest(pending, entry); // into memory: no I/O to wait on
            enqueuedOffset += pending.size() - before;
            workArrived.signal();
            return enqueuedOffset;
        } finally {
            state.unlock();
        }
    }

    /**
     * Blocks until the entry ending at {@code offset} may be acknowledged to its client:
     * forced to disk under ALWAYS, handed to the OS under EVERYSEC and NO.
     *
     * <p>Waiting for the OS even when not forcing means an acknowledged write survives a
     * crash of this process, though not a power loss, which is what those policies promise.
     * It also bounds the buffer: a client cannot queue its next write until its last one is
     * out, so the backlog is at most one entry per connection however slow the disk gets.
     *
     * <p>The caller must not hold the database monitor, or every other client waits on this
     * one's disk.
     */
    public void awaitAcknowledgeable(long offset) throws IOException {
        awaitReached(offset, policy == FsyncPolicy.ALWAYS);
    }

    /** {@link #enqueue} and {@link #awaitAcknowledgeable} in one, for a caller holding no lock. */
    public void append(Value entry) throws IOException {
        awaitAcknowledgeable(enqueue(entry));
    }

    /**
     * True once a write or fsync has failed. Nothing reaches the log after that, so a write
     * command has to be refused before it changes anything.
     */
    public boolean failed() {
        state.lock();
        try {
            return failure != null;
        } finally {
            state.unlock();
        }
    }

    /** Blocks until {@code offset} has been handed to the OS or, if {@code durable}, forced. */
    private void awaitReached(long offset, boolean durable) throws IOException {
        state.lock();
        try {
            while (reached(durable) < offset && failure == null) {
                progressed.await();
            }
            if (reached(durable) < offset) {
                // Entries that made it before the failure stand. This one did not.
                throw new AofWriteFailedException(failure);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted waiting for the AOF");
        } finally {
            state.unlock();
        }
    }

    private long reached(boolean durable) { // caller holds state
        return durable ? durableOffset : writtenOffset;
    }

    /** Where the last enqueued entry ends. */
    private long endOfLog() {
        state.lock();
        try {
            return enqueuedOffset;
        } finally {
            state.unlock();
        }
    }

    /**
     * The writer thread: the only thread that appends to the file.
     *
     * <p>Each pass takes everything enqueued so far as one batch. Swapping in an empty buffer
     * is the only part done under {@code state}. The write and the fsync run with it
     * released, so clients keep enqueueing -- into the next batch -- while the disk works.
     */
    private void writeLoop() {
        try {
            while (true) {
                ByteArrayOutputStream batch;
                long batchEnd;
                boolean force;

                state.lock();
                try {
                    while (pending.size() == 0 && !closing && !everysecForceDue(false)) {
                        awaitWork();
                    }
                    if (closing && pending.size() == 0 && durableOffset == writtenOffset) {
                        return; // drained and forced: close() can let go of the file
                    }
                    batch = pending;
                    pending = new ByteArrayOutputStream();
                    batchEnd = enqueuedOffset;
                    // Closing forces whatever the policy, so a clean shutdown is durable
                    // even under NO.
                    force = policy == FsyncPolicy.ALWAYS || closing || everysecForceDue(batch.size() > 0);
                } finally {
                    state.unlock();
                }

                synchronized (fileLock) {
                    if (batch.size() > 0) {
                        file.append(batch.toByteArray());
                    }
                    if (force) {
                        file.force();
                    }
                }

                state.lock();
                try {
                    writtenOffset = batchEnd;
                    if (force) {
                        durableOffset = batchEnd;
                        lastForceNanos = System.nanoTime();
                    }
                    progressed.signalAll(); // each waiter checks its own offset
                } finally {
                    state.unlock();
                }
            }
        } catch (Throwable t) {
            // Whatever stopped this thread, nothing is written after it. Every waiter has to
            // hear that, or it waits forever for an offset that is never coming.
            state.lock();
            try {
                failure = (t instanceof IOException io) ? io : new IOException("AOF writer stopped: " + t, t);
                progressed.signalAll();
            } finally {
                state.unlock();
            }
            System.err.println("[AOF] Writing the log failed; refusing further writes: " + t);
        }
    }

    /** Sleeps until there is something to do. Caller holds state. */
    private void awaitWork() throws InterruptedException {
        if (policy == FsyncPolicy.EVERYSEC && writtenOffset > durableOffset) {
            // Written data is due its once-a-second fsync, so wake for that even if no new
            // write arrives in the meantime.
            workArrived.awaitNanos(EVERYSEC_INTERVAL_NANOS - (System.nanoTime() - lastForceNanos));
        } else {
            workArrived.await();
        }
    }

    /** EVERYSEC: something is not yet forced, and a second has passed since the last force. */
    private boolean everysecForceDue(boolean moreToWrite) { // caller holds state
        return policy == FsyncPolicy.EVERYSEC
                && (moreToWrite || writtenOffset > durableOffset)
                && System.nanoTime() - lastForceNanos >= EVERYSEC_INTERVAL_NANOS;
    }

    /**
     * Replaces the log with the shortest one that reproduces the current dataset.
     *
     * <p>The AOF is a history, so its size tracks writes performed rather than data held:
     * ten million INCRs against one counter are ten million entries to store and to
     * replay at startup. Compaction writes what the data <em>is</em> instead of how it got
     * that way.
     *
     * <p>The new log is built in a sibling file, forced to disk, and then moved over the
     * old one under ATOMIC_MOVE, so a crash at any point leaves either the complete old
     * log or the complete new one -- never a half-written file that replay would truncate
     * at.
     *
     * <p>Holding the database monitor throughout means no write can apply, and so nothing
     * new can be enqueued, until the new log is in place. Before swapping files it waits for
     * the writer to hand everything already enqueued to the old file. An entry still queued
     * at the swap would land in the new log after the snapshot, which already holds its
     * effect, and replay would apply it twice: an INCR counted twice.
     *
     * <p>Locks are taken database, then file, then state, the same order as everywhere else.
     */
    public void rewrite(Database db) throws IOException {
        synchronized (db) {
            // The database monitor keeps endOfLog() from moving while this waits.
            awaitReached(endOfLog(), false);

            synchronized (fileLock) {
                List<List<String>> commands = db.dumpAsCommands();

                Path temp = aofPath.resolveSibling(aofPath.getFileName() + ".rewrite");
                try (FileOutputStream tempFile = new FileOutputStream(temp.toFile(), false)) {
                    BufferedOutputStream buffered = new BufferedOutputStream(tempFile, 64 * 1024);
                    for (List<String> command : commands) {
                        List<Value> args = new ArrayList<>(command.size());
                        for (String arg : command) {
                            args.add(new Value("bulk", arg));
                        }
                        writer.writeRequest(buffered, new Value("array", args));
                    }
                    buffered.flush();
                    tempFile.getChannel().force(false); // durable before it becomes the real log
                }

                file.close(); // releases the old file before the move replaces it
                try {
                    Files.move(temp, aofPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    file = opener.open(aofPath); // the new log -- or the old one, if the move failed
                }

                state.lock();
                try {
                    // The new file holds the effect of every entry enqueued so far, already
                    // forced. Offsets carry on from here rather than starting again.
                    writtenOffset = enqueuedOffset;
                    durableOffset = enqueuedOffset;
                    lastForceNanos = System.nanoTime();
                    progressed.signalAll();
                } finally {
                    state.unlock();
                }
            }
        }
    }

    /**
     * Replay AOF file at startup (no re-append).
     */
    public void replay(Database db, CommandProcessor processor, RespWriter respWriter) throws IOException {
        if (!Files.exists(aofPath)) {
            System.out.println("[AOF] No AOF file found at " + aofPath + ", skipping replay.");
            return;
        }

        System.out.println("[AOF] Starting replay from " + aofPath);
        int commandsReplayed = 0;
        int commandsSkipped = 0;

        try (InputStream in = new BufferedInputStream(Files.newInputStream(aofPath), 64 * 1024);
                OutputStream nullOut = OutputStream.nullOutputStream()) {

            while (true) {
                Value req;
                try {
                    req = RespParser.readValue(in);
                } catch (EOFException e) {
                    // Truncated tail: the process died mid-append. Everything before this
                    // point is intact, so keep what we have.
                    System.err.println("[AOF] Truncated final entry, stopping replay: " + e.getMessage());
                    break;
                } catch (RespParseException e) {
                    System.err.println("[AOF] Corrupted entry detected, stopping replay: " + e.getMessage());
                    break;
                }

                if (req == null) {
                    break; // EOF
                }

                if (!"array".equals(req.typ) || req.array == null || req.array.isEmpty()) {
                    commandsSkipped++;
                    continue;
                }

                String cmdName = req.array.get(0).str.toUpperCase(Locale.ROOT);

                try {
                    // fromReplay=true => do not append again
                    processor.executeCommand(cmdName, db, respWriter, nullOut, req.array, true);
                    commandsReplayed++;
                } catch (Exception e) {
                    // Log the error but continue replaying remaining commands
                    System.err.println("[AOF] Failed to replay command '" + cmdName + "': " + e.getMessage());
                    commandsSkipped++;
                }
            }
        }

        System.out.println("[AOF] Replay complete. Commands replayed: " + commandsReplayed +
                (commandsSkipped > 0 ? ", skipped: " + commandsSkipped : ""));
    }

    /**
     * Writes and forces everything enqueued, then lets go of the file. That holds under
     * every policy, NO included, so a clean shutdown loses nothing.
     *
     * @throws AofWriteFailedException if the writer had failed with entries still unwritten
     */
    @Override
    public void close() throws IOException {
        state.lock();
        try {
            closing = true; // enqueue refuses from here on
            workArrived.signal();
        } finally {
            state.unlock();
        }

        try {
            writerThread.join(); // it drains and forces before it exits
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted closing the AOF");
        } finally {
            synchronized (fileLock) {
                file.close();
            }
        }

        state.lock();
        try {
            if (failure != null && durableOffset < enqueuedOffset) {
                throw new AofWriteFailedException(failure);
            }
        } finally {
            state.unlock();
        }
    }
}
