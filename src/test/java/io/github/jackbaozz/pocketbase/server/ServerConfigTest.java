package io.github.jackbaozz.pocketbase.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerConfigTest {

  @TempDir
  Path tempDir;

  @Test
  void loadsApplicationPropertiesAndAllowsCommandLineOverrides() throws Exception {
    Path configFile = tempDir.resolve("application.properties");
    Files.writeString(
        configFile,
        """
            app.name=Configured App
            server.host=0.0.0.0
            server.port=9010
            server.data-dir=config-data
            storage.type=sqlite
            database.url=jdbc:sqlite:configured.db
            database.user=config-user
            database.password=config-password
            """);

    String previousStorage = System.getProperty("storage");
    System.clearProperty("storage");
    ServerConfig config;
    try {
      config =
          ServerConfig.fromArgs(
              new String[] {"start", "--config", configFile.toString(), "--port", "9020"},
              Map.of());
    } finally {
      if (previousStorage == null) {
        System.clearProperty("storage");
      } else {
        System.setProperty("storage", previousStorage);
      }
    }

    assertEquals("Configured App", config.applicationName());
    assertEquals("0.0.0.0", config.host());
    assertEquals(9020, config.port());
    assertEquals(Path.of("config-data"), config.dataDir());
    assertEquals("sqlite", config.storageType());
    assertEquals("jdbc:sqlite:configured.db", config.databaseUrl());
    assertEquals("config-user", config.databaseUser());
    assertEquals("config-password", config.databasePassword());
  }

  @Test
  void environmentOverridesPropertiesFile() throws Exception {
    Path configFile = tempDir.resolve("application.properties");
    Files.writeString(configFile, "server.port=9010\napp.name=File App\n");

    ServerConfig config =
        ServerConfig.fromArgs(
            new String[] {"start", "--config=" + configFile},
            Map.of("PB_HTTP_PORT", "9030", "PB_APP_NAME", "Environment App"));

    assertEquals(9030, config.port());
    assertEquals("Environment App", config.applicationName());
  }

  @Test
  void missingExplicitConfigurationFileFailsClearly() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> ServerConfig.fromArgs(new String[] {"start", "--config", "missing.properties"}, Map.of()));

    assertEquals("configuration file not found: missing.properties", error.getMessage());
  }

  @Test
  void rejectsTheRemovedServeCommand() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> ServerConfig.fromArgs(new String[] {"serve"}, Map.of()));

    assertEquals("unknown argument: serve", error.getMessage());
  }

  @Test
  void allowsStartingWithoutACommand() {
    ServerConfig config = ServerConfig.fromArgs(new String[0], Map.of());

    assertEquals(ServerConfig.DEFAULT_PORT, config.port());
    assertEquals("sqlite", config.storageType());
  }

  @Test
  void rejectsUnsupportedStorageEncryptionConfiguration() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> ServerConfig.fromArgs(new String[0], Map.of("PB_ENCRYPTION_KEY", "secret")));

    assertTrue(error.getMessage().contains("Encryption at rest is not supported"));
  }

  @Test
  void loadsTheProfileFileAfterBaseProperties() throws Exception {
    Path configFile = tempDir.resolve("application.properties");
    Path profileFile = tempDir.resolve("application-dev.properties");
    Files.writeString(
        configFile,
        """
            app.profile=dev
            app.name=Base App
            server.port=9010
            server.data-dir=base-data
            """);
    Files.writeString(
        profileFile,
        """
            app.name=Development App
            server.port=9020
            server.data-dir=dev-data
            """);

    ServerConfig config =
        ServerConfig.fromArgs(new String[] {"start", "--config", configFile.toString()}, Map.of());

    assertEquals("dev", config.profile());
    assertEquals("Development App", config.applicationName());
    assertEquals(9020, config.port());
    assertEquals(Path.of("dev-data"), config.dataDir());
  }

  @Test
  void commandLineProfileOverridesTheEnvironmentAndBaseFile() throws Exception {
    Path configFile = tempDir.resolve("application.properties");
    Files.writeString(configFile, "app.profile=dev\nserver.port=9010\n");
    Files.writeString(tempDir.resolve("application-dev.properties"), "server.port=9020\n");
    Files.writeString(tempDir.resolve("application-prod.properties"), "server.port=9030\n");

    ServerConfig config =
        ServerConfig.fromArgs(
            new String[] {"start", "--config", configFile.toString(), "--profile", "prod"},
            Map.of("PB_PROFILE", "dev"));

    assertEquals("prod", config.profile());
    assertEquals(9030, config.port());
  }

  @Test
  void environmentProfileOverridesTheBaseFile() throws Exception {
    Path configFile = tempDir.resolve("application.properties");
    Files.writeString(configFile, "app.profile=dev\nserver.port=9010\n");
    Files.writeString(tempDir.resolve("application-dev.properties"), "server.port=9020\n");
    Files.writeString(tempDir.resolve("application-prod.properties"), "server.port=9030\n");

    ServerConfig config =
        ServerConfig.fromArgs(
            new String[] {"start", "--config", configFile.toString()}, Map.of("PB_PROFILE", "prod"));

    assertEquals("prod", config.profile());
    assertEquals(9030, config.port());
  }

  @Test
  void rejectsUnsafeProfileNames() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> ServerConfig.fromArgs(new String[] {"start", "--profile", "../prod"}, Map.of()));

    assertEquals("invalid profile: ../prod", error.getMessage());
  }

  @Test
  void programmaticSqliteConfigurationCreatesDatabaseAndAppliesApplicationName() throws Exception {
    String previousStorage = System.getProperty("storage");
    System.clearProperty("storage");
    try {
      Path dataDir = tempDir.resolve("sqlite-data");
      ServerConfig config =
          new ServerConfig(
              "127.0.0.1",
              0,
              dataDir,
              null,
              null,
              null,
              "Configured UI",
              "sqlite",
              null,
              null,
              null);

      try (LocalPocketBase server = LocalPocketBase.start(config)) {
        assertTrue(Files.isRegularFile(dataDir.resolve("pocketbase.db")));
        Map<?, ?> settings = server.store().getSettings(Map.of());
        Map<?, ?> meta = (Map<?, ?>) settings.get("meta");
        assertEquals("Configured UI", meta.get("appName"));
      }
    } finally {
      if (previousStorage == null) {
        System.clearProperty("storage");
      } else {
        System.setProperty("storage", previousStorage);
      }
    }
  }
}
