package redis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import redis.core.CommandProcessor;
import redis.core.Database;
import redis.mocks.FakeClock;
import redis.resp.Protocol;
import redis.resp.RespWriter;
import redis.resp.Value;

/** What the log records, and how large it is allowed to get. */
class AofFidelityTest {

    private static final long START = 1_700_000_000_000L;

    @TempDir
    Path dir;

    private final RespWriter writer = new RespWriter();

    private record Session(Database db, AofManager aof, CommandProcessor processor, FakeClock clock) {
    }

    private Session open(Path aofPath, long startMillis) throws IOException {
        FakeClock clock = new FakeClock(startMillis);
        Database db = new Database(clock);
        AofManager aof = new AofManager(aofPath, writer, AofManager.FsyncPolicy.ALWAYS);
        return new Session(db, aof, new CommandProcessor(null, aof), clock);
    }

    private String run(Session s, String... parts) throws IOException {
        List<Value> args = new ArrayList<>();
        for (String p : parts) {
            args.add(new Value("bulk", p));
        }
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        s.processor().executeCommand(parts[0].toUpperCase(Locale.ROOT), s.db(), writer, reply, args, false);
        return reply.toString(Protocol.WIRE);
    }

    private void close(Session s) throws IOException {
        s.aof().close();
        s.db().shutdown();
    }

    private long entries(Path aofPath) throws IOException {
        return Files.readString(aofPath, Protocol.WIRE).chars().filter(c -> c == '*').count();
    }

    @Test
    @DisplayName("A command that replied with an error is not logged")
    void rejectedCommandsAreNotLogged() throws IOException {
        Path aofPath = dir.resolve("a.aof");
        Session s = open(aofPath, START);

        run(s, "SET", "s", "v");
        assertTrue(run(s, "HSET", "s", "f", "v").startsWith("-ERR"), "expected WRONGTYPE");
        assertTrue(run(s, "SET", "s", "v3", "GARBAGE").startsWith("-ERR"), "expected syntax error");
        close(s);

        String log = Files.readString(aofPath, Protocol.WIRE);
        assertFalse(log.contains("HSET"), "a rejected HSET was logged");
        assertFalse(log.contains("GARBAGE"), "a rejected SET was logged");
        assertTrue(log.contains("SET"), "the write that succeeded is missing");
    }

    @Test
    @DisplayName("Compaction collapses a key's history to its current value")
    void rewriteCollapsesHistory() throws IOException {
        Path aofPath = dir.resolve("a.aof");
        Session s = open(aofPath, START);

        run(s, "SET", "counter", "0");
        for (int i = 0; i < 500; i++) {
            run(s, "INCR", "counter");
        }
        assertTrue(entries(aofPath) > 500, "expected the history to be long");

        run(s, "BGREWRITEAOF");
        assertEquals(1, entries(aofPath), "the whole history should collapse to one SET");
        close(s);

        Session restarted = open(aofPath, START);
        restarted.aof().replay(restarted.db(), restarted.processor(), writer);
        assertEquals("500", restarted.db().get("counter"), "compaction changed the data");
        close(restarted);
    }

    @Test
    @DisplayName("Compaction preserves hashes and absolute deadlines")
    void rewritePreservesEverything() throws IOException {
        Path aofPath = dir.resolve("a.aof");
        Session s = open(aofPath, START);

        run(s, "SET", "plain", "value");
        run(s, "SET", "living", "v", "EX", "600");
        run(s, "HSET", "h", "f1", "v1", "f2", "v2");
        run(s, "EXPIRE", "h", "600");
        long livingDeadline = s.db().getExpiry("living");
        long hashDeadline = s.db().getExpiry("h");

        run(s, "BGREWRITEAOF");
        close(s);

        // Restart a minute later: the deadlines are absolute, so they must not move.
        Session restarted = open(aofPath, START + 60_000L);
        restarted.aof().replay(restarted.db(), restarted.processor(), writer);

        assertEquals("value", restarted.db().get("plain"));
        assertEquals("v", restarted.db().get("living"));
        assertEquals(livingDeadline, restarted.db().getExpiry("living"));
        assertEquals(2, restarted.db().hlen("h"));
        assertEquals("v1", restarted.db().hashget("h", "f1"));
        assertEquals(hashDeadline, restarted.db().getExpiry("h"));
        close(restarted);
    }

    @Test
    @DisplayName("Compaction drops keys that have already expired")
    void rewriteOmitsDeadKeys() throws IOException {
        Path aofPath = dir.resolve("a.aof");
        Session s = open(aofPath, START);

        run(s, "SET", "ghost", "v", "EX", "10");
        run(s, "SET", "alive", "v");
        s.clock().advanceSeconds(3600);

        run(s, "BGREWRITEAOF");
        assertEquals(1, entries(aofPath), "the expired key was carried into the new log");
        close(s);

        Session restarted = open(aofPath, START + 3600_000L);
        restarted.aof().replay(restarted.db(), restarted.processor(), writer);
        assertNull(restarted.db().get("ghost"));
        assertEquals("v", restarted.db().get("alive"));
        close(restarted);
    }

    @Test
    @DisplayName("Writes after a compaction still land in the new log")
    void appendsContinueAfterRewrite() throws IOException {
        Path aofPath = dir.resolve("a.aof");
        Session s = open(aofPath, START);

        run(s, "SET", "before", "1");
        run(s, "BGREWRITEAOF");
        run(s, "SET", "after", "2");
        close(s);

        Session restarted = open(aofPath, START);
        restarted.aof().replay(restarted.db(), restarted.processor(), writer);
        assertEquals("1", restarted.db().get("before"));
        assertEquals("2", restarted.db().get("after"), "the reopened log was not being written to");
        close(restarted);
    }
}
