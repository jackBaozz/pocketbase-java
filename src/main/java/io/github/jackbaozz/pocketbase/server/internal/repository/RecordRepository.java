package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jackbaozz.pocketbase.server.internal.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.ApiErrors;
import io.github.jackbaozz.pocketbase.server.internal.FieldTypeMapping;
import io.github.jackbaozz.pocketbase.server.internal.FieldValidator;
import io.github.jackbaozz.pocketbase.server.internal.FilterToSqlCompiler;
import io.github.jackbaozz.pocketbase.server.internal.IdGenerator;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;
import io.github.jackbaozz.pocketbase.server.internal.RecordProcessor;
import io.github.jackbaozz.pocketbase.server.internal.RealtimeHub;
import io.github.jackbaozz.pocketbase.server.internal.RequestPrincipal;
import io.github.jackbaozz.pocketbase.server.internal.RuleEvaluator;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;
import io.github.jackbaozz.pocketbase.server.internal.UploadedFile;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.jooq.exception.DataAccessException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class RecordRepository extends BaseRepository {

    private final CollectionRepository collectionRepository;
    private final RecordProcessor.StoreContext storeContext;
    private final Path storageDir;
    private volatile RealtimeHub realtimeHub;

    public RecordRepository(JooqDatabase database, ObjectMapper mapper, CollectionRepository collectionRepository, RecordProcessor.StoreContext storeContext, Path dataDir) {
        super(database, mapper);
        this.collectionRepository = collectionRepository;
        this.storeContext = storeContext;
        this.storageDir = dataDir.resolve("storage");
    }

    public void setRealtimeHub(RealtimeHub realtimeHub) {
        this.realtimeHub = realtimeHub;
    }

    public Map<String, Object> getRecord(String collection, String id, Map<String, String> query, RequestPrincipal principal) {
        collectionRepository.requireCollectionExists(collection);
        CollectionSchema colSchema = collectionRepository.getCollectionSchema(collection);

        Map<String, Object> raw = getRawRecord(colSchema, id);
        if (raw == null) {
            throw new ApiException(404, "Record not found.");
        }
        if (!storeContext.canView(colSchema, raw, query, principal)) {
            throw new ApiException(404, "Record not found.");
        }
        return RecordProcessor.process(storeContext, colSchema, raw, false, query, principal);
    }

    public Map<String, Object> getRawRecord(CollectionSchema collection, String id) {
        return getRawRecordByField(collection, "id", id);
    }

    public Map<String, Object> getRawRecordByField(CollectionSchema collection, String field, Object value) {
        try {
            org.jooq.Record record = database.dsl()
                    .select(recordSelectFields(collection))
                    .from(qt(collection.name))
                    .where(qf(field).eq(value))
                    .fetchOne();
            return record == null ? null : normalizeStoredRecord(collection, record.intoMap());
        } catch (DataAccessException e) {
            return null;
        }
    }

    public List<Map<String, Object>> listRawRecords(CollectionSchema collection) {
        try {
            return database.dsl()
                    .select(recordSelectFields(collection))
                    .from(qt(collection.name))
                    .fetch()
                    .map(record -> normalizeStoredRecord(collection, record.intoMap()));
        } catch (DataAccessException e) {
            return List.of();
        }
    }

    public Map<String, Object> normalizeStoredRecord(CollectionSchema collection, Map<String, Object> record) {
        if (record == null) {
            return null;
        }
        Map<String, Object> normalized = new LinkedHashMap<>(record);
        for (FieldSchema field : collection.fields) {
            if (!normalized.containsKey(field.name)) {
                continue;
            }
            normalized.put(field.name, normalizeStoredFieldValue(field, normalized.get(field.name)));
        }
        return normalized;
    }

    public Map<String, Object> listRecords(String collection, Map<String, String> query, RequestPrincipal principal) {
        collectionRepository.requireCollectionExists(collection);
        CollectionSchema colSchema = collectionRepository.getCollectionSchema(collection);

        // Filter support
        String filter = query.getOrDefault("filter", "");
        org.jooq.Condition condition = null;
        if (filter != null && !filter.isBlank()) {
            FilterToSqlCompiler.CompiledFilter compiled = database.compileFilter(filter);
            condition = DSL.condition(compiled.sql(), compiled.bindings().toArray());
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
                ? database.dsl().select(recordSelectFields(colSchema)).from(qt(colSchema.name)).where(condition)
                : database.dsl().select(recordSelectFields(colSchema)).from(qt(colSchema.name)).where(DSL.trueCondition());

        org.jooq.SelectSeekStepN<?> selectWithSort = sortFields.isEmpty()
                ? selectWithCondition.orderBy(sortFields)
                : selectWithCondition.orderBy(sortFields);

        List<Map<String, Object>> items = new ArrayList<>();
        for (org.jooq.Record record : selectWithSort.limit(perPage).offset((page - 1) * perPage).fetch()) {
            Map<String, Object> raw = normalizeStoredRecord(colSchema, record.intoMap());
            if (!canList(colSchema, raw, query, principal)) {
                continue;
            }
            Map<String, Object> processed = RecordProcessor.process(storeContext, colSchema, raw, false, query, principal);
            if (processed != null) {
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
        if (body == null || !body.isObject()) {
            throw new ApiException(400, "Record payload must be a JSON object.",
                    ApiErrors.invalidField("body", "Request body must be a JSON object."));
        }
        Map<String, String> safeQuery = query == null ? Map.of() : query;

        Map<String, Object> errors = new LinkedHashMap<>();
        Map<String, Object> recordValues = new LinkedHashMap<>();
        JsonNode effectiveBody = bodyWithFileMarkers(colSchema, body, files);
        for (FieldSchema field : colSchema.fields) {
            JsonNode val = effectiveBody.get(field.name);
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
            throw new ApiException(400, "Failed to create record.", errors);
        }
        FileChanges fileChanges = prepareFileChanges(colSchema, recordValues, files, body, null);
        recordValues.putAll(fileChanges.fieldValues());
        enforceUniqueFields(colSchema, recordValues, null, errors);
        if (!errors.isEmpty()) {
            throw new ApiException(400, "Failed to create record.", errors);
        }

        if ("auth".equals(colSchema.type)) {
            recordValues.putIfAbsent("verified", false);
            recordValues.putIfAbsent("tokenKey", IdGenerator.prefixed("tk_"));
        }

        String id = body.has("id") ? body.get("id").asText() : IdGenerator.id();
        String now = Instant.now().toString();
        Map<String, Object> candidate = new LinkedHashMap<>(recordValues);
        candidate.put("id", id);
        candidate.put("created", now);
        candidate.put("updated", now);
        requireCreateRule(colSchema, candidate, safeQuery, principal);

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
            handleSqlConstraintException(e, "Failed to create record.");
            throw new ApiException(400, "Failed to create record: " + e.getMessage());
        }
        writeFileChanges(colSchema, Map.of("id", id), fileChanges);
        publishRealtime(colSchema, "create", getRawRecord(colSchema, id));

        return getRecord(collection, id, safeQuery, principal);
    }

    public Map<String, Object> updateRecord(String collection, String id, JsonNode body, Map<String, List<UploadedFile>> files, Map<String, String> query, RequestPrincipal principal) {
        CollectionSchema colSchema = collectionRepository.getCollectionSchema(collection);
        if ("view".equals(colSchema.type)) {
            throw new ApiException(400, "View collections are read-only.");
        }
        if (body == null || !body.isObject()) {
            throw new ApiException(400, "Record payload must be a JSON object.",
                    ApiErrors.invalidField("body", "Request body must be a JSON object."));
        }
        Map<String, Object> existing = getRawRecord(colSchema, id);
        if (existing == null) {
            throw new ApiException(404, "Record not found.");
        }

        Map<String, Object> errors = new LinkedHashMap<>();
        Map<String, Object> recordValues = new LinkedHashMap<>();
        JsonNode effectiveBody = bodyWithFileMarkers(colSchema, body, files);
        for (FieldSchema field : colSchema.fields) {
            if (!effectiveBody.has(field.name)) {
                continue;
            }
            JsonNode val = effectiveBody.get(field.name);
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
            throw new ApiException(400, "Failed to update record.", errors);
        }
        FileChanges fileChanges = prepareFileChanges(colSchema, recordValues, files, body, existing);
        recordValues.putAll(fileChanges.fieldValues());
        enforceUniqueFields(colSchema, recordValues, id, errors);
        if (!errors.isEmpty()) {
            throw new ApiException(400, "Failed to update record.", errors);
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
            handleSqlConstraintException(e, "Failed to update record.");
            throw new ApiException(400, "Failed to update record: " + e.getMessage());
        }
        writeFileChanges(colSchema, Map.of("id", id), fileChanges);
        publishRealtime(colSchema, "update", getRawRecord(colSchema, id));

        return getRecord(collection, id, query, principal);
    }

    public Map<String, Object> upsertRecord(String collection, String id, JsonNode body, Map<String, List<UploadedFile>> files, Map<String, String> query, RequestPrincipal principal) {
        if (body == null || !body.isObject()) {
            throw new ApiException(400, "Record payload must be a JSON object.",
                    ApiErrors.invalidField("body", "Request body must be a JSON object."));
        }
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
        Map<String, Object> existing = getRawRecord(schema, id);
        if (existing == null) {
            throw new ApiException(404, "Record not found.");
        }
        try {
            database.dsl()
                    .deleteFrom(qt(schema.name))
                    .where(qfs("id").eq(id))
                    .execute();
        } catch (DataAccessException e) {
            throw new ApiException(400, "Failed to delete record: " + e.getMessage());
        }
        publishRealtime(schema, "delete", existing);
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
        return getRawRecordByField(colSchema, "email", email);
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

    private JsonNode bodyWithFileMarkers(
            CollectionSchema collection,
            JsonNode body,
            Map<String, List<UploadedFile>> files
    ) {
        ObjectNode copy = body != null && body.isObject() ? body.deepCopy() : mapper.createObjectNode();
        if (files == null || files.isEmpty()) {
            return copy;
        }
        for (FieldSchema field : collection.fields) {
            if (!"file".equals(normalizeType(field.type))) {
                continue;
            }
            if (!copy.has(field.name) && !filesFor(files, field.name).isEmpty()) {
                copy.put(field.name, "__uploaded__");
            }
        }
        return copy;
    }

    private List<Field<Object>> recordSelectFields(CollectionSchema collection) {
        Set<String> names = new LinkedHashSet<>();
        names.add("id");
        if ("_superusers".equals(collection.name)) {
            names.add("email");
            names.add("passwordHash");
            names.add("tokenKey");
            names.add("created");
            names.add("updated");
            return names.stream().map(this::qf).toList();
        }
        names.add("created");
        names.add("updated");
        for (FieldSchema field : collection.fields) {
            names.add(field.name);
        }
        if ("auth".equals(collection.type)) {
            names.add("tokenKey");
        }
        return names.stream().map(this::qf).toList();
    }

    private FileChanges prepareFileChanges(
            CollectionSchema collection,
            Map<String, Object> values,
            Map<String, List<UploadedFile>> files,
            JsonNode body,
            Map<String, Object> existing
    ) {
        Map<String, Object> fieldValues = new LinkedHashMap<>();
        Map<String, UploadedFile> writes = new LinkedHashMap<>();
        Map<String, List<String>> removals = new LinkedHashMap<>();
        Map<String, List<UploadedFile>> safeFiles = files == null ? Map.of() : files;

        for (FieldSchema field : collection.fields) {
            if (!"file".equals(normalizeType(field.type))) {
                continue;
            }
            List<String> current = existing == null ? List.of() : fileList(existing.get(field.name));
            List<String> next = new ArrayList<>(current);
            List<String> removeNames = requestedFileRemovals(field, body);
            if (!removeNames.isEmpty()) {
                next.removeIf(removeNames::contains);
                removals.put(field.name, removeNames);
            }

            List<UploadedFile> replaceUploads = filesFor(safeFiles, field.name);
            List<UploadedFile> appendUploads = filesFor(safeFiles, field.name + "+");
            boolean replace = !replaceUploads.isEmpty();
            if (replace) {
                removals.put(field.name, current);
                next.clear();
            }

            List<String> uploadedNames = new ArrayList<>();
            for (UploadedFile file : merge(replaceUploads, appendUploads)) {
                validateUploadedFile(field, file);
                String storedName = storedFilename(file.originalFilename());
                uploadedNames.add(storedName);
                writes.put(storedName, file);
            }
            next.addAll(uploadedNames);

            if (values.containsKey(field.name) && uploadedNames.isEmpty() && removeNames.isEmpty()) {
                next = fileList(values.get(field.name));
            }
            int maxSelect = maxSelect(field);
            if (maxSelect > 0 && next.size() > maxSelect) {
                String message = "Too many files uploaded for field `" + field.name + "`.";
                throw new ApiException(400, message, ApiErrors.invalidField(field.name, message));
            }
            if (replace || !appendUploads.isEmpty() || !removeNames.isEmpty()) {
                fieldValues.put(field.name, fileFieldValue(field, next));
            }
        }
        return new FileChanges(fieldValues, writes, removals);
    }

    private void writeFileChanges(CollectionSchema collection, Map<String, Object> record, FileChanges changes) {
        if (changes.isEmpty()) {
            return;
        }
        String recordId = String.valueOf(record.get("id"));
        Path recordDir = storageDir.resolve(collection.id).resolve(recordId).normalize();
        List<Path> written = new ArrayList<>();
        try {
            Files.createDirectories(recordDir);
            for (Map.Entry<String, UploadedFile> entry : changes.writes().entrySet()) {
                Path target = recordDir.resolve(entry.getKey()).normalize();
                if (!target.startsWith(recordDir)) {
                    throw new ApiException(400, "Invalid upload filename.",
                            ApiErrors.invalidField("file", "Invalid upload filename."));
                }
                Files.write(target, entry.getValue().bytes(), StandardOpenOption.CREATE_NEW);
                written.add(target);
            }
            if (!written.isEmpty()) {
                database.onRollback(() -> written.forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new IllegalStateException("failed to rollback uploaded file " + path, e);
                    }
                }));
            }
            for (List<String> names : changes.removals().values()) {
                for (String name : names) {
                    Files.deleteIfExists(recordDir.resolve(name).normalize());
                }
            }
        } catch (IOException e) {
            for (Path path : written) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            }
            throw new IllegalStateException("failed to write uploaded files", e);
        }
    }

    private Object fileFieldValue(FieldSchema field, List<String> names) {
        return maxSelect(field) == 1 ? (names.isEmpty() ? "" : names.get(0)) : new ArrayList<>(names);
    }

    private List<String> fileList(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .map(String::valueOf)
                    .filter(item -> !item.isBlank())
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(List.of(text));
    }

    private List<String> requestedFileRemovals(FieldSchema field, JsonNode body) {
        if (body == null || !body.isObject()) {
            return List.of();
        }
        JsonNode value = body.get(field.name + "-");
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (value.isArray()) {
            List<String> names = new ArrayList<>();
            value.forEach(item -> {
                if (!item.asText().isBlank()) {
                    names.add(item.asText());
                }
            });
            return names;
        }
        return value.asText().isBlank() ? List.of() : List.of(value.asText());
    }

    private List<UploadedFile> filesFor(Map<String, List<UploadedFile>> files, String field) {
        List<UploadedFile> values = files.get(field);
        return values == null ? List.of() : values;
    }

    private List<UploadedFile> merge(List<UploadedFile> left, List<UploadedFile> right) {
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        List<UploadedFile> merged = new ArrayList<>(left);
        merged.addAll(right);
        return merged;
    }

    private int maxSelect(FieldSchema field) {
        if (field.maxSelect != null) {
            return Math.max(1, field.maxSelect);
        }
        if (field.maxFiles != null) {
            return Math.max(1, field.maxFiles);
        }
        JsonNode maxSelect = field.options == null ? null : field.options.get("maxSelect");
        if (maxSelect == null) {
            maxSelect = field.options == null ? null : field.options.get("maxFiles");
        }
        if (maxSelect != null && maxSelect.canConvertToInt()) {
            return Math.max(1, maxSelect.asInt());
        }
        return 1;
    }

    private void validateUploadedFile(FieldSchema field, UploadedFile file) {
        long maxSize = maxSize(field);
        if (maxSize > 0 && file.bytes().length > maxSize) {
            String message = "File `" + file.originalFilename() + "` exceeds maxSize for field `" + field.name + "`.";
            throw new ApiException(400, message, ApiErrors.invalidField(field.name, message));
        }
        List<String> mimeTypes = mimeTypes(field);
        if (!mimeTypes.isEmpty() && mimeTypes.stream().noneMatch(pattern -> matchesMimeType(pattern, file.contentType()))) {
            String message = "File `" + file.originalFilename() + "` MIME type is not allowed for field `" + field.name + "`.";
            throw new ApiException(400, message, ApiErrors.invalidField(field.name, message));
        }
    }

    private long maxSize(FieldSchema field) {
        if (field.maxSize != null) {
            return Math.max(0L, field.maxSize);
        }
        JsonNode value = field.options == null ? null : field.options.get("maxSize");
        return value != null && value.canConvertToLong() ? Math.max(0L, value.asLong()) : 0L;
    }

    private List<String> mimeTypes(FieldSchema field) {
        if (field.mimeTypes != null && !field.mimeTypes.isEmpty()) {
            return field.mimeTypes.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toLowerCase(Locale.ROOT))
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        JsonNode value = field.options == null ? null : field.options.get("mimeTypes");
        if (value == null || value.isNull()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (value.isArray()) {
            value.forEach(item -> {
                String text = item.asText("").trim().toLowerCase(Locale.ROOT);
                if (!text.isBlank()) {
                    out.add(text);
                }
            });
        } else {
            String text = value.asText("").trim().toLowerCase(Locale.ROOT);
            if (!text.isBlank()) {
                out.add(text);
            }
        }
        return out;
    }

    private boolean matchesMimeType(String pattern, String contentType) {
        String actual = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (actual.isBlank()) {
            return false;
        }
        if ("*/*".equals(pattern) || "*".equals(pattern)) {
            return true;
        }
        if (pattern.endsWith("/*")) {
            return actual.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return actual.equals(pattern);
    }

    private String storedFilename(String original) {
        String sanitized = sanitizeFilename(original);
        int dot = sanitized.lastIndexOf('.');
        String name = dot > 0 ? sanitized.substring(0, dot) : sanitized;
        String extension = dot > 0 ? sanitized.substring(dot) : "";
        return name + "_" + IdGenerator.suffix() + extension;
    }

    private String sanitizeFilename(String original) {
        String file = original == null || original.isBlank() ? "file" : Path.of(original).getFileName().toString();
        String sanitized = file.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("_+", "_");
        if (sanitized.isBlank() || ".".equals(sanitized) || "..".equals(sanitized)) {
            return "file";
        }
        return sanitized.length() > 80 ? sanitized.substring(sanitized.length() - 80) : sanitized;
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

    private String normalizeType(String type) {
        return type == null ? "text" : type.toLowerCase(Locale.ROOT).trim();
    }

    private boolean canList(CollectionSchema collection, Map<String, Object> record, Map<String, String> query, RequestPrincipal principal) {
        if (principal != null && principal.superuser()) {
            return true;
        }
        return collection.listRule != null && RuleEvaluator.matches(
                collection.listRule,
                RuleEvaluator.context(record, null, query == null ? Map.of() : query, "GET", principal, storeContext::recordsForRule)
        );
    }

    private Object normalizeStoredFieldValue(FieldSchema field, Object value) {
        if (value == null) {
            return null;
        }
        String type = normalizeType(field.type);
        if ("bool".equals(type) || "boolean".equals(type)) {
            if (value instanceof Boolean bool) {
                return bool;
            }
            if (value instanceof Number number) {
                return number.intValue() != 0;
            }
            return Boolean.parseBoolean(String.valueOf(value));
        }
        if ("json".equals(type) || "geopoint".equals(type)) {
            return parseJsonStoredValue(value, false);
        }
        if (FieldTypeMapping.isJsonStoredType(type)) {
            return parseJsonStoredValue(value, maxSelect(field) > 1);
        }
        return value;
    }

    private Object parseJsonStoredValue(Object value, boolean arrayOnly) {
        if (!(value instanceof String text)) {
            return value;
        }
        String trimmed = text.trim();
        if (trimmed.isBlank()) {
            return value;
        }
        if (arrayOnly && !trimmed.startsWith("[")) {
            return value;
        }
        if (!arrayOnly && !(trimmed.startsWith("{") || trimmed.startsWith("[") || "null".equals(trimmed)
                || "true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed) || isJsonNumber(trimmed))) {
            return value;
        }
        try {
            return mapper.readValue(trimmed, Object.class);
        } catch (IOException e) {
            return value;
        }
    }

    private boolean isJsonNumber(String value) {
        if (value.isBlank()) {
            return false;
        }
        if (!Character.isDigit(value.charAt(0)) && value.charAt(0) != '-') {
            return false;
        }
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void requireCreateRule(CollectionSchema collection, Map<String, Object> record, Map<String, String> query, RequestPrincipal principal) {
        if (principal != null && principal.superuser()) {
            return;
        }
        if (collection.createRule == null) {
            throw new ApiException(403, "Only superusers can create records in this collection.");
        }
        if (!RuleEvaluator.matches(
                collection.createRule,
                RuleEvaluator.context(record, record, query == null ? Map.of() : query, "POST", principal, storeContext::recordsForRule)
        )) {
            throw new ApiException(400, "The record failed the collection create rule.");
        }
    }

    private void publishRealtime(CollectionSchema collection, String action, Map<String, Object> record) {
        RealtimeHub hub = realtimeHub;
        if (hub == null || record == null) {
            return;
        }
        String recordId = String.valueOf(record.get("id"));
        hub.publish(collection.name, collection.id, recordId, action, (subscription, principal) -> {
            if (!canReceiveRealtime(collection, record, subscription, principal)) {
                return null;
            }
            return RecordProcessor.process(storeContext, collection, record, principal != null && principal.superuser(), subscription.query(), principal);
        });
    }

    private boolean canReceiveRealtime(
            CollectionSchema collection,
            Map<String, Object> record,
            RealtimeHub.Subscription subscription,
            RequestPrincipal principal
    ) {
        boolean allowed = subscription.wildcard()
                ? canList(collection, record, subscription.query(), principal)
                : storeContext.canView(collection, record, subscription.query(), principal);
        if (!allowed) {
            return false;
        }
        String filter = subscription.filter();
        return filter == null || filter.isBlank()
                || RuleEvaluator.matches(
                filter,
                RuleEvaluator.context(record, null, subscription.query(), "GET", principal, storeContext::recordsForRule)
        );
    }

    private record FileChanges(
            Map<String, Object> fieldValues,
            Map<String, UploadedFile> writes,
            Map<String, List<String>> removals
    ) {
        boolean isEmpty() {
            return fieldValues.isEmpty() && writes.isEmpty() && removals.isEmpty();
        }
    }

    private void enforceUniqueFields(CollectionSchema collection, Map<String, Object> recordValues, String currentRecordId, Map<String, Object> errors) {
        for (FieldSchema field : collection.fields) {
            if (!field.unique || !recordValues.containsKey(field.name)) {
                continue;
            }
            Object value = recordValues.get(field.name);
            if (value == null || String.valueOf(value).isBlank()) {
                continue;
            }
            try {
                var condition = qf(field.name).eq(toStoredValue(value));
                if (currentRecordId != null) {
                    condition = condition.and(qfs("id").ne(currentRecordId));
                }
                boolean exists = database.dsl().fetchExists(
                        database.dsl().selectOne().from(qt(collection.name)).where(condition)
                );
                if (exists) {
                    errors.put(field.name, ApiErrors.validationError("validation_not_unique", ApiErrors.MESSAGE_VALUE_MUST_BE_UNIQUE));
                }
            } catch (DataAccessException e) {
                throw new ApiException(400, "Failed to validate unique field: " + field.name);
            }
        }
    }
}
