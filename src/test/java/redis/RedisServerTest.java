package redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import redis.server.RedisServer;

public class RedisServerTest {

    private RedisServer server;
    private int port;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws IOException {
        // Find a free port
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }

        server = new RedisServer(
                new redis.config.ServerConfig(port, false, null, redis.persistence.AofManager.FsyncPolicy.NO, 0));
        executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> server.start());

        // Give the server a moment to start
        try {
            // Simple poll to check if port is listening
            boolean connected = false;
            for (int i = 0; i < 10; i++) {
                try (Socket ignored = new Socket("localhost", port)) {
                    connected = true;
                    break;
                } catch (IOException e) {
                    Thread.sleep(100);
                }
            }
            if (!connected) {
                fail("Server failed to start on port " + port);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void testConnectionAndPing() throws IOException {
        try (Socket client = new Socket("localhost", port);
                OutputStream out = client.getOutputStream();
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()))) {

            // Send PING
            out.write("*1\r\n$4\r\nPING\r\n".getBytes());
            out.flush();

            // Expect +PONG
            String response = in.readLine();
            assertEquals("+PONG", response);
        }
    }

    @Test
    void testMultipleCommands() throws IOException {
        try (Socket client = new Socket("localhost", port);
                OutputStream out = client.getOutputStream();
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()))) {

            // SET k v
            out.write("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n".getBytes());
            out.flush();
            assertEquals("+OK", in.readLine());

            // GET k
            out.write("*2\r\n$3\r\nGET\r\n$1\r\nk\r\n".getBytes());
            out.flush();

            assertEquals("$1", in.readLine());
            assertEquals("v", in.readLine());
        }
    }
}
