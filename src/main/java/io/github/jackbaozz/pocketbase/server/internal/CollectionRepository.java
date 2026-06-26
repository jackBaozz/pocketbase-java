package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;

import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.regex.Pattern;

public class CollectionRepository {
    private final RelationalStorageEngine engine;
    private final ObjectMapper mapper;

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,62}$");

    public CollectionRepository(RelationalStorageEngine engine, ObjectMapper mapper) {
        this.engine = engine;
        this.mapper = mapper;
    }

    private void validateIdentifier(String identifier, String fieldName, String propName) {
        if (identifier == null || !IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new ApiException(400, "Record validation failed.", Map.of(
                    propName, Map.of(
                            "code", "validation_failed",
                            "message", "Invalid " + fieldName + " identifier format. Must start with letter/underscore and contain only letters/numbers/underscores (max 63 chars)."
                    )
            ));
        }
    }

    private void validateSchemaIdentifiers(CollectionSchema schema) {
        validateIdentifier(schema.name, "collection name", "name");
        if (schema.fields != null) {
            for (FieldSchema field : schema.fields) {
                // Technically fields are nested in fields list, but let's just use field.name for now.
                validateIdentifier(field.name, "field name", "name");
            }
        }
    }

    private void requireCollectionExists(String collection) {
        try (Connection conn = engine.connection();
             PreparedStatement check = conn.prepareStatement("SELECT 1 FROM _collections WHERE name = ? OR id = ?")) {
            check.setString(1, collection);
            check.setString(2, collection);
            try (ResultSet rs = check.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(404, "Collection not found.");
                }
            }
        } catch (SQLException e) {
            throw new ApiException(500, "Database error.");
        }
    }

    private void rebuildIndexes(Connection conn, CollectionSchema schema, String physicalName) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='" + physicalName.replace("'", "''") + "'");
            List<String> existingIndexes = new ArrayList<>();
            while (rs.next()) {
                String idx = rs.getString(1);
                if (!idx.startsWith("sqlite_autoindex_")) {
                    existingIndexes.add(idx);
                }
            }
            for (String idx : existingIndexes) {
                stmt.execute("DROP INDEX IF EXISTS " + engine.qi(idx));
            }

            if (schema.indexes != null) {
                for (String createIdxSql : schema.indexes) {
                    if (createIdxSql != null && createIdxSql.trim().toUpperCase().startsWith("CREATE ")) {
                        stmt.execute(createIdxSql);
                    }
                }
            }
        }
    }

    public Map<String, Object> listCollections(Map<String, String> query) {
        Map<String, String> safeQuery = query == null ? Map.of() : query;
        try (Connection conn = engine.connection();
             PreparedStatement stmt = conn.prepareStatement("SELECT * FROM _collections ORDER BY created DESC");
             ResultSet rs = stmt.executeQuery()) {
            List<Map<String, Object>> items = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("id", rs.getString("id"));
                col.put("name", rs.getString("name"));
                col.put("type", rs.getString("type"));
                col.put("system", rs.getInt("system") == 1);
                col.put("created", rs.getString("created") == null ? "" : rs.getString("created"));
                col.put("updated", rs.getString("updated") == null ? "" : rs.getString("updated"));
                
                String schemaStr = rs.getString("schema");
                if (schemaStr != null) {
                    col.put("schema", mapper.readValue(schemaStr, List.class));
                } else {
                    col.put("schema", List.of());
                }

                col.put("createRule", rs.getString("createRule"));
                col.put("listRule", rs.getString("listRule"));
                col.put("viewRule", rs.getString("viewRule"));
                col.put("updateRule", rs.getString("updateRule"));
                col.put("deleteRule", rs.getString("deleteRule"));

                String optionsStr = rs.getString("options");
                if (optionsStr != null && !optionsStr.isBlank()) {
                    col.put("options", mapper.readValue(optionsStr, Map.class));
                } else {
                    col.put("options", Map.of());
                }
                items.add(col);
            }
            
            String filter = safeQuery.get("filter");
            if (filter != null && !filter.isBlank()) {
                items.removeIf(col -> !io.github.jackbaozz.pocketbase.server.internal.RuleEvaluator.matches(filter, io.github.jackbaozz.pocketbase.server.internal.RuleEvaluator.context(col, null, Map.of(), "GET", null, name -> java.util.List.of())));
            }
            
            String sort = safeQuery.get("sort");
            if (sort != null && !sort.isBlank()) {
                String[] parts = sort.split(",");
                java.util.Comparator<Map<String, Object>> comparator = null;
                for (String raw : parts) {
                    String part = raw.trim();
                    if (part.isEmpty()) continue;
                    boolean desc = part.startsWith("-");
                    String field = desc ? part.substring(1) : part;
                    java.util.Comparator<Map<String, Object>> next = java.util.Comparator.comparing(record -> String.valueOf(record.get(field) == null ? "" : record.get(field)));
                    if (desc) next = next.reversed();
                    comparator = comparator == null ? next : comparator.thenComparing(next);
                }
                if (comparator != null) items.sort(comparator);
            }
            
            int page = 1;
            int perPage = 100;
            try {
                if (safeQuery.containsKey("page")) page = Integer.parseInt(safeQuery.get("page"));
                if (safeQuery.containsKey("perPage")) perPage = Integer.parseInt(safeQuery.get("perPage"));
            } catch (NumberFormatException ignored) {}
            if (page < 1) page = 1;
            if (perPage < 1) perPage = 100;
            int total = items.size();
            int from = Math.min(total, (page - 1) * perPage);
            int to = Math.min(total, from + perPage);
            List<Map<String, Object>> pageItems = items.subList(from, to).stream()
                    .map(item -> io.github.jackbaozz.pocketbase.server.internal.RecordProcessor.selectFields(item, safeQuery.get("fields")))
                    .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
                    
            int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / perPage);
            return Map.of("items", pageItems, "page", page, "perPage", perPage, "totalItems", total, "totalPages", totalPages);
        } catch (SQLException | IOException e) {
            e.printStackTrace();
            throw new RuntimeException("failed to list collections", e);
        }
    }

    public CollectionSchema createCollection(JsonNode body) {
        CollectionSchema colSchema;
        Map<String, Object> rawOptions = Map.of();
        try {
            colSchema = mapper.treeToValue(body, CollectionSchema.class);
            if (body.has("options")) {
                rawOptions = mapper.convertValue(body.get("options"), new TypeReference<Map<String, Object>>() {});
            }
        } catch (IOException e) {
            throw new ApiException(400, "Collection payload must be a JSON object.");
        }

        if (colSchema.id == null || colSchema.id.isBlank()) {
            colSchema.id = "pbc_" + IdGenerator.id();
        }
        validateSchemaIdentifiers(colSchema);
        if (!"base".equals(colSchema.type) && !"auth".equals(colSchema.type) && !"view".equals(colSchema.type)) {
            throw new ApiException(400, "Record validation failed.", Map.of(
                    "type", Map.of(
                            "code", "validation_failed",
                            "message", "Unsupported collection type."
                    )
            ));
        }

        Connection conn = null;
        try {
            conn = engine.connection();
            try (PreparedStatement check = conn.prepareStatement("SELECT count(*) FROM _collections WHERE name = ? OR id = ?")) {
                check.setString(1, colSchema.name);
                check.setString(2, colSchema.id);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        throw new ApiException(400, "Collection name or id already exists.");
                    }
                }
            }

            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO _collections(id, name, type, schema, system, createRule, listRule, viewRule, updateRule, deleteRule, options, created, updated) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1, colSchema.id);
                insert.setString(2, colSchema.name);
                insert.setString(3, colSchema.type);
                insert.setString(4, mapper.writeValueAsString(colSchema.fields));
                insert.setInt(5, colSchema.system ? 1 : 0);
                insert.setString(6, colSchema.createRule);
                insert.setString(7, colSchema.listRule);
                insert.setString(8, colSchema.viewRule);
                insert.setString(9, colSchema.updateRule);
                insert.setString(10, colSchema.deleteRule);
                insert.setString(11, mapper.writeValueAsString(rawOptions));
                String now = java.time.Instant.now().toString();
                insert.setString(12, now);
                insert.setString(13, now);
                insert.executeUpdate();
            }

            if ("view".equals(colSchema.type)) {
                String viewQuery = rawOptions.containsKey("query") ? rawOptions.get("query").toString() : "SELECT 1";
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE VIEW " + engine.qi(colSchema.name) + " AS " + viewQuery);
                }
            } else {
                var createTable = engine.database().dsl(conn)
                        .createTable(DSL.name(colSchema.name))
                        .column(DSL.name("id"), SQLDataType.VARCHAR(255).nullable(false))
                        .column(DSL.name("created"), SQLDataType.VARCHAR(64))
                        .column(DSL.name("updated"), SQLDataType.VARCHAR(64));

                if ("auth".equals(colSchema.type)) {
                    createTable = createTable.column(DSL.name("username"), SQLDataType.VARCHAR(255))
                            .column(DSL.name("email"), SQLDataType.VARCHAR(255))
                            .column(DSL.name("emailVisibility"), SQLDataType.INTEGER.defaultValue(0))
                            .column(DSL.name("verified"), SQLDataType.INTEGER.defaultValue(0))
                            .column(DSL.name("tokenKey"), SQLDataType.VARCHAR(255))
                            .column(DSL.name("passwordHash"), SQLDataType.VARCHAR(255));
                }

                for (FieldSchema field : colSchema.fields) {
                    createTable = createTable.column(DSL.name(field.name), SQLDataType.CLOB);
                }
                createTable.constraints(DSL.constraint(DSL.name("pk_" + colSchema.name)).primaryKey(DSL.name("id")))
                        .execute();
                rebuildIndexes(conn, colSchema, colSchema.name);
            }
        } catch (SQLException | IOException | DataAccessException e) {
            engine.handleSqlConstraintException(e);
            throw new ApiException(400, "Failed to create collection: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    engine.closeIfStandalone(conn);
                } catch (SQLException ignored) {
                }
            }
        }

        return colSchema;
    }

    public CollectionSchema updateCollection(String collection, JsonNode body) {
        requireCollectionExists(collection);
        CollectionSchema newSchema;
        Map<String, Object> rawOptions = Map.of();
        try {
            newSchema = mapper.treeToValue(body, CollectionSchema.class);
            if (body.has("options")) {
                rawOptions = mapper.convertValue(body.get("options"), new TypeReference<Map<String, Object>>() {});
            }
        } catch (IOException e) {
            throw new ApiException(400, "Collection payload must be a JSON object.");
        }
        validateSchemaIdentifiers(newSchema);

        Connection conn = null;
        try {
            conn = engine.connection();
            String oldSchemaJson = null;
            String physicalName = null;
            try (PreparedStatement select = conn.prepareStatement("SELECT name, schema FROM _collections WHERE name = ? OR id = ?")) {
                select.setString(1, collection);
                select.setString(2, collection);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        physicalName = rs.getString("name");
                        oldSchemaJson = rs.getString("schema");
                    }
                }
            }
            if (physicalName == null) throw new ApiException(404, "Collection not found.");

            if ("view".equals(newSchema.type)) {
                String viewQuery = rawOptions.containsKey("query") ? rawOptions.get("query").toString() : "SELECT 1";
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("DROP VIEW IF EXISTS " + engine.qi(physicalName));
                    stmt.execute("CREATE VIEW " + engine.qi(physicalName) + " AS " + viewQuery);
                }
            } else {
                List<FieldSchema> oldFields = new ArrayList<>();
                if (oldSchemaJson != null && !oldSchemaJson.isBlank()) {
                    oldFields = mapper.readValue(oldSchemaJson, new TypeReference<List<FieldSchema>>() {});
                }

                List<String> oldNames = oldFields.stream().map(f -> f.name).toList();
                List<String> newNames = newSchema.fields.stream().map(f -> f.name).toList();

                DSLContext dsl = engine.database().dsl(conn);
                for (FieldSchema nf : newSchema.fields) {
                    if (!oldNames.contains(nf.name)) {
                        dsl.alterTable(DSL.name(physicalName))
                                .add(DSL.name(nf.name), SQLDataType.CLOB)
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

            engine.database().dsl(conn)
                    .update(engine.qt("_collections"))
                    .set(engine.qfs("name"), newSchema.name)
                    .set(engine.qfs("type"), newSchema.type)
                    .set(engine.qfs("schema"), mapper.writeValueAsString(newSchema.fields))
                    .set(engine.qfi("system"), newSchema.system ? 1 : 0)
                    .set(engine.qfs("createRule"), newSchema.createRule)
                    .set(engine.qfs("listRule"), newSchema.listRule)
                    .set(engine.qfs("viewRule"), newSchema.viewRule)
                    .set(engine.qfs("updateRule"), newSchema.updateRule)
                    .set(engine.qfs("deleteRule"), newSchema.deleteRule)
                    .set(engine.qfs("options"), mapper.writeValueAsString(rawOptions))
                    .set(engine.qfs("updated"), java.time.Instant.now().toString())
                    .where(engine.qfs("id").eq(collection).or(engine.qfs("name").eq(collection)))
                    .execute();

        } catch (SQLException | IOException | DataAccessException e) {
            engine.handleSqlConstraintException(e);
            throw new ApiException(400, "Failed to update collection: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    engine.closeIfStandalone(conn);
                } catch (SQLException ignored) {
                }
            }
        }

        return newSchema;
    }

    public void deleteCollection(String collection) {
        Connection conn = null;
        try {
            conn = engine.connection();
            String physicalName = null;
            String type = null;
            try (PreparedStatement select = conn.prepareStatement("SELECT name, type FROM _collections WHERE id = ? OR name = ?")) {
                select.setString(1, collection);
                select.setString(2, collection);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        physicalName = rs.getString("name");
                        type = rs.getString("type");
                    }
                }
            }

            if (physicalName == null) {
                throw new ApiException(404, "Collection not found.");
            }

            engine.database().dsl(conn)
                    .deleteFrom(engine.qt("_collections"))
                    .where(engine.qfs("id").eq(collection).or(engine.qfs("name").eq(collection)))
                    .execute();

            if ("view".equals(type)) {
                engine.database().dsl(conn).dropViewIfExists(DSL.name(physicalName)).execute();
            } else {
                engine.database().dsl(conn).dropTableIfExists(DSL.name(physicalName)).execute();
            }

        } catch (SQLException | DataAccessException e) {
            throw new ApiException(400, "Failed to delete collection: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    engine.closeIfStandalone(conn);
                } catch (SQLException ignored) {
                }
            }
        }
    }

    public Map<String, Object> getCollection(String collection, Map<String, String> query) {
        try (Connection conn = engine.connection();
             PreparedStatement select = conn.prepareStatement(
                     "SELECT id, name, type, schema, system, createRule, listRule, viewRule, updateRule, deleteRule, options, created, updated FROM _collections WHERE name = ? OR id = ?")) {
            select.setString(1, collection);
            select.setString(2, collection);
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("id", rs.getString("id"));
                    col.put("name", rs.getString("name"));
                    col.put("type", rs.getString("type"));
                    col.put("system", rs.getInt("system") == 1);
                    col.put("created", rs.getString("created") == null ? "" : rs.getString("created"));
                    col.put("updated", rs.getString("updated") == null ? "" : rs.getString("updated"));
                    
                    String schemaStr = rs.getString("schema");
                    if (schemaStr != null) {
                        col.put("schema", mapper.readValue(schemaStr, List.class));
                    } else {
                        col.put("schema", List.of());
                    }

                    col.put("createRule", rs.getString("createRule"));
                    col.put("listRule", rs.getString("listRule"));
                    col.put("viewRule", rs.getString("viewRule"));
                    col.put("updateRule", rs.getString("updateRule"));
                    col.put("deleteRule", rs.getString("deleteRule"));

                    String optionsStr = rs.getString("options");
                    if (optionsStr != null && !optionsStr.isBlank()) {
                        col.put("options", mapper.readValue(optionsStr, Map.class));
                    } else {
                        col.put("options", Map.of());
                    }
                    return io.github.jackbaozz.pocketbase.server.internal.RecordProcessor.selectFields(col, query == null ? null : query.get("fields"));
                }
            }
        } catch (SQLException | IOException e) {
            throw new RuntimeException(e);
        }
        throw new ApiException(404, "Collection not found.");
    }

    public CollectionSchema getCollectionSchema(String nameOrId) {
        Connection conn = null;
        try {
            conn = engine.connection();
            try (PreparedStatement select = conn.prepareStatement("SELECT id, name, schema, type, viewRule FROM _collections WHERE name = ? OR id = ?")) {
                select.setString(1, nameOrId);
                select.setString(2, nameOrId);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        CollectionSchema col = new CollectionSchema();
                        col.id = rs.getString("id");
                        col.name = rs.getString("name");
                        col.type = rs.getString("type");
                        col.viewRule = rs.getString("viewRule");
                        String schemaJson = rs.getString("schema");
                        if (schemaJson != null && !schemaJson.isBlank()) {
                            col.fields = mapper.readValue(schemaJson, new TypeReference<List<FieldSchema>>() {});
                        }
                        return col;
                    }
                }
            }
        } catch (SQLException | IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (conn != null) {
                try {
                    engine.closeIfStandalone(conn);
                } catch (SQLException ignored) {
                }
            }
        }
        throw new ApiException(404, "Collection not found.");
    }

    public void truncateCollection(String collection) {
        requireCollectionExists(collection);
        CollectionSchema schema = getCollectionSchema(collection);
        try {
            engine.database().dsl()
                    .deleteFrom(engine.qt(schema.name))
                    .execute();
        } catch (DataAccessException e) {
            throw new ApiException(400, "Failed to truncate collection: " + e.getMessage());
        }
    }

    public Map<String, Object> importCollections(JsonNode body, boolean dryRun) {
        if (body == null || !body.isObject()) {
            throw new ApiException(400, "Collections import payload must be a JSON object.");
        }
        JsonNode collectionsNode = body.get("collections");
        if (collectionsNode == null || !collectionsNode.isArray() || collectionsNode.isEmpty()) {
            throw new ApiException(400, "Failed to import collections.", Map.of("collections", Map.of("code", "validation_failed", "message", "collections is required.")));
        }

        List<CollectionSchema> newOrUpdated = new ArrayList<>();
        for (JsonNode item : collectionsNode) {
            try {
                CollectionSchema collection = mapper.treeToValue(item, CollectionSchema.class);
                newOrUpdated.add(collection);
            } catch (IOException e) {
                throw new ApiException(400, "Invalid collection payload.");
            }
        }
        List<String> deleted = new ArrayList<>();

        if (dryRun) {
            return Map.of("collections", newOrUpdated, "deletedCollections", deleted);
        }

        return engine.transactional(() -> {
            for (CollectionSchema c : newOrUpdated) {
                try {
                    getCollection(c.id != null ? c.id : c.name, null);
                    updateCollection(c.id != null ? c.id : c.name, mapper.valueToTree(c));
                } catch (ApiException e) {
                    createCollection(mapper.valueToTree(c));
                }
            }
            return Map.of("collections", newOrUpdated, "deletedCollections", deleted);
        });
    }

}
