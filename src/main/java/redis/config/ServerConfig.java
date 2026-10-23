package redis.config;

import java.nio.file.Path;
import java.util.Locale;

import redis.persistence.AofManager.FsyncPolicy;

public class ServerConfig {

        /** Loopback: only this machine can connect. Redis ships the same default. */
        public static final String DEFAULT_BIND = "127.0.0.1";

        public final int port;

        /**
         * The address to listen on. Anything other than loopback should come with a
         * {@link #password}: whoever can reach the port can read and change every key, and
         * on a chat backend publish forged events to any user.
         */
        public final String bind;

        /**
         * What AUTH checks, or null for no password. With one set, a connection can do
         * nothing but AUTH or QUIT until it authenticates.
         */
        public final String password;
        public final boolean aofEnabled;
        public final Path aofPath;
        public final FsyncPolicy fsyncPolicy;
        public final int maxThreads;

        /**
         * Close a client that has sent nothing for this many seconds. 0 disables it,
         * which is Redis's default. Subscribers are exempt: sitting silent waiting for
         * messages is the whole point of being subscribed.
         */
        public final int timeoutSeconds;

        /**
         * Refuse connections past this many concurrent clients. Each client holds a
         * thread for as long as it stays connected, so without a ceiling a connection
         * flood becomes an out-of-memory.
         */
        public final int maxClients;

        public ServerConfig(
                        int port,
                        boolean aofEnabled,
                        Path aofPath,
                        FsyncPolicy fsyncPolicy,
                        int maxThreads) {
                this(port, aofEnabled, aofPath, fsyncPolicy, maxThreads, 0, 10_000);
        }

        public ServerConfig(
                        int port,
                        boolean aofEnabled,
                        Path aofPath,
                        FsyncPolicy fsyncPolicy,
                        int maxThreads,
                        int timeoutSeconds,
                        int maxClients) {
                this(port, aofEnabled, aofPath, fsyncPolicy, maxThreads, timeoutSeconds, maxClients,
                                DEFAULT_BIND, null);
        }

        public ServerConfig(
                        int port,
                        boolean aofEnabled,
                        Path aofPath,
                        FsyncPolicy fsyncPolicy,
                        int maxThreads,
                        int timeoutSeconds,
                        int maxClients,
                        String bind,
                        String password) {
                this.port = port;
                this.bind = bind;
                this.password = password;
                this.aofEnabled = aofEnabled;
                this.aofPath = aofPath;
                this.fsyncPolicy = fsyncPolicy;
                this.maxThreads = maxThreads;
                this.timeoutSeconds = timeoutSeconds;
                this.maxClients = maxClients;
        }

        public static ServerConfig fromEnv() {
                int port = Integer.parseInt(env("REDIS_PORT", "6379"));

                boolean aofEnabled = Boolean.parseBoolean(
                                env("REDIS_AOF_ENABLED", "false"));

                Path aofPath = Path.of(
                                env("REDIS_AOF_PATH", "appendonly.aof"));

                FsyncPolicy fsyncPolicy = FsyncPolicy.valueOf(
                                env("REDIS_AOF_FSYNC", "EVERYSEC")
                                                .toUpperCase(Locale.ROOT));

                int maxThreads = Integer.parseInt(
                                env("REDIS_MAX_THREADS", "0"));

                int timeoutSeconds = Integer.parseInt(
                                env("REDIS_TIMEOUT", "0"));

                int maxClients = Integer.parseInt(
                                env("REDIS_MAX_CLIENTS", "10000"));

                String bind = env("REDIS_BIND", DEFAULT_BIND);

                // env() treats an empty value as unset, so REDIS_PASSWORD= means no password.
                String password = env("REDIS_PASSWORD", null);

                return new ServerConfig(
                                port,
                                aofEnabled,
                                aofPath,
                                fsyncPolicy,
                                maxThreads,
                                timeoutSeconds,
                                maxClients,
                                bind,
                                password);
        }

        private static final java.util.Map<String, String> dotenv = new java.util.HashMap<>();

        static {
                loadEnv();
        }

        private static void loadEnv() {
                Path envPath = Path.of(".env");
                if (!java.nio.file.Files.exists(envPath)) {
                        System.out.println("[ServerConfig] .env file not found at " + envPath.toAbsolutePath());
                        return;
                }

                try (java.util.stream.Stream<String> lines = java.nio.file.Files.lines(envPath)) {
                        lines.filter(line -> !line.isBlank() && !line.trim().startsWith("#"))
                                        .forEach(line -> {
                                                String[] parts = line.split("=", 2);
                                                if (parts.length == 2) {
                                                        String key = parts[0].trim();
                                                        String value = parts[1].trim();
                                                        // Remove optional quotes
                                                        if ((value.startsWith("\"") && value.endsWith("\"")) ||
                                                                        (value.startsWith("'")
                                                                                        && value.endsWith("'"))) {
                                                                value = value.substring(1, value.length() - 1);
                                                        }
                                                        dotenv.put(key, value);
                                                }
                                        });
                        System.out.println("[ServerConfig] Loaded .env file");
                } catch (java.io.IOException e) {
                        System.err.println("[ServerConfig] Failed to load .env file: " + e.getMessage());
                }
        }

        private static String env(String key, String def) {
                // Priority 1: System environment variables (standard override)
                String val = System.getenv(key);

                // Priority 2: .env file
                if (val == null || val.isBlank()) {
                        val = dotenv.get(key);
                }

                return (val == null || val.isBlank()) ? def : val;
        }
}