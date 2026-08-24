package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class LogPersistenceSanitizer {

  public static final int MESSAGE_MAX_BYTES = 8000;
  public static final int DEFAULT_MAX_DATA_SIZE = 16384;
  public static final String TRUNCATED_KEY = "__pb_truncated__";

  private LogPersistenceSanitizer() {}

  public record Result(String message, String dataJson, Map<String, Object> dataMap) {}

  public static Result sanitize(
      String message, Map<String, Object> data, long maxDataSize, ObjectMapper mapper) {
    String sanitizedMessage = sanitizeMessage(message);
    DataResult sanitizedData = sanitizeData(data, maxDataSize, mapper);
    return new Result(sanitizedMessage, sanitizedData.json(), sanitizedData.map());
  }

  public static String sanitizeMessage(String message) {
    if (message == null || message.isEmpty()) {
      return "";
    }
    byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= MESSAGE_MAX_BYTES) {
      return message;
    }
    int low = 0;
    int high = message.length();
    int bestLen = 0;
    while (low <= high) {
      int mid = (low + high) >>> 1;
      int adjustedMid = mid;
      if (adjustedMid > 0
          && adjustedMid < message.length()
          && Character.isHighSurrogate(message.charAt(adjustedMid - 1))
          && Character.isLowSurrogate(message.charAt(adjustedMid))) {
        adjustedMid--;
      }
      String sub = message.substring(0, adjustedMid);
      if (sub.getBytes(StandardCharsets.UTF_8).length <= MESSAGE_MAX_BYTES) {
        bestLen = adjustedMid;
        low = mid + 1;
      } else {
        high = mid - 1;
      }
    }
    return message.substring(0, bestLen);
  }

  public record DataResult(String json, Map<String, Object> map) {}

  public static DataResult sanitizeData(
      Map<String, Object> data, long maxDataSize, ObjectMapper mapper) {
    if (data == null || data.isEmpty()) {
      return new DataResult("{}", Map.of());
    }

    long budget = maxDataSize <= 0 ? DEFAULT_MAX_DATA_SIZE : maxDataSize;
    try {
      String fullJson = mapper.writeValueAsString(data);
      byte[] fullBytes = fullJson.getBytes(StandardCharsets.UTF_8);
      if (fullBytes.length <= budget) {
        return new DataResult(fullJson, data);
      }

      Map<String, Object> truncatedMap = new LinkedHashMap<>();
      for (Map.Entry<String, Object> entry : data.entrySet()) {
        Map<String, Object> candidate = new LinkedHashMap<>(truncatedMap);
        candidate.put(entry.getKey(), entry.getValue());
        String candidateJson = mapper.writeValueAsString(candidate);
        if (candidateJson.getBytes(StandardCharsets.UTF_8).length <= budget) {
          truncatedMap.put(entry.getKey(), entry.getValue());
        } else {
          break;
        }
      }

      truncatedMap.put(TRUNCATED_KEY, true);
      String resultJson = mapper.writeValueAsString(truncatedMap);
      return new DataResult(resultJson, truncatedMap);
    } catch (Exception e) {
      Map<String, Object> fallback = Map.of(TRUNCATED_KEY, true);
      try {
        return new DataResult(mapper.writeValueAsString(fallback), fallback);
      } catch (Exception ex) {
        return new DataResult("{\"" + TRUNCATED_KEY + "\":true}", fallback);
      }
    }
  }
}
