package io.github.jackbaozz.pocketbase.server.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;

public class SqliteDefensiveModeTest {

  @TempDir
  Path tempDir;

  @Test
  void testSqliteDefensivePragmaOrConfig() throws Exception {
    Path dbFile = tempDir.resolve("test.db");
    SQLiteConfig config = new SQLiteConfig();
    config.setBusyTimeout(10000);
    // Let's test PRAGMA defensive = ON
    try (Connection conn = config.createConnection("jdbc:sqlite:" + dbFile);
        Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
      stmt.execute("INSERT INTO t VALUES (1, 'foo')");

      // Check if pragma defensive works or if config has defensive config
      boolean pragmaDefensiveExecuted = false;
      try {
        stmt.execute("PRAGMA defensive = ON");
        pragmaDefensiveExecuted = true;
      } catch (SQLException e) {
        System.out.println("PRAGMA defensive failed: " + e.getMessage());
      }
      System.out.println("pragmaDefensiveExecuted: " + pragmaDefensiveExecuted);

      // Try modifying sqlite_schema/sqlite_master
      SQLException ex = assertThrows(SQLException.class, () -> {
        stmt.executeUpdate("UPDATE sqlite_master SET name = 'bar' WHERE name = 't'");
      });
      System.out.println("Update sqlite_master threw: " + ex.getMessage());
      assertTrue(ex.getMessage().contains("may not be modified") || ex.getMessage().contains("not authorized") || ex.getMessage().contains("defensive"));
    }
  }
}
