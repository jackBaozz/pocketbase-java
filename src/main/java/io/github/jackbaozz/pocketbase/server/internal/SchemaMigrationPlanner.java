package io.github.jackbaozz.pocketbase.server.internal;

import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class SchemaMigrationPlanner {
  private static final Pattern INDEX_NAME =
      Pattern.compile(
          "(?i)\\b(?:unique\\s+)?index\\s+(?:if\\s+not\\s+exists\\s+)?(?:[`\\\"\\[])?([a-zA-Z0-9_$.-]+)");

  private SchemaMigrationPlanner() {
  }

  public static List<Map<String, Object>> plan(
      CollectionSchema current,
      CollectionSchema desired,
      Function<String, String> quoteIdentifier) {
    return plan(current, desired, quoteIdentifier, JooqDatabase.Engine.SQLITE);
  }

  public static List<Map<String, Object>> plan(
      CollectionSchema current,
      CollectionSchema desired,
      Function<String, String> quoteIdentifier,
      JooqDatabase.Engine engine) {
    Function<String, String> quote =
        quoteIdentifier == null ? SchemaMigrationPlanner::defaultQuote : quoteIdentifier;
    JooqDatabase.Engine dialect = engine == null ? JooqDatabase.Engine.SQLITE : engine;
    List<Map<String, Object>> operations = new ArrayList<>();
    if (current == null && desired == null) {
      return operations;
    }
    if (current == null) {
      if (isView(desired)) {
        operations.add(
            operation("createView", desired.name, null, false, createViewSql(desired, quote)));
      } else {
        operations.add(
            operation(
                "createCollection", desired.name, null, false, createTableSql(desired, quote)));
        appendCreateIndexes(operations, desired);
      }
      return operations;
    }
    if (desired == null) {
      operations.add(
          operation(
              "dropCollection",
              current.name,
              null,
              true,
              "DROP " + collectionStorageKind(current) + " " + quote.apply(current.name)));
      return operations;
    }

    if (isView(current) || isView(desired)) {
      return planViewTransition(current, desired, quote);
    }

    String physicalName = current.name;
    if (!Objects.equals(current.name, desired.name)) {
      operations.add(
          operation(
              "renameCollection",
              desired.name,
              null,
              false,
              "ALTER TABLE "
                  + quote.apply(current.name)
                  + " RENAME TO "
                  + quote.apply(desired.name)));
      physicalName = desired.name;
    }

    if ("view".equals(desired.type)) {
      if (!Objects.equals(current.type, desired.type)) {
        operations.add(
            operation(
                "replaceView",
                desired.name,
                null,
                false,
                "DROP VIEW IF EXISTS "
                    + quote.apply(current.name)
                    + "; CREATE VIEW "
                    + quote.apply(desired.name)
                    + " AS SELECT 1"));
      }
      return operations;
    }

    Map<String, FieldSchema> oldByName = fieldsByName(current.fields);
    Map<String, FieldSchema> newByName = fieldsByName(desired.fields);
    Map<String, FieldSchema> oldById = fieldsById(current.fields);
    Map<String, FieldSchema> newById = fieldsById(desired.fields);

    for (FieldSchema next : desired.fields) {
      if (next.name == null || next.name.isBlank()) {
        continue;
      }
      FieldSchema previous = next.id == null ? null : oldById.get(next.id);
      if (previous != null
          && previous.name != null
          && !previous.name.isBlank()
          && !Objects.equals(previous.name, next.name)) {
        operations.add(
            operation(
                "renameField",
                desired.name,
                next.name,
                false,
                "ALTER TABLE "
                    + quote.apply(physicalName)
                    + " RENAME COLUMN "
                    + quote.apply(previous.name)
                    + " TO "
                    + quote.apply(next.name)));
      }
      if (previous == null && !oldByName.containsKey(next.name)) {
        operations.add(
            operation(
                "addField",
                desired.name,
                next.name,
                false,
                "ALTER TABLE "
                    + quote.apply(physicalName)
                    + " ADD COLUMN "
                    + quote.apply(next.name)
                    + " "
                    + sqlType(next)));
      }
    }

    for (FieldSchema previous : current.fields) {
      if (previous.name == null || previous.name.isBlank()) {
        continue;
      }
      boolean keptById = previous.id != null && newById.containsKey(previous.id);
      if (!keptById && !newByName.containsKey(previous.name)) {
        operations.add(
            operation(
                "dropField",
                desired.name,
                previous.name,
                true,
                "ALTER TABLE "
                    + quote.apply(physicalName)
                    + " DROP COLUMN "
                    + quote.apply(previous.name)));
      }
    }

    for (FieldSchema next : desired.fields) {
      if (next.name == null || next.name.isBlank()) {
        continue;
      }
      FieldSchema previous = next.id == null ? oldByName.get(next.name) : oldById.get(next.id);
      if (previous != null
          && previous.name != null
          && !previous.name.isBlank()
          && !Objects.equals(normalize(previous.type), normalize(next.type))) {
        Map<String, Object> op =
            operation(
                "alterFieldType",
                desired.name,
                next.name,
                true,
                alterFieldTypeSql(dialect, physicalName, next, quote));
        if (dialect == JooqDatabase.Engine.SQLITE) {
          op.put(
              "warning",
              "SQLite uses dynamic typing; the relational executor updates metadata without rewriting existing values.");
        }
        operations.add(op);
      }
    }

    List<String> oldIndexes = current.indexes == null ? List.of() : current.indexes;
    List<String> newIndexes = desired.indexes == null ? List.of() : desired.indexes;
    for (String oldIndex : oldIndexes) {
      if (!newIndexes.contains(oldIndex)) {
        operations.add(
            operation(
                "dropIndex",
                desired.name,
                null,
                true,
                dropIndexSql(dialect, physicalName, oldIndex, quote)));
      }
    }
    for (String newIndex : newIndexes) {
      if (!oldIndexes.contains(newIndex)) {
        operations.add(operation("createIndex", desired.name, null, false, newIndex));
      }
    }

    return operations;
  }

  private static List<Map<String, Object>> planViewTransition(
      CollectionSchema current, CollectionSchema desired, Function<String, String> quote) {
    List<Map<String, Object>> operations = new ArrayList<>();
    if (isView(current) && isView(desired)) {
      if (!Objects.equals(current.name, desired.name)
          || !Objects.equals(
              normalizeQuery(current.viewQuery), normalizeQuery(desired.viewQuery))) {
        String sql =
            "DROP VIEW IF EXISTS "
                + quote.apply(current.name)
                + "; "
                + createViewSql(desired, quote);
        operations.add(operation("replaceView", desired.name, null, false, sql));
      }
      return operations;
    }
    if (isView(desired)) {
      String sql = "DROP TABLE " + quote.apply(current.name) + "; " + createViewSql(desired, quote);
      operations.add(operation("replaceCollectionWithView", desired.name, null, true, sql));
      return operations;
    }

    String sql = "DROP VIEW " + quote.apply(current.name) + "; " + createTableSql(desired, quote);
    operations.add(operation("replaceViewWithCollection", desired.name, null, true, sql));
    appendCreateIndexes(operations, desired);
    return operations;
  }

  private static void appendCreateIndexes(
      List<Map<String, Object>> operations, CollectionSchema schema) {
    if (schema.indexes == null) {
      return;
    }
    for (String index : schema.indexes) {
      operations.add(operation("createIndex", schema.name, null, false, index));
    }
  }

  private static String createViewSql(CollectionSchema schema, Function<String, String> quote) {
    return "CREATE VIEW " + quote.apply(schema.name) + " AS " + normalizeQuery(schema.viewQuery);
  }

  private static String createTableSql(CollectionSchema schema, Function<String, String> quote) {
    List<String> columns = new ArrayList<>();
    columns.add(quote.apply("id") + " VARCHAR(255) NOT NULL PRIMARY KEY");
    columns.add(quote.apply("created") + " VARCHAR(64)");
    columns.add(quote.apply("updated") + " VARCHAR(64)");
    for (FieldSchema field : schema.fields == null ? List.<FieldSchema>of() : schema.fields) {
      if (field.name == null
          || field.name.isBlank()
          || "id".equals(field.name)
          || "created".equals(field.name)
          || "updated".equals(field.name)) {
        continue;
      }
      columns.add(quote.apply(field.name) + " " + sqlType(field));
    }
    return "CREATE TABLE " + quote.apply(schema.name) + " (" + String.join(", ", columns) + ")";
  }

  private static String alterFieldTypeSql(
      JooqDatabase.Engine engine, String table, FieldSchema field, Function<String, String> quote) {
    String prefix = "ALTER TABLE " + quote.apply(table) + " ";
    if (engine == JooqDatabase.Engine.MYSQL) {
      return prefix + "MODIFY COLUMN " + quote.apply(field.name) + " " + sqlType(field);
    }
    if (engine == JooqDatabase.Engine.SQLITE) {
      return "-- SQLite metadata-only type change for "
          + quote.apply(table)
          + "."
          + quote.apply(field.name)
          + " to "
          + sqlType(field);
    }
    return prefix + "ALTER COLUMN " + quote.apply(field.name) + " TYPE " + sqlType(field);
  }

  private static String dropIndexSql(
      JooqDatabase.Engine engine,
      String table,
      String createIndexSql,
      Function<String, String> quote) {
    Matcher matcher = INDEX_NAME.matcher(createIndexSql == null ? "" : createIndexSql);
    String indexName = matcher.find() ? matcher.group(1) : createIndexSql;
    if (indexName == null || indexName.isBlank()) {
      return "";
    }
    String sql = "DROP INDEX " + quote.apply(indexName);
    return engine == JooqDatabase.Engine.MYSQL ? sql + " ON " + quote.apply(table) : sql;
  }

  private static Map<String, Object> operation(
      String type, String collection, String field, boolean destructive, String sql) {
    Map<String, Object> op = new LinkedHashMap<>();
    op.put("type", type);
    op.put("collection", collection);
    if (field != null) {
      op.put("field", field);
    }
    op.put("destructive", destructive);
    op.put("sql", sql);
    return op;
  }

  private static Map<String, FieldSchema> fieldsByName(List<FieldSchema> fields) {
    return (fields == null ? List.<FieldSchema>of() : fields)
        .stream()
        .filter(field -> field.name != null && !field.name.isBlank())
        .collect(
            Collectors.toMap(
                field -> field.name,
                field -> field,
                (left, ignored) -> left,
                LinkedHashMap::new));
  }

  private static Map<String, FieldSchema> fieldsById(List<FieldSchema> fields) {
    return (fields == null ? List.<FieldSchema>of() : fields)
        .stream()
        .filter(field -> field.id != null && !field.id.isBlank())
        .collect(
            Collectors.toMap(
                field -> field.id,
                field -> field,
                (left, ignored) -> left,
                LinkedHashMap::new));
  }

  private static String sqlType(FieldSchema field) {
    return FieldTypeMapping.sqlTypeForField(field).getTypeName();
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
  }

  private static String normalizeQuery(String value) {
    return value == null ? "" : value.trim();
  }

  private static boolean isView(CollectionSchema schema) {
    return schema != null && "view".equals(normalize(schema.type));
  }

  private static String collectionStorageKind(CollectionSchema schema) {
    return schema != null && "view".equals(schema.type) ? "VIEW" : "TABLE";
  }

  private static String defaultQuote(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
