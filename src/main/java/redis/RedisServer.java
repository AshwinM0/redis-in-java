package redis;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class RedisServer {
    private final int port;
    private final ExecutorService threadPool;
    private final Database db = new Database();

    public RedisServer() {
        this.port = 6379;
        this.threadPool = Executors.newCachedThreadPool();
    }

    public void start() {
        try {
            ServerSocket serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true); // allow immediate reuse
            serverSocket.bind(new InetSocketAddress(port));
            System.out.println("Redis server listening on port " + port);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("New client connected: " + clientSocket.getInetAddress());
                threadPool.submit(new ClientHandler(clientSocket, db));
            }

        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        RedisServer server = new RedisServer();
        server.start();
    }
}
