package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.internal.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.IdGenerator;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class BackupRepository extends BaseRepository {

    private static final String SNAPSHOT_ENTRY = "relational-backup.json";

    private final Path dataDir;

    public BackupRepository(JooqDatabase database, ObjectMapper mapper, Path dataDir) {
        super(database, mapper);
        this.dataDir = dataDir;
    }

    public Map<String, Object> listBackups(int page, int perPage) {
        try {
            Path backupsDir = dataDir.resolve("backups");
            Files.createDirectories(backupsDir);
            List<Map<String, Object>> items = new ArrayList<>();
            try (var stream = Files.list(backupsDir)) {
                stream.filter(p -> p.toString().endsWith(".zip"))
                        .forEach(p -> {
                            try {
                                Map<String, Object> item = new LinkedHashMap<>();
                                item.put("key", p.getFileName().toString());
                                item.put("name", p.getFileName().toString());
                                item.put("size", Files.size(p));
                                item.put("modified", Files.getLastModifiedTime(p).toMillis());
                                items.add(item);
                            } catch (IOException ignored) {
                            }
                        });
            }
            items.sort((a, b) -> Long.compare((long) b.get("modified"), (long) a.get("modified")));
            return Map.of("items", items, "page", 1, "perPage", 100, "totalItems", items.size(), "totalPages", 1);
        } catch (IOException e) {
            return Map.of("items", List.of(), "page", 1, "perPage", 100, "totalItems", 0, "totalPages", 0);
        }
    }

    public void deleteBackup(String key) {
        try {
            Path backup = backupFileRequired(key);
            Files.delete(backup);
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException(400, "Failed to delete backup.");
        }
    }

    public Map<String, Object> restoreBackup(String key) {
        Path backup = backupFileRequired(key);
        Map<String, Object> snapshot = readSnapshot(backup);
        database.transactional(() -> {
            restoreDatabase(snapshot);
            return null;
        });
        restoreStorageFiles(backup);
        return Map.of("restored", key);
    }

    public Map<String, Object> createBackup(JsonNode body) {
        try {
            Path backupsDir = dataDir.resolve("backups");
            Files.createDirectories(backupsDir);

            String name;
            if (body != null && body.has("name") && !body.get("name").isNull() && !body.get("name").asText().isBlank()) {
                name = body.get("name").asText().trim();
                if (!name.matches("^(@auto_pb_backup_)?[a-z0-9_-]+\\.zip$")) {
                    throw new ApiException(400, "Invalid backup name. Must match: [a-z0-9_-].zip",
                            Map.of("name", Map.of("code", "validation_invalid_format", "message", "Must be in the format [a-z0-9_-].zip")));
                }
            } else {
                name = "pb_backup_" + Instant.now().toString().replace(":", "-").replace(".", "-") + ".zip";
            }

            Path backupFile = backupsDir.resolve(name);
            if (Files.exists(backupFile)) {
                throw new ApiException(400, "Backup already exists.",
                        Map.of("name", Map.of("code", "validation_not_unique", "message", "Backup already exists.")));
            }

            writeBackupZip(backupFile);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("key", name);
            result.put("size", Files.size(backupFile));
            result.put("modified", Files.getLastModifiedTime(backupFile).toMillis());
            return result;
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException(400, "Failed to create backup: " + e.getMessage());
        }
    }

    public Map<String, Object> uploadBackup(String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ApiException(400, "Backup file is required.");
        }
        try {
            Path backupsDir = dataDir.resolve("backups");
            Files.createDirectories(backupsDir);

            String name = filename;
            if (name == null || name.isBlank()) {
                name = "upload_" + IdGenerator.id() + ".zip";
            }
            name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (!name.endsWith(".zip")) {
                name = name + ".zip";
            }

            Path backupFile = backupsDir.resolve(name);
            Files.write(backupFile, bytes, StandardOpenOption.CREATE_NEW);
            validateBackupZip(backupFile);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("key", name);
            result.put("size", bytes.length);
            result.put("modified", Files.getLastModifiedTime(backupFile).toMillis());
            return result;
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException(400, "Failed to upload backup: " + e.getMessage());
        }
    }

    public Path backupFile(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        validateBackupKey(key);
        Path backup = dataDir.resolve("backups").resolve(key);
        return Files.exists(backup) && Files.isRegularFile(backup) ? backup : null;
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
            throw new ApiException(400, "Backup key is required.");
        }
        if (key.contains("..") || key.contains("/") || key.contains("\\")) {
            throw new ApiException(400, "Invalid backup key.");
        }
    }

    private void writeBackupZip(Path backupFile) throws IOException {
        byte[] snapshot = mapper.writeValueAsBytes(createSnapshot());
        try (OutputStream output = Files.newOutputStream(backupFile, StandardOpenOption.CREATE_NEW);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(SNAPSHOT_ENTRY));
            zip.write(snapshot);
            zip.closeEntry();
            zipStorageFiles(zip);
        }
    }

    private Map<String, Object> createSnapshot() {
        Connection conn = null;
        try {
            conn = database.connection();
            List<Map<String, Object>> objects = readSqliteObjects(conn);
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
            snapshot.put("objects", objects);
            snapshot.put("tables", tables);
            return snapshot;
        } catch (SQLException e) {
            throw new ApiException(400, "Failed to create backup snapshot: " + e.getMessage());
        } finally {
            if (conn != null) {
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
             ResultSet rs = stmt.executeQuery("""
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
                object.put("tblName", rs.getString("tbl_name"));
                object.put("sql", rs.getString("sql"));
                objects.add(object);
            }
        }
        return objects;
    }

    private List<Map<String, Object>> readRows(Connection conn, String table) throws SQLException {
        validateSqlIdentifier(table);
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM " + database.quoteIdentifier(table))) {
            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(meta.getColumnName(i), rs.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readSnapshot(Path backup) {
        try (InputStream input = Files.newInputStream(backup);
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (SNAPSHOT_ENTRY.equals(entry.getName())) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    zip.transferTo(bytes);
                    Map<String, Object> snapshot = mapper.readValue(new ByteArrayInputStream(bytes.toByteArray()), Map.class);
                    if (!"pocketbase-java-relational-backup-v1".equals(snapshot.get("format"))) {
                        throw new ApiException(400, "Invalid backup archive.");
                    }
                    return snapshot;
                }
                zip.closeEntry();
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException(400, "Invalid backup archive.");
        }
        throw new ApiException(400, "Invalid backup archive.");
    }

    @SuppressWarnings("unchecked")
    private void restoreDatabase(Map<String, Object> snapshot) {
        Connection conn = null;
        try {
            conn = database.connection();
            List<Map<String, Object>> objects = (List<Map<String, Object>>) snapshot.getOrDefault("objects", List.of());
            List<Map<String, Object>> tables = (List<Map<String, Object>>) snapshot.getOrDefault("tables", List.of());

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = OFF");
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
                stmt.execute("PRAGMA foreign_keys = ON");
            }
        } catch (SQLException | RuntimeException e) {
            if (e instanceof ApiException apiException) {
                throw apiException;
            }
            throw new ApiException(400, "Failed to restore backup: " + e.getMessage());
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
        List<Map<String, Object>> current = readSqliteObjects(conn);
        for (Map<String, Object> object : current) {
            if ("view".equals(object.get("type"))) {
                String name = String.valueOf(object.get("name"));
                validateSqlIdentifier(name);
                stmt.execute("DROP VIEW IF EXISTS " + database.quoteIdentifier(name));
            }
        }
        for (Map<String, Object> object : current) {
            if ("index".equals(object.get("type"))) {
                String name = String.valueOf(object.get("name"));
                validateSqlIdentifier(name);
                stmt.execute("DROP INDEX IF EXISTS " + database.quoteIdentifier(name));
            }
        }
        for (Map<String, Object> object : current) {
            if ("table".equals(object.get("type"))) {
                String name = String.valueOf(object.get("name"));
                validateSqlIdentifier(name);
                stmt.execute("DROP TABLE IF EXISTS " + database.quoteIdentifier(name));
            }
        }
    }

    private void executeSnapshotSql(Statement stmt, Map<String, Object> object, String expectedPrefix) throws SQLException {
        String name = String.valueOf(object.get("name"));
        validateSqlIdentifier(name);
        String sql = String.valueOf(object.get("sql")).trim();
        if (!sql.toUpperCase(java.util.Locale.ROOT).startsWith(expectedPrefix)) {
            throw new ApiException(400, "Invalid backup archive.");
        }
        stmt.execute(sql);
    }

    @SuppressWarnings("unchecked")
    private void insertRows(Connection conn, Map<String, Object> table) throws SQLException {
        String tableName = String.valueOf(table.get("name"));
        validateSqlIdentifier(tableName);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) table.getOrDefault("rows", List.of());
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
        String columnSql = columns.stream().map(database::quoteIdentifier).collect(Collectors.joining(", "));
        String placeholders = columns.stream().map(ignored -> "?").collect(Collectors.joining(", "));
        String sql = "INSERT INTO " + database.quoteIdentifier(tableName) + " (" + columnSql + ") VALUES (" + placeholders + ")";
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
                if (!Files.exists(path)) {
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
        deleteRecursively(storage);
        try {
            Files.createDirectories(storage);
            try (InputStream input = Files.newInputStream(backup);
                 ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (name != null && name.startsWith("storage/")) {
                        Path out = safeStorageTarget(storage, name.substring("storage/".length()));
                        if (entry.isDirectory()) {
                            Files.createDirectories(out);
                        } else {
                            Files.createDirectories(out.getParent());
                            Files.copy(zip, out);
                        }
                    }
                    zip.closeEntry();
                }
            }
        } catch (IOException e) {
            throw new ApiException(400, "Failed to restore backup storage: " + e.getMessage());
        }
    }

    private Path safeStorageTarget(Path storage, String relative) {
        if (relative == null || relative.isBlank() || relative.startsWith("/") || relative.contains("\\")) {
            throw new ApiException(400, "Invalid backup archive.");
        }
        Path out = storage.resolve(relative).normalize();
        if (!out.startsWith(storage.normalize())) {
            throw new ApiException(400, "Invalid backup archive.");
        }
        return out;
    }

    private void validateBackupZip(Path backup) {
        readSnapshot(backup);
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
            throw new ApiException(400, "Failed to clear storage files: " + e.getMessage());
        }
    }
}
