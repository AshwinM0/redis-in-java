package redis.server;

import java.io.*;
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

    private final Socket client;
    private final Database db;
    private final PubSubManager pubsub;
    private final RespWriter writer = new RespWriter();
    private final CommandProcessor commandProcessor;
    private BufferedWriter out; // Stored as field for cleanup access

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

    @Override
    public void run() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()))) {
            out = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()));
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
                        writer.writeError(out, "invalid request");
                        out.flush();
                        continue;
                    }

                    String command = request.array.get(0).str.toUpperCase();

                    if (pubsub.isSubscribed(out) && !isPubSubAllowedCommand(command)) {
                        continue;
                    }

                    commandProcessor.executeCommand(command, db, writer, out, request.array, false);
                    out.flush();
                } catch (RespParseException e) {
                    // Invalid RESP format - send error but keep connection open
                    writer.writeError(out, "invalid RESP format: " + e.getMessage());
                    out.flush();
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
                        writer.writeError(out, "internal server error");
                        out.flush();
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
