package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Iterator;
import java.util.Map;

/**
 * Replaces malformed UTF-16 surrogate code units in JSON values crossing the HTTP boundary.
 *
 * <p>Sanitizing before persistence keeps the JSONL and relational storage engines consistent;
 * sanitizing before output also protects legacy or externally supplied data.
 */
final class JsonResponseSanitizer {
  private JsonResponseSanitizer() {
  }

  static JsonNode sanitize(ObjectMapper mapper, Object value) {
    return sanitizeNode(mapper.valueToTree(value));
  }

  private static JsonNode sanitizeNode(JsonNode node) {
    if (node instanceof ObjectNode object) {
      Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        object.set(field.getKey(), sanitizeNode(field.getValue()));
      }
      return object;
    }
    if (node instanceof ArrayNode array) {
      for (int i = 0; i < array.size(); i++) {
        array.set(i, sanitizeNode(array.get(i)));
      }
      return array;
    }
    if (node != null && node.isTextual()) {
      return TextNode.valueOf(replaceMalformedSurrogates(node.textValue()));
    }
    return node;
  }

  private static String replaceMalformedSurrogates(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    StringBuilder sanitized = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char current = value.charAt(i);
      if (Character.isHighSurrogate(current)) {
        if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
          sanitized.append(current).append(value.charAt(++i));
        } else {
          sanitized.append('\uFFFD');
        }
      } else if (Character.isLowSurrogate(current)) {
        sanitized.append('\uFFFD');
      } else {
        sanitized.append(current);
      }
    }
    return sanitized.toString();
  }
}
