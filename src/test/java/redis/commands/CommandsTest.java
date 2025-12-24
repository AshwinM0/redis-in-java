package redis.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

public class CommandsTest {

    private Database db;
    private RespWriter writer;
    private ByteArrayOutputStream outputStream;
    private BufferedWriter out;

    @BeforeEach
    void setUp() {
        db = new Database();
        writer = new RespWriter();
        outputStream = new ByteArrayOutputStream();
        out = new BufferedWriter(new OutputStreamWriter(outputStream));
    }

    private String execute(Command cmd, String... args) throws IOException {
        outputStream.reset();
        List<Value> valueArgs = new ArrayList<>();
        for (String arg : args) {
            valueArgs.add(new Value("bulk", arg));
        }

        cmd.execute(db, writer, out, valueArgs);
        out.flush();
        return outputStream.toString();
    }

    @Nested
    @DisplayName("String Commands")
    class StringCommands {

        @Test
        void testSetAndGet() throws IOException {
            execute(new SetCommand(), "SET", "key", "value");
            assertEquals("+OK\r\n", execute(new SetCommand(), "SET", "key", "value")); // Re-run to check output logic
                                                                                       // purely

            // Check DB state
            assertEquals("value", db.get("key"));

            String output = execute(new GetCommand(), "GET", "key");
            assertEquals("$5\r\nvalue\r\n", output);
        }

        @Test
        void testSetOptions() throws IOException {
            // SET key val NX (should set)
            String out1 = execute(new SetCommand(), "SET", "nxkey", "val", "NX");
            assertEquals("+OK\r\n", out1);
            assertEquals("val", db.get("nxkey"));

            // SET key val NX (should fail)
            outputStream.reset();
            String out2 = execute(new SetCommand(), "SET", "nxkey", "newval", "NX");
            assertEquals("$-1\r\n", out2);
            assertEquals("val", db.get("nxkey"));

            // SET key val XX (should fail)
            outputStream.reset();
            String out3 = execute(new SetCommand(), "SET", "missing", "val", "XX");
            assertEquals("$-1\r\n", out3);

            // SET key val XX (should succeed)
            db.set("xxkey", "old");
            outputStream.reset();
            String out4 = execute(new SetCommand(), "SET", "xxkey", "new", "XX");
            assertEquals("+OK\r\n", out4);
            assertEquals("new", db.get("xxkey"));
        }

        @Test
        void testSetnx() throws IOException {
            String out1 = execute(new SetnxCommand(), "SETNX", "k", "v");
            assertEquals(":1\r\n", out1);

            outputStream.reset();
            String out2 = execute(new SetnxCommand(), "SETNX", "k", "v2");
            assertEquals(":0\r\n", out2);
        }

        @Test
        void testAppend() throws IOException {
            db.set("key", "Hello");
            String out = execute(new AppendCommand(), "APPEND", "key", " World");
            assertEquals(":11\r\n", out);
            assertEquals("Hello World", db.get("key"));
        }

        @Test
        void testStrlen() throws IOException {
            db.set("key", "Hello");
            String out = execute(new StrlenCommand(), "STRLEN", "key");
            assertEquals(":5\r\n", out);
        }

        @Test
        void testIncrDecr() throws IOException {
            db.set("cnt", "10");

            assertEquals(":11\r\n", execute(new IncrCommand(), "INCR", "cnt"));
            outputStream.reset();
            assertEquals(":10\r\n", execute(new DecrCommand(), "DECR", "cnt"));
        }

        @Test
        void testIncrByDecrBy() throws IOException {
            db.set("cnt", "10");

            assertEquals(":15\r\n", execute(new IncrByCommand(), "INCRBY", "cnt", "5"));
            outputStream.reset();
            assertEquals(":5\r\n", execute(new DecrByCommand(), "DECRBY", "cnt", "10"));
        }

        @Test
        void testGetRange() throws IOException {
            db.set("key", "Hello World");
            assertEquals("$5\r\nHello\r\n", execute(new GetRangeCommand(), "GETRANGE", "key", "0", "4"));
        }

        @Test
        void testGetSet() throws IOException {
            db.set("key", "old");
            String out = execute(new GetSetCommand(), "GETSET", "key", "new");
            assertEquals("$3\r\nold\r\n", out);
            assertEquals("new", db.get("key"));
        }

        @Test
        void testMSetMGet() throws IOException {
            execute(new MSetCommand(), "MSET", "k1", "v1", "k2", "v2");
            assertEquals("v1", db.get("k1"));
            assertEquals("v2", db.get("k2"));

            outputStream.reset();
            String out = execute(new MGetCommand(), "MGET", "k1", "k2", "missing");
            // *3\r\n$2\r\nv1\r\n$2\r\nv2\r\n$-1\r\n
            assertTrue(out.contains("*3\r\n"));
            assertTrue(out.contains("$2\r\nv1\r\n"));
        }
    }

    @Nested
    @DisplayName("Key Commands")
    class KeyCommands {
        @Test
        void testDel() throws IOException {
            db.set("k", "v");
            assertEquals(":1\r\n", execute(new DelCommand(), "DEL", "k"));
            assertEquals(":0\r\n", execute(new DelCommand(), "DEL", "k"));
        }

        @Test
        void testExists() throws IOException {
            db.set("k", "v");
            assertEquals(":1\r\n", execute(new ExistsCommand(), "EXISTS", "k"));
            assertEquals(":0\r\n", execute(new ExistsCommand(), "EXISTS", "missing"));
        }

        @Test
        void testExpireTTL() throws IOException {
            db.set("k", "v");
            assertEquals(":1\r\n", execute(new ExpireCommand(), "EXPIRE", "k", "10"));

            outputStream.reset();
            String ttl = execute(new TTLCommand(), "TTL", "k");
            assertTrue(ttl.startsWith(":"));
        }

        @Test
        void testType() throws IOException {
            db.set("s", "v");
            db.hset("h", "f", "v");

            assertEquals("+string\r\n", execute(new TypeCommand(), "TYPE", "s"));
            outputStream.reset();
            assertEquals("+hash\r\n", execute(new TypeCommand(), "TYPE", "h"));
            outputStream.reset();
            assertEquals("+none\r\n", execute(new TypeCommand(), "TYPE", "missing"));
        }

        @Test
        void testKeys() throws IOException {
            db.set("abc", "1");
            db.set("abd", "2");
            String out = execute(new KeysCommand(), "KEYS", "ab*");
            assertTrue(out.startsWith("*2"));
        }
    }

    @Nested
    @DisplayName("Hash Commands")
    class HashCommands {
        @Test
        void testHSetHGet() throws IOException {
            assertEquals(":1\r\n", execute(new HSetCommand(), "HSET", "h", "f", "v"));
            outputStream.reset();
            assertEquals("$1\r\nv\r\n", execute(new HGetCommand(), "HGET", "h", "f"));
        }

        @Test
        void testHSetNx() throws IOException {
            assertEquals(":1\r\n", execute(new HSetnxCommand(), "HSETNX", "h", "f", "v"));
            outputStream.reset();
            assertEquals(":0\r\n", execute(new HSetnxCommand(), "HSETNX", "h", "f", "v2"));
        }

        @Test
        void testHExists() throws IOException {
            db.hset("h", "f", "v");
            assertEquals(":1\r\n", execute(new HExistsCommand(), "HEXISTS", "h", "f"));
            outputStream.reset();
            assertEquals(":0\r\n", execute(new HExistsCommand(), "HEXISTS", "h", "missing"));
        }

        @Test
        void testHLen() throws IOException {
            db.hset("h", "f1", "v");
            db.hset("h", "f2", "v");
            assertEquals(":2\r\n", execute(new HLenCommand(), "HLEN", "h"));
        }

        @Test
        void testHDel() throws IOException {
            db.hset("h", "f", "v");
            assertEquals(":1\r\n", execute(new HDelCommand(), "HDEL", "h", "f"));
        }

        @Test
        void testHGetAll() throws IOException {
            db.hset("h", "f", "v");
            String out = execute(new HGetAllCommand(), "HGETALL", "h");
            // *2\r\n$1\r\nf\r\n$1\r\nv\r\n
            assertTrue(out.contains("*2"));
        }
    }

    @Nested
    @DisplayName("Server Commands")
    class ServerCommands {
        @Test
        void testPing() throws IOException {
            assertEquals("+PONG\r\n", execute(new PingCommand(), "PING"));
            assertEquals("+HELLO\r\n", execute(new PingCommand(), "PING", "HELLO"));
        }

        @Test
        void testFlushAll() throws IOException {
            db.set("k", "v");
            assertEquals("+OK\r\n", execute(new FlushAllCommand(), "FLUSHALL"));
            assertEquals(null, db.get("k"));
        }
    }
}
