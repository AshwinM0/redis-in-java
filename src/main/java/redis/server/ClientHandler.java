package redis.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import redis.resp.RespParser;
import redis.core.CommandProcessor;
import redis.core.Database;
import redis.pubsub.PubSubManager;
import redis.resp.RespParseException;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClientHandler implements Runnable {

    private static final Logger LOG = Logger.getLogger(ClientHandler.class.getName());

    private static final int BUFFER_SIZE = 16 * 1024;

    private final Socket client;
    private final Database db;
    private final PubSubManager pubsub;
    private final RespWriter writer = new RespWriter();
    private final CommandProcessor commandProcessor;
    private final int idleTimeoutSeconds;
    private final Runnable onClose;
    private final Password password;
    private OutputStream out; // Stored as field for cleanup access

    /**
     * Whether this connection may run commands: true from the start when there is no
     * password, otherwise once AUTH succeeds. Only this connection's thread touches it.
     */
    private boolean authenticated;

    public ClientHandler(Socket client, Database sharedDB, CommandProcessor commandProcessor, PubSubManager pubsub) {
        this(client, sharedDB, commandProcessor, pubsub, 0, () -> {
        }, Password.NONE);
    }

    public ClientHandler(Socket client, Database sharedDB, CommandProcessor commandProcessor, PubSubManager pubsub,
            int idleTimeoutSeconds, Runnable onClose, Password password) {
        this.client = client;
        this.db = sharedDB;
        this.pubsub = pubsub;
        this.commandProcessor = commandProcessor;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.onClose = onClose;
        this.password = password;
        this.authenticated = !password.isSet();
    }

    private static final Set<String> PUBSUB_ALLOWED_COMMANDS = Set.of(
            "SUBSCRIBE", "UNSUBSCRIBE", "PSUBSCRIBE", "PUNSUBSCRIBE", "PING", "QUIT", "RESET");

    private static boolean hasNullArgument(java.util.List<redis.resp.Value> args) {
        for (redis.resp.Value arg : args) {
            if (arg == null || arg.str == null) {
                return true;
            }
        }
        return false;
    }

    private boolean isPubSubAllowedCommand(String command) {
        return PUBSUB_ALLOWED_COMMANDS.contains(command);
    }

    private void writeGuarded(String error) throws IOException {
        writeGuarded("ERR", error);
    }

    /**
     * Writes an error reply under this connection's write lock, so it cannot land in the
     * middle of a message being delivered to the same socket by a publishing thread.
     */
    private void writeGuarded(String code, String error) throws IOException {
        synchronized (pubsub.lockFor(out)) {
            writer.writeError(out, code, error);
            out.flush();
        }
    }

    /**
     * AUTH password, or AUTH username password as Redis 6 added it. There are no users here
     * beyond Redis's "default", which is the name clients send when a URL gives none.
     *
     * <p>A failed attempt leaves the connection as it was, as in Redis: a client that has
     * already authenticated stays authenticated.
     */
    private void auth(List<Value> args) throws IOException {
        if (args.size() < 2) {
            writeGuarded("wrong number of arguments for 'auth' command");
            return;
        }
        if (args.size() > 3) {
            writeGuarded("syntax error");
            return;
        }
        boolean withUsername = args.size() == 3;
        if (!withUsername && !password.isSet()) {
            // Redis answers this too. Accepting quietly would hide a client that was set up
            // for a different server.
            writeGuarded("AUTH <password> called without any password configured for the default user."
                    + " Are you sure your configuration is correct?");
            return;
        }
        boolean knownUser = !withUsername || "default".equals(args.get(1).str);
        String attempt = args.get(args.size() - 1).str;
        if (!knownUser || (password.isSet() && !password.matches(attempt))) {
            writeGuarded("WRONGPASS", "invalid username-password pair or user is disabled.");
            return;
        }
        authenticated = true;
        synchronized (pubsub.lockFor(out)) {
            writer.writeSimple(out, "OK");
            out.flush();
        }
    }

    @Override
    public void run() {
        try (InputStream in = new BufferedInputStream(client.getInputStream(), BUFFER_SIZE)) {
            if (idleTimeoutSeconds > 0) {
                client.setSoTimeout(idleTimeoutSeconds * 1000);
            }
            out = new BufferedOutputStream(client.getOutputStream(), BUFFER_SIZE);
            while (true) {
                try {
                    // Parse next RESP message
                    Value request = RespParser.readValue(in);
                    if (request == null) {
                        LOG.info(() -> "Client disconnected: " + client.getRemoteSocketAddress());
                        break;
                    }

                    // Ensure it's an array type (commands are always arrays)
                    if (!"array".equals(request.typ) || request.array.isEmpty()) {
                        writeGuarded("invalid request");
                        continue;
                    }

                    // A null bulk ($-1) inside a command array leaves Value.str null.
                    // Letting one through reaches the stores as a null key or value and
                    // surfaces as "internal server error" from a NullPointerException.
                    if (hasNullArgument(request.array)) {
                        writeGuarded("Protocol error: unexpected null argument");
                        break;
                    }

                    String command = request.array.get(0).str.toUpperCase(Locale.ROOT);

                    // Until AUTH succeeds a client may only authenticate or leave. Everything
                    // else is refused before it is even looked up, so an unauthenticated
                    // client learns nothing, not even which commands exist.
                    if (!authenticated && !"AUTH".equals(command) && !"QUIT".equals(command)) {
                        writeGuarded("NOAUTH", "Authentication required.");
                        continue;
                    }

                    if (pubsub.isSubscribed(out) && !isPubSubAllowedCommand(command)) {
                        // Dropping this silently leaves the client blocked on a read that
                        // will never be answered. Every request gets a reply.
                        writeGuarded("Can't execute '" + command.toLowerCase(Locale.ROOT)
                                + "': only (P|S)SUBSCRIBE / (P|S)UNSUBSCRIBE / PING / QUIT / RESET"
                                + " are allowed in this context");
                        continue;
                    }

                    // AUTH changes this connection's state, which commands have no access to,
                    // so it is answered here rather than by the CommandProcessor.
                    if ("AUTH".equals(command)) {
                        auth(request.array);
                        continue;
                    }

                    // A subscribed connection has a second writer: any thread delivering
                    // a published message. Both go through the connection's lock, so a
                    // reply and a message can never interleave inside one frame.
                    if (commandProcessor.writesToOtherConnections(command)) {
                        commandProcessor.executeCommand(command, db, writer, out, request.array, false);
                    } else {
                        synchronized (pubsub.lockFor(out)) {
                            commandProcessor.executeCommand(command, db, writer, out, request.array, false);
                            out.flush();
                        }
                    }

                    if ("QUIT".equals(command)) {
                        LOG.info(() -> "Client sent QUIT: " + client.getRemoteSocketAddress());
                        break;
                    }
                } catch (RespParseException e) {
                    // Invalid RESP format - the stream is no longer trustworthy, so
                    // report and close rather than trying to resync mid-message.
                    writeGuarded("Protocol error: " + e.getMessage());
                    break;
                } catch (SocketTimeoutException e) {
                    // A subscriber is supposed to sit silent waiting for messages, so the
                    // idle timeout does not apply to one. Redis exempts them the same way.
                    if (pubsub.isSubscribed(out)) {
                        continue;
                    }
                    LOG.info(() -> "Closing idle client: " + client.getRemoteSocketAddress());
                    break;

                } catch (EOFException | SocketException e) {
                    // Abrupt or normal client disconnect
                    LOG.info(() -> "Client disconnected: " + client.getRemoteSocketAddress());
                    break;

                } catch (IOException e) {
                    // Real I/O problem
                    LOG.log(Level.WARNING, "I/O error from client " + client.getRemoteSocketAddress(), e);
                    break;

                } catch (Exception e) {
                    // Command bug or unexpected server error
                    LOG.log(Level.SEVERE, "Internal error: " + e.getMessage());
                    try {
                        writeGuarded("internal server error");
                    } catch (IOException ignored) {
                        break; // client probably gone
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to initialize client handler for " + client.getRemoteSocketAddress(), e);
        } finally {
            onClose.run();
            if (out != null) {
                pubsub.unsubscribeAll(out); // Clean up subscriptions
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
            try {
                client.close();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to close socket: " + e.getMessage());
            }
        }
    }
}
