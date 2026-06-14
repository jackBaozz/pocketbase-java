package io.github.jackbaozz.pocketbase.server;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime configuration for the embedded PocketBase-like server.
 */
public record ServerConfig(
        String host,
        int port,
        Path dataDir,
        String bootstrapSuperuserEmail,
        String bootstrapSuperuserPassword
) {
    public static final int DEFAULT_PORT = 8090;

    public ServerConfig {
        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        if (dataDir == null) {
            dataDir = Path.of("pb_data");
        }
    }

    public static ServerConfig defaults() {
        return new ServerConfig("127.0.0.1", DEFAULT_PORT, Path.of("pb_data"), null, null);
    }

    public static ServerConfig fromArgs(String[] args) {
        return fromArgs(args, System.getenv());
    }

    public static ServerConfig fromArgs(String[] args, Map<String, String> env) {
        String host = firstNonBlank(env.get("PB_HTTP_HOST"), "127.0.0.1");
        int port = parsePort(firstNonBlank(env.get("PB_HTTP_PORT"), String.valueOf(DEFAULT_PORT)));
        Path dataDir = Path.of(firstNonBlank(env.get("PB_DATA_DIR"), "pb_data"));
        String email = firstNonBlank(env.get("PB_SUPERUSER_EMAIL"), null);
        String password = firstNonBlank(env.get("PB_SUPERUSER_PASSWORD"), null);

        for (int i = 0; args != null && i < args.length; i++) {
            String arg = args[i];
            if ("serve".equals(arg)) {
                continue;
            }
            if ("--help".equals(arg) || "-h".equals(arg)) {
                throw new HelpRequested();
            }
            if ("--dir".equals(arg) && i + 1 < args.length) {
                dataDir = Path.of(args[++i]);
                continue;
            }
            if ("--host".equals(arg) && i + 1 < args.length) {
                host = args[++i];
                continue;
            }
            if ("--port".equals(arg) && i + 1 < args.length) {
                port = parsePort(args[++i]);
                continue;
            }
            if ("--http".equals(arg) && i + 1 < args.length) {
                String value = args[++i];
                int split = value.lastIndexOf(':');
                if (split > 0 && split < value.length() - 1) {
                    host = value.substring(0, split);
                    port = parsePort(value.substring(split + 1));
                } else {
                    port = parsePort(value);
                }
                continue;
            }
            if (arg.startsWith("--dir=")) {
                dataDir = Path.of(arg.substring("--dir=".length()));
                continue;
            }
            if (arg.startsWith("--host=")) {
                host = arg.substring("--host=".length());
                continue;
            }
            if (arg.startsWith("--port=")) {
                port = parsePort(arg.substring("--port=".length()));
                continue;
            }
            if (arg.startsWith("--http=")) {
                String value = arg.substring("--http=".length());
                int split = value.lastIndexOf(':');
                if (split > 0 && split < value.length() - 1) {
                    host = value.substring(0, split);
                    port = parsePort(value.substring(split + 1));
                } else {
                    port = parsePort(value);
                }
                continue;
            }
            throw new IllegalArgumentException("unknown argument: " + arg);
        }

        return new ServerConfig(host, port, dataDir, email, password);
    }

    public InetSocketAddress bindAddress() {
        return new InetSocketAddress(host, port);
    }

    public String displayUrl(int actualPort) {
        return "http://" + host + ":" + actualPort;
    }

    public static String usage() {
        return """
                Usage:
                  pocketbase-java serve [--http 127.0.0.1:8090] [--dir pb_data]

                Environment:
                  PB_HTTP_HOST              bind host, default 127.0.0.1
                  PB_HTTP_PORT              bind port, default 8090
                  PB_DATA_DIR               data directory, default pb_data
                  PB_SUPERUSER_EMAIL        optional first superuser email
                  PB_SUPERUSER_PASSWORD     optional first superuser password
                """;
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value.trim().toLowerCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid port: " + value, e);
        }
    }

    public static final class HelpRequested extends RuntimeException {
    }
}
