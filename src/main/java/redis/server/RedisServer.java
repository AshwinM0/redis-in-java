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
import redis.time.SystemClock;

import java.net.*;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLSocket;

public class RedisServer {

    private static final Logger LOG = Logger.getLogger(RedisServer.class.getName());

    private final ServerConfig config;
    private final Password password;
    private final ExecutorService threadPool;
    private final Database db = new Database();
    private final PubSubManager pubsub = new PubSubManager();

    private final List<ServerSocket> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;
    private volatile boolean stopped = false;
    private final Admission admission;
    private volatile TlsContext tlsContext; // null without TLS
    private volatile ScheduledExecutorService housekeeping; // deadlines and certificate reloads
    /** Why a listener stopped accepting on its own, if one did. The first failure wins. */
    private final AtomicReference<IllegalStateException> acceptFailure = new AtomicReference<>();

    private final RespWriter writer = new RespWriter();;
    private AofManager aofManager;
    private CommandProcessor commandProcessor;

    public RedisServer() {
        this(ServerConfig.fromEnv());
    }

    public RedisServer(ServerConfig config) {
        this.config = config;
        this.password = Password.of(config.password);
        this.admission = new Admission(config.maxClients, config.maxUnauthenticated,
                config.authTimeoutSeconds, password.isSet(), new SystemClock());
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

        if (config.port == 0 && config.tls == null) {
            stop();
            throw new IllegalStateException("Nothing to listen on: REDIS_PORT is 0 and TLS is off");
        }

        if (config.tls != null) {
            try {
                tlsContext = new TlsContext(config.tls.keystore(), config.tls.keystorePassword(),
                        config.tls.clientCa());
            } catch (IOException | GeneralSecurityException e) {
                // Carrying on without the TLS port that was asked for would leave clients
                // that require encryption with nowhere to connect, or tempt them to plaintext.
                stop();
                throw new IllegalStateException("Could not load TLS settings from " + config.tls.keystore(), e);
            }
        }

        // TLS binds first, so a client that finds the plaintext port up can rely on TLS too.
        ServerSocket tlsListener = (config.tls == null) ? null : listen(config.tls.port());
        ServerSocket plainListener = (config.port == 0) ? null : listen(config.port);

        // Scripts wait for "listening on port" to know the server is up; keep that wording.
        for (ServerSocket listener : listeners) {
            String kind = (listener == tlsListener) ? ", TLS" : "";
            LOG.info(() -> "Redis server listening on port " + listener.getLocalPort()
                    + " (bind " + listener.getInetAddress().getHostAddress() + kind + ")");
        }
        InetAddress bound = listeners.get(0).getInetAddress();
        if (!bound.isLoopbackAddress()) {
            if (!password.isSet()) {
                // Redis refuses outside clients outright in this state ("protected mode").
                // This only warns, but the loopback default keeps a fresh install out of it.
                LOG.warning(() -> "No REDIS_PASSWORD is set and other machines can connect (bind "
                        + bound.getHostAddress()
                        + "). Anyone who can reach this port can read and change every key.");
            }
            if (tlsListener != null && plainListener != null) {
                LOG.warning(() -> "Plaintext port " + config.port + " is open to other machines"
                        + " alongside TLS. Set REDIS_PORT=0 to accept TLS only.");
            }
        }

        startHousekeeping();

        // Each listener needs a thread blocked in accept(). This thread takes one, so start()
        // still returns only once the server stops.
        if (tlsListener != null && plainListener != null) {
            Thread tlsAcceptor = new Thread(() -> acceptLoop(tlsListener, true), "tls-acceptor");
            tlsAcceptor.setDaemon(true);
            tlsAcceptor.start();
        }
        if (plainListener != null) {
            acceptLoop(plainListener, false);
        } else {
            acceptLoop(tlsListener, true);
        }

        IllegalStateException failure = acceptFailure.get();
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Binds a listening socket, or fails the start: a busy port, an address this machine
     * doesn't have. Returning normally here used to let main() exit 0, which a supervisor or a
     * script waiting on the process reads as a clean shutdown.
     */
    private ServerSocket listen(int port) {
        try {
            ServerSocket listener = new ServerSocket();
            listeners.add(listener); // so stop() closes it, whatever happens next
            listener.setReuseAddress(true); // allow immediate reuse
            // Port alone would mean every interface. Taking the address from config is what
            // keeps the default reachable from this machine only.
            listener.bind(new InetSocketAddress(InetAddress.getByName(config.bind), port));
            return listener;
        } catch (IOException e) {
            stop();
            throw new IllegalStateException("Could not listen on " + config.bind + ":" + port, e);
        }
    }

    /**
     * Takes connections from one listener until the server stops. If accepting fails while the
     * server is meant to be running, records why and stops the whole server, which is no
     * longer serving everything it was asked to.
     */
    private void acceptLoop(ServerSocket listener, boolean tls) {
        try {
            while (running) {
                Socket clientSocket = listener.accept();
                Admission.Ticket ticket = admission.admit(clientSocket, tls);
                if (ticket == null) {
                    refuse(clientSocket, tls);
                    continue;
                }

                LOG.info(() -> "New client connected: " + clientSocket.getRemoteSocketAddress()
                        + (tls ? " (TLS)" : ""));
                Runnable session = tls
                        ? () -> serveTls(clientSocket, ticket)
                        : new ClientHandler(clientSocket, db, commandProcessor, pubsub,
                                config.timeoutSeconds, ticket, password);
                // execute, not submit: submit parks anything the handler throws in a
                // Future nobody reads, so a thread dying on an Error leaves no trace.
                try {
                    threadPool.execute(session);
                } catch (RejectedExecutionException e) {
                    // The pool refuses work only once stop() has shut it down, and that can
                    // land between accept() returning and this call. Checking `running` first
                    // would not close the gap. It is a requested stop, so drop the client and
                    // leave normally.
                    ticket.release();
                    closeQuietly(clientSocket);
                    return;
                }
            }
        } catch (IOException e) {
            // stop() closes the listener to end accept(), so after a stop this is the normal
            // way out. Otherwise the server has stopped serving on its own.
            if (running) {
                acceptFailure.compareAndSet(null, new IllegalStateException(
                        "Stopped accepting connections on " + listener.getLocalSocketAddress(), e));
            }
        } finally {
            stop();
        }
    }

    /**
     * A TLS connection, on its own thread: the handshake, then the session. The accept thread
     * never touches TLS, so a client that stalls its handshake holds up only itself, and only
     * until its deadline: Admission counts it as unauthenticated until the handshake is done.
     */
    private void serveTls(Socket accepted, Admission.Ticket ticket) {
        SSLSocket socket;
        try {
            socket = tlsContext.handshake(accepted);
        } catch (IOException e) {
            LOG.info(() -> "TLS handshake failed for " + accepted.getRemoteSocketAddress() + ": " + e.getMessage());
            closeQuietly(accepted);
            ticket.release();
            return;
        }
        // With no password, finishing the handshake is all the server asks before trusting a
        // client. False means the deadline closed this connection while it was handshaking.
        if (!password.isSet() && !ticket.authenticated()) {
            closeQuietly(socket);
            ticket.release();
            return;
        }
        new ClientHandler(socket, db, commandProcessor, pubsub, config.timeoutSeconds, ticket, password).run();
    }

    /**
     * Turns away a client that arrived with the server at {@code maxClients}.
     *
     * <p>Each client occupies a thread for as long as it stays connected, so the count of
     * live connections is the real resource. Refusing past the ceiling costs one error
     * reply; accepting without one trades an unbounded number of threads for an
     * out-of-memory that takes the whole server with it.
     */
    private void refuse(Socket clientSocket, boolean tls) {
        LOG.warning(() -> "Refusing client, at limit of " + config.maxClients + ": "
                + clientSocket.getRemoteSocketAddress());
        if (tls) {
            // An error reply would need a handshake first, here on the accept thread. Closing
            // is the only answer that costs nothing.
            closeQuietly(clientSocket);
            return;
        }
        try (Socket refused = clientSocket) {
            writer.writeError(refused.getOutputStream(), "max number of clients reached");
            refused.getOutputStream().flush();
        } catch (IOException ignored) {
            // The client is being dropped either way.
        }
    }

    /** Periodic upkeep on one daemon thread: connection deadlines, and renewed certificates. */
    private void startHousekeeping() {
        boolean deadlines = (password.isSet() || tlsContext != null) && config.authTimeoutSeconds > 0;
        if (!deadlines && tlsContext == null) {
            return;
        }
        housekeeping = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "housekeeping");
            t.setDaemon(true);
            return t;
        });
        if (deadlines) {
            // Once a second, so a deadline holds to within a second. A connection that is
            // blocked reading cannot notice its own deadline; this thread closes its socket.
            housekeeping.scheduleAtFixedRate(
                    () -> guarded("Closing connections past their deadline", admission::expireOverdue),
                    1, 1, TimeUnit.SECONDS);
        }
        if (tlsContext != null) {
            housekeeping.scheduleAtFixedRate(
                    () -> guarded("Reloading TLS settings", tlsContext::reloadIfChanged),
                    60, 60, TimeUnit.SECONDS);
        }
    }

    /**
     * scheduleAtFixedRate cancels a task for good, and silently, the first time it throws.
     * Catching here keeps the upkeep running for the life of the server.
     */
    private static void guarded(String what, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, what + " failed", e);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // The server is going away either way.
        }
    }

    /** Live connections, for tests. */
    public int connectedClients() {
        return admission.connected();
    }

    public void stop() {
        if (stopped) {
            return; // Already stopped, avoid duplicate cleanup
        }
        stopped = true;
        running = false;

        System.err.println("Shutting down Redis server...");

        for (ServerSocket listener : listeners) {
            try {
                listener.close();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Error closing server socket", e);
            }
        }

        if (housekeeping != null) {
            housekeeping.shutdownNow();
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
