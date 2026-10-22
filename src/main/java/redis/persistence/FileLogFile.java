package redis.persistence;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/** The real {@link LogFile}: a file opened for appending, and its channel for fsync. */
final class FileLogFile implements LogFile {

    private final FileOutputStream out;
    private final FileChannel channel;

    FileLogFile(Path path) throws IOException {
        this.out = new FileOutputStream(path.toFile(), true); // append mode
        // FileOutputStream exposes the FileChannel, which is what force() needs to push
        // bytes past the OS page cache onto the disk.
        this.channel = out.getChannel();
    }

    /**
     * No buffering of its own. The log hands over a whole batch at a time, so this is one
     * write system call per batch rather than one per entry.
     */
    @Override
    public void append(byte[] bytes) throws IOException {
        out.write(bytes);
    }

    /**
     * {@code force(false)} is the equivalent of {@code fdatasync(2)}: it syncs file contents
     * and the size metadata needed to read them back, but skips unrelated metadata such as
     * mtime. This is what Redis itself uses for the AOF.
     */
    @Override
    public void force() throws IOException {
        channel.force(false);
    }

    @Override
    public void close() throws IOException {
        out.close(); // closes the channel too
    }
}
