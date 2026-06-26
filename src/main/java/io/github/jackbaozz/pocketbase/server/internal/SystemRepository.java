package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;


import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SystemRepository {
    private final RelationalStorageEngine engine;
    private final ObjectMapper mapper;

    public SystemRepository(RelationalStorageEngine engine, ObjectMapper mapper) {
        this.engine = engine;
        this.mapper = mapper;
    }

    public Map<String, Object> getSettings(Map<String, String> query) {
        Map<String, String> safeQuery = query == null ? Map.of() : query;
        Map<String, Object> settings = getRawSettings();
        if (safeQuery.containsKey("fields")) {
            return RecordProcessor.selectFields(redactedSettings(), safeQuery.get("fields"));
        }
        return redactedSettings();
    }
    
    private Map<String, Object> getRawSettings() {
        try (Connection conn = engine.connection();
             PreparedStatement stmt = conn.prepareStatement("SELECT value FROM _params WHERE key = ?")) {
            stmt.setString(1, "settings");
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String value = rs.getString(1);
                    if (value != null && !value.isBlank()) {
                        Map<String, Object> stored = mapper.readValue(value, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                        Map<String, Object> defaults = defaultSettings();
                        deepMerge(defaults, stored);
                        return defaults;
                    }
                }
            }
        } catch (SQLException | IOException ignored) {
        }
        return defaultSettings();
    }
    
    private Map<String, Object> redactedSettings() {
        Map<String, Object> copy = mapper.convertValue(getRawSettings(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        hideSensitiveSettings(copy);
        return copy;
    }

    private void hideSensitiveSettings(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> target = (Map<String, Object>) map;
            for (Map.Entry<String, Object> entry : new ArrayList<>(target.entrySet())) {
                Object child = entry.getValue();
                if (hiddenSettingKey(entry.getKey())) {
                    target.remove(entry.getKey());
                } else {
                    hideSensitiveSettings(child);
                }
            }
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(this::hideSensitiveSettings);
        }
    }

    private boolean hiddenSettingKey(String key) {
        if (key == null || key.isBlank()) return false;
        String k = key.toLowerCase(java.util.Locale.ROOT);
        return k.contains("secret") || k.contains("password") || k.contains("private");
    }

    
    private Map<String, Object> orderedMap(Object... entries) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            out.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return out;
    }

    private Map<String, Object> defaultSettings() {
        return orderedMap(
                "meta", orderedMap(
                        "accentColor", "#1055c9",
                        "appName", "pocketbase-java",
                        "appURL", "http://127.0.0.1:8090",
                        "senderName", "PocketBase Java",
                        "senderAddress", "noreply@example.com",
                        "hideControls", false
                ),
                "logs", orderedMap(
                        "maxDays", 5,
                        "minLevel", 0,
                        "logIP", true,
                        "logAuthId", true
                ),
                "smtp", orderedMap(
                        "enabled", false,
                        "host", "",
                        "port", 587,
                        "username", "",
                        "password", "",
                        "authMethod", "PLAIN",
                        "tls", false,
                        "localName", ""
                ),
                "s3", orderedMap(
                        "enabled", false,
                        "bucket", "",
                        "region", "",
                        "endpoint", "",
                        "accessKey", "",
                        "secret", "",
                        "forcePathStyle", false
                ),
                "backups", orderedMap(
                        "cron", "",
                        "cronMaxKeep", 3,
                        "s3", orderedMap(
                                "enabled", false,
                                "bucket", "",
                                "region", "",
                                "endpoint", "",
                                "accessKey", "",
                                "secret", "",
                                "forcePathStyle", false
                        )
                ),
                "rateLimits", orderedMap(
                        "enabled", false,
                        "rules", List.of(
                                orderedMap("label", "*:auth", "audience", "", "duration", 3, "maxRequests", 2),
                                orderedMap("label", "*:create", "audience", "", "duration", 5, "maxRequests", 20),
                                orderedMap("label", "/api/batch", "audience", "", "duration", 1, "maxRequests", 3),
                                orderedMap("label", "/api/", "audience", "", "duration", 10, "maxRequests", 300)
                        ),
                        "excludedIPs", List.of()
                ),
                "trustedProxy", orderedMap(
                        "headers", List.of(),
                        "useLeftmostIP", false
                ),
                "batch", orderedMap(
                        "enabled", true,
                        "maxRequests", 50,
                        "timeout", 3,
                        "maxBodySize", 33_554_432
                ),
                "superuserIPs", List.of()
        );
    }

    public Map<String, Object> updateSettings(JsonNode body, Map<String, String> query) {
        Map<String, Object> current = getRawSettings();
        Map<String, Object> updates = mapper.convertValue(body, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        deepMerge(current, updates);
        try {
            String value = mapper.writeValueAsString(current);
            String now = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS'Z'")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.now());
            try (Connection conn = engine.connection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "INSERT INTO _params (id, key, value, created, updated) VALUES (?, ?, ?, ?, ?) " +
                         "ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated = excluded.updated")) {
                stmt.setString(1, "settings");
                stmt.setString(2, "settings");
                stmt.setString(3, value);
                stmt.setString(4, now);
                stmt.setString(5, now);
                stmt.executeUpdate();
            }
        } catch (SQLException | IOException e) {
            throw new ApiException(500, "Failed to update settings.", e);
        }
        return getSettings(query);
    }

    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        if (source == null || source.isEmpty()) return;
        source.forEach((rawKey, value) -> {
            String key = normalizeSettingKey(rawKey);
            if ("******".equals(value) && target.containsKey(key)) return;
            Object existing = target.get(key);
            if (existing instanceof Map<?, ?> existingMap && value instanceof Map<?, ?> sourceMap) {
                deepMerge((Map<String, Object>) existingMap, (Map<String, Object>) sourceMap);
            } else {
                target.put(key, mapper.convertValue(value, Object.class));
            }
        });
    }

    private String normalizeSettingKey(String key) {
        if ("appUrl".equals(key)) return "appURL";
        if ("logIp".equals(key)) return "logIP";
        return key;
    }

    public Map<String, Object> listLogs(Map<String, String> query) {
        Map<String, String> safeQuery = query == null ? Map.of() : query;
        int page = 1;
        int perPage = 30;
        try {
            if (safeQuery.containsKey("page")) page = Integer.parseInt(safeQuery.get("page"));
            if (safeQuery.containsKey("perPage")) perPage = Integer.parseInt(safeQuery.get("perPage"));
        } catch (NumberFormatException ignored) {}

        try (Connection conn = engine.connection()) {
            int total = 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT count(*) FROM _logs")) {
                if (rs.next()) total = rs.getInt(1);
            }

            int offset = (page - 1) * perPage;
            List<Map<String, Object>> items = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM _logs ORDER BY created DESC LIMIT ? OFFSET ?")) {
                stmt.setInt(1, perPage);
                stmt.setInt(2, offset);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> log = new LinkedHashMap<>();
                        log.put("id", rs.getString("id"));
                        log.put("created", rs.getString("created"));
                        log.put("updated", rs.getString("updated"));
                        log.put("level", rs.getInt("level"));
                        log.put("message", rs.getString("message"));
                        String dataStr = rs.getString("data");
                        if (dataStr != null) {
                            try {
                                log.put("data", mapper.readValue(dataStr, Map.class));
                            } catch (IOException e) {
                                log.put("data", Map.of());
                            }
                        }
                        items.add(log);
                    }
                }
            }
            int totalPages = (int) Math.ceil((double) total / perPage);
            return Map.of("items", items, "page", page, "perPage", perPage, "totalItems", total, "totalPages", totalPages);
        } catch (SQLException e) {
            return Map.of("items", List.of(), "page", 1, "perPage", 30, "totalItems", 0, "totalPages", 0);
        }
    }

    public List<Map<String, Object>> logStats(Map<String, String> query) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        try (Connection conn = engine.connection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT created FROM _logs")) {
            while (rs.next()) {
                String created = rs.getString(1);
                if (created != null && created.length() >= 13) {
                    String date = created.substring(0, 13) + ":00:00.000Z";
                    Map<String, Object> bucket = grouped.computeIfAbsent(date, key -> {
                        Map<String, Object> createdBucket = new LinkedHashMap<>();
                        createdBucket.put("date", key);
                        createdBucket.put("total", 0);
                        return createdBucket;
                    });
                    bucket.put("total", ((Number) bucket.get("total")).intValue() + 1);
                }
            }
        } catch (SQLException ignored) {
        }
        return new ArrayList<>(grouped.values());
    }

    public Map<String, Object> getLog(String id, Map<String, String> query) {
        try (Connection conn = engine.connection();
             PreparedStatement stmt = conn.prepareStatement("SELECT * FROM _logs WHERE id = ?")) {
            stmt.setString(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> log = new LinkedHashMap<>();
                    log.put("id", rs.getString("id"));
                    log.put("created", rs.getString("created"));
                    log.put("updated", rs.getString("updated"));
                    log.put("level", rs.getInt("level"));
                    log.put("message", rs.getString("message"));
                    String dataStr = rs.getString("data");
                    if (dataStr != null) {
                        try {
                            log.put("data", mapper.readValue(dataStr, Map.class));
                        } catch (IOException e) {
                            log.put("data", Map.of());
                        }
                    }
                    return log;
                }
            }
        } catch (SQLException ignored) {
        }
        throw new ApiException(404, "Log not found.");
    }

    public List<Map<String, Object>> listCrons() {
        return List.of();
    }

    public void runCron(String id) {
    }

    public Map<String, Object> listBackups(int page, int perPage) {
        return Map.of("items", List.of(), "page", 1, "perPage", 100, "totalItems", 0, "totalPages", 0);
    }

    public void deleteBackup(String key) {
    }

    public Map<String, Object> restoreBackup(String key) {
        return Map.of();
    }

    public Map<String, Object> createBackup(JsonNode body) {
        return Map.of();
    }

    public Map<String, Object> uploadBackup(String filename, byte[] bytes) {
        return Map.of();
    }
}
