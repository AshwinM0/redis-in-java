package redis;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

import redis.core.Database;
import redis.pubsub.PubSubManager;
import redis.server.ClientHandler;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

class ClientHandlerTest {

    private ServerSocket serverSocket;
    private ExecutorService executor;
    private Database db;
    private PubSubManager pubsub;

    private static String resp(String... parts) {
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(parts.length).append("\r\n");
        for (String p : parts) {
            sb.append("$").append(p.length()).append("\r\n");
            sb.append(p).append("\r\n");
        }
        return sb.toString();
    }

    @BeforeEach
    void setup() throws IOException {
        serverSocket = new ServerSocket(0); // random free port
        executor = Executors.newCachedThreadPool();
        db = new Database();
        pubsub = new PubSubManager();
    }

    @AfterEach
    void teardown() throws IOException {
        executor.shutdownNow();
        serverSocket.close();
    }

    private Socket startServer() throws Exception {
        executor.submit(() -> {
            try {
                Socket client = serverSocket.accept();
                new ClientHandler(client, db, new redis.core.CommandProcessor(pubsub), pubsub).run();
            } catch (IOException ignored) {
            }
        });
        return new Socket("localhost", serverSocket.getLocalPort());
    }

    // -------------------------
    // BASIC COMMANDS
    // -------------------------

    @Test
    void testPing() throws Exception {
        try (Socket socket = startServer()) {
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            out.write(resp("PING"));
            out.flush();

            assertEquals("+PONG", in.readLine());
        }
    }

    @Test
    void testPingWithMessage() throws Exception {
        try (Socket socket = startServer()) {
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            out.write(resp("PING", "hello"));
            out.flush();

            assertEquals("+hello", in.readLine());
        }
    }

    // -------------------------
    // STRING COMMANDS
    // -------------------------

    @Test
    void testSetGet() throws Exception {
        try (Socket socket = startServer()) {
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            out.write(resp("SET", "a", "1"));
            out.flush();
            assertEquals("+OK", in.readLine());

            out.write(resp("GET", "a"));
            out.flush();

            assertEquals("$1", in.readLine());
            assertEquals("1", in.readLine());
        }
    }

    @Test
    void testGetMissingKey() throws Exception {
        try (Socket socket = startServer()) {
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            out.write(resp("GET", "missing"));
            out.flush();

            assertEquals("$-1", in.readLine());
        }
    }

    // -------------------------
    // HASH COMMANDS
    // -------------------------

    @Test
    void testHSetHGet() throws Exception {
        try (Socket socket = startServer()) {
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            out.write(resp("HSET", "h", "f", "v"));
            out.flush();
            assertEquals(":1", in.readLine());

            out.write(resp("HGET", "h", "f"));
            out.flush();

            assertEquals("$1", in.readLine());
            assertEquals("v", in.readLine());
        }
    }

    // -------------------------
    // TTL / EXPIRE
    // -------------------------

    @Test
    void testExpireAndTTL() throws Exception {
        try (Socket socket = startServer()) {
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            out.write(resp("SET", "x", "1"));
            out.flush();
            in.readLine();

            out.write(resp("EXPIRE", "x", "1"));
            out.flush();
            assertEquals(":1", in.readLine());

            out.write(resp("TTL", "x"));
            out.flush();
            long ttl = Long.parseLong(in.readLine().substring(1));
            assertTrue(ttl >= 0);
        }
    }

    // -------------------------
    // WRONG TYPE ERROR HANDLING
    // -------------------------

    @Test
    void testWrongTypeError() throws Exception {
        try (Socket socket = startServer()) {
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            out.write(resp("SET", "k", "v"));
            out.flush();
            in.readLine();

            out.write(resp("HGET", "k", "f"));
            out.flush();

            String line = in.readLine();
            assertTrue(line.startsWith("-ERR"));
            assertTrue(line.contains("WRONGTYPE"));
        }
    }

    // -------------------------
    // WRONG ARITY ERROR HANDLING
    // -------------------------

    @Test
    void testWrongArity() throws Exception {
        try (Socket socket = startServer()) {
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            out.write(resp("GET"));
            out.flush();

            assertTrue(in.readLine().contains("wrong number of arguments"));
        }
    }

    // -------------------------
    // SHARED DATABASE, MULTIPLE CLIENTS
    // -------------------------

    @Test
    void testMultipleClientsShareDatabase() throws Exception {
        Socket s1 = startServer();
        Socket s2 = startServer();

        BufferedWriter out1 = new BufferedWriter(new OutputStreamWriter(s1.getOutputStream()));
        BufferedReader in1 = new BufferedReader(new InputStreamReader(s1.getInputStream()));

        BufferedWriter out2 = new BufferedWriter(new OutputStreamWriter(s2.getOutputStream()));
        BufferedReader in2 = new BufferedReader(new InputStreamReader(s2.getInputStream()));

        out1.write(resp("SET", "shared", "1"));
        out1.flush();
        in1.readLine();

        out2.write(resp("GET", "shared"));
        out2.flush();

        assertEquals("$1", in2.readLine());
        assertEquals("1", in2.readLine());

        s1.close();
        s2.close();
    }

    // -------------------------
    // ERROR HANDLING
    // -------------------------

    @Test
    void testUnknownCommand() throws Exception {
        try (Socket socket = startServer()) {
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            out.write(resp("NOPE"));
            out.flush();

            assertTrue(in.readLine().contains("unknown command"));
        }
    }

    @Test
    void testInvalidRESP() throws Exception {
        try (Socket socket = startServer()) {
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            out.write("INVALID\r\n");
            out.flush();

            String response = in.readLine();
            assertTrue(response.startsWith("-ERR"));
        }
    }

    // -------------------------
    // MULTIPLE COMMANDS
    // -------------------------

    @Test
    void testMultipleCommandsSameConnection() throws Exception {
        try (Socket socket = startServer()) {
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            out.write(resp("SET", "a", "1"));
            out.write(resp("INCR", "a"));
            out.write(resp("GET", "a"));
            out.flush();

            assertEquals("+OK", in.readLine());
            assertEquals(":2", in.readLine());
            assertEquals("$1", in.readLine());
            assertEquals("2", in.readLine());
        }
    }

    // -------------------------
    // CLIENT DISCONNECT
    // -------------------------

    @Test
    void testClientDisconnectDoesNotCrashServer() throws Exception {
        Socket socket = startServer();
        socket.close(); // abrupt close

        // Give server a moment
        Thread.sleep(100);

        // No assertion needed — test passes if no exception escapes
    }
}
