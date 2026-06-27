package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.internal.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.IdGenerator;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;
import io.github.jackbaozz.pocketbase.server.internal.RequestPrincipal;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.exception.DataAccessException;

import java.io.IOException;
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

        try {
            int total = database.dsl().fetchCount(qt("_logs"));

            int offset = (page - 1) * perPage;
            Result<? extends Record> records = database.dsl()
                    .selectFrom(qt("_logs"))
                    .orderBy(qfs("created").desc())
                    .limit(perPage)
                    .offset(offset)
                    .fetch();

            List<Map<String, Object>> items = new ArrayList<>();
            for (Record r : records) {
                items.add(recordToLogMap(r));
            }

            int totalPages = (int) Math.ceil((double) total / perPage);
            return Map.of("items", items, "page", page, "perPage", perPage, "totalItems", total, "totalPages", totalPages);
        } catch (DataAccessException e) {
            return Map.of("items", List.of(), "page", 1, "perPage", 30, "totalItems", 0, "totalPages", 0);
        }
    }

    public List<Map<String, Object>> logStats(Map<String, String> query) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            Result<? extends Record> records = database.dsl()
                    .select(qfs("created"))
                    .from(qt("_logs"))
                    .fetch();

            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Record r : records) {
                String created = r.get(qfs("created"));
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
        } catch (DataAccessException ignored) {
        }
        return result;
    }

    public Map<String, Object> getLog(String id, Map<String, String> query) {
        try {
            Record r = database.dsl()
                    .selectFrom(qt("_logs"))
                    .where(qfs("id").eq(id))
                    .fetchOne();
            if (r != null) {
                return recordToLogMap(r);
            }
        } catch (DataAccessException ignored) {
        }
        throw new ApiException(404, "Log not found.");
    }

    private Map<String, Object> recordToLogMap(Record r) {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("id", r.get(qfs("id")));
        log.put("created", r.get(qfs("created")));
        log.put("updated", r.get(qfs("updated")));
        log.put("level", r.get(qfi("level")));
        log.put("message", r.get(qfs("message")));
        String dataStr = r.get(qfs("data"));
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
