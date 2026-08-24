package io.github.jackbaozz.pocketbase.server.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class LogPersistenceSanitizerTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void testMessageTruncationUnderLimit() {
    String shortMsg = "GET /api/health 200";
    assertEquals(shortMsg, LogPersistenceSanitizer.sanitizeMessage(shortMsg));
  }

  @Test
  void testMessageTruncationOver8000BytesAscii() {
    String longMsg = "a".repeat(10000);
    String truncated = LogPersistenceSanitizer.sanitizeMessage(longMsg);
    byte[] bytes = truncated.getBytes(StandardCharsets.UTF_8);
    assertEquals(8000, bytes.length);
    assertEquals("a".repeat(8000), truncated);
  }

  @Test
  void testMessageTruncationOver8000BytesMultiByte() {
    // Each Chinese character is 3 bytes in UTF-8
    // 8000 / 3 = 2666 chars (7998 bytes) + 2 remainder
    String multiByteMsg = "中".repeat(3000);
    String truncated = LogPersistenceSanitizer.sanitizeMessage(multiByteMsg);
    byte[] bytes = truncated.getBytes(StandardCharsets.UTF_8);
    assertTrue(bytes.length <= 8000);
    assertEquals(7998, bytes.length);
    assertEquals("中".repeat(2666), truncated);
  }

  @Test
  void testDataUnderThresholdNoMarker() {
    Map<String, Object> data = Map.of("key", "value", "status", 200);
    var res = LogPersistenceSanitizer.sanitizeData(data, 1000, mapper);
    assertFalse(res.map().containsKey(LogPersistenceSanitizer.TRUNCATED_KEY));
    assertEquals(data, res.map());
  }

  @Test
  void testDataOverThresholdAddsMarker() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("k1", "v1");
    data.put("k2", "very long string that will make data exceed the budget threshold");
    data.put("k3", "v3");

    var res = LogPersistenceSanitizer.sanitizeData(data, 30, mapper);
    assertTrue(res.map().containsKey(LogPersistenceSanitizer.TRUNCATED_KEY));
    assertEquals(true, res.map().get(LogPersistenceSanitizer.TRUNCATED_KEY));
    assertTrue(res.map().containsKey("k1"));
  }

  @Test
  void testDataEmptyOrNull() {
    var resNull = LogPersistenceSanitizer.sanitizeData(null, 0, mapper);
    assertEquals("{}", resNull.json());
    assertFalse(resNull.map().containsKey(LogPersistenceSanitizer.TRUNCATED_KEY));

    var resEmpty = LogPersistenceSanitizer.sanitizeData(Map.of(), 0, mapper);
    assertEquals("{}", resEmpty.json());
    assertFalse(resEmpty.map().containsKey(LogPersistenceSanitizer.TRUNCATED_KEY));
  }
}
