package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.IdGenerator;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;
import io.github.jackbaozz.pocketbase.server.internal.RequestPrincipal;
import org.jooq.exception.DataAccessException;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LogRepository extends BaseRepository {

    private static final String SUPERUSERS = "_superusers";

    public LogRepository(JooqDatabase database, ObjectMapper mapper) {
        super(database, mapper);
    }

    public Map<String, Object> listLogs(Map<String, String> query) {
        Map<String, String> safeQuery = query == null ? Map.of() : query;
        int page = 1;
        int perPage = 30;
        try {
            if (safeQuery.containsKey("page")) page = Integer.parseInt(safeQuery.get("page"));
            if (safeQuery.containsKey("perPage")) perPage = Integer.parseInt(safeQuery.get("perPage"));
        } catch (NumberFormatException ignored) {}

        try (Connection conn = database.connection()) {
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
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = database.connection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT created FROM _logs")) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            while (rs.next()) {
                String created = rs.getString("created");
                if (created != null && created.length() >= 13) {
                    String hour = created.substring(0, 13) + ":00:00.000Z";
                    counts.put(hour, counts.getOrDefault(hour, 0) + 1);
                }
            }
            counts.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> {
                        Map<String, Object> bucket = new LinkedHashMap<>();
                        bucket.put("date", e.getKey());
                        bucket.put("total", e.getValue());
                        result.add(bucket);
                    });
        } catch (SQLException ignored) {
        }
        return result;
    }

    public Map<String, Object> getLog(String id, Map<String, String> query) {
        try (Connection conn = database.connection();
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
                    } else {
                        log.put("data", Map.of());
                    }
                    return log;
                }
            }
        } catch (SQLException ignored) {
        }
        throw new ApiException(404, "Log not found.");
    }

    public void recordActivityLog(String method, String url, int status, long duration, RequestPrincipal principal, Map<String, String> headers, String remoteIp) {
        String id = IdGenerator.id();
        String now = Instant.now().toString();
        int level = status >= 400 ? 8 : 0;
        String message = (method == null ? "" : method) + " " + (url == null ? "" : url);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "request");
        data.put("method", method == null ? "" : method);
        data.put("url", url == null ? "" : url);
        data.put("status", status);
        data.put("execTime", (double) duration);
        data.put("remoteIP", remoteIp == null ? "" : remoteIp);
        data.put("userIP", remoteIp == null ? "" : remoteIp);
        data.put("userAgent", headers != null ? headers.getOrDefault("user-agent", "") : "");
        data.put("referer", headers != null ? headers.getOrDefault("referer", "") : "");
        data.put("auth", principal != null ? (principal.superuser() ? SUPERUSERS : principal.collectionName()) : "");
        if (principal != null) {
            data.put("authId", principal.id());
        }

        try {
            database.dsl()
                    .insertInto(qt("_logs"))
                    .set(qfs("id"), id)
                    .set(qfs("created"), now)
                    .set(qfs("updated"), now)
                    .set(qfi("level"), level)
                    .set(qfs("message"), message)
                    .set(qfs("data"), mapper.writeValueAsString(data))
                    .execute();
        } catch (DataAccessException | IOException ignored) {
            // Activity logging must never fail the request
        }
    }
}
