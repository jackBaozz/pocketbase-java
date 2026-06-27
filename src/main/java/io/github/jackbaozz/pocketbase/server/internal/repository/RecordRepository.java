package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.internal.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.FieldValidator;
import io.github.jackbaozz.pocketbase.server.internal.FilterToSqlCompiler;
import io.github.jackbaozz.pocketbase.server.internal.IdGenerator;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;
import io.github.jackbaozz.pocketbase.server.internal.RecordProcessor;
import io.github.jackbaozz.pocketbase.server.internal.RequestPrincipal;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;
import io.github.jackbaozz.pocketbase.server.internal.UploadedFile;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.jooq.exception.DataAccessException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RecordRepository extends BaseRepository {

    private final CollectionRepository collectionRepository;
    private final RecordProcessor.StoreContext storeContext;

    public RecordRepository(JooqDatabase database, ObjectMapper mapper, CollectionRepository collectionRepository, RecordProcessor.StoreContext storeContext) {
        super(database, mapper);
        this.collectionRepository = collectionRepository;
        this.storeContext = storeContext;
    }

    public Map<String, Object> getRecord(String collection, String id, Map<String, String> query, RequestPrincipal principal) {
        collectionRepository.requireCollectionExists(collection);
        CollectionSchema colSchema = collectionRepository.getCollectionSchema(collection);

        org.jooq.Record record = database.dsl()
                .selectFrom(qt(colSchema.name))
                .where(qfs("id").eq(id))
                .fetchOne();
        if (record == null) {
            throw new ApiException(404, "Record not found.");
        }
        return RecordProcessor.process(storeContext, colSchema, record.intoMap(), false, query, principal);
    }

    public Map<String, Object> listRecords(String collection, Map<String, String> query, RequestPrincipal principal) {
        collectionRepository.requireCollectionExists(collection);
        CollectionSchema colSchema = collectionRepository.getCollectionSchema(collection);

        // Filter support
        String filter = query.getOrDefault("filter", "");
        org.jooq.Condition condition = null;
        if (filter != null && !filter.isBlank()) {
            String filterSql = FilterToSqlCompiler.compile(filter);
            condition = DSL.condition(filterSql);
        }

        // Sort support: parse sort string like "-created,title" where "-" prefix means DESC
        String sort = query.getOrDefault("sort", "");
        List<SortField<?>> sortFields = new ArrayList<>();
        if (sort != null && !sort.isBlank()) {
            for (String part : sort.split(",")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.startsWith("-")) {
                    String fieldName = trimmed.substring(1);
                    sortFields.add(qf(fieldName).desc());
                } else {
                    sortFields.add(qf(trimmed).asc());
                }
            }
        }

        // Pagination
        int page = 1;
        int perPage = 100;
        if (query.containsKey("page")) {
            try { page = Math.max(1, Integer.parseInt(query.get("page"))); } catch (NumberFormatException ignored) {}
        }
        if (query.containsKey("perPage")) {
            try { perPage = Math.max(1, Integer.parseInt(query.get("perPage"))); } catch (NumberFormatException ignored) {}
        }

        // Build and execute query
        org.jooq.SelectConditionStep<?> selectWithCondition = condition != null
                ? database.dsl().selectFrom(qt(colSchema.name)).where(condition)
                : database.dsl().selectFrom(qt(colSchema.name)).where(DSL.trueCondition());

        org.jooq.SelectSeekStepN<?> selectWithSort = sortFields.isEmpty()
                ? selectWithCondition.orderBy(sortFields)
                : selectWithCondition.orderBy(sortFields);

        List<Map<String, Object>> items = new ArrayList<>();
        for (org.jooq.Record record : selectWithSort.limit(perPage).offset((page - 1) * perPage).fetch()) {
            Map<String, Object> processed = RecordProcessor.process(storeContext, colSchema, record.intoMap(), false, query, principal);
            if (processed != null && storeContext.canView(colSchema, processed, query, principal)) {
                items.add(processed);
            }
        }

        int totalPages = (items.size() < perPage) ? page : page + 1;
        return Map.of("items", items, "page", page, "perPage", perPage, "totalItems", items.size(), "totalPages", totalPages);
    }

    public Map<String, Object> createRecord(String collection, JsonNode body, Map<String, List<UploadedFile>> files, Map<String, String> query, RequestPrincipal principal) {
        CollectionSchema colSchema = collectionRepository.getCollectionSchema(collection);
        if ("view".equals(colSchema.type)) {
            throw new ApiException(400, "View collections are read-only.");
        }

        Map<String, Object> errors = new LinkedHashMap<>();
        Map<String, Object> recordValues = new LinkedHashMap<>();
        for (FieldSchema field : colSchema.fields) {
            JsonNode val = body.get(field.name);
            if (val == null || val.isMissingNode()) {
                val = mapper.nullNode();
            }
            Object normalized = FieldValidator.normalizeFieldValue(mapper, field, val, false, errors, (col, targetId) -> {
                try {
                    getRecord(col, targetId, Map.of(), principal);
                    return true;
                } catch (ApiException e) {
                    return false;
                }
            });
            if (normalized != FieldValidator.Unchanged.INSTANCE && normalized != null) {
                recordValues.put(field.name, normalized);
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(400, "Record validation failed.", errors);
        }

        String id = body.has("id") ? body.get("id").asText() : IdGenerator.id();
        String now = Instant.now().toString();

        List<String> fields = new ArrayList<>();
        List<Object> values = new ArrayList<>();

        fields.add("id");
        values.add(id);
        fields.add("created");
        values.add(now);
        fields.add("updated");
        values.add(now);

        for (Map.Entry<String, Object> entry : recordValues.entrySet()) {
            if (!"id".equals(entry.getKey())) {
                fields.add(entry.getKey());
                values.add(toStoredValue(entry.getValue()));
            }
        }

        try {
            List<Field<Object>> insertFields = fields.stream().map(this::qf).toList();
            database.dsl()
                    .insertInto(qt(colSchema.name))
                    .columns(insertFields)
                    .values(values)
                    .execute();
        } catch (DataAccessException e) {
            handleSqlConstraintException(e);
            throw new ApiException(400, "Failed to create record: " + e.getMessage());
        }

        return getRecord(collection, id, query, principal);
    }

    public Map<String, Object> updateRecord(String collection, String id, JsonNode body, Map<String, List<UploadedFile>> files, Map<String, String> query, RequestPrincipal principal) {
        CollectionSchema colSchema = collectionRepository.getCollectionSchema(collection);
        if ("view".equals(colSchema.type)) {
            throw new ApiException(400, "View collections are read-only.");
        }

        Map<String, Object> errors = new LinkedHashMap<>();
        Map<String, Object> recordValues = new LinkedHashMap<>();
        for (FieldSchema field : colSchema.fields) {
            JsonNode val = body.get(field.name);
            if (val == null || val.isMissingNode()) {
                val = mapper.nullNode();
            }
            Object normalized = FieldValidator.normalizeFieldValue(mapper, field, val, true, errors, (col, targetId) -> {
                try {
                    getRecord(col, targetId, Map.of(), principal);
                    return true;
                } catch (ApiException e) {
                    return false;
                }
            });
            if (normalized != FieldValidator.Unchanged.INSTANCE && normalized != null) {
                recordValues.put(field.name, normalized);
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(400, "Record validation failed.", errors);
        }

        String now = Instant.now().toString();
        List<String> fields = new ArrayList<>();
        List<Object> values = new ArrayList<>();

        for (Map.Entry<String, Object> entry : recordValues.entrySet()) {
            if (!"id".equals(entry.getKey())) {
                fields.add(entry.getKey());
                values.add(toStoredValue(entry.getValue()));
            }
        }

        fields.add("updated");
        values.add(now);

        try {
            Map<Field<Object>, Object> updates = new LinkedHashMap<>();
            for (int i = 0; i < fields.size(); i++) {
                updates.put(qf(fields.get(i)), values.get(i));
            }
            database.dsl()
                    .update(qt(colSchema.name))
                    .set(updates)
                    .where(qfs("id").eq(id))
                    .execute();
        } catch (DataAccessException e) {
            handleSqlConstraintException(e);
            throw new ApiException(400, "Failed to update record: " + e.getMessage());
        }

        return getRecord(collection, id, query, principal);
    }

    public Map<String, Object> upsertRecord(String collection, String id, JsonNode body, Map<String, List<UploadedFile>> files, Map<String, String> query, RequestPrincipal principal) {
        String collectionName = collectionRepository.getCollectionSchema(collection).name;
        boolean exists = false;
        try {
            exists = database.dsl().fetchExists(
                    database.dsl().selectOne().from(qt(collectionName)).where(qfs("id").eq(id))
            );
        } catch (Exception ignored) {
        }
        if (exists) {
            return updateRecord(collection, id, body, files, query, principal);
        } else {
            if (body != null && body.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("id", id);
            }
            return createRecord(collection, body, files, query, principal);
        }
    }

    public void deleteRecord(String collection, String id, RequestPrincipal principal) {
        collectionRepository.requireCollectionExists(collection);
        CollectionSchema schema = collectionRepository.getCollectionSchema(collection);
        try {
            database.dsl()
                    .deleteFrom(qt(schema.name))
                    .where(qfs("id").eq(id))
                    .execute();
        } catch (DataAccessException e) {
            throw new ApiException(400, "Failed to delete record: " + e.getMessage());
        }
    }

    /**
     * Find a record in an auth collection by email address.
     * Returns null if no record is found (does not throw).
     */
    public Map<String, Object> findRecordByEmail(String collection, String email) {
        CollectionSchema colSchema = collectionRepository.getCollectionSchema(collection);
        if (!"auth".equals(colSchema.type)) {
            throw new ApiException(400, "The collection is not an auth collection.");
        }
        try {
            var record = database.dsl()
                    .selectFrom(qt(colSchema.name))
                    .where(qfs("email").eq(email))
                    .fetchOne();
            if (record == null) return null;
            return record.intoMap();
        } catch (DataAccessException e) {
            return null;
        }
    }

    /**
     * Update specific fields on a record. Used for auth action mutations
     * (password reset, verification, email change, token key rotation).
     */
    public void updateFields(String collection, String recordId, Map<String, Object> fields) {
        CollectionSchema colSchema = collectionRepository.getCollectionSchema(collection);
        try {
            Map<Field<Object>, Object> updates = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                updates.put(qf(entry.getKey()), toStoredValue(entry.getValue()));
            }
            database.dsl()
                    .update(qt(colSchema.name))
                    .set(updates)
                    .where(qfs("id").eq(recordId))
                    .execute();
        } catch (DataAccessException e) {
            throw new ApiException(400, "Failed to update record: " + e.getMessage());
        }
    }

    private Object toStoredValue(Object value) {
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value instanceof Number) {
            return value;
        }
        if (value instanceof String) {
            return value;
        }
        if (value instanceof List<?> || value instanceof Map<?, ?>) {
            try {
                return mapper.writeValueAsString(value);
            } catch (Exception e) {
                return null;
            }
        }
        if (value == null) {
            return null;
        }
        return value.toString();
    }
}
