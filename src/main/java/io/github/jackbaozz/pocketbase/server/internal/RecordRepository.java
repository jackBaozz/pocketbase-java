package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import io.github.jackbaozz.pocketbase.server.internal.IdGenerator;
import io.github.jackbaozz.pocketbase.server.internal.FieldValidator;
import io.github.jackbaozz.pocketbase.server.internal.FilterToSqlCompiler;
import io.github.jackbaozz.pocketbase.server.internal.RecordProcessor;

public class RecordRepository {
    private final RelationalStorageEngine engine;

    public RecordRepository(RelationalStorageEngine engine) {
        this.engine = engine;
    }

    public Map<String, Object> listRecords(String collection, Map<String, String> query, RequestPrincipal principal) {
        CollectionSchema colSchema = engine.getCollectionRepository().getCollectionSchema(collection);

        Map<String, String> safeQuery = query == null ? Map.of() : query;
        int page = 1;
        int perPage = 30;
        try {
            if (safeQuery.containsKey("page")) page = Integer.parseInt(safeQuery.get("page"));
            if (safeQuery.containsKey("perPage")) perPage = Integer.parseInt(safeQuery.get("perPage"));
        } catch (NumberFormatException ignored) {}

        Condition whereCondition = DSL.trueCondition();
        if (safeQuery.containsKey("filter")) {
            FilterToSqlCompiler.CompiledFilter compiledFilter = FilterToSqlCompiler.compileBound(
                    safeQuery.get("filter"),
                    engine::qi,
                    engine.database()::renderContainsCondition
            );

            Object authJson = null;
            if (principal != null) {
                try {
                    authJson = engine.mapper.writeValueAsString(Map.of(
                            "id", principal.id(),
                            "collectionId", principal.collectionName(),
                            "collectionName", principal.collectionName(),
                            "email", "dummy@example.com"
                    ));
                } catch (Exception ignored) {}
            }
            String queryJson = "{}";
            try { queryJson = engine.mapper.writeValueAsString(safeQuery); } catch (Exception ignored) {}

            RelationalStorageEngine.BoundSql boundFilter = engine.bindFilterContext(compiledFilter, authJson, queryJson, "GET", "{}");
            whereCondition = DSL.condition(boundFilter.sql(), boundFilter.bindings().toArray());
        }

        try {
            DSLContext dsl = engine.database().dsl();
            int offset = (page - 1) * perPage;
            int total = dsl.selectCount()
                    .from(engine.qt(colSchema.name))
                    .where(whereCondition)
                    .fetchOne(0, int.class);
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> row : dsl.select()
                    .from(engine.qt(colSchema.name))
                    .where(whereCondition)
                    .limit(perPage)
                    .offset(offset)
                    .fetchMaps()) {
                items.add(RecordProcessor.process(engine, colSchema, row, false, safeQuery, principal));
            }

            int totalPages = (int) Math.ceil((double) total / perPage);
            return Map.of(
                    "items", items,
                    "page", page,
                    "perPage", perPage,
                    "totalItems", total,
                    "totalPages", totalPages
            );

        } catch (DataAccessException e) {
            throw new ApiException(400, "Failed to list records: " + e.getMessage());
        }
    }

    public Map<String, Object> getRecord(CollectionSchema collection, String id) {
        Connection conn = null;
        try {
            conn = engine.connection();
            try (PreparedStatement select = conn.prepareStatement("SELECT * FROM " + engine.qi(collection.name) + " WHERE id = ?")) {
                select.setString(1, id);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        ResultSetMetaData md = rs.getMetaData();
                        int columns = md.getColumnCount();
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= columns; i++) {
                            row.put(md.getColumnLabel(i), rs.getObject(i));
                        }
                        return row;
                    }
                }
            }
        } catch (SQLException ignored) {
        } finally {
            if (conn != null) {
                try {
                    engine.closeIfStandalone(conn);
                } catch (SQLException ignored) {
                }
            }
        }
        return null;
    }

    public Map<String, Object> getRecord(String collection, String id, Map<String, String> query, RequestPrincipal principal) {
        CollectionSchema colSchema = engine.getCollectionRepository().getCollectionSchema(collection);

        Connection conn = null;
        try {
            conn = engine.connection();
            try (PreparedStatement select = conn.prepareStatement("SELECT * FROM " + engine.qi(colSchema.name) + " WHERE id = ?")) {
                select.setString(1, id);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        ResultSetMetaData md = rs.getMetaData();
                        int columns = md.getColumnCount();
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= columns; i++) {
                            row.put(md.getColumnLabel(i), rs.getObject(i));
                        }
                        
                        if (principal == null || !principal.superuser()) {
                            if (colSchema.viewRule == null) {
                                throw new ApiException(403, "Only admins can view this record.");
                            }
                            if (!colSchema.viewRule.isBlank()) {
                                if (!engine.canView(colSchema, row, query == null ? Map.of() : query, principal)) {
                                    throw new ApiException(404, "Record not found.");
                                }
                            }
                        }

                        return RecordProcessor.process(engine, colSchema, row, false, query, principal);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            if (conn != null) {
                try {
                    engine.closeIfStandalone(conn);
                } catch (SQLException ignored) {
                }
            }
        }
        throw new ApiException(404, "Record not found.");
    }

    public Map<String, Object> createRecord(String collection, JsonNode body, Map<String, List<UploadedFile>> files, Map<String, String> query, RequestPrincipal principal) {
        CollectionSchema colSchema = engine.getCollectionRepository().getCollectionSchema(collection);
        if ("view".equals(colSchema.type)) {
            throw new ApiException(400, "View collections are read-only.");
        }

        Map<String, Object> errors = new LinkedHashMap<>();
        Map<String, Object> recordValues = new LinkedHashMap<>();
        List<Runnable> fileSaveTasks = new ArrayList<>();
        String id = body.has("id") ? body.get("id").asText() : IdGenerator.id();
        for (FieldSchema field : colSchema.fields) {
            JsonNode val = body.get(field.name);
            List<UploadedFile> fieldFiles = files != null ? files.getOrDefault(field.name, List.of()) : List.of();

            if ("file".equals(field.type)) {
                Object normalizedFile = normalizeFileField(field, colSchema, val, fieldFiles, id, errors, fileSaveTasks);
                if (normalizedFile != null) {
                    recordValues.put(field.name, normalizedFile);
                }
                continue;
            }

            if (val == null || val.isMissingNode()) {
                val = engine.mapper.nullNode();
            }
            Object normalized = FieldValidator.normalizeFieldValue(engine.mapper, field, val, false, errors, (col, targetId) -> {
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
        
        if ("auth".equals(colSchema.type)) {
            if (body.has("email")) recordValues.put("email", body.get("email").asText());
            if (body.has("username")) recordValues.put("username", body.get("username").asText());
            if (body.has("emailVisibility")) recordValues.put("emailVisibility", body.get("emailVisibility").asBoolean() ? 1 : 0);
            if (body.has("verified")) recordValues.put("verified", body.get("verified").asBoolean() ? 1 : 0);
            if (body.has("password") && !body.get("password").asText().isBlank()) {
                String pw = body.get("password").asText();
                String confirm = body.has("passwordConfirm") ? body.get("passwordConfirm").asText() : pw;
                if (!pw.equals(confirm)) {
                    errors.put("passwordConfirm", Map.of("code", "validation_failed", "message", "Passwords do not match."));
                } else {
                    recordValues.put("passwordHash", PasswordHasher.hash(pw));
                    recordValues.put("tokenKey", IdGenerator.id());
                }
            } else if (!body.has("id")) { // create mode requires password
                // but wait, is password required? Only if it's not a superuser creating it? For MVP let's allow it without
            }
        }

        if (!errors.isEmpty()) {
            throw new ApiException(400, "Record validation failed.", errors);
        }

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
                values.add(engine.toStoredValue(entry.getValue()));
            }
        }

        try {
            List<Field<Object>> insertFields = fields.stream().map(engine::qf).toList();
            engine.database().dsl()
                    .insertInto(engine.qt(colSchema.name))
                    .columns(insertFields)
                    .values(values)
                    .execute();
        } catch (DataAccessException e) {
            engine.handleSqlConstraintException(e);
            throw new ApiException(400, "Failed to create record: " + e.getMessage());
        }

        fileSaveTasks.forEach(Runnable::run);

        return getRecord(collection, id, query, principal);
    }

    public Map<String, Object> updateRecord(String collection, String id, JsonNode body, Map<String, List<UploadedFile>> files, Map<String, String> query, RequestPrincipal principal) {
        CollectionSchema colSchema = engine.getCollectionRepository().getCollectionSchema(collection);
        if ("view".equals(colSchema.type)) {
            throw new ApiException(400, "View collections are read-only.");
        }

        Map<String, Object> errors = new LinkedHashMap<>();
        Map<String, Object> recordValues = new LinkedHashMap<>();
        List<Runnable> fileSaveTasks = new ArrayList<>();
        for (FieldSchema field : colSchema.fields) {
            JsonNode val = body.get(field.name);
            List<UploadedFile> fieldFiles = files != null ? files.getOrDefault(field.name, List.of()) : List.of();

            if ("file".equals(field.type)) {
                if ((val == null || val.isMissingNode()) && fieldFiles.isEmpty() && !body.has(field.name)) {
                    continue; // unchanged
                }
                Object normalizedFile = normalizeFileField(field, colSchema, val, fieldFiles, id, errors, fileSaveTasks);
                if (normalizedFile == null && field.required) {
                    // validation will catch this in errors
                } else if (normalizedFile != null) {
                    recordValues.put(field.name, normalizedFile);
                } else {
                    recordValues.put(field.name, null); // explicit delete all files
                }
                continue;
            }

            if (val == null || val.isMissingNode()) {
                val = engine.mapper.nullNode();
            }
            Object normalized = FieldValidator.normalizeFieldValue(engine.mapper, field, val, true, errors, (col, targetId) -> {
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
        
        if ("auth".equals(colSchema.type)) {
            if (body.has("email")) recordValues.put("email", body.get("email").asText());
            if (body.has("username")) recordValues.put("username", body.get("username").asText());
            if (body.has("emailVisibility")) recordValues.put("emailVisibility", body.get("emailVisibility").asBoolean() ? 1 : 0);
            if (body.has("verified")) recordValues.put("verified", body.get("verified").asBoolean() ? 1 : 0);
            if (body.has("password") && !body.get("password").asText().isBlank()) {
                String pw = body.get("password").asText();
                String confirm = body.has("passwordConfirm") ? body.get("passwordConfirm").asText() : pw;
                if (!pw.equals(confirm)) {
                    errors.put("passwordConfirm", Map.of("code", "validation_failed", "message", "Passwords do not match."));
                } else {
                    recordValues.put("passwordHash", PasswordHasher.hash(pw));
                    recordValues.put("tokenKey", IdGenerator.id());
                }
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
                values.add(engine.toStoredValue(entry.getValue()));
            }
        }

        fields.add("updated");
        values.add(now);

        try {
            Map<Field<Object>, Object> updates = new LinkedHashMap<>();
            for (int i = 0; i < fields.size(); i++) {
                updates.put(engine.qf(fields.get(i)), values.get(i));
            }
            engine.database().dsl()
                    .update(engine.qt(colSchema.name))
                    .set(updates)
                    .where(engine.qfs("id").eq(id))
                    .execute();
        } catch (DataAccessException e) {
            engine.handleSqlConstraintException(e);
            throw new ApiException(400, "Failed to update record: " + e.getMessage());
        }

        fileSaveTasks.forEach(Runnable::run);

        return getRecord(collection, id, query, principal);
    }

    public Map<String, Object> upsertRecord(String collection, String id, JsonNode body, Map<String, List<UploadedFile>> files, Map<String, String> query, RequestPrincipal principal) {
        if (id != null && !id.isBlank()) {
            return updateRecord(collection, id, body, files, query, principal);
        }
        return createRecord(collection, body, files, query, principal);
    }

    public void deleteRecord(String collection, String id, RequestPrincipal principal) {
        CollectionSchema schema = engine.getCollectionRepository().getCollectionSchema(collection);
        try {
            engine.database().dsl()
                    .deleteFrom(engine.qt(schema.name))
                    .where(engine.qfs("id").eq(id))
                    .execute();
        } catch (DataAccessException e) {
            throw new ApiException(400, "Failed to delete record: " + e.getMessage());
        }
    }

    public Path filePath(String collection, String recordId, String filename, RequestPrincipal principal) {
        try {
            CollectionSchema schema = engine.getCollectionRepository().getCollectionSchema(collection);
            if (recordId == null || recordId.isBlank() || filename == null || filename.isBlank()) {
                return null;
            }

            boolean isProtected = false;
            Connection conn = null;
            try {
                conn = engine.connection();
                try (PreparedStatement select = conn.prepareStatement("SELECT * FROM " + engine.qi(schema.name) + " WHERE id = ?")) {
                    select.setString(1, recordId);
                    try (ResultSet rs = select.executeQuery()) {
                        if (rs.next()) {
                            for (FieldSchema field : schema.fields) {
                                if ("file".equals(field.type)) {
                                    Object val = rs.getObject(field.name);
                                    String valStr = val == null ? "" : val.toString();
                                    System.out.println("DEBUG filePath: checking field " + field.name + " valStr=" + valStr + " filename=" + filename);
                                    if (valStr.contains(filename)) {
                                        isProtected = Boolean.TRUE.equals(field.protectedFile);
                                        System.out.println("DEBUG filePath: found match! isProtected=" + isProtected + " (field.protectedFile=" + field.protectedFile + ")");
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                // ignore
            } finally {
                if (conn != null) {
                    try {
                        engine.closeIfStandalone(conn);
                    } catch (SQLException ignored) {
                    }
                }
            }

            if (isProtected) {
                try {
                    getRecord(collection, recordId, Map.of(), principal);
                } catch (ApiException e) {
                    throw new ApiException(403, "File access denied.");
                }
            }

            return engine.getDataDir().resolve("storage").resolve(schema.id).resolve(recordId).resolve(filename);
        } catch (ApiException e) {
            if (e.status() == 403) throw e;
            return null;
        }
    }

    private Object normalizeFileField(FieldSchema field, CollectionSchema colSchema, JsonNode bodyVal, List<UploadedFile> uploadedFiles, String recordId, Map<String, Object> errors, List<Runnable> fileSaveTasks) {
        List<String> keptFiles = new ArrayList<>();
        if (bodyVal != null && !bodyVal.isNull() && !bodyVal.isMissingNode()) {
            if (bodyVal.isArray()) {
                for (JsonNode node : bodyVal) {
                    if (node.isTextual() && !node.asText().isBlank()) keptFiles.add(node.asText());
                }
            } else if (bodyVal.isTextual() && !bodyVal.asText().isBlank()) {
                keptFiles.add(bodyVal.asText());
            }
        }

        int maxSelect = field.maxSelect != null ? field.maxSelect : 1;
        if (keptFiles.size() + uploadedFiles.size() > maxSelect) {
            errors.put(field.name, Map.of("code", "validation_failed", "message", "Failed to upload all files. Maximum allowed files are " + maxSelect + "."));
            return null;
        }

        if (field.required && keptFiles.isEmpty() && uploadedFiles.isEmpty()) {
            errors.put(field.name, Map.of("code", "validation_failed", "message", field.name + " is required."));
            return null;
        }
        if (keptFiles.isEmpty() && uploadedFiles.isEmpty()) return null;

        long maxSize = field.maxSize != null ? field.maxSize : 5242880;

        List<String> allowedMimeTypes = field.mimeTypes != null ? field.mimeTypes : new ArrayList<>();

        List<String> finalFiles = new ArrayList<>(keptFiles);
        for (UploadedFile file : uploadedFiles) {
            if (file.bytes().length > maxSize) {
                errors.put(field.name, Map.of("code", "validation_failed", "message", "File size exceeds maxSize."));
                return null;
            }
            if (!allowedMimeTypes.isEmpty()) {
                boolean match = false;
                for (String allowed : allowedMimeTypes) {
                    if (allowed.equals(file.contentType()) || file.contentType().startsWith(allowed.replace("*", ""))) {
                        match = true; break;
                    }
                }
                if (!match) {
                    errors.put(field.name, Map.of("code", "validation_failed", "message", "MIME type is not allowed."));
                    return null;
                }
            }
            String ext = "";
            int dotIdx = file.originalFilename().lastIndexOf('.');
            if (dotIdx > 0 && dotIdx < file.originalFilename().length() - 1) {
                ext = file.originalFilename().substring(dotIdx);
            }
            String originalBase = file.originalFilename().replaceAll("\\.[^.]*$", "").replaceAll("[^a-zA-Z0-9_-]", "_");
            if (originalBase.isEmpty()) originalBase = "file";
            if (originalBase.length() > 20) originalBase = originalBase.substring(0, 20);

            String filename = originalBase + "_" + IdGenerator.id() + ext;
            finalFiles.add(filename);

            fileSaveTasks.add(() -> {
                try {
                    Path dir = engine.getDataDir().resolve("storage").resolve(colSchema.id).resolve(recordId);
                    Files.createDirectories(dir);
                    Files.copy(new ByteArrayInputStream(file.bytes()), dir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new RuntimeException("failed to save file", e);
                }
            });
        }
        return maxSelect == 1 ? (finalFiles.isEmpty() ? null : finalFiles.get(0)) : finalFiles;
    }
}
