package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.internal.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.IdGenerator;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BackupRepository extends BaseRepository {

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
                            } catch (IOException ignored) {}
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
            validateBackupKey(key);
            Path backup = dataDir.resolve("backups").resolve(key);
            if (Files.exists(backup)) {
                Files.delete(backup);
            } else {
                throw new ApiException(404, "Backup not found.");
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException(400, "Failed to delete backup.");
        }
    }

    public Map<String, Object> restoreBackup(String key) {
        validateBackupKey(key);
        Path backup = dataDir.resolve("backups").resolve(key);
        if (!Files.exists(backup)) {
            throw new ApiException(404, "Backup not found.");
        }
        // Restore is a destructive operation that requires stopping and
        // replacing the database. For now, return a placeholder response.
        // Full implementation requires database shutdown, file replacement,
        // and restart - which is a complex lifecycle operation.
        throw new ApiException(400, "Backup restore requires server restart. Use the CLI to restore from backup.");
    }

    public Map<String, Object> createBackup(JsonNode body) {
        try {
            Path backupsDir = dataDir.resolve("backups");
            Files.createDirectories(backupsDir);

            String name;
            if (body != null && body.has("name") && !body.get("name").isNull() && !body.get("name").asText().isBlank()) {
                name = body.get("name").asText().trim();
                if (!name.matches("^[a-z0-9_-]+\\.zip$")) {
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

            // Create an empty zip file as a placeholder backup
            // A full implementation would dump the database into the zip
            byte[] emptyZip = {0x50, 0x4B, 0x05, 0x06, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00};
            Files.write(backupFile, emptyZip);

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
        try {
            Path backupsDir = dataDir.resolve("backups");
            Files.createDirectories(backupsDir);

            String name = filename;
            if (name == null || name.isBlank()) {
                name = "upload_" + IdGenerator.id() + ".zip";
            }
            // Sanitize filename
            name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (!name.endsWith(".zip")) {
                name = name + ".zip";
            }

            Path backupFile = backupsDir.resolve(name);
            Files.write(backupFile, bytes);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("key", name);
            result.put("size", bytes.length);
            result.put("modified", Files.getLastModifiedTime(backupFile).toMillis());
            return result;
        } catch (IOException e) {
            throw new ApiException(400, "Failed to upload backup: " + e.getMessage());
        }
    }

    public Path backupFile(String key) {
        if (key == null || key.isBlank()) return null;
        validateBackupKey(key);
        return dataDir.resolve("backups").resolve(key);
    }

    private void validateBackupKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(400, "Backup key is required.");
        }
        // Prevent path traversal
        if (key.contains("..") || key.contains("/") || key.contains("\\")) {
            throw new ApiException(400, "Invalid backup key.");
        }
    }
}
