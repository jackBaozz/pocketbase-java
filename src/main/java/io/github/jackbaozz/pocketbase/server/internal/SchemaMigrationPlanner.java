package io.github.jackbaozz.pocketbase.server.internal;

import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class SchemaMigrationPlanner {
    private SchemaMigrationPlanner() {
    }

    public static List<Map<String, Object>> plan(CollectionSchema current, CollectionSchema desired, Function<String, String> quoteIdentifier) {
        Function<String, String> quote = quoteIdentifier == null ? SchemaMigrationPlanner::defaultQuote : quoteIdentifier;
        List<Map<String, Object>> operations = new ArrayList<>();
        if (current == null && desired == null) {
            return operations;
        }
        if (current == null) {
            operations.add(operation("createCollection", desired.name, null, false, "CREATE " + collectionStorageKind(desired) + " " + quote.apply(desired.name)));
            return operations;
        }
        if (desired == null) {
            operations.add(operation("dropCollection", current.name, null, true, "DROP " + collectionStorageKind(current) + " " + quote.apply(current.name)));
            return operations;
        }

        String physicalName = current.name;
        if (!Objects.equals(current.name, desired.name)) {
            operations.add(operation("renameCollection", desired.name, null, false,
                    "ALTER TABLE " + quote.apply(current.name) + " RENAME TO " + quote.apply(desired.name)));
            physicalName = desired.name;
        }

        if ("view".equals(desired.type)) {
            if (!Objects.equals(current.type, desired.type)) {
                operations.add(operation("replaceView", desired.name, null, false,
                        "DROP VIEW IF EXISTS " + quote.apply(current.name) + "; CREATE VIEW " + quote.apply(desired.name) + " AS SELECT 1"));
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
            if (previous != null && previous.name != null && !previous.name.isBlank() && !Objects.equals(previous.name, next.name)) {
                operations.add(operation("renameField", desired.name, next.name, false,
                        "ALTER TABLE " + quote.apply(physicalName) + " RENAME COLUMN " + quote.apply(previous.name) + " TO " + quote.apply(next.name)));
                continue;
            }
            if (!oldByName.containsKey(next.name)) {
                operations.add(operation("addField", desired.name, next.name, false,
                        "ALTER TABLE " + quote.apply(physicalName) + " ADD COLUMN " + quote.apply(next.name) + " " + sqlType(next)));
            }
        }

        for (FieldSchema previous : current.fields) {
            if (previous.name == null || previous.name.isBlank()) {
                continue;
            }
            boolean keptById = previous.id != null && newById.containsKey(previous.id);
            if (!keptById && !newByName.containsKey(previous.name)) {
                operations.add(operation("dropField", desired.name, previous.name, true,
                        "ALTER TABLE " + quote.apply(physicalName) + " DROP COLUMN " + quote.apply(previous.name)));
            }
        }

        for (FieldSchema next : desired.fields) {
            if (next.name == null || next.name.isBlank()) {
                continue;
            }
            FieldSchema previous = next.id == null ? oldByName.get(next.name) : oldById.get(next.id);
            if (previous != null && previous.name != null && !previous.name.isBlank() && !Objects.equals(normalize(previous.type), normalize(next.type))) {
                Map<String, Object> op = operation("alterFieldType", desired.name, next.name, true,
                        "ALTER TABLE " + quote.apply(physicalName) + " ALTER COLUMN " + quote.apply(next.name) + " TYPE " + sqlType(next));
                op.put("warning", "SQLite requires table rebuild for column type changes.");
                operations.add(op);
            }
        }

        List<String> oldIndexes = current.indexes == null ? List.of() : current.indexes;
        List<String> newIndexes = desired.indexes == null ? List.of() : desired.indexes;
        for (String oldIndex : oldIndexes) {
            if (!newIndexes.contains(oldIndex)) {
                operations.add(operation("dropIndex", desired.name, null, true, oldIndex));
            }
        }
        for (String newIndex : newIndexes) {
            if (!oldIndexes.contains(newIndex)) {
                operations.add(operation("createIndex", desired.name, null, false, newIndex));
            }
        }

        return operations;
    }

    private static Map<String, Object> operation(String type, String collection, String field, boolean destructive, String sql) {
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
        return (fields == null ? List.<FieldSchema>of() : fields).stream()
                .filter(field -> field.name != null && !field.name.isBlank())
                .collect(Collectors.toMap(field -> field.name, field -> field, (left, ignored) -> left, LinkedHashMap::new));
    }

    private static Map<String, FieldSchema> fieldsById(List<FieldSchema> fields) {
        return (fields == null ? List.<FieldSchema>of() : fields).stream()
                .filter(field -> field.id != null && !field.id.isBlank())
                .collect(Collectors.toMap(field -> field.id, field -> field, (left, ignored) -> left, LinkedHashMap::new));
    }

    private static String sqlType(FieldSchema field) {
        return FieldTypeMapping.sqlType(field == null ? null : field.type).getTypeName();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String collectionStorageKind(CollectionSchema schema) {
        return schema != null && "view".equals(schema.type) ? "VIEW" : "TABLE";
    }

    private static String defaultQuote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
