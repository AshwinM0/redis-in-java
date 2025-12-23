package redis;

import java.io.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.net.*;
import java.util.concurrent.*;

public class RedisServer {

    private static final Logger LOG = Logger.getLogger(RedisServer.class.getName());

    private final int port;
    private final ExecutorService threadPool;
    private final Database db = new Database();
    private ServerSocket serverSocket;

    private volatile boolean running = true;
    private volatile boolean stopped = false;

    public RedisServer() {
        this(6379);
    }

    public RedisServer(int port) {
        this.port = port;
        this.threadPool = Executors.newCachedThreadPool();
    }

    public void start() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println(">>> SHUTDOWN HOOK RUNNING <<<"); // unbuffered stderr
            System.err.flush();
            stop();
        }));

        try {
            this.serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true); // allow immediate reuse
            serverSocket.bind(new InetSocketAddress(port));

            LOG.info(() -> "Redis server listening on port " + port);

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    LOG.info(() -> "New client connected: " + clientSocket.getRemoteSocketAddress());
                    threadPool.submit(new ClientHandler(clientSocket, db));
                } catch (SocketException e) {
                    if (running) {
                        LOG.log(Level.WARNING, "Socket error", e);
                    }
                    break;
                }
            }

        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Server startup failure", e);
        } finally {
            stop();
        }
    }

    public void stop() {
        if (stopped) {
            return; // Already stopped, avoid duplicate cleanup
        }
        stopped = true;
        running = false;

        System.err.println("Shutting down Redis server...");

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error closing server socket", e);
        }

        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.log(Level.WARNING, "Forcing thread pool shutdown");
                threadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        System.err.println("Redis server stopped.");
    }

    public static void main(String[] args) {
        RedisServer server = new RedisServer();
        server.start();
    }
}
