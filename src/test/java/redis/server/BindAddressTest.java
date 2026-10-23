package redis.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import redis.config.ServerConfig;
import redis.persistence.AofManager;

/**
 * The listening address decides who can reach the server at all. Whoever can connect can
 * read and change every key, and on a chat backend publish forged events to any user, so
 * the default has to be this machine only.
 */
class BindAddressTest {

    /** Long enough for Windows, which retries a refused connect for about two seconds. */
    private static final int CONNECT_TIMEOUT_MILLIS = 3000;

    private RedisServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("The default bind is loopback")
    void defaultsToLoopback() {
        ServerConfig cfg = new ServerConfig(6379, false, null, AofManager.FsyncPolicy.NO, 0);
        assertEquals("127.0.0.1", cfg.bind);
    }

    @Test
    @DisplayName("Bound to loopback, the server cannot be reached through this machine's network address")
    void loopbackBindIsUnreachableFromTheNetwork() throws Exception {
        InetAddress lan = reachableNetworkAddress();

        int port = start("127.0.0.1", InetAddress.getLoopbackAddress());

        assertTrue(reachable(InetAddress.getLoopbackAddress(), port, CONNECT_TIMEOUT_MILLIS),
                "unreachable on loopback");
        assertFalse(reachable(lan, port, CONNECT_TIMEOUT_MILLIS),
                "a loopback-only server accepted a connection on " + lan);
    }

    /**
     * The test above would also pass if the server ignored its config and always bound
     * loopback. Binding somewhere else proves the setting is what decides.
     */
    @Test
    @DisplayName("The server listens on the configured address and nowhere else")
    void listensOnTheConfiguredAddress() throws Exception {
        InetAddress lan = reachableNetworkAddress();

        int port = start(lan.getHostAddress(), lan);

        assertTrue(reachable(lan, port, CONNECT_TIMEOUT_MILLIS),
                "unreachable on the configured address " + lan);
        assertFalse(reachable(InetAddress.getLoopbackAddress(), port, CONNECT_TIMEOUT_MILLIS),
                "a server bound to " + lan + " also accepted on loopback");
    }

    /**
     * A non-loopback IPv4 address that this machine can connect to itself on.
     *
     * <p>The "unreachable" assertions only mean something if the address is reachable when
     * something does listen on it; a firewall or a disconnected adapter would make them pass
     * for the wrong reason. So each candidate is first checked with a plain socket, and the
     * test is skipped on a machine where none qualifies.
     */
    private static InetAddress reachableNetworkAddress() throws IOException {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback()) {
                continue;
            }
            for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                if (!(address instanceof Inet4Address) || address.isLinkLocalAddress()) {
                    continue;
                }
                try (ServerSocket control = new ServerSocket(0, 50, address)) {
                    if (reachable(address, control.getLocalPort(), CONNECT_TIMEOUT_MILLIS)) {
                        return address;
                    }
                }
            }
        }
        return abort("no non-loopback IPv4 address this machine can connect to itself on");
    }

    /** Starts a server bound to {@code bind} and waits until it accepts on {@code probe}. */
    private int start(String bind, InetAddress probe) throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        server = new RedisServer(new ServerConfig(port, false, Path.of("unused.aof"),
                AofManager.FsyncPolicy.NO, 0, 0, 100, bind, null));
        Thread t = new Thread(server::start, "bind-test-server");
        t.setDaemon(true);
        t.start();

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (reachable(probe, port, 200)) {
                return port;
            }
            Thread.sleep(25);
        }
        return fail("server did not start listening on " + bind + ":" + port);
    }

    private static boolean reachable(InetAddress address, int port, int timeoutMillis) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(address, port), timeoutMillis);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
