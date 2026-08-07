package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jackbaozz.pocketbase.server.internal.repository.*;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

public final class RelationalStorageEngine implements StorageEngine, RecordProcessor.StoreContext {
  private static final String AUTO_BACKUP_JOB_ID = "__pbAutoBackup__";
  private static final String AUTO_BACKUP_PREFIX = "@auto_pb_backup_";
  private static final int SQL_MAX_QUERY_LENGTH = 5000;
  private static final DateTimeFormatter BACKUP_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
  private static final List<String> SQL_WRITE_PREFIXES =
      List.of("insert", "update", "delete", "create", "drop");

  private final JooqDatabase database;
  private final ObjectMapper mapper;
  private final Path dataDir;
  private final CollectionRepository collectionRepository;
  private final RecordRepository recordRepository;
  private final AuthRepository authRepository;
  private final LogRepository logRepository;
  private final SettingsRepository settingsRepository;
  private final BackupRepository backupRepository;
  private final FileRepository fileRepository;
  private final AsyncJobRunner cronRunner = new AsyncJobRunner("pocketbase-java-cron-relational");

  private RelationalStorageEngine(
      Path dataDir, ObjectMapper mapper, TokenService tokenService, JooqDatabase.Engine engine) {
    this.mapper = mapper;
    this.dataDir = dataDir;

    try {
      Files.createDirectories(dataDir);
    } catch (IOException e) {
      throw new RuntimeException("failed to create data dir", e);
    }

    this.database = JooqDatabase.open(engine, dataDir);

    this.settingsRepository = new SettingsRepository(database, mapper, dataDir);
    this.collectionRepository = new CollectionRepository(database, mapper);
    this.recordRepository =
        new RecordRepository(database, mapper, collectionRepository, this, dataDir);
    this.logRepository = new LogRepository(database, mapper, settingsRepository);
    this.authRepository =
        new AuthRepository(
            database, mapper, tokenService, this, recordRepository, settingsRepository, dataDir);
    this.backupRepository = new BackupRepository(database, mapper, dataDir);
    this.fileRepository =
        new FileRepository(
            database, mapper, dataDir, tokenService, collectionRepository, recordRepository, this);

    bootstrapSystemTables();
  }

  public static RelationalStorageEngine open(
      Path dataDir, String bootstrapEmail, String bootstrapPassword) {
    return open(dataDir, bootstrapEmail, bootstrapPassword, JooqDatabase.Engine.SQLITE);
  }

  public static RelationalStorageEngine open(
      Path dataDir,
      String bootstrapEmail,
      String bootstrapPassword,
      JooqDatabase.Engine databaseEngine) {
    ObjectMapper mapper = RuntimeJson.create();
    try {
      Files.createDirectories(dataDir);
      String secret = readOrCreateSecret(dataDir.resolve("pb_secret"));
      RelationalStorageEngine engine =
          new RelationalStorageEngine(
              dataDir, mapper, new TokenService(mapper, secret), databaseEngine);
      if (bootstrapEmail != null
          && !bootstrapEmail.isBlank()
          && bootstrapPassword != null
          && !bootstrapPassword.isBlank()) {
        engine.bootstrapSuperuser(
            mapper
                .createObjectNode()
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
  public Map<String, Object> getSettings(Map<String, String> query) {
    return settingsRepository.getSettings(query);
  }

  @Override
  public Map<String, Object> updateSettings(JsonNode body, Map<String, String> query) {
    Map<String, Object> result = settingsRepository.updateSettings(body, query);
    logRepository.cleanupForCurrentSettings();
    return result;
  }

  @Override
  public void testS3(JsonNode body) {
    settingsRepository.testS3(body);
  }

  @Override
  public void testEmail(JsonNode body) {
    // Validate the optional collection parameter: it must refer to an auth
    // collection, matching the JsonFileStore implementation.
    if (body != null && body.isObject() && body.hasNonNull("collection")) {
      String collectionName = body.get("collection").asText().trim();
      if (!collectionName.isBlank() && !"_superusers".equals(collectionName)) {
        try {
          CollectionSchema col = collectionRepository.getCollectionSchema(collectionName);
          if (!"auth".equals(col.type)) {
            throw new ApiException(
                400,
                "Failed to send the test email.",
                ApiErrors.invalidField(
                    "collection", "Must be a valid auth collection id or name."));
          }
        } catch (ApiException e) {
          throw e;
        } catch (Exception e) {
          throw new ApiException(
              400,
              "Failed to send the test email.",
              ApiErrors.invalidField(
                  "collection", "Must be a valid auth collection id or name."));
        }
      }
    }
    settingsRepository.testEmail(body);
  }

  @Override
  public Map<String, Object> generateAppleClientSecret(JsonNode body) {
    return settingsRepository.generateAppleClientSecret(body);
  }

  @Override
  public Map<String, Object> listLogs(Map<String, String> query) {
    return logRepository.listLogs(query);
  }

  @Override
  public List<Map<String, Object>> logStats(Map<String, String> query) {
    return logRepository.logStats(query);
  }

  @Override
  public Map<String, Object> getLog(String id, Map<String, String> query) {
    return logRepository.getLog(id, query);
  }

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
    jobs.sort(
        (left, right) -> {
          boolean leftSystem = String.valueOf(left.get("id")).startsWith("__pb");
          boolean rightSystem = String.valueOf(right.get("id")).startsWith("__pb");
          if (leftSystem && !rightSystem)
            return 1;
          if (!leftSystem && rightSystem)
            return -1;
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
    cronRunner.execute(() -> runCronJob(id));
  }

  @Override
  public void close() {
    cronRunner.close();
    database.close();
  }

  private void runCronJob(String id) {
    switch (id) {
      case "__pbLogsCleanup__" -> logRepository.deleteOldLogs();
      case "__pbDBOptimize__" -> optimizeDatabase();
      case "__pbMFACleanup__" -> authRepository.pruneExpiredMfas();
      case "__pbOTPCleanup__" -> authRepository.pruneExpiredOtps();
      case AUTO_BACKUP_JOB_ID -> runAutoBackupCron();
      default -> throw new ApiException(404, "Missing or invalid cron job");
    }
  }

  private void optimizeDatabase() {
    switch (database.engine()) {
      case SQLITE -> {
        database.dsl().fetch("PRAGMA wal_checkpoint(TRUNCATE)");
        database.dsl().execute("PRAGMA optimize");
      }
      case MYSQL -> {
        for (org.jooq.Record table : database.dsl().fetch("SHOW TABLES")) {
          Object tableName = table.get(0);
          if (tableName != null && !String.valueOf(tableName).isBlank()) {
            database
                .dsl()
                .execute("ANALYZE TABLE " + database.quoteIdentifier(String.valueOf(tableName)));
          }
        }
      }
      case POSTGRES -> database.dsl().execute("ANALYZE");
    }
  }

  @Override
  public Map<String, Object> fileToken(RequestPrincipal principal) {
    return fileRepository.fileToken(principal);
  }

  @Override
  public List<Map<String, Object>> listBackups() {
    return backupRepository.listBackups();
  }

  @Override
  public void deleteBackup(String key) {
    backupRepository.deleteBackup(key);
  }

  @Override
  public Map<String, Object> restoreBackup(String key) {
    return backupRepository.restoreBackup(key);
  }

  @Override
  public Map<String, Object> createBackup(JsonNode body) {
    return backupRepository.createBackup(body);
  }

  @Override
  public Map<String, Object> uploadBackup(String filename, byte[] bytes) {
    return backupRepository.uploadBackup(filename, bytes);
  }

  @Override
  public Map<String, Object> bootstrapSuperuser(JsonNode body) {
    return authRepository.bootstrapSuperuser(body);
  }

  @Override
  public Map<String, Object> authWithPassword(
      String collection, JsonNode body, Map<String, String> query, AuthOriginContext origin) {
    return authRepository.authWithPassword(collection, body, query, origin);
  }

  @Override
  public Map<String, Object> authWithPassword(
      String collection, JsonNode body, RuleRequestContext request, AuthOriginContext origin) {
    return authRepository.authWithPassword(collection, body, request, origin);
  }

  @Override
  public Map<String, Object> authWithOAuth2(
      String collection,
      JsonNode body,
      Map<String, String> query,
      RequestPrincipal principal,
      AuthOriginContext origin) {
    return authRepository.authWithOAuth2(collection, body, query, principal, origin);
  }

  @Override
  public Map<String, Object> authWithOAuth2(
      String collection,
      JsonNode body,
      RuleRequestContext request,
      RequestPrincipal principal,
      AuthOriginContext origin) {
    return authRepository.authWithOAuth2(collection, body, request, principal, origin);
  }

  @Override
  public Map<String, Object> authRefresh(
      String collection, RequestPrincipal principal, Map<String, String> query) {
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
  public Map<String, Object> impersonate(
      String collection, String id, JsonNode body, Map<String, String> query) {
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
    return collectionRepository.dryRunView(body);
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
  public Map<String, Object> requestOtp(String collection, JsonNode body) {
    return authRepository.requestOtp(collection, body);
  }

  @Override
  public Map<String, Object> authWithOtp(
      String collection, JsonNode body, Map<String, String> query, AuthOriginContext origin) {
    return authRepository.authWithOtp(collection, body, query, origin);
  }

  @Override
  public Map<String, Object> authWithOtp(
      String collection, JsonNode body, RuleRequestContext request, AuthOriginContext origin) {
    return authRepository.authWithOtp(collection, body, request, origin);
  }

  @Override
  public Map<String, Object> listRecords(
      String collection, Map<String, String> query, RequestPrincipal principal) {
    return recordRepository.listRecords(collection, query, principal);
  }

  @Override
  public Map<String, Object> listRecords(
      String collection, RuleRequestContext request, RequestPrincipal principal) {
    return recordRepository.listRecords(collection, request, principal);
  }

  @Override
  public Map<String, Object> getRecord(
      String collection, String id, Map<String, String> query, RequestPrincipal principal) {
    return recordRepository.getRecord(collection, id, query, principal);
  }

  @Override
  public Map<String, Object> getRecord(
      String collection, String id, RuleRequestContext request, RequestPrincipal principal) {
    return recordRepository.getRecord(collection, id, request, principal);
  }

  @Override
  public Map<String, Object> createRecord(
      String collection,
      JsonNode body,
      Map<String, List<UploadedFile>> files,
      Map<String, String> query,
      RequestPrincipal principal) {
    return recordRepository.createRecord(collection, body, files, query, principal);
  }

  @Override
  public Map<String, Object> createRecord(
      String collection,
      JsonNode body,
      Map<String, List<UploadedFile>> files,
      RuleRequestContext request,
      RequestPrincipal principal) {
    return recordRepository.createRecord(collection, body, files, request, principal);
  }

  @Override
  public Map<String, Object> updateRecord(
      String collection,
      String id,
      JsonNode body,
      Map<String, List<UploadedFile>> files,
      Map<String, String> query,
      RequestPrincipal principal) {
    return recordRepository.updateRecord(collection, id, body, files, query, principal);
  }

  @Override
  public Map<String, Object> updateRecord(
      String collection,
      String id,
      JsonNode body,
      Map<String, List<UploadedFile>> files,
      RuleRequestContext request,
      RequestPrincipal principal) {
    return recordRepository.updateRecord(collection, id, body, files, request, principal);
  }

  @Override
  public Map<String, Object> upsertRecord(
      String collection,
      String id,
      JsonNode body,
      Map<String, List<UploadedFile>> files,
      Map<String, String> query,
      RequestPrincipal principal) {
    return recordRepository.upsertRecord(collection, id, body, files, query, principal);
  }

  @Override
  public Map<String, Object> upsertRecord(
      String collection,
      String id,
      JsonNode body,
      Map<String, List<UploadedFile>> files,
      RuleRequestContext request,
      RequestPrincipal principal) {
    return recordRepository.upsertRecord(collection, id, body, files, request, principal);
  }

  @Override
  public void deleteRecord(String collection, String id, RequestPrincipal principal) {
    recordRepository.deleteRecord(collection, id, principal);
  }

  @Override
  public void deleteRecord(
      String collection, String id, RuleRequestContext request, RequestPrincipal principal) {
    recordRepository.deleteRecord(collection, id, request, principal);
  }

  @Override
  public Path filePath(
      String collectionIdOrName, String recordId, String filename, RequestPrincipal principal) {
    return fileRepository.filePath(collectionIdOrName, recordId, filename, principal);
  }

  @Override
  public Path filePath(
      String collectionIdOrName,
      String recordId,
      String filename,
      RuleRequestContext request,
      RequestPrincipal principal) {
    return fileRepository.filePath(collectionIdOrName, recordId, filename, request, principal);
  }

  @Override
  public Path backupFile(String key) {
    return backupRepository.backupFile(key);
  }

  @Override
  public boolean fileThumbAllowed(
      String collection, String recordId, String filename, String thumb) {
    return fileRepository.fileThumbAllowed(collection, recordId, filename, thumb);
  }

  @Override
  public Optional<Map<String, Object>> verifyToken(String token) {
    return authRepository.verifyToken(token);
  }

  @Override
  public void recordActivityLog(
      String method,
      String url,
      int status,
      long duration,
      RequestPrincipal principal,
      Map<String, String> headers,
      String remoteIp) {
    logRepository.recordActivityLog(method, url, status, duration, principal, headers, remoteIp);
  }

  @Override
  public Optional<RequestPrincipal> verifyFileToken(String token) {
    return fileRepository.verifyFileToken(token);
  }

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
  public void updateRecordField(
      CollectionSchema collection, String recordId, Map<String, Object> fields) {
    recordRepository.updateFields(collection.name, recordId, fields);
  }

  private void bootstrapSystemTables() {
    try {
      DSLContext dsl = database.dsl();
      createCollectionsTable(dsl);
      ensureCollectionMetadataColumns(dsl);
      createSuperusersTable(dsl);
      createLogsTable(dsl);
      createMfasTable(dsl);
      createExternalAuthsTable(dsl);
      createAuthOriginsTable(dsl);
      createOtpsTable(dsl);
      createAuthRequestsTable(dsl);
      createParamsTable(dsl);
      ensureParamsKeyColumn(dsl);
      ensureAuthSystemTableColumns(dsl);
      migrateSystemCollectionIds(dsl);
      ensureSuperusersCollection(dsl);
      ensureAuthSystemCollections(dsl);
      ensureAuthSystemIndexes(dsl);
      backfillLegacyCollectionIndexes(dsl);
      ensureCollectionMetadataDefaults(dsl);
      collectionRepository.ensureAuthCollectionFields();
      collectionRepository.ensureAuthTokenSecrets();

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
        .column(DSL.name("indexes"), SQLDataType.CLOB)
        .column(DSL.name("system"), SQLDataType.INTEGER)
        .column(DSL.name("createRule"), SQLDataType.CLOB)
        .column(DSL.name("listRule"), SQLDataType.CLOB)
        .column(DSL.name("viewRule"), SQLDataType.CLOB)
        .column(DSL.name("updateRule"), SQLDataType.CLOB)
        .column(DSL.name("deleteRule"), SQLDataType.CLOB)
        .column(DSL.name("options"), SQLDataType.CLOB)
        .column(DSL.name("created"), SQLDataType.VARCHAR(64))
        .column(DSL.name("updated"), SQLDataType.VARCHAR(64))
        .constraints(
            DSL.constraint(DSL.name("pk__collections")).primaryKey(DSL.name("id")),
            DSL.constraint(DSL.name("uk__collections_name")).unique(DSL.name("name")))
        .execute();
  }

  private void ensureCollectionMetadataColumns(DSLContext dsl) {
    ensureColumn(dsl, "_collections", "indexes", SQLDataType.CLOB);
    ensureColumn(dsl, "_collections", "created", SQLDataType.VARCHAR(64));
    ensureColumn(dsl, "_collections", "updated", SQLDataType.VARCHAR(64));
  }

  private void ensureCollectionMetadataDefaults(DSLContext dsl) {
    Table<?> collections = DSL.table(DSL.name("_collections"));
    Field<String> indexes = DSL.field(DSL.name("indexes"), String.class);
    Field<String> created = DSL.field(DSL.name("created"), String.class);
    Field<String> updated = DSL.field(DSL.name("updated"), String.class);
    String now = Instant.now().toString();
    dsl.update(collections).set(indexes, "[]").where(indexes.isNull()).execute();
    dsl.update(collections).set(created, now).where(created.isNull()).execute();
    dsl.update(collections).set(updated, created).where(updated.isNull()).execute();
  }

  private void backfillLegacyCollectionIndexes(DSLContext dsl) {
    Table<?> collections = DSL.table(DSL.name("_collections"));
    Field<String> id = DSL.field(DSL.name("id"), String.class);
    Field<String> name = DSL.field(DSL.name("name"), String.class);
    Field<String> indexes = DSL.field(DSL.name("indexes"), String.class);
    var rows = dsl.select(id, name).from(collections).where(indexes.isNull()).fetch();
    for (org.jooq.Record row : rows) {
      String collectionName = row.get(name);
      List<String> definitions =
          switch (database.engine()) {
            case SQLITE -> sqliteIndexDefinitions(dsl, collectionName);
            case MYSQL -> mysqlIndexDefinitions(dsl, collectionName);
            case POSTGRES -> postgresIndexDefinitions(dsl, collectionName);
          };
      try {
        dsl.update(collections)
            .set(indexes, mapper.writeValueAsString(definitions))
            .where(id.eq(row.get(id)))
            .execute();
      } catch (IOException e) {
        throw new IllegalStateException("failed to backfill collection index metadata", e);
      }
    }
  }

  private List<String> sqliteIndexDefinitions(DSLContext dsl, String table) {
    return dsl
        .fetch(
            "SELECT sql FROM sqlite_master WHERE type = 'index' AND tbl_name = ? AND sql IS NOT NULL ORDER BY name",
            table)
        .map(record -> normalizedIndexDefinition(record.get("sql", String.class), table))
        .stream()
        .filter(definition -> !definition.isBlank())
        .toList();
  }

  private List<String> mysqlIndexDefinitions(DSLContext dsl, String table) {
    return dsl
        .fetch(
            "SELECT index_name, non_unique, "
                + "GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS columns_list "
                + "FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name <> 'PRIMARY' "
                + "GROUP BY index_name, non_unique ORDER BY index_name",
            table)
        .map(
            record -> {
              String indexName = record.get("index_name", String.class);
              Integer nonUnique = record.get("non_unique", Integer.class);
              String columns = record.get("columns_list", String.class);
              if (indexName == null || columns == null) {
                return "";
              }
              String unique = nonUnique != null && nonUnique == 0 ? "UNIQUE " : "";
              String quotedColumns =
                  java.util.Arrays.stream(columns.split(","))
                      .map(String::trim)
                      .map(column -> "`" + column.replace("`", "``") + "`")
                      .collect(java.util.stream.Collectors.joining(", "));
              return "CREATE "
                  + unique
                  + "INDEX `"
                  + indexName.replace("`", "``")
                  + "` ON `"
                  + table.replace("`", "``")
                  + "` ("
                  + quotedColumns
                  + ")";
            })
        .stream()
        .filter(definition -> !definition.isBlank())
        .toList();
  }

  private List<String> postgresIndexDefinitions(DSLContext dsl, String table) {
    return dsl
        .fetch(
            "SELECT indexdef FROM pg_indexes "
                + "WHERE schemaname = current_schema() AND tablename = ? AND indexname NOT LIKE '%_pkey' "
                + "ORDER BY indexname",
            table)
        .map(
            record -> {
              String definition = record.get("indexdef", String.class);
              if (definition == null) {
                return "";
              }
              return normalizedIndexDefinition(
                  definition.replaceFirst("(?i)\\s+USING\\s+btree\\s*", " "), table);
            })
        .stream()
        .filter(definition -> !definition.isBlank())
        .toList();
  }

  private String normalizedIndexDefinition(String raw, String table) {
    String normalized = CollectionIndexSupport.normalizeDefinition(raw, table);
    return normalized.isBlank() ? raw : normalized;
  }

  private void createSuperusersTable(DSLContext dsl) {
    dsl.createTableIfNotExists(DSL.name("_superusers"))
        .column(DSL.name("id"), SQLDataType.VARCHAR(255).nullable(false))
        .column(DSL.name("email"), SQLDataType.VARCHAR(320))
        .column(DSL.name("passwordHash"), SQLDataType.VARCHAR(255))
        .column(DSL.name("tokenKey"), SQLDataType.VARCHAR(255))
        .column(DSL.name("emailVisibility"), SQLDataType.BOOLEAN.defaultValue(false))
        .column(DSL.name("verified"), SQLDataType.BOOLEAN.defaultValue(true))
        .column(DSL.name("created"), SQLDataType.VARCHAR(64))
        .column(DSL.name("updated"), SQLDataType.VARCHAR(64))
        .constraints(
            DSL.constraint(DSL.name("pk__superusers")).primaryKey(DSL.name("id")),
            DSL.constraint(DSL.name("uk__superusers_email")).unique(DSL.name("email")))
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
        .column(DSL.name("recordRef"), SQLDataType.VARCHAR(255))
        .column(DSL.name("collectionRef"), SQLDataType.VARCHAR(255))
        .column(DSL.name("method"), SQLDataType.VARCHAR(64))
        .constraints(DSL.constraint(DSL.name("pk__mfas")).primaryKey(DSL.name("id")))
        .execute();
  }

  private void createExternalAuthsTable(DSLContext dsl) {
    dsl.createTableIfNotExists(DSL.name("_externalAuths"))
        .column(DSL.name("id"), SQLDataType.VARCHAR(255).nullable(false))
        .column(DSL.name("created"), SQLDataType.VARCHAR(64))
        .column(DSL.name("updated"), SQLDataType.VARCHAR(64))
        .column(DSL.name("recordRef"), SQLDataType.VARCHAR(255))
        .column(DSL.name("collectionRef"), SQLDataType.VARCHAR(255))
        .column(DSL.name("provider"), SQLDataType.VARCHAR(128))
        .column(DSL.name("providerId"), SQLDataType.VARCHAR(255))
        .constraints(DSL.constraint(DSL.name("pk__externalAuths")).primaryKey(DSL.name("id")))
        .execute();
  }

  private void createAuthOriginsTable(DSLContext dsl) {
    dsl.createTableIfNotExists(DSL.name("_authOrigins"))
        .column(DSL.name("id"), SQLDataType.VARCHAR(255).nullable(false))
        .column(DSL.name("created"), SQLDataType.VARCHAR(64))
        .column(DSL.name("updated"), SQLDataType.VARCHAR(64))
        .column(DSL.name("collectionRef"), SQLDataType.VARCHAR(255).nullable(false))
        .column(DSL.name("recordRef"), SQLDataType.VARCHAR(255).nullable(false))
        .column(DSL.name("fingerprint"), SQLDataType.VARCHAR(64).nullable(false))
        .constraints(
            DSL.constraint(DSL.name("pk__authOrigins")).primaryKey(DSL.name("id")),
            DSL.constraint(DSL.name("uk__authOrigins_pair"))
                .unique(DSL.name("collectionRef"), DSL.name("recordRef"), DSL.name("fingerprint")))
        .execute();
  }

  private void createOtpsTable(DSLContext dsl) {
    dsl.createTableIfNotExists(DSL.name("_otps"))
        .column(DSL.name("id"), SQLDataType.VARCHAR(255).nullable(false))
        .column(DSL.name("created"), SQLDataType.VARCHAR(64))
        .column(DSL.name("updated"), SQLDataType.VARCHAR(64))
        .column(DSL.name("recordRef"), SQLDataType.VARCHAR(255))
        .column(DSL.name("collectionRef"), SQLDataType.VARCHAR(255))
        .column(DSL.name("password"), SQLDataType.VARCHAR(255))
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
      dsl.alterTable(DSL.name("_params")).add(DSL.name("key"), SQLDataType.VARCHAR(255)).execute();
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

  private void ensureAuthSystemTableColumns(DSLContext dsl) {
    ensureColumn(dsl, "_mfas", "collectionRef", SQLDataType.VARCHAR(255));
    ensureColumn(dsl, "_mfas", "recordRef", SQLDataType.VARCHAR(255));
    copyLegacyColumn(dsl, "_mfas", "collectionRef", "collectionId");
    copyLegacyColumn(dsl, "_mfas", "recordRef", "recordId");

    ensureColumn(dsl, "_externalAuths", "collectionRef", SQLDataType.VARCHAR(255));
    ensureColumn(dsl, "_externalAuths", "recordRef", SQLDataType.VARCHAR(255));
    copyLegacyColumn(dsl, "_externalAuths", "collectionRef", "collectionId");
    copyLegacyColumn(dsl, "_externalAuths", "recordRef", "recordId");

    ensureColumn(dsl, "_otps", "collectionRef", SQLDataType.VARCHAR(255));
    ensureColumn(dsl, "_otps", "recordRef", SQLDataType.VARCHAR(255));
    ensureColumn(dsl, "_otps", "password", SQLDataType.VARCHAR(255));
    copyLegacyColumn(dsl, "_otps", "collectionRef", "collectionId");
    copyLegacyColumn(dsl, "_otps", "recordRef", "recordId");
    copyLegacyColumn(dsl, "_otps", "password", "passwordHash");
  }

  private void ensureColumn(
      DSLContext dsl, String table, String column, org.jooq.DataType<?> type) {
    try {
      dsl.alterTable(DSL.name(table)).add(DSL.name(column), type).execute();
    } catch (DataAccessException ignored) {
    }
  }

  private void copyLegacyColumn(DSLContext dsl, String table, String target, String legacy) {
    try {
      Field<String> targetField = DSL.field(DSL.name(target), String.class);
      Field<String> legacyField = DSL.field(DSL.name(legacy), String.class);
      dsl.update(DSL.table(DSL.name(table)))
          .set(targetField, legacyField)
          .where(targetField.isNull().and(legacyField.isNotNull()))
          .execute();
    } catch (DataAccessException ignored) {
    }
  }

  private void ensureSuperusersCollection(DSLContext dsl) {
    Table<?> collections = DSL.table(DSL.name("_collections"));
    Field<String> id = DSL.field(DSL.name("id"), String.class);
    Field<String> name = DSL.field(DSL.name("name"), String.class);
    Field<String> type = DSL.field(DSL.name("type"), String.class);
    Field<String> schema = DSL.field(DSL.name("schema"), String.class);
    Field<String> indexes = DSL.field(DSL.name("indexes"), String.class);
    Field<String> options = DSL.field(DSL.name("options"), String.class);
    Field<Integer> system = DSL.field(DSL.name("system"), Integer.class);
    CollectionSchema defaults = AuthSystemCollections.superusers();
    String fields;
    String indexDefinitions;
    try {
      fields = mapper.writeValueAsString(defaults.fields);
      indexDefinitions = mapper.writeValueAsString(defaults.indexes);
    } catch (IOException e) {
      throw new IllegalStateException("failed to serialize superusers collection schema", e);
    }
    boolean exists =
        dsl.fetchExists(
            DSL.selectOne().from(collections).where(name.eq(SystemCollections.SUPERUSERS)));
    if (!exists) {
      dsl.insertInto(collections)
          .columns(id, name, type, schema, indexes, system, options)
          .values(
              SystemCollections.SUPERUSERS_ID,
              SystemCollections.SUPERUSERS,
              "auth",
              fields,
              indexDefinitions,
              1,
              "{\"authToken\":{\"duration\":86400}}")
          .execute();
      return;
    }
    dsl.update(collections)
        .set(id, SystemCollections.SUPERUSERS_ID)
        .set(type, "auth")
        .set(schema, fields)
        .set(indexes, indexDefinitions)
        .set(system, 1)
        .where(name.eq(SystemCollections.SUPERUSERS))
        .execute();
  }

  private void ensureAuthSystemCollections(DSLContext dsl) {
    Table<?> collections = DSL.table(DSL.name("_collections"));
    Field<String> id = DSL.field(DSL.name("id"), String.class);
    Field<String> name = DSL.field(DSL.name("name"), String.class);
    Field<String> type = DSL.field(DSL.name("type"), String.class);
    Field<String> schema = DSL.field(DSL.name("schema"), String.class);
    Field<String> indexes = DSL.field(DSL.name("indexes"), String.class);
    Field<Integer> system = DSL.field(DSL.name("system"), Integer.class);
    Field<String> createRule = DSL.field(DSL.name("createRule"), String.class);
    Field<String> listRule = DSL.field(DSL.name("listRule"), String.class);
    Field<String> viewRule = DSL.field(DSL.name("viewRule"), String.class);
    Field<String> updateRule = DSL.field(DSL.name("updateRule"), String.class);
    Field<String> deleteRule = DSL.field(DSL.name("deleteRule"), String.class);
    Field<String> options = DSL.field(DSL.name("options"), String.class);

    for (CollectionSchema collection : AuthSystemCollections.defaults()) {
      String fields;
      String indexDefinitions;
      try {
        fields = mapper.writeValueAsString(collection.fields);
        indexDefinitions = mapper.writeValueAsString(collection.indexes);
      } catch (IOException e) {
        throw new IllegalStateException("failed to serialize system collection schema", e);
      }
      boolean exists =
          dsl.fetchExists(dsl.selectOne().from(collections).where(name.eq(collection.name)));
      if (!exists) {
        dsl.insertInto(collections)
            .columns(
                id,
                name,
                type,
                schema,
                indexes,
                system,
                createRule,
                listRule,
                viewRule,
                updateRule,
                deleteRule,
                options)
            .values(
                collection.id,
                collection.name,
                collection.type,
                fields,
                indexDefinitions,
                1,
                collection.createRule,
                collection.listRule,
                collection.viewRule,
                collection.updateRule,
                collection.deleteRule,
                "{}")
            .execute();
        continue;
      }
      dsl.update(collections)
          .set(type, collection.type)
          .set(schema, fields)
          .set(indexes, indexDefinitions)
          .set(system, 1)
          .set(createRule, collection.createRule)
          .set(listRule, collection.listRule)
          .set(viewRule, collection.viewRule)
          .set(updateRule, collection.updateRule)
          .set(deleteRule, collection.deleteRule)
          .where(name.eq(collection.name))
          .execute();
    }
  }

  private void ensureAuthSystemIndexes(DSLContext dsl) {
    List<CollectionSchema> systemCollections = new ArrayList<>();
    systemCollections.add(AuthSystemCollections.superusers());
    systemCollections.addAll(AuthSystemCollections.defaults());
    for (CollectionSchema collection : systemCollections) {
      for (String index : collection.indexes) {
        String sql =
            CollectionIndexSupport.createSql(
                index,
                collection.name,
                database::quoteIdentifier,
                database.engine(),
                collection.fields);
        if (sql.isBlank()) {
          continue;
        }
        try {
          dsl.execute(sql);
        } catch (DataAccessException ignored) {
          // Existing system indexes are expected on subsequent starts.
        }
      }
    }
  }

  private void migrateSystemCollectionIds(DSLContext dsl) {
    Table<?> collections = DSL.table(DSL.name("_collections"));
    Field<String> id = DSL.field(DSL.name("id"), String.class);
    Field<String> name = DSL.field(DSL.name("name"), String.class);
    for (SystemCollections.Definition definition : SystemCollections.definitions()) {
      org.jooq.Record row =
          dsl.select(id).from(collections).where(name.eq(definition.name())).fetchOne();
      if (row == null) {
        continue;
      }
      String previousId = row.get(id);
      if (definition.officialId().equals(previousId)) {
        continue;
      }
      boolean targetExists =
          dsl.fetchExists(dsl.selectOne().from(collections).where(id.eq(definition.officialId())));
      if (targetExists) {
        throw new IllegalStateException(
            "system collection id migration target already exists: " + definition.officialId());
      }
      rewriteRelationalCollectionReferences(dsl, previousId, definition.officialId());
      migrateRelationalCollectionSchemas(dsl, previousId, definition.officialId());
      dsl.update(collections)
          .set(id, definition.officialId())
          .where(name.eq(definition.name()))
          .execute();
      migrateStorageDirectory(previousId, definition.officialId());
    }
  }

  private void rewriteRelationalCollectionReferences(
      DSLContext dsl, String previousId, String officialId) {
    for (String table : List.of("_authOrigins", "_externalAuths", "_mfas", "_otps")) {
      Field<String> collectionRef = DSL.field(DSL.name("collectionRef"), String.class);
      dsl.update(DSL.table(DSL.name(table)))
          .set(collectionRef, officialId)
          .where(collectionRef.eq(previousId))
          .execute();
    }
    Field<String> collectionId = DSL.field(DSL.name("collectionId"), String.class);
    dsl.update(DSL.table(DSL.name("_authRequests")))
        .set(collectionId, officialId)
        .where(collectionId.eq(previousId))
        .execute();
  }

  private void migrateRelationalCollectionSchemas(
      DSLContext dsl, String previousId, String officialId) {
    Field<String> id = DSL.field(DSL.name("id"), String.class);
    Field<String> schema = DSL.field(DSL.name("schema"), String.class);
    var rows = dsl.select(id, schema).from(DSL.table(DSL.name("_collections"))).fetch();
    for (org.jooq.Record row : rows) {
      String rawSchema = row.get(schema);
      if (rawSchema == null || rawSchema.isBlank()) {
        continue;
      }
      try {
        List<FieldSchema> fields =
            mapper.readValue(rawSchema, new TypeReference<List<FieldSchema>>() {
            });
        boolean changed = false;
        for (FieldSchema field : fields) {
          if (previousId.equals(field.collectionId)) {
            field.collectionId = officialId;
            changed = true;
          }
          if (field.collectionIds != null && field.collectionIds.contains(previousId)) {
            field.collectionIds =
                field.collectionIds.stream()
                    .map(value -> previousId.equals(value) ? officialId : value)
                    .toList();
            changed = true;
          }
          if (field.options != null) {
            for (Map.Entry<String, JsonNode> entry : field.options.entrySet()) {
              JsonNode replacement = replaceJsonValue(entry.getValue(), previousId, officialId);
              if (!Objects.equals(replacement, entry.getValue())) {
                entry.setValue(replacement);
                changed = true;
              }
            }
          }
        }
        if (changed) {
          dsl.update(DSL.table(DSL.name("_collections")))
              .set(schema, mapper.writeValueAsString(fields))
              .where(id.eq(row.get(id)))
              .execute();
        }
      } catch (IOException e) {
        throw new IllegalStateException("failed to migrate collection relation metadata", e);
      }
    }
  }

  private JsonNode replaceJsonValue(JsonNode value, String previousId, String officialId) {
    if (value == null || value.isNull()) {
      return value;
    }
    if (value.isTextual()) {
      return previousId.equals(value.asText())
          ? mapper.getNodeFactory().textNode(officialId)
          : value;
    }
    if (value.isArray()) {
      ArrayNode copy = value.deepCopy();
      for (int i = 0; i < copy.size(); i++) {
        copy.set(i, replaceJsonValue(copy.get(i), previousId, officialId));
      }
      return copy;
    }
    if (value.isObject()) {
      ObjectNode copy = value.deepCopy();
      List<String> names = new ArrayList<>();
      copy.fieldNames().forEachRemaining(names::add);
      for (String name : names) {
        copy.set(name, replaceJsonValue(copy.get(name), previousId, officialId));
      }
      return copy;
    }
    return value;
  }

  private void migrateStorageDirectory(String previousId, String officialId) {
    Path source = dataDir.resolve("storage").resolve(previousId);
    if (!Files.exists(source)) {
      return;
    }
    Path target = dataDir.resolve("storage").resolve(officialId);
    try {
      if (!Files.exists(target)) {
        Files.createDirectories(target.getParent());
        Files.move(source, target);
        return;
      }
      try (java.util.stream.Stream<Path> paths = Files.walk(source)) {
        for (Path path : paths.sorted().toList()) {
          Path destination = target.resolve(source.relativize(path));
          if (Files.isDirectory(path)) {
            Files.createDirectories(destination);
          } else if (!Files.exists(destination)) {
            Files.move(path, destination);
          } else if (Files.mismatch(path, destination) != -1L) {
            throw new IllegalStateException(
                "conflicting files found while migrating system collection " + previousId);
          } else {
            Files.delete(path);
          }
        }
      }
      try (java.util.stream.Stream<Path> paths = Files.walk(source)) {
        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException(
          "failed to migrate system collection storage from " + previousId, e);
    }
  }

  @Override
  public boolean canBackup() {
    return backupRepository.canBackup();
  }

  @Override
  public boolean hasSuperusers() {
    return database.dsl().fetchCount(DSL.table(DSL.name("_superusers"))) > 0;
  }

  @Override
  public Map<String, Object> runSql(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new ApiException(
          400,
          "An error occurred while loading the submitted data.",
          ApiErrors.invalidField("body", "Request body must be a JSON object."));
    }
    JsonNode queryNode = body.get("query");
    if (queryNode == null || queryNode.isNull() || queryNode.asText().isBlank()) {
      throw new ApiException(
          400,
          "An error occurred while validating the submitted data.",
          ApiErrors.requiredField("query"));
    }
    String query = queryNode.asText();
    if (query.length() > SQL_MAX_QUERY_LENGTH) {
      throw new ApiException(
          400,
          "An error occurred while validating the submitted data.",
          ApiErrors.invalidField(
              "query", "query must be at most " + SQL_MAX_QUERY_LENGTH + " characters."));
    }

    long started = System.nanoTime();
    SqlResult result;
    try {
      result = executeSql(query);
    } catch (RuntimeException e) {
      String message =
          "Failed to execute query. Raw error:\n"
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
      throw new ApiException(400, message, ApiErrors.invalidField("query", message));
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("execTime", Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
    response.put("affectedRows", result.affectedRows());
    response.put("columns", result.columns());
    response.put("rows", result.rows());
    return response;
  }

  @Override
  public boolean canView(
      CollectionSchema collection,
      Map<String, Object> record,
      Map<String, String> query,
      RequestPrincipal principal) {
    return canView(collection, record, RuleRequestContext.of(query, Map.of()), principal);
  }

  @Override
  public boolean canView(
      CollectionSchema collection,
      Map<String, Object> record,
      RuleRequestContext request,
      RequestPrincipal principal) {
    if (principal != null && principal.superuser()) {
      return true;
    }
    return collection.viewRule != null
        && RuleEvaluator.matches(
            collection.viewRule,
            RecordFieldResolverSupport.context(
                this, collection, record, null, request, "GET", principal, true, false));
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

  private String backupCron() {
    Map<String, Object> settings = settingsRepository.getSettings(Map.of());
    Object backups = settings.get("backups");
    if (backups instanceof Map<?, ?> map) {
      Object cron = Unsafe.stringObjectMap(map).get("cron");
      return cron == null ? "" : String.valueOf(cron).trim();
    }
    return "";
  }

  private int backupCronMaxKeep() {
    Map<String, Object> settings = settingsRepository.getSettings(Map.of());
    Object backups = settings.get("backups");
    if (backups instanceof Map<?, ?> map) {
      Object maxKeep = Unsafe.stringObjectMap(map).get("cronMaxKeep");
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
      paths
          .sorted((left, right) -> right.getNameCount() - left.getNameCount())
          .forEach(
              path -> {
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
    List<String> statements =
        splitSqlStatements(query).stream()
            .map(String::trim)
            .filter(statement -> !statement.isBlank())
            .toList();
    if (statements.isEmpty()) {
      throw new IllegalArgumentException("empty query");
    }

    String first = statements.get(0);
    boolean writeMode =
        SQL_WRITE_PREFIXES.stream().anyMatch(prefix -> startsWithKeyword(first, prefix));
    if (writeMode) {
      return database.transactional(
          () -> {
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
    List<org.jooq.Field<?>> parsedSelectFields;
    try {
      // Parse using jOOQ AST to reject malformed/dangerous SELECT constructs
      var query = database.dsl().parser().parseSelect(sql);
      parsedSelectFields = query.getSelect();
      // Keep the historical text execution path so JDBC value types stay
      // stable on SQLite. PostgreSQL's `?column?` label is reconciled with
      // the parser field below when building the response columns.
      result = database.dsl().fetch(query.toString());
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid SQL SELECT statement: " + e.getMessage(), e);
    }
    List<Map<String, Object>> columns = new ArrayList<>();
    for (int index = 0; index < result.fields().length; index++) {
      var field = result.fields()[index];
      Map<String, Object> column = new LinkedHashMap<>();
      String fieldName = field.getName();
      if ("?column?".equals(fieldName) && index < parsedSelectFields.size()) {
        String parsedName = parsedSelectFields.get(index).getName();
        if (parsedName != null && !parsedName.isBlank()) {
          fieldName = parsedName;
        }
      }
      column.put("name", fieldName);

      // Format column types to match PocketBase dialects
      String typeName = field.getDataType().getTypeName();
      if (typeName != null) {
        typeName = typeName.toUpperCase(java.util.Locale.ROOT);
        if (typeName.contains("VARCHAR")
            || typeName.contains("TEXT")
            || typeName.contains("CHAR")) {
          column.put("type", "TEXT");
        } else if (typeName.contains("INT")
            || typeName.contains("LONG")
            || typeName.contains("DECIMAL")
            || typeName.contains("NUMERIC")) {
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
        row.add(normalizeSqlResultValue(record.get(i)));
      }
      rows.add(row);
    }
    return new SqlResult(0, columns, rows);
  }

  /**
   * JDBC drivers disagree on the Java representation of integral SQL values. In particular MySQL
   * can expose an INT as Double/BigDecimal with a trailing scale. Normalize exact integers before
   * Jackson serializes the SQL response so the API remains storage-engine independent.
   */
  private Object normalizeSqlResultValue(Object value) {
    if (value instanceof BigDecimal decimal) {
      try {
        BigDecimal stripped = decimal.stripTrailingZeros();
        if (stripped.scale() <= 0) {
          return stripped.longValueExact();
        }
      } catch (ArithmeticException ignored) {
        return decimal;
      }
      return decimal;
    }
    if (value instanceof BigInteger integer) {
      try {
        return integer.longValueExact();
      } catch (ArithmeticException ignored) {
        return integer;
      }
    }
    if (value instanceof Double number) {
      if (Double.isFinite(number)
          && Math.rint(number) == number
          && number >= Long.MIN_VALUE
          && number <= Long.MAX_VALUE) {
        return number.longValue();
      }
      return number;
    }
    if (value instanceof Float number) {
      if (Float.isFinite(number)
          && Math.rint(number) == number
          && number >= Long.MIN_VALUE
          && number <= Long.MAX_VALUE) {
        return number.longValue();
      }
      return number;
    }
    return value;
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
    CollectionSchema created = collectionRepository.createCollection(payload);
    // MySQL commits DDL implicitly, so the surrounding SQL API transaction
    // cannot undo a CREATE TABLE through JDBC rollback alone. Register an
    // explicit compensating delete to preserve the /api/sql batch contract.
    if (database.engine() == JooqDatabase.Engine.MYSQL) {
      database.onRollback(() -> collectionRepository.deleteCollection(created.name));
    }
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
    return trimmed.length() == keyword.length()
        || !isIdentifierChar(trimmed.charAt(keyword.length()));
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
    if (upper.startsWith("PRIMARY KEY")
        || upper.startsWith("FOREIGN KEY")
        || upper.startsWith("UNIQUE")
        || upper.startsWith("CONSTRAINT")) {
      return null;
    }
    String[] tokens = trimmed.split("\\s+");
    String name = unquoteIdentifier(tokens[0]);
    String typeText = tokens.length > 1 ? tokens[1] : "text";
    return new SqlColumnDefinition(
        name,
        sqlFieldType(typeText),
        upper.contains("NOT NULL"),
        upper.contains("UNIQUE") || upper.contains("PRIMARY KEY"));
  }

  private String sqlFieldType(String sqlType) {
    String upper = sqlType == null ? "" : sqlType.toUpperCase(Locale.ROOT);
    if (upper.contains("BOOL")) {
      return "bool";
    }
    if (upper.contains("INT")
        || upper.contains("REAL")
        || upper.contains("FLOA")
        || upper.contains("DOUB")
        || upper.contains("NUM")
        || upper.contains("DEC")) {
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

  private record SqlResult(
      long affectedRows, List<Map<String, Object>> columns, List<List<Object>> rows) {
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
        autoBackups =
            paths
                .filter(
                    path -> Files.isRegularFile(path)
                        && path.getFileName().toString().startsWith(AUTO_BACKUP_PREFIX))
                .sorted(
                    (left, right) -> {
                      try {
                        return Files.getLastModifiedTime(right)
                            .compareTo(Files.getLastModifiedTime(left));
                      } catch (IOException e) {
                        return right
                            .getFileName()
                            .toString()
                            .compareTo(left.getFileName().toString());
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
}
