package io.github.jackbaozz.pocketbase.server.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonResponseSanitizerTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void replacesMalformedSurrogatesWithoutChangingValidUnicode() throws Exception {
    JsonNode result =
        JsonResponseSanitizer.sanitize(
            mapper,
            Map.of(
                "badHigh",
                "before\uD800after",
                "badLow",
                "before\uDC00after",
                "nested",
                List.of("valid \uD83D\uDE80", "bad\uD800")));

    assertEquals("before\uFFFDafter", result.path("badHigh").asText());
    assertEquals("before\uFFFDafter", result.path("badLow").asText());
    assertEquals("valid \uD83D\uDE80", result.path("nested").get(0).asText());
    assertEquals("bad\uFFFD", result.path("nested").get(1).asText());
    assertEquals(result, mapper.readTree(mapper.writeValueAsBytes(result)));
  }
}
