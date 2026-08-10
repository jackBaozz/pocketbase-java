package io.github.jackbaozz.pocketbase.server.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpRateLimiterTest {

  @Test
  void settingsFingerprintIgnoresMapIterationOrder() {
    Map<String, Object> firstRateLimits =
        ordered(
            "enabled",
            true,
            "rules",
            List.of(
                ordered(
                    "label", "/api/health",
                    "audience", "@guest",
                    "duration", 60,
                    "maxRequests", 1)),
            "excludedIPs",
            List.of());
    Map<String, Object> secondRateLimits =
        ordered(
            "excludedIPs",
            List.of(),
            "rules",
            List.of(
                ordered(
                    "maxRequests", 1,
                    "duration", 60,
                    "audience", "@guest",
                    "label", "/api/health")),
            "enabled",
            true);

    assertEquals(
        HttpRateLimiter.settingsFingerprint(firstRateLimits, Map.of("headers", List.of())),
        HttpRateLimiter.settingsFingerprint(secondRateLimits, Map.of("headers", List.of())));
  }

  @Test
  void settingsFingerprintChangesWhenRateLimitSemanticsChange() {
    Map<String, Object> oneRequest =
        ordered(
            "enabled",
            true,
            "rules",
            List.of(
                ordered(
                    "label", "/api/health",
                    "audience", "@guest",
                    "duration", 60,
                    "maxRequests", 1)));
    Map<String, Object> twoRequests =
        ordered(
            "enabled",
            true,
            "rules",
            List.of(
                ordered(
                    "label", "/api/health",
                    "audience", "@guest",
                    "duration", 60,
                    "maxRequests", 2)));

    assertNotEquals(
        HttpRateLimiter.settingsFingerprint(oneRequest, Map.of()),
        HttpRateLimiter.settingsFingerprint(twoRequests, Map.of()));
  }

  private static Map<String, Object> ordered(Object... entries) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < entries.length; i += 2) {
      result.put(String.valueOf(entries[i]), entries[i + 1]);
    }
    return result;
  }
}
