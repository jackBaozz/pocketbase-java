package io.github.jackbaozz.pocketbase.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class JsonV040CompatibilityTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void testNullEmptyAndMissingFields() throws Exception {
    String json = "{\"nullVal\": null, \"emptyArr\": [], \"emptyObj\": {}}";
    JsonNode node = mapper.readTree(json);

    assertTrue(node.get("nullVal").isNull());
    assertTrue(node.get("emptyArr").isArray());
    assertEquals(0, node.get("emptyArr").size());
    assertTrue(node.get("emptyObj").isObject());
    assertEquals(0, node.get("emptyObj").size());
    assertNull(node.get("nonExistent"));
  }

  @Test
  void testNumericBoundariesAndPrecision() throws Exception {
    // Test safe integer boundary: 2^53 - 1 (9007199254740991)
    long maxSafeInt = 9007199254740991L;
    String json = "{\"neg\": -1, \"zero\": 0, \"safeMax\": 9007199254740991, \"floatVal\": 3.14159, \"sci\": 1.23e4}";
    JsonNode node = mapper.readTree(json);

    assertEquals(-1, node.get("neg").asInt());
    assertEquals(0, node.get("zero").asInt());
    assertEquals(maxSafeInt, node.get("safeMax").asLong());
    assertEquals(3.14159, node.get("floatVal").asDouble(), 0.00001);
    assertEquals(12300, node.get("sci").asInt());
  }

  @Test
  void testUnicodeAndEscapedSequences() throws Exception {
    String unicodeJson = "{\"cjk\": \"中文测试 🌟\", \"escaped\": \"line1\\nline2\\t\\\"quoted\\\"\"}";
    JsonNode node = mapper.readTree(unicodeJson.getBytes(StandardCharsets.UTF_8));

    assertEquals("中文测试 🌟", node.get("cjk").asText());
    assertEquals("line1\nline2\t\"quoted\"", node.get("escaped").asText());
  }

  @Test
  void testSettingsSerializationRoundtrip() throws Exception {
    ObjectNode root = mapper.createObjectNode();
    ObjectNode logs = root.putObject("logs");
    logs.put("maxDataSize", 0);
    logs.put("maxDays", 5);
    logs.put("minLevel", 0);
    logs.put("logIP", true);
    logs.put("logAuthId", false);

    String serialized = mapper.writeValueAsString(root);
    JsonNode deserialized = mapper.readTree(serialized);

    assertEquals(0, deserialized.get("logs").get("maxDataSize").asLong());
    assertEquals(5, deserialized.get("logs").get("maxDays").asLong());
    assertEquals(false, deserialized.get("logs").get("logAuthId").asBoolean());
  }

  @Test
  void testBatchOperationsFormat() throws Exception {
    ArrayNode requests = mapper.createArrayNode();
    ObjectNode req1 = requests.addObject();
    req1.put("method", "POST");
    req1.put("url", "/api/collections/posts/records");
    req1.putObject("body").put("title", "Batch Item");

    String batchJson = mapper.writeValueAsString(requests);
    JsonNode parsed = mapper.readTree(batchJson);

    assertTrue(parsed.isArray());
    assertEquals(1, parsed.size());
    assertEquals("POST", parsed.get(0).get("method").asText());
    assertEquals("Batch Item", parsed.get(0).get("body").get("title").asText());
  }
}
