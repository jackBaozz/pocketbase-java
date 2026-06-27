package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.internal.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.IdGenerator;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CollectionRepository extends BaseRepository {

    public CollectionRepository(JooqDatabase database, ObjectMapper mapper) {
        super(database, mapper);
    }

    public Map<String, Object> listCollections(Map<String, String> query) {
        try (Connection conn = database.connection();
             PreparedStatement select = conn.prepareStatement("SELECT id, name, type, schema, system, createRule, listRule, viewRule, updateRule, deleteRule, options FROM _collections");
             ResultSet rs = select.executeQuery()) {
            List<Map<String, Object>> items = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("id", rs.getString("id"));
                col.put("name", rs.getString("name"));
                col.put("type", rs.getString("type"));
                String schemaJson = rs.getString("schema");
                if (schemaJson != null) {
                    try {
                        col.put("schema", mapper.readValue(schemaJson, List.class));
                    } catch (IOException e) {
                        col.put("schema", List.of());
                    }
                } else {
                    col.put("schema", List.of());
                }
                col.put("system", rs.getInt("system") == 1);
                col.put("createRule", rs.getString("createRule"));
                col.put("listRule", rs.getString("listRule"));
                col.put("viewRule", rs.getString("viewRule"));
                col.put("updateRule", rs.getString("updateRule"));
                col.put("deleteRule", rs.getString("deleteRule"));

                String optsJson = rs.getString("options");
                if (optsJson != null && !optsJson.isBlank()) {
                    try {
                        col.put("options", mapper.readValue(optsJson, Map.class));
                    } catch (IOException e) {
                        col.put("options", Map.of());
                    }
                } else {
                    col.put("options", Map.of());
                }
                items.add(col);
            }
            return Map.of("items", items, "page", 1, "perPage", 100, "totalItems", items.size(), "totalPages", 1);
        } catch (SQLException e) {
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
            throw new ApiException(400, "Unsupported collection type.", Map.of("type", Map.of("code", "validation_invalid_value", "message", "Supported types are base, auth and view.")));
        }

        Connection conn = null;
        try {
            conn = database.connection();
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
                    "INSERT INTO _collections(id, name, type, schema, system, createRule, listRule, viewRule, updateRule, deleteRule, options) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
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
                insert.executeUpdate();
            }

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
                    createTable = createTable.column(DSL.name(field.name), SQLDataType.CLOB);
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
        try (Connection conn = database.connection();
             PreparedStatement select = conn.prepareStatement(
                     "SELECT id, name, type, schema, system, createRule, listRule, viewRule, updateRule, deleteRule, options FROM _collections WHERE name = ? OR id = ?")) {
            select.setString(1, collection);
            select.setString(2, collection);
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("id", rs.getString("id"));
                    col.put("name", rs.getString("name"));
                    col.put("type", rs.getString("type"));
                    return col;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        throw new ApiException(404, "Collection not found.");
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
            conn = database.connection();
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
                    stmt.execute("DROP VIEW IF EXISTS " + qi(physicalName));
                    stmt.execute("CREATE VIEW " + qi(physicalName) + " AS " + viewQuery);
                }
            } else {
                List<FieldSchema> oldFields = new ArrayList<>();
                if (oldSchemaJson != null && !oldSchemaJson.isBlank()) {
                    oldFields = mapper.readValue(oldSchemaJson, new TypeReference<List<FieldSchema>>() {});
                }

                List<String> oldNames = oldFields.stream().map(f -> f.name).toList();
                List<String> newNames = newSchema.fields.stream().map(f -> f.name).toList();

                DSLContext dsl = database.dsl(conn);
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
                    .where(qfs("id").eq(collection).or(qfs("name").eq(collection)))
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

            database.dsl(conn)
                    .deleteFrom(qt("_collections"))
                    .where(qfs("id").eq(collection).or(qfs("name").eq(collection)))
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
            throw new ApiException(400, "Collections import payload must be a JSON object.");
        }
        JsonNode collectionsNode = body.get("collections");
        if (collectionsNode == null || !collectionsNode.isArray() || collectionsNode.isEmpty()) {
            throw new ApiException(400, "Failed to import collections.", Map.of("collections", Map.of("code", "validation_required", "message", "collections is required.")));
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

        return database.transactional(() -> {
            for (CollectionSchema c : newOrUpdated) {
                try {
                    getCollection(c.id != null ? c.id : c.name, Map.of());
                    updateCollection(c.id != null ? c.id : c.name, mapper.valueToTree(c));
                } catch (ApiException e) {
                    createCollection(mapper.valueToTree(c));
                }
            }
            return Map.of("collections", newOrUpdated, "deletedCollections", deleted);
        });
    }

    public Map<String, Object> collectionScaffolds() {
        return Map.of();
    }

    public Map<String, Object> dryRunView(JsonNode body) {
        return Map.of();
    }

    public List<Map<String, Object>> oauth2ProviderMetadata() {
        return List.of();
    }

    public void requireCollectionExists(String collection) {
        try (Connection conn = database.connection();
             PreparedStatement select = conn.prepareStatement("SELECT count(*) FROM _collections WHERE name = ? OR id = ?")) {
            select.setString(1, collection);
            select.setString(2, collection);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next() || rs.getInt(1) == 0) {
                    throw new ApiException(404, "Collection not found.");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public CollectionSchema getCollectionSchema(String nameOrId) {
        Connection conn = null;
        try {
            conn = database.connection();
            try (PreparedStatement select = conn.prepareStatement("SELECT id, name, schema, type, system, createRule, listRule, viewRule, updateRule, deleteRule FROM _collections WHERE name = ? OR id = ?")) {
                select.setString(1, nameOrId);
                select.setString(2, nameOrId);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        CollectionSchema col = new CollectionSchema();
                        col.id = rs.getString("id");
                        col.name = rs.getString("name");
                        col.type = rs.getString("type");
                        col.system = rs.getInt("system") == 1;
                        col.createRule = rs.getString("createRule");
                        col.listRule = rs.getString("listRule");
                        col.viewRule = rs.getString("viewRule");
                        col.updateRule = rs.getString("updateRule");
                        col.deleteRule = rs.getString("deleteRule");
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
                    database.closeIfStandalone(conn);
                } catch (SQLException ignored) {
                }
            }
        }
        throw new ApiException(404, "Collection not found.");
    }

    private void validateSchemaIdentifiers(CollectionSchema schema) {
        validateIdentifier(schema.name, "name");
        if (schema.fields != null) {
            for (FieldSchema field : schema.fields) {
                validateIdentifier(field.name, field.name == null || field.name.isBlank() ? "schema" : field.name);
            }
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
}
