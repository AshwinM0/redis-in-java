package redis.persistence;

import java.io.IOException;

/**
 * The append-only file has stopped taking entries: a write or an fsync failed, or the log
 * was closed. An entry that had not been acknowledged by then may never reach the disk.
 *
 * <p>It is an {@link IOException} because that is what it is, which also keeps the existing
 * {@code throws IOException} signatures true. {@code CommandProcessor} catches it by name, so
 * the client hears an error reply instead of the dropped connection that a failure on its
 * own socket means.
 */
public class AofWriteFailedException extends IOException {

    public AofWriteFailedException(String message) {
        super(message);
    }

    public AofWriteFailedException(IOException cause) {
        super(cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName(), cause);
    }
}
