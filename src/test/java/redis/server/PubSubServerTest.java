package redis.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import redis.config.ServerConfig;
import redis.persistence.AofManager;

/**
 * Pub/Sub over real sockets: pattern subscriptions, and subscribers that stop reading.
 *
 * <p>A subscriber that stops reading fills its socket's buffers in the kernel, and then a
 * write to it blocks. These check that no publisher ever waits on that write, and that the
 * subscriber is disconnected once its queue on the server passes the limit.
 */
class PubSubServerTest {

    /** Small, so a stuck subscriber passes it soon after the kernel's buffers fill. */
    private static final long LIMIT_BYTES = 256 * 1024;
    private static final String PAYLOAD = "x".repeat(64 * 1024);

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
    @DisplayName("PSUBSCRIBE receives matching publishes as pmessage frames")
    void patternSubscriptionOverASocket() throws Exception {
        int port = start(null).plain;
        Socket subscriber = connect(port);
        send(subscriber, "PSUBSCRIBE", "news.*");
        assertEquals(List.of("psubscribe", "news.*", "1"), readFrame(subscriber.getInputStream()));

        Socket publisher = connect(port);
        assertEquals(":0", call(publisher, "PUBLISH", "sport", "goal"));
        assertEquals(":1", call(publisher, "PUBLISH", "news.tech", "hello"));
        assertEquals(List.of("pmessage", "news.*", "news.tech", "hello"), readFrame(subscriber.getInputStream()));

        assertEquals(":1", call(publisher, "PUBSUB", "NUMPAT"));
    }

    @Test
    @DisplayName("A subscriber that stops reading is disconnected, and publishing never waits for it")
    void stuckSubscriberIsDisconnected() throws Exception {
        int port = start(null).plain;
        Socket stuck = subscribe(connectWithSmallBuffer(port));
        Socket healthy = subscribe(connect(port));
        Socket publisher = connect(port);
        publishUntilCutOff(publisher, healthy);

        assertEquals("*2", call(publisher, "PUBSUB", "NUMSUB", "news"));
        assertEquals(List.of("$4", "news", ":1"), List.of(readLine(publisher), readLine(publisher), readLine(publisher)));
        assertClosed(stuck);
    }

    /**
     * Closing a TLS socket sends a close_notify alert, and sending it waits for the TLS write
     * lock, which the stuck writer holds while it blocks. Cutting a TLS subscriber off that
     * way would block the publishing thread for good, so the server closes the TCP socket
     * beneath instead.
     */
    @Test
    @DisplayName("A TLS subscriber that stops reading is disconnected without blocking the publisher")
    void stuckTlsSubscriberIsDisconnected() throws Exception {
        Ports ports = start(TlsFixtures.get());
        Socket stuck = subscribe(connectTlsWithSmallBuffer(ports.tls));
        Socket healthy = subscribe(connect(ports.plain));
        Socket publisher = connect(ports.plain);
        publishUntilCutOff(publisher, healthy);

        assertClosed(stuck);
    }

    /**
     * Publishes until the stuck subscriber is cut off, which the reply shows by counting one
     * receiver instead of two. Each message reaches the healthy subscriber before the next
     * goes out, so its queue never fills: racing ahead could cut off even a healthy one.
     */
    private void publishUntilCutOff(Socket publisher, Socket healthy) throws Exception {
        AtomicInteger received = new AtomicInteger();
        Thread reader = new Thread(() -> {
            try {
                InputStream in = healthy.getInputStream();
                while (true) {
                    readFrame(in);
                    received.incrementAndGet();
                }
            } catch (IOException e) {
                // closed at teardown
            }
        }, "healthy-subscriber");
        reader.setDaemon(true);
        reader.start();

        for (int published = 1; published <= 2000; published++) {
            String reply;
            try {
                reply = call(publisher, "PUBLISH", "news", PAYLOAD);
            } catch (SocketTimeoutException e) {
                fail("PUBLISH went unanswered for 5 s: the publisher is waiting on the stuck subscriber");
                return;
            }
            int expected = published;
            waitFor(() -> received.get() >= expected, "the healthy subscriber stopped receiving");
            if (reply.equals(":1")) {
                return;
            }
            assertEquals(":2", reply);
        }
        fail("the stuck subscriber was never cut off");
    }

    private record Ports(int plain, int tls) {
    }

    private Ports start(TlsFixtures certs) throws Exception {
        int plain = freePort();
        int tls = (certs == null) ? 0 : freePort();
        ServerConfig.TlsSettings tlsSettings = (certs == null) ? null
                : new ServerConfig.TlsSettings(tls, certs.serverKeystore, TlsFixtures.PASSWORD, null);
        server = new RedisServer(new ServerConfig(plain, false, Path.of("unused.aof"),
                AofManager.FsyncPolicy.NO, 0, 0, 100, "127.0.0.1", null, 128, 10, tlsSettings, 0,
                LIMIT_BYTES));
        Thread t = new Thread(server::start, "pubsub-test-server");
        t.setDaemon(true);
        t.start();

        waitFor(() -> canConnect(plain), "the server did not start listening");
        if (certs != null) {
            waitFor(() -> canConnect(tls), "the server did not start listening for TLS");
        }
        // The probes were clients too. Let their slots come back.
        waitFor(() -> server.connectedClients() == 0, "the readiness probes never closed");
        return new Ports(plain, tls);
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static boolean canConnect(int port) {
        try (Socket probe = new Socket("127.0.0.1", port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private Socket connect(int port) throws IOException {
        Socket s = new Socket("127.0.0.1", port);
        s.setSoTimeout(5000);
        sockets.add(s);
        return s;
    }

    /** A small receive window, so the server's writes to it start blocking sooner. */
    private Socket connectWithSmallBuffer(int port) throws IOException {
        Socket s = new Socket();
        s.setReceiveBufferSize(4096);
        s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        s.setSoTimeout(5000);
        sockets.add(s);
        return s;
    }

    private Socket connectTlsWithSmallBuffer(int port) throws Exception {
        TlsFixtures certs = TlsFixtures.get();
        Socket raw = connectWithSmallBuffer(port);
        SSLSocket s = (SSLSocket) TlsFixtures.client(null, certs.serverCert)
                .createSocket(raw, "127.0.0.1", port, true);
        SSLParameters params = s.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        s.setSSLParameters(params);
        s.setSoTimeout(5000);
        sockets.add(s);
        s.startHandshake();
        return s;
    }

    /** Subscribes to "news" and reads the confirmation. Afterwards the caller may stop reading. */
    private static Socket subscribe(Socket s) throws IOException {
        send(s, "SUBSCRIBE", "news");
        assertEquals(List.of("subscribe", "news", "1"), readFrame(s.getInputStream()));
        return s;
    }

    private static void send(Socket s, String... parts) throws IOException {
        StringBuilder frame = new StringBuilder("*").append(parts.length).append("\r\n");
        for (String p : parts) {
            frame.append('$').append(p.length()).append("\r\n").append(p).append("\r\n");
        }
        OutputStream out = s.getOutputStream();
        out.write(frame.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** Sends one command and returns the first line of the reply. */
    private static String call(Socket s, String... parts) throws IOException {
        send(s, parts);
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
        if (b == -1) {
            throw new SocketException("connection closed");
        }
        return line.toString(StandardCharsets.US_ASCII).stripTrailing();
    }

    /** Reads one array of bulk strings and integers, as Pub/Sub sends them. */
    private static List<String> readFrame(InputStream in) throws IOException {
        String header = readLine(in);
        assertTrue(header.startsWith("*"), "expected an array, got " + header);
        List<String> items = new ArrayList<>();
        for (int i = Integer.parseInt(header.substring(1)); i > 0; i--) {
            String item = readLine(in);
            if (item.startsWith(":")) {
                items.add(item.substring(1));
            } else {
                byte[] bulk = in.readNBytes(Integer.parseInt(item.substring(1)) + 2); // with its CRLF
                items.add(new String(bulk, 0, bulk.length - 2, StandardCharsets.US_ASCII));
            }
        }
        return items;
    }

    /** Reads whatever the server sent before closing, then expects the end of the stream. */
    private static void assertClosed(Socket s) throws IOException {
        byte[] discard = new byte[64 * 1024];
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        try {
            while (System.nanoTime() < deadline) {
                if (s.getInputStream().read(discard) == -1) {
                    return;
                }
            }
        } catch (SocketTimeoutException e) {
            fail("the stuck subscriber's connection was still open");
        } catch (SocketException e) {
            return; // a reset is the server closing it too
        }
        fail("the stuck subscriber's connection was still open after 15 s");
    }

    private static void waitFor(BooleanSupplier condition, String failure) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail(failure);
            }
            Thread.sleep(5);
        }
    }
}
