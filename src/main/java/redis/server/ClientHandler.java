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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClientHandler implements Runnable {

    private static final Logger LOG = Logger.getLogger(ClientHandler.class.getName());

    private static final int BUFFER_SIZE = 16 * 1024;

    /** How long a connection closed for its age keeps reading, so no reply is lost to a reset. */
    private static final int LINGER_MILLIS = 2000;

    /**
     * What a connection may send before it authenticates: one flat command of at most ten
     * arguments, none over 16 KB. These are Redis's numbers (CVE-2021-32675). AUTH needs at
     * most three short arguments, so legitimate clients never come near them.
     *
     * <p>Flat matters as much as the numbers. Per-array caps multiply across nesting levels,
     * so without a depth limit ten elements of ten elements, 32 levels deep, would bound
     * nothing.
     */
    private static final RespParser.Limits BEFORE_AUTH =
            new RespParser.Limits(1, 10, 16 * 1024, 16 * 1024);

    private final Socket client;
    /**
     * The TCP socket: {@link #client} itself, or the one beneath it under TLS. Closing this
     * never blocks, which is what Pub/Sub needs to cut off a subscriber stuck mid-write.
     */
    private final Socket transport;
    private final Database db;
    private final PubSubManager pubsub;
    private final RespWriter writer = new RespWriter();
    private final CommandProcessor commandProcessor;
    private final int idleTimeoutSeconds;
    private final Admission.Ticket ticket;
    private final Password password;

    private final long connectedAtNanos = System.nanoTime();
    /** How long this connection may live, jittered; 0 for no limit. */
    private final long maxAgeNanos;
    private OutputStream out; // Stored as field for cleanup access

    /**
     * Whether this connection may run commands: true from the start when there is no
     * password, otherwise once AUTH succeeds. Only this connection's thread touches it.
     */
    private boolean authenticated;

    public ClientHandler(Socket client, Database sharedDB, CommandProcessor commandProcessor, PubSubManager pubsub) {
        this(client, sharedDB, commandProcessor, pubsub, 0, 0, Admission.Ticket.UNTRACKED, Password.NONE);
    }

    public ClientHandler(Socket client, Database sharedDB, CommandProcessor commandProcessor, PubSubManager pubsub,
            int idleTimeoutSeconds, int maxAgeSeconds, Admission.Ticket ticket, Password password) {
        this(client, sharedDB, commandProcessor, pubsub, idleTimeoutSeconds, maxAgeSeconds, ticket, password, client);
    }

    /** @param transport the TCP socket beneath {@code client}, when that is a TLS socket */
    public ClientHandler(Socket client, Database sharedDB, CommandProcessor commandProcessor, PubSubManager pubsub,
            int idleTimeoutSeconds, int maxAgeSeconds, Admission.Ticket ticket, Password password,
            Socket transport) {
        this.client = client;
        this.transport = transport;
        this.db = sharedDB;
        this.pubsub = pubsub;
        this.commandProcessor = commandProcessor;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.maxAgeNanos = (maxAgeSeconds <= 0) ? 0
                : TimeUnit.MILLISECONDS.toNanos(jitteredAgeMillis(maxAgeSeconds * 1000L, ThreadLocalRandom.current()));
        this.ticket = ticket;
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

    /**
     * The age a connection actually gets: between 90% and 100% of the maximum, so connections
     * opened together, after a deploy say, do not all close and reconnect together. Jitter only
     * ever shortens an age, so no connection outlives the configured maximum.
     */
    static long jitteredAgeMillis(long maxAgeMillis, RandomGenerator random) {
        return maxAgeMillis - random.nextLong(maxAgeMillis / 10 + 1);
    }

    /** Time left before this connection's maximum age; Long.MAX_VALUE with no maximum. */
    private long ageLeftNanos() {
        return (maxAgeNanos == 0) ? Long.MAX_VALUE : maxAgeNanos - (System.nanoTime() - connectedAtNanos);
    }

    /**
     * The read timeout for the next request: the idle timeout or the time left before the
     * maximum age, whichever comes first; 0 to wait forever. The age is rounded up, so a read
     * that times out for age really has reached the deadline.
     */
    private int readTimeoutMillis(long ageLeftNanos) {
        long millis = (idleTimeoutSeconds > 0) ? idleTimeoutSeconds * 1000L : Long.MAX_VALUE;
        if (ageLeftNanos != Long.MAX_VALUE) {
            millis = Math.min(millis, (ageLeftNanos + 999_999) / 1_000_000);
        }
        return (millis > Integer.MAX_VALUE) ? 0 : (int) millis;
    }

    /**
     * Ends a connection that has reached its maximum age without destroying replies already
     * sent.
     *
     * <p>Closing a socket that still holds unread input makes TCP send a reset instead of a
     * normal close, and a reset can make the client throw away replies it has not read yet:
     * a pipelining client would lose the answers to commands that ran. So the server first
     * ends its own output, which the client sees right after the last reply, then reads and
     * discards whatever the client is still sending until it hangs up or the linger runs out.
     * Web servers call this a lingering close.
     */
    private void lingeringClose(InputStream in) {
        // A closing connection should not be sent new messages. This also tells Pub/Sub
        // that a write failing from here on is this close, not a reason to cut it off.
        pubsub.closing(out);
        try {
            client.shutdownOutput();
            client.setSoTimeout(LINGER_MILLIS);
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LINGER_MILLIS);
            byte[] discard = new byte[BUFFER_SIZE];
            while (System.nanoTime() < until && in.read(discard) != -1) {
                // Commands that arrive now never run; their bytes are dropped.
            }
        } catch (IOException e) {
            // The client hung up or went quiet. Either way its replies have had their chance.
        }
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
        // The deadline or an eviction may have claimed this connection a moment ago. Its
        // socket is then being closed, and replying OK would tell the client it got in.
        if (!ticket.authenticated()) {
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
            out = new BufferedOutputStream(client.getOutputStream(), BUFFER_SIZE);
            pubsub.connect(out, transport);
            while (true) {
                try {
                    // Past its maximum age, a connection closes here, between commands: the
                    // last reply has gone out and nothing has been read since. Closing in the
                    // middle of a command would leave the client unable to tell whether it
                    // ran, and a retried INCR runs twice.
                    long ageLeft = ageLeftNanos();
                    if (ageLeft <= 0) {
                        LOG.info(() -> "Closing client at its maximum age: " + client.getRemoteSocketAddress());
                        lingeringClose(in);
                        break;
                    }
                    // A blocked read has to wake for the deadline, or an idle connection -- a
                    // subscriber above all -- would outlive it.
                    client.setSoTimeout(readTimeoutMillis(ageLeft));

                    // Parse next RESP message
                    // The NOAUTH check below only sees a request once it has been read in
                    // full, so until AUTH succeeds the reading itself has to be bounded.
                    // Chosen per request, so a command pipelined right behind a successful
                    // AUTH gets the full limits.
                    Value request = RespParser.readValue(in,
                            authenticated ? RespParser.Limits.DEFAULT : BEFORE_AUTH);
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
                    // The read timed out at the deadline. Any request it was part-way through
                    // never ran, so closing still leaves no command half done.
                    if (ageLeftNanos() <= 0) {
                        LOG.info(() -> "Closing client at its maximum age: " + client.getRemoteSocketAddress());
                        lingeringClose(in);
                        break;
                    }
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
            ticket.release();
            if (out != null) {
                pubsub.disconnected(out); // Clean up subscriptions
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
