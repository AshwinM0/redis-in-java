package redis.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;

import redis.resp.RespParser;
import redis.core.CommandProcessor;
import redis.core.Database;
import redis.pubsub.PubSubManager;
import redis.resp.RespParseException;
import redis.resp.RespWriter;
import redis.resp.Value;

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
    private OutputStream out; // Stored as field for cleanup access

    public ClientHandler(Socket client, Database sharedDB, CommandProcessor commandProcessor, PubSubManager pubsub) {
        this.client = client;
        this.db = sharedDB;
        this.pubsub = pubsub;
        this.commandProcessor = commandProcessor;
    }

    private static final Set<String> PUBSUB_ALLOWED_COMMANDS = Set.of(
            "SUBSCRIBE", "UNSUBSCRIBE", "PSUBSCRIBE", "PUNSUBSCRIBE", "PING", "QUIT");

    private boolean isPubSubAllowedCommand(String command) {
        return PUBSUB_ALLOWED_COMMANDS.contains(command);
    }

    /**
     * Writes an error reply under this connection's write lock, so it cannot land in the
     * middle of a message being delivered to the same socket by a publishing thread.
     */
    private void writeGuarded(String error) throws IOException {
        synchronized (pubsub.lockFor(out)) {
            writer.writeError(out, error);
            out.flush();
        }
    }

    @Override
    public void run() {
        try (InputStream in = new BufferedInputStream(client.getInputStream(), BUFFER_SIZE)) {
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

                    String command = request.array.get(0).str.toUpperCase();

                    if (pubsub.isSubscribed(out) && !isPubSubAllowedCommand(command)) {
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
