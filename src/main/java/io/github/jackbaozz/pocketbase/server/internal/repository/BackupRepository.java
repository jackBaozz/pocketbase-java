package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.internal.ApiErrors;
import io.github.jackbaozz.pocketbase.server.internal.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.BackupOperationGuard;
import io.github.jackbaozz.pocketbase.server.internal.FilePermissionSupport;
import io.github.jackbaozz.pocketbase.server.internal.IdGenerator;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;
import io.github.jackbaozz.pocketbase.server.internal.SecuritySupport;
import io.github.jackbaozz.pocketbase.server.internal.Unsafe;
import io.github.jackbaozz.pocketbase.server.spi.FileStorageProvider;
import io.github.jackbaozz.pocketbase.server.spi.StorageProviderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class BackupRepository extends BaseRepository {

  private static final String SNAPSHOT_ENTRY = "relational-backup.json";
  private static final int MAX_BACKUP_ENTRIES = 10_000;
  private static final long MAX_BACKUP_UNCOMPRESSED_BYTES = 512L << 20;
  private static final long MAX_SNAPSHOT_BYTES = 64L << 20;

  private final Path dataDir;
  private final BackupOperationGuard backupOperations = new BackupOperationGuard();

  public BackupRepository(JooqDatabase database, ObjectMapper mapper, Path dataDir) {
    super(database, mapper);
    this.dataDir = dataDir;
  }

  public List<Map<String, Object>> listBackups() {
    Optional<FileStorageProvider> s3 = backupS3Provider();
    if (s3.isPresent()) {
      return listS3Backups(s3.get());
    }
    try {
      Path backupsDir = dataDir.resolve("backups");
      FilePermissionSupport.secureDirectory(dataDir);
      FilePermissionSupport.secureDirectory(backupsDir);
      List<Map<String, Object>> items = new ArrayList<>();
      try (var stream = Files.list(backupsDir)) {
        stream
            .filter(p -> !Files.isSymbolicLink(p) && p.toString().endsWith(".zip"))
            .forEach(
                p -> {
                  try {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("key", p.getFileName().toString());
                    item.put("size", Files.size(p));
                    item.put("modified", Files.getLastModifiedTime(p).toInstant().toString());
                    items.add(item);
                  } catch (IOException ignored) {
                  }
                });
      }
      items.sort(
          (a, b) -> String.valueOf(b.get("modified")).compareTo(String.valueOf(a.get("modified"))));
      return items;
    } catch (IOException e) {
      return List.of();
    }
  }

  public boolean canBackup() {
    return backupOperations.available();
  }

  public void deleteBackup(String key) {
    if (backupOperations.active(key)) {
      throw new ApiException(400, "The backup is currently being used and cannot be deleted.");
    }
    Optional<FileStorageProvider> s3 = backupS3Provider();
    if (s3.isPresent()) {
      validateBackupKey(key);
      if (s3.get().stat(key).isEmpty()) {
        throw new ApiException(400, "Invalid or already deleted backup file.");
      }
      s3.get().delete(key);
      deleteCachedS3Backup(key);
      return;
    }
    try {
      Path backup = backupFile(key);
      if (backup == null) {
        throw new ApiException(400, "Invalid or already deleted backup file.");
      }
      Files.delete(backup);
    } catch (ApiException e) {
      throw e;
    } catch (IOException e) {
      throw new ApiException(400, "Failed to delete backup.");
    }
  }

  public Map<String, Object> restoreBackup(String key) {
    if (backupFile(key) == null) {
      throw new ApiException(400, "Missing or invalid backup file.");
    }
    return backupOperations.run(
        key,
        () -> {
          Path backup = backupFileRequired(key);
          Map<String, Object> snapshot = readSnapshot(backup);
          validateStorageEntries(backup);
          database.transactional(
              () -> {
                restoreDatabase(snapshot);
                return null;
              });
          restoreStorageFiles(backup);
          return Map.of("restored", key);
        });
  }

  public Map<String, Object> createBackup(JsonNode body) {
    String name = createBackupName(body);
    return backupOperations.run(name, () -> createBackup(name));
  }

  private Map<String, Object> createBackup(String name) {
    try {
      Optional<FileStorageProvider> s3 = backupS3Provider();
      if (s3.isPresent()) {
        if (s3.get().stat(name).isPresent()) {
          throw new ApiException(400, "Backup already exists.", ApiErrors.notUniqueField("name"));
        }
        FilePermissionSupport.secureDirectory(dataDir);
        Path tempFile = Files.createTempFile(dataDir, ".create-backup-", ".zip");
        try {
          FilePermissionSupport.secureFile(tempFile);
          try (OutputStream output = Files.newOutputStream(tempFile)) {
            writeBackupZip(output);
          }
          FilePermissionSupport.secureFile(tempFile);
          long size = Files.size(tempFile);
          try (InputStream input = Files.newInputStream(tempFile)) {
            s3.get().put(name, input, size, "application/zip");
          }
          return s3BackupItem(s3.get(), name)
              .orElseGet(() -> backupItem(name, size, Instant.now().toEpochMilli()));
        } finally {
          Files.deleteIfExists(tempFile);
        }
      }

      Path backupsDir = dataDir.resolve("backups");
      FilePermissionSupport.secureDirectory(dataDir);
      FilePermissionSupport.secureDirectory(backupsDir);
      Path backupFile = backupsDir.resolve(name);
      if (Files.exists(backupFile)) {
        throw new ApiException(400, "Backup already exists.", ApiErrors.notUniqueField("name"));
      }

      Path temporary = Files.createTempFile(backupsDir, ".create-backup-", ".tmp");
      try {
        writeBackupZip(temporary);
        publishBackup(temporary, backupFile);
        FilePermissionSupport.secureFile(backupFile);
        temporary = null;
      } finally {
        deleteTemporaryBackup(temporary);
      }

      return backupItem(
          name, Files.size(backupFile), Files.getLastModifiedTime(backupFile).toMillis());
    } catch (ApiException e) {
      throw e;
    } catch (IOException e) {
      throw internalFailure(400, "Failed to create backup.", "create backup", e);
    }
  }

  public Map<String, Object> uploadBackup(String filename, byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      throw new ApiException(400, "Backup file is required.", ApiErrors.requiredField("file"));
    }
    Path temporary = null;
    try {
      Optional<FileStorageProvider> s3 = backupS3Provider();
      if (s3.isPresent()) {
        String name = sanitizedBackupName(filename);
        if (s3.get().stat(name).isPresent()) {
          throw new ApiException(400, "Backup already exists.", ApiErrors.notUniqueField("file"));
        }
        s3.get().put(name, new ByteArrayInputStream(bytes), bytes.length, "application/zip");
        return s3BackupItem(s3.get(), name)
            .orElseGet(() -> backupItem(name, bytes.length, Instant.now().toEpochMilli()));
      }

      Path backupsDir = dataDir.resolve("backups");
      FilePermissionSupport.secureDirectory(dataDir);
      FilePermissionSupport.secureDirectory(backupsDir);

      String name = sanitizedBackupName(filename);

      Path targetBackupFile = backupsDir.resolve(name);
      if (Files.exists(targetBackupFile)) {
        throw new ApiException(400, "Backup already exists.", ApiErrors.notUniqueField("file"));
      }
      temporary = Files.createTempFile(backupsDir, ".upload-backup-", ".tmp");
      FilePermissionSupport.secureFile(temporary);
      Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
      publishBackup(temporary, targetBackupFile);
      FilePermissionSupport.secureFile(targetBackupFile);
      temporary = null;
      return backupItem(name, bytes.length, Files.getLastModifiedTime(targetBackupFile).toMillis());
    } catch (ApiException e) {
      throw e;
    } catch (IOException e) {
      throw internalFailure(400, "Failed to upload backup.", "upload backup", e);
    } finally {
      deleteTemporaryBackup(temporary);
    }
  }

  private void deleteTemporaryBackup(Path temporary) {
    if (temporary == null) {
      return;
    }
    try {
      Files.deleteIfExists(temporary);
    } catch (IOException ignored) {
      // best effort cleanup for rejected uploads
    }
  }

  private void publishBackup(Path temporary, Path target) throws IOException {
    try {
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temporary, target);
    }
  }

  public Path backupFile(String key) {
    if (key == null || key.isBlank()) {
      return null;
    }
    validateBackupKey(key);
    Optional<FileStorageProvider> s3 = backupS3Provider();
    if (s3.isPresent()) {
      return cachedS3BackupFile(s3.get(), key);
    }
    Path backup = dataDir.resolve("backups").resolve(key);
    return !Files.isSymbolicLink(backup) && Files.exists(backup) && Files.isRegularFile(backup)
        ? backup
        : null;
  }

  private Path backupFileRequired(String key) {
    Path backup = backupFile(key);
    if (backup == null) {
      throw new ApiException(404, "Backup not found.");
    }
    return backup;
  }

  private void validateBackupKey(String key) {
    if (key == null || key.isBlank()) {
      throw new ApiException(400, "Backup key is required.", ApiErrors.requiredField("key"));
    }
    if (key.contains("..") || key.contains("/") || key.contains("\\")) {
      throw new ApiException(
          400, "Invalid backup key.", ApiErrors.invalidField("key", "Invalid backup key."));
    }
  }

  private void writeBackupZip(Path backupFile) throws IOException {
    try (OutputStream output =
        Files.newOutputStream(backupFile, StandardOpenOption.TRUNCATE_EXISTING)) {
      writeBackupZip(output);
    }
  }

  private void writeBackupZip(OutputStream output) throws IOException {
    byte[] snapshot = mapper.writeValueAsBytes(createSnapshot());
    Path storage = dataDir.resolve("storage");
    List<Path> filesToZip = new ArrayList<>();
    if (Files.exists(storage)) {
      try (Stream<Path> paths = Files.walk(storage)) {
        filesToZip = paths
            .filter(Files::isRegularFile)
            .filter(p -> !Files.isSymbolicLink(p))
            .collect(Collectors.toList());
      }
    }

    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      zip.putNextEntry(new ZipEntry(SNAPSHOT_ENTRY));
      zip.write(snapshot);
      zip.closeEntry();

      for (Path path : filesToZip) {
        if (!Files.exists(path) || Files.isSymbolicLink(path)) {
          continue;
        }
        String entryName = dataDir.relativize(path).toString().replace('\\', '/');
        zip.putNextEntry(new ZipEntry(entryName));
        try {
          Files.copy(path, zip);
        } catch (NoSuchFileException ignored) {
          zip.closeEntry();
          continue;
        }
        zip.closeEntry();
      }
    }
  }

  private List<Map<String, Object>> listS3Backups(FileStorageProvider provider) {
    List<Map<String, Object>> items =
        provider.list("").stream()
            .filter(name -> name.endsWith(".zip"))
            .filter(name -> !name.contains("/") && !name.contains("\\"))
            .map(name -> s3BackupItem(provider, name).orElseGet(() -> backupItem(name, 0L, 0L)))
            .sorted(
                (left, right) -> String.valueOf(right.get("modified"))
                    .compareTo(String.valueOf(left.get("modified"))))
            .collect(Collectors.toCollection(ArrayList::new));
    return items;
  }

  private Optional<Map<String, Object>> s3BackupItem(FileStorageProvider provider, String name) {
    return provider
        .stat(name)
        .map(stat -> backupItem(name, stat.size(), stat.lastModifiedMillis()));
  }

  private Map<String, Object> backupItem(String name, long size, long modified) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("key", name);
    result.put("size", size);
    result.put("modified", Instant.ofEpochMilli(Math.max(0L, modified)).toString());
    return result;
  }

  private String createBackupName(JsonNode body) {
    if (body == null || !body.hasNonNull("name") || body.get("name").asText().isBlank()) {
      return "pb_backup_" + Instant.now().toString().replaceAll("[^0-9]", "") + ".zip";
    }
    String name = body.get("name").asText().trim();
    if (name.length() > 150 || !name.matches("^(@auto_pb_backup_)?[a-z0-9_-]+\\.zip$")) {
      throw new ApiException(
          400,
          "An error occurred while validating the submitted data.",
          ApiErrors.fieldError("name", "validation_match_invalid", "Must be in a valid format."));
    }
    return name;
  }

  private String sanitizedBackupName(String filename) {
    String name = filename;
    if (name == null || name.isBlank()) {
      name = "upload_" + IdGenerator.id() + ".zip";
    }
    name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
    if (!name.endsWith(".zip")) {
      name = name + ".zip";
    }
    validateBackupKey(name);
    return name;
  }

  private Path cachedS3BackupFile(FileStorageProvider provider, String key) {
    Optional<InputStream> input = provider.get(key);
    if (input.isEmpty()) {
      return null;
    }
    try (InputStream stream = input.get()) {
      FilePermissionSupport.secureDirectory(dataDir);
      FilePermissionSupport.secureDirectory(dataDir.resolve("backups"));
      Path cacheDir = dataDir.resolve("backups").resolve(".s3-cache");
      FilePermissionSupport.secureDirectory(cacheDir);
      Path cached = cacheDir.resolve(key).normalize();
      if (!cached.startsWith(cacheDir.normalize())) {
        throw invalidBackupArchive();
      }
      Files.copy(stream, cached, StandardCopyOption.REPLACE_EXISTING);
      FilePermissionSupport.secureFile(cached);
      return cached;
    } catch (IOException e) {
      throw internalFailure(400, "Failed to download backup.", "download backup", e);
    }
  }

  private void deleteCachedS3Backup(String key) {
    try {
      FilePermissionSupport.secureDirectory(dataDir.resolve("backups").resolve(".s3-cache"));
      Files.deleteIfExists(
          dataDir.resolve("backups").resolve(".s3-cache").resolve(key).normalize());
    } catch (IOException ignored) {
    }
  }

  private Optional<FileStorageProvider> backupS3Provider() {
    try {
      Map<String, Object> settings = loadRawSettings();
      Object backupsValue = settings.get("backups");
      if (!(backupsValue instanceof Map<?, ?> backupsMap)) {
        return Optional.empty();
      }
      Object s3Value = backupsMap.get("s3");
      if (!(s3Value instanceof Map<?, ?> s3Map)) {
        return Optional.empty();
      }
      Map<String, Object> s3 =
          mapper.convertValue(s3Map, new TypeReference<Map<String, Object>>() {
          });
      if (!truthy(s3.get("enabled"))) {
        return Optional.empty();
      }
      return Optional.of(StorageProviderFactory.createS3Provider(s3));
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw internalFailure(
          400, "Failed to initialize S3 backup storage.", "initialize S3 backup storage", e);
    }
  }

  private Map<String, Object> loadRawSettings() {
    try {
      var result =
          database
              .dsl()
              .select(qfs("value"))
              .from(qt("_params"))
              .where(qfs("id").eq("settings"))
              .fetchOne();
      if (result == null) {
        return Map.of();
      }
      String value = result.get(qfs("value"));
      if (value == null || value.isBlank()) {
        return Map.of();
      }
      return mapper.readValue(value, new TypeReference<Map<String, Object>>() {
      });
    } catch (Exception e) {
      return Map.of();
    }
  }

  private boolean truthy(Object value) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    return value != null
        && switch (String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT)) {
          case "", "0", "false", "no" -> false;
          default -> true;
        };
  }

  private Map<String, Object> createSnapshot() {
    Connection conn = null;
    boolean previousAutoCommit = true;
    try {
      conn = database.connection();
      previousAutoCommit = conn.getAutoCommit();
      conn.setAutoCommit(false);

      if (database.engine() == JooqDatabase.Engine.MYSQL) {
        try (Statement s = conn.createStatement()) {
          s.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY");
        }
      } else if (database.engine() == JooqDatabase.Engine.POSTGRES) {
        try (Statement s = conn.createStatement()) {
          s.execute("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
        }
      }

      List<Map<String, Object>> objects = readDatabaseObjects(conn);
      List<Map<String, Object>> tables = new ArrayList<>();
      for (Map<String, Object> object : objects) {
        if (!"table".equals(object.get("type"))) {
          continue;
        }
        String table = String.valueOf(object.get("name"));
        Map<String, Object> tableSnapshot = new LinkedHashMap<>();
        tableSnapshot.put("name", table);
        tableSnapshot.put("rows", readRows(conn, table));
        tables.add(tableSnapshot);
      }
      Map<String, Object> snapshot = new LinkedHashMap<>();
      snapshot.put("format", "pocketbase-java-relational-backup-v1");
      snapshot.put("engine", database.engine().name().toLowerCase(java.util.Locale.ROOT));
      snapshot.put("objects", objects);
      snapshot.put("tables", tables);

      conn.commit();
      return snapshot;
    } catch (SQLException e) {
      if (conn != null) {
        try {
          conn.rollback();
        } catch (SQLException ignored) {
        }
      }
      throw internalFailure(400, "Failed to create backup snapshot.", "create backup snapshot", e);
    } finally {
      if (conn != null) {
        try {
          conn.setAutoCommit(previousAutoCommit);
        } catch (SQLException ignored) {
        }
        try {
          database.closeIfStandalone(conn);
        } catch (SQLException ignored) {
        }
      }
    }
  }

  private List<Map<String, Object>> readSqliteObjects(Connection conn) throws SQLException {
    List<Map<String, Object>> objects = new ArrayList<>();
    try (Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                """
                    SELECT type, name, tbl_name, sql
                    FROM sqlite_master
                    WHERE type IN ('table', 'view', 'index')
                      AND name NOT LIKE 'sqlite_%'
                      AND sql IS NOT NULL
                    ORDER BY CASE type WHEN 'table' THEN 0 WHEN 'view' THEN 1 ELSE 2 END, name
                    """)) {
      while (rs.next()) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("type", rs.getString("type"));
        object.put("name", rs.getString("name"));
        if ("_pb_bootstrap_guard".equals(object.get("name"))) {
          continue;
        }
        object.put("tblName", rs.getString("tbl_name"));
        object.put("sql", rs.getString("sql"));
        objects.add(object);
      }
    }
    return objects;
  }

  /**
   * Backups are a logical database dump. SQLite exposes its complete DDL through sqlite_master,
   * while the external JDBC drivers require metadata-based DDL reconstruction. Keeping the snapshot
   * format identical avoids making the HTTP backup contract depend on the selected storage engine.
   */
  private List<Map<String, Object>> readDatabaseObjects(Connection conn) throws SQLException {
    if (database.engine() == JooqDatabase.Engine.SQLITE) {
      return readSqliteObjects(conn);
    }
    return readJdbcObjects(conn);
  }

  private List<Map<String, Object>> readJdbcObjects(Connection conn) throws SQLException {
    DatabaseMetaData metadata = conn.getMetaData();
    String catalog = conn.getCatalog();
    String schema = database.engine() == JooqDatabase.Engine.POSTGRES ? conn.getSchema() : null;
    List<Map<String, Object>> objects = new ArrayList<>();
    List<String> tables = new ArrayList<>();
    List<String> views = new ArrayList<>();

    try (ResultSet rs =
        metadata.getTables(catalog, schema, "%", new String[] {"TABLE", "BASE TABLE", "VIEW"})) {
      while (rs.next()) {
        String tableCatalog = rs.getString("TABLE_CAT");
        String tableSchema = rs.getString("TABLE_SCHEM");
        if (!sameCatalog(catalog, tableCatalog) || !sameSchema(schema, tableSchema)) {
          continue;
        }
        String name = rs.getString("TABLE_NAME");
        String type = rs.getString("TABLE_TYPE");
        if (name == null || name.isBlank()) {
          continue;
        }
        if ("VIEW".equalsIgnoreCase(type)) {
          views.add(name);
        } else {
          tables.add(name);
        }
      }
    }
    Collections.sort(tables);
    Collections.sort(views);

    for (String table : tables) {
      if ("_pb_bootstrap_guard".equals(table)) {
        continue;
      }
      objects.add(
          databaseObject(
              "table", table, table, createJdbcTableSql(metadata, catalog, schema, table)));
    }
    for (String view : views) {
      String sql = createJdbcViewSql(conn, schema, view);
      if (sql != null && !sql.isBlank()) {
        objects.add(databaseObject("view", view, view, sql));
      }
    }
    for (String table : tables) {
      objects.addAll(readJdbcIndexes(conn, metadata, catalog, schema, table));
    }
    return objects;
  }

  private boolean sameCatalog(String expected, String actual) {
    return expected == null
        || expected.isBlank()
        || actual == null
        || expected.equalsIgnoreCase(actual);
  }

  private boolean sameSchema(String expected, String actual) {
    return expected == null
        || expected.isBlank()
        || actual == null
        || expected.equalsIgnoreCase(actual);
  }

  private Map<String, Object> databaseObject(
      String type, String name, String tableName, String sql) {
    Map<String, Object> object = new LinkedHashMap<>();
    object.put("type", type);
    object.put("name", name);
    object.put("tblName", tableName);
    object.put("sql", sql);
    return object;
  }

  private String createJdbcTableSql(
      DatabaseMetaData metadata, String catalog, String schema, String table) throws SQLException {
    List<String> columns = new ArrayList<>();
    try (ResultSet rs = metadata.getColumns(catalog, schema, table, null)) {
      while (rs.next()) {
        String name = rs.getString("COLUMN_NAME");
        if (name == null || name.isBlank()) {
          continue;
        }
        String definition = database.quoteIdentifier(name) + " " + columnTypeSql(rs);
        if (rs.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls) {
          definition += " NOT NULL";
        }
        columns.add(definition);
      }
    }
    if (columns.isEmpty()) {
      throw new SQLException("No columns found for table " + table);
    }
    List<String> primaryKey = readPrimaryKeyColumns(metadata, catalog, schema, table);
    if (!primaryKey.isEmpty()) {
      columns.add(
          "PRIMARY KEY ("
              + primaryKey.stream().map(database::quoteIdentifier).collect(Collectors.joining(", "))
              + ")");
    }
    return "CREATE TABLE "
        + database.quoteIdentifier(table)
        + " ("
        + String.join(", ", columns)
        + ")";
  }

  private String columnTypeSql(ResultSet column) throws SQLException {
    String typeName = column.getString("TYPE_NAME");
    int jdbcType = column.getInt("DATA_TYPE");
    int size = column.getInt("COLUMN_SIZE");
    int scale = column.getInt("DECIMAL_DIGITS");
    String normalized = typeName == null ? "" : typeName.trim();
    if (!normalized.matches("[A-Za-z0-9_ () ,]+")) {
      normalized = "";
    }
    if (normalized.isBlank()) {
      normalized = fallbackTypeName(jdbcType);
    }
    String upper = normalized.toUpperCase(java.util.Locale.ROOT);
    if ((jdbcType == Types.CHAR
        || jdbcType == Types.VARCHAR
        || jdbcType == Types.NCHAR
        || jdbcType == Types.NVARCHAR)
        && !upper.contains("(")
        && !upper.contains("TEXT")
        && size > 0) {
      return normalized + "(" + size + ")";
    }
    if ((jdbcType == Types.DECIMAL || jdbcType == Types.NUMERIC)
        && !upper.contains("(")
        && size > 0) {
      return normalized + "(" + size + (scale > 0 ? "," + scale : "") + ")";
    }
    return normalized;
  }

  private String fallbackTypeName(int jdbcType) {
    return switch (jdbcType) {
      case Types.BOOLEAN, Types.BIT -> "BOOLEAN";
      case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> "INTEGER";
      case Types.BIGINT -> "BIGINT";
      case Types.REAL, Types.FLOAT, Types.DOUBLE -> "DOUBLE";
      case Types.DECIMAL, Types.NUMERIC -> "DECIMAL";
      case Types.DATE -> "DATE";
      case Types.TIME, Types.TIME_WITH_TIMEZONE -> "TIME";
      case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "TIMESTAMP";
      case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> "BLOB";
      default -> "TEXT";
    };
  }

  private List<String> readPrimaryKeyColumns(
      DatabaseMetaData metadata, String catalog, String schema, String table) throws SQLException {
    TreeMap<Short, String> ordered = new TreeMap<>();
    try (ResultSet rs = metadata.getPrimaryKeys(catalog, schema, table)) {
      while (rs.next()) {
        String column = rs.getString("COLUMN_NAME");
        if (column != null && !column.isBlank()) {
          ordered.put(rs.getShort("KEY_SEQ"), column);
        }
      }
    }
    return new ArrayList<>(ordered.values());
  }

  private List<Map<String, Object>> readJdbcIndexes(
      Connection conn, DatabaseMetaData metadata, String catalog, String schema, String table)
      throws SQLException {
    return switch (database.engine()) {
      case MYSQL -> readMysqlIndexes(conn, table);
      case POSTGRES -> readPostgresIndexes(conn, schema, table);
      case SQLITE -> readGenericJdbcIndexes(metadata, catalog, schema, table);
    };
  }

  /**
   * MySQL's JDBC metadata omits functional key parts and partial-index emulation expressions. Read
   * the server metadata directly so a backup restores the same index semantics, including prefix
   * lengths and sort direction.
   */
  private List<Map<String, Object>> readMysqlIndexes(Connection conn, String table)
      throws SQLException {
    Map<String, MysqlIndex> indexes = new LinkedHashMap<>();
    try (PreparedStatement statement =
        conn.prepareStatement(
            """
                SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME, SUB_PART,
                       COLLATION, EXPRESSION
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND index_name <> 'PRIMARY'
                ORDER BY index_name, seq_in_index
                """)) {
      statement.setString(1, table);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          String name = rs.getString("INDEX_NAME");
          if (name == null || name.isBlank()) {
            continue;
          }
          MysqlIndex index =
              indexes.computeIfAbsent(
                  name, ignored -> new MysqlIndex(!rsBoolean(rs, "NON_UNIQUE")));
          String expression = rs.getString("EXPRESSION");
          String column = rs.getString("COLUMN_NAME");
          String keyPart;
          if (expression != null && !expression.isBlank()) {
            // Functional key parts require their own parentheses in
            // addition to the enclosing index key-part list.
            keyPart = "(" + mysqlExecutableExpression(expression) + ")";
          } else if (column != null && !column.isBlank()) {
            keyPart = database.quoteIdentifier(column);
            int prefix = rs.getInt("SUB_PART");
            if (!rs.wasNull() && prefix > 0) {
              keyPart += "(" + prefix + ")";
            }
          } else {
            continue;
          }
          if ("D".equalsIgnoreCase(rs.getString("COLLATION"))) {
            keyPart += " DESC";
          }
          index.keyParts.put(rs.getInt("SEQ_IN_INDEX"), keyPart);
        }
      }
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map.Entry<String, MysqlIndex> entry : indexes.entrySet()) {
      List<String> keyParts = new ArrayList<>(entry.getValue().keyParts.values());
      if (keyParts.isEmpty()) {
        continue;
      }
      String sql =
          "CREATE "
              + (entry.getValue().unique ? "UNIQUE " : "")
              + "INDEX "
              + database.quoteIdentifier(entry.getKey())
              + " ON "
              + database.quoteIdentifier(table)
              + " ("
              + String.join(", ", keyParts)
              + ")";
      result.add(databaseObject("index", entry.getKey(), table, sql));
    }
    return result;
  }

  private String mysqlExecutableExpression(String expression) {
    // information_schema.statistics serializes string quotes in an
    // expression as \'. That representation is useful as metadata but is
    // not accepted verbatim by CREATE INDEX (for example, _utf8mb4\'\').
    return expression.replace("\\'", "'");
  }

  /**
   * PostgreSQL can return its canonical index DDL, preserving expression, predicate,
   * operator-class, collation, and ordering details that JDBC {@link DatabaseMetaData} does not
   * expose.
   */
  private List<Map<String, Object>> readPostgresIndexes(
      Connection conn, String schema, String table) throws SQLException {
    List<Map<String, Object>> result = new ArrayList<>();
    try (PreparedStatement statement =
        conn.prepareStatement(
            """
                SELECT idx.relname, pg_get_indexdef(idx.oid)
                FROM pg_class tbl
                JOIN pg_namespace ns ON ns.oid = tbl.relnamespace
                JOIN pg_index ind ON ind.indrelid = tbl.oid
                JOIN pg_class idx ON idx.oid = ind.indexrelid
                WHERE ns.nspname = ?
                  AND tbl.relname = ?
                  AND NOT ind.indisprimary
                ORDER BY idx.relname
                """)) {
      statement.setString(1, schema == null || schema.isBlank() ? "public" : schema);
      statement.setString(2, table);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          String name = rs.getString(1);
          String sql = rs.getString(2);
          if (name != null && !name.isBlank() && sql != null && !sql.isBlank()) {
            result.add(databaseObject("index", name, table, sql));
          }
        }
      }
    }
    return result;
  }

  private List<Map<String, Object>> readGenericJdbcIndexes(
      DatabaseMetaData metadata, String catalog, String schema, String table) throws SQLException {
    List<String> primaryKey = readPrimaryKeyColumns(metadata, catalog, schema, table);
    Map<String, JdbcIndex> indexes = new TreeMap<>();
    try (ResultSet rs = metadata.getIndexInfo(catalog, schema, table, false, false)) {
      while (rs.next()) {
        if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
          continue;
        }
        String name = rs.getString("INDEX_NAME");
        String column = rs.getString("COLUMN_NAME");
        if (name == null || name.isBlank() || column == null || column.isBlank()) {
          continue;
        }
        JdbcIndex index =
            indexes.computeIfAbsent(name, ignored -> new JdbcIndex(!rsBoolean(rs, "NON_UNIQUE")));
        index.columns.put(rs.getShort("ORDINAL_POSITION"), column);
      }
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map.Entry<String, JdbcIndex> entry : indexes.entrySet()) {
      List<String> columns = new ArrayList<>(entry.getValue().columns.values());
      if (columns.isEmpty() || columns.equals(primaryKey)) {
        continue;
      }
      String sql =
          "CREATE "
              + (entry.getValue().unique ? "UNIQUE " : "")
              + "INDEX "
              + database.quoteIdentifier(entry.getKey())
              + " ON "
              + database.quoteIdentifier(table)
              + " ("
              + columns.stream().map(database::quoteIdentifier).collect(Collectors.joining(", "))
              + ")";
      result.add(databaseObject("index", entry.getKey(), table, sql));
    }
    return result;
  }

  private boolean rsBoolean(ResultSet rs, String column) {
    try {
      return rs.getBoolean(column);
    } catch (SQLException e) {
      throw new IllegalStateException("failed to read JDBC metadata", e);
    }
  }

  private String createJdbcViewSql(Connection conn, String schema, String view)
      throws SQLException {
    if (database.engine() == JooqDatabase.Engine.MYSQL) {
      try (PreparedStatement statement =
          conn.prepareStatement(
              """
                  SELECT VIEW_DEFINITION
                  FROM information_schema.views
                  WHERE table_schema = DATABASE()
                    AND table_name = ?
                  """)) {
        statement.setString(1, view);
        try (ResultSet rs = statement.executeQuery()) {
          if (!rs.next()) {
            return null;
          }
          String definition = rs.getString(1);
          return definition == null || definition.isBlank()
              ? null
              : "CREATE VIEW " + database.quoteIdentifier(view) + " AS " + definition;
        }
      }
    }
    try (PreparedStatement statement =
        conn.prepareStatement(
            """
                SELECT pg_get_viewdef(c.oid, true)
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relname = ?
                """)) {
      statement.setString(1, schema == null || schema.isBlank() ? "public" : schema);
      statement.setString(2, view);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        return "CREATE VIEW " + database.quoteIdentifier(view) + " AS " + rs.getString(1);
      }
    }
  }

  private static final class JdbcIndex {
    private final boolean unique;
    private final TreeMap<Short, String> columns = new TreeMap<>();

    private JdbcIndex(boolean unique) {
      this.unique = unique;
    }
  }

  private static final class MysqlIndex {
    private final boolean unique;
    private final TreeMap<Integer, String> keyParts = new TreeMap<>();

    private MysqlIndex(boolean unique) {
      this.unique = unique;
    }
  }

  private List<Map<String, Object>> readRows(Connection conn, String table) throws SQLException {
    validateSqlIdentifier(table);
    List<String> columns = readTableColumns(conn, table);
    if (columns.isEmpty()) {
      return List.of();
    }
    String columnSql =
        columns.stream().map(database::quoteIdentifier).collect(Collectors.joining(", "));
    List<Map<String, Object>> rows = new ArrayList<>();
    try (Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery("SELECT " + columnSql + " FROM " + database.quoteIdentifier(table))) {
      while (rs.next()) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String column : columns) {
          row.put(column, rs.getObject(column));
        }
        rows.add(row);
      }
    }
    return rows;
  }

  private List<String> readTableColumns(Connection conn, String table) throws SQLException {
    validateSqlIdentifier(table);
    List<String> columns = new ArrayList<>();
    try (ResultSet rs = conn.getMetaData().getColumns(null, null, table, null)) {
      while (rs.next()) {
        String column = rs.getString("COLUMN_NAME");
        validateSqlIdentifier(column);
        columns.add(column);
      }
    }
    return columns;
  }

  private Map<String, Object> readSnapshot(Path backup) {
    try (InputStream input = Files.newInputStream(backup);
        ZipInputStream zip = new ZipInputStream(input)) {
      ZipBudget budget = new ZipBudget();
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        budget.begin(entry);
        if (SNAPSHOT_ENTRY.equals(entry.getName())) {
          ByteArrayOutputStream bytes = new ByteArrayOutputStream();
          drainEntry(zip, budget, bytes, MAX_SNAPSHOT_BYTES);
          Map<String, Object> snapshot =
              mapper.readValue(new ByteArrayInputStream(bytes.toByteArray()), new TypeReference<Map<String, Object>>() {
              });
          if (!"pocketbase-java-relational-backup-v1".equals(snapshot.get("format"))) {
            throw invalidBackupArchive();
          }
          return snapshot;
        }
        drainEntry(zip, budget, OutputStream.nullOutputStream(), MAX_BACKUP_UNCOMPRESSED_BYTES);
        zip.closeEntry();
      }
    } catch (ApiException e) {
      throw e;
    } catch (IOException e) {
      throw invalidBackupArchive();
    }
    throw invalidBackupArchive();
  }

  private void restoreDatabase(Map<String, Object> snapshot) {
    Connection conn = null;
    try {
      conn = database.connection();
      Object engineValue = snapshot.get("engine");
      String sourceEngine = engineValue instanceof String value ? value.trim() : "";
      if (sourceEngine.isBlank()) {
        // Relational v1 archives created before the engine marker was
        // introduced were SQLite-only. Treat them as such rather than
        // risking a destructive restore into another dialect.
        if (database.engine() != JooqDatabase.Engine.SQLITE) {
          throw new ApiException(
              400, "Backup storage engine does not match the active storage engine.");
        }
      } else if (!sourceEngine.equalsIgnoreCase(database.engine().name())) {
        throw new ApiException(
            400, "Backup storage engine does not match the active storage engine.");
      }
      List<Map<String, Object>> objects =
          Unsafe.stringObjectMapList(snapshot.getOrDefault("objects", List.of()));
      List<Map<String, Object>> tables =
          Unsafe.stringObjectMapList(snapshot.getOrDefault("tables", List.of()));

      try (Statement stmt = conn.createStatement()) {
        disableForeignKeys(stmt);
        try {
          dropExistingObjects(conn, stmt);
          for (Map<String, Object> object : objects) {
            if ("table".equals(object.get("type"))) {
              executeSnapshotSql(stmt, object, "CREATE TABLE");
            }
          }
          for (Map<String, Object> table : tables) {
            insertRows(conn, table);
          }
          for (Map<String, Object> object : objects) {
            if ("view".equals(object.get("type"))) {
              executeSnapshotSql(stmt, object, "CREATE VIEW");
            }
          }
          for (Map<String, Object> object : objects) {
            if ("index".equals(object.get("type"))) {
              executeSnapshotSql(stmt, object, "CREATE INDEX");
            }
          }
        } finally {
          enableForeignKeys(stmt);
        }
      }
    } catch (SQLException | RuntimeException e) {
      if (e instanceof ApiException apiException) {
        throw apiException;
      }
      throw internalFailure(400, "Failed to restore backup.", "restore backup", e);
    } finally {
      if (conn != null) {
        try {
          database.closeIfStandalone(conn);
        } catch (SQLException ignored) {
        }
      }
    }
  }

  private void dropExistingObjects(Connection conn, Statement stmt) throws SQLException {
    List<Map<String, Object>> current = readDatabaseObjects(conn);
    for (Map<String, Object> object : current) {
      if ("view".equals(object.get("type"))) {
        String name = String.valueOf(object.get("name"));
        validateSqlIdentifier(name);
        stmt.execute("DROP VIEW IF EXISTS " + database.quoteIdentifier(name));
      }
    }
    if (database.engine() == JooqDatabase.Engine.SQLITE) {
      for (Map<String, Object> object : current) {
        if ("index".equals(object.get("type"))) {
          String name = String.valueOf(object.get("name"));
          validateSqlIdentifier(name);
          stmt.execute("DROP INDEX IF EXISTS " + database.quoteIdentifier(name));
        }
      }
    }
    for (Map<String, Object> object : current) {
      if ("table".equals(object.get("type"))) {
        String name = String.valueOf(object.get("name"));
        if ("_pb_bootstrap_guard".equals(name)) {
          continue;
        }
        validateSqlIdentifier(name);
        stmt.execute("DROP TABLE IF EXISTS " + database.quoteIdentifier(name));
      }
    }
  }

  private void disableForeignKeys(Statement stmt) throws SQLException {
    switch (database.engine()) {
      case SQLITE -> stmt.execute("PRAGMA foreign_keys = OFF");
      case MYSQL -> stmt.execute("SET FOREIGN_KEY_CHECKS = 0");
      case POSTGRES -> {
        // The schema generated by this runtime has no cross-table foreign
        // keys. PostgreSQL DDL remains transactional and tables are dropped
        // after dependent views, so no session-wide toggle is required.
      }
    }
  }

  private void enableForeignKeys(Statement stmt) throws SQLException {
    switch (database.engine()) {
      case SQLITE -> stmt.execute("PRAGMA foreign_keys = ON");
      case MYSQL -> stmt.execute("SET FOREIGN_KEY_CHECKS = 1");
      case POSTGRES -> {
      }
    }
  }

  private void executeSnapshotSql(Statement stmt, Map<String, Object> object, String expectedPrefix)
      throws SQLException {
    String name = String.valueOf(object.get("name"));
    validateSqlIdentifier(name);
    String sql = String.valueOf(object.get("sql")).trim();
    String normalizedSql = sql.toUpperCase(java.util.Locale.ROOT);
    boolean allowed =
        normalizedSql.startsWith(expectedPrefix)
            || ("CREATE INDEX".equals(expectedPrefix)
                && normalizedSql.startsWith("CREATE UNIQUE INDEX"));
    if (!allowed) {
      throw invalidBackupArchive();
    }
    stmt.execute(sql);
  }

  private void insertRows(Connection conn, Map<String, Object> table) throws SQLException {
    String tableName = String.valueOf(table.get("name"));
    validateSqlIdentifier(tableName);
    List<Map<String, Object>> rows =
        Unsafe.stringObjectMapList(table.getOrDefault("rows", List.of()));
    if (rows.isEmpty()) {
      return;
    }
    LinkedHashSet<String> columnSet = new LinkedHashSet<>();
    for (Map<String, Object> row : rows) {
      columnSet.addAll(row.keySet());
    }
    List<String> columns = new ArrayList<>(columnSet);
    for (String column : columns) {
      validateSqlIdentifier(column);
    }
    String columnSql =
        columns.stream().map(database::quoteIdentifier).collect(Collectors.joining(", "));
    String placeholders = columns.stream().map(ignored -> "?").collect(Collectors.joining(", "));
    String sql =
        "INSERT INTO "
            + database.quoteIdentifier(tableName)
            + " ("
            + columnSql
            + ") VALUES ("
            + placeholders
            + ")";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      for (Map<String, Object> row : rows) {
        for (int i = 0; i < columns.size(); i++) {
          ps.setObject(i + 1, row.get(columns.get(i)));
        }
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private void zipStorageFiles(ZipOutputStream zip) throws IOException {
    Path storage = dataDir.resolve("storage");
    if (!Files.exists(storage)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(storage)) {
      for (Path path : paths.filter(Files::isRegularFile).collect(Collectors.toList())) {
        if (!Files.exists(path) || Files.isSymbolicLink(path)) {
          continue;
        }
        String entryName = dataDir.relativize(path).toString().replace('\\', '/');
        zip.putNextEntry(new ZipEntry(entryName));
        try {
          Files.copy(path, zip);
        } catch (NoSuchFileException ignored) {
          zip.closeEntry();
          continue;
        }
        zip.closeEntry();
      }
    }
  }

  private void restoreStorageFiles(Path backup) {
    Path storage = dataDir.resolve("storage");
    Path staging = null;
    try {
      FilePermissionSupport.secureDirectory(dataDir);
      staging = Files.createTempDirectory(dataDir, ".restore-storage-");
      FilePermissionSupport.secureDirectory(staging);
      try (InputStream input = Files.newInputStream(backup);
          ZipInputStream zip = new ZipInputStream(input)) {
        ZipBudget budget = new ZipBudget();
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
          budget.begin(entry);
          String name = entry.getName();
          if (name != null && name.startsWith("storage/")) {
            Path out = safeStorageTarget(staging, name.substring("storage/".length()));
            if (entry.isDirectory()) {
              Files.createDirectories(out);
            } else {
              Files.createDirectories(out.getParent());
              try (OutputStream output = Files.newOutputStream(out)) {
                drainEntry(zip, budget, output, MAX_BACKUP_UNCOMPRESSED_BYTES);
              }
            }
          } else {
            drainEntry(zip, budget, OutputStream.nullOutputStream(), MAX_BACKUP_UNCOMPRESSED_BYTES);
          }
          zip.closeEntry();
        }
      }
      deleteRecursively(storage);
      Files.move(staging, storage, StandardCopyOption.REPLACE_EXISTING);
      FilePermissionSupport.secureTree(storage);
      staging = null;
    } catch (IOException e) {
      throw internalFailure(
          400, "Failed to restore backup storage.", "restore backup storage", e);
    } finally {
      deleteRecursively(staging);
    }
  }

  private void validateStorageEntries(Path backup) {
    Path storage = dataDir.resolve("storage");
    try (InputStream input = Files.newInputStream(backup);
        ZipInputStream zip = new ZipInputStream(input)) {
      ZipBudget budget = new ZipBudget();
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        budget.begin(entry);
        String name = entry.getName();
        if (name != null && name.startsWith("storage/")) {
          safeStorageTarget(storage, name.substring("storage/".length()));
        }
        drainEntry(zip, budget, OutputStream.nullOutputStream(), MAX_BACKUP_UNCOMPRESSED_BYTES);
        zip.closeEntry();
      }
    } catch (ApiException e) {
      throw e;
    } catch (IOException e) {
      throw invalidBackupArchive();
    }
  }

  private Path safeStorageTarget(Path storage, String relative) {
    if (relative == null
        || relative.isBlank()
        || relative.startsWith("/")
        || relative.contains("\\")) {
      throw invalidBackupArchive();
    }
    Path out = storage.resolve(relative).normalize();
    if (!out.startsWith(storage.normalize())) {
      throw invalidBackupArchive();
    }
    return out;
  }

  private void drainEntry(InputStream input, ZipBudget budget, OutputStream output, long outputLimit)
      throws IOException {
    byte[] buffer = new byte[8192];
    long written = 0L;
    int read;
    while ((read = input.read(buffer)) >= 0) {
      if (read == 0) {
        continue;
      }
      budget.add(read);
      written += read;
      if (written > outputLimit) {
        throw invalidBackupArchive();
      }
      output.write(buffer, 0, read);
    }
  }

  private static final class ZipBudget {
    private int entries;
    private long bytes;

    private void begin(ZipEntry entry) {
      if (++entries > MAX_BACKUP_ENTRIES) {
        throw new ApiException(400, "Invalid backup archive.");
      }
      long declaredSize = entry.getSize();
      if (declaredSize > MAX_BACKUP_UNCOMPRESSED_BYTES) {
        throw new ApiException(400, "Invalid backup archive.");
      }
    }

    private void add(long count) {
      if (count < 0 || bytes > MAX_BACKUP_UNCOMPRESSED_BYTES - count) {
        throw new ApiException(400, "Invalid backup archive.");
      }
      bytes += count;
    }
  }

  private ApiException invalidBackupArchive() {
    return new ApiException(
        400, "Invalid backup archive.", ApiErrors.invalidField("file", "Invalid backup archive."));
  }

  private void deleteRecursively(Path path) {
    if (path == null || !Files.exists(path)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(path)) {
      for (Path item : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
        Files.deleteIfExists(item);
      }
    } catch (IOException e) {
      throw internalFailure(400, "Failed to clear storage files.", "clear storage files", e);
    }
  }

  private ApiException internalFailure(int status, String message, String operation, Throwable e) {
    SecuritySupport.logInternalFailure(operation, e);
    return new ApiException(status, message);
  }
}
