package io.github.jackbaozz.pocketbase.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Runtime configuration for the embedded PocketBase-like server. */
public record ServerConfig(
    String host,
    int port,
    Path dataDir,
    String bootstrapSuperuserEmail,
    String bootstrapSuperuserPassword,
    String encryptionEnv,
    String applicationName,
    String storageType,
    String databaseUrl,
    String databaseUser,
    String databasePassword,
    String profile) {
  public static final int DEFAULT_PORT = 8090;

  /** Backwards-compatible constructor for embedded applications and existing integrations. */
  public ServerConfig(
      String host,
      int port,
      Path dataDir,
      String bootstrapSuperuserEmail,
      String bootstrapSuperuserPassword,
      String encryptionEnv) {
    this(
        host,
        port,
        dataDir,
        bootstrapSuperuserEmail,
        bootstrapSuperuserPassword,
        encryptionEnv,
        null,
        "sqlite",
        null,
        null,
        null,
        null);
  }

  /** Backwards-compatible constructor for callers using the extended configuration fields. */
  public ServerConfig(
      String host,
      int port,
      Path dataDir,
      String bootstrapSuperuserEmail,
      String bootstrapSuperuserPassword,
      String encryptionEnv,
      String applicationName,
      String storageType,
      String databaseUrl,
      String databaseUser,
      String databasePassword) {
    this(
        host,
        port,
        dataDir,
        bootstrapSuperuserEmail,
        bootstrapSuperuserPassword,
        encryptionEnv,
        applicationName,
        storageType,
        databaseUrl,
        databaseUser,
        databasePassword,
        null);
  }

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
    applicationName = blankToNull(applicationName);
    storageType = blankToNull(storageType);
    databaseUrl = blankToNull(databaseUrl);
    databaseUser = blankToNull(databaseUser);
    databasePassword = blankToNull(databasePassword);
    profile = normalizeProfile(profile);
  }

  public static ServerConfig defaults() {
    return new ServerConfig(
        "127.0.0.1",
        DEFAULT_PORT,
        Path.of("pb_data"),
        null,
        null,
        null,
        null,
        "sqlite",
        null,
        null,
        null,
        null);
  }

  public static ServerConfig fromArgs(String[] args) {
    return fromArgs(args, System.getenv());
  }

  public static ServerConfig fromArgs(String[] args, Map<String, String> env) {
    ConfigFileSelection configFile = selectConfigFile(args, env);
    Properties baseProperties = loadProperties(configFile, null);
    String profile = selectProfile(args, env, baseProperties);
    Properties file = profile == null ? baseProperties : loadProperties(configFile, profile);
    String host = configured(file, env, "server.host", "PB_HTTP_HOST", "127.0.0.1", "server.host");
    int port =
        parsePort(
            configured(
                file, env, "server.port", "PB_HTTP_PORT", String.valueOf(DEFAULT_PORT), "server.port"));
    Path dataDir =
        Path.of(configured(file, env, "server.data-dir", "PB_DATA_DIR", "pb_data", "server.data-dir"));
    String email = configured(file, env, "superuser.email", "PB_SUPERUSER_EMAIL", null, "superuser.email");
    String password =
        configured(file, env, "superuser.password", "PB_SUPERUSER_PASSWORD", null, "superuser.password");
    String encryptionEnv =
        configured(
            file,
            env,
            "security.encryption-key",
            "PB_ENCRYPTION_KEY",
            null,
            "security.encryption-key");
    String applicationName =
        configured(file, env, "app.name", "PB_APP_NAME", null, "app.name");
    String storageType = configured(file, env, "storage", "PB_STORAGE", null, "storage.type", "storage");
    String databaseUrl = configured(file, env, "db.url", "PB_DATABASE_URL", null, "database.url");
    String databaseUser = configured(file, env, "db.user", "PB_DATABASE_USER", null, "database.user");
    String databasePassword =
        configured(file, env, "db.password", "PB_DATABASE_PASSWORD", null, "database.password");

    for (int i = 0; args != null && i < args.length; i++) {
      String arg = args[i];
      if ("start".equals(arg)) {
        continue;
      }
      if ("--help".equals(arg) || "-h".equals(arg)) {
        throw new HelpRequested();
      }
      if ("--config".equals(arg) && i + 1 < args.length) {
        i++;
        continue;
      }
      if ("--profile".equals(arg) && i + 1 < args.length) {
        i++;
        continue;
      }
      if ("--dir".equals(arg) && i + 1 < args.length) {
        dataDir = Path.of(args[++i]);
        continue;
      }
      if ("--encryptionEnv".equals(arg) && i + 1 < args.length) {
        encryptionEnv = args[++i];
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
      if ("--app-name".equals(arg) && i + 1 < args.length) {
        applicationName = args[++i];
        continue;
      }
      if ("--storage".equals(arg) && i + 1 < args.length) {
        storageType = args[++i];
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
      if (arg.startsWith("--encryptionEnv=")) {
        encryptionEnv = arg.substring("--encryptionEnv=".length());
        continue;
      }
      if (arg.startsWith("--config=")) {
        continue;
      }
      if (arg.startsWith("--profile=")) {
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
      if (arg.startsWith("--app-name=")) {
        applicationName = arg.substring("--app-name=".length());
        continue;
      }
      if (arg.startsWith("--storage=")) {
        storageType = arg.substring("--storage=".length());
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

    return new ServerConfig(
        host,
        port,
        dataDir,
        email,
        password,
        encryptionEnv,
        applicationName,
        storageType,
        databaseUrl,
        databaseUser,
        databasePassword,
        profile);
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
          pocketbase-java start [--config application.properties] [--http 127.0.0.1:8090] [--dir pb_data]
                                [--app-name pocketbase-java] [--profile dev]
                                [--storage jsonl|sqlite|mysql|postgresql]
          pocketbase-java                         (start is optional)

        Configuration:
          config/application.properties preferred external file; root file is a fallback
          src/main/resources/application.properties bundled safe defaults used when no external file exists
          --config <path>              explicitly select a properties file
          PB_CONFIG_FILE               explicitly select a properties file
          --profile <name>             load application-<name>.properties overrides
          PB_PROFILE / -Dapp.profile   select an optional profile
          app.profile                  select an optional profile from the base properties file
          app.name                     application name shown by the server and Admin UI
          server.host                  bind host, default 127.0.0.1
          server.port                  bind port, default 8090
          server.data-dir              data directory, default pb_data
          storage.type                 sqlite (default), jsonl (legacy file mode), mysql, or postgresql
          database.url                 JDBC URL for MySQL/PostgreSQL
          database.user                database username
          database.password            database password

        Environment overrides properties; JVM -D values override both:
          PB_HTTP_HOST              bind host, default 127.0.0.1
          PB_HTTP_PORT              bind port, default 8090
          PB_DATA_DIR               data directory, default pb_data
          PB_SUPERUSER_EMAIL        optional first superuser email
          PB_SUPERUSER_PASSWORD     optional first superuser password
          PB_ENCRYPTION_KEY         optional encryption key
          PB_STORAGE                optional storage engine (sqlite, mysql, postgresql)
          PB_PROFILE                optional profile name
        """;
  }

  private static ConfigFileSelection selectConfigFile(String[] args, Map<String, String> env) {
    String configuredPath = optionValue(args, "--config");
    if (configuredPath == null || configuredPath.isBlank()) {
      configuredPath = firstNonBlank(System.getProperty("config.file"), env.get("PB_CONFIG_FILE"));
    }

    Path path = null;
    if (configuredPath != null && !configuredPath.isBlank()) {
      path = Path.of(configuredPath.trim());
      if (!Files.isRegularFile(path)) {
        throw new IllegalArgumentException("configuration file not found: " + path);
      }
      return new ConfigFileSelection(path);
    } else {
      Path configDirectoryFile = Path.of("config", "application.properties");
      Path workingDirectoryFile = Path.of("application.properties");
      if (Files.isRegularFile(configDirectoryFile)) {
        path = configDirectoryFile;
      } else if (Files.isRegularFile(workingDirectoryFile)) {
        path = workingDirectoryFile;
      }
    }
    return new ConfigFileSelection(path);
  }

  private static String selectProfile(String[] args, Map<String, String> env, Properties properties) {
    String commandLineProfile = optionValue(args, "--profile");
    String selected =
        firstNonBlank(
            commandLineProfile,
            configured(properties, env, "app.profile", "PB_PROFILE", null, "app.profile"));
    return normalizeProfile(selected);
  }

  private static Properties loadProperties(ConfigFileSelection configFile, String profile) {
    Properties properties = new Properties();
    loadResourceProperties(properties, "/application.properties");
    if (profile != null) {
      loadResourceProperties(properties, "/application-" + profile + ".properties");
    }
    loadFileProperties(properties, configFile.path());
    if (profile != null) {
      loadFileProperties(properties, profileFile(configFile.path(), profile));
    }
    return properties;
  }

  private static void loadResourceProperties(Properties properties, String resourceName) {
    InputStream stream = ServerConfig.class.getResourceAsStream(resourceName);
    if (stream == null) return;
    try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (IOException e) {
      throw new IllegalStateException("failed to read bundled configuration: " + resourceName, e);
    }
  }

  private static void loadFileProperties(Properties properties, Path path) {
    if (path == null || !Files.isRegularFile(path)) return;
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (IOException e) {
      throw new IllegalArgumentException("failed to read configuration file: " + path, e);
    }
  }

  private static Path profileFile(Path baseFile, String profile) {
    if (baseFile == null) {
      Path configProfile = Path.of("config", "application-" + profile + ".properties");
      if (Files.isRegularFile(configProfile)) return configProfile;
      Path workingDirectoryProfile = Path.of("application-" + profile + ".properties");
      return Files.isRegularFile(workingDirectoryProfile) ? workingDirectoryProfile : configProfile;
    }

    String filename = baseFile.getFileName().toString();
    int extension = filename.lastIndexOf('.');
    String baseName = extension > 0 ? filename.substring(0, extension) : filename;
    String suffix = extension > 0 ? filename.substring(extension) : ".properties";
    Path parent = baseFile.getParent();
    String profileFilename = baseName + "-" + profile + suffix;
    return parent == null ? Path.of(profileFilename) : parent.resolve(profileFilename);
  }

  private static String optionValue(String[] args, String option) {
    for (int i = 0; args != null && i < args.length; i++) {
      String arg = args[i];
      if (option.equals(arg) && i + 1 < args.length) {
        return args[i + 1];
      }
      String prefix = option + "=";
      if (arg != null && arg.startsWith(prefix)) {
        return arg.substring(prefix.length());
      }
    }
    return null;
  }

  private static String configured(
      Properties file,
      Map<String, String> env,
      String systemProperty,
      String environmentVariable,
      String fallback,
      String... propertyKeys) {
    String value = firstNonBlank(System.getProperty(systemProperty), env.get(environmentVariable));
    if (value != null) {
      return value.trim();
    }
    for (String key : propertyKeys) {
      value = firstNonBlank(file.getProperty(key), null);
      if (value != null) {
        return value.trim();
      }
    }
    return fallback;
  }

  private static String firstNonBlank(String first, String fallback) {
    return first == null || first.isBlank() ? fallback : first;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String normalizeProfile(String value) {
    String profile = blankToNull(value);
    if (profile == null) return null;
    if (!profile.matches("[A-Za-z0-9][A-Za-z0-9_-]*")) {
      throw new IllegalArgumentException("invalid profile: " + profile);
    }
    return profile;
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

  private record ConfigFileSelection(Path path) {
  }
}
