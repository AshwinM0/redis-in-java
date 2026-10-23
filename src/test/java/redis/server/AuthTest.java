package redis.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import redis.config.ServerConfig;
import redis.persistence.AofManager;

/**
 * AUTH over real sockets. Once the server carries chat traffic, a connection that skips
 * AUTH could publish forged events to any user, so a refused command must not only get an
 * error but also have no effect.
 */
class AuthTest {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String NOAUTH = "-NOAUTH Authentication required.";
    private static final String WRONGPASS = "-WRONGPASS invalid username-password pair or user is disabled.";

    private RedisServer server;
    private int port;
    private final List<Client> clients = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Client c : clients) {
            c.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Nested
    @DisplayName("With a password set")
    class WithAPassword {

        @BeforeEach
        void startServer() throws Exception {
            start(PASSWORD);
        }

        @Test
        @DisplayName("Every command but AUTH and QUIT is refused, and a refused one has no effect")
        void commandsAreRefusedUntilAuth() throws Exception {
            Client c = connect();

            assertEquals(NOAUTH, c.call("PING"));
            assertEquals(NOAUTH, c.call("SET", "k", "v"));
            // Not "unknown command": the gate answers before any lookup.
            assertEquals(NOAUTH, c.call("NOSUCHCOMMAND"));

            assertEquals("+OK", c.call("AUTH", PASSWORD));
            assertEquals("$-1", c.call("GET", "k"), "the refused SET was applied");
            assertEquals("+PONG", c.call("PING"));
        }

        @Test
        @DisplayName("A refused SUBSCRIBE does not subscribe")
        void subscribeIsRefusedUntilAuth() throws Exception {
            Client sub = connect();
            Client pub = connect();

            assertEquals(NOAUTH, sub.call("SUBSCRIBE", "news"));

            assertEquals("+OK", pub.call("AUTH", PASSWORD));
            assertEquals(":0", pub.call("PUBLISH", "news", "forged"), "the refused SUBSCRIBE took effect");
        }

        @Test
        @DisplayName("AUTH default <password> works: clients send it when the URL names a user")
        void authWithTheDefaultUsername() throws Exception {
            Client c = connect();

            assertEquals("+OK", c.call("AUTH", "default", PASSWORD));
            assertEquals("+PONG", c.call("PING"));
        }

        @Test
        void wrongPasswordIsRefused() throws Exception {
            Client c = connect();

            assertEquals(WRONGPASS, c.call("AUTH", "guess"));
            assertEquals(WRONGPASS, c.call("AUTH", "default", "guess"));
            assertEquals(NOAUTH, c.call("PING"));
        }

        @Test
        @DisplayName("default is the only user")
        void unknownUserIsRefusedEvenWithThePassword() throws Exception {
            Client c = connect();

            assertEquals(WRONGPASS, c.call("AUTH", "admin", PASSWORD));
            assertEquals(NOAUTH, c.call("PING"));
        }

        @Test
        @DisplayName("A failed AUTH after a successful one leaves the connection authenticated")
        void failedAuthDoesNotLogOut() throws Exception {
            Client c = connect();

            assertEquals("+OK", c.call("AUTH", PASSWORD));
            assertEquals(WRONGPASS, c.call("AUTH", "guess"));
            assertEquals("+PONG", c.call("PING"));
        }

        @Test
        @DisplayName("QUIT works without authenticating")
        void quitBeforeAuth() throws Exception {
            Client c = connect();

            assertEquals("+OK", c.call("QUIT"));
            assertNull(c.readLine(), "the server should close the connection after QUIT");
        }

        /** Clients pipeline AUTH with the commands queued behind it, in one write. */
        @Test
        @DisplayName("Pipelined AUTH covers the commands behind it")
        void pipelinedAuth() throws Exception {
            Client c = connect();

            c.write(frame("AUTH", PASSWORD), frame("SET", "k", "v"), frame("GET", "k"));

            assertEquals("+OK", c.readLine());
            assertEquals("+OK", c.readLine());
            assertEquals("$1", c.readLine());
            assertEquals("v", c.readLine());
        }

        /**
         * The CVE-2021-32675 shape: a header announcing 512 MB. Only the header is sent: a
         * server that waited for the payload before checking would leave this read hanging.
         * (Sending the payload too would make the test flaky the other way: closing a socket
         * with unread input sends a reset, which can discard the reply before it is read.)
         */
        @Test
        @DisplayName("Before AUTH, an oversized request is refused at its header and the connection closed")
        void oversizedRequestBeforeAuth() throws Exception {
            Client c = connect();

            c.write("*1\r\n$536870912\r\n".getBytes(StandardCharsets.US_ASCII));

            assertEquals("-ERR Protocol error: bulk length 536870912 exceeds 16384", c.readLine());
            assertNull(c.readLine(), "the connection should close: the stream can't be trusted past a refused header");
        }

        @Test
        @DisplayName("Once authenticated, the same connection can send large values")
        void limitsLiftOnceAuthenticated() throws Exception {
            Client c = connect();
            String big = "v".repeat(64 * 1024);

            assertEquals("+OK", c.call("AUTH", PASSWORD));
            assertEquals("+OK", c.call("SET", "big", big));
            assertEquals("$" + big.length(), c.call("GET", "big"));
            assertEquals(big, c.readLine());
        }

        @Test
        @DisplayName("A large command pipelined right behind AUTH gets the full limits")
        void largeCommandPipelinedBehindAuth() throws Exception {
            Client c = connect();

            c.write(frame("AUTH", PASSWORD), frame("SET", "big", "v".repeat(20 * 1024)));

            assertEquals("+OK", c.readLine());
            assertEquals("+OK", c.readLine());
        }

        @Test
        @DisplayName("After a failed AUTH the limits still apply")
        void failedAuthKeepsTheLimits() throws Exception {
            Client c = connect();

            // Header only, for the reason given on oversizedRequestBeforeAuth.
            c.write(frame("AUTH", "guess"),
                    "*3\r\n$3\r\nSET\r\n$3\r\nbig\r\n$20480\r\n".getBytes(StandardCharsets.US_ASCII));

            assertEquals(WRONGPASS, c.readLine());
            assertEquals("-ERR Protocol error: bulk length 20480 exceeds 16384", c.readLine());
            assertNull(c.readLine());
        }

        @Test
        @DisplayName("Authenticating one connection does not authenticate another")
        void authIsPerConnection() throws Exception {
            Client a = connect();
            Client b = connect();

            assertEquals("+OK", a.call("AUTH", PASSWORD));
            assertEquals(NOAUTH, b.call("PING"));
        }

        @Test
        void wrongNumberOfArguments() throws Exception {
            Client c = connect();

            assertEquals("-ERR wrong number of arguments for 'auth' command", c.call("AUTH"));
            assertEquals("-ERR syntax error", c.call("AUTH", "default", PASSWORD, "extra"));
            assertEquals(NOAUTH, c.call("PING"));
        }
    }

    @Nested
    @DisplayName("With no password")
    class WithoutAPassword {

        @BeforeEach
        void startServer() throws Exception {
            start(null);
        }

        @Test
        void commandsNeedNoAuth() throws Exception {
            assertEquals("+PONG", connect().call("PING"));
        }

        @Test
        @DisplayName("AUTH <password> is an error, as in Redis, so a misconfigured client finds out")
        void authWithAPasswordIsAnError() throws Exception {
            assertEquals("-ERR AUTH <password> called without any password configured for the default user."
                    + " Are you sure your configuration is correct?", connect().call("AUTH", "anything"));
        }

        @Test
        @DisplayName("AUTH default <anything> succeeds: the default user needs no password")
        void authAsDefaultSucceeds() throws Exception {
            Client c = connect();

            assertEquals("+OK", c.call("AUTH", "default", "anything"));
            assertEquals("+PONG", c.call("PING"));
        }
    }

    @Test
    @DisplayName("A non-ASCII password works when sent as UTF-8, as node-redis sends it")
    void nonAsciiPassword() throws Exception {
        start("pässwörd ☕");
        Client c = connect();

        assertEquals("+OK", c.call("AUTH", "pässwörd ☕"));
        assertEquals("+PONG", c.call("PING"));
    }

    private void start(String password) throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        server = new RedisServer(new ServerConfig(port, false, Path.of("unused.aof"),
                AofManager.FsyncPolicy.NO, 0, 0, 100, "127.0.0.1", password));
        Thread t = new Thread(server::start, "auth-test-server");
        t.setDaemon(true);
        t.start();

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket probe = new Socket("127.0.0.1", port)) {
                return;
            } catch (IOException e) {
                Thread.sleep(25);
            }
        }
        fail("server did not start listening on port " + port);
    }

    private Client connect() throws IOException {
        Client c = new Client(port);
        clients.add(c);
        return c;
    }

    /** One RESP command, with its arguments encoded as UTF-8 and counted in bytes. */
    private static byte[] frame(String... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("*" + parts.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String p : parts) {
            byte[] bytes = p.getBytes(StandardCharsets.UTF_8);
            out.writeBytes(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(bytes);
            out.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        return out.toByteArray();
    }

    private static final class Client {

        private final Socket socket;
        private final BufferedReader in;

        Client(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(5000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        }

        /** Sends one command and returns the first line of its reply. */
        String call(String... parts) throws IOException {
            write(frame(parts));
            return readLine();
        }

        /** Sends every frame in a single write, the way a client pipelines. */
        void write(byte[]... frames) throws IOException {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            for (byte[] f : frames) {
                all.writeBytes(f);
            }
            OutputStream out = socket.getOutputStream();
            out.write(all.toByteArray());
            out.flush();
        }

        String readLine() throws IOException {
            return in.readLine();
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // already gone
            }
        }
    }
}
