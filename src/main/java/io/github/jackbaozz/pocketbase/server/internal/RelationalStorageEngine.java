package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class RelationalStorageEngine implements StorageEngine, RecordProcessor.StoreContext {

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
        this.recordRepository = new RecordRepository(database, mapper, collectionRepository, this);
        this.authRepository = new AuthRepository(database, mapper, tokenService, this, recordRepository);
        this.logRepository = new LogRepository(database, mapper);
        this.settingsRepository = new SettingsRepository(database, mapper, dataDir);
        this.backupRepository = new BackupRepository(database, mapper, dataDir);
        this.fileRepository = new FileRepository(database, mapper, dataDir, tokenService);

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
    public List<Map<String, Object>> listCrons() { return List.of(); }

    @Override
    public void runCron(String id) {}

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
    public void deleteCollection(String collection) { collectionRepository.deleteCollection(collection); }

    @Override
    public void truncateCollection(String collection) { collectionRepository.truncateCollection(collection); }

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
        return recordRepository.getRecord(collection.name, id, Map.of(), null);
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
            createParamsTable(dsl);
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
                .constraints(DSL.constraint(DSL.name("pk__otps")).primaryKey(DSL.name("id")))
                .execute();
    }


    private void createParamsTable(DSLContext dsl) {
        dsl.createTableIfNotExists(DSL.name("_params"))
                .column(DSL.name("id"), SQLDataType.VARCHAR(255).nullable(false))
                .column(DSL.name("created"), SQLDataType.VARCHAR(64))
                .column(DSL.name("updated"), SQLDataType.VARCHAR(64))
                .column(DSL.name("value"), SQLDataType.CLOB)
                .constraints(DSL.constraint(DSL.name("pk__params")).primaryKey(DSL.name("id")))
                .execute();
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
        return Map.of("status", "healthy");
    }


    @Override
    public Map<String, Object> runSql(JsonNode body) {
        String query = body.get("query").asText();
        try {
            return Map.of("rows", database.dsl().fetch(query).intoMaps());
        } catch (DataAccessException fetchError) {
            try {
                return Map.of("rows", List.of(), "rowsAffected", database.dsl().execute(query));
            } catch (DataAccessException executeError) {
                throw new ApiException(400, "Failed to run SQL. Raw error: " + executeError.getMessage());
            }
        }
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

    private List<Map<String, Object>> recordsForRule(String collectionName) {
        CollectionSchema collection = getCollection(collectionName);
        if (collection == null) {
            return List.of();
        }
        Connection conn = null;
        try {
            conn = connection();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM " + qi(collection.name))) {
                ResultSetMetaData md = rs.getMetaData();
                int columns = md.getColumnCount();
                List<Map<String, Object>> records = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columns; i++) {
                        row.put(md.getColumnLabel(i), rs.getObject(i));
                    }
                    records.add(row);
                }
                return records;
            }
        } catch (SQLException e) {
            return List.of();
        } finally {
            if (conn != null) {
                try {
                    closeIfStandalone(conn);
                } catch (SQLException ignored) {
                }
            }
        }
    }


    @Override
    public void realtimeHub(RealtimeHub hub) {
    }


    @Override
    public <T> T transactional(Supplier<T> action) {
        return database.transactional(action);
    }


    private void closeIfStandalone(Connection conn) throws SQLException {
        database.closeIfStandalone(conn);
    }
}
