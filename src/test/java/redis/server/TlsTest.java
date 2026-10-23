package redis.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.Set;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import redis.config.ServerConfig;
import redis.persistence.AofManager;

/** TLS end to end, through RedisServer on real sockets: what a client using rediss:// sees. */
class TlsTest {

    private static final String PASSWORD = "pw";

    private record Ports(int plain, int tls) {
    }

    private TlsFixtures certs;
    private RedisServer server;
    private final List<Socket> sockets = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        certs = TlsFixtures.get();
    }

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
    @DisplayName("A client that trusts the server's certificate connects, authenticates and runs commands")
    void trustedClientRunsCommands() throws Exception {
        Ports ports = start(PASSWORD, true, null, 10);
        SSLSocket c = tls(ports.tls(), null);

        assertEquals("+OK", call(c, "AUTH", PASSWORD));
        assertEquals("+OK", call(c, "SET", "k", "secret"));
        assertEquals("$6", call(c, "GET", "k"));
        assertEquals("secret", readLine(c));
        String protocol = c.getSession().getProtocol();
        assertTrue(Set.of("TLSv1.3", "TLSv1.2").contains(protocol), protocol);
    }

    @Test
    @DisplayName("A client that does not trust the server's certificate refuses to talk to it")
    void untrustedServerIsRefused() throws Exception {
        Ports ports = start(PASSWORD, true, null, 10);
        SSLSocket c = TlsFixtures.connect(TlsFixtures.client(null, certs.rotatedCert), ports.tls());
        sockets.add(c);

        assertThrows(SSLHandshakeException.class, c::startHandshake);
    }

    @Test
    @DisplayName("Plaintext sent to the TLS port gets no reply")
    void plaintextOnTheTlsPortIsRejected() throws Exception {
        Ports ports = start(PASSWORD, true, null, 10);
        Socket c = plain(ports.tls());

        c.getOutputStream().write(frame("PING"));
        String received = drain(c);

        assertFalse(received.contains("PONG"), "a plaintext command was served on the TLS port: " + received);
    }

    @Test
    @DisplayName("Both ports serve the same data")
    void bothPortsShareOneServer() throws Exception {
        Ports ports = start(PASSWORD, true, null, 10);
        Socket plain = plain(ports.plain());
        assertEquals("+OK", call(plain, "AUTH", PASSWORD));
        assertEquals("+OK", call(plain, "SET", "k", "v"));

        SSLSocket tls = tls(ports.tls(), null);
        assertEquals("+OK", call(tls, "AUTH", PASSWORD));
        assertEquals("$1", call(tls, "GET", "k"));
        assertEquals("v", readLine(tls));
    }

    @Test
    @DisplayName("With the plaintext port at 0, the server runs on TLS alone")
    void tlsOnly() throws Exception {
        Ports ports = start(PASSWORD, false, null, 10);
        SSLSocket c = tls(ports.tls(), null);

        assertEquals("+OK", call(c, "AUTH", PASSWORD));
        assertEquals("+PONG", call(c, "PING"));
    }

    @Test
    @DisplayName("With a client CA set, a client presenting a trusted certificate gets in")
    void trustedClientCertificateIsAccepted() throws Exception {
        Ports ports = start(PASSWORD, true, certs.clientCert, 10);
        SSLSocket c = tls(ports.tls(), certs.clientKeystore);

        assertEquals("+OK", call(c, "AUTH", PASSWORD));
        assertEquals("+PONG", call(c, "PING"));
    }

    /**
     * Under TLS 1.3 a client finishes its half of the handshake before the server has judged
     * its certificate, so the refusal arrives on its first read rather than at once.
     */
    @Test
    @DisplayName("With a client CA set, a client without a certificate is refused")
    void clientWithoutACertificateIsRefused() throws Exception {
        Ports ports = start(PASSWORD, true, certs.clientCert, 10);
        SSLSocket c = TlsFixtures.connect(TlsFixtures.client(null, certs.serverCert), ports.tls());
        sockets.add(c);

        assertNull(replyOrNull(c, "PING"), "a client with no certificate was served");
    }

    @Test
    @DisplayName("A client that never finishes its handshake is closed at the deadline, even with no password")
    void stalledHandshakeIsClosedAtTheDeadline() throws Exception {
        Ports ports = start(null, true, null, 1);

        assertClosedWithin(plain(ports.tls()), 4000); // connects, then never says a word
    }

    @Test
    @DisplayName("With no password, a finished handshake is all it takes, and the deadline stops applying")
    void finishedHandshakeIsTrustedWithoutAPassword() throws Exception {
        Ports ports = start(null, true, null, 1);
        SSLSocket c = tls(ports.tls(), null);

        assertEquals("+PONG", call(c, "PING"));
        Thread.sleep(2500);
        assertEquals("+PONG", call(c, "PING"));
    }

    /** Starts a server with TLS on, and plaintext too unless {@code plaintext} is false. */
    private Ports start(String password, boolean plaintext, Path clientCa, int authTimeoutSeconds)
            throws Exception {
        int plainPort = plaintext ? freePort() : 0;
        int tlsPort = freePort();
        server = new RedisServer(new ServerConfig(plainPort, false, Path.of("unused.aof"),
                AofManager.FsyncPolicy.NO, 0, 0, 100, "127.0.0.1", password, 128, authTimeoutSeconds,
                new ServerConfig.TlsSettings(tlsPort, certs.serverKeystore, TlsFixtures.PASSWORD, clientCa)));
        Thread t = new Thread(server::start, "tls-test-server");
        t.setDaemon(true);
        t.start();

        waitFor(() -> canConnect(tlsPort));
        if (plaintext) {
            waitFor(() -> canConnect(plainPort));
        }
        // The probes were clients that never handshook or authenticated. Let their slots come back.
        waitFor(() -> server.connectedClients() == 0);
        return new Ports(plainPort, tlsPort);
    }

    /** A TLS client that trusts the server's certificate, checks its name, and has handshaken. */
    private SSLSocket tls(int port, Path clientKeystore) throws Exception {
        SSLSocket s = TlsFixtures.connect(TlsFixtures.client(clientKeystore, certs.serverCert), port);
        sockets.add(s);
        s.startHandshake();
        return s;
    }

    private Socket plain(int port) throws IOException {
        Socket s = new Socket("127.0.0.1", port);
        s.setSoTimeout(5000);
        sockets.add(s);
        return s;
    }

    private static byte[] frame(String... parts) {
        StringBuilder sb = new StringBuilder("*").append(parts.length).append("\r\n");
        for (String p : parts) {
            sb.append('$').append(p.length()).append("\r\n").append(p).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** Sends one command and returns the first line of its reply. */
    private static String call(Socket s, String... parts) throws IOException {
        OutputStream out = s.getOutputStream();
        out.write(frame(parts));
        out.flush();
        return readLine(s);
    }

    /** Like call, but null where the connection refuses to carry a reply at all. */
    private static String replyOrNull(Socket s, String... parts) {
        try {
            String line = call(s, parts);
            return line.isEmpty() ? null : line;
        } catch (IOException e) {
            return null;
        }
    }

    private static String readLine(Socket s) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        InputStream in = s.getInputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            line.write(b);
        }
        return line.toString(StandardCharsets.US_ASCII).stripTrailing();
    }

    /** Everything the server sends until it closes the connection or goes quiet. */
    private static String drain(Socket s) {
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        try {
            InputStream in = s.getInputStream();
            int b;
            while ((b = in.read()) != -1) {
                received.write(b);
            }
        } catch (IOException e) {
            // A reset or a timeout ends it as well as a close.
        }
        return received.toString(StandardCharsets.ISO_8859_1);
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

    private static boolean canConnect(int port) {
        try (Socket probe = new Socket("127.0.0.1", port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
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
