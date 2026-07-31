package io.github.jackbaozz.pocketbase.server.internal;

import io.github.jackbaozz.pocketbase.server.model.FieldSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** PocketBase-compatible generated collection and field identifiers. */
public final class SchemaIdSupport {
  private SchemaIdSupport() {
  }

  public static String nextCollectionId(String type, String name, Predicate<String> exists) {
    String base = IdGenerator.collectionId(type, name);
    String candidate = base;
    for (int i = 2; i < 1000 && exists.test(candidate); i++) {
      candidate = base + i;
    }
    return candidate;
  }

  public static void assignMissingFieldIds(
      List<FieldSchema> fields, List<FieldSchema> previousFields) {
    Map<String, String> previousByName = new HashMap<>();
    for (FieldSchema field : previousFields == null ? List.<FieldSchema>of() : previousFields) {
      if (field != null && field.name != null && field.id != null && !field.id.isBlank()) {
        previousByName.putIfAbsent(field.name, field.id);
      }
    }

    Set<String> used = new HashSet<>();
    for (FieldSchema field : fields == null ? List.<FieldSchema>of() : fields) {
      if (field != null && field.id != null && !field.id.isBlank()) {
        used.add(field.id);
      }
    }
    for (FieldSchema field : fields == null ? List.<FieldSchema>of() : fields) {
      if (field == null || field.id != null && !field.id.isBlank()) {
        continue;
      }
      String previousId = previousByName.get(field.name);
      if (previousId != null && !used.contains(previousId)) {
        field.id = previousId;
        used.add(previousId);
        continue;
      }
      String base = IdGenerator.fieldId(field.type, field.name);
      String candidate = base;
      for (int i = 2; i < 1000 && used.contains(candidate); i++) {
        candidate = base + i;
      }
      field.id = candidate;
      used.add(candidate);
    }
  }

  public static List<FieldSchema> canonicalizeSubmittedFields(List<FieldSchema> fields) {
    List<FieldSchema> normalized = new ArrayList<>();
    for (FieldSchema field : fields == null ? List.<FieldSchema>of() : fields) {
      if (field == null) {
        normalized.add(null);
        continue;
      }
      boolean hasExplicitId = field.id != null && !field.id.isBlank();
      int replaceAt = -1;
      for (int i = 0; i < normalized.size(); i++) {
        FieldSchema current = normalized.get(i);
        if (current == null) {
          continue;
        }
        if (hasExplicitId && Objects.equals(current.id, field.id)) {
          replaceAt = i;
          break;
        }
        if (!hasExplicitId
            && field.name != null
            && !field.name.isBlank()
            && Objects.equals(current.name, field.name)) {
          field.id = current.id;
          replaceAt = i;
          break;
        }
      }
      if (replaceAt >= 0) {
        normalized.set(replaceAt, field);
      } else {
        normalized.add(field);
      }
    }
    return normalized;
  }

  public static void ensureBaseIdField(List<FieldSchema> fields) {
    FieldSchema idField = null;
    for (FieldSchema field : fields) {
      if (field != null && "id".equals(field.name)) {
        idField = field;
        break;
      }
    }
    if (idField == null) {
      boolean defaultIdReused =
          fields.stream()
              .filter(java.util.Objects::nonNull)
              .anyMatch(field -> "text3208210256".equals(field.id));
      if (defaultIdReused) {
        return;
      }
      idField = new FieldSchema("text3208210256", "id", "text", true, false, false);
      idField.system = true;
      fields.add(0, idField);
      return;
    }
    idField.id = "text3208210256";
    idField.type = "text";
    idField.required = true;
    idField.hidden = false;
    idField.system = true;
  }
}
