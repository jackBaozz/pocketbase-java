package io.github.jackbaozz.pocketbase.server.internal;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

public final class SecuritySupport {
  private static final Pattern SENSITIVE_QUERY_KEY =
      Pattern.compile(
          "(?i).*?(?:token|password|secret|authorization|api[_-]?key|signature|code|cookie|session).*?");
  private static final Pattern SENSITIVE_HEADER =
      Pattern.compile(
          "(?i)(?:authorization|cookie|token|secret|password|credential|api[_-]?key|signature|session)");

  private SecuritySupport() {
  }

  public static boolean constantTimeEquals(String left, String right) {
    byte[] expected = left == null ? new byte[0] : left.getBytes(StandardCharsets.UTF_8);
    byte[] actual = right == null ? new byte[0] : right.getBytes(StandardCharsets.UTF_8);
    int max = Math.max(expected.length, actual.length);
    int result = expected.length ^ actual.length;
    for (int i = 0; i < max; i++) {
      byte leftByte = i < expected.length ? expected[i] : 0;
      byte rightByte = i < actual.length ? actual[i] : 0;
      result |= leftByte ^ rightByte;
    }
    return result == 0;
  }

  /** Emits only an operation identifier and exception type; exception text may contain secrets. */
  public static String logInternalFailure(String operation, Throwable failure) {
    String id = IdGenerator.prefixed("err_");
    String type = failure == null ? "Unknown" : failure.getClass().getSimpleName();
    String safeOperation = sanitizeLogValue(operation, "unknown");
    System.err.printf(
        Locale.ROOT, "[pocketbase-java] internal failure id=%s operation=%s type=%s%n", id,
        safeOperation, type);
    return id;
  }

  private static String sanitizeLogValue(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    String sanitized = value.replaceAll("[\\p{Cntrl}\\r\\n\\t]", " ").trim();
    if (sanitized.isBlank()) {
      return fallback;
    }
    return sanitized.length() > 160 ? sanitized.substring(0, 160) : sanitized;
  }

  public static boolean isSensitiveQueryKey(String key) {
    return key != null && SENSITIVE_QUERY_KEY.matcher(key.trim()).matches();
  }

  public static boolean isSensitiveHeader(String name) {
    return name != null && SENSITIVE_HEADER.matcher(name.trim()).find();
  }
}
