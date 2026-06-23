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

public final class SqliteStorageEngine implements StorageEngine, RecordProcessor.StoreContext {

    private static final String SUPERUSERS = "_superusers";
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,62}$");
    private static final Pattern SQL_IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private final JooqDatabase database;
    private final ObjectMapper mapper;
    private final TokenService tokenService;

    private SqliteStorageEngine(Path dataDir, ObjectMapper mapper, TokenService tokenService, JooqDatabase.Engine engine) {
        this.mapper = mapper;
        this.tokenService = tokenService;

        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new RuntimeException("failed to create data dir", e);
        }

        this.database = JooqDatabase.open(engine, dataDir);

        bootstrapSystemTables();
    }

    public static SqliteStorageEngine open(Path dataDir, String bootstrapEmail, String bootstrapPassword) {
        ObjectMapper mapper = RuntimeJson.create();
        try {
            Files.createDirectories(dataDir);
            String secret = readOrCreateSecret(dataDir.resolve("pb_secret"));
            SqliteStorageEngine engine = new SqliteStorageEngine(dataDir, mapper, new TokenService(mapper, secret), JooqDatabase.Engine.SQLITE);
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

    public static SqliteStorageEngine open(Path dataDir, String bootstrapEmail, String bootstrapPassword, JooqDatabase.Engine databaseEngine) {
        ObjectMapper mapper = RuntimeJson.create();
        try {
            Files.createDirectories(dataDir);
            String secret = readOrCreateSecret(dataDir.resolve("pb_secret"));
            SqliteStorageEngine engine = new SqliteStorageEngine(dataDir, mapper, new TokenService(mapper, secret), databaseEngine);
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

    private Connection connection() throws SQLException {
        return database.connection();
    }

    private void closeIfStandalone(Connection conn) throws SQLException {
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

    private String qi(String identifier) {
        validateSqlIdentifier(identifier);
        return database.quoteIdentifier(identifier);
    }

    private Table<?> qt(String identifier) {
        validateSqlIdentifier(identifier);
        return DSL.table(DSL.name(identifier));
    }

    private Field<Object> qf(String identifier) {
        validateSqlIdentifier(identifier);
        return DSL.field(DSL.name(identifier));
    }

    private Field<String> qfs(String identifier) {
        validateSqlIdentifier(identifier);
        return DSL.field(DSL.name(identifier), String.class);
    }

    private Field<Integer> qfi(String identifier) {
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

    private void handleSqlConstraintException(Throwable e) {
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

    private Object toStoredValue(Object value) {
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value instanceof Number) {
            return value;
        }
        return value == null ? null : value.toString();
    }

    private record BoundSql(String sql, List<Object> bindings) {
    }

    private BoundSql bindFilterContext(FilterToSqlCompiler.CompiledFilter compiled,
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
        Connection conn = null;
        try {
            conn = connection();
            try (PreparedStatement select = conn.prepareStatement("SELECT * FROM " + qi(collection.name) + " WHERE id = ?")) {
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
                    closeIfStandalone(conn);
                } catch (SQLException ignored) {
                }
            }
        }
        return null;
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
        return Map.of("meta", Map.of("appName", "pocketbase-java"));
    }

    @Override
    public Map<String, Object> updateSettings(JsonNode body, Map<String, String> query) {
        return Map.of("meta", Map.of("appName", "pocketbase-java"));
    }

    @Override
    public void testS3(JsonNode body) {
    }

    @Override
    public void testEmail(JsonNode body) {
    }

    @Override
    public Map<String, Object> generateAppleClientSecret(JsonNode body) {
        return Map.of();
    }

    @Override
    public Map<String, Object> listLogs(Map<String, String> query) {
        Map<String, String> safeQuery = query == null ? Map.of() : query;
        int page = 1;
        int perPage = 30;
        try {
            if (safeQuery.containsKey("page")) page = Integer.parseInt(safeQuery.get("page"));
            if (safeQuery.containsKey("perPage")) perPage = Integer.parseInt(safeQuery.get("perPage"));
        } catch (NumberFormatException ignored) {}

        try (Connection conn = connection()) {
            int total = 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT count(*) FROM _logs")) {
                if (rs.next()) total = rs.getInt(1);
            }

            int offset = (page - 1) * perPage;
            List<Map<String, Object>> items = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM _logs ORDER BY created DESC LIMIT ? OFFSET ?")) {
                stmt.setInt(1, perPage);
                stmt.setInt(2, offset);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> log = new LinkedHashMap<>();
                        log.put("id", rs.getString("id"));
                        log.put("created", rs.getString("created"));
                        log.put("updated", rs.getString("updated"));
                        log.put("level", rs.getInt("level"));
                        log.put("message", rs.getString("message"));
                        String dataStr = rs.getString("data");
                        if (dataStr != null) {
                            try {
                                log.put("data", mapper.readValue(dataStr, Map.class));
                            } catch (IOException e) {
                                log.put("data", Map.of());
                            }
                        }
                        items.add(log);
                    }
                }
            }
            int totalPages = (int) Math.ceil((double) total / perPage);
            return Map.of("items", items, "page", page, "perPage", perPage, "totalItems", total, "totalPages", totalPages);
        } catch (SQLException e) {
            return Map.of("items", List.of(), "page", 1, "perPage", 30, "totalItems", 0, "totalPages", 0);
        }
    }

    @Override
    public List<Map<String, Object>> logStats(Map<String, String> query) {
        return List.of();
    }

    @Override
    public Map<String, Object> getLog(String id, Map<String, String> query) {
        throw new ApiException(404, "Log not found.");
    }

    @Override
    public List<Map<String, Object>> listCrons() {
        return List.of();
    }

    @Override
    public void runCron(String id) {
    }

    @Override
    public Map<String, Object> fileToken(RequestPrincipal principal) {
        return Map.of("token", "file-token-dummy");
    }

    @Override
    public Map<String, Object> listBackups(int page, int perPage) {
        return Map.of("items", List.of(), "page", 1, "perPage", 100, "totalItems", 0, "totalPages", 0);
    }

    @Override
    public void deleteBackup(String key) {
    }

    @Override
    public Map<String, Object> restoreBackup(String key) {
        return Map.of();
    }

    @Override
    public Map<String, Object> createBackup(JsonNode body) {
        return Map.of();
    }

    @Override
    public Map<String, Object> uploadBackup(String filename, byte[] bytes) {
        return Map.of();
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
        String identity = body.get("identity").asText();
        String password = body.get("password").asText();

        if (SUPERUSERS.equals(collection)) {
            try (Connection conn = connection();
                 PreparedStatement select = conn.prepareStatement(
                         "SELECT id, email, passwordHash, tokenKey, created, updated FROM _superusers WHERE email = ?")) {
                select.setString(1, identity);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        String hash = rs.getString("passwordHash");
                        if (PasswordHasher.verify(password, hash)) {
                            String tokenKey = rs.getString("tokenKey");
                            String id = rs.getString("id");
                            String email = rs.getString("email");
                            String created = rs.getString("created");
                            String updated = rs.getString("updated");

                            Map<String, Object> claims = Map.of(
                                    "sub", id,
                                    "email", email,
                                    "type", "superuser",
                                    "collectionId", "pbc_superusers",
                                    "collectionName", SUPERUSERS,
                                    "tokenType", "auth",
                                    "tokenKey", tokenKey
                            );
                            String token = tokenService.create(claims, Duration.ofDays(7));
                            Map<String, Object> record = Map.of(
                                    "id", id,
                                    "email", email,
                                    "collectionId", "pbc_superusers",
                                    "collectionName", SUPERUSERS,
                                    "created", created,
                                    "updated", updated
                            );
                            return Map.of("token", token, "record", record);
                        }
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            throw new ApiException(400, "Invalid identity or password.");
        }

        throw new ApiException(400, "Only superusers auth is supported in SQLite MVP.");
    }

    @Override
    public Map<String, Object> authWithOAuth2(String collection, JsonNode body, Map<String, String> query, RequestPrincipal principal) {
        return AuthProcessor.authWithOAuth2(this, mapper, tokenService, collection, body);
    }

    @Override
    public Map<String, Object> authRefresh(String collection, RequestPrincipal principal, Map<String, String> query) {
        return AuthProcessor.authRefresh(this, tokenService, collection, principal);
    }

    @Override
    public Map<String, Object> authMethods(String collection) {
        return AuthProcessor.authMethods(this, collection);
    }

    @Override
    public void requestPasswordReset(String collection, JsonNode body) {
        AuthProcessor.requestPasswordReset(collection);
    }

    @Override
    public void confirmPasswordReset(String collection, JsonNode body) {
        AuthProcessor.confirmPasswordReset(collection);
    }

    @Override
    public void requestVerification(String collection, JsonNode body) {
        AuthProcessor.requestVerification(collection);
    }

    @Override
    public void confirmVerification(String collection, JsonNode body) {
        AuthProcessor.confirmVerification(collection);
    }

    @Override
    public void requestEmailChange(String collection, JsonNode body, RequestPrincipal principal) {
        AuthProcessor.requestEmailChange(collection);
    }

    @Override
    public void confirmEmailChange(String collection, JsonNode body) {
        AuthProcessor.confirmEmailChange(collection);
    }

    @Override
    public Map<String, Object> impersonate(String collection, String id, JsonNode body, Map<String, String> query) {
        return Map.of("token", "dummy-impersonate", "record", getRecord(collection, id, query, null));
    }

    @Override
    public Map<String, Object> listCollections(Map<String, String> query) {
        try (Connection conn = connection();
             PreparedStatement select = conn.prepareStatement("SELECT id, name, type, schema, system, createRule, listRule, viewRule, updateRule, deleteRule, options FROM _collections");
             ResultSet rs = select.executeQuery()) {
            List<Map<String, Object>> items = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("id", rs.getString("id"));
                col.put("name", rs.getString("name"));
                col.put("type", rs.getString("type"));
                String schemaJson = rs.getString("schema");
                if (schemaJson != null) {
                    try {
                        col.put("schema", mapper.readValue(schemaJson, List.class));
                    } catch (IOException e) {
                        col.put("schema", List.of());
                    }
                } else {
                    col.put("schema", List.of());
                }
                col.put("system", rs.getInt("system") == 1);
                col.put("createRule", rs.getString("createRule"));
                col.put("listRule", rs.getString("listRule"));
                col.put("viewRule", rs.getString("viewRule"));
                col.put("updateRule", rs.getString("updateRule"));
                col.put("deleteRule", rs.getString("deleteRule"));

                String optsJson = rs.getString("options");
                if (optsJson != null && !optsJson.isBlank()) {
                    try {
                        col.put("options", mapper.readValue(optsJson, Map.class));
                    } catch (IOException e) {
                        col.put("options", Map.of());
                    }
                } else {
                    col.put("options", Map.of());
                }
                items.add(col);
            }
            return Map.of("items", items, "page", 1, "perPage", 100, "totalItems", items.size(), "totalPages", 1);
        } catch (SQLException e) {
            throw new RuntimeException("failed to list collections", e);
        }
    }

    @Override
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
            throw new ApiException(400, "Unsupported collection type.", Map.of("type", Map.of("code", "validation_failed", "message", "Supported types are base, auth and view.")));
        }

        Connection conn = null;
        try {
            conn = connection();
            try (PreparedStatement check = conn.prepareStatement("SELECT count(*) FROM _collections WHERE name = ? OR id = ?")) {
                check.setString(1, colSchema.name);
                check.setString(2, colSchema.id);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        throw new ApiException(400, "Collection name or id already exists.");
                    }
                }
            }

            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO _collections(id, name, type, schema, system, createRule, listRule, viewRule, updateRule, deleteRule, options) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1, colSchema.id);
                insert.setString(2, colSchema.name);
                insert.setString(3, colSchema.type);
                insert.setString(4, mapper.writeValueAsString(colSchema.fields));
                insert.setInt(5, colSchema.system ? 1 : 0);
                insert.setString(6, colSchema.createRule);
                insert.setString(7, colSchema.listRule);
                insert.setString(8, colSchema.viewRule);
                insert.setString(9, colSchema.updateRule);
                insert.setString(10, colSchema.deleteRule);
                insert.setString(11, mapper.writeValueAsString(rawOptions));
                insert.executeUpdate();
            }

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
                    createTable = createTable.column(DSL.name(field.name), SQLDataType.CLOB);
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
                    closeIfStandalone(conn);
                } catch (SQLException ignored) {
                }
            }
        }

        return colSchema;
    }

    @Override
    public Map<String, Object> importCollections(JsonNode body, boolean dryRun) {
        if (body == null || !body.isObject()) {
            throw new ApiException(400, "Collections import payload must be a JSON object.");
        }
        JsonNode collectionsNode = body.get("collections");
        if (collectionsNode == null || !collectionsNode.isArray() || collectionsNode.isEmpty()) {
            throw new ApiException(400, "Failed to import collections.", Map.of("collections", Map.of("code", "validation_failed", "message", "collections is required.")));
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
        List<String> deleted = new ArrayList<>(); // To fully implement SDP2-C08, we'd calculate diff here against _collections table

        if (dryRun) {
            return Map.of("collections", newOrUpdated, "deletedCollections", deleted);
        }

        return transactional(() -> {
            for (CollectionSchema c : newOrUpdated) {
                try {
                    getCollection(c.id != null ? c.id : c.name);
                    updateCollection(c.id != null ? c.id : c.name, mapper.valueToTree(c));
                } catch (ApiException e) {
                    createCollection(mapper.valueToTree(c));
                }
            }
            return Map.of("collections", newOrUpdated, "deletedCollections", deleted);
        });
    }

    @Override
    public Map<String, Object> collectionScaffolds() {
        return Map.of();
    }

    @Override
    public Map<String, Object> dryRunView(JsonNode body) {
        return Map.of();
    }

    @Override
    public List<Map<String, Object>> oauth2ProviderMetadata() {
        return List.of();
    }

    @Override
    public Map<String, Object> getCollection(String collection, Map<String, String> query) {
        try (Connection conn = connection();
             PreparedStatement select = conn.prepareStatement(
                     "SELECT id, name, type, schema, system, createRule, listRule, viewRule, updateRule, deleteRule, options FROM _collections WHERE name = ? OR id = ?")) {
            select.setString(1, collection);
            select.setString(2, collection);
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("id", rs.getString("id"));
                    col.put("name", rs.getString("name"));
                    col.put("type", rs.getString("type"));
                    return col;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        throw new ApiException(404, "Collection not found.");
    }

    @Override
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
            conn = connection();
            String oldSchemaJson = null;
            String physicalName = null;
            try (PreparedStatement select = conn.prepareStatement("SELECT name, schema FROM _collections WHERE name = ? OR id = ?")) {
                select.setString(1, collection);
                select.setString(2, collection);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        physicalName = rs.getString("name");
                        oldSchemaJson = rs.getString("schema");
                    }
                }
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
                                .add(DSL.name(nf.name), SQLDataType.CLOB)
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
                    .where(qfs("id").eq(collection).or(qfs("name").eq(collection)))
                    .execute();

        } catch (SQLException | IOException | DataAccessException e) {
            handleSqlConstraintException(e);
            throw new ApiException(400, "Failed to update collection: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    closeIfStandalone(conn);
                } catch (SQLException ignored) {
                }
            }
        }

        return newSchema;
    }

    @Override
    public void deleteCollection(String collection) {
        Connection conn = null;
        try {
            conn = connection();
            String physicalName = null;
            String type = null;
            try (PreparedStatement select = conn.prepareStatement("SELECT name, type FROM _collections WHERE id = ? OR name = ?")) {
                select.setString(1, collection);
                select.setString(2, collection);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        physicalName = rs.getString("name");
                        type = rs.getString("type");
                    }
                }
            }

            if (physicalName == null) {
                throw new ApiException(404, "Collection not found.");
            }

            database.dsl(conn)
                    .deleteFrom(qt("_collections"))
                    .where(qfs("id").eq(collection).or(qfs("name").eq(collection)))
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
                    closeIfStandalone(conn);
                } catch (SQLException ignored) {
                }
            }
        }
    }

    @Override
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
        requireCollectionExists(collection);
        CollectionSchema colSchema = getCollectionSchema(collection);

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
                    this::qi,
                    database::renderContainsCondition
            );

            Object authJson = null;
            if (principal != null) {
                try {
                    authJson = mapper.writeValueAsString(Map.of(
                            "id", principal.id(),
                            "collectionId", principal.collectionName(),
                            "collectionName", principal.collectionName(),
                            "email", "dummy@example.com"
                    ));
                } catch (Exception ignored) {}
            }
            String queryJson = "{}";
            try { queryJson = mapper.writeValueAsString(safeQuery); } catch (Exception ignored) {}

            BoundSql boundFilter = bindFilterContext(compiledFilter, authJson, queryJson, "GET", "{}");
            whereCondition = DSL.condition(boundFilter.sql(), boundFilter.bindings().toArray());
        }

        try {
            DSLContext dsl = database.dsl();
            int offset = (page - 1) * perPage;
            int total = dsl.selectCount()
                    .from(qt(colSchema.name))
                    .where(whereCondition)
                    .fetchOne(0, int.class);
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> row : dsl.select()
                    .from(qt(colSchema.name))
                    .where(whereCondition)
                    .limit(perPage)
                    .offset(offset)
                    .fetchMaps()) {
                items.add(RecordProcessor.process(this, colSchema, row, false, safeQuery, principal));
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

    private CollectionSchema getCollectionSchema(String nameOrId) {
        Connection conn = null;
        try {
            conn = connection();
            try (PreparedStatement select = conn.prepareStatement("SELECT id, name, schema, type, viewRule FROM _collections WHERE name = ? OR id = ?")) {
            select.setString(1, nameOrId);
            select.setString(2, nameOrId);
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    CollectionSchema col = new CollectionSchema();
                    col.id = rs.getString("id");
                    col.name = rs.getString("name");
                    col.type = rs.getString("type");
                    col.viewRule = rs.getString("viewRule");
                    String schemaJson = rs.getString("schema");
                    if (schemaJson != null && !schemaJson.isBlank()) {
                        col.fields = mapper.readValue(schemaJson, new TypeReference<List<FieldSchema>>() {});
                    }
                    return col;
                }
            }
            }
        } catch (SQLException | IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (conn != null) {
                try {
                    closeIfStandalone(conn);
                } catch (SQLException ignored) {
                }
            }
        }
        throw new ApiException(404, "Collection not found.");
    }

    @Override
    public Map<String, Object> getRecord(String collection, String id, Map<String, String> query, RequestPrincipal principal) {
        requireCollectionExists(collection);
        CollectionSchema colSchema = getCollectionSchema(collection);

        Connection conn = null;
        try {
            conn = connection();
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
                    return RecordProcessor.process(this, colSchema, row, false, query, principal);
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
        throw new ApiException(404, "Record not found.");
    }

    @Override
    public Map<String, Object> createRecord(String collection, JsonNode body, Map<String, List<UploadedFile>> files, Map<String, String> query, RequestPrincipal principal) {
        CollectionSchema colSchema = getCollectionSchema(collection);
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

    @Override
    public Map<String, Object> updateRecord(String collection, String id, JsonNode body, Map<String, List<UploadedFile>> files, Map<String, String> query, RequestPrincipal principal) {
        CollectionSchema colSchema = getCollectionSchema(collection);
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

    @Override
    public Map<String, Object> upsertRecord(String collection, String id, JsonNode body, Map<String, List<UploadedFile>> files, Map<String, String> query, RequestPrincipal principal) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteRecord(String collection, String id, RequestPrincipal principal) {
        requireCollectionExists(collection);
        CollectionSchema schema = getCollectionSchema(collection);
        try {
            database.dsl()
                    .deleteFrom(qt(schema.name))
                    .where(qfs("id").eq(id))
                    .execute();
        } catch (DataAccessException e) {
            throw new ApiException(400, "Failed to delete record: " + e.getMessage());
        }
    }

    @Override
    public Path filePath(String collection, String recordId, String filename, RequestPrincipal principal) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean fileThumbAllowed(String collection, String recordId, String filename, String thumb) {
        return true;
    }

    @Override
    public Path backupFile(String key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Map<String, Object>> verifyToken(String token) {
        return tokenService.verify(token);
    }

    @Override
    public void recordActivityLog(String method, String url, int status, long duration, RequestPrincipal principal, Map<String, String> headers, String remoteIp) {
        String id = IdGenerator.id();
        String now = Instant.now().toString();
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

    @Override
    public <T> T transactional(Supplier<T> action) {
        return database.transactional(action);
    }

    @Override
    public Optional<RequestPrincipal> verifyFileToken(String token) {
        return Optional.empty();
    }

    @Override
    public void close() {
        database.close();
    }
}
