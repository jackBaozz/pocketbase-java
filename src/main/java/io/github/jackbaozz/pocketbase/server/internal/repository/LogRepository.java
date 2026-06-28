package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.internal.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.IdGenerator;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;
import io.github.jackbaozz.pocketbase.server.internal.RecordProcessor;
import io.github.jackbaozz.pocketbase.server.internal.RequestPrincipal;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.exception.DataAccessException;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class LogRepository extends BaseRepository {

    private static final String SUPERUSERS = "_superusers";
    private static final String INTERNAL_ROWID = "@rowid";
    private static final DateTimeFormatter LOG_STATS_HOUR_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00:00.000'Z'").withZone(ZoneOffset.UTC);

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
            Result<? extends Record> records = database.dsl()
                    .selectFrom(qt("_logs"))
                    .fetch();

            List<Map<String, Object>> items = new ArrayList<>();
            int rowid = 1;
            for (Record r : records) {
                Map<String, Object> log = recordToLogMap(r);
                log.put(INTERNAL_ROWID, rowid++);
                if (matchesLogFilter(log, safeQuery.get("filter"))) {
                    items.add(log);
                }
            }

            sortLogs(items, safeQuery.getOrDefault("sort", "-created"));
            int total = items.size();
            int offset = Math.min(total, (page - 1) * perPage);
            int to = Math.min(total, offset + perPage);
            List<Map<String, Object>> pageItems = items.subList(offset, to).stream()
                    .map(this::withoutInternalFields)
                    .map(log -> RecordProcessor.selectFields(log, safeQuery.get("fields")))
                    .toList();
            int totalPages = (int) Math.ceil((double) total / perPage);
            return Map.of("items", pageItems, "page", page, "perPage", perPage, "totalItems", total, "totalPages", totalPages);
        } catch (DataAccessException e) {
            return Map.of("items", List.of(), "page", 1, "perPage", 30, "totalItems", 0, "totalPages", 0);
        }
    }

    public List<Map<String, Object>> logStats(Map<String, String> query) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            Result<? extends Record> records = database.dsl()
                    .selectFrom(qt("_logs"))
                    .fetch();

            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Record r : records) {
                Map<String, Object> log = recordToLogMap(r);
                if (matchesLogFilter(log, query == null ? null : query.get("filter"))) {
                    String hour = logHour(log.get("created"));
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

    private Map<String, Object> withoutInternalFields(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>(source);
        copy.remove(INTERNAL_ROWID);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private boolean matchesLogFilter(Map<String, Object> log, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        String[] parts = filter.trim().split("=", 2);
        if (parts.length != 2) {
            return true;
        }
        String left = parts[0].trim();
        String right = stripQuotes(parts[1].trim());
        Object value = null;
        if (left.startsWith("data.") && log.get("data") instanceof Map<?, ?> data) {
            value = ((Map<String, Object>) data).get(left.substring("data.".length()));
        } else {
            value = log.get(left);
        }
        return Objects.equals(String.valueOf(value), right);
    }

    private String stripQuotes(String value) {
        if ((value.startsWith("'") && value.endsWith("'")) || (value.startsWith("\"") && value.endsWith("\""))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private void sortLogs(List<Map<String, Object>> items, String sort) {
        String key = sort == null || sort.isBlank() ? "-created" : sort.trim();
        boolean desc = key.startsWith("-");
        if (desc || key.startsWith("+")) {
            key = key.substring(1);
        }
        String sortKey = key;
        Comparator<Map<String, Object>> comparator = (left, right) ->
                comparableLogValue(left, sortKey).compareTo(comparableLogValue(right, sortKey));
        if (desc) {
            comparator = comparator.reversed();
        }
        items.sort(comparator);
    }

    private String comparableLogValue(Map<String, Object> log, String key) {
        Object value = INTERNAL_ROWID.equals(key) ? log.get(INTERNAL_ROWID) : log.get(key);
        if (value instanceof Number number) {
            return String.format("%020d", number.longValue());
        }
        return value == null ? "" : String.valueOf(value).toLowerCase(Locale.ROOT);
    }

    private String logHour(Object created) {
        if (created == null) {
            return "";
        }
        try {
            return LOG_STATS_HOUR_FORMAT.format(Instant.parse(String.valueOf(created)).truncatedTo(ChronoUnit.HOURS));
        } catch (Exception ignored) {
            return String.valueOf(created);
        }
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
