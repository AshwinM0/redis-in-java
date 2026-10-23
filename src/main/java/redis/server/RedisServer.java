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
    private final Password password;
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
        this.password = Password.of(config.password);
        this.threadPool = (config.maxThreads <= 0)
                ? Executors.newCachedThreadPool()
                : Executors.newFixedThreadPool(config.maxThreads);
    }

    /**
     * Runs the server on the calling thread until {@link #stop()}.
     *
     * <p>Returns normally only when stop() ended it. If the server cannot start, or stops
     * accepting connections on its own, this throws instead, so that {@link #main} exits
     * non-zero and whatever is supervising the process can tell a failure from a shutdown.
     */
    public void start() {
        if (config.aofEnabled) {
            try {
                aofManager = new AofManager(config.aofPath, writer, config.fsyncPolicy);
            } catch (IOException e) {
                // Serving without the log that was asked for would acknowledge writes that
                // a restart then loses.
                stop();
                throw new IllegalStateException("Could not open AOF file " + config.aofPath, e);
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
            // Port alone would mean every interface. Taking the address from config is what
            // keeps the default reachable from this machine only.
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName(config.bind), config.port));
        } catch (IOException e) {
            // A busy port, or an address this machine doesn't have. Returning here used to
            // let main() exit 0, which a supervisor or a script waiting on the process reads
            // as a clean shutdown.
            stop();
            throw new IllegalStateException("Could not listen on " + config.bind + ":" + config.port, e);
        }

        // Scripts wait for "listening on port" to know the server is up; keep that wording.
        LOG.info(() -> "Redis server listening on port " + serverSocket.getLocalPort()
                + " (bind " + serverSocket.getInetAddress().getHostAddress() + ")");
        if (!password.isSet() && !serverSocket.getInetAddress().isLoopbackAddress()) {
            // Redis refuses outside clients outright in this state ("protected mode"). This
            // only warns, but the loopback default keeps a fresh install out of it.
            LOG.warning(() -> "No REDIS_PASSWORD is set and other machines can connect (bind "
                    + serverSocket.getInetAddress().getHostAddress()
                    + "). Anyone who can reach this port can read and change every key.");
        }

        try {
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    if (!admit(clientSocket)) {
                        continue;
                    }

                    LOG.info(() -> "New client connected: " + clientSocket.getRemoteSocketAddress());
                    // execute, not submit: submit parks anything the handler throws in a
                    // Future nobody reads, so a thread dying on an Error leaves no trace.
                    try {
                        threadPool.execute(new ClientHandler(clientSocket, db, commandProcessor, pubsub,
                                config.timeoutSeconds, connectedClients::decrementAndGet, password));
                    } catch (RejectedExecutionException e) {
                        // The pool refuses work only once stop() has shut it down, and that can
                        // land between accept() returning and this call. Checking `running`
                        // first would not close the gap. It is a requested stop, like the
                        // SocketException below, so drop the client and leave normally.
                        connectedClients.decrementAndGet();
                        closeQuietly(clientSocket);
                        break;
                    }
                } catch (SocketException e) {
                    if (!running) {
                        break; // stop() closed the socket to end accept(): the normal way out
                    }
                    throw e;
                }
            }

        } catch (IOException e) {
            // Still meant to be running, so the server stopped serving on its own.
            throw new IllegalStateException(
                    "Stopped accepting connections on " + config.bind + ":" + config.port, e);
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

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // The server is going away either way.
        }
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
        try {
            new RedisServer().start();
        } catch (RuntimeException e) {
            // start() returns normally only after stop(), so anything thrown means the server
            // could not serve. Falling off the end of main would exit 0 and look like a clean
            // stop; the exit code is how a supervisor, a script or a test harness finds out.
            LOG.log(Level.SEVERE, "Server failed", e);
            System.exit(1);
        }
    }
}
