package redis.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import redis.config.ServerConfig;
import redis.persistence.AofManager;

/**
 * start() returns normally only when stop() ended it, and throws on any other ending. That
 * is what lets main() exit non-zero; these failures used to return quietly, so the process
 * exited 0 and a script waiting on it saw a clean shutdown instead of a busy port.
 */
class StartupFailureTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("A busy port is a failed start, not a quiet return")
    void busyPort() throws Exception {
        try (ServerSocket squatter = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            RedisServer server = new RedisServer(config(squatter.getLocalPort(), "127.0.0.1", null));

            IllegalStateException e = assertFailsToStart(server);
            assertInstanceOf(BindException.class, e.getCause());
        }
    }

    @Test
    @DisplayName("An address this machine doesn't have is a failed start")
    void unassignableAddress() {
        // 203.0.113.0/24 is reserved for documentation, so no machine is configured with it.
        assertFailsToStart(new RedisServer(config(0, "203.0.113.7", null)));
    }

    @Test
    @DisplayName("An AOF that cannot be opened is a failed start")
    void unopenableAof() throws IOException {
        Path notADirectory = Files.createFile(dir.resolve("not-a-directory"));

        assertFailsToStart(new RedisServer(config(0, "127.0.0.1", notADirectory.resolve("appendonly.aof"))));
    }

    @Test
    @DisplayName("Ended by stop(), start() returns normally")
    void stopIsANormalReturn() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer(config(port, "127.0.0.1", null));
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                server.start();
            } catch (Throwable e) {
                thrown.set(e);
            }
        }, "stop-test-server");
        t.start();
        waitUntilListening(port);

        server.stop();
        t.join(10_000);

        assertFalse(t.isAlive(), "start() did not return after stop()");
        assertNull(thrown.get(), "a requested stop must not look like a failure");
    }

    /** Fails rather than hangs if start() wrongly succeeds and sits in accept(). */
    private static IllegalStateException assertFailsToStart(RedisServer server) {
        return assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertThrows(IllegalStateException.class, server::start),
                "start() kept running when it should have failed");
    }

    /** AOF on when a path is given; no password either way. */
    private static ServerConfig config(int port, String bind, Path aofPath) {
        return new ServerConfig(port, aofPath != null, aofPath, AofManager.FsyncPolicy.NO,
                0, 0, 100, bind, null);
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void waitUntilListening(int port) throws Exception {
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
}
