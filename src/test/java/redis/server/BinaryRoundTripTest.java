package redis.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import redis.core.CommandProcessor;
import redis.core.Database;
import redis.pubsub.PubSubManager;

/**
 * End-to-end checks over a real socket, speaking the same bytes a real client speaks.
 *
 * <p>Covers the full path: a value goes in as bytes, is stored, and comes back out with a
 * {@code $N} header the client can trust - leaving the connection aligned for whatever
 * command follows.
 */
class BinaryRoundTripTest {

    private ServerSocket serverSocket;
    private ExecutorService executor;
    private Database db;

    @BeforeEach
    void setUp() throws IOException {
        serverSocket = new ServerSocket(0);
        executor = Executors.newCachedThreadPool();
        db = new Database();
    }

    @AfterEach
    void tearDown() throws IOException {
        executor.shutdownNow();
        serverSocket.close();
        db.shutdown();
    }

    private Socket connect() throws Exception {
        PubSubManager pubsub = new PubSubManager();
        executor.submit(() -> {
            try {
                Socket client = serverSocket.accept();
                new ClientHandler(client, db, new CommandProcessor(pubsub), pubsub).run();
            } catch (IOException ignored) {
                // socket closed during teardown
            }
        });
        return new Socket("localhost", serverSocket.getLocalPort());
    }

    private static byte[] command(byte[]... args) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (byte[] arg : args) {
            b.write(("$" + arg.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            b.write(arg);
            b.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        return b.toByteArray();
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Reads a $N bulk reply and returns exactly the N payload bytes. */
    private static byte[] readBulk(InputStream in) throws IOException {
        assertEquals('$', in.read(), "expected a bulk reply");
        int length = Integer.parseInt(readLine(in));
        byte[] payload = in.readNBytes(length);
        assertEquals('\r', in.read(), "payload must be exactly the declared length");
        assertEquals('\n', in.read(), "payload must be exactly the declared length");
        return payload;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            if (c != '\r') {
                b.write(c);
            }
        }
        return b.toString(StandardCharsets.US_ASCII);
    }

    private static String readSimple(InputStream in) throws IOException {
        return (char) in.read() + readLine(in);
    }

    @Test
    @DisplayName("a UTF-8 value comes back exactly as it was sent")
    void utf8ValueRoundTrips() throws Exception {
        byte[] value = utf8("Jos\u00e9 \u2615 \ud83d\ude80");
        try (Socket sock = connect()) {
            OutputStream out = sock.getOutputStream();
            InputStream in = sock.getInputStream();

            out.write(command(utf8("SET"), utf8("name"), value));
            out.flush();
            assertEquals("+OK", readSimple(in));

            out.write(command(utf8("GET"), utf8("name")));
            out.flush();
            assertArrayEquals(value, readBulk(in));
        }
    }

    @Test
    @DisplayName("a non-ASCII value does not desynchronise the connection")
    void nonAsciiDoesNotDesync() throws Exception {
        try (Socket sock = connect()) {
            OutputStream out = sock.getOutputStream();
            InputStream in = sock.getInputStream();

            // A short $N here would make the client read into the next reply, and every
            // command after it would fail. Pipeline several so any drift shows up.
            out.write(command(utf8("SET"), utf8("k1"), utf8("Jos\u00e9")));
            out.write(command(utf8("GET"), utf8("k1")));
            out.write(command(utf8("SET"), utf8("k2"), utf8("caf\u00e9\u2615")));
            out.write(command(utf8("GET"), utf8("k2")));
            out.write(command(utf8("PING")));
            out.flush();

            assertEquals("+OK", readSimple(in));
            assertArrayEquals(utf8("Jos\u00e9"), readBulk(in));
            assertEquals("+OK", readSimple(in));
            assertArrayEquals(utf8("caf\u00e9\u2615"), readBulk(in));
            assertEquals("+PONG", readSimple(in), "the stream must still be aligned here");
        }
    }

    @Test
    @DisplayName("arbitrary binary (embedded CR, LF and NUL) round trips")
    void binaryValueRoundTrips() throws Exception {
        byte[] blob = new byte[512];
        for (int i = 0; i < blob.length; i++) {
            blob[i] = (byte) (i % 256);
        }
        try (Socket sock = connect()) {
            OutputStream out = sock.getOutputStream();
            InputStream in = sock.getInputStream();

            out.write(command(utf8("SET"), utf8("blob"), blob));
            out.write(command(utf8("GET"), utf8("blob")));
            out.write(command(utf8("PING")));
            out.flush();

            assertEquals("+OK", readSimple(in));
            assertArrayEquals(blob, readBulk(in));
            assertEquals("+PONG", readSimple(in));
        }
    }

    @Test
    @DisplayName("STRLEN reports byte length, as real Redis does")
    void strlenIsByteLength() throws Exception {
        try (Socket sock = connect()) {
            OutputStream out = sock.getOutputStream();
            InputStream in = sock.getInputStream();

            out.write(command(utf8("SET"), utf8("s"), utf8("Jos\u00e9"))); // 5 bytes, 4 chars
            out.write(command(utf8("STRLEN"), utf8("s")));
            out.flush();

            assertEquals("+OK", readSimple(in));
            assertEquals(':', in.read());
            assertEquals("5", readLine(in));
        }
    }
}
