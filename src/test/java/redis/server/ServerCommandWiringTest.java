package redis.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
 * Commands are only usable if the dispatcher knows about them. ECHO was documented but
 * never registered, and QUIT was allow-listed for subscribers without existing.
 */
class ServerCommandWiringTest {

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

    private static String resp(String... parts) {
        StringBuilder sb = new StringBuilder("*").append(parts.length).append("\r\n");
        for (String p : parts) {
            sb.append("$").append(p.length()).append("\r\n").append(p).append("\r\n");
        }
        return sb.toString();
    }

    private static void send(Socket sock, String raw) throws IOException {
        OutputStream out = sock.getOutputStream();
        out.write(raw.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    @Test
    @DisplayName("ECHO is reachable through the dispatcher")
    void echoIsRegistered() throws Exception {
        try (Socket sock = connect()) {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII));

            send(sock, resp("ECHO", "hello"));
            assertEquals("$5", in.readLine());
            assertEquals("hello", in.readLine());
        }
    }

    @Test
    @DisplayName("ECHO is case-insensitive, like every other command")
    void echoIsCaseInsensitive() throws Exception {
        try (Socket sock = connect()) {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII));

            send(sock, resp("echo", "hi"));
            assertEquals("$2", in.readLine());
            assertEquals("hi", in.readLine());
        }
    }

    @Test
    @DisplayName("QUIT replies OK and then closes the connection")
    void quitAcknowledgesAndCloses() throws Exception {
        try (Socket sock = connect()) {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII));

            send(sock, resp("PING"));
            assertEquals("+PONG", in.readLine());

            send(sock, resp("QUIT"));
            assertEquals("+OK", in.readLine());
            assertNull(in.readLine(), "the server should close the connection after QUIT");
        }
    }

    @Test
    @DisplayName("commands still work normally before a QUIT")
    void commandsWorkBeforeQuit() throws Exception {
        try (Socket sock = connect()) {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII));

            send(sock, resp("SET", "k", "v"));
            assertEquals("+OK", in.readLine());

            send(sock, resp("ECHO", "bye"));
            assertEquals("$3", in.readLine());
            assertEquals("bye", in.readLine());

            send(sock, resp("QUIT"));
            assertEquals("+OK", in.readLine());
            assertNull(in.readLine());
        }
    }
}
