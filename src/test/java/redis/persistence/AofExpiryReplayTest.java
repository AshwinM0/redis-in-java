package redis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/**
 * The AOF is replayed at an unknown later time, so what it stores has to mean the same
 * thing then as it did when it was written. A relative TTL does not.
 */
class AofExpiryReplayTest {

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

    @Test
    @DisplayName("A key whose TTL lapsed while the server was down stays dead through replay")
    void relativeTtlIsNotRecomputedAgainstTheRestartClock() throws IOException {
        Path aofPath = dir.resolve("appendonly.aof");

        Session first = open(aofPath, START);
        run(first, "SET", "session", "tok", "EX", "60");
        run(first, "SET", "perm", "val");
        run(first, "EXPIRE", "perm", "30");
        close(first);

        // The process is down for ten hours. Both keys should have died minutes in.
        Session second = open(aofPath, START + 10 * 3600 * 1000L);
        second.aof().replay(second.db(), second.processor(), writer);

        assertNull(second.db().get("session"), "an expired key came back from the AOF");
        assertNull(second.db().get("perm"));
        assertEquals(-2, second.db().ttl("session"), "the TTL restarted from the replay clock");
        assertEquals(-2, second.db().ttl("perm"));
        close(second);
    }

    @Test
    @DisplayName("A TTL still running at restart keeps its original deadline")
    void aLiveTtlKeepsItsDeadlineAcrossRestart() throws IOException {
        Path aofPath = dir.resolve("appendonly.aof");

        Session first = open(aofPath, START);
        run(first, "SET", "k", "v", "EX", "600");
        long deadline = first.db().getExpiry("k");
        close(first);

        // Back up one minute later: 540s should be left, not a fresh 600.
        Session second = open(aofPath, START + 60_000L);
        second.aof().replay(second.db(), second.processor(), writer);

        assertEquals("v", second.db().get("k"));
        assertEquals(deadline, second.db().getExpiry("k"), "the deadline moved with the restart");
        assertEquals(540, second.db().ttl("k"));
        close(second);
    }

    @Test
    @DisplayName("Sub-second TTLs survive the round trip")
    void millisecondPrecisionIsPreserved() throws IOException {
        Path aofPath = dir.resolve("appendonly.aof");

        Session first = open(aofPath, START);
        run(first, "SET", "k", "v", "PX", "1500");
        close(first);

        Session second = open(aofPath, START + 500L);
        second.aof().replay(second.db(), second.processor(), writer);

        assertEquals("v", second.db().get("k"));
        assertEquals(1000, second.db().pttl("k"));
        close(second);
    }

    @Test
    @DisplayName("A SET declined by NX is not logged, so replay cannot apply it")
    void aDeclinedSetIsNotLogged() throws IOException {
        Path aofPath = dir.resolve("appendonly.aof");

        Session first = open(aofPath, START);
        run(first, "SET", "k", "original");
        assertEquals("$-1\r\n", run(first, "SET", "k", "replacement", "NX"));
        close(first);

        String log = Files.readString(aofPath, Protocol.WIRE);
        assertTrue(log.contains("original"), "the write that happened was not logged");
        assertTrue(!log.contains("replacement"),
                "a SET that declined to write was logged, and would overwrite on replay");

        Session second = open(aofPath, START);
        second.aof().replay(second.db(), second.processor(), writer);
        assertEquals("original", second.db().get("k"));
        close(second);
    }

    @Test
    @DisplayName("EXPIRE with a past deadline replays as a delete, not a resurrection")
    void expireThatDeletedReplaysAsDelete() throws IOException {
        Path aofPath = dir.resolve("appendonly.aof");

        Session first = open(aofPath, START);
        run(first, "SET", "k", "v");
        run(first, "EXPIRE", "k", "0"); // deletes immediately
        assertNull(first.db().get("k"));
        close(first);

        Session second = open(aofPath, START);
        second.aof().replay(second.db(), second.processor(), writer);
        assertNull(second.db().get("k"), "the key came back through replay");
        close(second);
    }
}
