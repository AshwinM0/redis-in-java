package redis.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;

import redis.config.ServerConfig;
import redis.core.CommandProcessor;
import redis.persistence.AofManager;
import redis.server.RedisServer;
import redis.core.Database;
import redis.resp.Protocol;
import redis.resp.RespWriter;
import redis.resp.Value;

/** Answers a client can actually parse, on a server that runs in any locale. */
class ProtocolCorrectnessTest {

    private Database db;
    private CommandProcessor processor;
    private final RespWriter writer = new RespWriter();

    @BeforeEach
    void setUp() {
        db = new Database();
        processor = new CommandProcessor();
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    private String run(String... parts) throws IOException {
        List<Value> args = new ArrayList<>();
        for (String p : parts) {
            args.add(new Value("bulk", p));
        }
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        processor.executeCommand(parts[0].toUpperCase(Locale.ROOT), db, writer, reply, args, true);
        return reply.toString(Protocol.WIRE);
    }

    private static String bulk(String body) {
        return "$" + body.length() + Protocol.CRLF_TEXT + body + Protocol.CRLF_TEXT;
    }

    @Nested
    @DisplayName("INCRBYFLOAT speaks decimal, not Java")
    class Floats {

        @Test
        void resultsAreRenderedAsRedisRendersThem() throws IOException {
            // Double.toString produced "3.0E10", "1.0" and "0.30000000000000004" here.
            assertEquals(bulk("30000000000"), run("INCRBYFLOAT", "a", "3.0e10"));
            assertEquals(bulk("1"), run("INCRBYFLOAT", "b", "1"));

            run("INCRBYFLOAT", "c", "0.1");
            assertEquals(bulk("0.3"), run("INCRBYFLOAT", "c", "0.2"));
        }

        @Test
        void javaLiteralSyntaxAndNonFinitesAreRejected() throws IOException {
            for (String bad : new String[] { "1f", "1d", "0x1p3", "Infinity", "-Infinity", "NaN", "" }) {
                assertTrue(run("INCRBYFLOAT", "k", bad).startsWith("-ERR"),
                        "accepted " + bad + ", which no client meant to send");
            }
        }

        @Test
        void theStoredValueIsItselfReadableBack() throws IOException {
            run("INCRBYFLOAT", "k", "10.5");
            assertEquals(bulk("10.5"), run("GET", "k"));
            assertEquals(bulk("21"), run("INCRBYFLOAT", "k", "10.5"));
        }
    }

    @Nested
    @DisplayName("Type errors are reported, not disguised as a missing key")
    class WrongType {

        @Test
        void getOnAHashSaysWrongType() throws IOException {
            run("HSET", "h", "f", "v");

            String reply = run("GET", "h");
            assertTrue(reply.startsWith("-ERR WRONGTYPE"),
                    "GET reported a hash as absent, inviting the client to overwrite it: " + reply);
        }

        @Test
        void getStillReturnsNilForAKeyThatIsGenuinelyAbsent() throws IOException {
            assertEquals("$-1" + Protocol.CRLF_TEXT, run("GET", "absent"));
        }
    }

    @Nested
    @DisplayName("Command dispatch does not depend on the JVM's locale")
    class LocaleIndependence {

        /**
         * Drives a real server over a socket, because the uppercasing that broke lives in
         * ClientHandler's dispatch. Calling CommandProcessor directly would prove nothing:
         * the test would be doing its own uppercasing.
         */
        @Test
        void commandsResolveUnderATurkishLocale() throws Exception {
            // "incr".toUpperCase() is "INCR" in en_US but "İNCR" in tr_TR, which left
            // INCR, PING, EXISTS and INCRBYFLOAT unknown on such a JVM.
            Locale original = Locale.getDefault();
            RedisServer server = null;
            try {
                Locale.setDefault(Locale.forLanguageTag("tr-TR"));

                int port;
                try (ServerSocket probe = new ServerSocket(0)) {
                    port = probe.getLocalPort();
                }
                server = new RedisServer(new ServerConfig(port, false, Path.of("unused.aof"),
                        AofManager.FsyncPolicy.NO, 0));
                RedisServer started = server;
                Thread t = new Thread(started::start, "locale-test-server");
                t.setDaemon(true);
                t.start();

                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline) {
                    try (Socket ping = new Socket("127.0.0.1", port)) {
                        break;
                    } catch (IOException e) {
                        Thread.sleep(25);
                    }
                }

                try (Socket c = new Socket("127.0.0.1", port)) {
                    c.setSoTimeout(4000);
                    send(c, "set", "counter", "1");
                    assertTrue(readLine(c).contains("OK"));

                    send(c, "incr", "counter");
                    assertEquals(":2", readLine(c).trim(), "INCR was not recognised");

                    send(c, "incrbyfloat", "f", "1.5");
                    assertTrue(readLine(c).startsWith("$"), "INCRBYFLOAT was not recognised");
                    assertEquals("1.5", readLine(c).trim()); // bulk replies are two lines

                    send(c, "ping");
                    assertTrue(readLine(c).contains("PONG"), "PING was not recognised");
                }
            } finally {
                if (server != null) {
                    server.stop();
                }
                Locale.setDefault(original);
            }
        }

        private void send(Socket s, String... parts) throws IOException {
            StringBuilder sb = new StringBuilder("*").append(parts.length).append(Protocol.CRLF_TEXT);
            for (String p : parts) {
                sb.append('$').append(p.length()).append(Protocol.CRLF_TEXT)
                        .append(p).append(Protocol.CRLF_TEXT);
            }
            s.getOutputStream().write(sb.toString().getBytes(Protocol.WIRE));
            s.getOutputStream().flush();
        }

        private String readLine(Socket s) throws IOException {
            StringBuilder sb = new StringBuilder();
            int b;
            while ((b = s.getInputStream().read()) != -1) {
                sb.append((char) b);
                if (b == 10) {
                    break;
                }
            }
            return sb.toString();
        }
    }
}
