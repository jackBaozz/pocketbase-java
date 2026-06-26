package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;
import org.jooq.Condition;
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

    public static final String SUPERUSERS = "_superusers";
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,62}$");
    private static final Pattern SQL_IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private final JooqDatabase database;
    public final ObjectMapper mapper;
    public final TokenService tokenService;
    private final Path dataDir;
    private final SystemRepository systemRepository;
    private final CollectionRepository collectionRepository;
    private final RecordRepository recordRepository;
    private final AuthRepository authRepository;

    private RelationalStorageEngine(Path dataDir, ObjectMapper mapper, TokenService tokenService, JooqDatabase.Engine engine) {
        this.dataDir = dataDir;
        this.mapper = mapper;
        this.tokenService = tokenService;
        this.systemRepository = new SystemRepository(this, mapper);
        this.collectionRepository = new CollectionRepository(this, mapper);
        this.recordRepository = new RecordRepository(this);
        this.authRepository = new AuthRepository(this);

        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new RuntimeException("failed to create data dir", e);
        }

        this.database = JooqDatabase.open(engine, dataDir);

        bootstrapSystemTables();
    }

    public static RelationalStorageEngine open(Path dataDir, String bootstrapEmail, String bootstrapPassword) {
        ObjectMapper mapper = RuntimeJson.create();
        try {
            Files.createDirectories(dataDir);
            String secret = readOrCreateSecret(dataDir.resolve("pb_secret"));
            RelationalStorageEngine engine = new RelationalStorageEngine(dataDir, mapper, new TokenService(mapper, secret), JooqDatabase.Engine.SQLITE);
            if (bootstrapEmail != null && !bootstrapEmail.isBlank()
                    && bootstrapPassword != null && !bootstrapPassword.isBlank()) {
                engine.bootstrapSuperuser(mapper.createObjectNode()
                        .put("email", bootstrapEmail)
                        .put("password", bootstrapPassword));
            }
            return engine;
        } catch (IOException e) {
            throw new RuntimeException("failed to open sqlite engine", e);
        }
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
            throw new RuntimeException("failed to open sqlite engine", e);
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

    public Connection connection() throws SQLException {
        return database.connection();
    }

    public void closeIfStandalone(Connection conn) throws SQLException {
        database.closeIfStandalone(conn);
    }

    private void validateIdentifier(String identifier, String fieldName) {
        if (identifier == null || !IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new ApiException(400, "Invalid identifier.", Map.of(
                    fieldName,
                    Map.of("code", "validation_failed", "message", "Use letters, numbers and underscore.")
            ));
        }
    }

    private void validateSchemaIdentifiers(CollectionSchema schema) {
        validateIdentifier(schema.name, "name");
        if (schema.fields != null) {
            for (FieldSchema field : schema.fields) {
                validateIdentifier(field.name, field.name == null || field.name.isBlank() ? "schema" : field.name);
            }
        }
    }

    public String qi(String identifier) {
        validateSqlIdentifier(identifier);
        return database.quoteIdentifier(identifier);
    }

    public Table<?> qt(String identifier) {
        validateSqlIdentifier(identifier);
        return DSL.table(DSL.name(identifier));
    }

    public Field<Object> qf(String identifier) {
        validateSqlIdentifier(identifier);
        return DSL.field(DSL.name(identifier));
    }

    public Field<String> qfs(String identifier) {
        validateSqlIdentifier(identifier);
        return DSL.field(DSL.name(identifier), String.class);
    }

    public Field<Integer> qfi(String identifier) {
        validateSqlIdentifier(identifier);
        return DSL.field(DSL.name(identifier), Integer.class);
    }

    private void validateSqlIdentifier(String identifier) {
        if (identifier == null || !SQL_IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new ApiException(400, "Invalid identifier.");
        }
    }

    private void bootstrapSystemTables() {
        try {
            DSLContext dsl = database.dsl();
            createCollectionsTable(dsl);
            createParamsTable(dsl);
            createSuperusersTable(dsl);
            createLogsTable(dsl);
            createMfasTable(dsl);
            createExternalAuthsTable(dsl);
            createOtpsTable(dsl);
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
                .column(DSL.name("created"), SQLDataType.VARCHAR(32))
                .column(DSL.name("updated"), SQLDataType.VARCHAR(32))
                .constraints(
                        DSL.constraint(DSL.name("pk__collections")).primaryKey(DSL.name("id")),
                        DSL.constraint(DSL.name("uk__collections_name")).unique(DSL.name("name"))
                )
                .execute();
    }

    private void createParamsTable(DSLContext dsl) {
        dsl.createTableIfNotExists(DSL.name("_params"))
                .column(DSL.name("id"), SQLDataType.VARCHAR(255).nullable(false))
                .column(DSL.name("key"), SQLDataType.VARCHAR(255).nullable(false))
                .column(DSL.name("value"), SQLDataType.CLOB)
                .column(DSL.name("created"), SQLDataType.VARCHAR(64))
                .column(DSL.name("updated"), SQLDataType.VARCHAR(64))
                .constraints(
                        DSL.constraint(DSL.name("pk__params")).primaryKey(DSL.name("id")),
                        DSL.constraint(DSL.name("uk__params_key")).unique(DSL.name("key"))
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

    private void requireCollectionExists(String collection) {
        Connection conn = null;
        try {
            conn = connection();
            try (PreparedStatement select = conn.prepareStatement("SELECT count(*) FROM _collections WHERE name = ? OR id = ?")) {
            select.setString(1, collection);
            select.setString(2, collection);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next() || rs.getInt(1) == 0) {
                    throw new ApiException(404, "Collection not found.");
                }
            }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            if (conn != null) {
                try {
                    closeIfStandalone(conn);
                } catch (SQLException ignored) {
                }
            }
        }
    }

    public void handleSqlConstraintException(Throwable e) {
        if (e == null) {
            return;
        }
        String msg = null;
        Throwable current = e;
        while (current != null) {
            if (current instanceof SQLException || current instanceof DataAccessException) {
                msg = current.getMessage();
                if (msg != null && msg.contains("UNIQUE constraint failed")) {
                    break;
                }
            }
            current = current.getCause();
        }
        if (msg != null && msg.contains("UNIQUE constraint failed")) {
            String field = "unknown";
            String[] parts = msg.split(":");
            if (parts.length > 1) {
                String[] fp = parts[1].trim().split("\\.");
                if (fp.length > 1) {
                    field = fp[1];
                } else if (fp.length == 1) {
                    field = fp[0];
                }
            }
            throw new ApiException(400, "Value must be unique.", Map.of(field, Map.of("code", "validation_failed", "message", "Value must be unique.")));
        }
    }

    public Object toStoredValue(Object value) {
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value instanceof Number) {
            return value;
        }
        return value == null ? null : value.toString();
    }

    public record BoundSql(String sql, List<Object> bindings) {
    }

    public BoundSql bindFilterContext(FilterToSqlCompiler.CompiledFilter compiled,
                                       Object requestAuth,
                                       Object requestQuery,
                                       Object requestMethod,
                                       Object requestBody) {
        Map<String, Object> placeholders = new LinkedHashMap<>();
        placeholders.put(":request_auth", requestAuth);
        placeholders.put(":request_query", requestQuery);
        placeholders.put(":request_method", requestMethod);
        placeholders.put(":request_body", requestBody);
        String sql = compiled.sql();
        StringBuilder rendered = new StringBuilder();
        List<Object> bindings = new ArrayList<>();
        int literalIndex = 0;
        int i = 0;
        while (i < sql.length()) {
            String matchedPlaceholder = null;
            for (String placeholder : placeholders.keySet()) {
                if (sql.startsWith(placeholder, i)) {
                    matchedPlaceholder = placeholder;
                    break;
                }
            }
            if (matchedPlaceholder != null) {
                rendered.append('?');
                bindings.add(placeholders.get(matchedPlaceholder));
                i += matchedPlaceholder.length();
                continue;
            }
            char c = sql.charAt(i);
            rendered.append(c);
            if (c == '?') {
                if (literalIndex >= compiled.bindings().size()) {
                    throw new ApiException(400, "Invalid filter binding state.");
                }
                bindings.add(compiled.bindings().get(literalIndex++));
            }
            i++;
        }
        if (literalIndex != compiled.bindings().size()) {
            throw new ApiException(400, "Invalid filter binding state.");
        }
        return new BoundSql(rendered.toString(), bindings);
    }

    private void rebuildIndexes(Connection conn, CollectionSchema schema, String physicalName) throws SQLException {
        if ("view".equals(schema.type)) return;
        validateIdentifier(physicalName, "name");
        if (database.engine() != JooqDatabase.Engine.SQLITE) {
            rebuildIndexesWithJooq(conn, schema, physicalName);
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("PRAGMA index_list(" + quoteSqlString(physicalName) + ")")) {
                List<String> indexesToDrop = new ArrayList<>();
                while (rs.next()) {
                    String idxName = rs.getString("name");
                    if (idxName.startsWith("idx_u_")) {
                        indexesToDrop.add(idxName);
                    }
                }
                for (String idxName : indexesToDrop) {
                    stmt.execute("DROP INDEX IF EXISTS " + qi(idxName));
                }
            }

            for (FieldSchema field : schema.fields) {
                if (field.unique) {
                    String indexName = "idx_u_" + physicalName + "_" + field.name;
                    stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS " + qi(indexName) + " ON " + qi(physicalName) + "(" + qi(field.name) + ")");
                }
            }

            if (schema.indexes != null) {
                for (String idxSql : schema.indexes) {
                    try {
                        stmt.execute(idxSql);
                    } catch (SQLException ignored) {
                    }
                }
            }
        }
    }

    private void rebuildIndexesWithJooq(Connection conn, CollectionSchema schema, String physicalName) throws SQLException {
        List<String> indexesToDrop = new ArrayList<>();
        try (ResultSet rs = conn.getMetaData().getIndexInfo(null, null, physicalName, false, false)) {
            while (rs.next()) {
                String idxName = rs.getString("INDEX_NAME");
                if (idxName != null && idxName.startsWith("idx_u_")) {
                    indexesToDrop.add(idxName);
                }
            }
        }
        for (String idxName : indexesToDrop) {
            database.dsl(conn)
                    .dropIndexIfExists(DSL.name(idxName))
                    .on(qt(physicalName))
                    .execute();
        }

        for (FieldSchema field : schema.fields) {
            if (field.unique) {
                String indexName = "idx_u_" + physicalName + "_" + field.name;
                database.dsl(conn)
                        .createUniqueIndexIfNotExists(DSL.name(indexName))
                        .on(qt(physicalName), qf(field.name))
                        .execute();
            }
        }

        if (schema.indexes != null) {
            for (String idxSql : schema.indexes) {
                try {
                    database.dsl(conn).execute(idxSql);
                } catch (DataAccessException ignored) {
                }
            }
        }
    }

    private String quoteSqlString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    @Override
    public CollectionSchema getCollection(String nameOrId) {
        Connection conn = null;
        try {
            conn = connection();
            try (PreparedStatement select = conn.prepareStatement(
                    "SELECT id, name, type, schema, system, createRule, listRule, viewRule, updateRule, deleteRule, options FROM _collections WHERE name = ? OR id = ?")) {
            select.setString(1, nameOrId);
            select.setString(2, nameOrId);
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    CollectionSchema col = new CollectionSchema();
                    col.id = rs.getString("id");
                    col.name = rs.getString("name");
                    col.type = rs.getString("type");
                    String schemaJson = rs.getString("schema");
                    if (schemaJson != null && !schemaJson.isBlank()) {
                        col.fields = mapper.readValue(schemaJson, new TypeReference<List<FieldSchema>>() {});
                    }
                    col.system = rs.getInt("system") == 1;
                    col.createRule = rs.getString("createRule");
                    col.listRule = rs.getString("listRule");
                    col.viewRule = rs.getString("viewRule");
                    col.updateRule = rs.getString("updateRule");
                    col.deleteRule = rs.getString("deleteRule");
                    return col;
                }
            }
            }
        } catch (SQLException | IOException ignored) {
        } finally {
            if (conn != null) {
                try {
                    closeIfStandalone(conn);
                } catch (SQLException ignored) {
                }
            }
        }
        return null;
    }

    @Override
    public Map<String, Object> getRecord(CollectionSchema collection, String id) {
        return recordRepository.getRecord(collection, id);
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
    public ObjectMapper mapper() {
        return mapper;
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
    public Map<String, Object> getSettings(Map<String, String> query) {
        return systemRepository.getSettings(query);
    }

    @Override
    public Map<String, Object> updateSettings(JsonNode body, Map<String, String> query) {
        return systemRepository.updateSettings(body, query);
    }

    @Override
    public void testS3(JsonNode body) {
        if (body == null || !body.isObject() || !body.hasNonNull("filesystem") || "invalid".equals(body.get("filesystem").asText())) {
            throw new ApiException(400, "Failed to test the S3 filesystem.", Map.of(
                    "filesystem", Map.of("code", "validation_invalid_filesystem", "message", "filesystem is required or invalid.")
            ));
        }
    }

    @Override
    public void testEmail(JsonNode body) {
        if (body == null || !body.isObject() || !body.hasNonNull("email") || !body.hasNonNull("template")) {
            throw new ApiException(400, "Failed to send the test email.", Map.of(
                    "email", Map.of("code", "validation_required", "message", "email is required.")
            ));
        }
        String template = body.get("template").asText();
        if (!java.util.List.of("verification", "password-reset", "email-change").contains(template)) {
            throw new ApiException(400, "Failed to send the test email.", Map.of(
                    "template", Map.of("code", "validation_invalid_template", "message", "Invalid email template.")
            ));
        }
        try {
            java.nio.file.Path authRequestsFile = dataDir.resolve("auth_requests.json");
            java.util.List<Map<String, Object>> authRequests = new java.util.ArrayList<>();
            if (java.nio.file.Files.exists(authRequestsFile)) {
                authRequests = mapper.readValue(authRequestsFile.toFile(), new com.fasterxml.jackson.core.type.TypeReference<java.util.List<Map<String, Object>>>() {});
            }
            Map<String, Object> request = new java.util.LinkedHashMap<>();
            request.put("type", "testEmail");
            request.put("template", template);
            request.put("email", body.get("email").asText());
            authRequests.add(request);
            java.nio.file.Files.writeString(authRequestsFile, mapper.writeValueAsString(authRequests), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            // ignore
        }
    }

    @Override
    public Map<String, Object> generateAppleClientSecret(JsonNode body) {
        return io.github.jackbaozz.pocketbase.server.internal.AppleClientSecretGenerator.generate(mapper, body);
    }

    @Override
    public Map<String, Object> listLogs(Map<String, String> query) {
        return systemRepository.listLogs(query);
    }

    @Override
    public List<Map<String, Object>> logStats(Map<String, String> query) {
        return systemRepository.logStats(query);
    }

    @Override
    public Map<String, Object> getLog(String id, Map<String, String> query) {
        return systemRepository.getLog(id, query);
    }

    @Override
    public List<Map<String, Object>> listCrons() {
        return systemRepository.listCrons();
    }

    @Override
    public void runCron(String id) {
        systemRepository.runCron(id);
    }

    @Override
    public Map<String, Object> fileToken(RequestPrincipal principal) {
        if (principal == null || principal.id().isBlank()) {
            throw new ApiException(401, "Missing or invalid auth token.");
        }
        Map<String, Object> claims = new LinkedHashMap<>(principal.claims());
        claims.remove("iat");
        claims.remove("exp");
        claims.put("tokenType", "file");
        return Map.of("token", tokenService.create(claims, java.time.Duration.ofMinutes(2)));
    }

    @Override
    public Map<String, Object> listBackups(int page, int perPage) {
        return systemRepository.listBackups(page, perPage);
    }

    @Override
    public void deleteBackup(String key) {
        systemRepository.deleteBackup(key);
    }

    @Override
    public Map<String, Object> restoreBackup(String key) {
        return systemRepository.restoreBackup(key);
    }

    @Override
    public Map<String, Object> createBackup(JsonNode body) {
        return systemRepository.createBackup(body);
    }

    @Override
    public Map<String, Object> uploadBackup(String filename, byte[] bytes) {
        return systemRepository.uploadBackup(filename, bytes);
    }

    @Override
    public Map<String, Object> bootstrapSuperuser(JsonNode body) {
        String email = body.get("email").asText();
        String password = body.get("password").asText();

        try (Connection conn = connection();
             PreparedStatement check = conn.prepareStatement("SELECT count(*) FROM _superusers");
             ResultSet rs = check.executeQuery()) {
            if (rs.next() && rs.getInt(1) > 0) {
                throw new ApiException(403, "Superuser already exists.");
            }
        } catch (SQLException e) {
            throw new RuntimeException("superuser bootstrap check failed", e);
        }

        String id = "su_" + IdGenerator.id();
        String passHash = PasswordHasher.hash(password);
        String tokenKey = IdGenerator.id();
        String now = Instant.now().toString();

        try (Connection conn = connection();
             PreparedStatement insert = conn.prepareStatement(
                     "INSERT INTO _superusers(id, email, passwordHash, tokenKey, created, updated) VALUES(?,?,?,?,?,?)")) {
            insert.setString(1, id);
            insert.setString(2, email);
            insert.setString(3, passHash);
            insert.setString(4, tokenKey);
            insert.setString(5, now);
            insert.setString(6, now);
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new ApiException(400, "Failed to bootstrap superuser: " + e.getMessage());
        }

        Map<String, Object> record = Map.of(
                "id", id,
                "email", email,
                "created", now,
                "updated", now
        );
        return Map.of("record", record);
    }

    @Override
    public Map<String, Object> authWithPassword(String collection, JsonNode body, Map<String, String> query) {
        return authRepository.authWithPassword(collection, body, query);
    }

    @Override
    public Map<String, Object> authWithOAuth2(String collection, JsonNode body, Map<String, String> query, RequestPrincipal principal) {
        return authRepository.authWithOAuth2(collection, body, query, principal);
    }

    @Override
    public Map<String, Object> authRefresh(String collection, RequestPrincipal principal, Map<String, String> query) {
        return authRepository.authRefresh(collection, principal, query);
    }

    @Override
    public Map<String, Object> authMethods(String collection) {
        return authRepository.authMethods(collection);
    }

    @Override
    public void requestPasswordReset(String collection, JsonNode body) {
        authRepository.requestPasswordReset(collection, body);
    }

    @Override
    public void confirmPasswordReset(String collection, JsonNode body) {
        authRepository.confirmPasswordReset(collection, body);
    }

    @Override
    public void requestVerification(String collection, JsonNode body) {
        authRepository.requestVerification(collection, body);
    }

    @Override
    public void confirmVerification(String collection, JsonNode body) {
        authRepository.confirmVerification(collection, body);
    }

    @Override
    public void requestEmailChange(String collection, JsonNode body, RequestPrincipal principal) {
        authRepository.requestEmailChange(collection, body, principal);
    }

    @Override
    public void confirmEmailChange(String collection, JsonNode body) {
        authRepository.confirmEmailChange(collection, body);
    }

    @Override
    public Map<String, Object> impersonate(String collection, String id, JsonNode body, Map<String, String> query) {
        return authRepository.impersonate(collection, id, body, query);
    }



    @Override
    public Map<String, Object> listCollections(Map<String, String> query) {
        return collectionRepository.listCollections(query);
    }

    @Override
    public CollectionSchema createCollection(JsonNode body) {
        return collectionRepository.createCollection(body);
    }

    @Override
    public Map<String, Object> importCollections(JsonNode body, boolean dryRun) {
        return collectionRepository.importCollections(body, dryRun);
    }

    @Override
    public Map<String, Object> collectionScaffolds() {
        return collectionRepository.collectionScaffolds();
    }

    @Override
    public Map<String, Object> dryRunView(JsonNode body) {
        return Map.of();
    }

    @Override
    public List<Map<String, Object>> oauth2ProviderMetadata() {
        return collectionRepository.oauth2ProviderMetadata();
    }

    @Override
    public Map<String, Object> getCollection(String collection, Map<String, String> query) {
        return collectionRepository.getCollection(collection, query);
    }

    @Override
    public CollectionSchema updateCollection(String collection, JsonNode body) {
        return collectionRepository.updateCollection(collection, body);
    }

    @Override
    public void deleteCollection(String collection) {
        collectionRepository.deleteCollection(collection);
    }

    @Override
    public void truncateCollection(String collection) {
        collectionRepository.truncateCollection(collection);
    }

    @Override
    public Map<String, Object> requestOtp(String collection, JsonNode body) {
        return Map.of();
    }

    @Override
    public Map<String, Object> authWithOtp(String collection, JsonNode body, Map<String, String> query) {
        return Map.of();
    }

    @Override
    public Map<String, Object> listRecords(String collection, Map<String, String> query, RequestPrincipal principal) {
        return recordRepository.listRecords(collection, query, principal);
    }

    @Override
    public Map<String, Object> getRecord(String collection, String id, Map<String, String> query, RequestPrincipal principal) {
        return recordRepository.getRecord(collection, id, query, principal);
    }

    @Override
    public Map<String, Object> createRecord(String collection, JsonNode body, Map<String, List<UploadedFile>> files, Map<String, String> query, RequestPrincipal principal) {
        return recordRepository.createRecord(collection, body, files, query, principal);
    }

    @Override
    public Map<String, Object> updateRecord(String collection, String id, JsonNode body, Map<String, List<UploadedFile>> files, Map<String, String> query, RequestPrincipal principal) {
        return recordRepository.updateRecord(collection, id, body, files, query, principal);
    }

    @Override
    public Map<String, Object> upsertRecord(String collection, String id, JsonNode body, Map<String, List<UploadedFile>> files, Map<String, String> query, RequestPrincipal principal) {
        return recordRepository.upsertRecord(collection, id, body, files, query, principal);
    }

    @Override
    public void deleteRecord(String collection, String id, RequestPrincipal principal) {
        recordRepository.deleteRecord(collection, id, principal);
    }

    @Override
    public Path filePath(String collection, String recordId, String filename, RequestPrincipal principal) {
        return recordRepository.filePath(collection, recordId, filename, principal);
    }

    @Override
    public boolean fileThumbAllowed(String collection, String recordId, String filename, String thumb) {
        return true;
    }

    @Override
    public Path backupFile(String key) {
        if (key == null || key.isBlank() || key.contains("..") || key.contains("/") || key.contains("\\")) {
            return null;
        }
        return dataDir.resolve("backups").resolve(key);
    }

    @Override
    public Optional<Map<String, Object>> verifyToken(String token) {
        return tokenService.verify(token);
    }

    @Override
    public void recordActivityLog(String method, String url, int status, long duration, RequestPrincipal principal, Map<String, String> headers, String remoteIp) {
        String id = IdGenerator.id();
        String now = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS'Z'")
                .withZone(java.time.ZoneOffset.UTC)
                .format(Instant.now());
        int level = status >= 400 ? 8 : 0;
        String message = (method == null ? "" : method) + " " + (url == null ? "" : url);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "request");
        data.put("method", method == null ? "" : method);
        data.put("url", url == null ? "" : url);
        data.put("status", status);
        data.put("execTime", (double) duration);
        data.put("remoteIP", remoteIp == null ? "" : remoteIp);
        data.put("userIP", remoteIp == null ? "" : remoteIp);
        data.put("userAgent", headers != null ? headers.getOrDefault("user-agent", "") : "");
        data.put("referer", headers != null ? headers.getOrDefault("referer", "") : "");
        data.put("auth", principal != null ? (principal.superuser() ? SUPERUSERS : principal.collectionName()) : "");
        if (principal != null) {
            data.put("authId", principal.id());
        }

        try {
            database.dsl()
                    .insertInto(qt("_logs"))
                    .set(qfs("id"), id)
                    .set(qfs("created"), now)
                    .set(qfs("updated"), now)
                    .set(qfi("level"), level)
                    .set(qfs("message"), message)
                    .set(qfs("data"), mapper.writeValueAsString(data))
                    .execute();
        } catch (DataAccessException | IOException ignored) {
            // Activity logging must never fail the request
        }
    }

    @Override
    public void realtimeHub(RealtimeHub hub) {
    }

    public JooqDatabase database() {
        return database;
    }

    public Path getDataDir() {
        return dataDir;
    }

    public CollectionRepository getCollectionRepository() {
        return collectionRepository;
    }

    public <T> T transactional(Supplier<T> action) {
        return database.transactional(action);
    }

    @Override
    public Optional<RequestPrincipal> verifyFileToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return tokenService.verify(token)
                .filter(claims -> "file".equals(claims.get("type")) || "file".equals(claims.get("tokenType")))
                .map(RequestPrincipal::fromClaims);
    }

    @Override
    public void close() {
        database.close();
    }
}
