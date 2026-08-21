package redis.persistence;

import redis.resp.Value;
import redis.core.CommandProcessor;
import redis.core.Database;
import redis.resp.RespParser;
import redis.resp.RespWriter;
import redis.resp.RespParseException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AofManager implements Closeable {
    public enum FsyncPolicy {
        ALWAYS, EVERYSEC, NO
    }

    private final Path aofPath;
    private final RespWriter writer;
    private final BufferedOutputStream out;
    private final FileChannel channel;
    private final FsyncPolicy policy;

    private final ScheduledExecutorService fsyncScheduler;
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    public AofManager(Path aofPath, RespWriter writer, FsyncPolicy policy) throws IOException {
        this.aofPath = aofPath;
        this.writer = writer;
        this.policy = policy;

        Files.createDirectories(aofPath.getParent() == null ? Path.of(".") : aofPath.getParent());

        // FileOutputStream exposes the FileChannel, which is what fsync() needs to push
        // bytes past the OS page cache onto the disk.
        FileOutputStream fos = new FileOutputStream(aofPath.toFile(), true);
        this.channel = fos.getChannel();
        this.out = new BufferedOutputStream(fos, 64 * 1024);

        if (policy == FsyncPolicy.EVERYSEC) {
            fsyncScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "aof-fsync");
                t.setDaemon(true);
                return t;
            });
            fsyncScheduler.scheduleAtFixedRate(this::fsyncIfDirty, 1, 1, TimeUnit.SECONDS);
        } else {
            fsyncScheduler = null;
        }
    }

    public synchronized void append(Object requestArrayObj) throws IOException {
        // write request array as RESP to AOF
        Value requestArray = (Value) requestArrayObj;
        writer.writeRequest(out, requestArray);
        out.flush(); // hand the bytes to the OS
        dirty.set(true);

        if (policy == FsyncPolicy.ALWAYS) {
            fsync(); // ...and, under ALWAYS, all the way to the platter
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

                String cmdName = req.array.get(0).str.toUpperCase();

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

    private void fsyncIfDirty() {
        if (!dirty.getAndSet(false))
            return;
        try {
            fsync();
        } catch (IOException e) {
            // Put the flag back so the next tick retries rather than silently losing it.
            dirty.set(true);
            System.err.println("[AOF] Failed to fsync AOF file: " + e.getMessage());
        }
    }

    /**
     * Flush the application buffer to the OS, then force the OS to write its page cache
     * through to the storage device. Only after {@link FileChannel#force} returns is the
     * data guaranteed to survive a power loss.
     *
     * <p>{@code force(false)} is the equivalent of {@code fdatasync(2)}: it syncs file
     * contents and the size metadata needed to read them back, but skips unrelated
     * metadata such as mtime. This is what Redis itself uses for the AOF.
     */
    private synchronized void fsync() throws IOException {
        out.flush();
        channel.force(false);
    }

    @Override
    public void close() throws IOException {
        if (fsyncScheduler != null) {
            fsyncScheduler.shutdownNow();
        }
        synchronized (this) {
            try {
                fsync(); // durable before we let go of the handle
            } finally {
                out.close(); // closes the underlying FileOutputStream and its channel
            }
        }
    }
}
