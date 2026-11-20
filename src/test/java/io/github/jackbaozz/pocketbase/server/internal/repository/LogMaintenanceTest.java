package io.github.jackbaozz.pocketbase.server.internal.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogMaintenanceTest {
  @TempDir
  Path dataDir;

  @Test
  void countsWithoutPaginationAndReclaimsSpaceAfterCumulativeThreshold() {
    try (var database = JooqDatabase.open(JooqDatabase.Engine.SQLITE, dataDir)) {
      database.dsl().execute("CREATE TABLE _logs (id TEXT PRIMARY KEY, created TEXT, updated TEXT, "
          + "level INTEGER, message TEXT, data TEXT)");
      // A non-log table must survive because Java shares one database file.
      database.dsl().execute("CREATE TABLE keep_me (value TEXT)");
      database.dsl().execute("INSERT INTO keep_me VALUES ('retained')");
      var mapper = new ObjectMapper();
      var repository = new LogRepository(database, mapper,
          new SettingsRepository(database, mapper, dataDir));

      insertOldLogs(database, 49_999);
      assertEquals(49_999L, repository.countLogs(Map.of()));
      assertEquals(49_999L, repository.countLogs(Map.of("perPage", "1", "page", "999")));
      assertEquals(49_999L, repository.countLogs(Map.of("filter", "level = 0")));
      assertEquals(0L, repository.countLogs(Map.of("filter", "level = 8")));
      repository.deleteOldLogs();
      assertEquals(0, repository.countLogs(null));
      assertTrue(freePages(database) > 0, "below 50k deletions must not vacuum");

      insertOldLogs(database, 1);
      repository.deleteOldLogs();
      assertEquals(0, freePages(database), "exactly 50k cumulative deletions must vacuum");
      assertEquals("retained", database.dsl().fetchOne("SELECT value FROM keep_me").get(0));

      insertOldLogs(database, 100);
      repository.deleteOldLogs();
      assertTrue(freePages(database) > 0, "successful vacuum resets the counter");
    }
  }

  @Test
  void defersVacuumUntilOutsideATransactionWithoutLosingTheCounter() {
    try (var database = JooqDatabase.open(JooqDatabase.Engine.SQLITE, dataDir)) {
      database.dsl().execute("CREATE TABLE _logs (id TEXT PRIMARY KEY, created TEXT, updated TEXT, "
          + "level INTEGER, message TEXT, data TEXT)");
      var mapper = new ObjectMapper();
      var repository = new LogRepository(database, mapper,
          new SettingsRepository(database, mapper, dataDir));
      insertOldLogs(database, 50_000);
      database.transactional(() -> {
        repository.deleteOldLogs();
        return null;
      });
      assertTrue(freePages(database) > 0);
      repository.deleteOldLogs();
      assertEquals(0, freePages(database));
    }
  }

  private static void insertOldLogs(JooqDatabase database, int count) {
    database.dsl().execute("WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x < ?) "
        + "INSERT INTO _logs SELECT CAST(x AS TEXT), '2000-01-01T00:00:00Z', "
        + "'2000-01-01T00:00:00Z', 0, 'old log', hex(zeroblob(256)) FROM n", count);
  }

  private static int freePages(JooqDatabase database) {
    return database.dsl().fetchOne("PRAGMA freelist_count").get(0, Integer.class);
  }
}
