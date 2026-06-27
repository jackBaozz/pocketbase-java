package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.internal.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.FieldTypeMapping;
import io.github.jackbaozz.pocketbase.server.internal.IdGenerator;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CollectionRepository extends BaseRepository {

    public CollectionRepository(JooqDatabase database, ObjectMapper mapper) {
        super(database, mapper);
    }

    private Condition collectionCondition(String collection) {
        return qfs("name").eq(collection).or(qfs("id").eq(collection));
    }

    public Map<String, Object> listCollections(Map<String, String> query) {
        try {
            List<Map<String, Object>> items = database.dsl()
                    .select(
                            qfs("id"), qfs("name"), qfs("type"), qfs("schema"),
                            qfi("system"), qfs("createRule"), qfs("listRule"),
                            qfs("viewRule"), qfs("updateRule"), qfs("deleteRule"), qfs("options")
                    )
                    .from(qt("_collections"))
                    .fetch()
                    .map(rs -> {
                        Map<String, Object> col = new LinkedHashMap<>();
                        col.put("id", rs.get(qfs("id")));
                        col.put("name", rs.get(qfs("name")));
                        col.put("type", rs.get(qfs("type")));
                        String schemaJson = rs.get(qfs("schema"));
                        if (schemaJson != null) {
                            try {
                                col.put("schema", mapper.readValue(schemaJson, List.class));
                            } catch (IOException e) {
                                col.put("schema", List.of());
                            }
                        } else {
                            col.put("schema", List.of());
                        }
                        col.put("system", rs.get(qfi("system")) == 1);
                        col.put("createRule", rs.get(qfs("createRule")));
                        col.put("listRule", rs.get(qfs("listRule")));
                        col.put("viewRule", rs.get(qfs("viewRule")));
                        col.put("updateRule", rs.get(qfs("updateRule")));
                        col.put("deleteRule", rs.get(qfs("deleteRule")));

                        String optsJson = rs.get(qfs("options"));
                        if (optsJson != null && !optsJson.isBlank()) {
                            try {
                                col.put("options", mapper.readValue(optsJson, Map.class));
                            } catch (IOException e) {
                                col.put("options", Map.of());
                            }
                        } else {
                            col.put("options", Map.of());
                        }
                        return col;
                    });
            return Map.of("items", items, "page", 1, "perPage", 100, "totalItems", items.size(), "totalPages", 1);
        } catch (DataAccessException e) {
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

            int count = database.dsl(conn)
                    .selectCount()
                    .from(qt("_collections"))
                    .where(collectionCondition(colSchema.name))
                    .fetchOne(0, int.class);
            if (count > 0) {
                throw new ApiException(400, "Collection name or id already exists.");
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
        try {
            Record rs = database.dsl()
                    .select(qfs("id"), qfs("name"), qfs("type"))
                    .from(qt("_collections"))
                    .where(collectionCondition(collection))
                    .fetchOne();
            if (rs != null) {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("id", rs.get(qfs("id")));
                col.put("name", rs.get(qfs("name")));
                col.put("type", rs.get(qfs("type")));
                return col;
            }
        } catch (DataAccessException e) {
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

            Record rs = database.dsl(conn)
                    .select(qfs("name"), qfs("type"))
                    .from(qt("_collections"))
                    .where(collectionCondition(collection))
                    .fetchOne();
            if (rs != null) {
                physicalName = rs.get(qfs("name"));
                type = rs.get(qfs("type"));
            }

            if (physicalName == null) {
                throw new ApiException(404, "Collection not found.");
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
                            qfs("viewRule"), qfs("updateRule"), qfs("deleteRule")
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
                return col;
            }
        } catch (DataAccessException | IOException e) {
            throw new RuntimeException(e);
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
