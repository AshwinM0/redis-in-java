package redis.config;

import java.nio.file.Path;
import java.util.Locale;

import redis.persistence.AofManager.FsyncPolicy;

public class ServerConfig {

        public final int port;
        public final boolean aofEnabled;
        public final Path aofPath;
        public final FsyncPolicy fsyncPolicy;
        public final int maxThreads;

        public ServerConfig(
                        int port,
                        boolean aofEnabled,
                        Path aofPath,
                        FsyncPolicy fsyncPolicy,
                        int maxThreads) {
                this.port = port;
                this.aofEnabled = aofEnabled;
                this.aofPath = aofPath;
                this.fsyncPolicy = fsyncPolicy;
                this.maxThreads = maxThreads;
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

                return new ServerConfig(
                                port,
                                aofEnabled,
                                aofPath,
                                fsyncPolicy,
                                maxThreads);
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