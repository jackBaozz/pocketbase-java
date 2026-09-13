package io.github.jackbaozz.pocketbase.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LifecycleCompatibilityTest {
  @TempDir
  Path dataDir;

  @Test
  void closeIsIdempotentAndCanReleaseStorageForRestart() throws Exception {
    LocalPocketBase first =
        TestDatabaseFactory.start(
            new ServerConfig("127.0.0.1", 0, dataDir, null, null, null));
    assertDoesNotThrow(first::close);
    assertDoesNotThrow(first::close);

    LocalPocketBase second =
        TestDatabaseFactory.start(
            new ServerConfig("127.0.0.1", 0, dataDir, null, null, null));
    assertDoesNotThrow(second::close);
  }

  @Test
  void failedPortBindingReleasesAlreadyOpenedStorage() throws Exception {
    LocalPocketBase occupiedServer =
        TestDatabaseFactory.start(
            new ServerConfig("127.0.0.1", 0, dataDir, null, null, null));
    int port = occupiedServer.port();
    ServerConfig config = new ServerConfig("127.0.0.1", port, dataDir, null, null, null);
    assertThrows(IOException.class, () -> TestDatabaseFactory.start(config));
    occupiedServer.close();

    LocalPocketBase recovered =
        TestDatabaseFactory.start(
            new ServerConfig("127.0.0.1", 0, dataDir, null, null, null));
    recovered.close();
  }
}
