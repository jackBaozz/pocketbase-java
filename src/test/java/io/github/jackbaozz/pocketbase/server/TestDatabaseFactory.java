package io.github.jackbaozz.pocketbase.server;

import io.github.jackbaozz.pocketbase.server.internal.ExternalDatabaseSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public class TestDatabaseFactory {

  private static MySQLContainer<?> mysql;
  private static PostgreSQLContainer<?> postgres;
  private static final Set<Path> preparedDataDirectories = new HashSet<>();
  private static boolean initialized = false;

  @SuppressWarnings("resource")
  public static synchronized void init() {
    if (initialized) {
      return;
    }

    String storage = System.getProperty("storage", "json").trim().toLowerCase(Locale.ROOT);
    ExternalDatabaseSupport.ResolvedConfig external = ExternalDatabaseSupport.resolve(storage);
    if (external != null) {
      external.applySystemProperties();
      initialized = true;
      System.err.println("Using external " + storage + " test database from " + external.source());
      return;
    }

    try {
      switch (storage) {
        case "mysql", "mariadb" -> {
          mysql =
              new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                  .withDatabaseName("pocketbase")
                  .withUsername("pb")
                  .withPassword("secret");
          mysql.start();
          System.setProperty("mysql.url", mysql.getJdbcUrl());
          System.setProperty("db.user", mysql.getUsername());
          System.setProperty("db.password", mysql.getPassword());
        }
        case "postgres", "postgresql" -> {
          postgres =
              new PostgreSQLContainer<>(DockerImageName.parse("postgres:15"))
                  .withDatabaseName("pocketbase")
                  .withUsername("pb")
                  .withPassword("secret");
          postgres.start();
          System.setProperty("postgres.url", postgres.getJdbcUrl());
          System.setProperty("db.user", postgres.getUsername());
          System.setProperty("db.password", postgres.getPassword());
        }
      }
      initialized = true;
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        if (mysql != null) {
          mysql.stop();
        }
        if (postgres != null) {
          postgres.stop();
        }
      }));
    } catch (Exception e) {
      String message =
          "Skipping "
              + storage
              + " tests because no external DSN is configured and Testcontainers could not start: "
              + e.getMessage();
      System.err.println(message);
      Assumptions.assumeTrue(false, message);
    }
  }

  /**
   * Starts a test server with an isolated external database schema when the Maven storage matrix
   * selects MySQL or PostgreSQL.
   *
   * <p>
   * The relational engines intentionally share one Testcontainers database for the Maven JVM. A
   * JUnit {@code @TempDir} still identifies a single test's logical data directory, so it is used
   * as the isolation boundary here. Re-starting a server with the same directory does not reset the
   * database, preserving persistence/restart assertions.
   */
  public static LocalPocketBase start(ServerConfig config) throws IOException {
    init();
    prepareFor(config.dataDir());
    return LocalPocketBase.start(config);
  }

  private static synchronized void prepareFor(Path dataDir) throws IOException {
    String storage = storage();
    if (!isExternalStorage(storage)) {
      return;
    }

    Path key = dataDir.toAbsolutePath().normalize();
    if (preparedDataDirectories.contains(key)) {
      return;
    }

    try {
      resetExternalDatabase(storage);
      preparedDataDirectories.add(key);
    } catch (SQLException e) {
      throw new IOException(
          "Failed to reset the external " + storage + " test database for " + key + ".", e);
    }
  }

  private static void resetExternalDatabase(String storage) throws SQLException {
    String prefix =
        switch (storage) {
          case "mysql", "mariadb" -> "mysql";
          case "postgres", "postgresql" -> "postgres";
          default ->
            throw new IllegalArgumentException("Unsupported external test storage: " + storage);
        };
    String url = firstNonBlank(System.getProperty(prefix + ".url"), System.getProperty("db.url"));
    String user =
        firstNonBlank(System.getProperty(prefix + ".user"), System.getProperty("db.user"));
    String password =
        firstNonBlank(System.getProperty(prefix + ".password"), System.getProperty("db.password"));
    if (url == null) {
      throw new SQLException("No JDBC URL configured for external " + storage + " test database.");
    }

    try (Connection connection = openConnection(url, user, password)) {
      switch (prefix) {
        case "mysql" -> resetMysql(connection);
        case "postgres" -> resetPostgres(connection);
        default -> throw new IllegalStateException("Unexpected external test storage: " + prefix);
      }
    }
  }

  private static Connection openConnection(String url, String user, String password)
      throws SQLException {
    if (user == null) {
      return DriverManager.getConnection(url);
    }
    return DriverManager.getConnection(url, user, password == null ? "" : password);
  }

  private static void resetMysql(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET FOREIGN_KEY_CHECKS = 0");
      try {
        dropMysqlObjects(connection, statement, "VIEW", "DROP VIEW IF EXISTS ");
        dropMysqlObjects(connection, statement, "BASE TABLE", "DROP TABLE IF EXISTS ");
      } finally {
        statement.execute("SET FOREIGN_KEY_CHECKS = 1");
      }
    }
  }

  private static void dropMysqlObjects(
      Connection connection, Statement statement, String tableType, String dropPrefix)
      throws SQLException {
    try (Statement lookup = connection.createStatement();
        ResultSet objects =
            lookup.executeQuery(
                """
                    SELECT TABLE_NAME
                    FROM INFORMATION_SCHEMA.TABLES
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = '%s'
                    """
                    .formatted(tableType))) {
      while (objects.next()) {
        statement.execute(dropPrefix + mysqlIdentifier(objects.getString(1)));
      }
    }
  }

  private static void resetPostgres(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS public CASCADE");
      statement.execute("CREATE SCHEMA public");
    }
  }

  private static String mysqlIdentifier(String value) {
    return "`" + value.replace("`", "``") + "`";
  }

  private static boolean isExternalStorage(String storage) {
    return "mysql".equals(storage)
        || "mariadb".equals(storage)
        || "postgres".equals(storage)
        || "postgresql".equals(storage);
  }

  private static String storage() {
    return System.getProperty("storage", "json").trim().toLowerCase(Locale.ROOT);
  }

  private static String firstNonBlank(String first, String second) {
    if (first != null && !first.isBlank()) {
      return first;
    }
    if (second != null && !second.isBlank()) {
      return second;
    }
    return null;
  }
}
