package redis.server;

import java.io.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import redis.config.ServerConfig;
import redis.core.CommandProcessor;
import redis.core.Database;
import redis.persistence.AofManager;
import redis.pubsub.PubSubManager;
import redis.resp.RespWriter;

import java.net.*;
import java.util.concurrent.*;

public class RedisServer {

    private static final Logger LOG = Logger.getLogger(RedisServer.class.getName());

    private final ServerConfig config;
    private final ExecutorService threadPool;
    private final Database db = new Database();
    private final PubSubManager pubsub = new PubSubManager();

    private ServerSocket serverSocket;
    private volatile boolean running = true;
    private volatile boolean stopped = false;
    private final java.util.concurrent.atomic.AtomicInteger connectedClients =
            new java.util.concurrent.atomic.AtomicInteger();

    private final RespWriter writer = new RespWriter();;
    private AofManager aofManager;
    private CommandProcessor commandProcessor;

    public RedisServer() {
        this(ServerConfig.fromEnv());
    }

    public RedisServer(ServerConfig config) {
        this.config = config;
        this.threadPool = (config.maxThreads <= 0)
                ? Executors.newCachedThreadPool()
                : Executors.newFixedThreadPool(config.maxThreads);
    }

    public void start() {
        if (config.aofEnabled) {
            try {
                aofManager = new AofManager(config.aofPath, writer, config.fsyncPolicy);
            } catch (IOException e) {
                LOG.log(Level.SEVERE, "Failed to initialize AOF manager", e);
                return; // Cannot start server without AOF if required
            }
        } else {
            LOG.info("AOF disabled");
        }

        commandProcessor = new CommandProcessor(pubsub, aofManager);

        if (config.aofEnabled && aofManager != null) {
            try {
                aofManager.replay(db, commandProcessor, writer);
            } catch (IOException e) {
                // Serving a half-loaded dataset is worse than not serving. Clients would
                // see keys as missing and write over them, and those writes would append
                // to this same file -- leaving an AOF that describes a state which never
                // existed. Refuse to start instead.
                LOG.log(Level.SEVERE, "Failed to replay AOF file; refusing to start", e);
                stop();
                throw new IllegalStateException("AOF replay failed for " + config.aofPath, e);
            }
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println(">>> SHUTDOWN HOOK RUNNING <<<"); // unbuffered stderr
            System.err.flush();
            stop();
        }));

        try {
            this.serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true); // allow immediate reuse
            serverSocket.bind(new InetSocketAddress(config.port));

            LOG.info(() -> "Redis server listening on port " + config.port);

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    if (!admit(clientSocket)) {
                        continue;
                    }

                    LOG.info(() -> "New client connected: " + clientSocket.getRemoteSocketAddress());
                    // execute, not submit: submit parks anything the handler throws in a
                    // Future nobody reads, so a thread dying on an Error leaves no trace.
                    threadPool.execute(new ClientHandler(clientSocket, db, commandProcessor, pubsub,
                            config.timeoutSeconds, connectedClients::decrementAndGet));
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

    /**
     * Takes a connection slot, or turns the client away.
     *
     * <p>Each client occupies a thread for as long as it stays connected, so the count of
     * live connections is the real resource. Refusing past the ceiling costs one error
     * reply; accepting without one trades an unbounded number of threads for an
     * out-of-memory that takes the whole server with it.
     */
    private boolean admit(Socket clientSocket) {
        if (connectedClients.incrementAndGet() <= config.maxClients) {
            return true;
        }
        connectedClients.decrementAndGet();
        LOG.warning(() -> "Refusing client, at limit of " + config.maxClients + ": "
                + clientSocket.getRemoteSocketAddress());
        try (Socket refused = clientSocket) {
            writer.writeError(refused.getOutputStream(), "max number of clients reached");
            refused.getOutputStream().flush();
        } catch (IOException ignored) {
            // The client is being dropped either way.
        }
        return false;
    }

    /** Live connections, for tests and for the admission check. */
    public int connectedClients() {
        return connectedClients.get();
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

        // Close the AOF only after the client threads are done writing to it. close()
        // fsyncs, so a clean shutdown is durable even under FsyncPolicy.NO.
        if (aofManager != null) {
            try {
                aofManager.close();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Error closing AOF", e);
            }
        }

        db.shutdown();

        System.err.println("Redis server stopped.");
    }

    public static void main(String[] args) {
        RedisServer server = new RedisServer();
        server.start();
    }
}
