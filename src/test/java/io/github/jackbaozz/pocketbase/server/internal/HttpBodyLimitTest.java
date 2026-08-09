package io.github.jackbaozz.pocketbase.server.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpBodyLimitTest {
  private static final long DEFAULT_LIMIT = 32L << 20;

  @Test
  void collectionBodyLimitIncludesOfficialFieldAllowances() {
    assertEquals(false, HttpApi.unlimitedBodyRoute("/api/backups/upload", "POST"));
    assertEquals(false, HttpApi.unlimitedBodyRoute("/api/backups", "POST"));
    assertEquals(false, HttpApi.unlimitedBodyRoute("/api/backups/upload", "GET"));

    assertEquals(
        DEFAULT_LIMIT, HttpApi.collectionBodyLimit(Map.of("type", "base", "fields", List.of())));
    assertEquals(
        DEFAULT_LIMIT,
        HttpApi.collectionBodyLimit(
            Map.of(
                "type",
                "view",
                "fields",
                List.of(Map.of("type", "file", "maxSize", 100_000_000L)))));

    long expected = DEFAULT_LIMIT + 2L * (5L << 20) + 2L * (1L << 20) + (1L << 20);
    assertEquals(
        expected,
        HttpApi.collectionBodyLimit(
            Map.of(
                "type",
                "base",
                "fields",
                List.of(
                    Map.of("type", "file", "maxSelect", 2),
                    Map.of("type", "editor", "maxSize", 2L << 20),
                    Map.of("type", "json")))));
  }
}
