package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.FieldValidator;
import io.github.jackbaozz.pocketbase.server.internal.IdGenerator;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;
import io.github.jackbaozz.pocketbase.server.internal.RecordProcessor;
import io.github.jackbaozz.pocketbase.server.internal.RequestPrincipal;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;
import io.github.jackbaozz.pocketbase.server.model.UploadedFile;
import org.jooq.Field;
import org.jooq.exception.DataAccessException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
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

        Connection conn = null;
        try {
            conn = database.connection();
            try (PreparedStatement select = conn.prepareStatement("SELECT * FROM " + qi(colSchema.name) + " WHERE id = ?")) {
                select.setString(1, id);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        ResultSetMetaData md = rs.getMetaData();
                        int columns = md.getColumnCount();
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= columns; i++) {
                            row.put(md.getColumnLabel(i), rs.getObject(i));
                        }
                        return RecordProcessor.process(storeContext, colSchema, row, false, query, principal);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            if (conn != null) {
                try {
                    database.closeIfStandalone(conn);
                } catch (SQLException ignored) {
                }
            }
        }
        throw new ApiException(404, "Record not found.");
    }

    public Map<String, Object> listRecords(String collection, Map<String, String> query, RequestPrincipal principal) {
        collectionRepository.requireCollectionExists(collection);
        CollectionSchema colSchema = collectionRepository.getCollectionSchema(collection);

        Connection conn = null;
        try {
            conn = database.connection();
            try (PreparedStatement select = conn.prepareStatement("SELECT * FROM " + qi(colSchema.name));
                 ResultSet rs = select.executeQuery()) {
                List<Map<String, Object>> items = new ArrayList<>();
                ResultSetMetaData md = rs.getMetaData();
                int columns = md.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columns; i++) {
                        row.put(md.getColumnLabel(i), rs.getObject(i));
                    }
                    Map<String, Object> processed = RecordProcessor.process(storeContext, colSchema, row, false, query, principal);
                    if (processed != null && storeContext.canView(colSchema, processed, query, principal)) {
                        items.add(processed);
                    }
                }
                return Map.of("items", items, "page", 1, "perPage", 100, "totalItems", items.size(), "totalPages", 1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            if (conn != null) {
                try {
                    database.closeIfStandalone(conn);
                } catch (SQLException ignored) {
                }
            }
        }
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
        boolean exists = false;
        try (Connection conn = database.connection();
             PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM " + qi(collectionRepository.getCollectionSchema(collection).name) + " WHERE id = ?")) {
            stmt.setString(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                exists = rs.next();
            }
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
