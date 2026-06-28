package io.github.jackbaozz.pocketbase.server;

import org.junit.jupiter.api.Assumptions;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import io.github.jackbaozz.pocketbase.server.internal.ExternalDatabaseSupport;

import java.util.Locale;

public class TestDatabaseFactory {

    private static MySQLContainer<?> mysql;
    private static PostgreSQLContainer<?> postgres;
    private static boolean initialized = false;

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
                    mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                            .withDatabaseName("pocketbase")
                            .withUsername("pb")
                            .withPassword("secret");
                    mysql.start();
                    System.setProperty("mysql.url", mysql.getJdbcUrl());
                    System.setProperty("db.user", mysql.getUsername());
                    System.setProperty("db.password", mysql.getPassword());
                }
                case "postgres", "postgresql" -> {
                    postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15"))
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
        } catch (Exception e) {
            String message = "Skipping " + storage + " tests because no external DSN is configured and Testcontainers could not start: " + e.getMessage();
            System.err.println(message);
            Assumptions.assumeTrue(false, message);
        }
    }
}
