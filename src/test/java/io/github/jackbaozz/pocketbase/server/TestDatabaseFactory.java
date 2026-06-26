package io.github.jackbaozz.pocketbase.server;

import org.junit.jupiter.api.Assumptions;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

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
            // If Docker is not available or container fails to start, we skip the tests.
            System.err.println("Failed to start Testcontainers for " + storage + ": " + e.getMessage());
            Assumptions.assumeTrue(false, "Skipping tests because Testcontainers could not start for storage: " + storage);
        }
    }
}
