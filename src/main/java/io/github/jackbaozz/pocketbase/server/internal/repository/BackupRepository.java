package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
            Path backup = dataDir.resolve("backups").resolve(key);
            if (Files.exists(backup)) {
                Files.delete(backup);
            }
        } catch (IOException ignored) {}
    }

    public Map<String, Object> restoreBackup(String key) {
        throw new ApiException(400, "Failed to restore backup.");
    }

    public Map<String, Object> createBackup(JsonNode body) {
        return Map.of();
    }

    public Map<String, Object> uploadBackup(String filename, byte[] bytes) {
        return Map.of();
    }

    public Path backupFile(String key) {
        if (key == null || key.isBlank()) return null;
        return dataDir.resolve("backups").resolve(key);
    }
}
