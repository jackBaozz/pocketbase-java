package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Small persistent store used by the native-friendly embedded server.
 */
public final class JsonFileStore {
    public static final String SUPERUSERS = "_superusers";

    private static final TypeReference<List<CollectionSchema>> COLLECTION_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> RECORD_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, List<Map<String, Object>>>> RECORD_MAP = new TypeReference<>() {
    };
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,62}$");
    private static final int MAX_EXPAND_DEPTH = 6;

    private final Path dataDir;
    private final Path schemaFile;
    private final Path recordsDir;
    private final Path storageDir;
    private final Path backupsDir;
    private final Path secretFile;
    private final ObjectMapper mapper;
    private final TokenService tokenService;
    private final Map<String, CollectionSchema> collectionsByName = new LinkedHashMap<>();
    private final Map<String, List<Map<String, Object>>> recordsByCollectionId = new LinkedHashMap<>();
    private RealtimeHub realtimeHub;

    private JsonFileStore(Path dataDir, ObjectMapper mapper, TokenService tokenService) {
        this.dataDir = dataDir;
        this.schemaFile = dataDir.resolve("pb_schema.json");
        this.recordsDir = dataDir.resolve("records");
        this.storageDir = dataDir.resolve("storage");
        this.backupsDir = dataDir.resolve("backups");
        this.secretFile = dataDir.resolve("pb_secret");
        this.mapper = mapper;
        this.tokenService = tokenService;
    }

    public static JsonFileStore open(Path dataDir, String bootstrapEmail, String bootstrapPassword) throws IOException {
        ObjectMapper mapper = RuntimeJson.create();
        Files.createDirectories(dataDir);
        Files.createDirectories(dataDir.resolve("records"));
        Files.createDirectories(dataDir.resolve("storage"));
        Files.createDirectories(dataDir.resolve("backups"));
        String secret = readOrCreateSecret(dataDir.resolve("pb_secret"));
        JsonFileStore store = new JsonFileStore(dataDir, mapper, new TokenService(mapper, secret));
        store.load();
        if (bootstrapEmail != null && !bootstrapEmail.isBlank()
                && bootstrapPassword != null && !bootstrapPassword.isBlank()
                && !store.hasSuperusers()) {
            ObjectNode body = mapper.createObjectNode();
            body.put("email", bootstrapEmail);
            body.put("password", bootstrapPassword);
            body.put("verified", true);
            store.createSuperuser(body);
        }
        return store;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public void realtimeHub(RealtimeHub realtimeHub) {
        this.realtimeHub = realtimeHub;
    }

    public Optional<Map<String, Object>> verifyToken(String token) {
        return tokenService.verify(token)
                .filter(claims -> !"file".equals(claims.get("tokenType")));
    }

    public Optional<RequestPrincipal> verifyFileToken(String token) {
        return tokenService.verify(token)
                .filter(claims -> "file".equals(claims.get("tokenType")))
                .map(RequestPrincipal::fromClaims);
    }

    public synchronized Map<String, Object> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("canBackup", true);
        data.put("dataDir", dataDir.toString());
        data.put("superuserReady", hasSuperusers());
        return Map.of(
                "code", 200,
                "message", "API is healthy.",
                "data", data
        );
    }

    public synchronized boolean hasSuperusers() {
        CollectionSchema collection = collectionsByName.get(SUPERUSERS);
        return collection != null && !records(collection).isEmpty();
    }

    public synchronized Map<String, Object> bootstrapSuperuser(JsonNode body) {
        if (hasSuperusers()) {
            throw new ApiException(403, "Superuser already exists.");
        }
        Map<String, Object> record = createSuperuser(body);
        return Map.of("record", record);
    }

    public synchronized Map<String, Object> listCollections(Map<String, String> query) {
        Map<String, String> safeQuery = query == null ? Map.of() : query;
        int page = parsePositive(safeQuery.get("page"), 1);
        int perPage = parsePositive(safeQuery.get("perPage"), 100);
        List<Map<String, Object>> items = collectionsByName.values().stream()
                .map(this::collectionMap)
                .filter(collection -> matchesCollectionFilter(collection, safeQuery.get("filter")))
                .collect(Collectors.toCollection(ArrayList::new));
        sort(items, safeQuery.get("sort"));
        int total = items.size();
        int from = Math.min(total, (page - 1) * perPage);
        int to = Math.min(total, from + perPage);
        List<Map<String, Object>> pageItems = items.subList(from, to).stream()
                .map(item -> selectFields(item, safeQuery.get("fields")))
                .collect(Collectors.toCollection(ArrayList::new));
        return paginated(page, perPage, total, pageItems);
    }

    public synchronized Map<String, Object> getCollection(String idOrName, Map<String, String> query) {
        Map<String, String> safeQuery = query == null ? Map.of() : query;
        return selectFields(collectionMap(findCollection(idOrName)), safeQuery.get("fields"));
    }

    public synchronized CollectionSchema createCollection(JsonNode body) {
        if (!body.isObject()) {
            throw new ApiException(400, "Collection payload must be a JSON object.");
        }
        CollectionSchema collection = mapper.convertValue(body, CollectionSchema.class);
        normalizeCollection(collection, false);
        if (collectionsByName.containsKey(collection.name)) {
            throw new ApiException(400, "Collection name already exists.", fieldError("name", "Collection name already exists."));
        }
        if (findCollectionOrNull(collection.id) != null) {
            throw new ApiException(400, "Collection id already exists.", fieldError("id", "Collection id already exists."));
        }
        collectionsByName.put(collection.name, copyCollection(collection));
        recordsByCollectionId.put(collection.id, new ArrayList<>());
        saveAll();
        return copyCollection(collection);
    }

    public synchronized CollectionSchema updateCollection(String idOrName, JsonNode body) {
        CollectionSchema existing = findCollection(idOrName);
        if (!body.isObject()) {
            throw new ApiException(400, "Collection payload must be a JSON object.");
        }
        String oldName = existing.name;
        if (body.hasNonNull("name")) {
            existing.name = body.get("name").asText();
        }
        if (body.hasNonNull("type") && !existing.system) {
            existing.type = body.get("type").asText();
        }
        if (body.has("listRule")) {
            existing.listRule = nullableText(body.get("listRule"));
        }
        if (body.has("viewRule")) {
            existing.viewRule = nullableText(body.get("viewRule"));
        }
        if (body.has("createRule")) {
            existing.createRule = nullableText(body.get("createRule"));
        }
        if (body.has("updateRule")) {
            existing.updateRule = nullableText(body.get("updateRule"));
        }
        if (body.has("deleteRule")) {
            existing.deleteRule = nullableText(body.get("deleteRule"));
        }
        JsonNode fields = body.has("fields") ? body.get("fields") : body.get("schema");
        if (fields != null && fields.isArray() && !existing.system) {
            existing.fields = mapper.convertValue(fields, new TypeReference<>() {
            });
        }
        normalizeCollection(existing, true);
        if (!oldName.equals(existing.name) && collectionsByName.containsKey(existing.name)) {
            existing.name = oldName;
            throw new ApiException(400, "Collection name already exists.", fieldError("name", "Collection name already exists."));
        }
        if (!oldName.equals(existing.name)) {
            collectionsByName.remove(oldName);
            collectionsByName.put(existing.name, existing);
        }
        existing.updated = now();
        saveAll();
        return copyCollection(existing);
    }

    public synchronized void deleteCollection(String idOrName) {
        CollectionSchema collection = findCollection(idOrName);
        if (collection.system) {
            throw new ApiException(400, "System collections cannot be deleted.");
        }
        collectionsByName.remove(collection.name);
        recordsByCollectionId.remove(collection.id);
        try {
            Files.deleteIfExists(recordsFile(collection));
            saveSchema();
        } catch (IOException e) {
            throw new IllegalStateException("failed to delete collection", e);
        }
    }

    public synchronized Map<String, Object> listRecords(String collectionName, Map<String, String> query, RequestPrincipal principal) {
        CollectionSchema collection = findCollection(collectionName);
        int page = parsePositive(query.get("page"), 1);
        int perPage = parsePositive(query.get("perPage"), 30);
        if (!isSuperuser(principal) && collection.listRule == null) {
            throw new ApiException(403, "Only superusers can list this collection.");
        }
        boolean includeHidden = isSuperuser(principal);
        List<Map<String, Object>> source = records(collection).stream()
                .filter(record -> isSuperuser(principal) || matchesRule(collection.listRule, record, null, query, "GET", principal))
                .filter(record -> matchesFilter(record, query.get("filter"), query, principal))
                .map(record -> responseRecord(collection, record, includeHidden, query, principal))
                .collect(Collectors.toCollection(ArrayList::new));
        sort(source, query.get("sort"));
        int total = source.size();
        int from = Math.min(total, (page - 1) * perPage);
        int to = Math.min(total, from + perPage);
        return paginated(page, perPage, total, new ArrayList<>(source.subList(from, to)));
    }

    public synchronized Map<String, Object> getRecord(String collectionName, String id, Map<String, String> query, RequestPrincipal principal) {
        CollectionSchema collection = findCollection(collectionName);
        Map<String, Object> record = findRecord(collection, id);
        Map<String, String> safeQuery = query == null ? Map.of() : query;
        requireRecordRule(collection, collection.viewRule, record, null, safeQuery, "GET", principal, "view");
        return responseRecord(collection, record, isSuperuser(principal), safeQuery, principal);
    }

    public synchronized Map<String, Object> createRecord(String collectionName, JsonNode body, RequestPrincipal principal) {
        return createRecord(collectionName, body, Map.of(), principal);
    }

    public synchronized Map<String, Object> createRecord(
            String collectionName,
            JsonNode body,
            Map<String, List<UploadedFile>> files,
            RequestPrincipal principal
    ) {
        return createRecord(collectionName, body, files, Map.of(), principal);
    }

    public synchronized Map<String, Object> createRecord(
            String collectionName,
            JsonNode body,
            Map<String, List<UploadedFile>> files,
            Map<String, String> query,
            RequestPrincipal principal
    ) {
        Map<String, String> safeQuery = query == null ? Map.of() : query;
        CollectionSchema collection = findCollection(collectionName);
        if (collection.system && !SUPERUSERS.equals(collection.name)) {
            throw new ApiException(403, "System collection is read-only.");
        }
        Map<String, Object> record = buildRecord(collection, bodyWithFileMarkers(collection, body, files), null);
        FileChanges fileChanges = prepareFileChanges(collection, record, files, body, null);
        record.putAll(fileChanges.values());
        if (!isSuperuser(principal) && collection.createRule == null) {
            throw new ApiException(403, "Only superusers can create records in this collection.");
        }
        if (!isSuperuser(principal) && !matchesRule(collection.createRule, record, record, safeQuery, "POST", principal)) {
            throw new ApiException(400, "The record failed the collection create rule.");
        }
        writeFileChanges(collection, record, fileChanges);
        records(collection).add(record);
        saveRecords(collection);
        publishRealtime(collection, "create", record);
        return responseRecord(collection, record, isSuperuser(principal), safeQuery, principal);
    }

    public synchronized Map<String, Object> updateRecord(String collectionName, String id, JsonNode body, RequestPrincipal principal) {
        return updateRecord(collectionName, id, body, Map.of(), principal);
    }

    public synchronized Map<String, Object> upsertRecord(String collectionName, String id, JsonNode body, RequestPrincipal principal) {
        return upsertRecord(collectionName, id, body, Map.of(), principal);
    }

    public synchronized Map<String, Object> upsertRecord(
            String collectionName,
            String id,
            JsonNode body,
            Map<String, String> query,
            RequestPrincipal principal
    ) {
        return upsertRecord(collectionName, id, body, Map.of(), query, principal);
    }

    public synchronized Map<String, Object> upsertRecord(
            String collectionName,
            String id,
            JsonNode body,
            Map<String, List<UploadedFile>> files,
            Map<String, String> query,
            RequestPrincipal principal
    ) {
        if (body == null || !body.isObject()) {
            throw new ApiException(400, "Record payload must be a JSON object.");
        }
        CollectionSchema collection = findCollection(collectionName);
        String recordId = id == null || id.isBlank() ? requiredText(body, "id") : id;
        ObjectNode upsertBody = body.deepCopy();
        if (!upsertBody.hasNonNull("id")) {
            upsertBody.put("id", recordId);
        }
        if (findRecordOrNull(collection, recordId) == null) {
            return createRecord(collectionName, upsertBody, files, query, principal);
        }
        return updateRecord(collectionName, recordId, upsertBody, files, query, principal);
    }

    public synchronized Map<String, Object> updateRecord(
            String collectionName,
            String id,
            JsonNode body,
            Map<String, List<UploadedFile>> files,
            RequestPrincipal principal
    ) {
        return updateRecord(collectionName, id, body, files, Map.of(), principal);
    }

    public synchronized Map<String, Object> updateRecord(
            String collectionName,
            String id,
            JsonNode body,
            Map<String, List<UploadedFile>> files,
            Map<String, String> query,
            RequestPrincipal principal
    ) {
        Map<String, String> safeQuery = query == null ? Map.of() : query;
        CollectionSchema collection = findCollection(collectionName);
        Map<String, Object> existing = findRecord(collection, id);
        requireRecordRule(collection, collection.updateRule, existing, jsonToMap(body), safeQuery, "PATCH", principal, "update");
        Map<String, Object> patch = buildRecord(collection, bodyWithFileMarkers(collection, body, files), existing);
        FileChanges fileChanges = prepareFileChanges(collection, patch, files, body, existing);
        patch.putAll(fileChanges.values());
        existing.putAll(patch);
        existing.put("updated", now());
        writeFileChanges(collection, existing, fileChanges);
        saveRecords(collection);
        publishRealtime(collection, "update", existing);
        return responseRecord(collection, existing, isSuperuser(principal), safeQuery, principal);
    }

    public synchronized void deleteRecord(String collectionName, String id, RequestPrincipal principal) {
        CollectionSchema collection = findCollection(collectionName);
        Map<String, Object> existing = findRecord(collection, id);
        requireRecordRule(collection, collection.deleteRule, existing, null, Map.of(), "DELETE", principal, "delete");
        List<Map<String, Object>> records = records(collection);
        boolean removed = records.removeIf(record -> Objects.equals(record.get("id"), id));
        if (!removed) {
            throw new ApiException(404, "Record not found.");
        }
        saveRecords(collection);
        publishRealtime(collection, "delete", existing);
    }

    public synchronized <T> T transactional(java.util.function.Supplier<T> operation) {
        Map<String, CollectionSchema> collectionsSnapshot = new LinkedHashMap<>();
        mapper.convertValue(new ArrayList<>(collectionsByName.values()), COLLECTION_LIST)
                .forEach(collection -> collectionsSnapshot.put(collection.name, collection));
        Map<String, List<Map<String, Object>>> recordsSnapshot = mapper.convertValue(recordsByCollectionId, RECORD_MAP);
        Path storageSnapshot = null;
        try {
            storageSnapshot = snapshotStorage();
            return operation.get();
        } catch (RuntimeException e) {
            collectionsByName.clear();
            collectionsByName.putAll(collectionsSnapshot);
            recordsByCollectionId.clear();
            recordsByCollectionId.putAll(recordsSnapshot);
            restoreStorageSnapshot(storageSnapshot);
            saveAll();
            throw e;
        } finally {
            deleteRecursively(storageSnapshot);
        }
    }

    private Path snapshotStorage() {
        try {
            Path parent = dataDir.toAbsolutePath().getParent();
            Path snapshot = Files.createTempDirectory(parent == null ? Path.of(".").toAbsolutePath() : parent, "pb_storage_tx_");
            if (Files.exists(storageDir)) {
                copyDirectory(storageDir, snapshot);
            }
            return snapshot;
        } catch (IOException e) {
            throw new IllegalStateException("failed to snapshot storage", e);
        }
    }

    private void restoreStorageSnapshot(Path snapshot) {
        try {
            deleteRecursively(storageDir);
            Files.createDirectories(storageDir);
            if (snapshot != null && Files.exists(snapshot)) {
                copyDirectory(snapshot, storageDir);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to restore storage snapshot", e);
        }
    }

    public synchronized Map<String, Object> listBackups(int page, int perPage) {
        try {
            Files.createDirectories(backupsDir);
            List<Map<String, Object>> items;
            try (Stream<Path> paths = Files.list(backupsDir)) {
                items = paths
                        .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".zip"))
                        .map(this::backupInfo)
                        .sorted((left, right) -> String.valueOf(right.get("modified")).compareTo(String.valueOf(left.get("modified"))))
                        .collect(Collectors.toCollection(ArrayList::new));
            }
            int safePage = Math.max(1, page);
            int safePerPage = Math.max(1, perPage);
            int total = items.size();
            int from = Math.min(total, (safePage - 1) * safePerPage);
            int to = Math.min(total, from + safePerPage);
            return paginated(safePage, safePerPage, total, new ArrayList<>(items.subList(from, to)));
        } catch (IOException e) {
            throw new IllegalStateException("failed to list backups", e);
        }
    }

    public synchronized Map<String, Object> createBackup(JsonNode body) {
        saveAll();
        String requested = body == null || !body.hasNonNull("name") ? "" : body.get("name").asText();
        String key = backupKey(requested.isBlank() ? "backup_" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(java.time.ZoneOffset.UTC).format(Instant.now()) + ".zip" : requested);
        Path backup = backupPath(key);
        try {
            Files.createDirectories(backupsDir);
            try (OutputStream output = Files.newOutputStream(backup, StandardOpenOption.CREATE_NEW);
                 ZipOutputStream zip = new ZipOutputStream(output)) {
                zipDirectory(dataDir, zip);
            }
            return backupInfo(backup);
        } catch (IOException e) {
            throw new IllegalStateException("failed to create backup", e);
        }
    }

    public synchronized Map<String, Object> uploadBackup(String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ApiException(400, "Backup file is required.");
        }
        String key = backupKey(filename);
        Path backup = backupPath(key);
        try {
            Files.createDirectories(backupsDir);
            Files.write(backup, bytes, StandardOpenOption.CREATE_NEW);
            validateBackupZip(backup);
            return backupInfo(backup);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(backup);
            } catch (IOException ignored) {
                // best effort cleanup
            }
            throw new IllegalStateException("failed to upload backup", e);
        } catch (RuntimeException e) {
            try {
                Files.deleteIfExists(backup);
            } catch (IOException ignored) {
                // best effort cleanup
            }
            throw e;
        }
    }

    public synchronized Path backupFile(String key) {
        Path backup = backupPath(key);
        return Files.exists(backup) && Files.isRegularFile(backup) ? backup : null;
    }

    public synchronized void deleteBackup(String key) {
        try {
            boolean deleted = Files.deleteIfExists(backupPath(key));
            if (!deleted) {
                throw new ApiException(404, "Backup not found.");
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to delete backup", e);
        }
    }

    public synchronized Map<String, Object> restoreBackup(String key) {
        Path backup = backupFile(key);
        if (backup == null) {
            throw new ApiException(404, "Backup not found.");
        }
        Path temp = null;
        try {
            String currentSecret = Files.exists(secretFile) ? Files.readString(secretFile, StandardCharsets.UTF_8) : "";
            Path parent = dataDir.toAbsolutePath().getParent();
            temp = Files.createTempDirectory(parent == null ? Path.of(".").toAbsolutePath() : parent, "pb_restore_");
            unzipBackup(backup, temp);
            clearDataDirForRestore();
            copyDirectory(temp, dataDir);
            if (!currentSecret.isBlank()) {
                Files.writeString(secretFile, currentSecret, StandardCharsets.UTF_8);
            }
            Files.createDirectories(recordsDir);
            Files.createDirectories(storageDir);
            Files.createDirectories(backupsDir);
            load();
            return Map.of("restored", key);
        } catch (IOException e) {
            throw new IllegalStateException("failed to restore backup", e);
        } finally {
            if (temp != null) {
                deleteRecursively(temp);
            }
        }
    }

    public synchronized Map<String, Object> authMethods(String collectionName) {
        CollectionSchema collection = findCollection(collectionName);
        if (!"auth".equals(collection.type)) {
            throw new ApiException(400, "Collection is not an auth collection.");
        }
        Map<String, Object> password = new LinkedHashMap<>();
        password.put("enabled", true);
        password.put("identityFields", List.of("email"));
        return Map.of(
                "password", password,
                "oauth2", List.of(),
                "mfa", Map.of("enabled", false)
        );
    }

    public synchronized Map<String, Object> fileToken(RequestPrincipal principal) {
        if (principal == null || principal.id().isBlank()) {
            throw new ApiException(401, "Missing or invalid auth token.");
        }
        Map<String, Object> claims = new LinkedHashMap<>(principal.claims());
        claims.remove("iat");
        claims.remove("exp");
        claims.put("tokenType", "file");
        return Map.of("token", tokenService.create(claims, Duration.ofMinutes(2)));
    }

    public synchronized Path filePath(String collectionIdOrName, String recordId, String filename, RequestPrincipal principal) {
        CollectionSchema collection = findCollectionOrNull(collectionIdOrName);
        if (collection == null || recordId == null || recordId.isBlank() || filename == null || filename.isBlank()) {
            return null;
        }
        Map<String, Object> record = records(collection).stream()
                .filter(item -> Objects.equals(item.get("id"), recordId))
                .findFirst()
                .orElse(null);
        if (record == null) {
            return null;
        }
        List<FieldSchema> fields = fileFieldsReferencing(collection, record, filename);
        if (fields.isEmpty()) {
            return null;
        }
        if (fields.stream().allMatch(this::protectedFileField)) {
            requireProtectedFileAccess(collection, record, principal);
        }
        Path file = storageDir.resolve(collection.id).resolve(recordId).resolve(filename).normalize();
        Path recordDir = storageDir.resolve(collection.id).resolve(recordId).normalize();
        if (!file.startsWith(recordDir)) {
            return null;
        }
        return file;
    }

    public synchronized boolean fileThumbAllowed(
            String collectionIdOrName,
            String recordId,
            String filename,
            String thumb
    ) {
        if (thumb == null || thumb.isBlank()) {
            return false;
        }
        CollectionSchema collection = findCollectionOrNull(collectionIdOrName);
        if (collection == null || recordId == null || recordId.isBlank() || filename == null || filename.isBlank()) {
            return false;
        }
        Map<String, Object> record = records(collection).stream()
                .filter(item -> Objects.equals(item.get("id"), recordId))
                .findFirst()
                .orElse(null);
        if (record == null) {
            return false;
        }
        return fileFieldsReferencing(collection, record, filename).stream()
                .anyMatch(field -> thumbs(field).contains(thumb));
    }

    public synchronized Map<String, Object> authWithPassword(String collectionName, JsonNode body) {
        return authWithPassword(collectionName, body, Map.of());
    }

    public synchronized Map<String, Object> authWithPassword(
            String collectionName,
            JsonNode body,
            Map<String, String> query
    ) {
        CollectionSchema collection = findCollection(collectionName);
        if (!"auth".equals(collection.type)) {
            throw new ApiException(400, "Collection is not an auth collection.");
        }
        String identity = requiredText(body, "identity");
        String password = requiredText(body, "password");
        String passwordField = collection.fields.stream()
                .filter(field -> "password".equals(normalizeType(field.type)))
                .map(field -> field.name)
                .findFirst()
                .orElse("password");
        Map<String, Object> record = records(collection).stream()
                .filter(item -> identity.equalsIgnoreCase(String.valueOf(item.getOrDefault("email", "")))
                        || identity.equalsIgnoreCase(String.valueOf(item.getOrDefault("username", "")))
                        || identity.equals(String.valueOf(item.getOrDefault("id", ""))))
                .findFirst()
                .orElseThrow(() -> new ApiException(400, "Invalid identity or password."));
        if (!PasswordHasher.verify(password, String.valueOf(record.get(passwordField)))) {
            throw new ApiException(400, "Invalid identity or password.");
        }

        return authResponse(collection, record, query);
    }

    public synchronized Map<String, Object> authRefresh(String collectionName, RequestPrincipal principal) {
        return authRefresh(collectionName, principal, Map.of());
    }

    public synchronized Map<String, Object> authRefresh(
            String collectionName,
            RequestPrincipal principal,
            Map<String, String> query
    ) {
        if (principal == null) {
            throw new ApiException(401, "Missing or invalid auth token.");
        }
        CollectionSchema collection = findCollection(collectionName);
        if (!"auth".equals(collection.type)) {
            throw new ApiException(400, "Collection is not an auth collection.");
        }
        boolean sameCollection = Objects.equals(principal.collectionId(), collection.id)
                || Objects.equals(principal.collectionName(), collection.name);
        if (!sameCollection) {
            throw new ApiException(401, "Auth token does not belong to this collection.");
        }
        Map<String, Object> record = records(collection).stream()
                .filter(item -> Objects.equals(principal.id(), String.valueOf(item.get("id"))))
                .findFirst()
                .orElseThrow(() -> new ApiException(401, "Auth record no longer exists."));
        return authResponse(collection, record, query);
    }

    private Map<String, Object> authResponse(
            CollectionSchema collection,
            Map<String, Object> record,
            Map<String, String> query
    ) {
        Map<String, String> safeQuery = query == null ? Map.of() : query;
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", record.get("id"));
        claims.put("collectionId", collection.id);
        claims.put("collectionName", collection.name);
        claims.put("type", SUPERUSERS.equals(collection.name) ? "superuser" : "authRecord");
        claims.put("email", record.get("email"));

        RequestPrincipal authPrincipal = RequestPrincipal.fromClaims(claims);
        Map<String, String> recordQuery = new LinkedHashMap<>(safeQuery);
        recordQuery.remove("fields");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token", tokenService.create(claims, Duration.ofDays(7)));
        response.put("record", responseRecord(collection, record, false, recordQuery, authPrincipal));
        response.put("meta", Map.of());
        return selectFields(response, safeQuery.get("fields"));
    }

    private void load() throws IOException {
        collectionsByName.clear();
        recordsByCollectionId.clear();
        if (Files.exists(schemaFile)) {
            JsonNode root = mapper.readTree(schemaFile.toFile());
            JsonNode collectionNode = root.has("collections") ? root.get("collections") : root;
            for (CollectionSchema collection : mapper.convertValue(collectionNode, COLLECTION_LIST)) {
                normalizeCollection(collection, true);
                collectionsByName.put(collection.name, collection);
            }
        }
        ensureSuperuserCollection();
        for (CollectionSchema collection : collectionsByName.values()) {
            Path file = recordsFile(collection);
            if (Files.exists(file)) {
                recordsByCollectionId.put(collection.id, mapper.readValue(file.toFile(), RECORD_LIST));
            } else {
                recordsByCollectionId.put(collection.id, new ArrayList<>());
            }
        }
        saveAll();
    }

    private Map<String, Object> createSuperuser(JsonNode body) {
        CollectionSchema collection = findCollection(SUPERUSERS);
        Map<String, Object> record = createRecordInternal(collection, body, false, null);
        records(collection).add(record);
        saveRecords(collection);
        return publicRecord(collection, record, false);
    }

    private Map<String, Object> buildRecord(CollectionSchema collection, JsonNode body, Map<String, Object> existing) {
        return createRecordInternal(collection, body, existing != null, existing == null ? null : String.valueOf(existing.get("id")));
    }

    private Map<String, Object> createRecordInternal(CollectionSchema collection, JsonNode body, boolean update, String currentId) {
        if (body == null || !body.isObject()) {
            throw new ApiException(400, "Record payload must be a JSON object.");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> errors = new LinkedHashMap<>();

        for (FieldSchema field : collection.fields) {
            JsonNode value = body.get(field.name);
            if (value == null || value.isMissingNode()) {
                if (!update && field.required) {
                    errors.put(field.name, validationError(field.name + " is required."));
                }
                continue;
            }
            Object normalized = normalizeFieldValue(field, value, update, errors);
            if (normalized != Unchanged.INSTANCE) {
                values.put(field.name, normalized);
            }
        }

        enforceUnique(collection, values, update ? currentId : null, errors);
        if (!errors.isEmpty()) {
            throw new ApiException(400, "Record validation failed.", errors);
        }

        if (!update) {
            String timestamp = now();
            values.put("id", body.hasNonNull("id") ? body.get("id").asText() : IdGenerator.id());
            values.put("collectionId", collection.id);
            values.put("collectionName", collection.name);
            values.put("created", timestamp);
            values.put("updated", timestamp);
        }
        return values;
    }

    private Object normalizeFieldValue(FieldSchema field, JsonNode value, boolean update, Map<String, Object> errors) {
        String type = normalizeType(field.type);
        if (value.isNull() || isBlankText(value)) {
            if (field.required && !(update && "password".equals(type))) {
                errors.put(field.name, validationError(field.name + " is required."));
            }
            return update && "password".equals(type) ? Unchanged.INSTANCE : null;
        }

        try {
            return switch (type) {
                case "email" -> {
                    String email = value.asText().trim().toLowerCase(Locale.ROOT);
                    if (!email.contains("@") || email.startsWith("@") || email.endsWith("@")) {
                        errors.put(field.name, validationError("Invalid email address."));
                    }
                    yield email;
                }
                case "password" -> PasswordHasher.hash(value.asText());
                case "bool", "boolean" -> normalizeBoolean(field, value, errors);
                case "number" -> normalizeNumber(field, value, errors);
                case "select" -> normalizeSelect(field, value, errors);
                case "json", "relation", "file" -> mapper.convertValue(value, Object.class);
                default -> value.asText();
            };
        } catch (IllegalArgumentException e) {
            errors.put(field.name, validationError(e.getMessage()));
            return null;
        }
    }

    private Object normalizeBoolean(FieldSchema field, JsonNode value, Map<String, Object> errors) {
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isTextual() && ("true".equalsIgnoreCase(value.asText()) || "false".equalsIgnoreCase(value.asText()))) {
            return Boolean.parseBoolean(value.asText());
        }
        errors.put(field.name, validationError("Expected boolean value."));
        return null;
    }

    private Object normalizeNumber(FieldSchema field, JsonNode value, Map<String, Object> errors) {
        if (value.isNumber()) {
            return value.numberValue();
        }
        if (value.isTextual()) {
            try {
                return Double.parseDouble(value.asText());
            } catch (NumberFormatException ignored) {
                errors.put(field.name, validationError("Expected numeric value."));
                return null;
            }
        }
        errors.put(field.name, validationError("Expected numeric value."));
        return null;
    }

    private Object normalizeSelect(FieldSchema field, JsonNode value, Map<String, Object> errors) {
        Object converted = mapper.convertValue(value, Object.class);
        JsonNode values = field.options == null ? null : field.options.get("values");
        if (values != null && values.isArray()) {
            Set<String> allowed = new LinkedHashSet<>();
            values.forEach(item -> allowed.add(item.asText()));
            if (value.isArray()) {
                for (JsonNode item : value) {
                    if (!allowed.contains(item.asText())) {
                        errors.put(field.name, validationError("Value is not in the allowed list."));
                    }
                }
            } else if (!allowed.contains(value.asText())) {
                errors.put(field.name, validationError("Value is not in the allowed list."));
            }
        }
        return converted;
    }

    private void enforceUnique(CollectionSchema collection, Map<String, Object> values, String currentId, Map<String, Object> errors) {
        for (FieldSchema field : collection.fields) {
            if (!field.unique || !values.containsKey(field.name) || values.get(field.name) == null) {
                continue;
            }
            Object candidate = values.get(field.name);
            for (Map<String, Object> record : records(collection)) {
                if (currentId != null && Objects.equals(currentId, record.get("id"))) {
                    continue;
                }
                if (Objects.equals(candidate, record.get(field.name))) {
                    errors.put(field.name, validationError("Value must be unique."));
                    break;
                }
            }
        }
    }

    private Map<String, Object> publicRecord(CollectionSchema collection, Map<String, Object> record, boolean includeHidden) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", record.get("id"));
        out.put("collectionId", collection.id);
        out.put("collectionName", collection.name);
        out.put("created", record.get("created"));
        out.put("updated", record.get("updated"));
        for (FieldSchema field : collection.fields) {
            if ("password".equals(normalizeType(field.type))) {
                continue;
            }
            if (field.hidden && !includeHidden) {
                continue;
            }
            if (record.containsKey(field.name)) {
                out.put(field.name, record.get(field.name));
            }
        }
        return out;
    }

    private Map<String, Object> responseRecord(
            CollectionSchema collection,
            Map<String, Object> record,
            boolean includeHidden,
            Map<String, String> query,
            RequestPrincipal principal
    ) {
        Map<String, Object> out = publicRecord(collection, record, includeHidden);
        Map<String, String> safeQuery = query == null ? Map.of() : query;
        applyExpand(collection, record, out, expandPaths(safeQuery.get("expand")), safeQuery, principal, includeHidden, 0);
        return selectFields(out, safeQuery.get("fields"));
    }

    private void applyExpand(
            CollectionSchema collection,
            Map<String, Object> source,
            Map<String, Object> output,
            List<List<String>> paths,
            Map<String, String> query,
            RequestPrincipal principal,
            boolean includeHidden,
            int depth
    ) {
        if (paths.isEmpty() || depth >= MAX_EXPAND_DEPTH) {
            return;
        }

        Map<String, List<List<String>>> grouped = new LinkedHashMap<>();
        for (List<String> path : paths) {
            if (path.isEmpty()) {
                continue;
            }
            List<String> tail = path.size() == 1 ? List.of() : path.subList(1, path.size());
            grouped.computeIfAbsent(path.get(0), ignored -> new ArrayList<>()).add(tail);
        }

        Map<String, Object> expanded = new LinkedHashMap<>();
        for (Map.Entry<String, List<List<String>>> entry : grouped.entrySet()) {
            FieldSchema field = relationField(collection, entry.getKey());
            if (field == null) {
                continue;
            }
            CollectionSchema target = relationTarget(field);
            if (target == null) {
                continue;
            }
            Object rawValue = source.get(field.name);
            List<Map<String, Object>> related = new ArrayList<>();
            for (String id : relationIds(rawValue)) {
                Map<String, Object> relatedRecord = findRecordOrNull(target, id);
                if (relatedRecord == null || !canViewExpandedRecord(target, relatedRecord, query, principal)) {
                    continue;
                }
                Map<String, Object> relatedOutput = publicRecord(target, relatedRecord, includeHidden);
                applyExpand(target, relatedRecord, relatedOutput, entry.getValue(), query, principal, includeHidden, depth + 1);
                related.add(relatedOutput);
            }
            if (relationMultiple(field, rawValue)) {
                expanded.put(field.name, related);
            } else if (!related.isEmpty()) {
                expanded.put(field.name, related.get(0));
            }
        }

        if (!expanded.isEmpty()) {
            output.put("expand", expanded);
        }
    }

    private List<List<String>> expandPaths(String expand) {
        if (expand == null || expand.isBlank()) {
            return List.of();
        }
        List<List<String>> paths = new ArrayList<>();
        for (String rawPath : expand.split(",")) {
            String trimmed = rawPath.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            List<String> segments = new ArrayList<>();
            for (String segment : trimmed.split("\\.")) {
                String name = segment.trim();
                if (!name.isBlank()) {
                    segments.add(name);
                }
                if (segments.size() >= MAX_EXPAND_DEPTH) {
                    break;
                }
            }
            if (!segments.isEmpty()) {
                paths.add(segments);
            }
        }
        return paths;
    }

    private FieldSchema relationField(CollectionSchema collection, String name) {
        return collection.fields.stream()
                .filter(field -> Objects.equals(field.name, name) && "relation".equals(normalizeType(field.type)))
                .findFirst()
                .orElse(null);
    }

    private CollectionSchema relationTarget(FieldSchema field) {
        List<String> candidates = new ArrayList<>();
        if (field.collectionId != null && !field.collectionId.isBlank()) {
            candidates.add(field.collectionId);
        }
        if (field.collectionIds != null) {
            field.collectionIds.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .forEach(candidates::add);
        }
        addOptionCandidates(candidates, field.options == null ? null : field.options.get("collectionId"));
        addOptionCandidates(candidates, field.options == null ? null : field.options.get("collectionIds"));
        addOptionCandidates(candidates, field.options == null ? null : field.options.get("collection"));
        addOptionCandidates(candidates, field.options == null ? null : field.options.get("collectionName"));
        for (String candidate : candidates) {
            CollectionSchema collection = findCollectionOrNull(candidate);
            if (collection != null) {
                return collection;
            }
        }
        return null;
    }

    private void addOptionCandidates(List<String> candidates, JsonNode value) {
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isArray()) {
            value.forEach(item -> addOptionCandidates(candidates, item));
            return;
        }
        String text = value.asText();
        if (!text.isBlank()) {
            candidates.add(text);
        }
    }

    private List<String> relationIds(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .map(String::valueOf)
                    .filter(item -> !item.isBlank())
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        String text = String.valueOf(value);
        return text.isBlank() ? List.of() : List.of(text);
    }

    private boolean relationMultiple(FieldSchema field, Object rawValue) {
        return rawValue instanceof Collection<?> || maxSelect(field) > 1;
    }

    private boolean canViewExpandedRecord(
            CollectionSchema collection,
            Map<String, Object> record,
            Map<String, String> query,
            RequestPrincipal principal
    ) {
        if (isSuperuser(principal)) {
            return true;
        }
        return collection.viewRule != null
                && matchesRule(collection.viewRule, record, null, query == null ? Map.of() : query, "GET", principal);
    }

    private JsonNode bodyWithFileMarkers(
            CollectionSchema collection,
            JsonNode body,
            Map<String, List<UploadedFile>> files
    ) {
        if (files == null || files.isEmpty() || body == null || !body.isObject()) {
            return body;
        }
        ObjectNode copy = body.deepCopy();
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
                throw new ApiException(400, "Too many files uploaded for field `" + field.name + "`.");
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
        Path recordDir = storageDir.resolve(collection.id).resolve(recordId);
        try {
            Files.createDirectories(recordDir);
            for (Map.Entry<String, UploadedFile> entry : changes.writes().entrySet()) {
                Path target = recordDir.resolve(entry.getKey()).normalize();
                if (!target.startsWith(recordDir.normalize())) {
                    throw new ApiException(400, "Invalid upload filename.");
                }
                Files.write(target, entry.getValue().bytes(), StandardOpenOption.CREATE_NEW);
            }
            for (List<String> names : changes.removals().values()) {
                for (String name : names) {
                    Files.deleteIfExists(recordDir.resolve(name).normalize());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to write uploaded files", e);
        }
    }

    private List<FieldSchema> fileFieldsReferencing(CollectionSchema collection, Map<String, Object> record, String filename) {
        List<FieldSchema> fields = new ArrayList<>();
        for (FieldSchema field : collection.fields) {
            if (!"file".equals(normalizeType(field.type))) {
                continue;
            }
            if (fileList(record.get(field.name)).contains(filename)) {
                fields.add(field);
            }
        }
        return fields;
    }

    private boolean protectedFileField(FieldSchema field) {
        if (Boolean.TRUE.equals(field.protectedFile)) {
            return true;
        }
        JsonNode value = field.options == null ? null : field.options.get("protected");
        return value != null && value.asBoolean(false);
    }

    private void requireProtectedFileAccess(CollectionSchema collection, Map<String, Object> record, RequestPrincipal principal) {
        if (isSuperuser(principal)) {
            return;
        }
        if (principal == null) {
            throw new ApiException(403, "Protected file token required.");
        }
        if (collection.viewRule == null) {
            throw new ApiException(403, "Protected file is not accessible.");
        }
        if (!matchesRule(collection.viewRule, record, null, Map.of(), "GET", principal)) {
            throw new ApiException(403, "Protected file is not accessible.");
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
            return responseRecord(collection, record, isSuperuser(principal), subscription.query(), principal);
        });
    }

    private boolean canReceiveRealtime(
            CollectionSchema collection,
            Map<String, Object> record,
            RealtimeHub.Subscription subscription,
            RequestPrincipal principal
    ) {
        if (isSuperuser(principal)) {
            return matchesRealtimeFilter(subscription, record, principal);
        }
        String rule = subscription.wildcard() ? collection.listRule : collection.viewRule;
        if (rule == null) {
            return false;
        }
        return matchesRule(rule, record, null, subscription.query(), "GET", principal)
                && matchesRealtimeFilter(subscription, record, principal);
    }

    private boolean matchesRealtimeFilter(
            RealtimeHub.Subscription subscription,
            Map<String, Object> record,
            RequestPrincipal principal
    ) {
        String filter = subscription.filter();
        return filter == null || filter.isBlank()
                || RuleEvaluator.matches(filter, ruleContext(record, null, subscription.query(), "GET", principal));
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

    private Object fileFieldValue(FieldSchema field, List<String> names) {
        int maxSelect = maxSelect(field);
        if (maxSelect == 1) {
            return names.isEmpty() ? "" : names.get(0);
        }
        return new ArrayList<>(names);
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
        if (value.asText().isBlank()) {
            return List.of();
        }
        return List.of(value.asText());
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
            throw new ApiException(400, "File `" + file.originalFilename() + "` exceeds maxSize for field `" + field.name + "`.");
        }
        List<String> mimeTypes = mimeTypes(field);
        if (!mimeTypes.isEmpty() && mimeTypes.stream().noneMatch(pattern -> matchesMimeType(pattern, file.contentType()))) {
            throw new ApiException(400, "File `" + file.originalFilename() + "` MIME type is not allowed for field `" + field.name + "`.");
        }
    }

    private long maxSize(FieldSchema field) {
        if (field.maxSize != null) {
            return Math.max(0L, field.maxSize);
        }
        JsonNode value = field.options == null ? null : field.options.get("maxSize");
        if (value != null && value.canConvertToLong()) {
            return Math.max(0L, value.asLong());
        }
        return 0L;
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

    private List<String> thumbs(FieldSchema field) {
        if (field.thumbs != null && !field.thumbs.isEmpty()) {
            return field.thumbs.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        JsonNode value = field.options == null ? null : field.options.get("thumbs");
        if (value == null || value.isNull()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (value.isArray()) {
            value.forEach(item -> {
                String text = item.asText("").trim();
                if (!text.isBlank()) {
                    out.add(text);
                }
            });
        } else {
            String text = value.asText("").trim();
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
        String sanitized = file.replaceAll("[^A-Za-z0-9._-]", "_");
        sanitized = sanitized.replaceAll("_+", "_");
        if (sanitized.isBlank() || ".".equals(sanitized) || "..".equals(sanitized)) {
            return "file";
        }
        return sanitized.length() > 80 ? sanitized.substring(sanitized.length() - 80) : sanitized;
    }

    private void requireRecordRule(
            CollectionSchema collection,
            String rule,
            Map<String, Object> record,
            Map<String, Object> body,
            Map<String, String> query,
            String method,
            RequestPrincipal principal,
            String action
    ) {
        if (isSuperuser(principal)) {
            return;
        }
        if (rule == null) {
            throw new ApiException(403, "Only superusers can " + action + " this record.");
        }
        if (!matchesRule(rule, record, body, query, method, principal)) {
            throw new ApiException(404, "Record not found.");
        }
    }

    private boolean matchesRule(
            String rule,
            Map<String, Object> record,
            Map<String, Object> body,
            Map<String, String> query,
            String method,
            RequestPrincipal principal
    ) {
        return RuleEvaluator.matches(rule, ruleContext(record, body, query, method, principal));
    }

    private boolean isSuperuser(RequestPrincipal principal) {
        return principal != null && principal.superuser();
    }

    private Map<String, Object> jsonToMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        return mapper.convertValue(node, new TypeReference<>() {
        });
    }

    private Map<String, Object> collectionMap(CollectionSchema collection) {
        return mapper.convertValue(copyCollection(collection), new TypeReference<>() {
        });
    }

    private boolean matchesCollectionFilter(Map<String, Object> collection, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        return RuleEvaluator.matches(filter, ruleContext(collection, null, Map.of(), "GET", null));
    }

    private Map<String, Object> selectFields(Map<String, Object> source, String fields) {
        List<String> selected = selectedFields(fields);
        if (selected.isEmpty()) {
            return source;
        }
        if (selected.contains("*")) {
            return source;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String field : selected) {
            copySelectedField(source, out, field.split("\\."), 0);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void copySelectedField(Object source, Map<String, Object> target, String[] path, int index) {
        if (!(source instanceof Map<?, ?> sourceMap) || index >= path.length) {
            return;
        }
        String key = path[index].trim();
        if (key.isBlank()) {
            return;
        }
        if ("*".equals(key)) {
            if (index == path.length - 1) {
                sourceMap.forEach((sourceKey, value) -> target.put(String.valueOf(sourceKey), value));
            }
            return;
        }
        if (!sourceMap.containsKey(key)) {
            return;
        }
        Object value = sourceMap.get(key);
        if (index == path.length - 1) {
            target.put(key, value);
            return;
        }
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> child = target.get(key) instanceof Map<?, ?> existing
                    ? (Map<String, Object>) existing
                    : new LinkedHashMap<>();
            copySelectedField(mapValue, child, path, index + 1);
            if (!child.isEmpty()) {
                target.put(key, child);
            }
            return;
        }
        if (value instanceof List<?> listValue) {
            List<Object> children = new ArrayList<>();
            for (Object item : listValue) {
                if (!(item instanceof Map<?, ?> mapItem)) {
                    continue;
                }
                Map<String, Object> child = new LinkedHashMap<>();
                copySelectedField(mapItem, child, path, index + 1);
                if (!child.isEmpty()) {
                    children.add(child);
                }
            }
            if (!children.isEmpty()) {
                target.put(key, children);
            }
        }
    }

    private List<String> selectedFields(String fields) {
        if (fields == null || fields.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String raw : fields.split(",")) {
            String field = raw.trim();
            if (!field.isBlank()) {
                out.add(field);
            }
        }
        return out;
    }

    private void normalizeCollection(CollectionSchema collection, boolean existing) {
        if (collection.id == null || collection.id.isBlank()) {
            collection.id = IdGenerator.prefixed("pbc_");
        }
        if (collection.name == null || collection.name.isBlank() || !NAME_PATTERN.matcher(collection.name).matches()) {
            throw new ApiException(400, "Invalid collection name.", fieldError("name", "Use letters, numbers and underscore."));
        }
        collection.type = normalizeType(collection.type == null || collection.type.isBlank() ? "base" : collection.type);
        if (!"base".equals(collection.type) && !"auth".equals(collection.type) && !"view".equals(collection.type)) {
            throw new ApiException(400, "Unsupported collection type.", fieldError("type", "Supported types are base, auth and view."));
        }
        if (collection.fields == null) {
            collection.fields = new ArrayList<>();
        }
        if ("auth".equals(collection.type)) {
            ensureAuthFields(collection);
        }
        Set<String> names = new LinkedHashSet<>();
        for (FieldSchema field : collection.fields) {
            normalizeField(field, names);
        }
        String timestamp = now();
        if (!existing || collection.created == null || collection.created.isBlank()) {
            collection.created = timestamp;
        }
        if (collection.updated == null || collection.updated.isBlank()) {
            collection.updated = timestamp;
        }
    }

    private void normalizeField(FieldSchema field, Set<String> names) {
        if (field.id == null || field.id.isBlank()) {
            field.id = IdGenerator.prefixed("field_");
        }
        if (field.name == null || field.name.isBlank() || !NAME_PATTERN.matcher(field.name).matches()) {
            throw new ApiException(400, "Invalid field name.", fieldError("fields", "Use letters, numbers and underscore."));
        }
        if (!names.add(field.name)) {
            throw new ApiException(400, "Duplicate field name.", fieldError(field.name, "Duplicate field name."));
        }
        field.type = normalizeType(field.type == null || field.type.isBlank() ? "text" : field.type);
        if (field.options == null) {
            field.options = new LinkedHashMap<>();
        }
    }

    private void ensureAuthFields(CollectionSchema collection) {
        boolean hasEmail = collection.fields.stream().anyMatch(field -> "email".equals(field.name));
        boolean hasPassword = collection.fields.stream().anyMatch(field -> "password".equals(field.name));
        if (!hasEmail) {
            collection.fields.add(new FieldSchema("field_email", "email", "email", true, true, false));
        }
        if (!hasPassword) {
            collection.fields.add(new FieldSchema("field_password", "password", "password", true, false, true));
        }
    }

    private void ensureSuperuserCollection() {
        if (collectionsByName.containsKey(SUPERUSERS)) {
            return;
        }
        CollectionSchema superusers = new CollectionSchema();
        superusers.id = "pbc_superusers";
        superusers.name = SUPERUSERS;
        superusers.type = "auth";
        superusers.system = true;
        superusers.fields.add(new FieldSchema("field_email", "email", "email", true, true, false));
        superusers.fields.add(new FieldSchema("field_password", "password", "password", true, false, true));
        superusers.fields.add(new FieldSchema("field_name", "name", "text", false, false, false));
        superusers.fields.add(new FieldSchema("field_verified", "verified", "bool", false, false, false));
        normalizeCollection(superusers, false);
        collectionsByName.put(superusers.name, superusers);
    }

    private CollectionSchema findCollection(String idOrName) {
        CollectionSchema collection = findCollectionOrNull(idOrName);
        if (collection == null) {
            throw new ApiException(404, "Collection not found.");
        }
        return collection;
    }

    private CollectionSchema findCollectionOrNull(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) {
            return null;
        }
        CollectionSchema direct = collectionsByName.get(idOrName);
        if (direct != null) {
            return direct;
        }
        for (CollectionSchema collection : collectionsByName.values()) {
            if (idOrName.equals(collection.id)) {
                return collection;
            }
        }
        return null;
    }

    private Map<String, Object> findRecord(CollectionSchema collection, String id) {
        return records(collection).stream()
                .filter(record -> Objects.equals(record.get("id"), id))
                .findFirst()
                .orElseThrow(() -> new ApiException(404, "Record not found."));
    }

    private Map<String, Object> findRecordOrNull(CollectionSchema collection, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return records(collection).stream()
                .filter(record -> Objects.equals(record.get("id"), id))
                .findFirst()
                .orElse(null);
    }

    private List<Map<String, Object>> records(CollectionSchema collection) {
        return recordsByCollectionId.computeIfAbsent(collection.id, ignored -> new ArrayList<>());
    }

    private boolean matchesFilter(Map<String, Object> record, String filter, Map<String, String> query, RequestPrincipal principal) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        return RuleEvaluator.matches(filter, ruleContext(record, null, query, "GET", principal));
    }

    private RuleEvaluator.Context ruleContext(
            Map<String, Object> record,
            Map<String, Object> body,
            Map<String, String> query,
            String method,
            RequestPrincipal principal
    ) {
        return RuleEvaluator.context(record, body, query, method, principal, collectionName -> {
            CollectionSchema collection = findCollectionOrNull(collectionName);
            return collection == null ? List.of() : records(collection);
        });
    }

    private void sort(List<Map<String, Object>> records, String sort) {
        if (sort == null || sort.isBlank()) {
            records.sort(Comparator.comparing(record -> String.valueOf(record.getOrDefault("created", ""))));
            return;
        }
        String[] parts = sort.split(",");
        Comparator<Map<String, Object>> comparator = null;
        for (String raw : parts) {
            String part = raw.trim();
            if (part.isEmpty()) {
                continue;
            }
            boolean desc = part.startsWith("-");
            String field = desc ? part.substring(1) : part;
            Comparator<Map<String, Object>> next = Comparator.comparing(record -> String.valueOf(record.getOrDefault(field, "")));
            if (desc) {
                next = next.reversed();
            }
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        if (comparator != null) {
            records.sort(comparator);
        }
    }

    private Map<String, Object> paginated(int page, int perPage, int total, List<?> items) {
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / perPage);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("page", page);
        response.put("perPage", perPage);
        response.put("totalItems", total);
        response.put("totalPages", totalPages);
        response.put("items", items);
        return response;
    }

    private Map<String, Object> backupInfo(Path path) {
        try {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("key", path.getFileName().toString());
            info.put("name", path.getFileName().toString());
            info.put("size", Files.size(path));
            info.put("modified", DateTimeFormatter.ISO_INSTANT.format(Files.getLastModifiedTime(path).toInstant()));
            return info;
        } catch (IOException e) {
            throw new IllegalStateException("failed to read backup metadata", e);
        }
    }

    private Path backupPath(String key) {
        String safeKey = backupKey(key);
        Path path = backupsDir.resolve(safeKey).normalize();
        if (!path.startsWith(backupsDir.normalize())) {
            throw new ApiException(400, "Invalid backup key.");
        }
        return path;
    }

    private String backupKey(String value) {
        String filename = value == null || value.isBlank() ? "backup.zip" : Path.of(value).getFileName().toString();
        String sanitized = filename.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("_+", "_");
        if (sanitized.isBlank() || ".".equals(sanitized) || "..".equals(sanitized)) {
            sanitized = "backup";
        }
        if (!sanitized.endsWith(".zip")) {
            sanitized += ".zip";
        }
        return sanitized.length() > 120 ? sanitized.substring(sanitized.length() - 120) : sanitized;
    }

    private void zipDirectory(Path root, ZipOutputStream zip) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths
                    .filter(Files::isRegularFile)
                    .filter(path -> !isBackupExcluded(path))
                    .collect(Collectors.toList())) {
                String entryName = root.relativize(path).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(entryName));
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
    }

    private boolean isBackupExcluded(Path path) {
        Path normalized = path.normalize();
        return normalized.startsWith(backupsDir.normalize()) || normalized.equals(secretFile.normalize());
    }

    private void validateBackupZip(Path backup) {
        try (InputStream input = Files.newInputStream(backup);
             ZipInputStream zip = new ZipInputStream(input)) {
            validateZipEntries(zip, Path.of(".").toAbsolutePath().normalize());
        } catch (IOException e) {
            throw new ApiException(400, "Invalid backup archive.");
        }
    }

    private void unzipBackup(Path backup, Path target) throws IOException {
        try (InputStream input = Files.newInputStream(backup);
             ZipInputStream zip = new ZipInputStream(input)) {
            validateZipEntries(zip, target.toAbsolutePath().normalize());
        }
        try (InputStream input = Files.newInputStream(backup);
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path out = safeZipTarget(target, entry);
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zip, out);
                }
                zip.closeEntry();
            }
        }
    }

    private void validateZipEntries(ZipInputStream zip, Path target) throws IOException {
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            safeZipTarget(target, entry);
            zip.closeEntry();
        }
    }

    private Path safeZipTarget(Path target, ZipEntry entry) {
        String name = entry.getName();
        if (name == null || name.isBlank() || name.startsWith("/") || name.contains("\\")) {
            throw new ApiException(400, "Invalid backup archive.");
        }
        if ("pb_secret".equals(name) || name.startsWith("backups/")) {
            throw new ApiException(400, "Invalid backup archive.");
        }
        Path out = target.resolve(name).normalize();
        if (!out.startsWith(target)) {
            throw new ApiException(400, "Invalid backup archive.");
        }
        return out;
    }

    private void clearDataDirForRestore() throws IOException {
        Files.createDirectories(dataDir);
        try (Stream<Path> paths = Files.list(dataDir)) {
            for (Path path : paths.collect(Collectors.toList())) {
                if (path.normalize().startsWith(backupsDir.normalize())) {
                    continue;
                }
                deleteRecursively(path);
            }
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.collect(Collectors.toList())) {
                Path relative = source.relativize(path);
                if (relative.toString().isBlank()) {
                    continue;
                }
                Path out = target.resolve(relative).normalize();
                if (Files.isDirectory(path)) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(path, out);
                }
            }
        }
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path item : paths
                    .sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList())) {
                Files.deleteIfExists(item);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to delete " + path, e);
        }
    }

    private void saveAll() {
        saveSchema();
        for (CollectionSchema collection : collectionsByName.values()) {
            saveRecords(collection);
        }
    }

    private void saveSchema() {
        try {
            Files.createDirectories(dataDir);
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("collections", collectionsByName.values());
            mapper.writerWithDefaultPrettyPrinter().writeValue(schemaFile.toFile(), root);
        } catch (IOException e) {
            throw new IllegalStateException("failed to save schema", e);
        }
    }

    private void saveRecords(CollectionSchema collection) {
        try {
            Files.createDirectories(recordsDir);
            mapper.writerWithDefaultPrettyPrinter().writeValue(recordsFile(collection).toFile(), records(collection));
        } catch (IOException e) {
            throw new IllegalStateException("failed to save records", e);
        }
    }

    private Path recordsFile(CollectionSchema collection) {
        return recordsDir.resolve(collection.id + ".json");
    }

    private CollectionSchema copyCollection(CollectionSchema collection) {
        return mapper.convertValue(collection, CollectionSchema.class);
    }

    private static String readOrCreateSecret(Path secretFile) throws IOException {
        if (Files.exists(secretFile)) {
            return Files.readString(secretFile, StandardCharsets.UTF_8).trim();
        }
        String secret = IdGenerator.prefixed("secret_") + IdGenerator.prefixed("_");
        Files.writeString(secretFile, secret, StandardCharsets.UTF_8);
        return secret;
    }

    private static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    private static String normalizeType(String type) {
        return type == null ? "text" : type.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlankText(JsonNode value) {
        return value.isTextual() && value.asText().isBlank();
    }

    private static String requiredText(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new ApiException(400, field + " is required.", fieldError(field, field + " is required."));
        }
        return value.asText();
    }

    private static String nullableText(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }

    private static int parsePositive(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Map<String, Object> fieldError(String field, String message) {
        return Map.of(field, validationError(message));
    }

    private static Map<String, Object> validationError(String message) {
        return Map.of("code", "validation_failed", "message", message);
    }

    private record FileChanges(
            Map<String, Object> values,
            Map<String, UploadedFile> writes,
            Map<String, List<String>> removals
    ) {
        private boolean isEmpty() {
            return values.isEmpty() && writes.isEmpty() && removals.isEmpty();
        }
    }

    private enum Unchanged {
        INSTANCE
    }
}
