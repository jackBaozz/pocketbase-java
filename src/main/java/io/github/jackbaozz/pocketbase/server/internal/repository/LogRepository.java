package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.internal.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.IdGenerator;
import io.github.jackbaozz.pocketbase.server.internal.LogPersistenceSanitizer;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;
import io.github.jackbaozz.pocketbase.server.internal.RecordProcessor;
import io.github.jackbaozz.pocketbase.server.internal.RequestPrincipal;
import io.github.jackbaozz.pocketbase.server.internal.RuleEvaluator;
import io.github.jackbaozz.pocketbase.server.internal.SearchFieldValidationSupport;
import io.github.jackbaozz.pocketbase.server.internal.SearchQuerySupport;
import io.github.jackbaozz.pocketbase.server.internal.Unsafe;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.exception.DataAccessException;

public class LogRepository extends BaseRepository {

  private static final String SUPERUSERS = "_superusers";
  private static final String INTERNAL_ROWID = "@rowid";
  private static final DateTimeFormatter LOG_STATS_HOUR_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00:00.000'Z'").withZone(ZoneOffset.UTC);
  private final SettingsRepository settingsRepository;

  public LogRepository(
      JooqDatabase database, ObjectMapper mapper, SettingsRepository settingsRepository) {
    super(database, mapper);
    this.settingsRepository = settingsRepository;
  }

  public Map<String, Object> listLogs(Map<String, String> query) {
    Map<String, String> safeQuery = query == null ? Map.of() : query;
    SearchQuerySupport.Parameters search = SearchQuerySupport.parse(safeQuery);
    SearchFieldValidationSupport.validateLogs(search);

    try {
      Result<? extends Record> records =
          database.dsl().select(logFields()).from(qt("_logs")).fetch();

      List<Map<String, Object>> items = new ArrayList<>();
      int rowid = 1;
      for (Record r : records) {
        Map<String, Object> log = recordToLogMap(r);
        log.put(INTERNAL_ROWID, rowid++);
        if (matchesLogFilter(log, search.filter())) {
          items.add(log);
        }
      }

      SearchQuerySupport.sortMaps(items, search.sort(), "-created");
      int total = items.size();
      int from = search.fromIndex(total);
      int to = Math.min(total, from + search.perPage());
      List<Map<String, Object>> pageItems =
          items.subList(from, to).stream()
              .map(this::withoutInternalFields)
              .map(log -> RecordProcessor.selectFields(log, safeQuery.get("fields")))
              .toList();
      return SearchQuerySupport.result(search, total, pageItems);
    } catch (DataAccessException e) {
      return SearchQuerySupport.result(search, 0, List.of());
    }
  }

  public List<Map<String, Object>> logStats(Map<String, String> query) {
    SearchFieldValidationSupport.validateLogFilter(query == null ? null : query.get("filter"));
    List<Map<String, Object>> result = new ArrayList<>();
    try {
      Result<? extends Record> records =
          database.dsl().select(logFields()).from(qt("_logs")).fetch();

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
          .forEach(
              e -> {
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
      Record r =
          database.dsl().select(logFields()).from(qt("_logs")).where(qfs("id").eq(id)).fetchOne();
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

  private List<Field<?>> logFields() {
    return List.of(
        qfs("id"), qfs("created"), qfs("updated"), qfi("level"), qfs("message"), qfs("data"));
  }

  private Map<String, Object> withoutInternalFields(Map<String, Object> source) {
    Map<String, Object> copy = new LinkedHashMap<>(source);
    copy.remove(INTERNAL_ROWID);
    return copy;
  }

  private boolean matchesLogFilter(Map<String, Object> log, String filter) {
    if (filter == null || filter.isBlank()) {
      return true;
    }
    return RuleEvaluator.matches(filter, RuleEvaluator.context(log, null, Map.of(), "GET", null));
  }

  private String logHour(Object created) {
    if (created == null) {
      return "";
    }
    try {
      return LOG_STATS_HOUR_FORMAT.format(
          Instant.parse(String.valueOf(created)).truncatedTo(ChronoUnit.HOURS));
    } catch (Exception ignored) {
      return String.valueOf(created);
    }
  }

  public void recordActivityLog(
      String method,
      String url,
      int status,
      long duration,
      RequestPrincipal principal,
      Map<String, String> headers,
      String remoteIp) {
    Map<String, Object> settings = logSettings();
    int level = status >= 400 ? 8 : 0;
    long maxDays = longSetting(settings.get("maxDays"), 5L);
    long minLevel = longSetting(settings.get("minLevel"), 0L);
    if (maxDays <= 0 || level < minLevel) {
      return;
    }

    String id = IdGenerator.id();
    String now = Instant.now().toString();
    String rawMessage = (method == null ? "" : method) + " " + (url == null ? "" : url);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("type", "request");
    data.put("method", method == null ? "" : method);
    data.put("url", url == null ? "" : url);
    data.put("status", status);
    data.put("execTime", (double) duration);
    if (truthySetting(settings.get("logIP"), true) && remoteIp != null && !remoteIp.isBlank()) {
      data.put("remoteIP", remoteIp);
      data.put("userIP", remoteIp);
    }
    if (headers != null) {
      String userAgent = headers.getOrDefault("user-agent", "");
      String referer = headers.getOrDefault("referer", "");
      if (!userAgent.isBlank()) {
        data.put("userAgent", userAgent);
      }
      if (!referer.isBlank()) {
        data.put("referer", referer);
      }
    }

    data.put(
        "auth",
        principal != null ? (principal.superuser() ? SUPERUSERS : principal.collectionName()) : "");
    if (principal != null && truthySetting(settings.get("logAuthId"), false)) {
      data.put("authId", principal.id());
    }

    long maxDataSize = longSetting(settings.get("maxDataSize"), 0L);
    LogPersistenceSanitizer.Result sanitized =
        LogPersistenceSanitizer.sanitize(rawMessage, data, maxDataSize, mapper);

    try {
      database
          .dsl()
          .insertInto(qt("_logs"))
          .set(qfs("id"), id)
          .set(qfs("created"), now)
          .set(qfs("updated"), now)
          .set(qfi("level"), level)
          .set(qfs("message"), sanitized.message())
          .set(qfs("data"), sanitized.dataJson())
          .execute();
    } catch (DataAccessException ignored) {
      // Activity logging must never fail the request
    }
  }

  public void cleanupForCurrentSettings() {
    Map<String, Object> settings = logSettings();
    long maxDays = longSetting(settings.get("maxDays"), 5L);
    long minLevel = longSetting(settings.get("minLevel"), 0L);
    if (maxDays <= 0) {
      truncateLogs();
      return;
    }
    Instant cutoff = cutoffInstant(maxDays);
    try {
      database
          .dsl()
          .deleteFrom(qt("_logs"))
          .where(qfs("created").le(cutoff.toString()).or(qfi("level").lt((int) Math.min(Integer.MAX_VALUE, Math.max(Integer.MIN_VALUE, minLevel)))))
          .execute();
    } catch (DataAccessException ignored) {
    }
  }

  public void deleteOldLogs() {
    Map<String, Object> settings = logSettings();
    long maxDays = longSetting(settings.get("maxDays"), 5L);
    if (maxDays <= 0) {
      truncateLogs();
      return;
    }
    Instant cutoff = cutoffInstant(maxDays);
    try {
      database.dsl().deleteFrom(qt("_logs")).where(qfs("created").le(cutoff.toString())).execute();
    } catch (DataAccessException ignored) {
    }
  }

  private Instant cutoffInstant(long maxDays) {
    if (maxDays >= 365_000L) {
      return Instant.EPOCH;
    }
    return Instant.now().minus(maxDays, ChronoUnit.DAYS);
  }

  public void truncateLogs() {
    try {
      database.dsl().deleteFrom(qt("_logs")).execute();
    } catch (DataAccessException ignored) {
    }
  }

  private Map<String, Object> logSettings() {
    Object value = settingsRepository.loadRawSettings().get("logs");
    return value instanceof Map<?, ?> map ? Unsafe.stringObjectMap(map) : Map.of();
  }

  private long longSetting(Object value, long fallback) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private int intSetting(Object value, int fallback) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private boolean truthySetting(Object value, boolean fallback) {
    if (value == null) {
      return fallback;
    }
    if (value instanceof Boolean bool) {
      return bool;
    }
    if (value instanceof Number number) {
      return number.intValue() != 0;
    }
    String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    return normalized.isBlank() ? fallback : List.of("1", "true", "yes", "on").contains(normalized);
  }
}
