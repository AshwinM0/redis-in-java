package redis;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;

import redis.resp.RespParser;
import redis.resp.RespWriter;
import redis.resp.Value;

public class ClientHandler implements Runnable {
    private final Socket client;
    private final Database db;
    private final RespWriter writer = new RespWriter();
    private final CommandProcessor commandProcessor = new CommandProcessor();

    public ClientHandler(Socket client, Database sharedDB) {
        this.client = client;
        this.db = sharedDB;
    }

    @Override
    public void run() {
        try (
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()))) {
            while (true) {
                try {
                    // Parse next RESP message
                    Value request = RespParser.readValue(in);
                    if (request == null) {
                        System.out.println("Client disconnected: " + client.getInetAddress());
                        break;
                    }

                    // Ensure it's an array type (commands are always arrays)
                    if (!"array".equals(request.typ) || request.array.isEmpty()) {
                        writer.writeError(out, "invalid request");
                        out.flush();
                        continue;
                    }

                    String command = request.array.get(0).str.toUpperCase();
                    commandProcessor.executeCommand(command, db, writer, out, request.array);

                    out.flush();
                } catch (SocketException e) {
                    // Client disconnected abruptly
                    System.out.println("Client disconnected: " + client.getInetAddress());
                    break;
                } catch (IOException e) {
                    // Check for connection reset in the message
                    if (isConnectionReset(e)) {
                        System.out.println("Client disconnected: " + client.getInetAddress());
                        break;
                    }
                    System.err.println("I/O error: " + e.getMessage());
                    break;
                } catch (Exception e) {
                    System.err.println("Internal error: " + e.getMessage());
                    writer.writeError(out, "internal server error");
                    out.flush();
                }
            }
        } catch (IOException e) {
            // Check if this is a normal disconnection scenario
            if (!isConnectionReset(e)) {
                System.err.println("Connection error: " + e.getMessage());
            } else {
                System.out.println("Client disconnected: " + client.getInetAddress());
            }
        } finally {
            try {
                client.close();
            } catch (IOException e) {
                System.err.println("Failed to close socket: " + e.getMessage());
            }
        }
    }

    /**
     * Checks if the exception indicates a client disconnection (connection reset).
     */
    private boolean isConnectionReset(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        message = message.toLowerCase();
        return message.contains("connection reset") ||
                message.contains("broken pipe") ||
                message.contains("socket closed") ||
                message.contains("stream closed");
    }
}
