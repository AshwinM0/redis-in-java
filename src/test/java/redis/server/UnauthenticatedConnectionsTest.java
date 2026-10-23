package redis.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import redis.config.ServerConfig;
import redis.persistence.AofManager;

/**
 * Every connection holds a thread, so connections that never authenticate must not be able
 * to pile up. Over real sockets: strangers get a small budget and a deadline, and a client
 * that authenticates gets in regardless.
 */
class UnauthenticatedConnectionsTest {

    private static final String PASSWORD = "pw";

    private RedisServer server;
    private final List<Socket> sockets = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Socket s : sockets) {
            try {
                s.close();
            } catch (IOException ignored) {
                // already gone
            }
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("A connection that never authenticates is closed at its deadline")
    void silentStrangerIsClosedAtTheDeadline() throws Exception {
        int port = start(128, 1);

        assertClosedWithin(connect(port), 4000);
    }

    /**
     * REDIS_TIMEOUT times each read, and every read here succeeds within 300 ms, so it would
     * never fire. Only a deadline counted from connecting ends this connection: at one byte
     * per 300 ms, the 16 KB it announces would otherwise take over an hour to arrive.
     */
    @Test
    @DisplayName("Trickling a byte at a time does not hold a connection past its deadline")
    void tricklingStrangerIsClosedAtTheDeadline() throws Exception {
        int port = start(128, 1);
        Socket stranger = connect(port);
        byte[] header = "*1\r\n$16384\r\n".getBytes(StandardCharsets.US_ASCII);
        OutputStream out = stranger.getOutputStream();
        InputStream in = stranger.getInputStream();
        stranger.setSoTimeout(300);

        long started = System.currentTimeMillis();
        for (int i = 0; ; i++) {
            assertTrue(System.currentTimeMillis() - started < 5000, "still open after 5 s of trickling");
            try {
                out.write(i < header.length ? header[i] : 'x');
                out.flush();
                if (in.read() == -1) {
                    break; // the server closed it
                }
            } catch (SocketTimeoutException e) {
                // Still open and nothing to read: trickle the next byte.
            } catch (SocketException e) {
                break; // reset by the server: closed as well
            }
        }
        long open = System.currentTimeMillis() - started;
        assertTrue(open < 4000, "closed only after " + open + " ms; the deadline is 1 s");
    }

    @Test
    @DisplayName("When strangers fill the budget, a client that authenticates still gets in")
    void authenticatingClientGetsPastAFullBudget() throws Exception {
        int port = start(2, 0);
        Socket oldest = connect(port);
        waitFor(() -> server.connectedClients() == 1);
        Socket newer = connect(port);
        waitFor(() -> server.connectedClients() == 2);

        Socket client = connect(port);
        assertEquals("+OK", call(client, "AUTH", PASSWORD));
        assertEquals("+PONG", call(client, "PING"));

        assertClosedWithin(oldest, 2000);
        assertEquals("-NOAUTH Authentication required.", call(newer, "PING"),
                "only the oldest stranger should have been evicted");
    }

    @Test
    @DisplayName("Authenticated clients are never evicted to make room for strangers")
    void authenticatedClientsAreNotEvicted() throws Exception {
        int port = start(1, 0);
        Socket client = connect(port);
        assertEquals("+OK", call(client, "AUTH", PASSWORD));

        for (int i = 0; i < 3; i++) {
            connect(port); // each one evicts the stranger before it, never the client
        }
        waitFor(() -> server.connectedClients() == 2);

        assertEquals("+PONG", call(client, "PING"));
    }

    @Test
    @DisplayName("An authenticated client stays connected past the deadline")
    void authenticatedClientOutlivesTheDeadline() throws Exception {
        int port = start(128, 1);
        Socket client = connect(port);
        assertEquals("+OK", call(client, "AUTH", PASSWORD));

        Thread.sleep(2500);

        assertEquals("+PONG", call(client, "PING"));
    }

    private int start(int maxUnauthenticated, int authTimeoutSeconds) throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        server = new RedisServer(new ServerConfig(port, false, Path.of("unused.aof"),
                AofManager.FsyncPolicy.NO, 0, 0, 100, "127.0.0.1", PASSWORD,
                maxUnauthenticated, authTimeoutSeconds));
        Thread t = new Thread(server::start, "unauthenticated-test-server");
        t.setDaemon(true);
        t.start();

        waitFor(() -> {
            try (Socket probe = new Socket("127.0.0.1", port)) {
                return true;
            } catch (IOException e) {
                return false;
            }
        });
        // The readiness probe was a stranger too. Let its slot come back, so each test
        // starts from an empty server and knows the order connections arrive in.
        waitFor(() -> server.connectedClients() == 0);
        return port;
    }

    private Socket connect(int port) throws IOException {
        Socket s = new Socket("127.0.0.1", port);
        s.setSoTimeout(5000);
        sockets.add(s);
        return s;
    }

    /** Sends one command and returns the first line of the reply, without the CRLF. */
    private static String call(Socket s, String... parts) throws IOException {
        StringBuilder frame = new StringBuilder("*").append(parts.length).append("\r\n");
        for (String p : parts) {
            frame.append('$').append(p.length()).append("\r\n").append(p).append("\r\n");
        }
        OutputStream out = s.getOutputStream();
        out.write(frame.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();

        ByteArrayOutputStream line = new ByteArrayOutputStream();
        InputStream in = s.getInputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            line.write(b);
        }
        return line.toString(StandardCharsets.US_ASCII).stripTrailing();
    }

    /** Asserts the server hangs up within the window, rather than merely going quiet. */
    private static void assertClosedWithin(Socket s, int millis) throws IOException {
        s.setSoTimeout(millis);
        try {
            assertEquals(-1, s.getInputStream().read(), "expected the server to close the connection");
        } catch (SocketTimeoutException e) {
            fail("the connection was still open after " + millis + " ms");
        } catch (SocketException e) {
            // A reset is the server closing it too.
        }
    }

    private interface Condition {
        boolean met() throws Exception;
    }

    private static void waitFor(Condition c) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (c.met()) {
                return;
            }
            Thread.sleep(25);
        }
        fail("condition not met within 5 s");
    }
}
