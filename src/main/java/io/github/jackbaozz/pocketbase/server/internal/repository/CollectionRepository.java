package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.internal.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.ApiErrors;
import io.github.jackbaozz.pocketbase.server.internal.FieldTypeMapping;
import io.github.jackbaozz.pocketbase.server.internal.IdGenerator;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;
import io.github.jackbaozz.pocketbase.server.internal.OAuth2ProviderManager;
import io.github.jackbaozz.pocketbase.server.internal.RecordProcessor;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class CollectionRepository extends BaseRepository {
    private static final int MAX_VIEW_QUERY_LENGTH = 5000;
    private static final int MAX_VIEW_ROWS = 10;
    private static final List<String> SQL_WRITE_PREFIXES = List.of(
            "insert",
            "update",
            "delete",
            "create",
            "drop",
            "alter",
            "replace",
            "truncate"
    );

    public CollectionRepository(JooqDatabase database, ObjectMapper mapper) {
        super(database, mapper);
    }

    private Condition collectionCondition(String collection) {
        return qfs("name").eq(collection).or(qfs("id").eq(collection));
    }

    public Map<String, Object> listCollections(Map<String, String> query) {
        try {
            Map<String, String> safeQuery = query == null ? Map.of() : query;
            int page = parsePositive(safeQuery.get("page"), 1);
            int perPage = parsePositive(safeQuery.get("perPage"), 100);
            List<Map<String, Object>> items = database.dsl()
                    .select(
                            qfs("id"), qfs("name"), qfs("type"), qfs("schema"),
                            qfi("system"), qfs("createRule"), qfs("listRule"),
                            qfs("viewRule"), qfs("updateRule"), qfs("deleteRule"), qfs("options")
                    )
                    .from(qt("_collections"))
                    .fetch()
                    .map(this::collectionMap);
            items = items.stream()
                    .filter(collection -> matchesCollectionFilter(collection, safeQuery.get("filter")))
                    .collect(Collectors.toCollection(ArrayList::new));
            sortCollections(items, safeQuery.get("sort"));
            int total = items.size();
            int from = Math.min(total, (page - 1) * perPage);
            int to = Math.min(total, from + perPage);
            List<Map<String, Object>> pageItems = items.subList(from, to).stream()
                    .map(item -> RecordProcessor.selectFields(item, safeQuery.get("fields")))
                    .collect(Collectors.toCollection(ArrayList::new));
            int totalPages = perPage <= 0 ? 0 : (int) Math.ceil((double) total / perPage);
            return Map.of("items", pageItems, "page", page, "perPage", perPage, "totalItems", total, "totalPages", totalPages);
        } catch (DataAccessException e) {
            throw new RuntimeException("failed to list collections", e);
        }
    }

    public CollectionSchema createCollection(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ApiException(400, "Collection payload must be a JSON object.",
                    ApiErrors.invalidField("body", "Request body must be a JSON object."));
        }
        CollectionSchema colSchema;
        Map<String, Object> rawOptions = Map.of();
        try {
            colSchema = mapper.treeToValue(body, CollectionSchema.class);
            if (body.has("options")) {
                rawOptions = mapper.convertValue(body.get("options"), new TypeReference<Map<String, Object>>() {});
            }
        } catch (IOException e) {
            throw new ApiException(400, "Collection payload must be a JSON object.",
                    ApiErrors.invalidField("body", "Request body must be a JSON object."));
        }

        if (colSchema.id == null || colSchema.id.isBlank()) {
            colSchema.id = "pbc_" + IdGenerator.id();
        }
        normalizeCollectionSchema(colSchema, rawOptions);
        validateSchemaIdentifiers(colSchema, "Failed to create collection.");
        if (!"base".equals(colSchema.type) && !"auth".equals(colSchema.type) && !"view".equals(colSchema.type)) {
            throw new ApiException(400, "Unsupported collection type.", Map.of("type", Map.of("code", "validation_invalid_value", "message", "Supported types are base, auth and view.")));
        }
        rawOptions = collectionOptions(colSchema, rawOptions);

        Connection conn = null;
        try {
            conn = database.connection();

            int count = database.dsl(conn)
                    .selectCount()
                    .from(qt("_collections"))
                    .where(collectionCondition(colSchema.name))
                    .fetchOne(0, int.class);
            if (count > 0) {
                throw new ApiException(400, "Failed to create collection.", ApiErrors.notUniqueField("name"));
            }

            database.dsl(conn)
                    .insertInto(qt("_collections"))
                    .columns(
                            qfs("id"), qfs("name"), qfs("type"), qfs("schema"),
                            qfi("system"), qfs("createRule"), qfs("listRule"),
                            qfs("viewRule"), qfs("updateRule"), qfs("deleteRule"), qfs("options")
                    )
                    .values(
                            colSchema.id, colSchema.name, colSchema.type,
                            mapper.writeValueAsString(colSchema.fields),
                            colSchema.system ? 1 : 0,
                            colSchema.createRule, colSchema.listRule, colSchema.viewRule,
                            colSchema.updateRule, colSchema.deleteRule,
                            mapper.writeValueAsString(rawOptions)
                    )
                    .execute();

            if ("view".equals(colSchema.type)) {
                String viewQuery = rawOptions.containsKey("query") ? rawOptions.get("query").toString() : "SELECT 1";
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE VIEW " + qi(colSchema.name) + " AS " + viewQuery);
                }
            } else {
                var createTable = database.dsl(conn)
                        .createTable(DSL.name(colSchema.name))
                        .column(DSL.name("id"), SQLDataType.VARCHAR(255).nullable(false))
                        .column(DSL.name("created"), SQLDataType.VARCHAR(64))
                        .column(DSL.name("updated"), SQLDataType.VARCHAR(64));
                for (FieldSchema field : colSchema.fields) {
                    createTable = createTable.column(DSL.name(field.name), FieldTypeMapping.sqlType(field.type));
                }
                if (isAuthUserCollection(colSchema) && !hasField(colSchema, "tokenKey")) {
                    createTable = createTable.column(DSL.name("tokenKey"), SQLDataType.VARCHAR(255));
                }
                createTable.constraints(DSL.constraint(DSL.name("pk_" + colSchema.name)).primaryKey(DSL.name("id")))
                        .execute();
                rebuildIndexes(conn, colSchema, colSchema.name);
            }
        } catch (SQLException | IOException | DataAccessException e) {
            handleSqlConstraintException(e);
            throw new ApiException(400, "Failed to create collection: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    database.closeIfStandalone(conn);
                } catch (SQLException ignored) {
                }
            }
        }

        return colSchema;
    }

    public Map<String, Object> getCollection(String collection, Map<String, String> query) {
        CollectionSchema schema = getCollectionSchema(collection);
        Map<String, String> safeQuery = query == null ? Map.of() : query;
        return RecordProcessor.selectFields(collectionMap(schema), safeQuery.get("fields"));
    }

    public CollectionSchema updateCollection(String collection, JsonNode body) {
        requireCollectionExists(collection);
        if (body == null || !body.isObject()) {
            throw new ApiException(400, "Collection payload must be a JSON object.",
                    ApiErrors.invalidField("body", "Request body must be a JSON object."));
        }
        CollectionSchema newSchema;
        Map<String, Object> rawOptions = Map.of();
        try {
            newSchema = mapper.treeToValue(body, CollectionSchema.class);
            if (body.has("options")) {
                rawOptions = mapper.convertValue(body.get("options"), new TypeReference<Map<String, Object>>() {});
            }
        } catch (IOException e) {
            throw new ApiException(400, "Collection payload must be a JSON object.",
                    ApiErrors.invalidField("body", "Request body must be a JSON object."));
        }
        normalizeCollectionSchema(newSchema, rawOptions);
        validateSchemaIdentifiers(newSchema, "Failed to update collection.");
        rawOptions = collectionOptions(newSchema, rawOptions);

        Connection conn = null;
        try {
            conn = database.connection();
            String oldSchemaJson = null;
            String physicalName = null;

            Record rs = database.dsl(conn)
                    .select(qfs("name"), qfs("schema"))
                    .from(qt("_collections"))
                    .where(collectionCondition(collection))
                    .fetchOne();
            if (rs != null) {
                physicalName = rs.get(qfs("name"));
                oldSchemaJson = rs.get(qfs("schema"));
            }

            if (physicalName == null) throw new ApiException(404, "Collection not found.");

            if ("view".equals(newSchema.type)) {
                String viewQuery = rawOptions.containsKey("query") ? rawOptions.get("query").toString() : "SELECT 1";
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("DROP VIEW IF EXISTS " + qi(physicalName));
                    stmt.execute("CREATE VIEW " + qi(newSchema.name) + " AS " + viewQuery);
                }
            } else {
                List<FieldSchema> oldFields = new ArrayList<>();
                if (oldSchemaJson != null && !oldSchemaJson.isBlank()) {
                    oldFields = mapper.readValue(oldSchemaJson, new TypeReference<List<FieldSchema>>() {});
                }

                DSLContext dsl = database.dsl(conn);
                if (!physicalName.equals(newSchema.name)) {
                    dsl.alterTable(DSL.name(physicalName))
                            .renameTo(DSL.name(newSchema.name))
                            .execute();
                    physicalName = newSchema.name;
                }

                List<String> oldNames = oldFields.stream().map(f -> f.name).toList();
                List<String> newNames = newSchema.fields.stream().map(f -> f.name).toList();

                for (FieldSchema nf : newSchema.fields) {
                    if (!oldNames.contains(nf.name)) {
                        dsl.alterTable(DSL.name(physicalName))
                                .add(DSL.name(nf.name), FieldTypeMapping.sqlType(nf.type))
                                .execute();
                    }
                }
                for (FieldSchema of : oldFields) {
                    if (!newNames.contains(of.name)) {
                        dsl.alterTable(DSL.name(physicalName))
                                .drop(DSL.name(of.name))
                                .execute();
                    }
                }

                rebuildIndexes(conn, newSchema, physicalName);
            }

            database.dsl(conn)
                    .update(qt("_collections"))
                    .set(qfs("name"), newSchema.name)
                    .set(qfs("type"), newSchema.type)
                    .set(qfs("schema"), mapper.writeValueAsString(newSchema.fields))
                    .set(qfi("system"), newSchema.system ? 1 : 0)
                    .set(qfs("createRule"), newSchema.createRule)
                    .set(qfs("listRule"), newSchema.listRule)
                    .set(qfs("viewRule"), newSchema.viewRule)
                    .set(qfs("updateRule"), newSchema.updateRule)
                    .set(qfs("deleteRule"), newSchema.deleteRule)
                    .set(qfs("options"), mapper.writeValueAsString(rawOptions))
                    .where(collectionCondition(collection))
                    .execute();

        } catch (SQLException | IOException | DataAccessException e) {
            handleSqlConstraintException(e);
            throw new ApiException(400, "Failed to update collection: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    database.closeIfStandalone(conn);
                } catch (SQLException ignored) {
                }
            }
        }

        return newSchema;
    }

    public void deleteCollection(String collection) {
        Connection conn = null;
        try {
            conn = database.connection();
            String physicalName = null;
            String type = null;
            boolean system = false;

            Record rs = database.dsl(conn)
                    .select(qfs("name"), qfs("type"), qfi("system"))
                    .from(qt("_collections"))
                    .where(collectionCondition(collection))
                    .fetchOne();
            if (rs != null) {
                physicalName = rs.get(qfs("name"));
                type = rs.get(qfs("type"));
                system = Objects.equals(rs.get(qfi("system")), 1);
            }

            if (physicalName == null) {
                throw new ApiException(404, "Collection not found.");
            }
            if (system) {
                throw new ApiException(400, "System collections cannot be deleted.");
            }

            database.dsl(conn)
                    .deleteFrom(qt("_collections"))
                    .where(collectionCondition(collection))
                    .execute();

            if ("view".equals(type)) {
                database.dsl(conn).dropViewIfExists(DSL.name(physicalName)).execute();
            } else {
                database.dsl(conn).dropTableIfExists(DSL.name(physicalName)).execute();
            }

        } catch (SQLException | DataAccessException e) {
            throw new ApiException(400, "Failed to delete collection: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    database.closeIfStandalone(conn);
                } catch (SQLException ignored) {
                }
            }
        }
    }

    public void truncateCollection(String collection) {
        requireCollectionExists(collection);
        CollectionSchema schema = getCollectionSchema(collection);
        if (schema.system) {
            throw new ApiException(400, "System collections cannot be truncated.");
        }
        try {
            database.dsl()
                    .deleteFrom(qt(schema.name))
                    .execute();
        } catch (DataAccessException e) {
            throw new ApiException(400, "Failed to truncate collection: " + e.getMessage());
        }
    }

    public Map<String, Object> importCollections(JsonNode body, boolean dryRun) {
        if (body == null || !body.isObject()) {
            throw new ApiException(400, "Failed to import collections.",
                    ApiErrors.invalidField("collections", "Collections import payload must be a JSON object."));
        }
        JsonNode collectionsNode = body.get("collections");
        if (collectionsNode == null || !collectionsNode.isArray() || collectionsNode.isEmpty()) {
            throw new ApiException(400, "Failed to import collections.", ApiErrors.requiredField("collections"));
        }

        List<CollectionSchema> newOrUpdated = new ArrayList<>();
        for (JsonNode item : collectionsNode) {
            try {
                CollectionSchema collection = mapper.treeToValue(item, CollectionSchema.class);
                normalizeCollectionSchema(collection, item.has("options")
                        ? mapper.convertValue(item.get("options"), new TypeReference<Map<String, Object>>() {})
                        : Map.of());
                newOrUpdated.add(collection);
            } catch (IOException e) {
                throw new ApiException(400, "Failed to import collections.",
                        ApiErrors.invalidField("collections", "Invalid collection payload."));
            }
        }
        boolean deleteMissing = body.path("deleteMissing").asBoolean(false);
        List<CollectionSchema> existing = database.dsl()
                .select(
                        qfs("id"), qfs("name"), qfs("schema"), qfs("type"),
                        qfi("system"), qfs("createRule"), qfs("listRule"),
                        qfs("viewRule"), qfs("updateRule"), qfs("deleteRule"), qfs("options")
                )
                .from(qt("_collections"))
                .fetch()
                .map(record -> getCollectionSchema(record.get(qfs("id"))));
        Set<String> desiredIds = newOrUpdated.stream()
                .map(item -> item.id == null ? "" : item.id)
                .filter(id -> !id.isBlank())
                .collect(Collectors.toCollection(HashSet::new));
        Set<String> desiredNames = newOrUpdated.stream()
                .map(item -> item.name == null ? "" : item.name)
                .filter(name -> !name.isBlank())
                .collect(Collectors.toCollection(HashSet::new));
        List<String> deleted = deleteMissing
                ? existing.stream()
                .filter(item -> !item.system)
                .filter(item -> !desiredIds.contains(item.id))
                .filter(item -> !desiredNames.contains(item.name))
                .map(item -> item.name)
                .collect(Collectors.toCollection(ArrayList::new))
                : new ArrayList<>();

        if (dryRun) {
            return Map.of("collections", newOrUpdated, "deletedCollections", deleted);
        }

        return database.transactional(() -> {
            for (CollectionSchema c : newOrUpdated) {
                try {
                    getCollection(c.id != null ? c.id : c.name, Map.of());
                    updateCollection(c.id != null ? c.id : c.name, mapper.valueToTree(c));
                } catch (ApiException e) {
                    createCollection(mapper.valueToTree(c));
                }
            }
            if (deleteMissing) {
                for (CollectionSchema item : existing) {
                    if (item.system || desiredIds.contains(item.id) || desiredNames.contains(item.name)) {
                        continue;
                    }
                    deleteCollection(item.id != null && !item.id.isBlank() ? item.id : item.name);
                }
            }
            return Map.of("collections", newOrUpdated, "deletedCollections", deleted);
        });
    }

    public Map<String, Object> collectionScaffolds() {
        Map<String, Object> base = mapper.convertValue(scaffoldCollection("base"), new TypeReference<Map<String, Object>>() {});
        Map<String, Object> auth = mapper.convertValue(scaffoldCollection("auth"), new TypeReference<Map<String, Object>>() {});
        Map<String, Object> view = mapper.convertValue(scaffoldCollection("view"), new TypeReference<Map<String, Object>>() {});
        view.put("viewQuery", "");
        return orderedMap(
                "base", base,
                "auth", auth,
                "view", view
        );
    }

    public Map<String, Object> dryRunView(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ApiException(400, "An error occurred while loading the submitted data.",
                    ApiErrors.invalidField("body", "Request body must be a JSON object."));
        }
        JsonNode queryNode = body.get("query");
        if (queryNode == null || queryNode.isNull() || queryNode.asText().isBlank()) {
            throw new ApiException(
                    400,
                    "An error occurred while validating the submitted data.",
                    ApiErrors.requiredField("query")
            );
        }
        String query = queryNode.asText();
        if (query.length() > MAX_VIEW_QUERY_LENGTH) {
            throw new ApiException(
                    400,
                    "An error occurred while validating the submitted data.",
                    ApiErrors.invalidField("query", "query must be at most " + MAX_VIEW_QUERY_LENGTH + " characters.")
            );
        }

        try {
            List<String> statements = splitSqlStatements(query).stream()
                    .map(String::trim)
                    .filter(statement -> !statement.isBlank())
                    .collect(Collectors.toCollection(ArrayList::new));
            if (statements.isEmpty()) {
                throw new IllegalArgumentException("empty query");
            }
            if (statements.stream().anyMatch(this::isWriteStatement)) {
                throw new IllegalArgumentException("write statements are not allowed");
            }

            List<Map<String, Object>> columns = List.of();
            List<List<Object>> rows = List.of();
            for (String statement : statements) {
                var result = database.dsl().fetch(statement);
                columns = new ArrayList<>();
                for (var field : result.fields()) {
                    Map<String, Object> column = new LinkedHashMap<>();
                    column.put("name", field.getName());
                    column.put("type", "");
                    column.put("nullable", true);
                    columns.add(column);
                }
                rows = new ArrayList<>();
                int limit = Math.min(MAX_VIEW_ROWS, result.size());
                for (int rowIndex = 0; rowIndex < limit; rowIndex++) {
                    org.jooq.Record rowRecord = result.get(rowIndex);
                    List<Object> row = new ArrayList<>();
                    for (int fieldIndex = 0; fieldIndex < result.fields().length; fieldIndex++) {
                        row.add(rowRecord.get(fieldIndex));
                    }
                    rows.add(row);
                }
            }

            return orderedMap(
                    "columns", columns,
                    "rows", rows
            );
        } catch (RuntimeException e) {
            String message = "Invalid view query. Raw error:\n"
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            throw new ApiException(
                    400,
                    message,
                    ApiErrors.invalidField("query", message)
            );
        }
    }

    public List<Map<String, Object>> oauth2ProviderMetadata() {
        return OAuth2ProviderManager.providers().stream()
                .map(provider -> orderedMap(
                        "name", provider.name(),
                        "displayName", provider.displayName(),
                        "logo", provider.logo()
                ))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public void requireCollectionExists(String collection) {
        try {
            int count = database.dsl()
                    .selectCount()
                    .from(qt("_collections"))
                    .where(collectionCondition(collection))
                    .fetchOne(0, int.class);
            if (count == 0) {
                throw new ApiException(404, "Collection not found.");
            }
        } catch (DataAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public CollectionSchema getCollectionSchema(String nameOrId) {
        try {
            Record rs = database.dsl()
                    .select(
                            qfs("id"), qfs("name"), qfs("schema"), qfs("type"),
                            qfi("system"), qfs("createRule"), qfs("listRule"),
                            qfs("viewRule"), qfs("updateRule"), qfs("deleteRule"), qfs("options")
                    )
                    .from(qt("_collections"))
                    .where(collectionCondition(nameOrId))
                    .fetchOne();
            if (rs != null) {
                CollectionSchema col = new CollectionSchema();
                col.id = rs.get(qfs("id"));
                col.name = rs.get(qfs("name"));
                col.type = rs.get(qfs("type"));
                col.system = rs.get(qfi("system")) == 1;
                col.createRule = rs.get(qfs("createRule"));
                col.listRule = rs.get(qfs("listRule"));
                col.viewRule = rs.get(qfs("viewRule"));
                col.updateRule = rs.get(qfs("updateRule"));
                col.deleteRule = rs.get(qfs("deleteRule"));
                String schemaJson = rs.get(qfs("schema"));
                if (schemaJson != null && !schemaJson.isBlank()) {
                    col.fields = mapper.readValue(schemaJson, new TypeReference<List<FieldSchema>>() {});
                }
                String optionsJson = rs.get(qfs("options"));
                Map<String, Object> rawOptions = Map.of();
                if (optionsJson != null && !optionsJson.isBlank()) {
                    rawOptions = mapper.readValue(optionsJson, new TypeReference<Map<String, Object>>() {});
                }
                normalizeCollectionSchema(col, rawOptions);
                if (col.system && "_superusers".equals(col.name) && (col.fields == null || col.fields.isEmpty())) {
                    col.fields = new ArrayList<>();
                    col.fields.add(new FieldSchema("field_email", "email", "email", true, true, false));
                    col.fields.add(new FieldSchema("field_password", "password", "password", true, false, true));
                    col.fields.add(new FieldSchema("field_verified", "verified", "bool", false, false, false));
                }
                return col;
            }
        } catch (DataAccessException | IOException e) {
            throw new RuntimeException(e);
        }
        throw new ApiException(404, "Collection not found.");
    }

    private void validateSchemaIdentifiers(CollectionSchema schema, String message) {
        validateCollectionIdentifier(schema.name, "name", message);
        if (schema.fields != null) {
            for (FieldSchema field : schema.fields) {
                validateCollectionIdentifier(field.name, field.name == null || field.name.isBlank() ? "schema" : field.name, message);
            }
        }
    }

    private void validateCollectionIdentifier(String value, String field, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(400, message, ApiErrors.requiredField(field));
        }
        if (!IDENTIFIER_PATTERN.matcher(value).matches()) {
            throw new ApiException(400, message, ApiErrors.fieldError(
                    field,
                    "validation_invalid_format",
                    "Use letters, numbers and underscore."
            ));
        }
    }

    private void rebuildIndexes(Connection conn, CollectionSchema schema, String physicalName) throws SQLException {
        if (schema.indexes != null) {
            try (Statement stmt = conn.createStatement()) {
                for (String q : schema.indexes) {
                    if (q.toUpperCase().startsWith("CREATE ")) {
                        try {
                            stmt.execute(q);
                        } catch (SQLException ignored) {
                        }
                    } else if (q.toUpperCase().startsWith("DROP ")) {
                        try {
                            stmt.execute(q);
                        } catch (SQLException ignored) {
                        }
                    }
                }
            }
        }
    }

    private Map<String, Object> collectionMap(Record rs) {
        Map<String, Object> col = new LinkedHashMap<>();
        col.put("id", rs.get(qfs("id")));
        col.put("name", rs.get(qfs("name")));
        col.put("type", rs.get(qfs("type")));
        String schemaJson = rs.get(qfs("schema"));
        if (schemaJson != null) {
            try {
                List<?> fields = mapper.readValue(schemaJson, List.class);
                col.put("fields", fields);
                col.put("schema", fields);
            } catch (IOException e) {
                col.put("fields", List.of());
                col.put("schema", List.of());
            }
        } else {
            col.put("fields", List.of());
            col.put("schema", List.of());
        }
        col.put("system", rs.get(qfi("system")) == 1);
        col.put("createRule", rs.get(qfs("createRule")));
        col.put("listRule", rs.get(qfs("listRule")));
        col.put("viewRule", rs.get(qfs("viewRule")));
        col.put("updateRule", rs.get(qfs("updateRule")));
        col.put("deleteRule", rs.get(qfs("deleteRule")));
        String optsJson = rs.get(qfs("options"));
        Map<?, ?> options;
        if (optsJson != null && !optsJson.isBlank()) {
            try {
                options = mapper.readValue(optsJson, Map.class);
            } catch (IOException e) {
                options = Map.of();
            }
        } else {
            options = Map.of();
        }
        col.put("options", options);
        if ("auth".equals(col.get("type"))) {
            copyOption(col, options, "passwordAuth");
            copyOption(col, options, "otp");
            copyOption(col, options, "mfa");
            copyOption(col, options, "oauth2");
            copyOption(col, options, "authToken");
            copyOption(col, options, "passwordResetToken");
            copyOption(col, options, "verificationToken");
            copyOption(col, options, "emailChangeToken");
            copyOption(col, options, "fileToken");
        }
        return col;
    }

    private void copyOption(Map<String, Object> collection, Map<?, ?> options, String key) {
        if (options.containsKey(key)) {
            collection.put(key, options.get(key));
        }
    }

    private Map<String, Object> collectionMap(CollectionSchema collection) {
        return mapper.convertValue(collection, new TypeReference<Map<String, Object>>() {});
    }

    private boolean matchesCollectionFilter(Map<String, Object> collection, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        return io.github.jackbaozz.pocketbase.server.internal.RuleEvaluator.matches(
                filter,
                io.github.jackbaozz.pocketbase.server.internal.RuleEvaluator.context(collection, null, Map.of(), "GET", null)
        );
    }

    private void sortCollections(List<Map<String, Object>> items, String sort) {
        if (sort == null || sort.isBlank()) {
            return;
        }
        List<String> parts = List.of(sort.split(","));
        items.sort((left, right) -> {
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                boolean desc = trimmed.startsWith("-");
                String key = desc ? trimmed.substring(1) : trimmed;
                String leftValue = String.valueOf(left.getOrDefault(key, ""));
                String rightValue = String.valueOf(right.getOrDefault(key, ""));
                int compare = leftValue.compareTo(rightValue);
                if (compare != 0) {
                    return desc ? -compare : compare;
                }
            }
            return 0;
        });
    }

    private int parsePositive(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private void normalizeCollectionSchema(CollectionSchema schema, Map<String, Object> rawOptions) {
        if (schema.type == null || schema.type.isBlank()) {
            schema.type = "base";
        }
        if (schema.fields == null) {
            schema.fields = new ArrayList<>();
        }
        if (rawOptions != null) {
            if (rawOptions.containsKey("passwordAuth")) {
                schema.passwordAuth = mapper.convertValue(rawOptions.get("passwordAuth"), CollectionSchema.PasswordAuthConfig.class);
            }
            if (rawOptions.containsKey("otp")) {
                schema.otp = mapper.convertValue(rawOptions.get("otp"), CollectionSchema.OtpConfig.class);
            }
            if (rawOptions.containsKey("mfa")) {
                schema.mfa = mapper.convertValue(rawOptions.get("mfa"), CollectionSchema.MfaConfig.class);
            }
            if (rawOptions.containsKey("oauth2")) {
                schema.oauth2 = mapper.convertValue(rawOptions.get("oauth2"), CollectionSchema.OAuth2Config.class);
            }
            if (rawOptions.containsKey("authToken")) {
                schema.authToken = mapper.convertValue(rawOptions.get("authToken"), CollectionSchema.TokenConfig.class);
            }
            if (rawOptions.containsKey("passwordResetToken")) {
                schema.passwordResetToken = mapper.convertValue(rawOptions.get("passwordResetToken"), CollectionSchema.TokenConfig.class);
            }
            if (rawOptions.containsKey("verificationToken")) {
                schema.verificationToken = mapper.convertValue(rawOptions.get("verificationToken"), CollectionSchema.TokenConfig.class);
            }
            if (rawOptions.containsKey("emailChangeToken")) {
                schema.emailChangeToken = mapper.convertValue(rawOptions.get("emailChangeToken"), CollectionSchema.TokenConfig.class);
            }
            if (rawOptions.containsKey("fileToken")) {
                schema.fileToken = mapper.convertValue(rawOptions.get("fileToken"), CollectionSchema.TokenConfig.class);
            }
        }
        if (schema.passwordAuth == null) {
            schema.passwordAuth = new CollectionSchema.PasswordAuthConfig();
        }
        normalizePasswordAuthConfig(schema.passwordAuth);
        if (schema.otp == null) {
            schema.otp = new CollectionSchema.OtpConfig();
        }
        schema.otp.duration = Math.max(60L, schema.otp.duration <= 0 ? 300L : schema.otp.duration);
        schema.otp.length = Math.max(4, Math.min(12, schema.otp.length <= 0 ? 6 : schema.otp.length));
        if (schema.mfa == null) {
            schema.mfa = new CollectionSchema.MfaConfig();
        }
        schema.mfa.duration = Math.max(60L, schema.mfa.duration <= 0 ? 1800L : schema.mfa.duration);
        if (schema.oauth2 == null) {
            schema.oauth2 = new CollectionSchema.OAuth2Config();
        }
        normalizeOAuth2Config(schema.oauth2);
        schema.authToken = normalizeTokenConfig(schema.authToken, CollectionSchema.DEFAULT_AUTH_TOKEN_DURATION);
        schema.passwordResetToken = normalizeTokenConfig(schema.passwordResetToken, CollectionSchema.DEFAULT_PASSWORD_RESET_TOKEN_DURATION);
        schema.verificationToken = normalizeTokenConfig(schema.verificationToken, CollectionSchema.DEFAULT_VERIFICATION_TOKEN_DURATION);
        schema.emailChangeToken = normalizeTokenConfig(schema.emailChangeToken, CollectionSchema.DEFAULT_EMAIL_CHANGE_TOKEN_DURATION);
        schema.fileToken = normalizeTokenConfig(schema.fileToken, CollectionSchema.DEFAULT_FILE_TOKEN_DURATION);
        if (isAuthUserCollection(schema)) {
            ensureAuthField(schema, new FieldSchema("field_email", "email", "email", true, true, false));
            ensureAuthField(schema, new FieldSchema("field_password", "password", "password", true, false, true));
            ensureAuthField(schema, new FieldSchema("field_verified", "verified", "bool", false, false, false));
        }
    }

    private Map<String, Object> collectionOptions(CollectionSchema schema, Map<String, Object> rawOptions) {
        Map<String, Object> options = new LinkedHashMap<>(rawOptions == null ? Map.of() : rawOptions);
        if ("auth".equals(schema.type)) {
            options.put("passwordAuth", mapper.convertValue(schema.passwordAuth, Map.class));
            options.put("otp", mapper.convertValue(schema.otp, Map.class));
            options.put("mfa", mapper.convertValue(schema.mfa, Map.class));
            options.put("oauth2", mapper.convertValue(schema.oauth2, Map.class));
            options.put("authToken", mapper.convertValue(schema.authToken, Map.class));
            options.put("passwordResetToken", mapper.convertValue(schema.passwordResetToken, Map.class));
            options.put("verificationToken", mapper.convertValue(schema.verificationToken, Map.class));
            options.put("emailChangeToken", mapper.convertValue(schema.emailChangeToken, Map.class));
            options.put("fileToken", mapper.convertValue(schema.fileToken, Map.class));
        }
        return options;
    }

    private void ensureAuthField(CollectionSchema schema, FieldSchema field) {
        if (!hasField(schema, field.name)) {
            field.system = true;
            schema.fields.add(field);
        }
    }

    private boolean hasField(CollectionSchema schema, String name) {
        if (schema.fields == null) return false;
        for (FieldSchema field : schema.fields) {
            if (name.equals(field.name)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAuthUserCollection(CollectionSchema schema) {
        return schema != null && "auth".equals(schema.type) && !"_superusers".equals(schema.name);
    }

    private void normalizePasswordAuthConfig(CollectionSchema.PasswordAuthConfig config) {
        if (config.identityFields == null || config.identityFields.isEmpty()) {
            config.identityFields = new ArrayList<>(List.of("email"));
            return;
        }
        LinkedHashSet<String> identities = new LinkedHashSet<>();
        for (String field : config.identityFields) {
            String value = field == null ? "" : field.trim();
            if ("email".equals(value) || "username".equals(value)) {
                identities.add(value);
            }
        }
        if (identities.isEmpty()) {
            identities.add("email");
        }
        config.identityFields = new ArrayList<>(identities);
    }

    private void normalizeOAuth2Config(CollectionSchema.OAuth2Config config) {
        if (config.providers == null) {
            config.providers = new ArrayList<>();
            return;
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        List<CollectionSchema.OAuth2ProviderConfig> normalizedProviders = new ArrayList<>();
        for (CollectionSchema.OAuth2ProviderConfig provider : config.providers) {
            if (provider == null || provider.name == null || provider.name.isBlank()) {
                continue;
            }
            OAuth2ProviderManager.ProviderMetadata metadata = OAuth2ProviderManager.providerMetadata(provider.name);
            if (metadata == null || !names.add(metadata.name())) {
                continue;
            }
            CollectionSchema.OAuth2ProviderConfig normalized = new CollectionSchema.OAuth2ProviderConfig();
            normalized.name = metadata.name();
            normalized.clientId = textSetting(provider.clientId);
            normalized.clientSecret = textSetting(provider.clientSecret);
            normalized.authURL = textSetting(provider.authURL);
            normalized.tokenURL = textSetting(provider.tokenURL);
            normalized.userInfoURL = textSetting(provider.userInfoURL);
            normalized.pkce = provider.pkce;
            normalized.scopes = provider.scopes == null
                    ? new ArrayList<>()
                    : provider.scopes.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(scope -> !scope.isBlank())
                    .collect(Collectors.toCollection(ArrayList::new));
            OAuth2ProviderManager.validateConfig(normalized);
            normalizedProviders.add(normalized);
        }
        config.providers = normalizedProviders;
    }

    private CollectionSchema scaffoldCollection(String type) {
        CollectionSchema collection = new CollectionSchema();
        collection.id = "";
        collection.name = "";
        collection.type = type;
        collection.system = false;
        collection.fields = new ArrayList<>();
        if ("auth".equals(type)) {
            collection.fields.add(new FieldSchema("field_email", "email", "email", true, true, false));
            collection.fields.add(new FieldSchema("field_password", "password", "password", true, false, true));
            collection.fields.add(new FieldSchema("field_verified", "verified", "bool", false, false, false));
        }
        return collection;
    }

    private Map<String, Object> orderedMap(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }

    private String textSetting(String value) {
        return value == null ? "" : value.trim();
    }

    private CollectionSchema.TokenConfig normalizeTokenConfig(CollectionSchema.TokenConfig config, long fallbackDuration) {
        CollectionSchema.TokenConfig normalized = config == null ? new CollectionSchema.TokenConfig() : config;
        normalized.duration = normalized.duration > 0 ? normalized.duration : fallbackDuration;
        if (normalized.secret == null) {
            normalized.secret = "";
        }
        return normalized;
    }

    private List<String> splitSqlStatements(String sql) {
        return splitOn(sql, ';');
    }

    private List<String> splitOn(String text, char delimiter) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        int parens = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quote != 0) {
                current.append(ch);
                if (ch == quote) {
                    if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
                        current.append(text.charAt(++i));
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                current.append(ch);
                continue;
            }
            if (ch == '(') {
                parens++;
            } else if (ch == ')' && parens > 0) {
                parens--;
            }
            if (ch == delimiter && parens == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private boolean isWriteStatement(String statement) {
        return SQL_WRITE_PREFIXES.stream().anyMatch(prefix -> startsWithKeyword(statement, prefix));
    }

    private boolean startsWithKeyword(String text, String keyword) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.length() < keyword.length()) {
            return false;
        }
        if (!trimmed.regionMatches(true, 0, keyword, 0, keyword.length())) {
            return false;
        }
        return trimmed.length() == keyword.length() || !isIdentifierChar(trimmed.charAt(keyword.length()));
    }

    private boolean isIdentifierChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '$';
    }
}
