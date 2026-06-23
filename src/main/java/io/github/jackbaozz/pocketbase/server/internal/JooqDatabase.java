package io.github.jackbaozz.pocketbase.server.internal;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.function.Supplier;

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
            String normalized = storageType == null || storageType.isBlank()
                    ? "sqlite"
                    : storageType.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "mysql", "mariadb" -> MYSQL;
                case "postgres", "postgresql" -> POSTGRES;
                case "sqlite" -> SQLITE;
                default -> throw new ApiException(400, "Unsupported relational storage engine: " + storageType);
            };
        }
    }

    private final HikariDataSource dataSource;
    private final Engine engine;
    private final ThreadLocal<Connection> transactionConnection = new ThreadLocal<>();

    private JooqDatabase(Engine engine, HikariDataSource dataSource) {
        this.engine = engine;
        this.dataSource = dataSource;
    }

    static JooqDatabase open(Engine engine, Path dataDir) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("PocketBaseJooq-" + engine.name().toLowerCase(Locale.ROOT));
        config.setMaximumPoolSize(engine == Engine.SQLITE ? 1 : 10);

        switch (engine) {
            case SQLITE -> config.setJdbcUrl("jdbc:sqlite:" + dataDir.resolve("pocketbase.db").toAbsolutePath());
            case MYSQL -> configureExternal(config, "mysql", "com.mysql.cj.jdbc.Driver");
            case POSTGRES -> configureExternal(config, "postgres", "org.postgresql.Driver");
        }

        return new JooqDatabase(engine, new HikariDataSource(config));
    }

    private static void configureExternal(HikariConfig config, String prefix, String driverClassName) {
        String url = firstNonBlank(
                System.getProperty("db.url"),
                System.getProperty(prefix + ".url"),
                System.getenv("PB_DATABASE_URL")
        );
        if (url == null) {
            throw new ApiException(400, "Missing JDBC URL for " + prefix + " storage. Set -Ddb.url or PB_DATABASE_URL.");
        }

        config.setJdbcUrl(url);
        config.setDriverClassName(driverClassName);

        String user = firstNonBlank(
                System.getProperty("db.user"),
                System.getProperty(prefix + ".user"),
                System.getenv("PB_DATABASE_USER")
        );
        String password = firstNonBlank(
                System.getProperty("db.password"),
                System.getProperty(prefix + ".password"),
                System.getenv("PB_DATABASE_PASSWORD")
        );
        if (user != null) {
            config.setUsername(user);
        }
        if (password != null) {
            config.setPassword(password);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    Engine engine() {
        return engine;
    }

    SQLDialect dialect() {
        return engine.dialect();
    }

    String quoteIdentifier(String identifier) {
        return dsl().render(DSL.name(identifier));
    }

    DSLContext dsl() {
        Connection current = transactionConnection.get();
        if (current != null) {
            return DSL.using(current, dialect());
        }
        return DSL.using(dataSource, dialect());
    }

    DSLContext dsl(Connection conn) {
        return DSL.using(conn, dialect());
    }

    FilterToSqlCompiler.CompiledFilter renderContainsCondition(
            FilterToSqlCompiler.CompiledFilter left,
            FilterToSqlCompiler.CompiledFilter right,
            boolean negated
    ) {
        DSLContext context = dsl();
        Field<String> leftField = DSL.field(left.sql(), String.class, left.bindings().toArray());
        Field<String> rightField = DSL.field(right.sql(), String.class, right.bindings().toArray());
        Condition condition = leftField.contains(rightField);
        if (negated) {
            condition = condition.not();
        }
        return new FilterToSqlCompiler.CompiledFilter(
                context.render(condition),
                context.extractBindValues(condition)
        );
    }

    Connection connection() throws SQLException {
        Connection current = transactionConnection.get();
        return current == null ? dataSource.getConnection() : closeShield(current);
    }

    void closeIfStandalone(Connection conn) throws SQLException {
        if (transactionConnection.get() != conn) {
            conn.close();
        }
    }

    private static Connection closeShield(Connection target) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                        return null;
                    }
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
        );
    }

    <T> T transactional(Supplier<T> action) {
        if (transactionConnection.get() != null) {
            return action.get();
        }

        try (Connection conn = dataSource.getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            transactionConnection.set(conn);
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
                throw e;
            } finally {
                transactionConnection.remove();
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException("jOOQ transaction failed.", e);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
