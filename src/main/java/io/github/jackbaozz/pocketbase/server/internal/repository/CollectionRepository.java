package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.github.jackbaozz.pocketbase.server.internal.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.ApiErrors;
import io.github.jackbaozz.pocketbase.server.internal.AuthCollectionConfigMerge;
import io.github.jackbaozz.pocketbase.server.internal.AuthCollectionFields;
import io.github.jackbaozz.pocketbase.server.internal.AuthCollectionConfigValidation;
import io.github.jackbaozz.pocketbase.server.internal.AuthSystemCollections;
import io.github.jackbaozz.pocketbase.server.internal.CollectionRuleSupport;
import io.github.jackbaozz.pocketbase.server.internal.CollectionResponseSupport;
import io.github.jackbaozz.pocketbase.server.internal.CollectionIndexSupport;
import io.github.jackbaozz.pocketbase.server.internal.CollectionFieldProtection;
import io.github.jackbaozz.pocketbase.server.internal.CollectionModelValidation;
import io.github.jackbaozz.pocketbase.server.internal.FieldTypeMapping;
import io.github.jackbaozz.pocketbase.server.internal.IdGenerator;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;
import io.github.jackbaozz.pocketbase.server.internal.OAuth2ProviderManager;
import io.github.jackbaozz.pocketbase.server.internal.OAuth2FieldMappingSupport;
import io.github.jackbaozz.pocketbase.server.internal.PhysicalTableNames;
import io.github.jackbaozz.pocketbase.server.internal.RecordProcessor;
import io.github.jackbaozz.pocketbase.server.internal.SchemaMigrationPlanner;
import io.github.jackbaozz.pocketbase.server.internal.SchemaIdSupport;
import io.github.jackbaozz.pocketbase.server.internal.SearchQuerySupport;
import io.github.jackbaozz.pocketbase.server.internal.SearchFieldValidationSupport;
import io.github.jackbaozz.pocketbase.server.internal.SystemCollections;
import io.github.jackbaozz.pocketbase.server.internal.ViewQuerySupport;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class CollectionRepository extends BaseRepository {
    private static final int MAX_VIEW_QUERY_LENGTH = 5000;
    private static final int MAX_VIEW_ROWS = 10;
    private final ThreadLocal<Map<String, CollectionSchema>> importRuleCollections = new ThreadLocal<>();
    public CollectionRepository(JooqDatabase database, ObjectMapper mapper) {
        super(database, mapper);
    }

    private Condition collectionCondition(String collection) {
        collection = SystemCollections.canonicalIdentifier(collection);
        return qfs("name").eq(collection).or(qfs("id").eq(collection));
    }

    /**
     * API collection names can be longer than the external database identifier
     * limit. Keep the logical name in _collections and use a deterministic
     * physical identifier for DDL and record access.
     */
    public String physicalTableName(CollectionSchema collection) {
        return collection == null ? null : PhysicalTableNames.tableName(database, collection.name);
    }

    private boolean collectionIdExists(String id) {
        return database.dsl().fetchExists(
                database.dsl().selectOne().from(qt("_collections")).where(qfs("id").eq(id))
        );
    }

    public Map<String, Object> listCollections(Map<String, String> query) {
        try {
            Map<String, String> safeQuery = query == null ? Map.of() : query;
            SearchQuerySupport.Parameters search = SearchQuerySupport.parse(safeQuery);
            SearchFieldValidationSupport.validateCollections(search);
            List<Map<String, Object>> items = database.dsl()
                    .select(
                            qfs("id"), qfs("name"), qfs("type"), qfs("schema"),
                            qfs("indexes"), qfs("created"), qfs("updated"),
                            qfi("system"), qfs("createRule"), qfs("listRule"),
                            qfs("viewRule"), qfs("updateRule"), qfs("deleteRule"), qfs("options")
                    )
                    .from(qt("_collections"))
                    .fetch()
                    .map(this::collectionMap);
            items = items.stream()
                    .filter(collection -> matchesCollectionFilter(collection, search.filter()))
                    .collect(Collectors.toCollection(ArrayList::new));
            SearchQuerySupport.sortMaps(items, search.sort(), null);
            int total = items.size();
            int from = search.fromIndex(total);
            int to = Math.min(total, from + search.perPage());
            List<Map<String, Object>> pageItems = items.subList(from, to).stream()
                    .map(item -> RecordProcessor.selectFields(item, safeQuery.get("fields")))
                    .collect(Collectors.toCollection(ArrayList::new));
            return SearchQuerySupport.result(search, total, pageItems);
        } catch (DataAccessException e) {
            throw new RuntimeException("failed to list collections", e);
        }
    }

    public CollectionSchema createCollection(JsonNode body) {
        return database.transactional(() -> createCollectionInternal(body));
    }

    private CollectionSchema createCollectionInternal(JsonNode body) {
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

        String collectionType = colSchema.type == null || colSchema.type.isBlank()
                ? "base"
                : colSchema.type.trim().toLowerCase(java.util.Locale.ROOT);
        colSchema.type = collectionType;
        if (body.has("fields") || body.has("schema")) {
            colSchema.fields = SchemaIdSupport.canonicalizeSubmittedFields(colSchema.fields);
        }
        if (colSchema.id == null || colSchema.id.isBlank()) {
            colSchema.id = SchemaIdSupport.nextCollectionId(
                    collectionType,
                    colSchema.name,
                    this::collectionIdExists
            );
        }
        String timestamp = Instant.now().toString();
        colSchema.created = timestamp;
        colSchema.updated = timestamp;
        normalizeCollectionSchema(colSchema, rawOptions, true);
        AuthCollectionConfigMerge.mergeSubmitted(mapper, colSchema, new CollectionSchema(), body);
        if ("view".equals(colSchema.type)) {
            prepareViewCollection(colSchema, "Failed to create collection.");
        }
        CollectionModelValidation.validate(
                null,
                colSchema,
                body,
                allCollectionSchemas(),
                this::physicalTableExists,
                "Failed to create collection."
        );
        CollectionRuleSupport.validate(
                colSchema,
                "Failed to create collection.",
                identifier -> resolveRuleCollection(colSchema, identifier)
        );
        colSchema.indexes = CollectionIndexSupport.normalize(
                colSchema,
                collectionIndexNames(null),
                "Failed to create collection."
        );
        AuthCollectionConfigValidation.validate(colSchema, "Failed to create collection.");
        if (!"base".equals(colSchema.type) && !"auth".equals(colSchema.type) && !"view".equals(colSchema.type)) {
            throw new ApiException(400, "Unsupported collection type.", Map.of("type", Map.of("code", "validation_invalid_value", "message", "Supported types are base, auth and view.")));
        }
        rawOptions = collectionOptions(colSchema, rawOptions);

        Connection conn = null;
        try {
            conn = database.connection();
            String physicalName = physicalTableName(colSchema);

            database.dsl(conn)
                    .insertInto(qt("_collections"))
                    .columns(
                            qfs("id"), qfs("name"), qfs("type"), qfs("schema"),
                            qfs("indexes"),
                            qfi("system"), qfs("createRule"), qfs("listRule"),
                            qfs("viewRule"), qfs("updateRule"), qfs("deleteRule"), qfs("options"),
                            qfs("created"), qfs("updated")
                    )
                    .values(
                            colSchema.id, colSchema.name, colSchema.type,
                            mapper.writeValueAsString(colSchema.fields),
                            mapper.writeValueAsString(colSchema.indexes),
                            colSchema.system ? 1 : 0,
                            colSchema.createRule, colSchema.listRule, colSchema.viewRule,
                            colSchema.updateRule, colSchema.deleteRule,
                            mapper.writeValueAsString(rawOptions),
                            colSchema.created, colSchema.updated
                    )
                    .execute();

            if ("view".equals(colSchema.type)) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE VIEW " + qi(physicalName) + " AS " + normalizedViewSelect(colSchema));
                }
            } else {
                var createTable = database.dsl(conn)
                        .createTable(DSL.name(physicalName))
                        .column(DSL.name("id"), SQLDataType.VARCHAR(255).nullable(false))
                        .column(DSL.name("created"), SQLDataType.VARCHAR(64))
                        .column(DSL.name("updated"), SQLDataType.VARCHAR(64));
                for (FieldSchema field : colSchema.fields) {
                    if ("id".equals(field.name)) {
                        continue;
                    }
                    createTable = createTable.column(DSL.name(field.name), FieldTypeMapping.sqlTypeForField(field));
                }
                createTable.constraints(DSL.constraint(DSL.name(PhysicalTableNames.primaryKeyName(database, physicalName))).primaryKey(DSL.name("id")))
                        .execute();
                createIndexes(conn, colSchema.indexes, physicalName, colSchema.fields, "Failed to create collection.");
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
        return database.transactional(() -> updateCollectionInternal(collection, body));
    }

    private CollectionSchema updateCollectionInternal(String collection, JsonNode body) {
        CollectionSchema currentSchema = getCollectionSchema(collection);
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
        mergeCollectionPatch(currentSchema, newSchema, body);
        if (body.has("fields") || body.has("schema")) {
            newSchema.fields = SchemaIdSupport.canonicalizeSubmittedFields(newSchema.fields);
        }
        SchemaIdSupport.assignMissingFieldIds(newSchema.fields, currentSchema.fields);
        if (!"view".equals(currentSchema.type) && (body.has("fields") || body.has("schema"))) {
            CollectionFieldProtection.validateSystemFieldUpdate(
                    currentSchema.fields,
                    newSchema.fields,
                    "Failed to update collection."
            );
        }
        CollectionResponseSupport.preserveOAuth2ClientSecrets(currentSchema.oauth2, newSchema.oauth2);
        normalizeCollectionSchema(newSchema, rawOptions, true);
        AuthCollectionConfigMerge.mergeSubmitted(mapper, newSchema, currentSchema, body);
        if ("view".equals(newSchema.type)) {
            prepareViewCollection(newSchema, "Failed to update collection.");
        }
        CollectionModelValidation.validate(
                currentSchema,
                newSchema,
                body,
                allCollectionSchemas(),
                this::physicalTableExists,
                "Failed to update collection."
        );
        CollectionRuleSupport.validate(
                newSchema,
                "Failed to update collection.",
                identifier -> resolveRuleCollection(newSchema, identifier)
        );
        newSchema.updated = Instant.now().toString();
        newSchema.indexes = CollectionIndexSupport.normalize(
                newSchema,
                collectionIndexNames(currentSchema.id),
                "Failed to update collection."
        );
        AuthCollectionConfigValidation.validate(newSchema, "Failed to update collection.");
        AuthSystemCollections.applySaveInvariants(newSchema);
        if ("auth".equals(newSchema.type) && authRuleChanged(currentSchema.authRule, newSchema.authRule)) {
            newSchema.authToken.secret = IdGenerator.secret();
        }
        rawOptions = collectionOptions(newSchema, rawOptions);

        Connection conn = null;
        try {
            conn = database.connection();
            String oldSchemaJson = null;
            String storedName = null;

            Record rs = database.dsl(conn)
                    .select(qfs("name"), qfs("schema"))
                    .from(qt("_collections"))
                    .where(collectionCondition(collection))
                    .fetchOne();
            if (rs != null) {
                storedName = rs.get(qfs("name"));
                oldSchemaJson = rs.get(qfs("schema"));
            }

            if (storedName == null) throw new ApiException(404, "Collection not found.");
            String physicalName = PhysicalTableNames.tableName(database, storedName);
            String nextPhysicalName = physicalTableName(newSchema);

            if ("view".equals(newSchema.type)) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("DROP VIEW IF EXISTS " + qi(physicalName));
                    stmt.execute("CREATE VIEW " + qi(nextPhysicalName) + " AS " + normalizedViewSelect(newSchema));
                }
            } else {
                List<FieldSchema> oldFields = new ArrayList<>();
                if (oldSchemaJson != null && !oldSchemaJson.isBlank()) {
                    oldFields = mapper.readValue(oldSchemaJson, new TypeReference<List<FieldSchema>>() {});
                }

                DSLContext dsl = database.dsl(conn);
                if (!physicalName.equals(nextPhysicalName)) {
                    renameTable(dsl, physicalName, nextPhysicalName);
                    physicalName = nextPhysicalName;
                }

                dropRemovedIndexes(
                        conn,
                        currentSchema.indexes,
                        newSchema.indexes,
                        physicalName,
                        "Failed to update collection."
                );

                List<String> oldNames = oldFields.stream().map(f -> f.name).toList();
                List<String> newNames = newSchema.fields.stream().map(f -> f.name).toList();

                for (FieldSchema nf : newSchema.fields) {
                    if (!"id".equals(nf.name)
                            && !oldNames.contains(nf.name)
                            && !columnExists(dsl, physicalName, nf.name)) {
                        dsl.alterTable(DSL.name(physicalName))
                                .add(DSL.name(nf.name), FieldTypeMapping.sqlTypeForField(nf))
                                .execute();
                    }
                }
                for (FieldSchema of : oldFields) {
                    if (!newNames.contains(of.name) && !isRequiredPhysicalColumn(of.name)) {
                        dsl.alterTable(DSL.name(physicalName))
                                .drop(DSL.name(of.name))
                                .execute();
                    }
                }

                createAddedIndexes(
                        conn,
                        currentSchema.indexes,
                        newSchema.indexes,
                        physicalName,
                        newSchema.fields,
                        "Failed to update collection."
                );
            }

            database.dsl(conn)
                    .update(qt("_collections"))
                    .set(qfs("name"), newSchema.name)
                    .set(qfs("type"), newSchema.type)
                    .set(qfs("schema"), mapper.writeValueAsString(newSchema.fields))
                    .set(qfs("indexes"), mapper.writeValueAsString(newSchema.indexes))
                    .set(qfi("system"), newSchema.system ? 1 : 0)
                    .set(qfs("createRule"), newSchema.createRule)
                    .set(qfs("listRule"), newSchema.listRule)
                    .set(qfs("viewRule"), newSchema.viewRule)
                    .set(qfs("updateRule"), newSchema.updateRule)
                    .set(qfs("deleteRule"), newSchema.deleteRule)
                    .set(qfs("options"), mapper.writeValueAsString(rawOptions))
                    .set(qfs("updated"), newSchema.updated)
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

    private boolean isRequiredPhysicalColumn(String name) {
        return "id".equals(name) || "created".equals(name) || "updated".equals(name);
    }

    private void renameTable(DSLContext dsl, String currentName, String nextName) {
        if (currentName.equalsIgnoreCase(nextName)) {
            String temporaryName = "_temp_" + IdGenerator.suffix();
            dsl.alterTable(DSL.name(currentName))
                    .renameTo(DSL.name(temporaryName))
                    .execute();
            dsl.alterTable(DSL.name(temporaryName))
                    .renameTo(DSL.name(nextName))
                    .execute();
            return;
        }
        dsl.alterTable(DSL.name(currentName))
                .renameTo(DSL.name(nextName))
                .execute();
    }

    public void deleteCollection(String collection) {
        Connection conn = null;
        try {
            conn = database.connection();
            String logicalName = null;
            String collectionId = null;
            String type = null;
            boolean system = false;

            Record rs = database.dsl(conn)
                    .select(qfs("id"), qfs("name"), qfs("type"), qfi("system"))
                    .from(qt("_collections"))
                    .where(collectionCondition(collection))
                    .fetchOne();
            if (rs != null) {
                collectionId = rs.get(qfs("id"));
                logicalName = rs.get(qfs("name"));
                type = rs.get(qfs("type"));
                system = Objects.equals(rs.get(qfi("system")), 1);
            }

            if (logicalName == null) {
                throw new ApiException(404, "Collection not found.");
            }
            String physicalName = PhysicalTableNames.tableName(database, logicalName);
            if (system) {
                throw new ApiException(400, "System collections cannot be deleted.");
            }

            database.dsl(conn)
                    .deleteFrom(qt("_collections"))
                    .where(collectionCondition(collection))
                    .execute();
            deleteAuthSupportRecords(database.dsl(conn), collectionId);

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
                    .deleteFrom(qt(physicalTableName(schema)))
                    .execute();
            if ("auth".equals(schema.type)) {
                deleteAuthSupportRecords(database.dsl(), schema.id);
            }
        } catch (DataAccessException e) {
            throw new ApiException(400, "Failed to truncate collection: " + e.getMessage());
        }
    }

    private void deleteAuthSupportRecords(DSLContext dsl, String collectionId) {
        for (String table : List.of("_authOrigins", "_externalAuths", "_mfas", "_otps")) {
            dsl.deleteFrom(qt(table))
                    .where(qfs("collectionRef").eq(collectionId))
                    .execute();
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
        List<CollectionSchema> existing = allCollectionSchemas();
        Set<String> reservedIds = new LinkedHashSet<>();
        for (JsonNode item : collectionsNode) {
            try {
                CollectionSchema collection = mapper.treeToValue(item, CollectionSchema.class);
                collection.fields = SchemaIdSupport.canonicalizeSubmittedFields(collection.fields);
                CollectionSchema current = findExistingCollectionForImport(collection);
                if (current != null) {
                    if (collection.id == null || collection.id.isBlank()) {
                        collection.id = current.id;
                    }
                    collection.created = current.created;
                    collection.updated = current.updated;
                    SchemaIdSupport.assignMissingFieldIds(collection.fields, current.fields);
                    if (!"view".equals(current.type)) {
                        CollectionFieldProtection.validateSystemFieldUpdate(
                                current.fields,
                                collection.fields,
                                "Failed to import collections."
                        );
                    }
                } else if (collection.id == null || collection.id.isBlank()) {
                    String type = collection.type == null || collection.type.isBlank()
                            ? "base"
                            : collection.type.trim().toLowerCase(java.util.Locale.ROOT);
                    collection.id = SchemaIdSupport.nextCollectionId(
                            type,
                            collection.name,
                            candidate -> collectionIdExists(candidate) || reservedIds.contains(candidate)
                    );
                    collection.created = Instant.now().toString();
                    collection.updated = collection.created;
                }
                normalizeCollectionSchema(collection, item.has("options")
                        ? mapper.convertValue(item.get("options"), new TypeReference<Map<String, Object>>() {})
                        : Map.of(), true);
                AuthCollectionConfigMerge.mergeSubmitted(
                        mapper,
                        collection,
                        current == null ? new CollectionSchema() : current,
                        item
                );
                if ("view".equals(collection.type)) {
                    prepareViewCollection(collection, "Failed to import collections.");
                }
                List<CollectionSchema> validationScope = new ArrayList<>(existing);
                validationScope.addAll(newOrUpdated);
                CollectionModelValidation.validate(
                        current,
                        collection,
                        item,
                        validationScope,
                        this::physicalTableExists,
                        "Failed to import collections."
                );
                newOrUpdated.add(collection);
                reservedIds.add(collection.id);
            } catch (IOException e) {
                throw new ApiException(400, "Failed to import collections.",
                        ApiErrors.invalidField("collections", "Invalid collection payload."));
            }
        }
        boolean deleteMissing = body.path("deleteMissing").asBoolean(false);
        Map<String, CollectionSchema> availableCollections = new LinkedHashMap<>();
        existing.stream()
                .filter(collection -> !deleteMissing || collection.system)
                .forEach(collection -> {
                    availableCollections.put(collection.id, collection);
                    availableCollections.put(collection.name, collection);
                });
        for (CollectionSchema imported : newOrUpdated) {
            availableCollections.put(imported.id, imported);
            availableCollections.put(imported.name, imported);
        }
        for (CollectionSchema imported : newOrUpdated) {
            CollectionRuleSupport.validate(
                    imported,
                    "Failed to import collections.",
                    availableCollections::get
            );
        }
        Set<String> importedIndexNames = new LinkedHashSet<>();
        for (CollectionSchema imported : newOrUpdated) {
            CollectionSchema current = existing.stream()
                    .filter(item -> Objects.equals(item.id, imported.id) || Objects.equals(item.name, imported.name))
                    .findFirst()
                    .orElse(null);
            Set<String> externalNames = collectionIndexNames(current == null ? null : current.id);
            externalNames.addAll(importedIndexNames);
            imported.indexes = CollectionIndexSupport.normalize(
                    imported,
                    externalNames,
                    "Failed to import collections."
            );
            AuthCollectionConfigValidation.validate(imported, "Failed to import collections.");
            AuthSystemCollections.applySaveInvariants(imported);
            for (String index : imported.indexes) {
                String name = CollectionIndexSupport.indexName(index);
                if (!name.isBlank()) {
                    importedIndexNames.add(name);
                }
            }
        }
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
            return Map.of(
                    "collections", newOrUpdated,
                    "deletedCollections", deleted,
                    "migrationPlan", migrationPlan(existing, newOrUpdated, deleted)
            );
        }

        importRuleCollections.set(availableCollections);
        try {
            return database.transactional(() -> {
                for (CollectionSchema c : newOrUpdated) {
                    boolean exists;
                    try {
                        getCollection(c.id != null ? c.id : c.name, Map.of());
                        exists = true;
                    } catch (ApiException e) {
                        if (e.status() != 404) {
                            throw e;
                        }
                        exists = false;
                    }
                    if (exists) {
                        updateCollection(c.id != null ? c.id : c.name, mapper.valueToTree(c));
                    } else {
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
        } finally {
            importRuleCollections.remove();
        }
    }

    private List<Map<String, Object>> migrationPlan(List<CollectionSchema> existing, List<CollectionSchema> desired, List<String> deleted) {
        List<Map<String, Object>> plan = new ArrayList<>();
        for (CollectionSchema next : desired) {
            CollectionSchema current = existing.stream()
                    .filter(item -> Objects.equals(item.id, next.id) || Objects.equals(item.name, next.name))
                    .findFirst()
                    .orElse(null);
            plan.addAll(SchemaMigrationPlanner.plan(current, next, database::quoteIdentifier, database.engine()));
        }
        for (String deletedName : deleted) {
            CollectionSchema current = existing.stream()
                    .filter(item -> Objects.equals(item.name, deletedName))
                    .findFirst()
                    .orElse(null);
            plan.addAll(SchemaMigrationPlanner.plan(current, null, database::quoteIdentifier, database.engine()));
        }
        return plan;
    }

    private CollectionSchema findExistingCollectionForImport(CollectionSchema imported) {
        String identifier = imported.id != null && !imported.id.isBlank() ? imported.id : imported.name;
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        try {
            return getCollectionSchema(identifier);
        } catch (ApiException e) {
            if (e.status() == 404) {
                return null;
            }
            throw e;
        }
    }

    public Map<String, Object> collectionScaffolds() {
        Map<String, Object> base = collectionMap(scaffoldCollection("base"));
        Map<String, Object> auth = collectionMap(scaffoldCollection("auth"));
        Map<String, Object> view = collectionMap(scaffoldCollection("view"));
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
            return previewViewQuery(query, MAX_VIEW_ROWS);
        } catch (RuntimeException e) {
            String message = "Invalid view query. Raw error: \n"
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            throw new ApiException(400, message, Map.of());
        }
    }

    private Map<String, Object> previewViewQuery(String query, int sampleSize) {
        String statement = ViewQuerySupport.normalizeSingleSelect(query);
        var result = database.dsl().fetch(
                "SELECT * FROM (" + statement + ") AS pb_dry_run LIMIT " + sampleSize
        );
        List<ViewQuerySupport.Column> columns = new ArrayList<>();
        for (var field : result.fields()) {
            columns.add(new ViewQuerySupport.Column(
                    field.getName(),
                    ViewQuerySupport.typeForJavaClass(field.getName(), field.getType())
            ));
        }
        List<List<Object>> rows = new ArrayList<>();
        for (org.jooq.Record rowRecord : result) {
            List<Object> row = new ArrayList<>();
            for (int fieldIndex = 0; fieldIndex < result.fields().length; fieldIndex++) {
                row.add(rowRecord.get(fieldIndex));
            }
            rows.add(row);
        }
        return ViewQuerySupport.result(columns, rows);
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
                            qfs("indexes"), qfs("created"), qfs("updated"),
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
                col.created = rs.get(qfs("created"));
                col.updated = rs.get(qfs("updated"));
                String schemaJson = rs.get(qfs("schema"));
                if (schemaJson != null && !schemaJson.isBlank()) {
                    col.fields = mapper.readValue(schemaJson, new TypeReference<List<FieldSchema>>() {});
                }
                String indexesJson = rs.get(qfs("indexes"));
                if (indexesJson != null && !indexesJson.isBlank()) {
                    col.indexes = mapper.readValue(indexesJson, new TypeReference<List<String>>() {});
                }
                String optionsJson = rs.get(qfs("options"));
                Map<String, Object> rawOptions = Map.of();
                if (optionsJson != null && !optionsJson.isBlank()) {
                    rawOptions = mapper.readValue(optionsJson, new TypeReference<Map<String, Object>>() {});
                }
                normalizeCollectionSchema(col, rawOptions, false);
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

    private CollectionSchema resolveRuleCollection(CollectionSchema candidate, String nameOrId) {
        if (candidate != null && (Objects.equals(candidate.id, nameOrId) || Objects.equals(candidate.name, nameOrId))) {
            return candidate;
        }
        Map<String, CollectionSchema> scoped = importRuleCollections.get();
        if (scoped != null) {
            CollectionSchema collection = scoped.get(nameOrId);
            if (collection != null) {
                return collection;
            }
        }
        try {
            return getCollectionSchema(nameOrId);
        } catch (ApiException e) {
            if (e.status() == 404) {
                return null;
            }
            throw e;
        }
    }

    private List<CollectionSchema> allCollectionSchemas() {
        return database.dsl()
                .select(qfs("id"))
                .from(qt("_collections"))
                .fetch(qfs("id"), String.class)
                .stream()
                .map(this::getCollectionSchema)
                .toList();
    }

    private boolean physicalTableExists(String name) {
        String physicalName = PhysicalTableNames.tableName(database, name);
        return name != null && database.dsl().meta().getTables().stream()
                .anyMatch(table -> table.getName().equalsIgnoreCase(physicalName));
    }

    private Set<String> collectionIndexNames(String excludedCollectionId) {
        Set<String> names = new LinkedHashSet<>();
        var rows = database.dsl()
                .select(qfs("id"), qfs("indexes"))
                .from(qt("_collections"))
                .fetch();
        for (Record row : rows) {
            if (Objects.equals(row.get(qfs("id")), excludedCollectionId)) {
                continue;
            }
            String rawIndexes = row.get(qfs("indexes"));
            if (rawIndexes == null || rawIndexes.isBlank()) {
                continue;
            }
            try {
                for (String index : mapper.readValue(rawIndexes, new TypeReference<List<String>>() {})) {
                    String name = CollectionIndexSupport.indexName(index);
                    if (!name.isBlank()) {
                        names.add(name);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("failed to read collection index metadata", e);
            }
        }
        return names;
    }

    private void createIndexes(
            Connection conn,
            List<String> indexes,
            String table,
            List<FieldSchema> fields,
            String message
    ) {
        List<String> safeIndexes = indexes == null ? List.of() : indexes;
        for (int i = 0; i < safeIndexes.size(); i++) {
            executeIndexSql(
                    conn,
                    CollectionIndexSupport.createSql(
                            safeIndexes.get(i),
                            table,
                            database::quoteIdentifier,
                            database.engine(),
                            fields
                    ),
                    message,
                    i
            );
        }
    }

    private void dropRemovedIndexes(
            Connection conn,
            List<String> current,
            List<String> desired,
            String table,
            String message
    ) {
        List<String> desiredIndexes = desired == null ? List.of() : desired;
        List<String> currentIndexes = current == null ? List.of() : current;
        for (int i = 0; i < currentIndexes.size(); i++) {
            String index = currentIndexes.get(i);
            if (!desiredIndexes.contains(index)) {
                executeIndexSql(
                        conn,
                        CollectionIndexSupport.dropSql(index, table, database.engine(), database::quoteIdentifier),
                        message,
                        i
                );
            }
        }
    }

    private void createAddedIndexes(
            Connection conn,
            List<String> current,
            List<String> desired,
            String table,
            List<FieldSchema> fields,
            String message
    ) {
        List<String> currentIndexes = current == null ? List.of() : current;
        List<String> desiredIndexes = desired == null ? List.of() : desired;
        for (int i = 0; i < desiredIndexes.size(); i++) {
            String index = desiredIndexes.get(i);
            if (!currentIndexes.contains(index)) {
                executeIndexSql(
                        conn,
                        CollectionIndexSupport.createSql(
                                index,
                                table,
                                database::quoteIdentifier,
                                database.engine(),
                                fields
                        ),
                        message,
                        i
                );
            }
        }
    }

    private void executeIndexSql(Connection conn, String sql, String message, int index) {
        if (sql == null || sql.isBlank()) {
            throw indexApiException(message, index, "Invalid CREATE INDEX expression.");
        }
        try (Statement statement = conn.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw indexApiException(message, index, e.getMessage() == null ? "Invalid CREATE INDEX expression." : e.getMessage());
        }
    }

    private ApiException indexApiException(String message, int index, String error) {
        return new ApiException(400, message, Map.of(
                "indexes",
                Map.of(String.valueOf(index), ApiErrors.validationError("validation_invalid_index_expression", error))
        ));
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
        String indexesJson = rs.get(qfs("indexes"));
        if (indexesJson != null && !indexesJson.isBlank()) {
            try {
                col.put("indexes", mapper.readValue(indexesJson, List.class));
            } catch (IOException e) {
                col.put("indexes", List.of());
            }
        } else {
            col.put("indexes", List.of());
        }
        col.put("created", rs.get(qfs("created")));
        col.put("updated", rs.get(qfs("updated")));
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
        Map<String, Object> responseOptions = new LinkedHashMap<>();
        options.forEach((key, value) -> responseOptions.put(String.valueOf(key), nullableJsonValue(value)));
        col.put("options", responseOptions);
        if ("auth".equals(col.get("type"))) {
            copyOption(col, responseOptions, "passwordAuth");
            copyOption(col, responseOptions, "otp");
            copyOption(col, responseOptions, "mfa");
            copyOption(col, responseOptions, "oauth2");
            copyOption(col, responseOptions, "authToken");
            copyOption(col, responseOptions, "passwordResetToken");
            copyOption(col, responseOptions, "verificationToken");
            copyOption(col, responseOptions, "emailChangeToken");
            copyOption(col, responseOptions, "fileToken");
            copyOption(col, responseOptions, "authAlert");
            copyOption(col, responseOptions, "verificationTemplate");
            copyOption(col, responseOptions, "resetPasswordTemplate");
            copyOption(col, responseOptions, "confirmEmailChangeTemplate");
            copyOption(col, responseOptions, "authRule");
            copyOption(col, responseOptions, "manageRule");
        }
        if ("view".equals(col.get("type"))) {
            Object viewQuery = options.containsKey("viewQuery") ? options.get("viewQuery") : options.get("query");
            col.put("viewQuery", viewQuery == null ? "" : String.valueOf(viewQuery));
        }
        return CollectionResponseSupport.redactSecrets(col);
    }

    private void copyOption(Map<String, Object> collection, Map<?, ?> options, String key) {
        if (options.containsKey(key)) {
            collection.put(key, options.get(key));
        }
    }

    private Map<String, Object> collectionMap(CollectionSchema collection) {
        Map<String, Object> result = mapper.convertValue(collection, new TypeReference<Map<String, Object>>() {});
        if ("auth".equals(collection.type)) {
            result.put("authRule", nullableJsonValue(collection.authRule));
            result.put("manageRule", nullableJsonValue(collection.manageRule));
        }
        return CollectionResponseSupport.redactSecrets(result);
    }

    private Object nullableJsonValue(Object value) {
        return value == null ? NullNode.instance : value;
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

    private void mergeCollectionPatch(CollectionSchema current, CollectionSchema patch, JsonNode body) {
        patch.id = current.id;
        patch.system = current.system;
        patch.created = current.created;
        patch.updated = current.updated;
        if (!body.has("name")) patch.name = current.name;
        if (!body.has("type")) patch.type = current.type;
        if (!body.has("fields") && !body.has("schema")) patch.fields = current.fields;
        if (!body.has("indexes")) patch.indexes = current.indexes;
        if (!body.has("listRule")) patch.listRule = current.listRule;
        if (!body.has("viewRule")) patch.viewRule = current.viewRule;
        if (!body.has("createRule")) patch.createRule = current.createRule;
        if (!body.has("updateRule")) patch.updateRule = current.updateRule;
        if (!body.has("deleteRule")) patch.deleteRule = current.deleteRule;
        if (!body.has("viewQuery") && !hasOption(body, "viewQuery") && !hasOption(body, "query")) {
            patch.viewQuery = current.viewQuery;
        }
        if (!body.has("passwordAuth") && !hasOption(body, "passwordAuth")) patch.passwordAuth = current.passwordAuth;
        if (!body.has("otp") && !hasOption(body, "otp")) patch.otp = current.otp;
        if (!body.has("mfa") && !hasOption(body, "mfa")) patch.mfa = current.mfa;
        if (!body.has("oauth2") && !hasOption(body, "oauth2")) patch.oauth2 = current.oauth2;
        if (!body.has("authToken") && !hasOption(body, "authToken")) patch.authToken = current.authToken;
        if (!body.has("passwordResetToken") && !hasOption(body, "passwordResetToken")) patch.passwordResetToken = current.passwordResetToken;
        if (!body.has("verificationToken") && !hasOption(body, "verificationToken")) patch.verificationToken = current.verificationToken;
        if (!body.has("emailChangeToken") && !hasOption(body, "emailChangeToken")) patch.emailChangeToken = current.emailChangeToken;
        if (!body.has("fileToken") && !hasOption(body, "fileToken")) patch.fileToken = current.fileToken;
        if (!body.has("authAlert") && !hasOption(body, "authAlert")) patch.authAlert = current.authAlert;
        if (!body.has("verificationTemplate") && !hasOption(body, "verificationTemplate")) patch.verificationTemplate = current.verificationTemplate;
        if (!body.has("resetPasswordTemplate") && !hasOption(body, "resetPasswordTemplate")) patch.resetPasswordTemplate = current.resetPasswordTemplate;
        if (!body.has("confirmEmailChangeTemplate") && !hasOption(body, "confirmEmailChangeTemplate")) patch.confirmEmailChangeTemplate = current.confirmEmailChangeTemplate;
        if (!body.has("authRule") && !hasOption(body, "authRule")) patch.authRule = current.authRule;
        if (!body.has("manageRule") && !hasOption(body, "manageRule")) patch.manageRule = current.manageRule;
    }

    private boolean hasOption(JsonNode body, String name) {
        return body.has("options") && body.path("options").isObject() && body.path("options").has(name);
    }

    private void prepareViewCollection(CollectionSchema schema, String message) {
        schema.fields = new ArrayList<>();
        if (schema.viewQuery == null || schema.viewQuery.isBlank()) {
            throw new ApiException(400, message, ApiErrors.requiredField("viewQuery"));
        }
        try {
            Map<String, Object> preview = previewViewQuery(schema.viewQuery, MAX_VIEW_ROWS);
            schema.fields = mapper.convertValue(preview.get("fields"), new TypeReference<>() {
            });
            for (FieldSchema field : schema.fields) {
                field.type = field.type == null || field.type.isBlank()
                        ? "text"
                        : field.type.trim().toLowerCase(java.util.Locale.ROOT);
            }
            SchemaIdSupport.assignMissingFieldIds(schema.fields, List.of());
        } catch (RuntimeException e) {
            String rawError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (rawError.length() > 500) {
                rawError = rawError.substring(0, 500);
            }
            throw new ApiException(
                    400,
                    message,
                    ApiErrors.fieldError("viewQuery", "validation_invalid_view_query", "Invalid query - " + rawError)
            );
        }
    }

    private String normalizedViewSelect(CollectionSchema schema) {
        return ViewQuerySupport.normalizeSingleSelect(schema.viewQuery);
    }

    @SuppressWarnings("unchecked")
    private void normalizeCollectionSchema(CollectionSchema schema, Map<String, Object> rawOptions, boolean ensureSecrets) {
        if (schema.type == null || schema.type.isBlank()) {
            schema.type = "base";
        }
        if (schema.fields == null) {
            schema.fields = new ArrayList<>();
        }
        for (FieldSchema field : schema.fields) {
            if (field != null) {
                field.type = field.type == null || field.type.isBlank()
                        ? "text"
                        : field.type.trim().toLowerCase(java.util.Locale.ROOT);
            }
        }
        if (schema.indexes == null) {
            schema.indexes = new ArrayList<>();
        }
        if (rawOptions != null) {
            if ((schema.viewQuery == null || schema.viewQuery.isBlank()) && rawOptions.containsKey("viewQuery")) {
                schema.viewQuery = String.valueOf(rawOptions.get("viewQuery"));
            }
            if ((schema.viewQuery == null || schema.viewQuery.isBlank()) && rawOptions.containsKey("query")) {
                schema.viewQuery = String.valueOf(rawOptions.get("query"));
            }
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
            if (rawOptions.containsKey("authAlert")) {
                schema.authAlert = mapper.convertValue(rawOptions.get("authAlert"), CollectionSchema.AuthAlertConfig.class);
            }
            if (rawOptions.containsKey("verificationTemplate")) {
                schema.verificationTemplate = mapper.convertValue(rawOptions.get("verificationTemplate"), CollectionSchema.EmailTemplate.class);
            }
            if (rawOptions.containsKey("resetPasswordTemplate")) {
                schema.resetPasswordTemplate = mapper.convertValue(rawOptions.get("resetPasswordTemplate"), CollectionSchema.EmailTemplate.class);
            }
            if (rawOptions.containsKey("confirmEmailChangeTemplate")) {
                schema.confirmEmailChangeTemplate = mapper.convertValue(rawOptions.get("confirmEmailChangeTemplate"), CollectionSchema.EmailTemplate.class);
            }
            if (rawOptions.containsKey("authRule")) {
                schema.authRule = rawOptions.get("authRule") == null ? null : String.valueOf(rawOptions.get("authRule"));
            }
            if (rawOptions.containsKey("manageRule")) {
                schema.manageRule = rawOptions.get("manageRule") == null ? null : String.valueOf(rawOptions.get("manageRule"));
            }
        }
        if (schema.passwordAuth == null) {
            schema.passwordAuth = new CollectionSchema.PasswordAuthConfig();
        }
        normalizePasswordAuthConfig(schema.passwordAuth);
        if (schema.otp == null) {
            schema.otp = new CollectionSchema.OtpConfig();
        }
        schema.otp.emailTemplate = normalizeEmailTemplate(schema.otp.emailTemplate, CollectionSchema.EmailTemplate.otp());
        if (schema.mfa == null) {
            schema.mfa = new CollectionSchema.MfaConfig();
        }
        if (schema.authAlert == null) {
            schema.authAlert = new CollectionSchema.AuthAlertConfig();
        }
        schema.authAlert.emailTemplate = normalizeEmailTemplate(schema.authAlert.emailTemplate, CollectionSchema.EmailTemplate.authAlert());
        schema.verificationTemplate = normalizeEmailTemplate(schema.verificationTemplate, CollectionSchema.EmailTemplate.verification());
        schema.resetPasswordTemplate = normalizeEmailTemplate(schema.resetPasswordTemplate, CollectionSchema.EmailTemplate.passwordReset());
        schema.confirmEmailChangeTemplate = normalizeEmailTemplate(schema.confirmEmailChangeTemplate, CollectionSchema.EmailTemplate.emailChange());
        if (schema.oauth2 == null) {
            schema.oauth2 = new CollectionSchema.OAuth2Config();
        }
        normalizeOAuth2Config(schema.oauth2);
        schema.authToken = normalizeTokenConfig(schema.authToken, CollectionSchema.DEFAULT_AUTH_TOKEN_DURATION, ensureSecrets);
        schema.passwordResetToken = normalizeTokenConfig(schema.passwordResetToken, CollectionSchema.DEFAULT_PASSWORD_RESET_TOKEN_DURATION, ensureSecrets);
        schema.verificationToken = normalizeTokenConfig(schema.verificationToken, CollectionSchema.DEFAULT_VERIFICATION_TOKEN_DURATION, ensureSecrets);
        schema.emailChangeToken = normalizeTokenConfig(schema.emailChangeToken, CollectionSchema.DEFAULT_EMAIL_CHANGE_TOKEN_DURATION, ensureSecrets);
        schema.fileToken = normalizeTokenConfig(schema.fileToken, CollectionSchema.DEFAULT_FILE_TOKEN_DURATION, ensureSecrets);
        if ("base".equals(schema.type)) {
            SchemaIdSupport.ensureBaseIdField(schema.fields);
        } else if ("auth".equals(schema.type)) {
            AuthCollectionFields.normalize(schema);
        }
        OAuth2FieldMappingSupport.normalize(schema);
        SchemaIdSupport.assignMissingFieldIds(schema.fields, List.of());
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
            options.put("authAlert", mapper.convertValue(schema.authAlert, Map.class));
            options.put("verificationTemplate", mapper.convertValue(schema.verificationTemplate, Map.class));
            options.put("resetPasswordTemplate", mapper.convertValue(schema.resetPasswordTemplate, Map.class));
            options.put("confirmEmailChangeTemplate", mapper.convertValue(schema.confirmEmailChangeTemplate, Map.class));
            options.put("authRule", schema.authRule == null ? NullNode.instance : schema.authRule);
            options.put("manageRule", schema.manageRule == null ? NullNode.instance : schema.manageRule);
        }
        if ("view".equals(schema.type)) {
            options.put("viewQuery", schema.viewQuery);
            options.put("query", schema.viewQuery);
        }
        return options;
    }

    private CollectionSchema.EmailTemplate normalizeEmailTemplate(
            CollectionSchema.EmailTemplate template,
            CollectionSchema.EmailTemplate fallback
    ) {
        CollectionSchema.EmailTemplate normalized = template == null ? new CollectionSchema.EmailTemplate() : template;
        if (normalized.subject == null || normalized.subject.isBlank()) {
            normalized.subject = fallback.subject;
        }
        if (normalized.body == null || normalized.body.isBlank()) {
            normalized.body = fallback.body;
        }
        return normalized;
    }

    private boolean columnExists(DSLContext dsl, String table, String column) {
        // Do not probe a column by selecting it. On PostgreSQL an unknown
        // column aborts the surrounding transaction, so the following ALTER
        // TABLE then fails even though adding that column is valid. JDBC
        // metadata provides the same answer without issuing an erroring SQL
        // statement and works inside the current transaction connection.
        return dsl.connectionResult(connection -> {
            String catalog = database.engine() == JooqDatabase.Engine.MYSQL
                    ? connection.getCatalog()
                    : null;
            String schema = database.engine() == JooqDatabase.Engine.POSTGRES
                    ? connection.getSchema()
                    : null;
            try (ResultSet columns = connection.getMetaData().getColumns(catalog, schema, table, column)) {
                return columns.next();
            }
        });
    }

    private void normalizePasswordAuthConfig(CollectionSchema.PasswordAuthConfig config) {
        if (config.identityFields == null) {
            config.identityFields = new ArrayList<>(List.of("email"));
            return;
        }
        LinkedHashSet<String> identities = new LinkedHashSet<>();
        for (String field : config.identityFields) {
            String value = field == null ? "" : field.trim();
            if (!value.isBlank()) {
                identities.add(value);
            }
        }
        config.identityFields = new ArrayList<>(identities);
    }

    private void normalizeOAuth2Config(CollectionSchema.OAuth2Config config) {
        if (config.providers == null) {
            config.providers = new ArrayList<>();
            return;
        }
        List<CollectionSchema.OAuth2ProviderConfig> normalizedProviders = new ArrayList<>();
        for (CollectionSchema.OAuth2ProviderConfig provider : config.providers) {
            if (provider == null) {
                normalizedProviders.add(null);
                continue;
            }
            OAuth2ProviderManager.ProviderMetadata metadata = OAuth2ProviderManager.providerMetadata(provider.name);
            CollectionSchema.OAuth2ProviderConfig normalized = new CollectionSchema.OAuth2ProviderConfig();
            normalized.name = metadata == null ? provider.name : metadata.name();
            normalized.clientId = textSetting(provider.clientId);
            normalized.clientSecret = textSetting(provider.clientSecret);
            normalized.authURL = textSetting(provider.authURL);
            normalized.tokenURL = textSetting(provider.tokenURL);
            normalized.userInfoURL = textSetting(provider.userInfoURL);
            normalized.displayName = textSetting(provider.displayName);
            normalized.pkce = provider.pkce;
            normalized.scopes = provider.scopes == null
                    ? new ArrayList<>()
                    : provider.scopes.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(scope -> !scope.isBlank())
                    .collect(Collectors.toCollection(ArrayList::new));
            normalized.extra = provider.extra == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(provider.extra);
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
        if ("base".equals(type)) {
            SchemaIdSupport.ensureBaseIdField(collection.fields);
        } else if ("auth".equals(type)) {
            AuthCollectionFields.normalize(collection);
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

    private CollectionSchema.TokenConfig normalizeTokenConfig(
            CollectionSchema.TokenConfig config,
            long fallbackDuration,
            boolean ensureSecret
    ) {
        CollectionSchema.TokenConfig normalized = config == null ? new CollectionSchema.TokenConfig() : config;
        normalized.duration = normalized.duration > 0 ? normalized.duration : fallbackDuration;
        if (ensureSecret && (normalized.secret == null || normalized.secret.isBlank())) {
            normalized.secret = IdGenerator.secret();
        } else if (normalized.secret == null) {
            normalized.secret = "";
        }
        return normalized;
    }

    public void ensureAuthTokenSecrets() {
        var rows = database.dsl()
                .select(qfs("id"), qfs("options"))
                .from(qt("_collections"))
                .where(qfs("type").eq("auth"))
                .fetch();
        for (Record row : rows) {
            String id = row.get(qfs("id"));
            Map<String, Object> options = new LinkedHashMap<>();
            String raw = row.get(qfs("options"));
            if (raw != null && !raw.isBlank()) {
                try {
                    options.putAll(mapper.readValue(raw, new TypeReference<Map<String, Object>>() {}));
                } catch (IOException ignored) {
                }
            }
            boolean changed = ensureTokenOption(options, "authToken", CollectionSchema.DEFAULT_AUTH_TOKEN_DURATION);
            changed |= ensureTokenOption(options, "passwordResetToken", CollectionSchema.DEFAULT_PASSWORD_RESET_TOKEN_DURATION);
            changed |= ensureTokenOption(options, "verificationToken", CollectionSchema.DEFAULT_VERIFICATION_TOKEN_DURATION);
            changed |= ensureTokenOption(options, "emailChangeToken", CollectionSchema.DEFAULT_EMAIL_CHANGE_TOKEN_DURATION);
            changed |= ensureTokenOption(options, "fileToken", CollectionSchema.DEFAULT_FILE_TOKEN_DURATION);
            if (changed) {
                try {
                    database.dsl()
                            .update(qt("_collections"))
                            .set(qfs("options"), mapper.writeValueAsString(options))
                            .where(qfs("id").eq(id))
                            .execute();
                } catch (IOException e) {
                    throw new IllegalStateException("failed to persist auth token secrets", e);
                }
            }
        }
    }

    public void ensureAuthCollectionFields() {
        DSLContext dsl = database.dsl();
        var rows = dsl.select(qfs("id"), qfs("name"), qfs("schema"), qfs("indexes"))
                .from(qt("_collections"))
                .where(qfs("type").eq("auth"))
                .fetch();
        for (Record row : rows) {
            String id = row.get(qfs("id"));
            String name = row.get(qfs("name"));
            String rawSchema = row.get(qfs("schema"));
            String rawIndexes = row.get(qfs("indexes"));
            List<FieldSchema> previous = new ArrayList<>();
            List<String> previousIndexes = new ArrayList<>();
            if (rawSchema != null && !rawSchema.isBlank()) {
                try {
                    previous = mapper.readValue(rawSchema, new TypeReference<List<FieldSchema>>() {});
                } catch (IOException e) {
                    throw new IllegalStateException("failed to read auth collection fields for " + name, e);
                }
            }
            if (rawIndexes != null && !rawIndexes.isBlank()) {
                try {
                    previousIndexes = mapper.readValue(rawIndexes, new TypeReference<List<String>>() {});
                } catch (IOException e) {
                    throw new IllegalStateException("failed to read auth collection indexes for " + name, e);
                }
            }

            CollectionSchema collection = new CollectionSchema();
            collection.id = id;
            collection.name = name;
            collection.type = "auth";
            collection.fields = new ArrayList<>(previous);
            collection.indexes = new ArrayList<>(previousIndexes);
            AuthCollectionFields.normalize(collection);
            String physicalName = physicalTableName(collection);

            for (FieldSchema field : collection.fields) {
                if (!AuthCollectionFields.isSystemField(field.name)
                        || "id".equals(field.name)
                        || ("_superusers".equals(name) && "password".equals(field.name))) {
                    continue;
                }
                if (!columnExists(dsl, physicalName, field.name)) {
                    dsl.alterTable(DSL.name(physicalName))
                            .add(DSL.name(field.name), FieldTypeMapping.sqlTypeForField(field))
                            .execute();
                }
                if ("emailVisibility".equals(field.name) || "verified".equals(field.name)) {
                    boolean defaultValue = "_superusers".equals(name) && "verified".equals(field.name);
                    org.jooq.Field<Boolean> boolField = DSL.field(DSL.name(field.name), Boolean.class);
                    dsl.update(DSL.table(DSL.name(physicalName)))
                            .set(boolField, defaultValue)
                            .where(boolField.isNull())
                            .execute();
                }
            }

            for (String index : collection.indexes) {
                if (previousIndexes.contains(index)) {
                    continue;
                }
                String sql = CollectionIndexSupport.createSql(
                        index,
                        physicalName,
                        database::quoteIdentifier,
                        database.engine(),
                        collection.fields
                );
                try {
                    dsl.execute(sql);
                } catch (DataAccessException e) {
                    throw new IllegalStateException("failed to create auth collection index for " + name, e);
                }
            }

            if (!mapper.valueToTree(previous).equals(mapper.valueToTree(collection.fields))
                    || !mapper.valueToTree(previousIndexes).equals(mapper.valueToTree(collection.indexes))) {
                try {
                    dsl.update(qt("_collections"))
                            .set(qfs("schema"), mapper.writeValueAsString(collection.fields))
                            .set(qfs("indexes"), mapper.writeValueAsString(collection.indexes))
                            .where(qfs("id").eq(id))
                            .execute();
                } catch (IOException e) {
                    throw new IllegalStateException("failed to persist auth collection fields for " + name, e);
                }
            }
        }
    }

    private boolean ensureTokenOption(Map<String, Object> options, String name, long duration) {
        Map<String, Object> token = options.get(name) instanceof Map<?, ?> existing
                ? new LinkedHashMap<>((Map<String, Object>) existing)
                : new LinkedHashMap<>();
        boolean changed = false;
        Object rawDuration = token.get("duration");
        if (!(rawDuration instanceof Number number) || number.longValue() <= 0) {
            token.put("duration", duration);
            changed = true;
        }
        Object rawSecret = token.get("secret");
        if (rawSecret == null || String.valueOf(rawSecret).isBlank()) {
            token.put("secret", IdGenerator.secret());
            changed = true;
        }
        options.put(name, token);
        return changed;
    }

    private boolean authRuleChanged(String previous, String next) {
        return !Objects.equals(previous, next)
                && !Objects.equals(previous == null ? "" : previous, next == null ? "" : next);
    }

}
