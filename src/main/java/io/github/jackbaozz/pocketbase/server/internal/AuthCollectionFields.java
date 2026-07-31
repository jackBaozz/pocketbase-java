package io.github.jackbaozz.pocketbase.server.internal;

import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Normalizes the system fields that PocketBase requires for every auth collection. */
public final class AuthCollectionFields {
  public static final List<String> NAMES =
      List.of("id", "password", "tokenKey", "email", "emailVisibility", "verified");

  private static final Set<String> MANAGED_RECORD_FIELDS = Set.of("id", "tokenKey");

  private AuthCollectionFields() {
  }

  public static void normalize(CollectionSchema collection) {
    if (collection == null || !"auth".equals(collection.type)) {
      return;
    }

    List<FieldSchema> existingFields =
        collection.fields == null ? List.of() : new ArrayList<>(collection.fields);
    Map<String, FieldSchema> existingByName = new LinkedHashMap<>();
    for (FieldSchema field : existingFields) {
      if (field != null && field.name != null && !existingByName.containsKey(field.name)) {
        existingByName.put(field.name, field);
      }
    }

    List<FieldSchema> normalized = new ArrayList<>();
    for (String name : NAMES) {
      FieldSchema field = existingByName.get(name);
      boolean created = field == null;
      if (created) {
        field = defaultField(name);
      }
      enforceSystemDefaults(field, name, created);
      normalized.add(field);
    }
    for (FieldSchema field : existingFields) {
      if (field != null && !NAMES.contains(field.name)) {
        normalized.add(field);
      }
    }
    collection.fields = normalized;
    ensureAuthIndexes(collection);
  }

  public static List<FieldSchema> defaults() {
    CollectionSchema collection = new CollectionSchema();
    collection.type = "auth";
    normalize(collection);
    return collection.fields;
  }

  public static boolean isManagedRecordField(String name) {
    return MANAGED_RECORD_FIELDS.contains(name);
  }

  public static boolean isSystemField(String name) {
    return NAMES.contains(name);
  }

  private static FieldSchema defaultField(String name) {
    return switch (name) {
      case "id" -> new FieldSchema("text3208210256", "id", "text", true, false, false);
      case "password" ->
        new FieldSchema("password901924565", "password", "password", true, false, true);
      case "tokenKey" -> new FieldSchema("text2504183744", "tokenKey", "text", true, true, true);
      case "email" -> new FieldSchema("email3885137012", "email", "email", true, true, false);
      case "emailVisibility" ->
        new FieldSchema("bool1547992806", "emailVisibility", "bool", false, false, false);
      case "verified" -> new FieldSchema("bool256245529", "verified", "bool", false, false, false);
      default -> throw new IllegalArgumentException("Unknown auth system field: " + name);
    };
  }

  private static void enforceSystemDefaults(FieldSchema field, String name, boolean created) {
    field.id = defaultField(name).id;
    field.name = name;
    field.system = true;
    switch (name) {
      case "id" -> {
        field.type = "text";
        field.required = true;
        field.hidden = false;
      }
      case "password" -> {
        field.type = "password";
        field.required = true;
        field.hidden = true;
      }
      case "tokenKey" -> {
        field.type = "text";
        field.required = true;
        field.unique = true;
        field.hidden = true;
      }
      case "email" -> {
        field.type = "email";
        field.unique = true;
        field.hidden = false;
        if (created) {
          field.required = true;
        }
      }
      case "emailVisibility", "verified" -> field.type = "bool";
      default -> throw new IllegalArgumentException("Unknown auth system field: " + name);
    }
  }

  private static void ensureAuthIndexes(CollectionSchema collection) {
    if (collection.indexes == null) {
      collection.indexes = new ArrayList<>();
    }
    ensureUniqueIndex(collection, "tokenKey", false);
    ensureUniqueIndex(collection, "email", true);
  }

  private static void ensureUniqueIndex(
      CollectionSchema collection, String field, boolean nonEmptyOnly) {
    if (collection.indexes.stream()
        .anyMatch(index -> CollectionIndexSupport.isSingleColumnUnique(index, field))) {
      return;
    }
    String suffix =
        collection.id == null || collection.id.isBlank() ? collection.name : collection.id;
    if (suffix == null || suffix.isBlank()) {
      suffix = "auth";
    }
    String indexName = "idx_" + field + "_" + suffix;
    if (indexName.length() > 64) {
      indexName = indexName.substring(0, 64);
    }
    String table = collection.name == null ? "" : collection.name;
    String definition =
        "CREATE UNIQUE INDEX `" + indexName + "` ON `" + table + "` (`" + field + "`)";
    if (nonEmptyOnly) {
      definition += " WHERE `" + field + "` != ''";
    }
    collection.indexes.add(definition);
  }
}
