package redis.persistence;

import redis.resp.Value;
import redis.core.CommandProcessor;
import redis.core.Database;
import redis.resp.RespParser;
import redis.resp.RespWriter;
import redis.resp.RespParseException;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class AofManager implements Closeable {
    public enum FsyncPolicy {
        ALWAYS, EVERYSEC, NO
    }

    private final Path aofPath;
    private final RespWriter writer;
    private final BufferedOutputStream out;
    private final FsyncPolicy policy;

    private final ScheduledExecutorService fsyncScheduler;
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    public AofManager(Path aofPath, RespWriter writer, FsyncPolicy policy) throws IOException {
        this.aofPath = aofPath;
        this.writer = writer;
        this.policy = policy;

        Files.createDirectories(aofPath.getParent() == null ? Path.of(".") : aofPath.getParent());
        OutputStream fos = Files.newOutputStream(aofPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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
        out.flush();
        dirty.set(true);

        if (policy == FsyncPolicy.ALWAYS) {
            fsync();
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

        try (BufferedReader in = Files.newBufferedReader(aofPath);
                BufferedWriter nullOut = new BufferedWriter(new OutputStreamWriter(OutputStream.nullOutputStream()))) {

            while (true) {
                Value req;
                try {
                    req = RespParser.readValue(in);
                } catch (EOFException e) {
                    // Normal end of file
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
        } catch (IOException ignored) {
            System.err.println("[AOF] Failed to fsync AOF file: " + ignored.getMessage());
        }
    }

    private synchronized void fsync() throws IOException {
        // Force OS flush to disk
        if (out instanceof FilterOutputStream) {
            out.flush();
        }
        // fsync needs underlying FileChannel; easiest: open a FileOutputStream instead
        // of NIO stream.
        // Option A: use FileOutputStream and call getFD().sync()
        // Option B: keep a FileChannel ref and force(true)
    }

    @Override
    public void close() throws IOException {
        if (fsyncScheduler != null) {
            fsyncScheduler.shutdownNow();
        }
        synchronized (this) {
            out.flush();
            // fsync on close
            out.close();
        }
    }
}
