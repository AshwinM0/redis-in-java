package redis.persistence;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The file under the append-only log, cut down to the two things the log asks of it.
 *
 * <p>It exists so tests can stand in for the disk, the way {@code Clock} stands in for time:
 * a real disk will not make {@code force()} hang, or fail, on cue, and those are exactly the
 * cases group commit has to get right.
 */
interface LogFile extends Closeable {

    /** Hands {@code bytes} to the operating system, at the end of the file. */
    void append(byte[] bytes) throws IOException;

    /**
     * Returns once everything appended before the call is on the storage device.
     *
     * <p>That covers every earlier append, whoever made it -- which is the whole reason one
     * force can acknowledge a batch of writes.
     */
    void force() throws IOException;

    /** How the log opens its file: on start-up, and again after a rewrite replaces it. */
    @FunctionalInterface
    interface Opener {
        LogFile open(Path path) throws IOException;
    }
}
