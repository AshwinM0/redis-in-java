package redis.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.resp.RespWriter;
import redis.resp.Value;
import redis.persistence.AofManager.FsyncPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AofManagerTest {

    private Path tempAof;
    private AofManager aofManager;
    private RespWriter writer;

    @BeforeEach
    void setUp() throws IOException {
        tempAof = Files.createTempFile("redis-aof", ".aof");
        writer = new RespWriter();
        // Use EVERYSEC to verify scheduling
        aofManager = new AofManager(tempAof, writer, FsyncPolicy.EVERYSEC);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (aofManager != null) {
            aofManager.close();
        }
        Files.deleteIfExists(tempAof);
    }

    @Test
    void testAppend() throws IOException, InterruptedException {
        Value req = new Value("array", List.of(
                new Value("bulk", "SET"),
                new Value("bulk", "key"),
                new Value("bulk", "value")));

        aofManager.append(req);

        Thread.sleep(1500);

        List<String> lines = Files.readAllLines(tempAof);
        // Expecting: *3, $3, SET, $3, key, $5, value
        // *3
        // $3
        // SET
        // $3
        // key
        // $5
        // value
        // Total 7 lines

        assertFalse(lines.isEmpty(), "AOF file should not be empty");
        assertTrue(lines.size() >= 7, "AOF file should have at least 7 lines");
        assertEquals("*3", lines.get(0));
        assertEquals("$3", lines.get(1));
        assertEquals("SET", lines.get(2));
    }
}
