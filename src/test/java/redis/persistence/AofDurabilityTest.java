package redis.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import redis.core.CommandProcessor;
import redis.core.Database;
import redis.persistence.AofManager.FsyncPolicy;
import redis.resp.Protocol;
import redis.resp.RespWriter;
import redis.resp.Value;

/**
 * Durability and byte-fidelity of the append-only file.
 *
 * <p>Scope note: no unit test can prove an {@code fsync} reached the platter — that needs
 * a real power cut. What these tests pin down is that the bytes on disk are byte-exact,
 * that a replay restores them unchanged, and that the write-through paths (ALWAYS, and
 * close()) actually put the bytes in the file rather than leaving them in a buffer.
 */
class AofDurabilityTest {

    private Path aofPath;
    private final RespWriter writer = new RespWriter();

    @BeforeEach
    void setUp() throws IOException {
        aofPath = Files.createTempFile("redis-aof-durability", ".aof");
        Files.delete(aofPath); // let AofManager create it
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(aofPath);
    }

    /** Builds a command Value the way the parser would: args are byte-strings. */
    private static Value command(String... utf8Args) {
        List<Value> args = new ArrayList<>();
        for (String a : utf8Args) {
            args.add(new Value("bulk", Protocol.fromWire(a.getBytes(StandardCharsets.UTF_8))));
        }
        return new Value("array", args);
    }

    private static byte[] expectedWire(String... utf8Args) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(("*" + utf8Args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String a : utf8Args) {
            byte[] bytes = a.getBytes(StandardCharsets.UTF_8);
            b.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            b.write(bytes);
            b.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        return b.toByteArray();
    }

    @Test
    @DisplayName("ALWAYS writes through on every append, before close")
    void alwaysPolicyWritesThroughImmediately() throws Exception {
        AofManager aof = new AofManager(aofPath, writer, FsyncPolicy.ALWAYS);
        try {
            aof.append(command("SET", "name", "Jos\u00e9"));
            // Read via a completely independent handle, without closing the manager.
            assertArrayEquals(expectedWire("SET", "name", "Jos\u00e9"), Files.readAllBytes(aofPath));
        } finally {
            aof.close();
        }
    }

    @Test
    @DisplayName("close() flushes and syncs, so a clean shutdown is durable under NO")
    void closeIsDurableUnderPolicyNo() throws Exception {
        AofManager aof = new AofManager(aofPath, writer, FsyncPolicy.NO);
        aof.append(command("SET", "k", "v"));
        aof.close();

        assertArrayEquals(expectedWire("SET", "k", "v"), Files.readAllBytes(aofPath));
    }

    @Test
    @DisplayName("a non-ASCII value survives an append/replay cycle byte-for-byte")
    void replayRestoresNonAsciiExactly() throws Exception {
        String value = "Jos\u00e9 \u2615 \ud83d\ude80";

        AofManager aof = new AofManager(aofPath, writer, FsyncPolicy.ALWAYS);
        aof.append(command("SET", "name", value));
        aof.close();

        Database db = new Database();
        try {
            AofManager reopened = new AofManager(aofPath, writer, FsyncPolicy.NO);
            reopened.replay(db, new CommandProcessor(), writer);
            reopened.close();

            String restored = db.get(Protocol.fromWire("name".getBytes(StandardCharsets.UTF_8)));
            assertArrayEquals(value.getBytes(StandardCharsets.UTF_8), Protocol.toWire(restored),
                    "the restored value must be the same bytes that were stored");
        } finally {
            db.shutdown();
        }
    }

    @Test
    @DisplayName("binary payloads with embedded CRLF replay correctly")
    void replayHandlesEmbeddedCrlf() throws Exception {
        // A value that contains the protocol's own delimiter: only an honest byte count
        // can get this back.
        String value = Protocol.fromWire(new byte[] { 'a', '\r', '\n', 0, (byte) 0xFF, 'b' });

        List<Value> args = List.of(
                new Value("bulk", "SET"), new Value("bulk", "bin"), new Value("bulk", value));

        AofManager aof = new AofManager(aofPath, writer, FsyncPolicy.ALWAYS);
        aof.append(new Value("array", args));
        aof.close();

        Database db = new Database();
        try {
            AofManager reopened = new AofManager(aofPath, writer, FsyncPolicy.NO);
            reopened.replay(db, new CommandProcessor(), writer);
            reopened.close();

            assertArrayEquals(Protocol.toWire(value), Protocol.toWire(db.get("bin")));
        } finally {
            db.shutdown();
        }
    }

    @Test
    @DisplayName("a torn final write is tolerated; earlier commands still replay")
    void tolerateTruncatedTail() throws Exception {
        AofManager aof = new AofManager(aofPath, writer, FsyncPolicy.ALWAYS);
        aof.append(command("SET", "a", "1"));
        aof.append(command("SET", "b", "2"));
        aof.close();

        // Simulate a crash midway through a third append.
        byte[] partial = expectedWire("SET", "c", "3");
        Files.write(aofPath, java.util.Arrays.copyOf(partial, partial.length / 2),
                StandardOpenOption.APPEND);

        Database db = new Database();
        try {
            AofManager reopened = new AofManager(aofPath, writer, FsyncPolicy.NO);
            reopened.replay(db, new CommandProcessor(), writer);
            reopened.close();

            assertEquals("1", db.get("a"));
            assertEquals("2", db.get("b"));
            assertNull(db.get("c"), "the torn command must not be applied");
        } finally {
            db.shutdown();
        }
    }

    @Test
    @DisplayName("appends are byte-identical to what a client would have sent")
    void appendMatchesClientWireFormat() throws Exception {
        AofManager aof = new AofManager(aofPath, writer, FsyncPolicy.ALWAYS);
        try (OutputStream ignored = OutputStream.nullOutputStream()) {
            aof.append(command("HSET", "user", "name", "\u00c5sa"));
        } finally {
            aof.close();
        }
        assertArrayEquals(expectedWire("HSET", "user", "name", "\u00c5sa"),
                Files.readAllBytes(aofPath));
    }
}
