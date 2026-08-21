package redis.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import redis.config.ServerConfig;
import redis.persistence.AofManager;
import redis.resp.Protocol;

/**
 * A connection is a resource with a lifetime. These cover both ends of it: every request
 * gets an answer, and a client that stops participating gives its thread back.
 */
class ConnectionLifecycleTest {

    /** How long to wait for a reply to stop arriving before calling it complete. */
    private static final int QUIET_MILLIS = 400;

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

    private int start(int timeoutSeconds, int maxClients) throws Exception {
        int port = freePort();
        ServerConfig cfg = new ServerConfig(port, false, Path.of("unused.aof"),
                AofManager.FsyncPolicy.NO, 0, timeoutSeconds, maxClients);
        server = new RedisServer(cfg);
        Thread t = new Thread(server::start, "test-server");
        t.setDaemon(true);
        t.start();

        waitForPort(port);
        // The readiness probe was itself a client. Let its slot come back so the
        // admission tests start from a known count.
        waitFor(() -> server.connectedClients() == 0);
        return port;
    }

    private Socket connect(int port) throws IOException {
        Socket s = new Socket("127.0.0.1", port);
        sockets.add(s);
        return s;
    }

    private static void send(Socket s, String... parts) throws IOException {
        StringBuilder sb = new StringBuilder("*").append(parts.length).append(Protocol.CRLF_TEXT);
        for (String p : parts) {
            sb.append('$').append(p.length()).append(Protocol.CRLF_TEXT)
                    .append(p).append(Protocol.CRLF_TEXT);
        }
        OutputStream o = s.getOutputStream();
        o.write(sb.toString().getBytes(Protocol.WIRE));
        o.flush();
    }

    /**
     * Drains whatever the server sends until it goes quiet. RESP replies span several
     * lines, so reading one line is not enough to see a whole one.
     */
    private static String read(Socket s) throws IOException {
        StringBuilder sb = new StringBuilder();
        s.setSoTimeout(QUIET_MILLIS);
        try {
            int b;
            while ((b = s.getInputStream().read()) != -1) {
                sb.append((char) b);
            }
        } catch (SocketTimeoutException e) {
            // The server has stopped sending; the reply is whatever arrived.
        }
        return sb.toString();
    }

    /** Asserts the server hangs up within the given window, rather than merely going quiet. */
    private static void assertClosedWithin(Socket s, int millis) throws IOException {
        s.setSoTimeout(millis);
        try {
            assertEquals(-1, s.getInputStream().read(), "expected EOF, got data");
        } catch (SocketTimeoutException e) {
            fail("connection was still open after " + millis + "ms");
        }
    }

    @Test
    @DisplayName("A command rejected while subscribed still gets a reply")
    void disallowedCommandWhileSubscribedIsAnswered() throws Exception {
        int port = start(0, 100);
        Socket c = connect(port);

        send(c, "SUBSCRIBE", "foo");
        assertTrue(read(c).contains("subscribe"));

        send(c, "GET", "k");
        String reply = read(c);

        // Previously this was a bare `continue`: no reply at all, and the client blocked
        // on a read that would never be answered.
        assertTrue(!reply.isEmpty(), "no reply at all: the client blocks here forever");
        assertTrue(reply.startsWith("-ERR"), "expected an error reply, got: " + reply);
        assertTrue(reply.contains("SUBSCRIBE"), reply);

        // And the connection is still usable, and still subscribed.
        send(c, "PING");
        assertTrue(read(c).contains("PONG"));
    }

    @Test
    @DisplayName("An idle client is closed once a timeout is configured")
    void idleClientIsClosed() throws Exception {
        int port = start(1, 100);
        Socket c = connect(port);

        send(c, "PING");
        assertTrue(read(c).contains("PONG"));

        assertClosedWithin(c, 4000);
    }

    @Test
    @DisplayName("A subscriber is exempt from the idle timeout")
    void subscriberSurvivesTheIdleTimeout() throws Exception {
        int port = start(1, 100);
        Socket sub = connect(port);

        send(sub, "SUBSCRIBE", "news");
        assertTrue(read(sub).contains("subscribe"));

        Thread.sleep(2500); // well past the 1s timeout

        // Sitting silent is exactly what a subscriber does; it must still be there.
        Socket pub = connect(port);
        send(pub, "PUBLISH", "news", "hello");
        assertTrue(read(pub).contains(":1"), "the subscriber was dropped while waiting");
        assertTrue(read(sub).contains("hello"));
    }

    @Test
    @DisplayName("Connections past the ceiling are refused, not accepted unboundedly")
    void connectionsAreCapped() throws Exception {
        int port = start(0, 2);

        Socket a = connect(port);
        send(a, "PING");
        assertTrue(read(a).contains("PONG"));

        Socket b = connect(port);
        send(b, "PING");
        assertTrue(read(b).contains("PONG"));

        Socket c = connect(port);
        String reply = read(c);
        assertTrue(reply.contains("max number of clients"), "third client was admitted: " + reply);

        // Freeing a slot lets the next client in, so the counter is released on close.
        a.close();
        waitFor(() -> server.connectedClients() <= 1);

        Socket d = connect(port);
        send(d, "PING");
        assertTrue(read(d).contains("PONG"), "the slot was never given back");
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private void waitForPort(int port) throws Exception {
        waitFor(() -> {
            try (Socket probe = new Socket("127.0.0.1", port)) {
                return true;
            } catch (IOException e) {
                return false;
            }
        });
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
        fail("condition not met within 5s");
    }
}
