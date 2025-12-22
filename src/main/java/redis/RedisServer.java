package redis;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class RedisServer {
    private final int port;
    private final ExecutorService threadPool;
    private final Database db = new Database();
    private ServerSocket serverSocket;

    public RedisServer() {
        this.port = 6379;
        this.threadPool = Executors.newCachedThreadPool();
    }

    public void start() {
        try {
            this.serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true); // allow immediate reuse
            serverSocket.bind(new InetSocketAddress(port));
            System.out.println("Redis server listening on port " + port);

            while (!serverSocket.isClosed()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("New client connected: " + clientSocket.getInetAddress());
                    threadPool.submit(new ClientHandler(clientSocket, db));
                } catch (SocketException e) {
                    if (!serverSocket.isClosed()) {
                        throw e;
                    }
                    // Server was closed, exit loop
                    break;
                }
            }

        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        } finally {
            stop();
        }
    }

    public void stop() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            threadPool.shutdown();
        } catch (IOException e) {
            System.err.println("Error closing server: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        RedisServer server = new RedisServer();
        server.start();
    }
}
