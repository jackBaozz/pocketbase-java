package io.github.jackbaozz.pocketbase.server.internal;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

public final class JooqDatabase implements AutoCloseable {
  public enum Engine {
    SQLITE(SQLDialect.SQLITE),
    MYSQL(SQLDialect.MYSQL),
    POSTGRES(SQLDialect.POSTGRES);

    private final SQLDialect dialect;

    Engine(SQLDialect dialect) {
      this.dialect = dialect;
    }

    SQLDialect dialect() {
      return dialect;
    }

    public static Engine fromStorageType(String storageType) {
      String normalized =
          storageType == null || storageType.isBlank()
              ? "sqlite"
              : storageType.trim().toLowerCase(Locale.ROOT);
      return switch (normalized) {
        case "mysql", "mariadb" -> MYSQL;
        case "postgres", "postgresql" -> POSTGRES;
        case "sqlite" -> SQLITE;
        default ->
          throw new ApiException(400, "Unsupported relational storage engine: " + storageType);
      };
    }
  }

  private final HikariDataSource dataSource;
  private final Engine engine;
  private final ThreadLocal<Connection> transactionConnection = new ThreadLocal<>();
  private final ThreadLocal<List<Runnable>> rollbackActions = new ThreadLocal<>();

  private JooqDatabase(Engine engine, HikariDataSource dataSource) {
    this.engine = engine;
    this.dataSource = dataSource;
  }

  public static JooqDatabase open(Engine engine, Path dataDir) {
    return open(engine, dataDir, ExternalDatabaseSupport.ConnectionDefaults.empty());
  }

  public static JooqDatabase open(
      Engine engine, Path dataDir, ExternalDatabaseSupport.ConnectionDefaults connectionDefaults) {
    HikariConfig config = new HikariConfig();
    config.setPoolName("PocketBaseJooq-" + engine.name().toLowerCase(Locale.ROOT));

    switch (engine) {
      case SQLITE -> {
        config.setMaximumPoolSize(4);
        Path sqliteFile = dataDir.resolve("pocketbase.db").toAbsolutePath();
        try {
          FilePermissionSupport.secureDirectory(dataDir);
          FilePermissionSupport.createPrivateFile(sqliteFile);
        } catch (java.io.IOException e) {
          throw new ApiException(500, "Failed to prepare SQLite storage.");
        }
        config.setJdbcUrl("jdbc:sqlite:" + sqliteFile);
        // SQLite tuning (per-connection via HikariCP init SQL):
        // • WAL journal mode — allows concurrent readers during a write.
        // • busy_timeout 5000ms — wait instead of erroring on lock contention.
        // • synchronous NORMAL — WAL + NORMAL is crash-safe and much faster than FULL.
        // • foreign_keys ON — enforce referential integrity.
        // • temp_store MEMORY — avoid temp tables hitting disk.
        config.setConnectionInitSql(
            "PRAGMA journal_mode = WAL;"
                + "PRAGMA busy_timeout = 5000;"
                + "PRAGMA synchronous = NORMAL;"
                + "PRAGMA foreign_keys = ON;"
                + "PRAGMA temp_store = MEMORY;"
                + "PRAGMA cache_size = -65536;");
      }
      case MYSQL -> {
        config.setMaximumPoolSize(10);
        config.setConnectionTimeout(10_000);
        config.setIdleTimeout(600_000);
        config.setMaxLifetime(1_800_000);
        config.setLeakDetectionThreshold(60_000);
        configureExternal(
            config, "mysql", "com.mysql.cj.jdbc.Driver", connectionDefaults);
        // MySQL connection tuning:
        // • utf8mb4 — full Unicode including 4-byte (emoji, CJK extensions).
        // • innodb_lock_wait_timeout 5s — fail fast on row lock contention.
        // • wait_timeout 600s — idle connections reaped after 10 min.
        // • SELECT 1 is cheaper than the default validation query.
        config.setConnectionTestQuery("SELECT 1");
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("useLocalSessionState", "true");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");
        config.addDataSourceProperty("connectionInitSqls",
            "SET NAMES utf8mb4;"
                + "SET SESSION innodb_lock_wait_timeout = 5;"
                + "SET SESSION wait_timeout = 600;"
                + "SET SESSION time_zone = '+00:00'");
      }
      case POSTGRES -> {
        config.setMaximumPoolSize(10);
        config.setConnectionTimeout(10_000);
        config.setIdleTimeout(600_000);
        config.setMaxLifetime(1_800_000);
        config.setLeakDetectionThreshold(60_000);
        configureExternal(
            config, "postgres", "org.postgresql.Driver", connectionDefaults);
        // PostgreSQL connection tuning:
        // • lock_timeout 5s — fail fast on lock contention instead of hanging.
        // • idle_in_transaction_session_timeout 60s — kill leaked transactions.
        // • statement_timeout 30s — prevent runaway queries from holding connections.
        // • standard_conforming_strings ON — safe SQL string escaping.
        config.setConnectionTestQuery("SELECT 1");
        config.addDataSourceProperty("reWriteBatchedInserts", "true");
        config.addDataSourceProperty("preparedStatementCacheQueries", "256");
        config.addDataSourceProperty("defaultRowFetchSize", "500");
        config.addDataSourceProperty("connectionInitSqls",
            "SET lock_timeout = '5s';"
                + "SET idle_in_transaction_session_timeout = '60s';"
                + "SET statement_timeout = '30s';"
                + "SET standard_conforming_strings = on;"
                + "SET TimeZone = 'UTC'");
      }
    }

    JooqDatabase database = new JooqDatabase(engine, new HikariDataSource(config));
    if (engine == Engine.SQLITE) {
      try {
        FilePermissionSupport.secureSqliteFiles(dataDir);
      } catch (java.io.IOException e) {
        database.close();
        throw new ApiException(500, "Failed to secure SQLite storage.");
      }
    }
    if (engine != Engine.SQLITE) {
      database.validateExternalConnection();
    }
    return database;
  }

  private static void configureExternal(
      HikariConfig config,
      String prefix,
      String driverClassName,
      ExternalDatabaseSupport.ConnectionDefaults connectionDefaults) {
    ExternalDatabaseSupport.ResolvedConfig resolved =
        ExternalDatabaseSupport.resolve(
            prefix, System::getProperty, System::getenv, connectionDefaults);
    if (resolved == null) {
      throw new ApiException(400, ExternalDatabaseSupport.missingUrlMessage(prefix));
    }
    resolved.applySystemProperties();
    config.setJdbcUrl(resolved.url());
    config.setDriverClassName(driverClassName);

    if (resolved.user() != null && !resolved.user().isBlank()) {
      config.setUsername(resolved.user());
    }
    if (resolved.password() != null && !resolved.password().isBlank()) {
      config.setPassword(resolved.password());
    }
  }

  public Engine engine() {
    return engine;
  }

  private void validateExternalConnection() {
    try (Connection connection = dataSource.getConnection()) {
      DSLContext context = dsl(connection);
      switch (engine) {
        case MYSQL -> validateMysqlConnection(context);
        case POSTGRES -> validatePostgresConnection(context);
        case SQLITE -> {
        }
      }
    } catch (SQLException | RuntimeException e) {
      SecuritySupport.logInternalFailure(
          "initialize " + engine.name().toLowerCase(Locale.ROOT) + " storage", e);
      throw new ApiException(
          400,
          "Failed to initialize "
              + engine.name().toLowerCase(Locale.ROOT)
              + " storage. Verify the JDBC URL, database/schema, credentials, and server settings.");
    }
  }

  private void validateMysqlConnection(DSLContext context) {
    org.jooq.Record record =
        context.fetchOne(
            "select database() as db, @@session.time_zone as tz, @@character_set_connection as charset, @@collation_connection as collation");
    if (record == null || blank(record.get("db", String.class))) {
      throw new ApiException(
          400, "Failed to initialize mysql storage. The JDBC URL must select a database.");
    }
    if (blank(record.get("tz", String.class))) {
      throw new ApiException(
          400, "Failed to initialize mysql storage. Session time zone is not available.");
    }
    if (blank(record.get("charset", String.class))
        || blank(record.get("collation", String.class))) {
      throw new ApiException(
          400, "Failed to initialize mysql storage. Session charset/collation is not available.");
    }
  }

  private void validatePostgresConnection(DSLContext context) {
    org.jooq.Record record =
        context.fetchOne(
            "select current_database() as db, current_schema() as schema, current_setting('TimeZone') as tz, current_setting('client_encoding') as encoding");
    if (record == null || blank(record.get("db", String.class))) {
      throw new ApiException(
          400, "Failed to initialize postgres storage. The JDBC URL must select a database.");
    }
    if (blank(record.get("schema", String.class))) {
      throw new ApiException(
          400, "Failed to initialize postgres storage. current_schema() returned blank.");
    }
    if (blank(record.get("tz", String.class)) || blank(record.get("encoding", String.class))) {
      throw new ApiException(
          400,
          "Failed to initialize postgres storage. Session time zone or client encoding is not available.");
    }
  }

  public SQLDialect dialect() {
    return engine.dialect();
  }

  public String quoteIdentifier(String identifier) {
    return dsl().render(DSL.name(identifier));
  }

  public FilterToSqlCompiler.CompiledFilter compileFilter(String filter) {
    return FilterToSqlCompiler.compileBound(
        filter, this::quoteIdentifier, this::renderContainsCondition, engine);
  }

  public DSLContext dsl() {
    Connection current = transactionConnection.get();
    if (current != null) {
      return DSL.using(current, dialect());
    }
    return DSL.using(dataSource, dialect());
  }

  public DSLContext dsl(Connection conn) {
    return DSL.using(conn, dialect());
  }

  FilterToSqlCompiler.CompiledFilter renderContainsCondition(
      FilterToSqlCompiler.CompiledFilter left,
      FilterToSqlCompiler.CompiledFilter right,
      boolean negated) {
    DSLContext context = dsl();
    Field<String> leftField = DSL.field(left.sql(), String.class, left.bindings().toArray());
    Field<String> rightField = DSL.field(right.sql(), String.class, right.bindings().toArray());
    Condition condition = leftField.contains(rightField);
    if (negated) {
      condition = condition.not();
    }
    return new FilterToSqlCompiler.CompiledFilter(
        context.render(condition), context.extractBindValues(condition));
  }

  public Connection connection() throws SQLException {
    Connection current = transactionConnection.get();
    return current == null ? dataSource.getConnection() : closeShield(current);
  }

  public void closeIfStandalone(Connection conn) throws SQLException {
    if (transactionConnection.get() != conn) {
      conn.close();
    }
  }

  public void onRollback(Runnable action) {
    List<Runnable> actions = rollbackActions.get();
    if (actions != null && action != null) {
      actions.add(action);
    }
  }

  private static Connection closeShield(Connection target) {
    return (Connection) Proxy.newProxyInstance(
        Connection.class.getClassLoader(),
        new Class<?>[] {Connection.class},
        (proxy, method, args) -> {
          if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
            return null;
          }
          try {
            return method.invoke(target, args);
          } catch (InvocationTargetException e) {
            throw e.getCause();
          }
        });
  }

  public <T> T transactional(Supplier<T> action) {
    if (transactionConnection.get() != null) {
      return action.get();
    }

    try (Connection conn = dataSource.getConnection()) {
      boolean previousAutoCommit = conn.getAutoCommit();
      conn.setAutoCommit(false);
      transactionConnection.set(conn);
      rollbackActions.set(new ArrayList<>());
      try {
        T result = action.get();
        conn.commit();
        return result;
      } catch (RuntimeException e) {
        try {
          conn.rollback();
        } catch (SQLException rollbackError) {
          e.addSuppressed(rollbackError);
        }
        runRollbackActions(e);
        throw e;
      } finally {
        transactionConnection.remove();
        rollbackActions.remove();
        conn.setAutoCommit(previousAutoCommit);
      }
    } catch (SQLException e) {
      throw new RuntimeException("jOOQ transaction failed.", e);
    }
  }

  private void runRollbackActions(RuntimeException cause) {
    List<Runnable> actions = rollbackActions.get();
    if (actions == null) {
      return;
    }
    for (int i = actions.size() - 1; i >= 0; i--) {
      try {
        actions.get(i).run();
      } catch (RuntimeException cleanupError) {
        cause.addSuppressed(cleanupError);
      }
    }
  }

  @Override
  public void close() {
    dataSource.close();
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
