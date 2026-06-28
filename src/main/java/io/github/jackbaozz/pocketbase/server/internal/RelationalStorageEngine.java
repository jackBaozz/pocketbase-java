package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jackbaozz.pocketbase.server.internal.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.repository.*;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;
import io.github.jackbaozz.pocketbase.server.internal.UploadedFile;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class RelationalStorageEngine implements StorageEngine, RecordProcessor.StoreContext {
    private static final String AUTO_BACKUP_JOB_ID = "__pbAutoBackup__";
    private static final String AUTO_BACKUP_PREFIX = "@auto_pb_backup_";
    private static final int SQL_MAX_QUERY_LENGTH = 5000;
    private static final DateTimeFormatter BACKUP_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private static final List<String> SQL_WRITE_PREFIXES = List.of(
            "insert",
            "update",
            "delete",
            "create",
            "drop"
    );

    private final JooqDatabase database;
    private final ObjectMapper mapper;
    private final TokenService tokenService;
    private final Path dataDir;

    private final CollectionRepository collectionRepository;
    private final RecordRepository recordRepository;
    private final AuthRepository authRepository;
    private final LogRepository logRepository;
    private final SettingsRepository settingsRepository;
    private final BackupRepository backupRepository;
    private final FileRepository fileRepository;

    private RelationalStorageEngine(Path dataDir, ObjectMapper mapper, TokenService tokenService, JooqDatabase.Engine engine) {
        this.mapper = mapper;
        this.tokenService = tokenService;
        this.dataDir = dataDir;

        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new RuntimeException("failed to create data dir", e);
        }

        this.database = JooqDatabase.open(engine, dataDir);

        this.collectionRepository = new CollectionRepository(database, mapper);
        this.recordRepository = new RecordRepository(database, mapper, collectionRepository, this, dataDir);
        this.authRepository = new AuthRepository(database, mapper, tokenService, this, recordRepository, dataDir);
        this.logRepository = new LogRepository(database, mapper);
        this.settingsRepository = new SettingsRepository(database, mapper, dataDir);
        this.backupRepository = new BackupRepository(database, mapper, dataDir);
        this.fileRepository = new FileRepository(database, mapper, dataDir, tokenService, collectionRepository, recordRepository, this);

        bootstrapSystemTables();
    }

    public static RelationalStorageEngine open(Path dataDir, String bootstrapEmail, String bootstrapPassword) {
        return open(dataDir, bootstrapEmail, bootstrapPassword, JooqDatabase.Engine.SQLITE);
    }

    public static RelationalStorageEngine open(Path dataDir, String bootstrapEmail, String bootstrapPassword, JooqDatabase.Engine databaseEngine) {
        ObjectMapper mapper = RuntimeJson.create();
        try {
            Files.createDirectories(dataDir);
            String secret = readOrCreateSecret(dataDir.resolve("pb_secret"));
            RelationalStorageEngine engine = new RelationalStorageEngine(dataDir, mapper, new TokenService(mapper, secret), databaseEngine);
            if (bootstrapEmail != null && !bootstrapEmail.isBlank()
                    && bootstrapPassword != null && !bootstrapPassword.isBlank()) {
                engine.bootstrapSuperuser(mapper.createObjectNode()
                        .put("email", bootstrapEmail)
                        .put("password", bootstrapPassword));
            }
            return engine;
        } catch (IOException e) {
            throw new RuntimeException("failed to open relational engine", e);
        }
    }

    private static String readOrCreateSecret(Path path) throws IOException {
        if (Files.exists(path)) {
            return Files.readString(path, StandardCharsets.UTF_8).trim();
        }
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Files.writeString(path, secret, StandardCharsets.UTF_8);
        return secret;
    }

    @Override
    public ObjectMapper mapper() {
        return mapper;
    }

    @Override
    public Map<String, Object> getSettings(Map<String, String> query) { return settingsRepository.getSettings(query); }

    @Override
    public Map<String, Object> updateSettings(JsonNode body, Map<String, String> query) { return settingsRepository.updateSettings(body, query); }

    @Override
    public void testS3(JsonNode body) { settingsRepository.testS3(body); }

    @Override
    public void testEmail(JsonNode body) { settingsRepository.testEmail(body); }

    @Override
    public Map<String, Object> generateAppleClientSecret(JsonNode body) { return settingsRepository.generateAppleClientSecret(body); }

    @Override
    public Map<String, Object> listLogs(Map<String, String> query) { return logRepository.listLogs(query); }

    @Override
    public List<Map<String, Object>> logStats(Map<String, String> query) { return logRepository.logStats(query); }

    @Override
    public Map<String, Object> getLog(String id, Map<String, String> query) { return logRepository.getLog(id, query); }

    @Override
    public List<Map<String, Object>> listCrons() {
        List<Map<String, Object>> jobs = new ArrayList<>();
        jobs.add(Map.of("id", "__pbLogsCleanup__", "expression", "0 */6 * * *"));
        jobs.add(Map.of("id", "__pbDBOptimize__", "expression", "0 0 * * *"));
        jobs.add(Map.of("id", "__pbMFACleanup__", "expression", "0 * * * *"));
        jobs.add(Map.of("id", "__pbOTPCleanup__", "expression", "0 * * * *"));
        String backupCron = backupCron();
        if (!backupCron.isBlank()) {
            jobs.add(Map.of("id", AUTO_BACKUP_JOB_ID, "expression", backupCron));
        }
        jobs.sort((left, right) -> {
            boolean leftSystem = String.valueOf(left.get("id")).startsWith("__pb");
            boolean rightSystem = String.valueOf(right.get("id")).startsWith("__pb");
            if (leftSystem && !rightSystem) return 1;
            if (!leftSystem && rightSystem) return -1;
            return String.valueOf(left.get("id")).compareTo(String.valueOf(right.get("id")));
        });
        return jobs;
    }

    @Override
    public void runCron(String id) {
        boolean exists = listCrons().stream().anyMatch(job -> String.valueOf(job.get("id")).equals(id));
        if (!exists) {
            throw new ApiException(404, "Missing or invalid cron job");
        }
        switch (id) {
            case "__pbMFACleanup__" -> authRepository.pruneExpiredMfas();
            case "__pbOTPCleanup__" -> authRepository.pruneExpiredOtps();
            case AUTO_BACKUP_JOB_ID -> runAutoBackupCron();
            default -> {
                // __pbLogsCleanup__ and __pbDBOptimize__ remain no-op placeholders for now.
            }
        }
    }

    @Override
    public Map<String, Object> fileToken(RequestPrincipal principal) { return fileRepository.fileToken(principal); }

    @Override
    public Map<String, Object> listBackups(int page, int perPage) { return backupRepository.listBackups(page, perPage); }

    @Override
    public void deleteBackup(String key) { backupRepository.deleteBackup(key); }

    @Override
    public Map<String, Object> restoreBackup(String key) { return backupRepository.restoreBackup(key); }

    @Override
    public Map<String, Object> createBackup(JsonNode body) { return backupRepository.createBackup(body); }

    @Override
    public Map<String, Object> uploadBackup(String filename, byte[] bytes) { return backupRepository.uploadBackup(filename, bytes); }

    @Override
    public Map<String, Object> bootstrapSuperuser(JsonNode body) { return authRepository.bootstrapSuperuser(body); }

    @Override
    public Map<String, Object> authWithPassword(String collection, JsonNode body, Map<String, String> query) { return authRepository.authWithPassword(collection, body, query); }

    @Override
    public Map<String, Object> authWithOAuth2(String collection, JsonNode body, Map<String, String> query, RequestPrincipal principal) { return authRepository.authWithOAuth2(collection, body, query, principal); }

    @Override
    public Map<String, Object> authRefresh(String collection, RequestPrincipal principal, Map<String, String> query) { return authRepository.authRefresh(collection, principal, query); }

    @Override
    public Map<String, Object> authMethods(String collection) { return authRepository.authMethods(collection); }

    @Override
    public void requestPasswordReset(String collection, JsonNode body) { authRepository.requestPasswordReset(collection, body); }

    @Override
    public void confirmPasswordReset(String collection, JsonNode body) { authRepository.confirmPasswordReset(collection, body); }

    @Override
    public void requestVerification(String collection, JsonNode body) { authRepository.requestVerification(collection, body); }

    @Override
    public void confirmVerification(String collection, JsonNode body) { authRepository.confirmVerification(collection, body); }

    @Override
    public void requestEmailChange(String collection, JsonNode body, RequestPrincipal principal) { authRepository.requestEmailChange(collection, body, principal); }

    @Override
    public void confirmEmailChange(String collection, JsonNode body) { authRepository.confirmEmailChange(collection, body); }

    @Override
    public Map<String, Object> impersonate(String collection, String id, JsonNode body, Map<String, String> query) { return authRepository.impersonate(collection, id, body, query); }

    @Override
    public Map<String, Object> listCollections(Map<String, String> query) { return collectionRepository.listCollections(query); }

    @Override
    public CollectionSchema createCollection(JsonNode body) { return collectionRepository.createCollection(body); }

    @Override
    public Map<String, Object> importCollections(JsonNode body, boolean dryRun) { return collectionRepository.importCollections(body, dryRun); }

    @Override
    public Map<String, Object> collectionScaffolds() { return collectionRepository.collectionScaffolds(); }

    @Override
    public Map<String, Object> dryRunView(JsonNode body) { return collectionRepository.dryRunView(body); }

    @Override
    public List<Map<String, Object>> oauth2ProviderMetadata() { return collectionRepository.oauth2ProviderMetadata(); }

    @Override
    public Map<String, Object> getCollection(String collection, Map<String, String> query) { return collectionRepository.getCollection(collection, query); }

    @Override
    public CollectionSchema updateCollection(String collection, JsonNode body) { return collectionRepository.updateCollection(collection, body); }

    @Override
    public void deleteCollection(String collection) {
        CollectionSchema schema = collectionRepository.getCollectionSchema(collection);
        collectionRepository.deleteCollection(collection);
        deleteStorageDir(schema.id);
    }

    @Override
    public void truncateCollection(String collection) {
        CollectionSchema schema = collectionRepository.getCollectionSchema(collection);
        collectionRepository.truncateCollection(collection);
        deleteStorageDir(schema.id);
    }

    @Override
    public Map<String, Object> requestOtp(String collection, JsonNode body) { return authRepository.requestOtp(collection, body); }

    @Override
    public Map<String, Object> authWithOtp(String collection, JsonNode body, Map<String, String> query) { return authRepository.authWithOtp(collection, body, query); }

    @Override
    public Map<String, Object> listRecords(String collection, Map<String, String> query, RequestPrincipal principal) { return recordRepository.listRecords(collection, query, principal); }

    @Override
    public Map<String, Object> getRecord(String collection, String id, Map<String, String> query, RequestPrincipal principal) { return recordRepository.getRecord(collection, id, query, principal); }

    @Override
    public Map<String, Object> createRecord(String collection, JsonNode body, Map<String, List<UploadedFile>> files, Map<String, String> query, RequestPrincipal principal) { return recordRepository.createRecord(collection, body, files, query, principal); }

    @Override
    public Map<String, Object> updateRecord(String collection, String id, JsonNode body, Map<String, List<UploadedFile>> files, Map<String, String> query, RequestPrincipal principal) { return recordRepository.updateRecord(collection, id, body, files, query, principal); }

    @Override
    public Map<String, Object> upsertRecord(String collection, String id, JsonNode body, Map<String, List<UploadedFile>> files, Map<String, String> query, RequestPrincipal principal) { return recordRepository.upsertRecord(collection, id, body, files, query, principal); }

    @Override
    public void deleteRecord(String collection, String id, RequestPrincipal principal) { recordRepository.deleteRecord(collection, id, principal); }

    @Override
    public Path filePath(String collectionIdOrName, String recordId, String filename, RequestPrincipal principal) { return fileRepository.filePath(collectionIdOrName, recordId, filename, principal); }

    @Override
    public Path backupFile(String key) { return backupRepository.backupFile(key); }

    @Override
    public boolean fileThumbAllowed(String collection, String recordId, String filename, String thumb) { return fileRepository.fileThumbAllowed(collection, recordId, filename, thumb); }

    @Override
    public Optional<Map<String, Object>> verifyToken(String token) { return authRepository.verifyToken(token); }

    @Override
    public void recordActivityLog(String method, String url, int status, long duration, RequestPrincipal principal, Map<String, String> headers, String remoteIp) { logRepository.recordActivityLog(method, url, status, duration, principal, headers, remoteIp); }

    @Override
    public Optional<RequestPrincipal> verifyFileToken(String token) { return fileRepository.verifyFileToken(token); }

    @Override
    public CollectionSchema getCollection(String nameOrId) {
        return collectionRepository.getCollectionSchema(nameOrId);
    }

    @Override
    public Map<String, Object> getRecord(CollectionSchema collection, String id) {
        return recordRepository.getRawRecord(collection, id);
    }

    @Override
    public Map<String, Object> findRecordByEmail(CollectionSchema collection, String email) {
        return recordRepository.findRecordByEmail(collection.name, email);
    }

    @Override
    public void updateRecordField(CollectionSchema collection, String recordId, Map<String, Object> fields) {
        recordRepository.updateFields(collection.name, recordId, fields);
    }




    private void bootstrapSystemTables() {
        try {
            DSLContext dsl = database.dsl();
            createCollectionsTable(dsl);
            createSuperusersTable(dsl);
            createLogsTable(dsl);
            createMfasTable(dsl);
            createExternalAuthsTable(dsl);
            createOtpsTable(dsl);
            createAuthRequestsTable(dsl);
            createParamsTable(dsl);
            ensureParamsKeyColumn(dsl);
            ensureSuperusersCollection(dsl);

        } catch (DataAccessException e) {
            throw new RuntimeException("failed to bootstrap system tables", e);
        }
    }


    private void createCollectionsTable(DSLContext dsl) {
        dsl.createTableIfNotExists(DSL.name("_collections"))
                .column(DSL.name("id"), SQLDataType.VARCHAR(255).nullable(false))
                .column(DSL.name("name"), SQLDataType.VARCHAR(255))
                .column(DSL.name("type"), SQLDataType.VARCHAR(64))
                .column(DSL.name("schema"), SQLDataType.CLOB)
                .column(DSL.name("system"), SQLDataType.INTEGER)
                .column(DSL.name("createRule"), SQLDataType.CLOB)
                .column(DSL.name("listRule"), SQLDataType.CLOB)
                .column(DSL.name("viewRule"), SQLDataType.CLOB)
                .column(DSL.name("updateRule"), SQLDataType.CLOB)
                .column(DSL.name("deleteRule"), SQLDataType.CLOB)
                .column(DSL.name("options"), SQLDataType.CLOB)
                .constraints(
                        DSL.constraint(DSL.name("pk__collections")).primaryKey(DSL.name("id")),
                        DSL.constraint(DSL.name("uk__collections_name")).unique(DSL.name("name"))
                )
                .execute();
    }


    private void createSuperusersTable(DSLContext dsl) {
        dsl.createTableIfNotExists(DSL.name("_superusers"))
                .column(DSL.name("id"), SQLDataType.VARCHAR(255).nullable(false))
                .column(DSL.name("email"), SQLDataType.VARCHAR(320))
                .column(DSL.name("passwordHash"), SQLDataType.VARCHAR(255))
                .column(DSL.name("tokenKey"), SQLDataType.VARCHAR(255))
                .column(DSL.name("created"), SQLDataType.VARCHAR(64))
                .column(DSL.name("updated"), SQLDataType.VARCHAR(64))
                .constraints(
                        DSL.constraint(DSL.name("pk__superusers")).primaryKey(DSL.name("id")),
                        DSL.constraint(DSL.name("uk__superusers_email")).unique(DSL.name("email"))
                )
                .execute();
    }


    private void createLogsTable(DSLContext dsl) {
        dsl.createTableIfNotExists(DSL.name("_logs"))
                .column(DSL.name("id"), SQLDataType.VARCHAR(255).nullable(false))
                .column(DSL.name("created"), SQLDataType.VARCHAR(64))
                .column(DSL.name("updated"), SQLDataType.VARCHAR(64))
                .column(DSL.name("level"), SQLDataType.INTEGER)
                .column(DSL.name("message"), SQLDataType.CLOB)
                .column(DSL.name("data"), SQLDataType.CLOB)
                .constraints(DSL.constraint(DSL.name("pk__logs")).primaryKey(DSL.name("id")))
                .execute();
    }


    private void createMfasTable(DSLContext dsl) {
        dsl.createTableIfNotExists(DSL.name("_mfas"))
                .column(DSL.name("id"), SQLDataType.VARCHAR(255).nullable(false))
                .column(DSL.name("created"), SQLDataType.VARCHAR(64))
                .column(DSL.name("updated"), SQLDataType.VARCHAR(64))
                .column(DSL.name("recordId"), SQLDataType.VARCHAR(255))
                .column(DSL.name("collectionId"), SQLDataType.VARCHAR(255))
                .column(DSL.name("method"), SQLDataType.VARCHAR(64))
                .constraints(DSL.constraint(DSL.name("pk__mfas")).primaryKey(DSL.name("id")))
                .execute();
    }


    private void createExternalAuthsTable(DSLContext dsl) {
        dsl.createTableIfNotExists(DSL.name("_externalAuths"))
                .column(DSL.name("id"), SQLDataType.VARCHAR(255).nullable(false))
                .column(DSL.name("created"), SQLDataType.VARCHAR(64))
                .column(DSL.name("updated"), SQLDataType.VARCHAR(64))
                .column(DSL.name("recordId"), SQLDataType.VARCHAR(255))
                .column(DSL.name("collectionId"), SQLDataType.VARCHAR(255))
                .column(DSL.name("provider"), SQLDataType.VARCHAR(128))
                .column(DSL.name("providerId"), SQLDataType.VARCHAR(255))
                .constraints(DSL.constraint(DSL.name("pk__externalAuths")).primaryKey(DSL.name("id")))
                .execute();
    }


    private void createOtpsTable(DSLContext dsl) {
        dsl.createTableIfNotExists(DSL.name("_otps"))
                .column(DSL.name("id"), SQLDataType.VARCHAR(255).nullable(false))
                .column(DSL.name("created"), SQLDataType.VARCHAR(64))
                .column(DSL.name("updated"), SQLDataType.VARCHAR(64))
                .column(DSL.name("recordId"), SQLDataType.VARCHAR(255))
                .column(DSL.name("collectionId"), SQLDataType.VARCHAR(255))
                .column(DSL.name("passwordHash"), SQLDataType.VARCHAR(255))
                .column(DSL.name("sentTo"), SQLDataType.VARCHAR(320))
                .column(DSL.name("failedAttempts"), SQLDataType.INTEGER)
                .constraints(DSL.constraint(DSL.name("pk__otps")).primaryKey(DSL.name("id")))
                .execute();
    }


    private void createParamsTable(DSLContext dsl) {
        dsl.createTableIfNotExists(DSL.name("_params"))
                .column(DSL.name("id"), SQLDataType.VARCHAR(255).nullable(false))
                .column(DSL.name("key"), SQLDataType.VARCHAR(255))
                .column(DSL.name("created"), SQLDataType.VARCHAR(64))
                .column(DSL.name("updated"), SQLDataType.VARCHAR(64))
                .column(DSL.name("value"), SQLDataType.CLOB)
                .constraints(DSL.constraint(DSL.name("pk__params")).primaryKey(DSL.name("id")))
                .execute();
    }

    private void createAuthRequestsTable(DSLContext dsl) {
        dsl.createTableIfNotExists(DSL.name("_authRequests"))
                .column(DSL.name("id"), SQLDataType.VARCHAR(255).nullable(false))
                .column(DSL.name("type"), SQLDataType.VARCHAR(64))
                .column(DSL.name("collectionId"), SQLDataType.VARCHAR(255))
                .column(DSL.name("collectionName"), SQLDataType.VARCHAR(255))
                .column(DSL.name("recordId"), SQLDataType.VARCHAR(255))
                .column(DSL.name("email"), SQLDataType.VARCHAR(320))
                .column(DSL.name("newEmail"), SQLDataType.VARCHAR(320))
                .column(DSL.name("token"), SQLDataType.CLOB)
                .column(DSL.name("created"), SQLDataType.VARCHAR(64))
                .column(DSL.name("expires"), SQLDataType.VARCHAR(64))
                .constraints(DSL.constraint(DSL.name("pk__authRequests")).primaryKey(DSL.name("id")))
                .execute();
    }

    private void ensureParamsKeyColumn(DSLContext dsl) {
        try {
            dsl.alterTable(DSL.name("_params"))
                    .add(DSL.name("key"), SQLDataType.VARCHAR(255))
                    .execute();
        } catch (DataAccessException ignored) {
        }
        try {
            dsl.update(DSL.table(DSL.name("_params")))
                    .set(DSL.field(DSL.name("key"), String.class), DSL.field(DSL.name("id"), String.class))
                    .where(DSL.field(DSL.name("key"), String.class).isNull())
                    .execute();
        } catch (DataAccessException ignored) {
        }
    }


    private void ensureSuperusersCollection(DSLContext dsl) {
        Table<?> collections = DSL.table(DSL.name("_collections"));
        Field<String> id = DSL.field(DSL.name("id"), String.class);
        Field<String> name = DSL.field(DSL.name("name"), String.class);
        Field<String> type = DSL.field(DSL.name("type"), String.class);
        Field<Integer> system = DSL.field(DSL.name("system"), Integer.class);
        boolean exists = dsl.fetchExists(DSL.selectOne().from(collections).where(id.eq("pbc_superusers")));
        if (!exists) {
            dsl.insertInto(collections)
                    .columns(id, name, type, system)
                    .values("pbc_superusers", "_superusers", "auth", 1)
                    .execute();
        }
    }


    @Override
    public Map<String, Object> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("canBackup", true);
        data.put("dataDir", dataDir.toString());
        data.put("superuserReady", database.dsl().fetchCount(DSL.table(DSL.name("_superusers"))) > 0);
        return Map.of(
                "code", 200,
                "message", "API is healthy.",
                "data", data
        );
    }


    @Override
    public Map<String, Object> runSql(JsonNode body) {
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
        if (query.length() > SQL_MAX_QUERY_LENGTH) {
            throw new ApiException(
                    400,
                    "An error occurred while validating the submitted data.",
                    ApiErrors.invalidField("query", "query must be at most " + SQL_MAX_QUERY_LENGTH + " characters.")
            );
        }

        long started = System.nanoTime();
        SqlResult result;
        try {
            result = executeSql(query);
        } catch (RuntimeException e) {
            String message = "Failed to execute query. Raw error:\n"
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            throw new ApiException(
                    400,
                    message,
                    ApiErrors.invalidField("query", message)
            );
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("execTime", Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
        response.put("affectedRows", result.affectedRows());
        response.put("columns", result.columns());
        response.put("rows", result.rows());
        return response;
    }


    @Override
    public boolean canView(CollectionSchema collection, Map<String, Object> record, Map<String, String> query, RequestPrincipal principal) {
        if (principal != null && principal.superuser()) {
            return true;
        }
        return collection.viewRule != null && RuleEvaluator.matches(
                collection.viewRule,
                RuleEvaluator.context(record, null, query == null ? Map.of() : query, "GET", principal, this::recordsForRule)
        );
    }


    private String qi(String identifier) {
        return database.quoteIdentifier(identifier);
    }

    private Connection connection() throws SQLException {
        return database.connection();
    }

    @Override
    public List<Map<String, Object>> recordsForRule(String collectionName) {
        CollectionSchema collection = getCollection(collectionName);
        if (collection == null) {
            return List.of();
        }
        return recordRepository.listRawRecords(collection);
    }


    @Override
    public void realtimeHub(RealtimeHub hub) {
        recordRepository.setRealtimeHub(hub);
    }


    @Override
    public <T> T transactional(Supplier<T> action) {
        return database.transactional(action);
    }

    @SuppressWarnings("unchecked")
    private String backupCron() {
        Map<String, Object> settings = settingsRepository.getSettings(Map.of());
        Object backups = settings.get("backups");
        if (backups instanceof Map<?, ?> map) {
            Object cron = ((Map<String, Object>) map).get("cron");
            return cron == null ? "" : String.valueOf(cron).trim();
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private int backupCronMaxKeep() {
        Map<String, Object> settings = settingsRepository.getSettings(Map.of());
        Object backups = settings.get("backups");
        if (backups instanceof Map<?, ?> map) {
            Object maxKeep = ((Map<String, Object>) map).get("cronMaxKeep");
            if (maxKeep instanceof Number number) {
                return number.intValue();
            }
            try {
                return Integer.parseInt(String.valueOf(maxKeep));
            } catch (Exception ignored) {
            }
        }
        return 3;
    }

    private void deleteStorageDir(String collectionId) {
        if (collectionId == null || collectionId.isBlank()) {
            return;
        }
        Path dir = dataDir.resolve("storage").resolve(collectionId);
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new IllegalStateException("failed to delete storage path " + path, e);
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("failed to delete storage dir " + dir, e);
        }
    }

    private SqlResult executeSql(String query) {
        List<String> statements = splitSqlStatements(query).stream()
                .map(String::trim)
                .filter(statement -> !statement.isBlank())
                .toList();
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("empty query");
        }

        String first = statements.get(0);
        boolean writeMode = SQL_WRITE_PREFIXES.stream().anyMatch(prefix -> startsWithKeyword(first, prefix));
        if (writeMode) {
            return database.transactional(() -> {
                long affectedRows = 0;
                for (String statement : statements) {
                    affectedRows += executeSqlWrite(statement);
                }
                return new SqlResult(affectedRows, List.of(), List.of());
            });
        }

        SqlResult result = new SqlResult(0, List.of(), List.of());
        for (String statement : statements) {
            result = executeSqlSelect(statement);
        }
        return result;
    }

    private long executeSqlWrite(String statement) {
        String sql = statement.trim();
        if (startsWithKeyword(sql, "create")) {
            return executeSqlCreate(sql);
        }
        if (startsWithKeyword(sql, "drop")) {
            return executeSqlDrop(sql);
        }
        if (startsWithKeyword(sql, "insert")
                || startsWithKeyword(sql, "update")
                || startsWithKeyword(sql, "delete")) {
            try {
                // Parse the SQL using jOOQ AST parser to enforce safety checks.
                // jOOQ parser validates the statement structure against known SQL syntax.
                var queries = database.dsl().parser().parse(sql);
                return database.dsl().execute(queries.queries()[0]);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid SQL statement: " + e.getMessage(), e);
            }
        }
        throw new IllegalArgumentException("Unsupported SQL statement.");
    }

    private SqlResult executeSqlSelect(String statement) {
        String sql = statement.trim();
        if (!startsWithKeyword(sql, "select")) {
            throw new IllegalArgumentException("Unsupported SQL statement.");
        }
        org.jooq.Result<?> result;
        try {
            // Parse using jOOQ AST to reject malformed/dangerous SELECT constructs
            var query = database.dsl().parser().parseSelect(sql);
            result = database.dsl().fetch(query.toString());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid SQL SELECT statement: " + e.getMessage(), e);
        }
        List<Map<String, Object>> columns = new ArrayList<>();
        for (var field : result.fields()) {
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("name", field.getName());

            // Format column types to match PocketBase dialects
            String typeName = field.getDataType().getTypeName();
            if (typeName != null) {
                typeName = typeName.toUpperCase(java.util.Locale.ROOT);
                if (typeName.contains("VARCHAR") || typeName.contains("TEXT") || typeName.contains("CHAR")) {
                    column.put("type", "TEXT");
                } else if (typeName.contains("INT") || typeName.contains("LONG") || typeName.contains("DECIMAL") || typeName.contains("NUMERIC")) {
                    column.put("type", "NUMERIC");
                } else if (typeName.contains("BOOL") || typeName.contains("BIT")) {
                    column.put("type", "BOOL");
                } else if (typeName.contains("DATE") || typeName.contains("TIME")) {
                    column.put("type", "DATETIME");
                } else if (typeName.contains("JSON")) {
                    column.put("type", "JSON");
                } else if (typeName.contains("BLOB") || typeName.contains("BINARY")) {
                    column.put("type", "BLOB");
                } else {
                    column.put("type", ""); // fallback
                }
            } else {
                column.put("type", "");
            }

            column.put("nullable", field.getDataType().nullable());
            columns.add(column);
        }
        List<List<Object>> rows = new ArrayList<>();
        for (var record : result) {
            List<Object> row = new ArrayList<>();
            for (int i = 0; i < result.fields().length; i++) {
                row.add(record.get(i));
            }
            rows.add(row);
        }
        return new SqlResult(0, columns, rows);
    }

    private long executeSqlCreate(String sql) {
        String remainder = sql.substring("create".length()).trim();
        if (!startsWithKeyword(remainder, "table")) {
            throw new IllegalArgumentException("Only CREATE TABLE is supported.");
        }
        remainder = remainder.substring("table".length()).trim();
        boolean ifNotExists = startsWithKeyword(remainder, "if not exists");
        if (ifNotExists) {
            remainder = remainder.substring("if not exists".length()).trim();
        }
        int columnsStart = remainder.indexOf('(');
        if (columnsStart < 0) {
            throw new IllegalArgumentException("CREATE TABLE columns are required.");
        }
        String tableName = unquoteIdentifier(remainder.substring(0, columnsStart).trim());
        if (tableName.isBlank()) {
            throw new IllegalArgumentException("CREATE TABLE name is required.");
        }
        try {
            collectionRepository.requireCollectionExists(tableName);
            if (ifNotExists) {
                return 0;
            }
            throw new IllegalArgumentException("table already exists: " + tableName);
        } catch (ApiException notFound) {
            if (notFound.status() != 404) {
                throw notFound;
            }
        }

        int columnsEnd = findMatchingParen(remainder, columnsStart);
        ObjectNode payload = mapper.createObjectNode();
        payload.put("name", tableName);
        payload.put("type", "base");
        payload.put("listRule", "");
        payload.put("viewRule", "");
        payload.put("createRule", "");
        payload.put("updateRule", "");
        payload.put("deleteRule", "");
        ArrayNode fields = payload.putArray("fields");
        for (String rawColumn : splitComma(remainder.substring(columnsStart + 1, columnsEnd))) {
            SqlColumnDefinition definition = sqlColumnDefinition(rawColumn);
            if (definition == null || isSystemSqlColumn(definition.name())) {
                continue;
            }
            ObjectNode field = fields.addObject();
            field.put("name", definition.name());
            field.put("type", definition.type());
            if (definition.required()) {
                field.put("required", true);
            }
            if (definition.unique()) {
                field.put("unique", true);
            }
        }
        collectionRepository.createCollection(payload);
        return 0;
    }

    private long executeSqlDrop(String sql) {
        String remainder = sql.substring("drop".length()).trim();
        if (!startsWithKeyword(remainder, "table")) {
            throw new IllegalArgumentException("Only DROP TABLE is supported.");
        }
        remainder = remainder.substring("table".length()).trim();
        boolean ifExists = startsWithKeyword(remainder, "if exists");
        if (ifExists) {
            remainder = remainder.substring("if exists".length()).trim();
        }
        String tableName = unquoteIdentifier(remainder.trim());
        try {
            collectionRepository.requireCollectionExists(tableName);
        } catch (ApiException notFound) {
            if (ifExists && notFound.status() == 404) {
                return 0;
            }
            throw new IllegalArgumentException("no such table: " + tableName);
        }
        deleteCollection(tableName);
        return 0;
    }

    private List<String> splitSqlStatements(String sql) {
        return splitOn(sql, ';');
    }

    private List<String> splitComma(String text) {
        return splitOn(text, ',');
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

    private int findMatchingParen(String text, int openIndex) {
        int depth = 0;
        char quote = 0;
        for (int i = openIndex; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                continue;
            }
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("Unclosed parenthesis.");
    }

    private SqlColumnDefinition sqlColumnDefinition(String rawColumn) {
        String trimmed = rawColumn == null ? "" : rawColumn.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (upper.startsWith("PRIMARY KEY") || upper.startsWith("FOREIGN KEY")
                || upper.startsWith("UNIQUE") || upper.startsWith("CONSTRAINT")) {
            return null;
        }
        String[] tokens = trimmed.split("\\s+");
        String name = unquoteIdentifier(tokens[0]);
        String typeText = tokens.length > 1 ? tokens[1] : "text";
        return new SqlColumnDefinition(
                name,
                sqlFieldType(typeText),
                upper.contains("NOT NULL"),
                upper.contains("UNIQUE") || upper.contains("PRIMARY KEY")
        );
    }

    private String sqlFieldType(String sqlType) {
        String upper = sqlType == null ? "" : sqlType.toUpperCase(Locale.ROOT);
        if (upper.contains("BOOL")) {
            return "bool";
        }
        if (upper.contains("INT") || upper.contains("REAL") || upper.contains("FLOA")
                || upper.contains("DOUB") || upper.contains("NUM") || upper.contains("DEC")) {
            return "number";
        }
        if (upper.contains("JSON")) {
            return "json";
        }
        return "text";
    }

    private boolean isSystemSqlColumn(String name) {
        return List.of("id", "collectionId", "collectionName", "created", "updated").contains(name);
    }

    private String unquoteIdentifier(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' || first == '\'' || first == '`') && last == first) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed;
    }

    private record SqlResult(long affectedRows, List<Map<String, Object>> columns, List<List<Object>> rows) {
    }

    private record SqlColumnDefinition(String name, String type, boolean required, boolean unique) {
    }

    private void runAutoBackupCron() {
        if (backupCron().isBlank()) {
            return;
        }
        String name = AUTO_BACKUP_PREFIX + BACKUP_TIMESTAMP.format(Instant.now()) + ".zip";
        backupRepository.createBackup(mapper.createObjectNode().put("name", name));
        pruneAutoBackups();
    }

    private void pruneAutoBackups() {
        int maxKeep = backupCronMaxKeep();
        if (maxKeep <= 0) {
            return;
        }
        try {
            Path backupsDir = dataDir.resolve("backups");
            Files.createDirectories(backupsDir);
            List<Path> autoBackups;
            try (var paths = Files.list(backupsDir)) {
                autoBackups = paths
                        .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().startsWith(AUTO_BACKUP_PREFIX))
                        .sorted((left, right) -> {
                            try {
                                return Files.getLastModifiedTime(right).compareTo(Files.getLastModifiedTime(left));
                            } catch (IOException e) {
                                return right.getFileName().toString().compareTo(left.getFileName().toString());
                            }
                        })
                        .toList();
            }
            for (int i = maxKeep; i < autoBackups.size(); i++) {
                Files.deleteIfExists(autoBackups.get(i));
            }
        } catch (IOException ignored) {
        }
    }

    private void closeIfStandalone(Connection conn) throws SQLException {
        database.closeIfStandalone(conn);
    }
}
