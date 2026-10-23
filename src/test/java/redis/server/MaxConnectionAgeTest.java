package redis.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedInputStream;
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
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import redis.config.ServerConfig;
import redis.persistence.AofManager;

/**
 * Authentication is checked once, when a connection opens, so a maximum age is what bounds
 * how long a revoked client keeps its access. Connections close only between commands:
 * closing in the middle of one would leave the client unable to tell whether it ran.
 */
class MaxConnectionAgeTest {

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
    @DisplayName("An idle connection is closed at its maximum age, and not before 90% of it")
    void idleConnectionClosesAtItsAge() throws Exception {
        int port = start(2);
        long opened = System.nanoTime();
        Socket c = connect(port);
        assertEquals("+PONG", call(c, "PING"));

        assertClosedWithin(c, 4000);
        long lived = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - opened);
        assertTrue(lived >= 1800, "closed after " + lived + " ms, before 90% of the 2 s maximum");
    }

    /**
     * Every command either ran and got its reply, or did not run at all. Counting the INCRs
     * that were acknowledged and reading the counter back proves no INCR ran with its reply
     * lost, which is what closing mid-command would cause.
     */
    @Test
    @DisplayName("A busy connection is closed between commands, never in the middle of one")
    void busyConnectionClosesBetweenCommands() throws Exception {
        int port = start(1);
        Socket c = connect(port);

        int acknowledged = 0;
        long started = System.nanoTime();
        try {
            while (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 5) {
                String reply = call(c, "INCR", "counter");
                if (reply.isEmpty()) {
                    break; // the server closed the connection between two commands
                }
                assertEquals(":" + (acknowledged + 1), reply);
                acknowledged++;
            }
        } catch (IOException e) {
            // A reset is the server closing it too.
        }
        long open = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(open < 3000, "a connection busy with commands outlived its 1 s maximum: " + open + " ms");
        assertTrue(acknowledged > 0);

        Socket fresh = connect(port);
        String count = String.valueOf(acknowledged);
        assertEquals("$" + count.length(), call(fresh, "GET", "counter"));
        assertEquals(count, readLine(fresh), "an INCR ran but its reply never reached the client");
    }

    /**
     * A pipelining client still has commands in flight when its age runs out. Closing a
     * socket with unread input makes TCP send a reset instead of a normal close, and a reset
     * can throw away replies the client has not read yet. Every command that ran must still
     * have its reply arrive.
     */
    @Test
    @DisplayName("A pipelining client gets the reply to every command that ran, even at the deadline")
    void pipelinedRepliesSurviveTheClose() throws Exception {
        int port = start(1);
        Socket c = connect(port);
        byte[] batch = frames(100, "INCR", "counter");

        Thread writer = new Thread(() -> {
            try {
                OutputStream out = c.getOutputStream();
                while (true) {
                    out.write(batch); // keep commands in flight until the server stops reading
                }
            } catch (IOException e) {
                // The server closed the connection.
            }
        }, "pipelining-writer");
        writer.setDaemon(true);
        writer.start();

        InputStream in = new BufferedInputStream(c.getInputStream(), 64 * 1024);
        long replies = 0;
        try {
            assertEquals(":1", readLine(in));
            replies = 1;
            // Stop reading across the deadline, so the server reaches it with replies queued
            // unread on this side and commands queued unread on its own. That is exactly when
            // a plain close sends the reset.
            Thread.sleep(1500);
            String line;
            while (!(line = readLine(in)).isEmpty()) {
                assertEquals(":" + (replies + 1), line);
                replies++;
            }
        } catch (IOException e) {
            // A reset: whatever arrived before it is all the client will ever see.
        }

        Socket fresh = connect(port);
        String count = String.valueOf(replies);
        assertEquals("$" + count.length(), call(fresh, "GET", "counter"));
        assertEquals(count, readLine(fresh), "INCRs ran whose replies the client never received");
    }

    /**
     * The case that matters most for revocation: a subscriber sends nothing, and is exempt
     * from the idle timeout, yet it receives every message published. It has to age out too.
     */
    @Test
    @DisplayName("A subscriber is closed at its maximum age too, and publishing carries on")
    void subscriberClosesAtItsAge() throws Exception {
        int port = start(1);
        Socket subscriber = connect(port);
        assertEquals("*3", call(subscriber, "SUBSCRIBE", "news"));
        for (int i = 0; i < 5; i++) {
            readLine(subscriber); // the rest of the confirmation: subscribe, news, :1
        }

        assertClosedWithin(subscriber, 3000);

        Socket publisher = connect(port);
        assertEquals(":0", call(publisher, "PUBLISH", "news", "hello"));
    }

    @Test
    @DisplayName("Jitter spreads ages over the last 10% of the maximum, and never past it")
    void jitterStaysWithinTheMaximum() {
        Random random = new Random(42); // seeded, so the test always sees the same draws
        Set<Long> ages = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            long age = ClientHandler.jitteredAgeMillis(3_600_000, random);
            assertTrue(age >= 3_240_000 && age <= 3_600_000, "age out of range: " + age);
            ages.add(age);
        }
        assertTrue(ages.size() > 100, "ages barely vary, so connections opened together would close together");
    }

    private int start(int maxConnectionAgeSeconds) throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        server = new RedisServer(new ServerConfig(port, false, Path.of("unused.aof"),
                AofManager.FsyncPolicy.NO, 0, 0, 100, "127.0.0.1", null, 128, 10, null,
                maxConnectionAgeSeconds));
        Thread t = new Thread(server::start, "max-age-test-server");
        t.setDaemon(true);
        t.start();

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket probe = new Socket("127.0.0.1", port)) {
                return port;
            } catch (IOException e) {
                Thread.sleep(25);
            }
        }
        return fail("server did not start listening on port " + port);
    }

    private Socket connect(int port) throws IOException {
        Socket s = new Socket("127.0.0.1", port);
        s.setSoTimeout(5000);
        sockets.add(s);
        return s;
    }

    /** The same command, {@code count} times over, as one pipelined write. */
    private static byte[] frames(int count, String... parts) {
        StringBuilder frame = new StringBuilder("*").append(parts.length).append("\r\n");
        for (String p : parts) {
            frame.append('$').append(p.length()).append("\r\n").append(p).append("\r\n");
        }
        return frame.toString().repeat(count).getBytes(StandardCharsets.US_ASCII);
    }

    /** Sends one command and returns the first line of the reply; empty if the server hung up. */
    private static String call(Socket s, String... parts) throws IOException {
        StringBuilder frame = new StringBuilder("*").append(parts.length).append("\r\n");
        for (String p : parts) {
            frame.append('$').append(p.length()).append("\r\n").append(p).append("\r\n");
        }
        OutputStream out = s.getOutputStream();
        out.write(frame.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return readLine(s);
    }

    private static String readLine(Socket s) throws IOException {
        return readLine(s.getInputStream());
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            line.write(b);
        }
        return line.toString(StandardCharsets.US_ASCII).stripTrailing();
    }

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
}
