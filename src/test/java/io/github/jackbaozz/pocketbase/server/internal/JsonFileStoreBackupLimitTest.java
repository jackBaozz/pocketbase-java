package io.github.jackbaozz.pocketbase.server.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonFileStoreBackupLimitTest {

  @TempDir
  Path tempDir;

  @Test
  void rejectsArchivesWithTooManyEntriesBeforeRestoring() throws Exception {
    Path dataDir = tempDir.resolve("data");
    Path archive = tempDir.resolve("too-many-entries.zip");
    try (OutputStream output = Files.newOutputStream(archive);
        ZipOutputStream zip = new ZipOutputStream(output)) {
      for (int index = 0; index < 10_001; index++) {
        zip.putNextEntry(new ZipEntry("records/entry-" + index + ".jsonl"));
        zip.closeEntry();
      }
    }

    JsonFileStore store = JsonFileStore.open(dataDir, null, null);
    try {
      store.uploadBackup("too-many-entries.zip", Files.readAllBytes(archive));
      ApiException error = assertThrows(ApiException.class, () -> store.restoreBackup("too-many-entries.zip"));
      assertEquals(400, error.status());
      assertEquals("Invalid backup archive.", error.getMessage());
    } finally {
      store.close();
    }
  }
}
