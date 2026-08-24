package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
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

/** Small persistent store used by the native-friendly embedded server. */
public final class JsonFileStore implements StorageEngine, RecordProcessor.StoreContext {
  public static final String SUPERUSERS = SystemCollections.SUPERUSERS;

  private static final TypeReference<List<CollectionSchema>> COLLECTION_LIST =
      new TypeReference<>() {
      };
  private static final TypeReference<List<Map<String, Object>>> RECORD_LIST =
      new TypeReference<>() {
      };
  private static final TypeReference<Map<String, List<Map<String, Object>>>> RECORD_MAP =
      new TypeReference<>() {
      };
  private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP =
      new TypeReference<>() {
      };
  private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
  private static final Set<String> INTERNAL_TABLE_NAMES =
      Set.of("_collections", "_params", "_migrations", "_logs", "_authRequests");
  private static final int MAX_ACTIVITY_LOGS = 10_000;
  private static final int SQL_MAX_QUERY_LENGTH = 5000;
  private static final int SQL_MAX_ROWS = 1000;
  private static final int MAX_BACKUP_ENTRIES = 10_000;
  private static final long MAX_BACKUP_UNCOMPRESSED_BYTES = 512L << 20;
  private static final int OTP_MAX_FAILED_ATTEMPTS = 5;
  private static final Duration OTP_ATTEMPT_WINDOW = Duration.ofMinutes(3);
  private static final String REDACTED_SECRET = "******";
  private static final String INTERNAL_ROWID = "@rowid";
  private static final String AUTO_BACKUP_JOB_ID = "__pbAutoBackup__";
  private static final String AUTO_BACKUP_PREFIX = "@auto_pb_backup_";
  private static final List<String> SQL_WRITE_PREFIXES =
      List.of("INSERT", "CREATE", "UPDATE", "DELETE", "DROP", "DETACH", "ALTER", "REPLACE");
  private static final DateTimeFormatter LOG_STATS_HOUR_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00:00.000'Z'").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter BACKUP_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
  private static final Set<String> TEST_EMAIL_TEMPLATES =
      Set.of("verification", "password-reset", "email-change", "otp", "login-alert");

  private final Path dataDir;
  private final Path schemaFile;
  private final Path settingsFile;
  private final Path logsFile;
  private final Path authRequestsFile;
  private final Path authOriginsFile;
  private final Path externalAuthsFile;
  private final Path mfasFile;
  private final Path otpsFile;
  private final Path recordsDir;
  private final Path storageDir;
  private final Path backupsDir;
  private final Path secretFile;
  private final ObjectMapper mapper;
  private final TokenService tokenService;
  private final Map<String, CollectionSchema> collectionsByName = new LinkedHashMap<>();
  private final Map<String, List<Map<String, Object>>> recordsByCollectionId =
      new LinkedHashMap<>();
  private final Map<String, Object> settings = new LinkedHashMap<>();
  private final List<Map<String, Object>> logs = new ArrayList<>();
  private final List<Map<String, Object>> mfas = new ArrayList<>();
  private final List<Map<String, Object>> authRequests = new ArrayList<>();
  private final List<Map<String, Object>> authOrigins = new ArrayList<>();
  private final List<Map<String, Object>> externalAuths = new ArrayList<>();
  private final List<Map<String, Object>> otps = new ArrayList<>();
  private final Set<String> evaluatingViewCollections = new LinkedHashSet<>();
  private final AsyncJobRunner cronRunner = new AsyncJobRunner("pocketbase-java-cron-jsonl");
  private final BackupOperationGuard backupOperations = new BackupOperationGuard();
  private RealtimeHub realtimeHub;

  private JsonFileStore(Path dataDir, ObjectMapper mapper, TokenService tokenService) {
    this.dataDir = dataDir;
    this.schemaFile = dataDir.resolve("pb_schema.json");
    this.settingsFile = dataDir.resolve("pb_settings.json");
    this.logsFile = dataDir.resolve("logs.json");
    this.authRequestsFile = dataDir.resolve("auth_requests.json");
    this.authOriginsFile = dataDir.resolve("auth_origins.json");
    this.externalAuthsFile = dataDir.resolve("external_auths.json");
    this.mfasFile = dataDir.resolve("mfas.json");
    this.otpsFile = dataDir.resolve("otps.json");
    this.recordsDir = dataDir.resolve("records");
    this.storageDir = dataDir.resolve("storage");
    this.backupsDir = dataDir.resolve("backups");
    this.secretFile = dataDir.resolve("pb_secret");
    this.mapper = mapper;
    this.tokenService = tokenService;
  }

  public static JsonFileStore open(Path dataDir, String bootstrapEmail, String bootstrapPassword)
      throws IOException {
    ObjectMapper mapper = RuntimeJson.create();
    FilePermissionSupport.secureDirectory(dataDir);
    FilePermissionSupport.secureDirectory(dataDir.resolve("records"));
    FilePermissionSupport.secureDirectory(dataDir.resolve("storage"));
    FilePermissionSupport.secureDirectory(dataDir.resolve("backups"));
    FilePermissionSupport.secureTree(dataDir);
    String secret = readOrCreateSecret(dataDir.resolve("pb_secret"));
    JsonFileStore store = new JsonFileStore(dataDir, mapper, new TokenService(mapper, secret));
    store.load();
    if (bootstrapEmail != null
        && !bootstrapEmail.isBlank()
        && bootstrapPassword != null
        && !bootstrapPassword.isBlank()
        && !store.hasSuperusers()) {
      ObjectNode body = mapper.createObjectNode();
      body.put("email", bootstrapEmail);
      body.put("password", bootstrapPassword);
      body.put("verified", true);
      store.createSuperuser(body);
    }
    FilePermissionSupport.secureTree(dataDir);
    return store;
  }

  public ObjectMapper mapper() {
    return mapper;
  }

  @Override
  public synchronized Map<String, Object> authWithPassword(
      String collection, JsonNode body, RuleRequestContext request, AuthOriginContext origin) {
    return authWithPasswordInternal(collection, body, request, origin);
  }

  @Override
  public synchronized Map<String, Object> authWithOAuth2(
      String collection,
      JsonNode body,
      RuleRequestContext request,
      RequestPrincipal principal,
      AuthOriginContext origin) {
    return authWithOAuth2Internal(collection, body, request, principal, origin);
  }

  @Override
  public synchronized Map<String, Object> authWithOtp(
      String collection, JsonNode body, RuleRequestContext request, AuthOriginContext origin) {
    return authWithOtpInternal(collection, body, request, origin);
  }

  @Override
  public synchronized Map<String, Object> listRecords(
      String collection, RuleRequestContext request, RequestPrincipal principal) {
    return listRecordsInternal(collection, request, principal);
  }

  @Override
  public synchronized Map<String, Object> getRecord(
      String collection, String id, RuleRequestContext request, RequestPrincipal principal) {
    return getRecordInternal(collection, id, request, principal);
  }

  @Override
  public synchronized Map<String, Object> createRecord(
      String collection,
      JsonNode body,
      Map<String, List<UploadedFile>> files,
      RuleRequestContext request,
      RequestPrincipal principal) {
    return createRecordInternal(collection, body, files, request, principal);
  }

  @Override
  public synchronized Map<String, Object> updateRecord(
      String collection,
      String id,
      JsonNode body,
      Map<String, List<UploadedFile>> files,
      RuleRequestContext request,
      RequestPrincipal principal) {
    return updateRecordInternal(collection, id, body, files, request, principal);
  }

  @Override
  public synchronized Map<String, Object> upsertRecord(
      String collection,
      String id,
      JsonNode body,
      Map<String, List<UploadedFile>> files,
      RuleRequestContext request,
      RequestPrincipal principal) {
    return upsertRecordInternal(collection, id, body, files, request, principal);
  }

  @Override
  public synchronized void deleteRecord(
      String collection, String id, RuleRequestContext request, RequestPrincipal principal) {
    deleteRecordInternal(collection, id, request, principal);
  }

  public void realtimeHub(RealtimeHub realtimeHub) {
    this.realtimeHub = realtimeHub;
  }

  @Override
  public void close() {
    cronRunner.close();
  }

  public Optional<Map<String, Object>> verifyToken(String token) {
    return tokenService
        .verify(token, this::bearerTokenSigningSecret)
        .filter(this::isBearerToken)
        .filter(this::authTokenClaimsValid);
  }

  public Optional<RequestPrincipal> verifyFileToken(String token) {
    return tokenService
        .verify(token, this::fileTokenSigningSecret)
        .filter(claims -> "file".equals(claims.get("tokenType")))
        .filter(this::authTokenClaimsValid)
        .map(RequestPrincipal::fromClaims);
  }

  public boolean canBackup() {
    return backupOperations.available();
  }

  public synchronized Map<String, Object> getSettings(Map<String, String> query) {
    Map<String, String> safeQuery = query == null ? Map.of() : query;
    return selectFields(redactedSettings(), safeQuery.get("fields"));
  }

  public synchronized Map<String, Object> updateSettings(JsonNode body, Map<String, String> query) {
    if (body == null || !body.isObject()) {
      throw new ApiException(
          400,
          "Settings payload must be a JSON object.",
          ApiErrors.invalidField("body", "Request body must be a JSON object."));
    }
    Map<String, Object> snapshot = copyMap(settings);
    try {
      deepMerge(settings, mapper.convertValue(body, STRING_OBJECT_MAP));
      normalizeSettings();
      HttpRateLimiter.validateSettings(settings);
      saveSettings();
      pruneLogs();
      saveLogs();
      return getSettings(query);
    } catch (RuntimeException e) {
      settings.clear();
      settings.putAll(snapshot);
      throw e;
    }
  }

  public synchronized void testS3(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new ApiException(
          400, "Failed to test the S3 filesystem.", ApiErrors.requiredField("filesystem"));
    }
    String filesystem = bodyText(body, "filesystem", "");
    if (filesystem.isBlank()) {
      throw new ApiException(
          400, "Failed to test the S3 filesystem.", ApiErrors.requiredField("filesystem"));
    }
    Map<String, Object> config = s3SettingsFor(filesystem, body);
    S3Probe.test(filesystem, config);
  }

  public synchronized void testEmail(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new ApiException(
          400, "Failed to send the test email.", ApiErrors.requiredField("email"));
    }
    String email = bodyText(body, "email", "").toLowerCase(Locale.ROOT);
    String template = bodyText(body, "template", "");
    String collectionName = bodyText(body, "collection", SUPERUSERS);
    if (email.isBlank()) {
      throw new ApiException(
          400, "Failed to send the test email.", ApiErrors.requiredField("email"));
    }
    if (template.isBlank()) {
      throw new ApiException(
          400, "Failed to send the test email.", ApiErrors.requiredField("template"));
    }
    if (!EMAIL_PATTERN.matcher(email).matches()) {
      throw new ApiException(
          400,
          "Failed to send the test email.",
          ApiErrors.invalidField("email", "Invalid email address."));
    }
    if (!TEST_EMAIL_TEMPLATES.contains(template)) {
      throw new ApiException(
          400,
          "Failed to send the test email.",
          ApiErrors.invalidField("template", "Invalid email template."));
    }
    CollectionSchema collection = findCollectionOrNull(collectionName);
    if (collection == null || !"auth".equals(collection.type)) {
      throw new ApiException(
          400,
          "Failed to send the test email.",
          fieldError(
              "collection",
              "validation_invalid_value",
              "Must be a valid auth collection id or name."));
    }

    TestEmailContent content = testEmailContent(template, settings);
    Map<String, Object> smtp = mergedSettingsSection("smtp", body);
    if (truthyObject(smtp.get("enabled")) && !textSetting(smtp.get("host")).isBlank()) {
      SmtpMailer.send(
          smtpSettings(smtp),
          new SmtpMailer.Message(
              textSetting(settingsSection("meta").get("senderName")),
              senderAddress(),
              email,
              content.subject(),
              content.html(),
              content.text()));
      return;
    }

    Map<String, Object> request = new LinkedHashMap<>();
    request.put("id", IdGenerator.id());
    request.put("type", "testEmail");
    request.put("template", template);
    request.put("collectionId", collection.id);
    request.put("collectionName", collection.name);
    request.put("email", email);
    request.put("subject", content.subject());
    request.put("text", content.text());
    request.put("html", content.html());
    request.put("created", now());
    request.put(
        "expires", DateTimeFormatter.ISO_INSTANT.format(Instant.now().plus(Duration.ofHours(2))));
    authRequests.add(request);
    pruneAuthRequests();
    saveAuthRequests();
  }

  public synchronized Map<String, Object> generateAppleClientSecret(JsonNode body) {
    return AppleClientSecretGenerator.generate(mapper, body);
  }

  public synchronized Map<String, Object> listLogs(Map<String, String> query) {
    Map<String, String> safeQuery = query == null ? Map.of() : query;
    SearchQuerySupport.Parameters search = SearchQuerySupport.parse(safeQuery);
    SearchFieldValidationSupport.validateLogs(search);
    List<Map<String, Object>> items = new ArrayList<>();
    for (int i = 0; i < logs.size(); i++) {
      Map<String, Object> log = copyMap(logs.get(i));
      log.put(INTERNAL_ROWID, i + 1);
      if (matchesLogFilter(log, search.filter())) {
        items.add(log);
      }
    }
    SearchQuerySupport.sortMaps(items, search.sort(), "-created");
    int total = items.size();
    int from = search.fromIndex(total);
    int to = Math.min(total, from + search.perPage());
    List<Map<String, Object>> pageItems =
        items.subList(from, to).stream()
            .map(this::withoutInternalLogFields)
            .map(log -> selectFields(log, safeQuery.get("fields")))
            .collect(Collectors.toCollection(ArrayList::new));
    return SearchQuerySupport.result(search, total, pageItems);
  }

  public synchronized Map<String, Object> getLog(String id, Map<String, String> query) {
    Map<String, String> safeQuery = query == null ? Map.of() : query;
    return logs.stream()
        .filter(log -> Objects.equals(id, String.valueOf(log.get("id"))))
        .findFirst()
        .map(this::copyMap)
        .map(log -> selectFields(log, safeQuery.get("fields")))
        .orElseThrow(() -> new ApiException(404, "Log not found."));
  }

  public synchronized List<Map<String, Object>> logStats(Map<String, String> query) {
    Map<String, String> safeQuery = query == null ? Map.of() : query;
    SearchFieldValidationSupport.validateLogFilter(safeQuery.get("filter"));
    Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
    logs.stream()
        .map(this::copyMap)
        .filter(log -> matchesLogFilter(log, safeQuery.get("filter")))
        .sorted(Comparator.comparing(log -> String.valueOf(log.getOrDefault("created", ""))))
        .forEach(
            log -> {
              String date = logHour(log.get("created"));
              Map<String, Object> bucket =
                  grouped.computeIfAbsent(
                      date,
                      key -> {
                        Map<String, Object> created = new LinkedHashMap<>();
                        created.put("date", key);
                        created.put("total", 0);
                        return created;
                      });
              bucket.put("total", ((Number) bucket.get("total")).intValue() + 1);
            });
    return new ArrayList<>(grouped.values());
  }

  public synchronized List<Map<String, Object>> listCrons() {
    List<CronJob> jobs = cronJobs();
    jobs.sort(
        (left, right) -> {
          boolean leftSystem = left.id().startsWith("__pb");
          boolean rightSystem = right.id().startsWith("__pb");
          if (leftSystem && !rightSystem) {
            return 1;
          }
          if (!leftSystem && rightSystem) {
            return -1;
          }
          return left.id().compareTo(right.id());
        });
    return jobs.stream()
        .map(job -> orderedMap("id", job.id(), "expression", job.expression()))
        .collect(Collectors.toCollection(ArrayList::new));
  }

  public void runCron(String id) {
    CronJob job;
    synchronized (this) {
      job =
          cronJobs().stream()
              .filter(candidate -> Objects.equals(candidate.id(), id))
              .findFirst()
              .orElseThrow(() -> new ApiException(404, "Missing or invalid cron job"));
    }
    cronRunner.execute(() -> runCronJob(job.id()));
  }

  public synchronized Map<String, Object> runSql(JsonNode body) {
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
      SecuritySupport.logInternalFailure("execute JSONL SQL", e);
      String message = "Failed to execute query.";
      throw new ApiException(
          400,
          message,
          ApiErrors.invalidField("query", "The SQL statement could not be executed."));
    }

    return orderedMap(
        "execTime", Math.max(0L, (System.nanoTime() - started) / 1_000_000L),
        "affectedRows", result.affectedRows(),
        "columns", result.columns(),
        "rows", result.rows());
  }

  public synchronized Map<String, Object> dryRunView(JsonNode body) {
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
    if (query.length() > 5000) {
      throw new ApiException(
          400,
          "An error occurred while validating the submitted data.",
          ApiErrors.invalidField("query", "query must be at most 5000 characters."));
    }

    try {
      return previewViewQuery(query, 10);
    } catch (RuntimeException e) {
      SecuritySupport.logInternalFailure("validate JSONL view query", e);
      throw new ApiException(400, "Invalid view query.", Map.of());
    }
  }

  private Map<String, Object> previewViewQuery(String query, int sampleSize) {
    String statement = ViewQuerySupport.normalizeSingleSelect(query);
    SqlResult result = executeSqlSelect(statement);
    List<List<Object>> limitRows = result.rows();
    if (limitRows.size() > sampleSize) {
      limitRows = limitRows.subList(0, sampleSize);
    }
    List<ViewQuerySupport.Column> columns =
        result.columns().stream()
            .map(
                column -> new ViewQuerySupport.Column(
                    String.valueOf(column.get("name")),
                    String.valueOf(column.getOrDefault("type", ""))))
            .toList();
    return ViewQuerySupport.result(columns, limitRows);
  }

  private SqlResult executeSql(String query) {
    List<String> statements =
        splitSqlStatements(query).stream()
            .map(String::trim)
            .filter(statement -> !statement.isBlank())
            .collect(Collectors.toCollection(ArrayList::new));
    if (statements.isEmpty()) {
      throw new IllegalArgumentException("empty query");
    }

    String upper = statements.get(0).toUpperCase(Locale.ROOT);
    boolean writeMode = SQL_WRITE_PREFIXES.stream().anyMatch(upper::startsWith);
    if (writeMode) {
      return transactional(
          () -> {
            long affected = 0;
            for (String statement : statements) {
              affected += executeSqlWrite(statement);
            }
            return new SqlResult(affected, List.of(), List.of());
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
    if (startsWithKeyword(sql, "insert")) {
      return executeSqlInsert(sql);
    }
    if (startsWithKeyword(sql, "update")) {
      return executeSqlUpdate(sql);
    }
    if (startsWithKeyword(sql, "delete")) {
      return executeSqlDelete(sql);
    }
    if (startsWithKeyword(sql, "create")) {
      return executeSqlCreate(sql);
    }
    if (startsWithKeyword(sql, "drop")) {
      return executeSqlDrop(sql);
    }
    throw new IllegalArgumentException("Unsupported SQL statement.");
  }

  private SqlResult executeSqlSelect(String statement) {
    String sql = statement.trim();
    if (!startsWithKeyword(sql, "select")) {
      throw new IllegalArgumentException("Unsupported SQL statement.");
    }
    String afterSelect = sql.substring(6).trim();
    int fromIndex = indexKeyword(afterSelect, "from");
    if (fromIndex < 0) {
      return executeSqlConstantSelect(afterSelect);
    }

    String selectPart = afterSelect.substring(0, fromIndex).trim();
    SqlFrom from = parseSqlFrom(afterSelect.substring(fromIndex + 4).trim());
    CollectionSchema collection = findCollection(from.table());
    List<Map<String, Object>> rows =
        records(collection).stream()
            .map(this::copyMap)
            .filter(record -> matchesSqlWhere(record, from.where()))
            .collect(Collectors.toCollection(ArrayList::new));
    sortSqlRows(rows, from.orderBy());

    List<SqlSelectExpression> expressions = sqlSelectExpressions(selectPart, collection, rows);
    if (expressions.size() == 1 && expressions.get(0).count()) {
      SqlSelectExpression expression = expressions.get(0);
      return new SqlResult(
          0,
          List.of(sqlColumn(expression.name(), "", true)),
          List.of(List.of(String.valueOf(rows.size()))));
    }

    int offset = Math.max(0, from.offset());
    int limit = from.limit() < 0 ? SQL_MAX_ROWS : Math.min(SQL_MAX_ROWS, from.limit());
    int fromRow = Math.min(rows.size(), offset);
    int toRow = Math.min(rows.size(), fromRow + limit);
    List<Map<String, Object>> columns =
        expressions.stream()
            .map(
                expression -> sqlColumn(
                    expression.name(), sqlColumnType(collection, expression.source()), true))
            .collect(Collectors.toCollection(ArrayList::new));
    List<List<Object>> resultRows = new ArrayList<>();
    for (Map<String, Object> row : rows.subList(fromRow, toRow)) {
      List<Object> values = new ArrayList<>();
      for (SqlSelectExpression expression : expressions) {
        values.add(sqlCell(sqlSelectValue(row, expression)));
      }
      resultRows.add(values);
    }
    return new SqlResult(0, columns, resultRows);
  }

  private SqlResult executeSqlConstantSelect(String selectPart) {
    List<SqlSelectExpression> expressions = sqlSelectExpressions(selectPart, null, List.of());
    if (expressions.stream().anyMatch(SqlSelectExpression::all)
        || expressions.stream().anyMatch(SqlSelectExpression::count)) {
      throw new IllegalArgumentException("Invalid SELECT expression.");
    }
    List<Map<String, Object>> columns =
        expressions.stream()
            .map(expression -> sqlColumn(expression.name(), "", true))
            .collect(Collectors.toCollection(ArrayList::new));
    List<Object> row =
        expressions.stream()
            .map(expression -> sqlCell(sqlSelectValue(Map.of(), expression)))
            .collect(Collectors.toCollection(ArrayList::new));
    return new SqlResult(0, columns, List.of(row));
  }

  private long executeSqlInsert(String sql) {
    String remainder = sql.substring("insert".length()).trim();
    if (startsWithKeyword(remainder, "into")) {
      remainder = remainder.substring(4).trim();
    }
    int columnsStart = remainder.indexOf('(');
    if (columnsStart < 0) {
      throw new IllegalArgumentException("INSERT columns are required.");
    }
    String tableName = unquoteIdentifier(remainder.substring(0, columnsStart).trim());
    int columnsEnd = findMatchingParen(remainder, columnsStart);
    List<String> columns =
        splitComma(remainder.substring(columnsStart + 1, columnsEnd)).stream()
            .map(JsonFileStore::unquoteIdentifier)
            .collect(Collectors.toCollection(ArrayList::new));
    String afterColumns = remainder.substring(columnsEnd + 1).trim();
    if (!startsWithKeyword(afterColumns, "values")) {
      throw new IllegalArgumentException("INSERT VALUES are required.");
    }
    String valuesText = afterColumns.substring(6).trim();
    CollectionSchema collection = findCollection(tableName);
    long affected = 0;
    for (String tuple : splitSqlValueTuples(valuesText)) {
      String trimmed = tuple.trim();
      if (!trimmed.startsWith("(") || !trimmed.endsWith(")")) {
        throw new IllegalArgumentException("Invalid INSERT value tuple.");
      }
      List<String> values = splitComma(trimmed.substring(1, trimmed.length() - 1));
      if (values.size() != columns.size()) {
        throw new IllegalArgumentException("INSERT column count doesn't match value count.");
      }
      Map<String, Object> data = new LinkedHashMap<>();
      for (int i = 0; i < columns.size(); i++) {
        data.put(columns.get(i), sqlLiteral(values.get(i)));
      }
      Map<String, Object> record =
          createRecordInternal(collection, mapper.valueToTree(data), false, null);
      records(collection).add(record);
      publishRealtime(collection, "create", record);
      affected++;
    }
    if (affected > 0) {
      saveRecords(collection);
    }
    return affected;
  }

  private long executeSqlUpdate(String sql) {
    String remainder = sql.substring("update".length()).trim();
    int setIndex = indexKeyword(remainder, "set");
    if (setIndex < 0) {
      throw new IllegalArgumentException("UPDATE SET is required.");
    }
    String tableName = unquoteIdentifier(remainder.substring(0, setIndex).trim());
    String afterSet = remainder.substring(setIndex + 3).trim();
    int whereIndex = indexKeyword(afterSet, "where");
    String assignmentsText = whereIndex < 0 ? afterSet : afterSet.substring(0, whereIndex).trim();
    String where = whereIndex < 0 ? "" : afterSet.substring(whereIndex + 5).trim();
    Map<String, Object> assignments = parseSqlAssignments(assignmentsText);
    CollectionSchema collection = findCollection(tableName);
    long affected = 0;
    for (Map<String, Object> record : records(collection)) {
      if (!matchesSqlWhere(record, where)) {
        continue;
      }
      record.putAll(assignments);
      record.put("updated", now());
      publishRealtime(collection, "update", record);
      affected++;
    }
    if (affected > 0) {
      saveRecords(collection);
    }
    return affected;
  }

  private long executeSqlDelete(String sql) {
    String remainder = sql.substring("delete".length()).trim();
    if (startsWithKeyword(remainder, "from")) {
      remainder = remainder.substring(4).trim();
    }
    int whereIndex = indexKeyword(remainder, "where");
    String tableName =
        unquoteIdentifier(
            whereIndex < 0 ? remainder.trim() : remainder.substring(0, whereIndex).trim());
    String where = whereIndex < 0 ? "" : remainder.substring(whereIndex + 5).trim();
    CollectionSchema collection = findCollection(tableName);
    List<Map<String, Object>> existing = records(collection);
    List<Map<String, Object>> removed = new ArrayList<>();
    existing.removeIf(
        record -> {
          if (!matchesSqlWhere(record, where)) {
            return false;
          }
          removed.add(copyMap(record));
          return true;
        });
    if (!removed.isEmpty()) {
      saveRecords(collection);
      removed.forEach(record -> publishRealtime(collection, "delete", record));
    }
    return removed.size();
  }

  private long executeSqlCreate(String sql) {
    String remainder = sql.substring("create".length()).trim();
    if (!startsWithKeyword(remainder, "table")) {
      throw new IllegalArgumentException("Only CREATE TABLE is supported.");
    }
    remainder = remainder.substring(5).trim();
    boolean ifNotExists = startsWithKeyword(remainder, "if not exists");
    if (ifNotExists) {
      remainder = remainder.substring("if not exists".length()).trim();
    }
    int columnsStart = remainder.indexOf('(');
    if (columnsStart < 0) {
      throw new IllegalArgumentException("CREATE TABLE columns are required.");
    }
    String tableName = unquoteIdentifier(remainder.substring(0, columnsStart).trim());
    if (findCollectionOrNull(tableName) != null) {
      if (ifNotExists) {
        return 0;
      }
      throw new IllegalArgumentException("table already exists: " + tableName);
    }
    int columnsEnd = findMatchingParen(remainder, columnsStart);
    CollectionSchema collection = new CollectionSchema();
    collection.name = tableName;
    collection.type = "base";
    collection.listRule = "";
    collection.viewRule = "";
    collection.createRule = "";
    collection.updateRule = "";
    collection.deleteRule = "";
    for (String rawColumn : splitComma(remainder.substring(columnsStart + 1, columnsEnd))) {
      SqlColumnDefinition column = sqlColumnDefinition(rawColumn);
      if (column == null || isSystemSqlColumn(column.name())) {
        continue;
      }
      FieldSchema field = new FieldSchema();
      field.name = column.name();
      field.type = column.type();
      field.required = column.required();
      field.unique = column.unique();
      collection.fields.add(field);
    }
    normalizeCollection(collection, false);
    CollectionRuleSupport.validate(
        collection,
        "Failed to create collection.",
        identifier -> resolveRuleCollection(collection, identifier));
    collectionsByName.put(collection.name, collection);
    recordsByCollectionId.put(collection.id, new ArrayList<>());
    saveAll();
    return 0;
  }

  private long executeSqlDrop(String sql) {
    String remainder = sql.substring("drop".length()).trim();
    if (!startsWithKeyword(remainder, "table")) {
      throw new IllegalArgumentException("Only DROP TABLE is supported.");
    }
    remainder = remainder.substring(5).trim();
    boolean ifExists = startsWithKeyword(remainder, "if exists");
    if (ifExists) {
      remainder = remainder.substring("if exists".length()).trim();
    }
    String tableName = unquoteIdentifier(remainder.trim());
    CollectionSchema collection = findCollectionOrNull(tableName);
    if (collection == null) {
      if (ifExists) {
        return 0;
      }
      throw new IllegalArgumentException("no such table: " + tableName);
    }
    deleteCollection(collection.name);
    return 0;
  }

  private SqlFrom parseSqlFrom(String rest) {
    List<SqlClauseIndex> indexes = new ArrayList<>();
    addSqlClauseIndex(indexes, rest, "where");
    addSqlClauseIndex(indexes, rest, "order by");
    addSqlClauseIndex(indexes, rest, "limit");
    addSqlClauseIndex(indexes, rest, "offset");
    indexes.sort(Comparator.comparingInt(SqlClauseIndex::index));
    int tableEnd = indexes.isEmpty() ? rest.length() : indexes.get(0).index();
    String tableSegment = rest.substring(0, tableEnd).trim();
    String table = unquoteIdentifier(firstSqlToken(tableSegment));
    if (table.isBlank()) {
      throw new IllegalArgumentException("SELECT table is required.");
    }
    String where = sqlClauseText(rest, indexes, "where");
    String orderBy = sqlClauseText(rest, indexes, "order by");
    int limit = parseSqlLimit(sqlClauseText(rest, indexes, "limit"), -1);
    int offset = parseSqlLimit(sqlClauseText(rest, indexes, "offset"), 0);
    return new SqlFrom(table, where, orderBy, limit, offset);
  }

  private void addSqlClauseIndex(List<SqlClauseIndex> indexes, String text, String keyword) {
    int index = indexKeyword(text, keyword);
    if (index >= 0) {
      indexes.add(new SqlClauseIndex(keyword, index));
    }
  }

  private String sqlClauseText(String text, List<SqlClauseIndex> indexes, String keyword) {
    for (int i = 0; i < indexes.size(); i++) {
      SqlClauseIndex current = indexes.get(i);
      if (!current.keyword().equals(keyword)) {
        continue;
      }
      int start = current.index() + keyword.length();
      int end = i + 1 < indexes.size() ? indexes.get(i + 1).index() : text.length();
      return text.substring(start, end).trim();
    }
    return "";
  }

  private List<SqlSelectExpression> sqlSelectExpressions(
      String selectPart, CollectionSchema collection, List<Map<String, Object>> rows) {
    if (selectPart == null || selectPart.isBlank()) {
      throw new IllegalArgumentException("SELECT expressions are required.");
    }
    if ("*".equals(selectPart.trim())) {
      if (collection == null) {
        throw new IllegalArgumentException("SELECT * requires a table.");
      }
      return sqlAllColumns(collection, rows).stream()
          .map(name -> new SqlSelectExpression(name, name, false, true, null, false))
          .collect(Collectors.toCollection(ArrayList::new));
    }
    List<SqlSelectExpression> expressions = new ArrayList<>();
    for (String raw : splitComma(selectPart)) {
      String expression = raw.trim();
      if (expression.isBlank()) {
        continue;
      }
      String alias = "";
      int asIndex = indexKeyword(expression, "as");
      if (asIndex >= 0) {
        alias = unquoteIdentifier(expression.substring(asIndex + 2).trim());
        expression = expression.substring(0, asIndex).trim();
      }
      String lowered = expression.toLowerCase(Locale.ROOT);
      boolean count = "count(*)".equals(lowered) || "count(1)".equals(lowered);
      Object literal = null;
      boolean literalOnly = false;
      if (!count && isSqlLiteral(expression)) {
        literal = sqlLiteral(expression);
        literalOnly = true;
      }
      String name = alias.isBlank() ? expression : alias;
      expressions.add(
          new SqlSelectExpression(expression, name, count, false, literal, literalOnly));
    }
    if (expressions.isEmpty()) {
      throw new IllegalArgumentException("SELECT expressions are required.");
    }
    return expressions;
  }

  private List<String> sqlAllColumns(CollectionSchema collection, List<Map<String, Object>> rows) {
    LinkedHashSet<String> names = new LinkedHashSet<>();
    names.add("id");
    names.add("collectionId");
    names.add("collectionName");
    names.add("created");
    names.add("updated");
    collection.fields.forEach(field -> names.add(field.name));
    rows.forEach(row -> names.addAll(row.keySet()));
    return new ArrayList<>(names);
  }

  private Object sqlSelectValue(Map<String, Object> row, SqlSelectExpression expression) {
    if (expression.literalOnly()) {
      return expression.literal();
    }
    return readPath(row, expression.source());
  }

  private Map<String, Object> sqlColumn(String name, String type, boolean nullable) {
    return orderedMap("name", name, "type", type == null ? "" : type, "nullable", nullable);
  }

  private String sqlColumnType(CollectionSchema collection, String source) {
    if (collection == null || source == null || source.isBlank()) {
      return "";
    }
    String normalized = unquoteIdentifier(source);
    if (List.of("id", "collectionId", "collectionName", "created", "updated")
        .contains(normalized)) {
      return "TEXT";
    }
    for (FieldSchema field : collection.fields) {
      if (Objects.equals(field.name, normalized)) {
        return switch (normalizeType(field.type)) {
          case "bool", "boolean" -> "BOOLEAN";
          case "number" -> "NUMERIC";
          case "json", "relation", "file", "select" -> "JSON";
          default -> "TEXT";
        };
      }
    }
    return "";
  }

  private boolean matchesSqlWhere(Map<String, Object> record, String where) {
    if (where == null || where.isBlank()) {
      return true;
    }
    String rule = sqlWhereToRule(where);
    return RuleEvaluator.matches(rule, ruleContext(record, null, Map.of(), "GET", null));
  }

  private String sqlWhereToRule(String where) {
    String rule = where.replace("<>", "!=");
    rule = replaceSqlKeyword(rule, "and", "&&");
    rule = replaceSqlKeyword(rule, "or", "||");
    return rule;
  }

  private void sortSqlRows(List<Map<String, Object>> rows, String orderBy) {
    if (orderBy == null || orderBy.isBlank()) {
      return;
    }
    Comparator<Map<String, Object>> comparator = null;
    for (String raw : splitComma(orderBy)) {
      String part = raw.trim();
      if (part.isBlank()) {
        continue;
      }
      String[] tokens = part.split("\\s+");
      String field = unquoteIdentifier(tokens[0]);
      boolean desc = tokens.length > 1 && "desc".equalsIgnoreCase(tokens[1]);
      Comparator<Map<String, Object>> next =
          Comparator.comparing(row -> String.valueOf(readPath(row, field)));
      comparator =
          comparator == null
              ? (desc ? next.reversed() : next)
              : comparator.thenComparing(desc ? next.reversed() : next);
    }
    if (comparator != null) {
      rows.sort(comparator);
    }
  }

  private Map<String, Object> parseSqlAssignments(String assignmentsText) {
    Map<String, Object> assignments = new LinkedHashMap<>();
    for (String raw : splitComma(assignmentsText)) {
      int equals = indexOperator(raw, '=');
      if (equals < 0) {
        throw new IllegalArgumentException("Invalid assignment.");
      }
      String field = unquoteIdentifier(raw.substring(0, equals).trim());
      if (field.isBlank()) {
        throw new IllegalArgumentException("Invalid assignment field.");
      }
      assignments.put(field, sqlLiteral(raw.substring(equals + 1).trim()));
    }
    if (assignments.isEmpty()) {
      throw new IllegalArgumentException("UPDATE assignments are required.");
    }
    return assignments;
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
    String type = sqlFieldType(typeText);
    return new SqlColumnDefinition(
        name,
        type,
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

  private Object sqlCell(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Map<?, ?> || value instanceof List<?>) {
      try {
        return mapper.writeValueAsString(value);
      } catch (IOException e) {
        return String.valueOf(value);
      }
    }
    return String.valueOf(value);
  }

  private static List<String> splitSqlStatements(String sql) {
    return splitOn(sql, ';');
  }

  private static List<String> splitComma(String text) {
    return splitOn(text, ',');
  }

  private static List<String> splitOn(String text, char delimiter) {
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

  private static List<String> splitSqlValueTuples(String valuesText) {
    List<String> tuples = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    char quote = 0;
    int parens = 0;
    for (int i = 0; i < valuesText.length(); i++) {
      char ch = valuesText.charAt(i);
      if (quote != 0) {
        current.append(ch);
        if (ch == quote) {
          if (i + 1 < valuesText.length() && valuesText.charAt(i + 1) == quote) {
            current.append(valuesText.charAt(++i));
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
      if (ch == ',' && parens == 0) {
        if (!current.toString().isBlank()) {
          tuples.add(current.toString().trim());
        }
        current.setLength(0);
      } else {
        current.append(ch);
      }
    }
    if (!current.toString().isBlank()) {
      tuples.add(current.toString().trim());
    }
    return tuples;
  }

  private static int indexKeyword(String text, String keyword) {
    String lower = text.toLowerCase(Locale.ROOT);
    String target = keyword.toLowerCase(Locale.ROOT);
    char quote = 0;
    for (int i = 0; i <= text.length() - target.length(); i++) {
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
      if (!lower.startsWith(target, i)) {
        continue;
      }
      int before = i - 1;
      int after = i + target.length();
      if ((before < 0 || !isIdentifierChar(text.charAt(before)))
          && (after >= text.length() || !isIdentifierChar(text.charAt(after)))) {
        return i;
      }
    }
    return -1;
  }

  private static boolean startsWithKeyword(String text, String keyword) {
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

  private static String replaceSqlKeyword(String text, String keyword, String replacement) {
    StringBuilder out = new StringBuilder();
    char quote = 0;
    String lower = text.toLowerCase(Locale.ROOT);
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (quote != 0) {
        out.append(ch);
        if (ch == quote) {
          if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
            out.append(text.charAt(++i));
          } else {
            quote = 0;
          }
        }
        continue;
      }
      if (ch == '\'' || ch == '"' || ch == '`') {
        quote = ch;
        out.append(ch);
        continue;
      }
      if (lower.startsWith(keyword, i)
          && (i == 0 || !isIdentifierChar(text.charAt(i - 1)))
          && (i + keyword.length() >= text.length()
              || !isIdentifierChar(text.charAt(i + keyword.length())))) {
        out.append(replacement);
        i += keyword.length() - 1;
      } else {
        out.append(ch);
      }
    }
    return out.toString();
  }

  private static int indexOperator(String text, char operator) {
    char quote = 0;
    int parens = 0;
    for (int i = 0; i < text.length(); i++) {
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
        parens++;
      } else if (ch == ')' && parens > 0) {
        parens--;
      } else if (ch == operator && parens == 0) {
        return i;
      }
    }
    return -1;
  }

  private static int findMatchingParen(String text, int openIndex) {
    char quote = 0;
    int depth = 0;
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
    throw new IllegalArgumentException("Missing closing parenthesis.");
  }

  private static boolean isSqlLiteral(String expression) {
    String value = expression == null ? "" : expression.trim();
    if (value.isBlank()) {
      return false;
    }
    if ("null".equalsIgnoreCase(value)
        || "true".equalsIgnoreCase(value)
        || "false".equalsIgnoreCase(value)) {
      return true;
    }
    if ((value.startsWith("'") && value.endsWith("'"))
        || (value.startsWith("\"") && value.endsWith("\""))) {
      return true;
    }
    try {
      Double.parseDouble(value);
      return true;
    } catch (NumberFormatException ignored) {
      return false;
    }
  }

  private static Object sqlLiteral(String expression) {
    String value = expression == null ? "" : expression.trim();
    if ("null".equalsIgnoreCase(value)) {
      return null;
    }
    if ("true".equalsIgnoreCase(value)) {
      return true;
    }
    if ("false".equalsIgnoreCase(value)) {
      return false;
    }
    if ((value.startsWith("'") && value.endsWith("'"))
        || (value.startsWith("\"") && value.endsWith("\""))) {
      return value.substring(1, value.length() - 1).replace("''", "'").replace("\"\"", "\"");
    }
    try {
      if (value.contains(".")) {
        return Double.parseDouble(value);
      }
      return Long.parseLong(value);
    } catch (NumberFormatException ignored) {
      throw new IllegalArgumentException("Unsupported SQL literal: " + expression);
    }
  }

  private static int parseSqlLimit(String text, int fallback) {
    if (text == null || text.isBlank()) {
      return fallback;
    }
    String token = firstSqlToken(text);
    try {
      return Math.max(0, Integer.parseInt(token));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid LIMIT/OFFSET value.");
    }
  }

  private static String firstSqlToken(String text) {
    String trimmed = text == null ? "" : text.trim();
    if (trimmed.isBlank()) {
      return "";
    }
    int end = 0;
    while (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end))) {
      end++;
    }
    return trimmed.substring(0, end);
  }

  private static String unquoteIdentifier(String value) {
    String text = value == null ? "" : value.trim();
    if (text.endsWith(";")) {
      text = text.substring(0, text.length() - 1).trim();
    }
    if ((text.startsWith("\"") && text.endsWith("\""))
        || (text.startsWith("`") && text.endsWith("`"))
        || (text.startsWith("[") && text.endsWith("]"))) {
      return text.substring(1, text.length() - 1);
    }
    return text;
  }

  private static boolean isIdentifierChar(char ch) {
    return Character.isLetterOrDigit(ch) || ch == '_' || ch == '@';
  }

  public synchronized void recordActivityLog(
      String method,
      String url,
      int status,
      long execTimeMillis,
      RequestPrincipal principal,
      Map<String, String> headers,
      String remoteAddress) {
    Map<String, Object> logSettings = settingsSection("logs");
    long maxDays = longSetting(logSettings.get("maxDays"), 5L);
    long minLevel = longSetting(logSettings.get("minLevel"), 0L);
    int level = status >= 400 ? 8 : 0;
    if (maxDays <= 0 || level < minLevel) {
      return;
    }

    String timestamp = now();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("type", "request");
    data.put("method", method == null ? "" : method);
    data.put("url", url == null ? "" : url);
    data.put("status", status);
    data.put("execTime", (double) execTimeMillis);
    Object logIp =
        logSettings.containsKey("logIP") ? logSettings.get("logIP") : logSettings.get("logIp");
    if (truthySetting(logIp, true) && remoteAddress != null && !remoteAddress.isBlank()) {
      data.put("remoteIP", remoteAddress);
      data.put("userIP", remoteAddress);
    }
    if (headers != null) {
      String userAgent = headers.getOrDefault("User-Agent", headers.get("user-agent"));
      String referer = headers.getOrDefault("Referer", headers.get("referer"));
      if (userAgent != null && !userAgent.isBlank()) {
        data.put("userAgent", userAgent);
      }
      if (referer != null && !referer.isBlank()) {
        data.put("referer", referer);
      }
    }
    if (principal != null) {
      data.put("auth", principal.superuser() ? SUPERUSERS : principal.collectionName());
      if (truthySetting(logSettings.get("logAuthId"), false)) {
        data.put("authId", principal.id());
      }
    } else {
      data.put("auth", "");
    }

    long maxDataSize = longSetting(logSettings.get("maxDataSize"), 0L);
    String rawMessage = (method == null ? "" : method) + " " + (url == null ? "" : url);
    LogPersistenceSanitizer.Result sanitized =
        LogPersistenceSanitizer.sanitize(rawMessage, data, maxDataSize, mapper);

    Map<String, Object> log = new LinkedHashMap<>();
    log.put("id", IdGenerator.id());
    log.put("created", timestamp);
    log.put("updated", timestamp);
    log.put("level", level);
    log.put("message", sanitized.message());
    log.put("data", sanitized.dataMap());
    logs.add(log);
    pruneLogs();
    saveLogs();
  }

  @Override
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
    SearchQuerySupport.Parameters search = SearchQuerySupport.parse(safeQuery);
    SearchFieldValidationSupport.validateCollections(search);
    List<Map<String, Object>> items =
        collectionsByName.values().stream()
            .map(this::collectionMap)
            .filter(collection -> matchesCollectionFilter(collection, search.filter()))
            .collect(Collectors.toCollection(ArrayList::new));
    SearchQuerySupport.sortMaps(items, search.sort(), null);
    int total = items.size();
    int from = search.fromIndex(total);
    int to = Math.min(total, from + search.perPage());
    List<Map<String, Object>> pageItems =
        items.subList(from, to).stream()
            .map(item -> selectFields(item, safeQuery.get("fields")))
            .collect(Collectors.toCollection(ArrayList::new));
    return SearchQuerySupport.result(search, total, pageItems);
  }

  public synchronized Map<String, Object> collectionScaffolds() {
    Map<String, Object> base = collectionMap(scaffoldCollection("base"));
    Map<String, Object> auth = collectionMap(scaffoldCollection("auth"));
    Map<String, Object> view = collectionMap(scaffoldCollection("view"));
    view.put("viewQuery", "");
    return orderedMap(
        "base", base,
        "auth", auth,
        "view", view);
  }

  public synchronized List<Map<String, Object>> oauth2ProviderMetadata() {
    return OAuth2ProviderManager.providers().stream()
        .map(
            provider -> orderedMap(
                "name", provider.name(),
                "displayName", provider.displayName(),
                "logo", provider.logo()))
        .collect(Collectors.toCollection(ArrayList::new));
  }

  public synchronized Map<String, Object> getCollection(
      String idOrName, Map<String, String> query) {
    Map<String, String> safeQuery = query == null ? Map.of() : query;
    return selectFields(collectionMap(findCollection(idOrName)), safeQuery.get("fields"));
  }

  public synchronized CollectionSchema createCollection(JsonNode body) {
    if (!body.isObject()) {
      throw new ApiException(
          400,
          "Collection payload must be a JSON object.",
          ApiErrors.invalidField("body", "Request body must be a JSON object."));
    }
    CollectionSchema collection = mapper.convertValue(body, CollectionSchema.class);
    applyAuthOptions(collection, body);
    if (body.has("fields") || body.has("schema")) {
      collection.fields = SchemaIdSupport.canonicalizeSubmittedFields(collection.fields);
    }
    if ("view".equals(collection.type)) {
      collection.viewQuery = viewQueryFromBody(body, collection.viewQuery);
      prepareViewCollection(collection, "Failed to create collection.");
    }
    normalizeCollection(collection, false);
    AuthCollectionConfigMerge.mergeSubmitted(mapper, collection, new CollectionSchema(), body);
    CollectionModelValidation.validate(
        null,
        collection,
        body,
        collectionsByName.values(),
        this::jsonTableExists,
        "Failed to create collection.");
    collection.indexes =
        CollectionIndexSupport.normalize(
            collection, collectionIndexNames(null), "Failed to create collection.");
    AuthCollectionConfigValidation.validate(collection, "Failed to create collection.");
    CollectionRuleSupport.validate(
        collection,
        "Failed to create collection.",
        identifier -> resolveRuleCollection(collection, identifier));
    collectionsByName.put(collection.name, copyCollection(collection));
    recordsByCollectionId.put(collection.id, new ArrayList<>());
    saveAll();
    return copyCollection(collection);
  }

  public synchronized CollectionSchema updateCollection(String idOrName, JsonNode body) {
    CollectionSchema persisted = findCollection(idOrName);
    CollectionSchema existing = copyCollection(persisted);
    if (!body.isObject()) {
      throw new ApiException(
          400,
          "Collection payload must be a JSON object.",
          ApiErrors.invalidField("body", "Request body must be a JSON object."));
    }
    String oldName = existing.name;
    if (body.hasNonNull("name")) {
      existing.name = body.get("name").asText();
    }
    if (body.hasNonNull("type") && !existing.system) {
      existing.type = body.get("type").asText();
    }
    JsonNode options = body.get("options");
    if (body.has("viewQuery")
        || body.path("options").has("query")
        || body.path("options").has("viewQuery")) {
      existing.viewQuery = viewQueryFromBody(body, existing.viewQuery);
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
    if (body.has("authRule") || options != null && options.has("authRule")) {
      JsonNode node = body.has("authRule") ? body.get("authRule") : options.get("authRule");
      existing.authRule = nullableText(node);
    }
    if (body.has("manageRule") || options != null && options.has("manageRule")) {
      JsonNode node = body.has("manageRule") ? body.get("manageRule") : options.get("manageRule");
      existing.manageRule = nullableText(node);
    }
    JsonNode fields = body.has("fields") ? body.get("fields") : body.get("schema");
    if (fields != null && fields.isArray()) {
      existing.fields = mapper.convertValue(fields, new TypeReference<>() {
      });
      existing.fields = SchemaIdSupport.canonicalizeSubmittedFields(existing.fields);
      SchemaIdSupport.assignMissingFieldIds(existing.fields, persisted.fields);
      if (!"view".equals(persisted.type)) {
        CollectionFieldProtection.validateSystemFieldUpdate(
            persisted.fields, existing.fields, "Failed to update collection.");
      }
    }
    if (body.has("indexes") && body.get("indexes").isArray()) {
      existing.indexes = mapper.convertValue(body.get("indexes"), new TypeReference<>() {
      });
    }
    if (body.has("passwordAuth") || options != null && options.has("passwordAuth")) {
      JsonNode node =
          body.has("passwordAuth") ? body.get("passwordAuth") : options.get("passwordAuth");
      existing.passwordAuth = mapper.convertValue(node, CollectionSchema.PasswordAuthConfig.class);
    }
    if (body.has("otp") || options != null && options.has("otp")) {
      JsonNode node = body.has("otp") ? body.get("otp") : options.get("otp");
      existing.otp = mapper.convertValue(node, CollectionSchema.OtpConfig.class);
    }
    if (body.has("mfa") || options != null && options.has("mfa")) {
      JsonNode node = body.has("mfa") ? body.get("mfa") : options.get("mfa");
      existing.mfa = mapper.convertValue(node, CollectionSchema.MfaConfig.class);
    }
    if (body.has("oauth2") || options != null && options.has("oauth2")) {
      JsonNode node = body.has("oauth2") ? body.get("oauth2") : options.get("oauth2");
      existing.oauth2 = mapper.convertValue(node, CollectionSchema.OAuth2Config.class);
    }
    if (body.has("authToken") || options != null && options.has("authToken")) {
      JsonNode node = body.has("authToken") ? body.get("authToken") : options.get("authToken");
      existing.authToken = mapper.convertValue(node, CollectionSchema.TokenConfig.class);
    }
    if (body.has("passwordResetToken") || options != null && options.has("passwordResetToken")) {
      JsonNode node =
          body.has("passwordResetToken")
              ? body.get("passwordResetToken")
              : options.get("passwordResetToken");
      existing.passwordResetToken = mapper.convertValue(node, CollectionSchema.TokenConfig.class);
    }
    if (body.has("verificationToken") || options != null && options.has("verificationToken")) {
      JsonNode node =
          body.has("verificationToken")
              ? body.get("verificationToken")
              : options.get("verificationToken");
      existing.verificationToken = mapper.convertValue(node, CollectionSchema.TokenConfig.class);
    }
    if (body.has("emailChangeToken") || options != null && options.has("emailChangeToken")) {
      JsonNode node =
          body.has("emailChangeToken")
              ? body.get("emailChangeToken")
              : options.get("emailChangeToken");
      existing.emailChangeToken = mapper.convertValue(node, CollectionSchema.TokenConfig.class);
    }
    if (body.has("fileToken") || options != null && options.has("fileToken")) {
      JsonNode node = body.has("fileToken") ? body.get("fileToken") : options.get("fileToken");
      existing.fileToken = mapper.convertValue(node, CollectionSchema.TokenConfig.class);
    }
    if (body.has("authAlert") || options != null && options.has("authAlert")) {
      JsonNode node = body.has("authAlert") ? body.get("authAlert") : options.get("authAlert");
      existing.authAlert = mapper.convertValue(node, CollectionSchema.AuthAlertConfig.class);
    }
    if (body.has("verificationTemplate")
        || options != null && options.has("verificationTemplate")) {
      JsonNode node =
          body.has("verificationTemplate")
              ? body.get("verificationTemplate")
              : options.get("verificationTemplate");
      existing.verificationTemplate =
          mapper.convertValue(node, CollectionSchema.EmailTemplate.class);
    }
    if (body.has("resetPasswordTemplate")
        || options != null && options.has("resetPasswordTemplate")) {
      JsonNode node =
          body.has("resetPasswordTemplate")
              ? body.get("resetPasswordTemplate")
              : options.get("resetPasswordTemplate");
      existing.resetPasswordTemplate =
          mapper.convertValue(node, CollectionSchema.EmailTemplate.class);
    }
    if (body.has("confirmEmailChangeTemplate")
        || options != null && options.has("confirmEmailChangeTemplate")) {
      JsonNode node =
          body.has("confirmEmailChangeTemplate")
              ? body.get("confirmEmailChangeTemplate")
              : options.get("confirmEmailChangeTemplate");
      existing.confirmEmailChangeTemplate =
          mapper.convertValue(node, CollectionSchema.EmailTemplate.class);
    }
    CollectionResponseSupport.preserveOAuth2ClientSecrets(persisted.oauth2, existing.oauth2);
    if ("view".equals(existing.type)) {
      prepareViewCollection(existing, "Failed to update collection.");
    }
    normalizeCollection(existing, true);
    AuthCollectionConfigMerge.mergeSubmitted(mapper, existing, persisted, body);
    CollectionModelValidation.validate(
        persisted,
        existing,
        body,
        collectionsByName.values(),
        this::jsonTableExists,
        "Failed to update collection.");
    CollectionRuleSupport.validate(
        existing,
        "Failed to update collection.",
        identifier -> resolveRuleCollection(existing, identifier));
    existing.indexes =
        CollectionIndexSupport.normalize(
            existing, collectionIndexNames(persisted.id), "Failed to update collection.");
    AuthCollectionConfigValidation.validate(existing, "Failed to update collection.");
    AuthSystemCollections.applySaveInvariants(existing);
    if ("auth".equals(existing.type) && authRuleChanged(persisted.authRule, existing.authRule)) {
      existing.authToken.secret = IdGenerator.secret();
    }
    if (!oldName.equals(existing.name)) {
      collectionsByName.remove(oldName);
    }
    collectionsByName.put(existing.name, existing);

    Set<String> allowedFields =
        new LinkedHashSet<>(
            Arrays.asList("id", "created", "updated", "collectionId", "collectionName", "expand"));
    if ("auth".equals(existing.type)) {
      allowedFields.addAll(
          Arrays.asList(
              "username", "email", "emailVisibility", "verified", "tokenKey", "passwordHash"));
    }
    existing.fields.forEach(f -> allowedFields.add(f.name));

    if (!"view".equals(existing.type)) {
      boolean recordsChanged = false;
      for (Map<String, Object> record : records(existing)) {
        boolean changed = record.keySet().removeIf(key -> !allowedFields.contains(key));
        if (changed) {
          recordsChanged = true;
        }
      }
      if (recordsChanged) {
        saveRecords(existing);
      }
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
    clearAuthRecordDependents(collection.id, null);
    try {
      Files.deleteIfExists(recordsFile(collection));
      deleteRecursively(collectionStorageDir(collection));
      saveSchema();
    } catch (IOException e) {
      throw new IllegalStateException("failed to delete collection", e);
    }
  }

  public synchronized void truncateCollection(String idOrName) {
    CollectionSchema collection = findCollection(idOrName);
    if (collection.system) {
      throw new ApiException(400, "System collections cannot be truncated.");
    }
    records(collection).clear();
    if ("auth".equals(collection.type)) {
      clearAuthRecordDependents(collection.id, null);
    }
    saveRecords(collection);
    deleteRecursively(collectionStorageDir(collection));
  }

  @Override
  public synchronized Map<String, Object> importCollections(JsonNode body, boolean dryRun) {
    if (body == null || !body.isObject()) {
      throw new ApiException(
          400,
          "Failed to import collections.",
          ApiErrors.invalidField(
              "collections", "Collections import payload must be a JSON object."));
    }
    JsonNode collectionsNode = body.get("collections");
    if (collectionsNode == null || !collectionsNode.isArray() || collectionsNode.isEmpty()) {
      throw new ApiException(
          400, "Failed to import collections.", ApiErrors.requiredField("collections"));
    }

    boolean deleteMissing = body.path("deleteMissing").asBoolean(false);
    Map<String, CollectionSchema> nextCollections = new LinkedHashMap<>();
    Map<String, List<Map<String, Object>>> oldRecords =
        mapper.convertValue(recordsByCollectionId, RECORD_MAP);

    if (!deleteMissing) {
      collectionsByName.values().stream()
          .map(this::copyCollection)
          .forEach(collection -> nextCollections.put(collection.name, collection));
    } else {
      collectionsByName.values().stream()
          .filter(collection -> collection.system)
          .map(this::copyCollection)
          .forEach(collection -> nextCollections.put(collection.name, collection));
    }

    List<CollectionSchema> newOrUpdated = new ArrayList<>();
    Set<String> seenIds = new LinkedHashSet<>();
    Set<String> seenNames = new LinkedHashSet<>();
    for (JsonNode item : collectionsNode) {
      if (item == null || !item.isObject()) {
        throw new ApiException(
            400,
            "Failed to import collections.",
            fieldError(
                "collections",
                "validation_invalid_value",
                "Each collection must be a JSON object."));
      }
      CollectionSchema imported = mapper.convertValue(item, CollectionSchema.class);
      imported.fields = SchemaIdSupport.canonicalizeSubmittedFields(imported.fields);
      CollectionSchema existing = existingCollectionForImport(imported);
      if (existing != null) {
        if (imported.id == null || imported.id.isBlank()) {
          imported.id = existing.id;
        }
        if (imported.created == null || imported.created.isBlank()) {
          imported.created = existing.created;
        }
        if (existing.system) {
          imported.system = true;
        }
        SchemaIdSupport.assignMissingFieldIds(imported.fields, existing.fields);
        if (!"view".equals(existing.type)) {
          CollectionFieldProtection.validateSystemFieldUpdate(
              existing.fields, imported.fields, "Failed to import collections.");
        }
      } else {
        if (imported.id == null || imported.id.isBlank()) {
          String importedType =
              normalizeType(
                  imported.type == null || imported.type.isBlank() ? "base" : imported.type);
          imported.id =
              SchemaIdSupport.nextCollectionId(
                  importedType,
                  imported.name,
                  candidate -> nextCollections.values().stream()
                      .anyMatch(collection -> Objects.equals(collection.id, candidate))
                      || seenIds.contains(candidate));
        }
        imported.created = now();
      }
      normalizeCollection(imported, existing != null);
      AuthCollectionConfigMerge.mergeSubmitted(
          mapper, imported, existing == null ? new CollectionSchema() : existing, item);
      CollectionModelValidation.validate(
          existing,
          imported,
          item,
          nextCollections.values(),
          this::jsonTableExists,
          "Failed to import collections.");
      imported.indexes =
          CollectionIndexSupport.normalize(
              imported,
              collectionIndexNames(nextCollections.values(), existing == null ? null : existing.id),
              "Failed to import collections.");
      AuthCollectionConfigValidation.validate(imported, "Failed to import collections.");
      AuthSystemCollections.applySaveInvariants(imported);
      imported.updated = now();
      if (!seenIds.add(imported.id)) {
        throw new ApiException(
            400,
            "Failed to import collections.",
            fieldError(
                "collections",
                "validation_invalid_value",
                "Duplicate collection id: " + imported.id));
      }
      if (!seenNames.add(imported.name)) {
        throw new ApiException(
            400,
            "Failed to import collections.",
            fieldError(
                "collections",
                "validation_invalid_value",
                "Duplicate collection name: " + imported.name));
      }
      putImportedCollection(nextCollections, imported, existing);
      newOrUpdated.add(imported);
    }

    for (CollectionSchema imported : newOrUpdated) {
      CollectionRuleSupport.validate(
          imported,
          "Failed to import collections.",
          identifier -> nextCollections.values().stream()
              .filter(
                  candidate -> Objects.equals(identifier, candidate.id)
                      || Objects.equals(identifier, candidate.name))
              .findFirst()
              .orElse(null));
    }

    List<String> deleted = new ArrayList<>();
    if (deleteMissing) {
      Set<String> nextIds =
          nextCollections.values().stream().map(c -> c.id).collect(Collectors.toSet());
      for (CollectionSchema existing : collectionsByName.values()) {
        if (!existing.system && !nextIds.contains(existing.id)) {
          deleted.add(existing.id);
        }
      }
    }

    if (dryRun) {
      return Map.of("collections", newOrUpdated, "deletedCollections", deleted);
    }

    try {
      Set<String> nextIds =
          nextCollections.values().stream().map(c -> c.id).collect(Collectors.toSet());
      Map<String, List<Map<String, Object>>> nextRecords = new LinkedHashMap<>();
      for (CollectionSchema collection : nextCollections.values()) {
        List<Map<String, Object>> records = oldRecords.get(collection.id);
        nextRecords.put(collection.id, records == null ? new ArrayList<>() : records);
      }
      collectionsByName.clear();
      collectionsByName.putAll(nextCollections);
      recordsByCollectionId.clear();
      recordsByCollectionId.putAll(nextRecords);
      saveAll();
      cleanupRemovedCollectionData(nextIds);
    } catch (RuntimeException e) {
      SecuritySupport.logInternalFailure("import JSONL collections", e);
      throw new ApiException(
          400,
          "Failed to import collections.",
          fieldError(
              "collections",
              "validation_invalid_value",
              "The collections could not be imported."));
    }

    return Map.of("collections", newOrUpdated, "deletedCollections", deleted);
  }

  public synchronized Map<String, Object> listRecords(
      String collectionName, Map<String, String> query, RequestPrincipal principal) {
    return listRecordsInternal(collectionName, RuleRequestContext.of(query, Map.of()), principal);
  }

  private Map<String, Object> listRecordsInternal(
      String collectionName, RuleRequestContext request, RequestPrincipal principal) {
    Map<String, String> query = request.query();
    CollectionSchema collection = findCollection(collectionName);
    requireSuperuserRecordsAccess(collection, principal);
    SearchQuerySupport.Parameters search = SearchQuerySupport.parse(query);
    SearchQuerySupport.rejectSuperuserOnlyRuleFields(query, principal);
    SearchFieldValidationSupport.validateRecords(this, collection, search, principal);
    if (!isSuperuser(principal) && collection.listRule == null) {
      throw new ApiException(403, "Only superusers can list this collection.");
    }
    boolean includeHidden = isSuperuser(principal);
    List<Map<String, Object>> source =
        records(collection).stream()
            .filter(
                record -> isSuperuser(principal)
                    || matchesRule(
                        collection,
                        collection.listRule,
                        record,
                        null,
                        request,
                        "GET",
                        principal))
            .filter(
                record -> matchesFilter(collection, record, search.filter(), request, principal))
            .collect(Collectors.toCollection(ArrayList::new));
    SearchQuerySupport.sortMaps(
        source,
        search.sort(),
        "created",
        (record, path) -> RecordFieldResolverSupport.resolveSortValue(
            this,
            collection,
            record,
            path,
            request,
            principal,
            isSuperuser(principal),
            !isSuperuser(principal)));
    int total = source.size();
    int from = search.fromIndex(total);
    int to = Math.min(total, from + search.perPage());
    List<Map<String, Object>> pageItems =
        source.subList(from, to).stream()
            .map(
                record -> RecordProcessor.process(
                    this, collection, record, includeHidden, request, principal))
            .collect(Collectors.toCollection(ArrayList::new));
    return SearchQuerySupport.result(search, total, pageItems);
  }

  public synchronized Map<String, Object> getRecord(
      String collectionName, String id, Map<String, String> query, RequestPrincipal principal) {
    return getRecordInternal(collectionName, id, RuleRequestContext.of(query, Map.of()), principal);
  }

  private Map<String, Object> getRecordInternal(
      String collectionName, String id, RuleRequestContext request, RequestPrincipal principal) {
    CollectionSchema collection = findCollection(collectionName);
    requireSuperuserRecordsAccess(collection, principal);
    Map<String, Object> record = findRecord(collection, id);
    if (!canViewExpandedRecord(collection, record, request, principal)) {
      throw new ApiException(404, "Record not found.");
    }
    return RecordProcessor.process(
        this, collection, record, isSuperuser(principal), request, principal);
  }

  public synchronized Map<String, Object> createRecord(
      String collectionName, JsonNode body, RequestPrincipal principal) {
    return createRecord(collectionName, body, Map.of(), principal);
  }

  public synchronized Map<String, Object> createRecord(
      String collectionName,
      JsonNode body,
      Map<String, List<UploadedFile>> files,
      RequestPrincipal principal) {
    return createRecord(collectionName, body, files, Map.of(), principal);
  }

  public synchronized Map<String, Object> createRecord(
      String collectionName,
      JsonNode body,
      Map<String, List<UploadedFile>> files,
      Map<String, String> query,
      RequestPrincipal principal) {
    return createRecordInternal(
        collectionName, body, files, RuleRequestContext.of(query, Map.of()), principal);
  }

  private Map<String, Object> createRecordInternal(
      String collectionName,
      JsonNode body,
      Map<String, List<UploadedFile>> files,
      RuleRequestContext request,
      RequestPrincipal principal) {
    CollectionSchema collection = findCollection(collectionName);
    requireSuperuserRecordsAccess(collection, principal);
    requireWritableRecordCollection(collection);
    if (!isSuperuser(principal) && collection.createRule == null) {
      throw new ApiException(403, "Only superusers can create records in this collection.");
    }
    JsonNode writableBody = RecordInputProtection.writableBody(collection, body, principal);
    Map<String, List<UploadedFile>> writableFiles =
        RecordInputProtection.writableFiles(collection, files, principal);
    Map<String, Object> record =
        buildRecord(collection, bodyWithFileMarkers(collection, writableBody, writableFiles), null);
    validateAuthSystemRecord(collection, record, null);
    Map<String, Object> manageCandidate = new LinkedHashMap<>(record);
    if ("auth".equals(collection.type)) {
      manageCandidate.put("verified", false);
    }
    boolean manageAccess =
        AuthRecordMutationSupport.hasManageAccess(
            collection, manageCandidate, jsonToMap(body), request, "POST", principal, this);
    AuthRecordMutationSupport.validate(
        collection, null, record, body, manageAccess, passwordField(collection));
    if (SUPERUSERS.equals(collection.name)) {
      record.put("verified", true);
    }
    FileChanges fileChanges =
        prepareFileChanges(collection, record, writableFiles, writableBody, null);
    record.putAll(fileChanges.values());
    if (!isSuperuser(principal)
        && !matchesRule(
            collection, collection.createRule, record, record, request, "POST", principal)) {
      throw new ApiException(400, "The record failed the collection create rule.");
    }
    writeFileChanges(collection, record, fileChanges);
    records(collection).add(record);
    saveRecords(collection);
    publishRealtime(collection, "create", record);
    return RecordProcessor.process(
        this, collection, record, isSuperuser(principal), request, principal);
  }

  public synchronized Map<String, Object> updateRecord(
      String collectionName, String id, JsonNode body, RequestPrincipal principal) {
    return updateRecord(collectionName, id, body, Map.of(), principal);
  }

  public synchronized Map<String, Object> upsertRecord(
      String collectionName, String id, JsonNode body, RequestPrincipal principal) {
    return upsertRecord(collectionName, id, body, Map.of(), principal);
  }

  public synchronized Map<String, Object> upsertRecord(
      String collectionName,
      String id,
      JsonNode body,
      Map<String, String> query,
      RequestPrincipal principal) {
    return upsertRecordInternal(
        collectionName, id, body, Map.of(), RuleRequestContext.of(query, Map.of()), principal);
  }

  public synchronized Map<String, Object> upsertRecord(
      String collectionName,
      String id,
      JsonNode body,
      Map<String, List<UploadedFile>> files,
      Map<String, String> query,
      RequestPrincipal principal) {
    return upsertRecordInternal(
        collectionName, id, body, files, RuleRequestContext.of(query, Map.of()), principal);
  }

  private Map<String, Object> upsertRecordInternal(
      String collectionName,
      String id,
      JsonNode body,
      Map<String, List<UploadedFile>> files,
      RuleRequestContext request,
      RequestPrincipal principal) {
    if (body == null || !body.isObject()) {
      throw new ApiException(
          400,
          "Record payload must be a JSON object.",
          ApiErrors.invalidField("body", "Request body must be a JSON object."));
    }
    CollectionSchema collection = findCollection(collectionName);
    requireWritableRecordCollection(collection);
    String recordId = id == null || id.isBlank() ? requiredText(body, "id") : id;
    ObjectNode upsertBody = body.deepCopy();
    if (!upsertBody.hasNonNull("id")) {
      upsertBody.put("id", recordId);
    }
    if (findRecordOrNull(collection, recordId) == null) {
      return createRecordInternal(collectionName, upsertBody, files, request, principal);
    }
    return updateRecordInternal(collectionName, recordId, upsertBody, files, request, principal);
  }

  public synchronized Map<String, Object> updateRecord(
      String collectionName,
      String id,
      JsonNode body,
      Map<String, List<UploadedFile>> files,
      RequestPrincipal principal) {
    return updateRecord(collectionName, id, body, files, Map.of(), principal);
  }

  public synchronized Map<String, Object> updateRecord(
      String collectionName,
      String id,
      JsonNode body,
      Map<String, List<UploadedFile>> files,
      Map<String, String> query,
      RequestPrincipal principal) {
    return updateRecordInternal(
        collectionName, id, body, files, RuleRequestContext.of(query, Map.of()), principal);
  }

  private Map<String, Object> updateRecordInternal(
      String collectionName,
      String id,
      JsonNode body,
      Map<String, List<UploadedFile>> files,
      RuleRequestContext request,
      RequestPrincipal principal) {
    CollectionSchema collection = findCollection(collectionName);
    requireSuperuserRecordsAccess(collection, principal);
    requireWritableRecordCollection(collection);
    Map<String, Object> existing = findRecord(collection, id);
    requireRecordRule(
        collection,
        collection.updateRule,
        existing,
        jsonToMap(body),
        request,
        "PATCH",
        principal,
        "update");
    JsonNode writableBody = RecordInputProtection.writableBody(collection, body, principal);
    Map<String, List<UploadedFile>> writableFiles =
        RecordInputProtection.writableFiles(collection, files, principal);
    Map<String, Object> patch =
        buildRecord(
            collection, bodyWithFileMarkers(collection, writableBody, writableFiles), existing);
    Map<String, Object> candidate = new LinkedHashMap<>(existing);
    candidate.putAll(patch);
    validateAuthSystemRecord(collection, candidate, id);
    boolean manageAccess =
        AuthRecordMutationSupport.hasManageAccess(
            collection, existing, jsonToMap(body), request, "PATCH", principal, this);
    AuthRecordMutationSupport.validate(
        collection, existing, patch, body, manageAccess, passwordField(collection));
    Object previousPassword =
        "auth".equals(collection.type) ? existing.get(passwordField(collection)) : null;
    boolean passwordChanged =
        "auth".equals(collection.type)
            && patch.containsKey(passwordField(collection))
            && !Objects.equals(previousPassword, patch.get(passwordField(collection)));
    boolean emailChanged =
        "auth".equals(collection.type)
            && patch.containsKey("email")
            && !Objects.equals(existing.get("email"), patch.get("email"));
    if (passwordChanged || emailChanged) {
      patch.put("tokenKey", IdGenerator.secret());
    }
    if (SUPERUSERS.equals(collection.name)) {
      patch.put("verified", true);
    }
    FileChanges fileChanges =
        prepareFileChanges(collection, patch, writableFiles, writableBody, existing);
    patch.putAll(fileChanges.values());
    existing.putAll(patch);
    if (passwordChanged || emailChanged) {
      clearAuthSecurityState(collection.id, id);
    }
    existing.put("updated", now());
    writeFileChanges(collection, existing, fileChanges);
    saveRecords(collection);
    publishRealtime(collection, "update", existing);
    return RecordProcessor.process(
        this, collection, existing, isSuperuser(principal), request, principal);
  }

  public synchronized void deleteRecord(
      String collectionName, String id, RequestPrincipal principal) {
    deleteRecordInternal(collectionName, id, RuleRequestContext.empty(), principal);
  }

  private void deleteRecordInternal(
      String collectionName, String id, RuleRequestContext request, RequestPrincipal principal) {
    CollectionSchema collection = findCollection(collectionName);
    requireSuperuserRecordsAccess(collection, principal);
    requireWritableRecordCollection(collection);
    Map<String, Object> existing = findRecord(collection, id);
    requireRecordRule(
        collection, collection.deleteRule, existing, null, request, "DELETE", principal, "delete");
    List<Map<String, Object>> records = records(collection);
    if (SUPERUSERS.equals(collection.name) && records.size() <= 1) {
      throw new ApiException(400, "You can't delete the only existing superuser.");
    }
    boolean removed = records.removeIf(record -> Objects.equals(record.get("id"), id));
    if (!removed) {
      throw new ApiException(404, "Record not found.");
    }
    if ("auth".equals(collection.type)) {
      clearAuthRecordDependents(collection.id, id);
    }
    saveRecords(collection);
    publishRealtime(collection, "delete", existing);
  }

  private void requireWritableRecordCollection(CollectionSchema collection) {
    if ("view".equals(collection.type)) {
      throw new ApiException(400, "View collections are read-only.");
    }
  }

  private void validateAuthSystemRecord(
      CollectionSchema collection, Map<String, Object> candidate, String currentId) {
    if (!AuthSystemCollections.contains(collection.name)) {
      return;
    }
    String collectionRef = String.valueOf(candidate.getOrDefault("collectionRef", ""));
    String recordRef = String.valueOf(candidate.getOrDefault("recordRef", ""));
    CollectionSchema authCollection = findCollectionOrNull(collectionRef);
    if (authCollection == null || !"auth".equals(authCollection.type)) {
      throw new ApiException(
          400,
          "Failed to validate record.",
          fieldError(
              "collectionRef",
              "validation_invalid_value",
              "Missing or invalid auth collection reference."));
    }
    if (findRecordOrNull(authCollection, recordRef) == null) {
      throw new ApiException(
          400,
          "Failed to validate record.",
          fieldError(
              "recordRef",
              "validation_invalid_value",
              "Missing or invalid auth record reference."));
    }
    for (Map<String, Object> existing : records(collection)) {
      if (currentId != null && Objects.equals(currentId, existing.get("id"))) {
        continue;
      }
      boolean duplicate =
          switch (collection.name) {
            case AuthSystemCollections.AUTH_ORIGINS ->
              sameSystemFields(existing, candidate, "collectionRef", "recordRef", "fingerprint");
            case AuthSystemCollections.EXTERNAL_AUTHS ->
              sameSystemFields(existing, candidate, "collectionRef", "recordRef", "provider")
                  || sameSystemFields(
                      existing, candidate, "collectionRef", "provider", "providerId");
            default -> false;
          };
      if (duplicate) {
        throw new ApiException(
            400,
            "Failed to validate record.",
            fieldError(
                "recordRef", "validation_not_unique", "The system auth record already exists."));
      }
    }
  }

  private boolean sameSystemFields(
      Map<String, Object> left, Map<String, Object> right, String... fields) {
    for (String field : fields) {
      if (!Objects.equals(left.get(field), right.get(field))) {
        return false;
      }
    }
    return true;
  }

  public synchronized <T> T transactional(java.util.function.Supplier<T> operation) {
    Map<String, CollectionSchema> collectionsSnapshot = new LinkedHashMap<>();
    collectionsByName.values().stream()
        .map(this::copyCollection)
        .forEach(collection -> collectionsSnapshot.put(collection.name, collection));
    Map<String, List<Map<String, Object>>> recordsSnapshot =
        mapper.convertValue(recordsByCollectionId, RECORD_MAP);
    List<Map<String, Object>> mfasSnapshot = mapper.convertValue(mfas, RECORD_LIST);
    List<Map<String, Object>> authRequestsSnapshot = mapper.convertValue(authRequests, RECORD_LIST);
    List<Map<String, Object>> authOriginsSnapshot = mapper.convertValue(authOrigins, RECORD_LIST);
    List<Map<String, Object>> externalAuthsSnapshot =
        mapper.convertValue(externalAuths, RECORD_LIST);
    List<Map<String, Object>> otpsSnapshot = mapper.convertValue(otps, RECORD_LIST);
    Path storageSnapshot = null;
    try {
      storageSnapshot = snapshotStorage();
      return operation.get();
    } catch (RuntimeException e) {
      collectionsByName.clear();
      collectionsByName.putAll(collectionsSnapshot);
      recordsByCollectionId.clear();
      recordsByCollectionId.putAll(recordsSnapshot);
      restoreRecords(mfas, mfasSnapshot);
      restoreRecords(authRequests, authRequestsSnapshot);
      restoreRecords(authOrigins, authOriginsSnapshot);
      restoreRecords(externalAuths, externalAuthsSnapshot);
      restoreRecords(otps, otpsSnapshot);
      restoreStorageSnapshot(storageSnapshot);
      saveAll();
      throw e;
    } finally {
      deleteRecursively(storageSnapshot);
    }
  }

  private void restoreRecords(
      List<Map<String, Object>> target, List<Map<String, Object>> snapshot) {
    target.clear();
    target.addAll(snapshot);
  }

  private Path snapshotStorage() {
    try {
      Path parent = dataDir.toAbsolutePath().getParent();
      Path snapshot =
          Files.createTempDirectory(
              parent == null ? Path.of(".").toAbsolutePath() : parent, "pb_storage_tx_");
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

  public synchronized List<Map<String, Object>> listBackups() {
    try {
      Files.createDirectories(backupsDir);
      try (Stream<Path> paths = Files.list(backupsDir)) {
        return paths
            .filter(
                path -> !Files.isSymbolicLink(path)
                    && Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".zip"))
            .map(this::backupInfo)
            .sorted(
                (left, right) -> String.valueOf(right.get("modified"))
                    .compareTo(String.valueOf(left.get("modified"))))
            .collect(Collectors.toCollection(ArrayList::new));
      }
    } catch (IOException e) {
      throw new IllegalStateException("failed to list backups", e);
    }
  }

  @Override
  public Map<String, Object> createBackup(JsonNode body) {
    String requested = body == null || !body.hasNonNull("name") ? "" : body.get("name").asText();
    String key = createBackupKey(requested);
    return backupOperations.run(
        key,
        () -> {
          Path snapshotDir;
          synchronized (this) {
            saveAll();
            try {
              snapshotDir = createSnapshotDirectory();
            } catch (IOException e) {
              throw new IllegalStateException("failed to create backup snapshot", e);
            }
          }
          Path backup = backupPath(key);
          if (Files.exists(backup)) {
            deleteRecursively(snapshotDir);
            throw new ApiException(400, "Backup already exists.", ApiErrors.notUniqueField("name"));
          }
          Path temporary = null;
          try {
            Files.createDirectories(backupsDir);
            temporary = Files.createTempFile(backupsDir, ".create-backup-", ".tmp");
            try (OutputStream output =
                Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING);
                ZipOutputStream zip = new ZipOutputStream(output)) {
              zipDirectory(snapshotDir, zip);
            }
            publishBackup(temporary, backup);
            FilePermissionSupport.secureFile(backup);
            temporary = null;
            return backupInfo(backup);
          } catch (IOException e) {
            throw new IllegalStateException("failed to create backup", e);
          } finally {
            deleteRecursively(snapshotDir);
            deleteTemporaryBackup(temporary);
          }
        });
  }

  private Path createSnapshotDirectory() throws IOException {
    Path tempDir = Files.createTempDirectory(dataDir, ".backup-snap-");
    FilePermissionSupport.secureDirectory(tempDir);
    try (Stream<Path> paths = Files.walk(dataDir)) {
      for (Path path : paths
          .filter(Files::isRegularFile)
          .filter(path -> !Files.isSymbolicLink(path))
          .filter(path -> !isBackupExcluded(path))
          .filter(path -> !path.startsWith(tempDir))
          .collect(Collectors.toList())) {
        Path relative = dataDir.relativize(path);
        Path target = tempDir.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
      }
    }
    return tempDir;
  }

  public synchronized Map<String, Object> uploadBackup(String filename, byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      throw new ApiException(400, "Backup file is required.", ApiErrors.requiredField("file"));
    }
    String key = backupKey(filename);
    Path backup = backupPath(key);
    if (Files.exists(backup)) {
      throw new ApiException(400, "Backup already exists.", ApiErrors.notUniqueField("file"));
    }
    Path temporary = null;
    try {
      Files.createDirectories(backupsDir);
      temporary = Files.createTempFile(backupsDir, ".upload-backup-", ".tmp");
      Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
      publishBackup(temporary, backup);
      FilePermissionSupport.secureFile(backup);
      temporary = null;
      return backupInfo(backup);
    } catch (IOException e) {
      throw new IllegalStateException("failed to upload backup", e);
    } finally {
      deleteTemporaryBackup(temporary);
    }
  }

  public synchronized Path backupFile(String key) {
    Path backup = backupPath(key);
    return !Files.isSymbolicLink(backup) && Files.exists(backup) && Files.isRegularFile(backup)
        ? backup
        : null;
  }

  public synchronized void deleteBackup(String key) {
    String safeKey = backupKey(key);
    if (backupOperations.active(safeKey)) {
      throw new ApiException(400, "The backup is currently being used and cannot be deleted.");
    }
    try {
      boolean deleted = Files.deleteIfExists(backupPath(safeKey));
      if (!deleted) {
        throw new ApiException(400, "Invalid or already deleted backup file.");
      }
    } catch (IOException e) {
      throw new IllegalStateException("failed to delete backup", e);
    }
  }

  public synchronized Map<String, Object> restoreBackup(String key) {
    Path backup = backupFile(key);
    if (backup == null) {
      throw new ApiException(400, "Missing or invalid backup file.");
    }
    String safeKey = backup.getFileName().toString();
    return backupOperations.run(
        safeKey,
        () -> {
          Path temp = null;
          try {
            String currentSecret =
                Files.exists(secretFile)
                    ? Files.readString(secretFile, StandardCharsets.UTF_8)
                    : "";
            Path parent = dataDir.toAbsolutePath().getParent();
            temp =
                Files.createTempDirectory(
                    parent == null ? Path.of(".").toAbsolutePath() : parent, "pb_restore_");
            unzipBackup(backup, temp);
            clearDataDirForRestore();
            copyDirectory(temp, dataDir);
            if (!currentSecret.isBlank()) {
              Files.writeString(secretFile, currentSecret, StandardCharsets.UTF_8);
              FilePermissionSupport.secureFile(secretFile);
            }
            FilePermissionSupport.secureDirectory(recordsDir);
            FilePermissionSupport.secureDirectory(storageDir);
            FilePermissionSupport.secureDirectory(backupsDir);
            load();
            FilePermissionSupport.secureTree(dataDir);
            return Map.of("restored", safeKey);
          } catch (IOException e) {
            throw new IllegalStateException("failed to restore backup", e);
          } finally {
            if (temp != null) {
              deleteRecursively(temp);
            }
          }
        });
  }

  public synchronized Map<String, Object> authMethods(String collectionName) {
    CollectionSchema collection = findCollection(collectionName);
    if (!"auth".equals(collection.type)) {
      throw new ApiException(404, "The requested resource wasn't found.");
    }
    Map<String, Object> password = new LinkedHashMap<>();
    password.put("enabled", collection.passwordAuth.enabled);
    password.put(
        "identityFields",
        collection.passwordAuth.enabled
            ? new ArrayList<>(collection.passwordAuth.identityFields)
            : List.of());
    Map<String, Object> oauth2 = new LinkedHashMap<>();
    List<Map<String, Object>> oauthProviders =
        collection.oauth2.enabled
            ? collection.oauth2.providers.stream()
                .map(provider -> OAuth2ProviderManager.providerMetadata(provider.name))
                .filter(Objects::nonNull)
                .map(
                    metadata -> {
                      CollectionSchema.OAuth2ProviderConfig config =
                          collection.oauth2.providers.stream()
                              .filter(provider -> metadata.name().equalsIgnoreCase(provider.name))
                              .findFirst()
                              .orElse(null);
                      if (config == null) {
                        return null;
                      }
                      OAuth2Support.AuthMethodProviderInfo info =
                          OAuth2Support.authMethodInfo(
                              config, metadata.displayName(), metadata.logo());
                      return orderedMap(
                          "name", info.name(),
                          "displayName", info.displayName(),
                          "logo", info.logo(),
                          "state", info.state(),
                          "authURL", info.authURL(),
                          "authUrl", info.authUrl(),
                          "codeVerifier", info.codeVerifier(),
                          "codeChallenge", info.codeChallenge(),
                          "codeChallengeMethod", info.codeChallengeMethod());
                    })
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new))
            : List.of();
    oauth2.put("enabled", collection.oauth2.enabled);
    oauth2.put("providers", oauthProviders);
    Map<String, Object> mfa = new LinkedHashMap<>();
    mfa.put("enabled", collection.mfa.enabled);
    mfa.put("duration", collection.mfa.enabled ? collection.mfa.duration : 0);
    Map<String, Object> otp = new LinkedHashMap<>();
    otp.put("enabled", collection.otp.enabled);
    otp.put("duration", collection.otp.enabled ? collection.otp.duration : 0);
    return Map.of(
        "password", password,
        "oauth2", oauth2,
        "mfa", mfa,
        "otp", otp,
        "authProviders",
        oauthProviders.stream()
            .map(
                provider -> orderedMap(
                    "name", provider.get("name"),
                    "displayName", provider.get("displayName"),
                    "logo", "",
                    "state", provider.get("state"),
                    "authURL", provider.get("authURL"),
                    "authUrl", provider.get("authUrl"),
                    "codeVerifier", provider.get("codeVerifier"),
                    "codeChallenge", provider.get("codeChallenge"),
                    "codeChallengeMethod", provider.get("codeChallengeMethod")))
            .collect(Collectors.toCollection(ArrayList::new)),
        "usernamePassword",
        collection.passwordAuth.enabled
            && collection.passwordAuth.identityFields.contains("username"),
        "emailPassword",
        collection.passwordAuth.enabled
            && collection.passwordAuth.identityFields.contains("email"));
  }

  public synchronized Map<String, Object> authWithOAuth2(
      String collectionName, JsonNode body, Map<String, String> query, RequestPrincipal principal) {
    return authWithOAuth2(collectionName, body, query, principal, AuthOriginContext.empty());
  }

  public synchronized Map<String, Object> authWithOAuth2(
      String collectionName,
      JsonNode body,
      Map<String, String> query,
      RequestPrincipal principal,
      AuthOriginContext origin) {
    return authWithOAuth2Internal(
        collectionName, body, RuleRequestContext.of(query, Map.of()), principal, origin);
  }

  private Map<String, Object> authWithOAuth2Internal(
      String collectionName,
      JsonNode body,
      RuleRequestContext request,
      RequestPrincipal principal,
      AuthOriginContext origin) {
    request = request.withContext(RuleRequestContext.OAUTH2);
    Map<String, String> query = request.query();
    CollectionSchema collection = authCollection(collectionName);
    if (!collection.oauth2.enabled) {
      throw new ApiException(
          403, "The collection is not configured to allow OAuth2 authentication.");
    }

    String providerName = requiredText(body, "provider");
    String code = requiredText(body, "code");
    String redirectURL = requiredText(body, "redirectURL");
    String codeVerifier = bodyText(body, "codeVerifier", "");
    CollectionSchema.OAuth2ProviderConfig provider =
        collection.oauth2.providers.stream()
            .filter(item -> providerName.equalsIgnoreCase(item.name))
            .findFirst()
            .orElseThrow(
                () -> new ApiException(
                    400,
                    "Failed to authenticate.",
                    ApiErrors.invalidField(
                        "provider",
                        "Provider with name "
                            + providerName
                            + " is missing or is not enabled.")));

    OAuth2Support.OAuth2User oauthUser =
        OAuth2Support.authenticate(mapper, provider, code, redirectURL, codeVerifier);
    Map<String, Object> record =
        findOAuth2LinkedRecord(collection, providerName, oauthUser.providerId());
    boolean isNew = false;

    if (record == null && principal != null && sameCollection(principal, collection)) {
      record = findRecordOrNull(collection, principal.id());
    }
    if (record == null && !oauthUser.email().isBlank()) {
      record = findAuthRecordByEmail(collection, oauthUser.email());
    }
    if (record == null) {
      ObjectNode payload = mapper.createObjectNode();
      JsonNode createData = body.get("createData");
      if (createData != null && createData.isObject()) {
        payload.setAll((ObjectNode) createData);
      }
      if (!payload.hasNonNull("email") && !oauthUser.email().isBlank()) {
        payload.put("email", oauthUser.email());
      }
      if (!payload.hasNonNull(passwordField(collection))) {
        String generatedPassword = IdGenerator.randomPassword();
        payload.put(passwordField(collection), generatedPassword);
        payload.put("passwordConfirm", generatedPassword);
      }
      Map<String, List<UploadedFile>> mappedFiles =
          OAuth2FieldMappingSupport.apply(
              collection,
              payload,
              oauthUser,
              (field, value) -> oauthUsernameAvailable(collection, field, value));
      RuleRequestContext createRequest =
          RuleRequestContext.of(Map.of(), request.headers(), RuleRequestContext.OAUTH2);
      Map<String, Object> created =
          createRecordInternal(collection.name, payload, mappedFiles, createRequest, principal);
      record = findRecord(collection, String.valueOf(created.get("id")));
      if (!oauthUser.email().isBlank()
          && oauthUser.email().equalsIgnoreCase(String.valueOf(record.getOrDefault("email", "")))
          && !truthyObject(record.get("verified"))) {
        record.put("verified", true);
        touch(record);
        saveRecords(collection);
      }
      isNew = true;
    } else {
      boolean changed = false;
      if (!oauthUser.email().isBlank()
          && oauthUser.email().equalsIgnoreCase(String.valueOf(record.getOrDefault("email", "")))
          && !truthyObject(record.get("verified"))) {
        record.put("verified", true);
        changed = true;
      }
      if (changed) {
        touch(record);
        saveRecords(collection);
      }
    }

    upsertExternalAuth(collection, record, providerName, oauthUser.providerId());
    Map<String, Object> meta = new LinkedHashMap<>(oauthUser.raw());
    meta.put("isNew", isNew);

    String mfaId = bodyOrQueryText(body, query, "mfaId", null);
    return handleAuthWithMfa(
        collection, record, request, mfaId, "oauth2", meta, origin, body, principal);
  }

  private boolean oauthUsernameAvailable(CollectionSchema collection, String field, String value) {
    FieldSchema schema =
        collection.fields.stream()
            .filter(candidate -> candidate != null && field.equals(candidate.name))
            .findFirst()
            .orElse(null);
    boolean unique =
        schema != null
            && (schema.unique
                || collection.indexes.stream()
                    .anyMatch(index -> CollectionIndexSupport.isSingleColumnUnique(index, field)));
    if (!unique) {
      return true;
    }
    return records(collection).stream()
        .noneMatch(record -> Objects.equals(record.get(field), value));
  }

  public synchronized Map<String, Object> requestOtp(String collectionName, JsonNode body) {
    CollectionSchema collection = authCollection(collectionName);
    if (!collection.otp.enabled) {
      throw new ApiException(403, "The collection is not configured to allow OTP authentication.");
    }
    String email = normalizedEmail(requiredText(body, "email"));
    Map<String, Object> record = findAuthRecordByEmail(collection, email);
    if (record == null) {
      return Map.of("otpId", IdGenerator.id());
    }

    String code = IdGenerator.digits(collection.otp.length);
    Map<String, Object> otp = latestReusableOtp(collection, record);
    if (otp == null) {
      otp = new LinkedHashMap<>();
      otp.put("id", IdGenerator.id());
      otp.put("collectionRef", collection.id);
      otp.put("recordRef", record.get("id"));
      otp.put("created", now());
      otps.add(otp);
    }
    otp.put("sentTo", email);
    otp.put("password", PasswordHasher.hash(code));
    otp.put("failedAttempts", 0);
    otp.remove("lastFailed");
    otp.put("updated", now());
    otp.put(
        "expires",
        DateTimeFormatter.ISO_INSTANT.format(
            Instant.now().plus(Duration.ofSeconds(collection.otp.duration))));
    pruneOtps();
    saveOtps();

    queueOtpMessage(collection, record, String.valueOf(otp.get("id")), email, code);
    return Map.of("otpId", otp.get("id"));
  }

  public synchronized Map<String, Object> authWithOtp(
      String collectionName, JsonNode body, Map<String, String> query) {
    return authWithOtp(collectionName, body, query, AuthOriginContext.empty());
  }

  public synchronized Map<String, Object> authWithOtp(
      String collectionName, JsonNode body, Map<String, String> query, AuthOriginContext origin) {
    return authWithOtpInternal(
        collectionName, body, RuleRequestContext.of(query, Map.of()), origin);
  }

  private Map<String, Object> authWithOtpInternal(
      String collectionName, JsonNode body, RuleRequestContext request, AuthOriginContext origin) {
    request = request.withContext(RuleRequestContext.OTP);
    Map<String, String> query = request.query();
    CollectionSchema collection = authCollection(collectionName);
    if (!collection.otp.enabled) {
      throw new ApiException(403, "The collection is not configured to allow OTP authentication.");
    }
    String otpId = requiredText(body, "otpId");
    String password = requiredText(body, "password");
    if (otpId.length() > 255) {
      throw new ApiException(
          400,
          "An error occurred while validating the submitted data.",
          fieldError("otpId", "validation_invalid_value", "otpId must be at most 255 characters."));
    }
    if (password.length() > 71) {
      throw new ApiException(
          400,
          "An error occurred while validating the submitted data.",
          fieldError(
              "password", "validation_invalid_value", "password must be at most 71 characters."));
    }

    Map<String, Object> otp = findOtp(otpId);
    if (!Objects.equals(collection.id, String.valueOf(otp.get("collectionRef")))) {
      throw invalidOtp();
    }
    if (otpExpired(otp)) {
      deleteOtp(otpId);
      throw invalidOtp();
    }
    Map<String, Object> record = findRecordOrNull(collection, String.valueOf(otp.get("recordRef")));
    if (record == null) {
      deleteOtp(otpId);
      throw invalidOtp();
    }
    ensureOtpAttemptsAllowed(otp);
    if (!PasswordHasher.verifyOrDummy(password, String.valueOf(otp.get("password")))) {
      recordOtpFailure(otp);
      throw invalidOtp();
    }

    deleteOtp(otpId);
    String sentTo = String.valueOf(otp.getOrDefault("sentTo", ""));
    if (!truthyObject(record.get("verified"))
        && !sentTo.isBlank()
        && sentTo.equalsIgnoreCase(String.valueOf(record.getOrDefault("email", "")))) {
      record.put("verified", true);
      if (!collection.mfa.enabled) {
        record.put(
            passwordField(collection),
            PasswordHasher.hash(IdGenerator.prefixed("otp_") + IdGenerator.id()));
        rotateAuthTokenKey(record);
        clearAuthSecurityState(collection.id, String.valueOf(record.get("id")));
      }
      touch(record);
      saveRecords(collection);
    }

    String mfaId = bodyOrQueryText(body, query, "mfaId", null);
    return handleAuthWithMfa(
        collection, record, request, mfaId, "otp", Map.of(), origin, body, null);
  }

  public synchronized void requestPasswordReset(String collectionName, JsonNode body) {
    CollectionSchema collection = authCollection(collectionName);
    String email = normalizedEmail(requiredText(body, "email"));
    Map<String, Object> record = findAuthRecordByEmail(collection, email);
    if (record != null) {
      appendAuthRequest(
          collection,
          record,
          "passwordReset",
          Map.of(),
          tokenDuration(
              collection.passwordResetToken,
              CollectionSchema.DEFAULT_PASSWORD_RESET_TOKEN_DURATION));
    }
  }

  public synchronized void confirmPasswordReset(String collectionName, JsonNode body) {
    AuthAction action =
        verifyAuthAction(collectionName, requiredText(body, "token"), "passwordReset");
    String password = requiredText(body, "password");
    String passwordConfirm = requiredText(body, "passwordConfirm");
    if (!password.equals(passwordConfirm)) {
      throw new ApiException(
          400,
          "passwordConfirm does not match password.",
          fieldError("passwordConfirm", "validation_invalid_value", "Passwords do not match."));
    }
    if (password.length() < 8) {
      throw new ApiException(
          400,
          "Password must be at least 8 characters.",
          fieldError(
              "password", "validation_invalid_value", "Password must be at least 8 characters."));
    }

    String passwordField = passwordField(action.collection());
    action.record().put(passwordField, PasswordHasher.hash(password));
    action.record().put("verified", true);
    rotateAuthTokenKey(action.record());
    clearAuthSecurityState(action.collection().id, String.valueOf(action.record().get("id")));
    touch(action.record());
    saveRecords(action.collection());
  }

  public synchronized void requestVerification(String collectionName, JsonNode body) {
    CollectionSchema collection = authCollection(collectionName);
    String email = normalizedEmail(requiredText(body, "email"));
    Map<String, Object> record = findAuthRecordByEmail(collection, email);
    if (record != null && !truthyObject(record.get("verified"))) {
      appendAuthRequest(
          collection,
          record,
          "verification",
          Map.of(),
          tokenDuration(
              collection.verificationToken, CollectionSchema.DEFAULT_VERIFICATION_TOKEN_DURATION));
    }
  }

  public synchronized void confirmVerification(String collectionName, JsonNode body) {
    AuthAction action =
        verifyAuthAction(collectionName, requiredText(body, "token"), "verification");
    action.record().put("verified", true);
    touch(action.record());
    saveRecords(action.collection());
  }

  public synchronized void requestEmailChange(
      String collectionName, JsonNode body, RequestPrincipal principal) {
    CollectionSchema collection = authCollection(collectionName);
    if (principal == null) {
      throw new ApiException(401, "Missing or invalid auth token.");
    }
    if (principal.superuser() || !sameCollection(principal, collection)) {
      throw new ApiException(403, "Auth record token required.");
    }
    Map<String, Object> record = findRecord(collection, principal.id());
    String newEmail = normalizedEmail(requiredText(body, "newEmail"));
    ensureEmailAvailable(collection, newEmail, String.valueOf(record.get("id")));
    appendAuthRequest(
        collection,
        record,
        "emailChange",
        Map.of("newEmail", newEmail),
        tokenDuration(
            collection.emailChangeToken, CollectionSchema.DEFAULT_EMAIL_CHANGE_TOKEN_DURATION));
  }

  public synchronized void confirmEmailChange(String collectionName, JsonNode body) {
    AuthAction action =
        verifyAuthAction(collectionName, requiredText(body, "token"), "emailChange");
    String password = requiredText(body, "password");
    if (!PasswordHasher.verifyOrDummy(
        password, String.valueOf(action.record().get(passwordField(action.collection()))))) {
      throw new ApiException(
          400, "Invalid password.", ApiErrors.invalidField("password", "Invalid password."));
    }
    String newEmail = normalizedEmail(String.valueOf(action.claims().getOrDefault("newEmail", "")));
    ensureEmailAvailable(action.collection(), newEmail, String.valueOf(action.record().get("id")));
    action.record().put("email", newEmail);
    action.record().put("verified", true);
    rotateAuthTokenKey(action.record());
    touch(action.record());
    saveRecords(action.collection());
  }

  public synchronized Map<String, Object> impersonate(
      String collectionName, String id, JsonNode body, Map<String, String> query) {
    CollectionSchema collection = authCollection(collectionName);
    Map<String, Object> record = findRecord(collection, id);
    long defaultDuration =
        tokenDurationSeconds(collection.authToken, CollectionSchema.DEFAULT_AUTH_TOKEN_DURATION);
    long requestedDuration = optionalLong(body, "duration", defaultDuration);
    long seconds = Math.max(60L, Math.min(604_800L, requestedDuration));
    return authResponse(collection, record, query, Duration.ofSeconds(seconds), "impersonate");
  }

  public synchronized Map<String, Object> fileToken(RequestPrincipal principal) {
    if (principal == null || principal.id().isBlank()) {
      throw new ApiException(401, "Missing or invalid auth token.");
    }
    Map<String, Object> claims = new LinkedHashMap<>(principal.claims());
    claims.remove("iat");
    claims.remove("exp");
    claims.put("tokenType", "file");
    CollectionSchema collection = findCollectionOrNull(principal.collectionId());
    if (collection == null
        && principal.collectionName() != null
        && !principal.collectionName().isBlank()) {
      collection = findCollectionOrNull(principal.collectionName());
    }
    Duration ttl =
        tokenDuration(
            collection == null ? null : collection.fileToken,
            CollectionSchema.DEFAULT_FILE_TOKEN_DURATION);
    return Map.of(
        "token",
        tokenService.create(
            claims,
            ttl,
            tokenSigningSecret(
                collection == null ? null : collection.fileToken, claims.get("tokenKey"))));
  }

  public synchronized Path filePath(
      String collectionIdOrName, String recordId, String filename, RequestPrincipal principal) {
    return filePath(
        collectionIdOrName,
        recordId,
        filename,
        RuleRequestContext.empty().withContext(RuleRequestContext.PROTECTED_FILE),
        principal);
  }

  @Override
  public synchronized Path filePath(
      String collectionIdOrName,
      String recordId,
      String filename,
      RuleRequestContext request,
      RequestPrincipal principal) {
    CollectionSchema collection = findCollectionOrNull(collectionIdOrName);
    if (collection == null
        || recordId == null
        || recordId.isBlank()
        || filename == null
        || filename.isBlank()) {
      return null;
    }
    Map<String, Object> record =
        records(collection).stream()
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
      requireProtectedFileAccess(collection, record, request, principal);
    }
    Path collectionDir = storageDir.resolve(collection.id).normalize();
    Path recordDir = collectionDir.resolve(recordId).normalize();
    Path file = recordDir.resolve(filename).normalize();
    if (!file.startsWith(recordDir)
        || Files.isSymbolicLink(storageDir)
        || Files.isSymbolicLink(collectionDir)
        || Files.isSymbolicLink(recordDir)
        || Files.isSymbolicLink(file)) {
      return null;
    }
    return file;
  }

  public synchronized boolean fileThumbAllowed(
      String collectionIdOrName, String recordId, String filename, String thumb) {
    if (thumb == null || thumb.isBlank()) {
      return false;
    }
    CollectionSchema collection = findCollectionOrNull(collectionIdOrName);
    if (collection == null
        || recordId == null
        || recordId.isBlank()
        || filename == null
        || filename.isBlank()) {
      return false;
    }
    Map<String, Object> record =
        records(collection).stream()
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
      String collectionName, JsonNode body, Map<String, String> query) {
    return authWithPassword(collectionName, body, query, AuthOriginContext.empty());
  }

  public synchronized Map<String, Object> authWithPassword(
      String collectionName, JsonNode body, Map<String, String> query, AuthOriginContext origin) {
    return authWithPasswordInternal(
        collectionName, body, RuleRequestContext.of(query, Map.of()), origin);
  }

  private Map<String, Object> authWithPasswordInternal(
      String collectionName, JsonNode body, RuleRequestContext request, AuthOriginContext origin) {
    request = request.withContext(RuleRequestContext.PASSWORD);
    Map<String, String> query = request.query();
    CollectionSchema collection = authCollection(collectionName);
    if (!collection.passwordAuth.enabled) {
      throw new ApiException(
          403, "The collection is not configured to allow password authentication.");
    }
    String identity = requiredText(body, "identity", "Failed to authenticate.");
    String identityField = bodyText(body, "identityField", "");
    String password = requiredText(body, "password", "Failed to authenticate.");
    String remoteIp = origin != null ? origin.ip() : null;
    AuthFailedAttemptTracker.checkLock(identity, remoteIp);

    String passwordField = passwordField(collection);
    Map<String, Object> record =
        records(collection).stream()
            .filter(item -> passwordIdentityMatches(collection, item, identity, identityField))
            .findFirst()
            .orElse(null);
    if (!PasswordHasher.verifyOrDummy(
        password, record == null ? null : String.valueOf(record.get(passwordField)))) {
      AuthFailedAttemptTracker.recordFailureAndThrow(identity, remoteIp);
    }
    if (record == null) {
      AuthFailedAttemptTracker.recordFailureAndThrow(identity, remoteIp);
    }

    String mfaId = bodyOrQueryText(body, query, "mfaId", null);
    Map<String, Object> res = handleAuthWithMfa(
        collection, record, request, mfaId, "password", Map.of(), origin, body, null);
    AuthFailedAttemptTracker.recordSuccess(identity, remoteIp);
    return res;
  }

  public synchronized Map<String, Object> authRefresh(
      String collectionName, RequestPrincipal principal) {
    return authRefresh(collectionName, principal, Map.of());
  }

  public synchronized Map<String, Object> authRefresh(
      String collectionName, RequestPrincipal principal, Map<String, String> query) {
    if (principal == null) {
      throw new ApiException(401, "Missing or invalid auth token.");
    }
    if ("impersonate".equals(principal.claims().get("tokenType"))) {
      throw new ApiException(401, "Impersonate auth tokens cannot be refreshed.");
    }
    CollectionSchema collection = authCollection(collectionName);
    if (!sameCollection(principal, collection)) {
      throw new ApiException(401, "Auth token does not belong to this collection.");
    }
    Map<String, Object> record =
        records(collection).stream()
            .filter(item -> Objects.equals(principal.id(), String.valueOf(item.get("id"))))
            .findFirst()
            .orElseThrow(() -> new ApiException(401, "Auth record no longer exists."));
    return authResponse(collection, record, query);
  }

  private Map<String, Object> handleAuthWithMfa(
      CollectionSchema collection,
      Map<String, Object> record,
      RuleRequestContext request,
      String mfaIdParam,
      String method,
      Map<String, Object> meta,
      AuthOriginContext origin,
      JsonNode body,
      RequestPrincipal requestPrincipal) {
    if (!collection.mfa.enabled) {
      return authenticatedResponse(
          collection, record, request, meta, origin, body, requestPrincipal);
    }
    if (mfaIdParam != null && !mfaIdParam.isBlank()) {
      Map<String, Object> mfa =
          mfas.stream()
              .filter(item -> Objects.equals(item.get("id"), mfaIdParam))
              .filter(item -> Objects.equals(item.get("recordRef"), record.get("id")))
              .filter(item -> Objects.equals(item.get("collectionRef"), collection.id))
              .findFirst()
              .orElse(null);
      if (mfa == null || mfaExpired(collection, mfa)) {
        if (mfa != null) {
          mfas.remove(mfa);
          saveMfas();
        }
        throw invalidMfaId("Missing or invalid MFA ID.");
      }
      if (Objects.equals(mfa.get("method"), method)) {
        throw invalidMfaId("MFA requires a different auth method.");
      }
      mfas.remove(mfa);
      saveMfas();
      return authenticatedResponse(
          collection, record, request, meta, origin, body, requestPrincipal);
    }
    boolean requireMfa =
        collection.mfa.rule == null
            || collection.mfa.rule.isBlank()
            || RuleEvaluator.matches(
                collection.mfa.rule,
                RecordFieldResolverSupport.context(
                    this, collection, record, null, request, "POST", null, true, false));
    if (!requireMfa) {
      return authenticatedResponse(
          collection, record, request, meta, origin, body, requestPrincipal);
    }
    String newMfaId = IdGenerator.id();
    Map<String, Object> mfaRecord = new LinkedHashMap<>();
    mfaRecord.put("id", newMfaId);
    String timestamp = now();
    mfaRecord.put("created", timestamp);
    mfaRecord.put("updated", timestamp);
    mfaRecord.put("collectionRef", collection.id);
    mfaRecord.put("recordRef", record.get("id"));
    mfaRecord.put("method", method);
    mfas.add(mfaRecord);
    saveMfas();
    throw new ApiException(401, "MFA required.", Map.of("mfaId", newMfaId));
  }

  private Map<String, Object> authenticatedResponse(
      CollectionSchema collection,
      Map<String, Object> record,
      RuleRequestContext request,
      Map<String, Object> meta,
      AuthOriginContext origin,
      JsonNode body,
      RequestPrincipal requestPrincipal) {
    requireAuthRule(collection, record, body, request, requestPrincipal);
    Map<String, Object> response =
        authResponse(
            collection,
            record,
            request,
            tokenDuration(collection.authToken, CollectionSchema.DEFAULT_AUTH_TOKEN_DURATION),
            "auth",
            meta);
    recordAuthOrigin(collection, record, origin);
    return response;
  }

  private void requireAuthRule(
      CollectionSchema collection,
      Map<String, Object> record,
      JsonNode body,
      RuleRequestContext request,
      RequestPrincipal requestPrincipal) {
    if (collection.authRule == null
        || !collection.authRule.isBlank()
            && !RuleEvaluator.matches(
                collection.authRule,
                RecordFieldResolverSupport.context(
                    this,
                    collection,
                    record,
                    jsonToMap(body),
                    request,
                    "POST",
                    requestPrincipal,
                    true,
                    false))) {
      throw new ApiException(
          403, "The request doesn't satisfy the collection requirements to authenticate.");
    }
  }

  private void recordAuthOrigin(
      CollectionSchema collection, Map<String, Object> record, AuthOriginContext origin) {
    if (collection == null
        || collection.authAlert == null
        || !collection.authAlert.enabled
        || record == null
        || origin == null
        || origin.ip().isBlank() && origin.userAgent().isBlank()) {
      return;
    }

    String recordId = String.valueOf(record.getOrDefault("id", ""));
    List<Map<String, Object>> origins =
        authOrigins.stream()
            .filter(item -> Objects.equals(collection.id, item.get("collectionRef")))
            .filter(
                item -> Objects.equals(recordId, String.valueOf(item.getOrDefault("recordRef", ""))))
            .sorted(
                Comparator.comparing(
                    (Map<String, Object> item) -> String.valueOf(item.getOrDefault("updated", "")))
                    .reversed())
            .collect(Collectors.toCollection(ArrayList::new));
    String fingerprint = origin.fingerprint();
    Map<String, Object> current =
        origins.stream()
            .filter(item -> Objects.equals(fingerprint, item.get("fingerprint")))
            .findFirst()
            .orElse(null);
    String timestamp = now();
    if (current != null) {
      current.put("updated", timestamp);
      saveAuthOrigins();
      return;
    }

    if (!origins.isEmpty() && !textSetting(record.get("email")).isBlank()) {
      Map<String, Object> request = new LinkedHashMap<>();
      request.put("id", IdGenerator.id());
      request.put("type", "authAlert");
      request.put("collectionId", collection.id);
      request.put("collectionName", collection.name);
      request.put("recordId", recordId);
      request.put("email", record.get("email"));
      request.put("alertInfo", origin.alertInfo());
      request.put("created", timestamp);
      request.put("expires", Instant.now().plus(Duration.ofHours(2)).toString());
      if (!AuthMailSupport.sendAsync(collection, record, request, settings, null)) {
        authRequests.add(request);
        pruneAuthRequests();
        saveAuthRequests();
      }
    }

    Map<String, Object> created = new LinkedHashMap<>();
    created.put("id", IdGenerator.id());
    created.put("collectionRef", collection.id);
    created.put("recordRef", recordId);
    created.put("fingerprint", fingerprint);
    created.put("created", timestamp);
    created.put("updated", timestamp);
    authOrigins.add(created);

    List<Map<String, Object>> currentOrigins =
        authOrigins.stream()
            .filter(item -> Objects.equals(collection.id, item.get("collectionRef")))
            .filter(
                item -> Objects.equals(recordId, String.valueOf(item.getOrDefault("recordRef", ""))))
            .sorted(
                Comparator.comparing(
                    (Map<String, Object> item) -> String.valueOf(item.getOrDefault("updated", "")))
                    .reversed())
            .toList();
    if (currentOrigins.size() > 5) {
      authOrigins.removeAll(currentOrigins.subList(5, currentOrigins.size()));
    }
    saveAuthOrigins();
  }

  private void clearAuthOrigins(String collectionId, String recordId) {
    boolean changed =
        authOrigins.removeIf(
            origin -> Objects.equals(collectionId, origin.get("collectionRef"))
                && (recordId == null
                    || Objects.equals(
                        recordId, String.valueOf(origin.getOrDefault("recordRef", "")))));
    if (changed) {
      saveAuthOrigins();
    }
  }

  private void clearAuthSecurityState(String collectionId, String recordId) {
    clearAuthOrigins(collectionId, recordId);
    boolean mfasChanged =
        mfas.removeIf(record -> systemRecordMatches(record, collectionId, recordId));
    if (mfasChanged) {
      saveMfas();
    }
    boolean otpsChanged =
        otps.removeIf(record -> systemRecordMatches(record, collectionId, recordId));
    if (otpsChanged) {
      saveOtps();
    }
  }

  private void clearAuthRecordDependents(String collectionId, String recordId) {
    clearAuthSecurityState(collectionId, recordId);
    boolean changed =
        externalAuths.removeIf(record -> systemRecordMatches(record, collectionId, recordId));
    if (changed) {
      saveExternalAuths();
    }
  }

  private boolean systemRecordMatches(
      Map<String, Object> record, String collectionId, String recordId) {
    return Objects.equals(collectionId, record.get("collectionRef"))
        && (recordId == null
            || Objects.equals(recordId, String.valueOf(record.getOrDefault("recordRef", ""))));
  }

  private ApiException invalidMfaId(String message) {
    return new ApiException(400, message, ApiErrors.invalidField("mfaId", message));
  }

  private boolean mfaExpired(CollectionSchema collection, Map<String, Object> mfa) {
    try {
      long durationSeconds = collection.mfa.duration > 0 ? collection.mfa.duration : 1800;
      Instant cutoff = Instant.now().minusSeconds(durationSeconds);
      return Instant.parse(String.valueOf(mfa.get("created"))).isBefore(cutoff);
    } catch (Exception e) {
      return true;
    }
  }

  private Map<String, Object> authResponse(
      CollectionSchema collection, Map<String, Object> record, Map<String, String> query) {
    return authResponse(
        collection,
        record,
        query,
        tokenDuration(collection.authToken, CollectionSchema.DEFAULT_AUTH_TOKEN_DURATION),
        "auth",
        Map.of());
  }

  private Map<String, Object> authResponse(
      CollectionSchema collection,
      Map<String, Object> record,
      Map<String, String> query,
      Duration ttl,
      String tokenType) {
    return authResponse(collection, record, query, ttl, tokenType, Map.of());
  }

  private Map<String, Object> authResponse(
      CollectionSchema collection,
      Map<String, Object> record,
      Map<String, String> query,
      Duration ttl,
      String tokenType,
      Map<String, Object> meta) {
    return authResponse(
        collection, record, RuleRequestContext.of(query, Map.of()), ttl, tokenType, meta);
  }

  private Map<String, Object> authResponse(
      CollectionSchema collection,
      Map<String, Object> record,
      RuleRequestContext request,
      Duration ttl,
      String tokenType,
      Map<String, Object> meta) {
    RuleRequestContext safeRequest = request == null ? RuleRequestContext.empty() : request;
    Map<String, String> safeQuery = safeRequest.query();
    ensureAuthTokenKey(collection, record);
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("sub", record.get("id"));
    claims.put("collectionId", collection.id);
    claims.put("collectionName", collection.name);
    claims.put("type", "auth");
    claims.put("email", record.get("email"));
    claims.put("tokenType", tokenType);
    claims.put("tokenKey", record.get("tokenKey"));

    RequestPrincipal authPrincipal = RequestPrincipal.fromClaims(claims);
    Map<String, String> recordQuery = new LinkedHashMap<>(safeQuery);
    recordQuery.remove("fields");
    RuleRequestContext recordRequest = RuleRequestContext.of(recordQuery, safeRequest.headers());
    Map<String, Object> response = new LinkedHashMap<>();
    Duration actualTtl =
        ttl == null
            ? tokenDuration(collection.authToken, CollectionSchema.DEFAULT_AUTH_TOKEN_DURATION)
            : ttl;
    response.put(
        "token",
        tokenService.create(
            claims, actualTtl, tokenSigningSecret(collection.authToken, record.get("tokenKey"))));
    response.put(
        "record",
        RecordProcessor.process(this, collection, record, false, recordRequest, authPrincipal));
    response.put("meta", meta == null ? Map.of() : meta);
    return selectFields(response, safeQuery.get("fields"));
  }

  private CollectionSchema authCollection(String collectionName) {
    CollectionSchema collection = findCollection(collectionName);
    if (!"auth".equals(collection.type)) {
      throw new ApiException(400, "The collection is not an auth collection.");
    }
    return collection;
  }

  private boolean isBearerToken(Map<String, Object> claims) {
    Object tokenType = claims.get("tokenType");
    return tokenType == null || "auth".equals(tokenType) || "impersonate".equals(tokenType);
  }

  private boolean authTokenClaimsValid(Map<String, Object> claims) {
    String type = String.valueOf(claims.getOrDefault("type", ""));
    if (!"auth".equals(type) && !"authRecord".equals(type) && !"superuser".equals(type)) {
      return false;
    }
    CollectionSchema collection =
        findCollectionOrNull(String.valueOf(claims.getOrDefault("collectionId", "")));
    if (collection == null) {
      collection = findCollectionOrNull(String.valueOf(claims.getOrDefault("collectionName", "")));
    }
    if (collection == null) {
      return false;
    }
    Map<String, Object> record =
        findRecordOrNull(collection, String.valueOf(claims.getOrDefault("sub", "")));
    if (record == null) {
      return false;
    }
    Object tokenKey = claims.get("tokenKey");
    return tokenKey == null
        || SecuritySupport.constantTimeEquals(
            String.valueOf(tokenKey), String.valueOf(record.get("tokenKey")));
  }

  private AuthAction verifyAuthAction(String collectionName, String token, String expectedType) {
    CollectionSchema collection = authCollection(collectionName);
    Map<String, Object> claims =
        tokenService
            .verify(
                token,
                tokenClaims -> tokenSigningSecret(
                    tokenConfigForAction(expectedType, collection),
                    tokenClaims.get("tokenKey")))
            .orElseThrow(this::invalidOrExpiredAuthActionToken);
    if (!expectedType.equals(claims.get("tokenType"))) {
      throw invalidOrExpiredAuthActionToken();
    }
    if (!Objects.equals(collection.id, claims.get("collectionId"))
        && !Objects.equals(collection.name, claims.get("collectionName"))) {
      throw invalidOrExpiredAuthActionToken();
    }
    Map<String, Object> record =
        findRecordOrNull(collection, String.valueOf(claims.getOrDefault("sub", "")));
    if (record == null
        || !SecuritySupport.constantTimeEquals(
            String.valueOf(record.get("tokenKey")), String.valueOf(claims.get("tokenKey")))) {
      throw invalidOrExpiredAuthActionToken();
    }
    return new AuthAction(collection, record, claims);
  }

  private ApiException invalidOrExpiredAuthActionToken() {
    return new ApiException(
        400,
        "Invalid or expired token.",
        ApiErrors.invalidField("token", "Invalid or expired token."));
  }

  private String appendAuthRequest(
      CollectionSchema collection,
      Map<String, Object> record,
      String tokenType,
      Map<String, Object> extraClaims,
      Duration ttl) {
    ensureAuthTokenKey(collection, record);
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("sub", record.get("id"));
    claims.put("collectionId", collection.id);
    claims.put("collectionName", collection.name);
    claims.put("type", "auth");
    claims.put("email", record.get("email"));
    claims.put("tokenType", tokenType);
    claims.put("tokenKey", record.get("tokenKey"));
    if (extraClaims != null) {
      claims.putAll(extraClaims);
    }
    String token =
        tokenService.create(
            claims,
            ttl == null ? Duration.ofHours(2) : ttl,
            tokenSigningSecret(
                tokenConfigForAction(tokenType, collection), record.get("tokenKey")));
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("id", IdGenerator.id());
    request.put("type", tokenType);
    request.put("collectionId", collection.id);
    request.put("collectionName", collection.name);
    request.put("recordId", record.get("id"));
    request.put("email", record.get("email"));
    if (extraClaims != null && extraClaims.containsKey("newEmail")) {
      request.put("newEmail", extraClaims.get("newEmail"));
    }
    request.put("token", token);
    request.put("created", now());
    request.put(
        "expires",
        DateTimeFormatter.ISO_INSTANT.format(
            Instant.now().plus(ttl == null ? Duration.ofHours(2) : ttl)));
    if (AuthMailSupport.sendAsync(collection, record, request, settings, null)) {
      return token;
    }
    authRequests.add(request);
    pruneAuthRequests();
    saveAuthRequests();
    return token;
  }

  private void ensureAuthTokenKey(CollectionSchema collection, Map<String, Object> record) {
    if (textSetting(record.get("tokenKey")).isBlank()) {
      record.put("tokenKey", IdGenerator.secret());
      saveRecords(collection);
    }
  }

  private void rotateAuthTokenKey(Map<String, Object> record) {
    record.put("tokenKey", IdGenerator.secret());
  }

  private Map<String, Object> findAuthRecordByEmail(CollectionSchema collection, String email) {
    return records(collection).stream()
        .filter(record -> email.equalsIgnoreCase(String.valueOf(record.getOrDefault("email", ""))))
        .findFirst()
        .orElse(null);
  }

  private boolean passwordIdentityMatches(
      CollectionSchema collection,
      Map<String, Object> record,
      String identity,
      String identityField) {
    List<String> fields = collection.passwordAuth.identityFields;
    if (identityField != null && !identityField.isBlank()) {
      if (!fields.contains(identityField)) {
        return false;
      }
      return authIdentityValueMatches(record, identityField, identity);
    }
    return fields.stream().anyMatch(field -> authIdentityValueMatches(record, field, identity));
  }

  private boolean authIdentityValueMatches(
      Map<String, Object> record, String field, String identity) {
    Object value = record.get(field);
    if (value == null) {
      return false;
    }
    if ("email".equals(field) || "username".equals(field)) {
      return identity.equalsIgnoreCase(String.valueOf(value));
    }
    return identity.equals(String.valueOf(value));
  }

  private Map<String, Object> findOAuth2LinkedRecord(
      CollectionSchema collection, String provider, String providerId) {
    return externalAuths.stream()
        .filter(item -> Objects.equals(collection.id, item.get("collectionRef")))
        .filter(item -> provider.equalsIgnoreCase(String.valueOf(item.get("provider"))))
        .filter(item -> Objects.equals(providerId, item.get("providerId")))
        .map(item -> findRecordOrNull(collection, String.valueOf(item.get("recordRef"))))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  private void upsertExternalAuth(
      CollectionSchema collection, Map<String, Object> record, String provider, String providerId) {
    Map<String, Object> existing =
        externalAuths.stream()
            .filter(item -> Objects.equals(collection.id, item.get("collectionRef")))
            .filter(item -> provider.equalsIgnoreCase(String.valueOf(item.get("provider"))))
            .filter(item -> Objects.equals(providerId, item.get("providerId")))
            .findFirst()
            .orElse(null);
    String timestamp = now();
    if (existing == null) {
      existing = new LinkedHashMap<>();
      existing.put("id", IdGenerator.id());
      existing.put("created", timestamp);
      externalAuths.add(existing);
    }
    existing.put("collectionRef", collection.id);
    existing.put("recordRef", record.get("id"));
    existing.put("provider", provider);
    existing.put("providerId", providerId);
    existing.put("updated", timestamp);
    saveExternalAuths();
  }

  private Map<String, Object> latestReusableOtp(
      CollectionSchema collection, Map<String, Object> record) {
    List<Map<String, Object>> active =
        otps.stream()
            .filter(otp -> Objects.equals(collection.id, otp.get("collectionRef")))
            .filter(otp -> Objects.equals(record.get("id"), otp.get("recordRef")))
            .filter(otp -> !otpExpired(otp))
            .sorted(
                (left, right) -> String.valueOf(right.getOrDefault("created", ""))
                    .compareTo(String.valueOf(left.getOrDefault("created", ""))))
            .collect(Collectors.toCollection(ArrayList::new));
    return active.size() > 9 ? active.get(0) : null;
  }

  private Map<String, Object> findOtp(String otpId) {
    pruneOtps();
    return otps.stream()
        .filter(otp -> Objects.equals(otpId, String.valueOf(otp.get("id"))))
        .findFirst()
        .orElseThrow(this::invalidOtp);
  }

  private synchronized void deleteOtp(String otpId) {
    boolean changed = otps.removeIf(otp -> Objects.equals(otpId, String.valueOf(otp.get("id"))));
    if (changed) {
      saveOtps();
    }
  }

  private void ensureOtpAttemptsAllowed(Map<String, Object> otp) {
    int attempts = intSetting(otp.get("failedAttempts"), 0);
    if (attempts < OTP_MAX_FAILED_ATTEMPTS) {
      return;
    }
    if (withinOtpAttemptWindow(otp.get("lastFailed"))) {
      throw new ApiException(
          429,
          "Too many failed OTP attempts.",
          ApiErrors.invalidField("otpId", "Too many failed OTP attempts."));
    }
    otp.put("failedAttempts", 0);
    otp.remove("lastFailed");
    saveOtps();
  }

  private void recordOtpFailure(Map<String, Object> otp) {
    int attempts =
        withinOtpAttemptWindow(otp.get("lastFailed"))
            ? intSetting(otp.get("failedAttempts"), 0) + 1
            : 1;
    otp.put("failedAttempts", attempts);
    otp.put("lastFailed", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
    saveOtps();
  }

  private boolean withinOtpAttemptWindow(Object lastFailed) {
    if (lastFailed == null) {
      return false;
    }
    try {
      return Instant.parse(String.valueOf(lastFailed))
          .plus(OTP_ATTEMPT_WINDOW)
          .isAfter(Instant.now());
    } catch (RuntimeException e) {
      return false;
    }
  }

  private boolean otpExpired(Map<String, Object> otp) {
    try {
      Object expires = otp.get("expires");
      if (expires != null && !String.valueOf(expires).isBlank()) {
        return Instant.parse(String.valueOf(expires)).isBefore(Instant.now());
      }
      CollectionSchema collection =
          findCollectionOrNull(String.valueOf(otp.getOrDefault("collectionRef", "")));
      long duration =
          collection != null && collection.otp != null && collection.otp.duration > 0
              ? collection.otp.duration
              : 180L;
      return Instant.parse(String.valueOf(otp.get("created")))
          .plusSeconds(duration)
          .isBefore(Instant.now());
    } catch (RuntimeException e) {
      return true;
    }
  }

  private ApiException invalidOtp() {
    return new ApiException(
        400, "Invalid or expired OTP.", ApiErrors.invalidField("otpId", "Invalid or expired OTP."));
  }

  private void ensureEmailAvailable(CollectionSchema collection, String email, String currentId) {
    Map<String, Object> existing = findAuthRecordByEmail(collection, email);
    if (existing != null && !Objects.equals(currentId, String.valueOf(existing.get("id")))) {
      throw new ApiException(
          400,
          "Email is already in use.",
          fieldError("email", "validation_invalid_value", "Value must be unique."));
    }
  }

  private String passwordField(CollectionSchema collection) {
    return collection.fields.stream()
        .filter(field -> "password".equals(normalizeType(field.type)))
        .map(field -> field.name)
        .findFirst()
        .orElse("password");
  }

  private String normalizedEmail(String value) {
    String email = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    if (email.isBlank() || !email.contains("@") || email.startsWith("@") || email.endsWith("@")) {
      throw new ApiException(
          400,
          "Invalid email address.",
          fieldError("email", "validation_invalid_value", "Invalid email address."));
    }
    return email;
  }

  private boolean sameCollection(RequestPrincipal principal, CollectionSchema collection) {
    return Objects.equals(principal.collectionId(), collection.id)
        || Objects.equals(principal.collectionName(), collection.name);
  }

  private long optionalLong(JsonNode body, String field, long fallback) {
    JsonNode value = body == null ? null : body.get(field);
    if (value == null || value.isNull()) {
      return fallback;
    }
    if (value.canConvertToLong()) {
      return value.asLong();
    }
    try {
      return Long.parseLong(value.asText());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private void touch(Map<String, Object> record) {
    record.put("updated", now());
  }

  private boolean truthyObject(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean bool) {
      return bool;
    }
    String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    return !normalized.isBlank()
        && !"0".equals(normalized)
        && !"false".equals(normalized)
        && !"no".equals(normalized);
  }

  private void pruneAuthRequests() {
    Instant now = Instant.now();
    authRequests.removeIf(
        request -> {
          try {
            return Instant.parse(String.valueOf(request.get("expires"))).isBefore(now);
          } catch (Exception ignored) {
            return false;
          }
        });
    int overflow = authRequests.size() - 1000;
    if (overflow > 0) {
      authRequests.subList(0, overflow).clear();
    }
  }

  private void pruneOtps() {
    otps.removeIf(this::otpExpired);
    int overflow = otps.size() - 1000;
    if (overflow > 0) {
      otps.subList(0, overflow).clear();
    }
  }

  private void load() throws IOException {
    collectionsByName.clear();
    recordsByCollectionId.clear();
    loadSettings();
    loadLogs();
    loadAuthRequests();
    loadAuthOrigins();
    loadExternalAuths();
    loadMfas();
    loadOtps();
    if (Files.exists(schemaFile)) {
      JsonNode root = mapper.readTree(schemaFile.toFile());
      JsonNode collectionNode = root.has("collections") ? root.get("collections") : root;
      for (CollectionSchema collection : mapper.convertValue(collectionNode, COLLECTION_LIST)) {
        normalizeCollection(collection, true);
        collectionsByName.put(collection.name, collection);
      }
    }
    migrateSystemCollectionIds();
    ensureSuperuserCollection();
    ensureAuthSystemCollections();
    pruneMfas();
    pruneOtps();
    for (CollectionSchema collection : collectionsByName.values()) {
      Path file = recordsFile(collection);
      if (Files.exists(file)) {
        List<Map<String, Object>> recordsList = new ArrayList<>();
        try (java.io.BufferedReader reader = Files.newBufferedReader(file)) {
          String line;
          while ((line = reader.readLine()) != null) {
            if (!line.trim().isEmpty()) {
              recordsList.add(mapper.readValue(line, STRING_OBJECT_MAP));
            }
          }
        } catch (Exception e) {
          try {
            recordsList = mapper.readValue(file.toFile(), RECORD_LIST);
          } catch (IOException ex) {
            throw new IllegalStateException("Failed to read records file: " + file, ex);
          }
        }
        recordsByCollectionId.put(collection.id, recordsList);
      } else {
        Path legacyFile = recordsDir.resolve(collection.id + ".json");
        if (Files.exists(legacyFile)) {
          try {
            recordsByCollectionId.put(
                collection.id, mapper.readValue(legacyFile.toFile(), RECORD_LIST));
          } catch (IOException e) {
            recordsByCollectionId.put(collection.id, new ArrayList<>());
          }
        } else {
          recordsByCollectionId.put(collection.id, new ArrayList<>());
        }
      }
      records(collection)
          .forEach(
              record -> {
                if ("auth".equals(collection.type)) {
                  if (textSetting(record.get("tokenKey")).isBlank()) {
                    record.put("tokenKey", IdGenerator.secret());
                  }
                  record.putIfAbsent("emailVisibility", false);
                  record.putIfAbsent("verified", SUPERUSERS.equals(collection.name));
                }
              });
    }
    saveAll();
  }

  private void loadSettings() throws IOException {
    settings.clear();
    settings.putAll(defaultSettings());
    if (Files.exists(settingsFile)) {
      deepMerge(settings, mapper.readValue(settingsFile.toFile(), STRING_OBJECT_MAP));
    }
    normalizeSettings();
  }

  private void loadLogs() throws IOException {
    logs.clear();
    if (Files.exists(logsFile)) {
      logs.addAll(mapper.readValue(logsFile.toFile(), RECORD_LIST));
    }
    pruneLogs();
  }

  private void loadAuthRequests() throws IOException {
    authRequests.clear();
    if (Files.exists(authRequestsFile)) {
      authRequests.addAll(mapper.readValue(authRequestsFile.toFile(), RECORD_LIST));
    }
    pruneAuthRequests();
  }

  private void loadAuthOrigins() throws IOException {
    authOrigins.clear();
    if (Files.exists(authOriginsFile)) {
      authOrigins.addAll(mapper.readValue(authOriginsFile.toFile(), RECORD_LIST));
    }
    authOrigins.forEach(
        record -> AuthSystemCollections.normalizeLegacyRecord(
            AuthSystemCollections.AUTH_ORIGINS, record));
  }

  private void loadExternalAuths() throws IOException {
    externalAuths.clear();
    if (Files.exists(externalAuthsFile)) {
      externalAuths.addAll(mapper.readValue(externalAuthsFile.toFile(), RECORD_LIST));
    }
    externalAuths.forEach(
        record -> AuthSystemCollections.normalizeLegacyRecord(
            AuthSystemCollections.EXTERNAL_AUTHS, record));
  }

  private void loadMfas() throws IOException {
    mfas.clear();
    if (Files.exists(mfasFile)) {
      mfas.addAll(mapper.readValue(mfasFile.toFile(), RECORD_LIST));
    }
    mfas.forEach(
        record -> AuthSystemCollections.normalizeLegacyRecord(AuthSystemCollections.MFAS, record));
  }

  private void loadOtps() throws IOException {
    otps.clear();
    if (Files.exists(otpsFile)) {
      otps.addAll(mapper.readValue(otpsFile.toFile(), RECORD_LIST));
    }
    otps.forEach(
        record -> AuthSystemCollections.normalizeLegacyRecord(AuthSystemCollections.OTPS, record));
  }

  private Map<String, Object> createSuperuser(JsonNode body) {
    CollectionSchema collection = findCollection(SUPERUSERS);
    Map<String, Object> record = createRecordInternal(collection, body, false, null);
    records(collection).add(record);
    saveRecords(collection);
    return publicRecord(collection, record, false);
  }

  private Map<String, Object> buildRecord(
      CollectionSchema collection, JsonNode body, Map<String, Object> existing) {
    return createRecordInternal(
        collection,
        body,
        existing != null,
        existing == null ? null : String.valueOf(existing.get("id")));
  }

  private Map<String, Object> createRecordInternal(
      CollectionSchema collection, JsonNode body, boolean update, String currentId) {
    if (body == null || !body.isObject()) {
      throw new ApiException(
          400,
          "Record payload must be a JSON object.",
          ApiErrors.invalidField("body", "Request body must be a JSON object."));
    }
    Map<String, Object> values = new LinkedHashMap<>();
    Map<String, Object> errors = new LinkedHashMap<>();

    for (FieldSchema field : collection.fields) {
      if (AuthCollectionFields.isManagedRecordField(field.name)
          || AuthSystemCollections.isManagedField(field.name)) {
        continue;
      }
      JsonNode value = body.get(field.name);
      if (value == null || value.isMissingNode()) {
        if (!update && field.required) {
          errors.put(
              field.name,
              ApiErrors.validationError("validation_required", ApiErrors.MESSAGE_CANNOT_BE_BLANK));
        }
        continue;
      }
      Object normalized =
          FieldValidator.normalizeFieldValue(
              mapper,
              field,
              value,
              update,
              errors,
              (col, id) -> {
                try {
                  getRecord(col, id, Map.of(), null);
                  return true;
                } catch (ApiException e) {
                  return false;
                }
              });
      if (normalized != FieldValidator.Unchanged.INSTANCE) {
        values.put(field.name, normalized);
      }
    }

    enforceUnique(collection, values, update ? currentId : null, errors);
    if (!errors.isEmpty()) {
      throw new ApiException(
          400, update ? "Failed to update record." : "Failed to create record.", errors);
    }

    if (!update) {
      String timestamp = now();
      values.put("id", body.hasNonNull("id") ? body.get("id").asText() : IdGenerator.id());
      values.put("collectionId", collection.id);
      values.put("collectionName", collection.name);
      values.put("created", timestamp);
      values.put("updated", timestamp);
      if ("auth".equals(collection.type)) {
        values.put("tokenKey", IdGenerator.secret());
        values.putIfAbsent("emailVisibility", false);
        values.putIfAbsent("verified", SUPERUSERS.equals(collection.name));
      }
    }
    return values;
  }

  private void enforceUnique(
      CollectionSchema collection,
      Map<String, Object> values,
      String currentId,
      Map<String, Object> errors) {
    for (FieldSchema field : collection.fields) {
      boolean unique =
          field.unique
              || collection.indexes.stream()
                  .anyMatch(
                      index -> CollectionIndexSupport.isSingleColumnUnique(index, field.name));
      if (!unique || !values.containsKey(field.name) || values.get(field.name) == null) {
        continue;
      }
      Object candidate = values.get(field.name);
      for (Map<String, Object> record : records(collection)) {
        if (currentId != null && Objects.equals(currentId, record.get("id"))) {
          continue;
        }
        if (Objects.equals(candidate, record.get(field.name))) {
          errors.put(
              field.name,
              ApiErrors.validationError(
                  "validation_not_unique", ApiErrors.MESSAGE_VALUE_MUST_BE_UNIQUE));
          break;
        }
      }
    }
  }

  private Map<String, Object> publicRecord(
      CollectionSchema collection, Map<String, Object> record, boolean includeHidden) {
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
      if ("tokenKey".equals(field.name)) {
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

  private boolean canViewExpandedRecord(
      CollectionSchema collection,
      Map<String, Object> record,
      Map<String, String> query,
      RequestPrincipal principal) {
    return canViewExpandedRecord(
        collection, record, RuleRequestContext.of(query, Map.of()), principal);
  }

  private boolean canViewExpandedRecord(
      CollectionSchema collection,
      Map<String, Object> record,
      RuleRequestContext request,
      RequestPrincipal principal) {
    if (isSuperuser(principal)) {
      return true;
    }
    return collection.viewRule != null
        && matchesRule(collection, collection.viewRule, record, null, request, "GET", principal);
  }

  private JsonNode bodyWithFileMarkers(
      CollectionSchema collection, JsonNode body, Map<String, List<UploadedFile>> files) {
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
      Map<String, Object> existing) {
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

  private void writeFileChanges(
      CollectionSchema collection, Map<String, Object> record, FileChanges changes) {
    if (changes.isEmpty()) {
      return;
    }
    String recordId = String.valueOf(record.get("id"));
    Path collectionDir = storageDir.resolve(collection.id).normalize();
    Path recordDir = collectionDir.resolve(recordId).normalize();
    try {
      if (!collectionDir.startsWith(storageDir.normalize())
          || Files.isSymbolicLink(storageDir)
          || Files.isSymbolicLink(collectionDir)
          || Files.isSymbolicLink(recordDir)) {
        throw new ApiException(
            400,
            "Invalid upload filename.",
            ApiErrors.invalidField("file", "Invalid upload filename."));
      }
      FilePermissionSupport.secureDirectory(storageDir);
      FilePermissionSupport.secureDirectory(collectionDir);
      FilePermissionSupport.secureDirectory(recordDir);
      for (Map.Entry<String, UploadedFile> entry : changes.writes().entrySet()) {
        Path target = recordDir.resolve(entry.getKey()).normalize();
        if (!target.startsWith(recordDir.normalize())) {
          throw new ApiException(
              400,
              "Invalid upload filename.",
              ApiErrors.invalidField("file", "Invalid upload filename."));
        }
        Files.write(target, entry.getValue().bytes(), StandardOpenOption.CREATE_NEW);
        FilePermissionSupport.secureFile(target);
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

  private List<FieldSchema> fileFieldsReferencing(
      CollectionSchema collection, Map<String, Object> record, String filename) {
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

  private void requireProtectedFileAccess(
      CollectionSchema collection,
      Map<String, Object> record,
      RuleRequestContext request,
      RequestPrincipal principal) {
    if (isSuperuser(principal)) {
      return;
    }
    if (collection.viewRule == null) {
      throw fileNotFound();
    }
    RuleRequestContext protectedRequest =
        (request == null ? RuleRequestContext.empty() : request)
            .withContext(RuleRequestContext.PROTECTED_FILE);
    if (!matchesRule(
        collection, collection.viewRule, record, null, protectedRequest, "GET", principal)) {
      throw fileNotFound();
    }
  }

  private ApiException fileNotFound() {
    return new ApiException(404, "The requested resource wasn't found.");
  }

  private void publishRealtime(
      CollectionSchema collection, String action, Map<String, Object> record) {
    RealtimeHub hub = realtimeHub;
    if (hub == null || record == null) {
      return;
    }
    String recordId = String.valueOf(record.get("id"));
    hub.publish(
        collection.name,
        collection.id,
        recordId,
        action,
        (subscription, principal) -> {
          if (!canReceiveRealtime(collection, record, subscription, principal)) {
            return null;
          }
          return RecordProcessor.process(
              this,
              collection,
              record,
              isSuperuser(principal),
              RuleRequestContext.of(
                  subscription.query(), subscription.headers(), RuleRequestContext.REALTIME),
              principal);
        });
  }

  private boolean canReceiveRealtime(
      CollectionSchema collection,
      Map<String, Object> record,
      RealtimeHub.Subscription subscription,
      RequestPrincipal principal) {
    if (isSuperuser(principal)) {
      return matchesRealtimeFilter(collection, subscription, record, principal);
    }
    String rule = subscription.wildcard() ? collection.listRule : collection.viewRule;
    if (rule == null) {
      return false;
    }
    RuleRequestContext request =
        RuleRequestContext.of(
            subscription.query(), subscription.headers(), RuleRequestContext.REALTIME);
    return matchesRule(collection, rule, record, null, request, "GET", principal)
        && matchesRealtimeFilter(collection, subscription, record, principal);
  }

  private boolean matchesRealtimeFilter(
      CollectionSchema collection,
      RealtimeHub.Subscription subscription,
      Map<String, Object> record,
      RequestPrincipal principal) {
    String filter = subscription.filter();
    return filter == null
        || filter.isBlank()
        || RuleEvaluator.matches(
            filter,
            RecordFieldResolverSupport.context(
                this,
                collection,
                record,
                null,
                RuleRequestContext.of(
                    subscription.query(), subscription.headers(), RuleRequestContext.REALTIME),
                "GET",
                principal,
                isSuperuser(principal),
                !isSuperuser(principal)));
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
      value.forEach(
          item -> {
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
      String message =
          "File `" + file.originalFilename() + "` exceeds maxSize for field `" + field.name + "`.";
      throw new ApiException(400, message, ApiErrors.invalidField(field.name, message));
    }
    List<String> mimeTypes = mimeTypes(field);
    if (!mimeTypes.isEmpty()
        && mimeTypes.stream().noneMatch(pattern -> matchesMimeType(pattern, file.contentType()))) {
      String message =
          "File `"
              + file.originalFilename()
              + "` MIME type is not allowed for field `"
              + field.name
              + "`.";
      throw new ApiException(400, message, ApiErrors.invalidField(field.name, message));
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
      value.forEach(
          item -> {
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
      value.forEach(
          item -> {
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
    String actual =
        contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
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
    String file =
        original == null || original.isBlank()
            ? "file"
            : Path.of(original).getFileName().toString();
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
      RuleRequestContext request,
      String method,
      RequestPrincipal principal,
      String action) {
    if (isSuperuser(principal)) {
      return;
    }
    if (rule == null) {
      throw new ApiException(403, "Only superusers can " + action + " this record.");
    }
    if (!matchesRule(collection, rule, record, body, request, method, principal)) {
      throw new ApiException(404, "Record not found.");
    }
  }

  private boolean matchesRule(
      CollectionSchema collection,
      String rule,
      Map<String, Object> record,
      Map<String, Object> body,
      RuleRequestContext request,
      String method,
      RequestPrincipal principal) {
    return RuleEvaluator.matches(
        rule,
        RecordFieldResolverSupport.context(
            this, collection, record, body, request, method, principal, true, false));
  }

  private boolean isSuperuser(RequestPrincipal principal) {
    return principal != null && principal.superuser();
  }

  private void requireSuperuserRecordsAccess(
      CollectionSchema collection, RequestPrincipal principal) {
    if (SUPERUSERS.equals(collection.name) && !isSuperuser(principal)) {
      throw new ApiException(403, "Only superusers can access this collection.");
    }
  }

  private Map<String, Object> jsonToMap(JsonNode node) {
    if (node == null || !node.isObject()) {
      return Map.of();
    }
    return mapper.convertValue(node, new TypeReference<>() {
    });
  }

  private Map<String, Object> collectionMap(CollectionSchema collection) {
    return CollectionResponseSupport.redactSecrets(collectionPersistenceMap(collection));
  }

  private Map<String, Object> collectionPersistenceMap(CollectionSchema collection) {
    Map<String, Object> result =
        mapper.convertValue(copyCollection(collection), new TypeReference<>() {
        });
    if ("auth".equals(collection.type)) {
      result.put("authRule", nullableJsonValue(collection.authRule));
      result.put("manageRule", nullableJsonValue(collection.manageRule));
    }
    return result;
  }

  private Object nullableJsonValue(Object value) {
    return value == null ? NullNode.instance : value;
  }

  private Map<String, Object> copyMap(Map<String, Object> source) {
    return mapper.convertValue(source, STRING_OBJECT_MAP);
  }

  private boolean matchesLogFilter(Map<String, Object> log, String filter) {
    if (filter == null || filter.isBlank()) {
      return true;
    }
    return RuleEvaluator.matches(filter, ruleContext(log, null, Map.of(), "GET", null));
  }

  private String logHour(Object created) {
    if (created == null) {
      return "";
    }
    try {
      return LOG_STATS_HOUR_FORMAT.format(
          Instant.parse(String.valueOf(created)).truncatedTo(ChronoUnit.HOURS));
    } catch (Exception ignored) {
      return String.valueOf(created);
    }
  }

  private Map<String, Object> redactedSettings() {
    Map<String, Object> copy = copyMap(settings);
    hideSensitiveSettings(copy);
    return copy;
  }

  private Map<String, Object> withoutInternalLogFields(Map<String, Object> source) {
    Map<String, Object> copy = copyMap(source);
    copy.remove(INTERNAL_ROWID);
    return copy;
  }

  private Map<String, Object> s3SettingsFor(String filesystem, JsonNode body) {
    String target = filesystem == null || filesystem.isBlank() ? "storage" : filesystem;
    Map<String, Object> base;
    if ("backups".equals(target)) {
      Map<String, Object> backups = copyMap(settingsSection("backups"));
      Object nested = backups.get("s3");
      base =
          nested instanceof Map<?, ?> map
              ? mapper.convertValue(map, STRING_OBJECT_MAP)
              : new LinkedHashMap<>();
      JsonNode backupsOverride = body == null ? null : body.get("backups");
      if (backupsOverride != null
          && backupsOverride.isObject()
          && backupsOverride.get("s3") != null) {
        deepMerge(base, mapper.convertValue(backupsOverride.get("s3"), STRING_OBJECT_MAP));
      }
    } else {
      base = copyMap(settingsSection("s3"));
    }
    JsonNode directOverride = body == null ? null : body.get("s3");
    if (directOverride != null && directOverride.isObject()) {
      deepMerge(base, mapper.convertValue(directOverride, STRING_OBJECT_MAP));
    }
    return base;
  }

  private Map<String, Object> mergedSettingsSection(String section, JsonNode body) {
    Map<String, Object> merged = copyMap(settingsSection(section));
    JsonNode override = body == null ? null : body.get(section);
    if (override != null && override.isObject()) {
      deepMerge(merged, mapper.convertValue(override, STRING_OBJECT_MAP));
    }
    return merged;
  }

  private SmtpMailer.Settings smtpSettings(Map<String, Object> smtp) {
    int port = intSetting(smtp.get("port"), 587);
    String host = textSetting(smtp.get("host"));
    if (host.isBlank()) {
      throw new ApiException(
          400, "Failed to send the test email.", ApiErrors.requiredField("host"));
    }
    return new SmtpMailer.Settings(
        host,
        Math.max(1, Math.min(65535, port)),
        textSetting(smtp.get("username")),
        textSetting(smtp.get("password")),
        textSetting(smtp.get("authMethod")).isBlank()
            ? "PLAIN"
            : textSetting(smtp.get("authMethod")),
        truthySetting(smtp.get("tls"), false),
        textSetting(smtp.get("localName")));
  }

  private String senderAddress() {
    String senderAddress = textSetting(settingsSection("meta").get("senderAddress"));
    return senderAddress.isBlank() ? "noreply@example.com" : senderAddress;
  }

  private void queueOtpMessage(
      CollectionSchema collection,
      Map<String, Object> record,
      String otpId,
      String email,
      String code) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("id", IdGenerator.id());
    request.put("type", "otp");
    request.put("collectionId", collection.id);
    request.put("collectionName", collection.name);
    request.put("recordId", record.get("id"));
    request.put("email", email);
    request.put("otpId", otpId);
    request.put("password", code);
    request.put("created", now());
    request.put(
        "expires",
        DateTimeFormatter.ISO_INSTANT.format(
            Instant.now().plus(Duration.ofSeconds(collection.otp.duration))));
    if (AuthMailSupport.sendAsync(collection, record, request, settings, () -> deleteOtp(otpId))) {
      return;
    }
    authRequests.add(request);
    pruneAuthRequests();
    saveAuthRequests();
  }

  private TestEmailContent testEmailContent(String template, Map<String, Object> settings) {
    String appName = textSetting(settingsSection("meta").get("appName"));
    if (appName.isBlank()) {
      appName = "PocketBase Java";
    }
    String actionUrl = textSetting(settingsSection("meta").get("appURL"));

    return switch (template) {
      case "verification" ->
        new TestEmailContent(
            "Verify your email",
            "Verify your email address for this " + appName + " instance.\n\n" + actionUrl,
            "<p>Verify your email address for this "
                + appName
                + " instance.</p><p><a href=\""
                + actionUrl
                + "\">Verify</a></p>");
      case "password-reset" ->
        new TestEmailContent(
            "Reset password",
            "Reset password request for this " + appName + " instance.\n\n" + actionUrl,
            "<p>Reset password request for this "
                + appName
                + " instance.</p><p><a href=\""
                + actionUrl
                + "\">Reset</a></p>");
      case "email-change" ->
        new TestEmailContent(
            "Confirm new email",
            "Confirm new email request for this " + appName + " instance.\n\n" + actionUrl,
            "<p>Confirm new email request for this "
                + appName
                + " instance.</p><p><a href=\""
                + actionUrl
                + "\">Confirm</a></p>");
      case "otp" ->
        new TestEmailContent(
            "Your one-time password",
            "Your test one-time password is 123456.",
            "<p>Your test one-time password is <strong>123456</strong>.</p>");
      case "login-alert" ->
        new TestEmailContent(
            "New login alert",
            "This is a test login alert from a new location.",
            "<p>This is a test login alert from a new location.</p>");
      default ->
        throw new ApiException(
            400,
            "Failed to send the test email.",
            fieldError("template", "validation_invalid_value", "Invalid email template."));
    };
  }

  private void hideSensitiveSettings(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> target = Unsafe.stringObjectMap(map);
      for (Map.Entry<String, Object> entry : new ArrayList<>(target.entrySet())) {
        Object child = entry.getValue();
        if (hiddenSettingKey(entry.getKey())) {
          target.remove(entry.getKey());
        } else {
          Unsafe.hideSensitiveSettings(child, this::hiddenSettingKey);
        }
      }
      return;
    }
    if (value instanceof List<?> list) {
      list.forEach(item -> Unsafe.hideSensitiveSettings(item, this::hiddenSettingKey));
    }
  }

  private void deepMerge(Map<String, Object> target, Map<String, Object> source) {
    if (source == null || source.isEmpty()) {
      return;
    }
    source.forEach(
        (rawKey, value) -> {
          String key = normalizeSettingKey(rawKey);
          if (REDACTED_SECRET.equals(value) && target.containsKey(key)) {
            return;
          }
          Object existing = target.get(key);
          if (existing instanceof Map<?, ?> existingMap && value instanceof Map<?, ?> sourceMap) {
            deepMerge(Unsafe.stringObjectMap(existingMap), Unsafe.stringObjectMap(sourceMap));
          } else {
            target.put(key, mapper.convertValue(value, Object.class));
          }
        });
  }

  private void applySettingDefaults(Map<String, Object> target, Map<String, Object> defaults) {
    defaults.forEach(
        (key, defaultValue) -> {
          Object existing = target.get(key);
          if (existing == null) {
            target.put(key, mapper.convertValue(defaultValue, Object.class));
          } else if (existing instanceof Map<?, ?> existingMap
              && defaultValue instanceof Map<?, ?> defaultMap) {
            applySettingDefaults(
                Unsafe.stringObjectMap(existingMap), Unsafe.stringObjectMap(defaultMap));
          }
        });
  }

  private void normalizeSettings() {
    if (settings.containsKey("appUrl") && !settings.containsKey("appURL")) {
      settings.put("appURL", settings.remove("appUrl"));
    }
    applySettingDefaults(settings, defaultSettings());
    Map<String, Object> meta = settingsSection("meta");
    if (meta.containsKey("appUrl") && !meta.containsKey("appURL")) {
      meta.put("appURL", meta.remove("appUrl"));
    }
    if (textSetting(meta.get("appName")).isBlank()) {
      meta.put("appName", "pocketbase-java");
    }
    if (textSetting(meta.get("appURL")).isBlank()) {
      meta.put("appURL", "http://127.0.0.1:8090");
    }
    normalizeLong(settingsSection("logs"), "maxDataSize", 0L, 0L, 9_007_199_254_740_991L);
    normalizeLong(settingsSection("logs"), "maxDays", 5L, 0L, 9_007_199_254_740_991L);
    normalizeLong(settingsSection("logs"), "minLevel", 0L, -9_007_199_254_740_991L, 9_007_199_254_740_991L);
    Map<String, Object> logSettings = settingsSection("logs");
    if (logSettings.containsKey("logIp") && !logSettings.containsKey("logIP")) {
      logSettings.put("logIP", logSettings.remove("logIp"));
    }
    normalizeBool(logSettings, "logIP", true);
    normalizeBool(settingsSection("logs"), "logAuthId", false);
    normalizeInt(settingsSection("batch"), "maxRequests", 50, 1, 500);
    normalizeInt(settingsSection("batch"), "timeout", 3, 1, 3600);
    normalizeInt(settingsSection("batch"), "maxBodySize", 33_554_432, 1, Integer.MAX_VALUE);
  }

  private Map<String, Object> settingsSection(String name) {
    Object value = settings.get(name);
    if (value instanceof Map<?, ?> map) {
      return Unsafe.stringObjectMap(map);
    }
    Map<String, Object> created = new LinkedHashMap<>();
    settings.put(name, created);
    return created;
  }

  private Map<String, Object> defaultSettings() {
    Map<String, Object> root =
        orderedMap(
            "meta",
            orderedMap(
                "accentColor", "#1055c9",
                "appName", "pocketbase-java",
                "appURL", "http://127.0.0.1:8090",
                "senderName", "PocketBase Java",
                "senderAddress", "noreply@example.com",
                "hideControls", false),
            "logs", orderedMap("maxDays", 5, "minLevel", 0, "logIP", true, "logAuthId", false, "maxDataSize", 0),
            "smtp",
            orderedMap(
                "enabled", false,
                "host", "",
                "port", 587,
                "username", "",
                "password", "",
                "authMethod", "PLAIN",
                "tls", false,
                "localName", ""),
            "s3",
            orderedMap(
                "enabled", false,
                "bucket", "",
                "region", "",
                "endpoint", "",
                "accessKey", "",
                "secret", "",
                "forcePathStyle", false),
            "backups",
            orderedMap(
                "cron",
                "",
                "cronMaxKeep",
                3,
                "s3",
                orderedMap(
                    "enabled", false,
                    "bucket", "",
                    "region", "",
                    "endpoint", "",
                    "accessKey", "",
                    "secret", "",
                    "forcePathStyle", false)),
            "rateLimits",
            orderedMap(
                "enabled", false,
                "rules",
                List.of(
                    orderedMap(
                        "label", "*:auth", "audience", "", "duration", 3, "maxRequests", 2),
                    orderedMap(
                        "label",
                        "*:create",
                        "audience",
                        "",
                        "duration",
                        5,
                        "maxRequests",
                        20),
                    orderedMap(
                        "label",
                        "/api/batch",
                        "audience",
                        "",
                        "duration",
                        1,
                        "maxRequests",
                        3),
                    orderedMap(
                        "label",
                        "/api/",
                        "audience",
                        "",
                        "duration",
                        10,
                        "maxRequests",
                        300)),
                "excludedIPs", List.of()),
            "trustedProxy", orderedMap("headers", List.of(), "useLeftmostIP", false),
            "batch",
            orderedMap(
                "enabled", true,
                "maxRequests", 50,
                "timeout", 3,
                "maxBodySize", 33_554_432),
            "superuserIPs", List.of());
    return root;
  }

  private Map<String, Object> orderedMap(Object... entries) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i + 1 < entries.length; i += 2) {
      out.put(String.valueOf(entries[i]), entries[i + 1]);
    }
    return out;
  }

  private String normalizeSettingKey(String key) {
    if ("appUrl".equals(key)) {
      return "appURL";
    }
    if ("logIp".equals(key)) {
      return "logIP";
    }
    return key;
  }

  private boolean hiddenSettingKey(String key) {
    String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
    return "password".equals(normalized)
        || "secret".equals(normalized)
        || "privatekey".equals(normalized);
  }

  private void normalizeLong(
      Map<String, Object> section, String key, long fallback, long min, long max) {
    long value = longSetting(section.get(key), fallback);
    section.put(key, Math.max(min, Math.min(max, value)));
  }

  private long longSetting(Object value, long fallback) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text && !text.isBlank()) {
      try {
        return Long.parseLong(text);
      } catch (NumberFormatException ignored) {
        return fallback;
      }
    }
    return fallback;
  }

  private void normalizeInt(
      Map<String, Object> section, String key, int fallback, int min, int max) {
    int value = intSetting(section.get(key), fallback);
    section.put(key, Math.max(min, Math.min(max, value)));
  }

  private void normalizeBool(Map<String, Object> section, String key, boolean fallback) {
    section.put(key, truthySetting(section.get(key), fallback));
  }

  private int intSetting(Object value, int fallback) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String text && !text.isBlank()) {
      try {
        return Integer.parseInt(text);
      } catch (NumberFormatException ignored) {
        return fallback;
      }
    }
    return fallback;
  }

  private boolean truthySetting(Object value, boolean fallback) {
    if (value == null) {
      return fallback;
    }
    if (value instanceof Boolean bool) {
      return bool;
    }
    String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    if (normalized.isBlank()) {
      return fallback;
    }
    return !"0".equals(normalized) && !"false".equals(normalized) && !"no".equals(normalized);
  }

  private String textSetting(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private void pruneLogs() {
    Map<String, Object> logSettings = settingsSection("logs");
    long maxDays = longSetting(logSettings.get("maxDays"), 5L);
    long minLevel = longSetting(logSettings.get("minLevel"), 0L);
    Instant cutoff = maxDays <= 0
        ? Instant.now()
        : (maxDays >= 365_000L ? Instant.EPOCH : Instant.now().minus(maxDays, ChronoUnit.DAYS));
    logs.removeIf(
        log -> {
          if (maxDays <= 0) {
            return true;
          }
          if (longSetting(log.get("level"), 0L) < minLevel) {
            return true;
          }
          try {
            return Instant.parse(String.valueOf(log.get("created"))).isBefore(cutoff);
          } catch (Exception ignored) {
            return false;
          }
        });
    int overflow = logs.size() - MAX_ACTIVITY_LOGS;
    if (overflow > 0) {
      logs.subList(0, overflow).clear();
    }
  }

  @Override
  public synchronized void truncateLogs() {
    logs.clear();
    saveLogs();
  }

  private void pruneMfas() {
    mfas.removeIf(
        mfa -> {
          try {
            CollectionSchema collection =
                collectionsByName.values().stream()
                    .filter(c -> Objects.equals(c.id, mfa.get("collectionRef")))
                    .findFirst()
                    .orElse(null);
            if (collection == null) {
              return true;
            }
            long durationSeconds = collection.mfa.duration > 0 ? collection.mfa.duration : 1800;
            Instant cutoff = Instant.now().minus(Duration.ofSeconds(durationSeconds));
            return Instant.parse(String.valueOf(mfa.get("created"))).isBefore(cutoff);
          } catch (Exception ignored) {
            return false;
          }
        });
  }

  private List<CronJob> cronJobs() {
    List<CronJob> jobs = new ArrayList<>();
    jobs.add(new CronJob("__pbLogsCleanup__", "0 */6 * * *"));
    jobs.add(new CronJob("__pbDBOptimize__", "0 0 * * *"));
    jobs.add(new CronJob("__pbMFACleanup__", "0 * * * *"));
    jobs.add(new CronJob("__pbOTPCleanup__", "0 * * * *"));
    String backupCron = textSetting(settingsSection("backups").get("cron"));
    if (!backupCron.isBlank()) {
      jobs.add(new CronJob(AUTO_BACKUP_JOB_ID, backupCron));
    }
    return jobs;
  }

  private synchronized void runCronJob(String id) {
    switch (id) {
      case "__pbLogsCleanup__" -> {
        pruneLogs();
        saveLogs();
      }
      case "__pbDBOptimize__", "__pbOTPCleanup__" -> {
        if ("__pbOTPCleanup__".equals(id)) {
          pruneOtps();
          saveOtps();
        }
      }
      case "__pbMFACleanup__" -> {
        pruneMfas();
        saveMfas();
      }
      case AUTO_BACKUP_JOB_ID -> runAutoBackupCron();
      default -> throw new ApiException(404, "Missing or invalid cron job");
    }
  }

  private void runAutoBackupCron() {
    String backupCron = textSetting(settingsSection("backups").get("cron"));
    if (backupCron.isBlank()) {
      return;
    }
    String name = AUTO_BACKUP_PREFIX + BACKUP_TIMESTAMP.format(Instant.now()) + ".zip";
    createBackup(mapper.createObjectNode().put("name", name));
    pruneAutoBackups();
  }

  private void pruneAutoBackups() {
    int maxKeep = intSetting(settingsSection("backups").get("cronMaxKeep"), 3);
    if (maxKeep <= 0) {
      return;
    }
    try {
      Files.createDirectories(backupsDir);
      List<Path> autoBackups;
      try (Stream<Path> paths = Files.list(backupsDir)) {
        autoBackups =
            paths
                .filter(
                    path -> !Files.isSymbolicLink(path)
                        && Files.isRegularFile(path)
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
                .collect(Collectors.toCollection(ArrayList::new));
      }
      for (int i = maxKeep; i < autoBackups.size(); i++) {
        Files.deleteIfExists(autoBackups.get(i));
      }
    } catch (IOException e) {
      throw new IllegalStateException("failed to prune auto backups", e);
    }
  }

  private boolean matchesCollectionFilter(Map<String, Object> collection, String filter) {
    if (filter == null || filter.isBlank()) {
      return true;
    }
    return RuleEvaluator.matches(filter, ruleContext(collection, null, Map.of(), "GET", null));
  }

  private CollectionSchema existingCollectionForImport(CollectionSchema imported) {
    if (imported == null) {
      return null;
    }
    CollectionSchema byId = findCollectionOrNull(imported.id);
    if (byId != null) {
      return byId;
    }
    return findCollectionOrNull(imported.name);
  }

  private void putImportedCollection(
      Map<String, CollectionSchema> target, CollectionSchema imported, CollectionSchema existing) {
    CollectionSchema nameConflict = target.get(imported.name);
    if (nameConflict != null && !Objects.equals(nameConflict.id, imported.id)) {
      throw new ApiException(
          400,
          "Failed to import collections.",
          fieldError(
              "collections",
              "validation_invalid_value",
              "Collection name already exists: " + imported.name));
    }
    String oldName = null;
    for (Map.Entry<String, CollectionSchema> entry : new ArrayList<>(target.entrySet())) {
      CollectionSchema candidate = entry.getValue();
      if (Objects.equals(candidate.id, imported.id)) {
        oldName = entry.getKey();
        break;
      }
    }
    if (oldName != null && !Objects.equals(oldName, imported.name)) {
      target.remove(oldName);
    }
    if (existing != null && !Objects.equals(existing.name, imported.name)) {
      target.remove(existing.name);
    }
    target.put(imported.name, copyCollection(imported));
  }

  private void cleanupRemovedCollectionData(Set<String> existingIds) {
    boolean originsChanged =
        authOrigins.removeIf(
            record -> !existingIds.contains(String.valueOf(record.get("collectionRef"))));
    boolean externalChanged =
        externalAuths.removeIf(
            record -> !existingIds.contains(String.valueOf(record.get("collectionRef"))));
    boolean mfasChanged =
        mfas.removeIf(record -> !existingIds.contains(String.valueOf(record.get("collectionRef"))));
    boolean otpsChanged =
        otps.removeIf(record -> !existingIds.contains(String.valueOf(record.get("collectionRef"))));
    if (originsChanged)
      saveAuthOrigins();
    if (externalChanged)
      saveExternalAuths();
    if (mfasChanged)
      saveMfas();
    if (otpsChanged)
      saveOtps();
    try {
      Files.createDirectories(recordsDir);
      try (Stream<Path> paths = Files.list(recordsDir)) {
        for (Path path : paths.collect(Collectors.toList())) {
          if (!Files.isRegularFile(path)) {
            continue;
          }
          String filename = path.getFileName().toString();
          if (!filename.endsWith(".json")) {
            continue;
          }
          String collectionId = filename.substring(0, filename.length() - ".json".length());
          if (!existingIds.contains(collectionId)) {
            Files.deleteIfExists(path);
          }
        }
      }
      Files.createDirectories(storageDir);
      try (Stream<Path> paths = Files.list(storageDir)) {
        for (Path path : paths.collect(Collectors.toList())) {
          if (Files.isDirectory(path) && !existingIds.contains(path.getFileName().toString())) {
            deleteRecursively(path);
          }
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("failed to cleanup removed collections", e);
    }
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

  private void copySelectedField(
      Object source, Map<String, Object> target, String[] path, int index) {
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
      Map<String, Object> child =
          target.get(key) instanceof Map<?, ?> existing
              ? Unsafe.stringObjectMap(existing)
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

  private String viewQueryFromBody(JsonNode body, String fallback) {
    if (body != null && body.has("viewQuery")) {
      return body.path("viewQuery").asText("");
    }
    JsonNode options = body == null ? null : body.get("options");
    if (options != null && options.isObject()) {
      if (options.has("viewQuery")) {
        return options.path("viewQuery").asText("");
      }
      if (options.has("query")) {
        return options.path("query").asText("");
      }
    }
    return fallback;
  }

  private void prepareViewCollection(CollectionSchema collection, String message) {
    collection.fields = new ArrayList<>();
    if (collection.viewQuery == null || collection.viewQuery.isBlank()) {
      throw new ApiException(400, message, ApiErrors.requiredField("viewQuery"));
    }
    try {
      Map<String, Object> preview = previewViewQuery(collection.viewQuery, 10);
      collection.fields = mapper.convertValue(preview.get("fields"), new TypeReference<>() {
      });
      for (FieldSchema field : collection.fields) {
        field.type =
            normalizeType(field.type == null || field.type.isBlank() ? "text" : field.type);
      }
      SchemaIdSupport.assignMissingFieldIds(collection.fields, List.of());
    } catch (RuntimeException e) {
      SecuritySupport.logInternalFailure("prepare JSONL view collection", e);
      throw new ApiException(
          400,
          message,
          ApiErrors.fieldError(
              "viewQuery",
              "validation_invalid_view_query",
              "Invalid query."));
    }
  }

  private void normalizeCollection(CollectionSchema collection, boolean existing) {
    collection.type =
        normalizeType(
            collection.type == null || collection.type.isBlank() ? "base" : collection.type);
    if (collection.id == null || collection.id.isBlank()) {
      collection.id =
          SchemaIdSupport.nextCollectionId(
              collection.type,
              collection.name,
              candidate -> findCollectionOrNull(candidate) != null);
    }
    if (collection.fields == null) {
      collection.fields = new ArrayList<>();
    }
    if ("base".equals(collection.type)) {
      SchemaIdSupport.ensureBaseIdField(collection.fields);
    } else if ("auth".equals(collection.type)) {
      AuthCollectionFields.normalize(collection);
      normalizeAuthConfig(collection);
    }
    for (FieldSchema field : collection.fields) {
      normalizeField(field);
    }
    SchemaIdSupport.assignMissingFieldIds(collection.fields, List.of());
    String timestamp = now();
    if (!existing || collection.created == null || collection.created.isBlank()) {
      collection.created = timestamp;
    }
    if (collection.updated == null || collection.updated.isBlank()) {
      collection.updated = timestamp;
    }
  }

  private void normalizeField(FieldSchema field) {
    if (field == null) {
      return;
    }
    field.type = normalizeType(field.type == null || field.type.isBlank() ? "text" : field.type);
    if (field.options == null) {
      field.options = new LinkedHashMap<>();
    }
  }

  private boolean jsonTableExists(String name) {
    return name != null
        && INTERNAL_TABLE_NAMES.stream().anyMatch(table -> table.equalsIgnoreCase(name));
  }

  private CollectionSchema scaffoldCollection(String type) {
    CollectionSchema collection = new CollectionSchema();
    collection.id = "";
    collection.name = "";
    collection.type = type;
    collection.system = false;
    collection.fields = new ArrayList<>();
    if ("base".equals(type)) {
      SchemaIdSupport.ensureBaseIdField(collection.fields);
    } else if ("auth".equals(type)) {
      AuthCollectionFields.normalize(collection);
    }
    return collection;
  }

  private void normalizeAuthConfig(CollectionSchema collection) {
    if (collection.passwordAuth == null) {
      collection.passwordAuth = new CollectionSchema.PasswordAuthConfig();
    }
    if (collection.passwordAuth.identityFields == null) {
      collection.passwordAuth.identityFields = new ArrayList<>(List.of("email"));
    } else {
      LinkedHashSet<String> identities = new LinkedHashSet<>();
      for (String field : collection.passwordAuth.identityFields) {
        String value = field == null ? "" : field.trim();
        if (!value.isBlank()) {
          identities.add(value);
        }
      }
      collection.passwordAuth.identityFields = new ArrayList<>(identities);
    }

    if (collection.otp == null) {
      collection.otp = new CollectionSchema.OtpConfig();
    }
    collection.otp.emailTemplate =
        normalizeEmailTemplate(collection.otp.emailTemplate, CollectionSchema.EmailTemplate.otp());

    if (collection.mfa == null) {
      collection.mfa = new CollectionSchema.MfaConfig();
    }

    if (collection.authAlert == null) {
      collection.authAlert = new CollectionSchema.AuthAlertConfig();
    }
    collection.authAlert.emailTemplate =
        normalizeEmailTemplate(
            collection.authAlert.emailTemplate, CollectionSchema.EmailTemplate.authAlert());
    collection.verificationTemplate =
        normalizeEmailTemplate(
            collection.verificationTemplate, CollectionSchema.EmailTemplate.verification());
    collection.resetPasswordTemplate =
        normalizeEmailTemplate(
            collection.resetPasswordTemplate, CollectionSchema.EmailTemplate.passwordReset());
    collection.confirmEmailChangeTemplate =
        normalizeEmailTemplate(
            collection.confirmEmailChangeTemplate, CollectionSchema.EmailTemplate.emailChange());

    collection.authToken =
        normalizeTokenConfig(collection.authToken, CollectionSchema.DEFAULT_AUTH_TOKEN_DURATION);
    collection.passwordResetToken =
        normalizeTokenConfig(
            collection.passwordResetToken, CollectionSchema.DEFAULT_PASSWORD_RESET_TOKEN_DURATION);
    collection.verificationToken =
        normalizeTokenConfig(
            collection.verificationToken, CollectionSchema.DEFAULT_VERIFICATION_TOKEN_DURATION);
    collection.emailChangeToken =
        normalizeTokenConfig(
            collection.emailChangeToken, CollectionSchema.DEFAULT_EMAIL_CHANGE_TOKEN_DURATION);
    collection.fileToken =
        normalizeTokenConfig(collection.fileToken, CollectionSchema.DEFAULT_FILE_TOKEN_DURATION);

    if (collection.oauth2 == null) {
      collection.oauth2 = new CollectionSchema.OAuth2Config();
    }
    if (collection.oauth2.providers == null) {
      collection.oauth2.providers = new ArrayList<>();
    } else {
      List<CollectionSchema.OAuth2ProviderConfig> providers = new ArrayList<>();
      for (CollectionSchema.OAuth2ProviderConfig provider : collection.oauth2.providers) {
        if (provider == null) {
          providers.add(null);
          continue;
        }
        OAuth2ProviderManager.ProviderMetadata metadata =
            OAuth2ProviderManager.providerMetadata(provider.name);
        CollectionSchema.OAuth2ProviderConfig normalized =
            new CollectionSchema.OAuth2ProviderConfig();
        normalized.name = metadata == null ? provider.name : metadata.name();
        normalized.clientId = textSetting(provider.clientId);
        normalized.clientSecret = textSetting(provider.clientSecret);
        normalized.authURL = textSetting(provider.authURL);
        normalized.tokenURL = textSetting(provider.tokenURL);
        normalized.userInfoURL = textSetting(provider.userInfoURL);
        normalized.displayName = textSetting(provider.displayName);
        normalized.pkce = provider.pkce;
        normalized.scopes =
            provider.scopes == null
                ? new ArrayList<>()
                : provider.scopes.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(scope -> !scope.isBlank())
                    .collect(Collectors.toCollection(ArrayList::new));
        normalized.extra =
            provider.extra == null ? new LinkedHashMap<>() : new LinkedHashMap<>(provider.extra);
        providers.add(normalized);
      }
      collection.oauth2.providers = providers;
    }
    OAuth2FieldMappingSupport.normalize(collection);
  }

  private CollectionSchema.TokenConfig normalizeTokenConfig(
      CollectionSchema.TokenConfig config, long fallbackDuration) {
    CollectionSchema.TokenConfig normalized =
        config == null ? new CollectionSchema.TokenConfig() : config;
    normalized.duration = normalized.duration > 0 ? normalized.duration : fallbackDuration;
    if (normalized.secret == null || normalized.secret.isBlank()) {
      normalized.secret = IdGenerator.secret();
    }
    return normalized;
  }

  private boolean authRuleChanged(String previous, String next) {
    return !Objects.equals(previous, next)
        && !Objects.equals(previous == null ? "" : previous, next == null ? "" : next);
  }

  private CollectionSchema.EmailTemplate normalizeEmailTemplate(
      CollectionSchema.EmailTemplate template, CollectionSchema.EmailTemplate fallback) {
    CollectionSchema.EmailTemplate normalized =
        template == null ? new CollectionSchema.EmailTemplate() : template;
    if (normalized.subject == null || normalized.subject.isBlank()) {
      normalized.subject = fallback.subject;
    }
    if (normalized.body == null || normalized.body.isBlank()) {
      normalized.body = fallback.body;
    }
    return normalized;
  }

  private void applyAuthOptions(CollectionSchema collection, JsonNode body) {
    if (collection == null || body == null || !body.isObject()) {
      return;
    }
    JsonNode options = body.path("options");
    if (!options.isObject()) {
      return;
    }
    if (!body.has("passwordAuth") && options.has("passwordAuth"))
      collection.passwordAuth =
          mapper.convertValue(
              options.get("passwordAuth"), CollectionSchema.PasswordAuthConfig.class);
    if (!body.has("otp") && options.has("otp"))
      collection.otp = mapper.convertValue(options.get("otp"), CollectionSchema.OtpConfig.class);
    if (!body.has("mfa") && options.has("mfa"))
      collection.mfa = mapper.convertValue(options.get("mfa"), CollectionSchema.MfaConfig.class);
    if (!body.has("oauth2") && options.has("oauth2"))
      collection.oauth2 =
          mapper.convertValue(options.get("oauth2"), CollectionSchema.OAuth2Config.class);
    if (!body.has("authAlert") && options.has("authAlert"))
      collection.authAlert =
          mapper.convertValue(options.get("authAlert"), CollectionSchema.AuthAlertConfig.class);
    if (!body.has("authToken") && options.has("authToken"))
      collection.authToken =
          mapper.convertValue(options.get("authToken"), CollectionSchema.TokenConfig.class);
    if (!body.has("passwordResetToken") && options.has("passwordResetToken"))
      collection.passwordResetToken =
          mapper.convertValue(
              options.get("passwordResetToken"), CollectionSchema.TokenConfig.class);
    if (!body.has("verificationToken") && options.has("verificationToken"))
      collection.verificationToken =
          mapper.convertValue(options.get("verificationToken"), CollectionSchema.TokenConfig.class);
    if (!body.has("emailChangeToken") && options.has("emailChangeToken"))
      collection.emailChangeToken =
          mapper.convertValue(options.get("emailChangeToken"), CollectionSchema.TokenConfig.class);
    if (!body.has("fileToken") && options.has("fileToken"))
      collection.fileToken =
          mapper.convertValue(options.get("fileToken"), CollectionSchema.TokenConfig.class);
    if (!body.has("verificationTemplate") && options.has("verificationTemplate"))
      collection.verificationTemplate =
          mapper.convertValue(
              options.get("verificationTemplate"), CollectionSchema.EmailTemplate.class);
    if (!body.has("resetPasswordTemplate") && options.has("resetPasswordTemplate"))
      collection.resetPasswordTemplate =
          mapper.convertValue(
              options.get("resetPasswordTemplate"), CollectionSchema.EmailTemplate.class);
    if (!body.has("confirmEmailChangeTemplate") && options.has("confirmEmailChangeTemplate"))
      collection.confirmEmailChangeTemplate =
          mapper.convertValue(
              options.get("confirmEmailChangeTemplate"), CollectionSchema.EmailTemplate.class);
    if (!body.has("authRule") && options.has("authRule"))
      collection.authRule = nullableText(options.get("authRule"));
    if (!body.has("manageRule") && options.has("manageRule"))
      collection.manageRule = nullableText(options.get("manageRule"));
  }

  private Duration tokenDuration(CollectionSchema.TokenConfig config, long fallbackSeconds) {
    return Duration.ofSeconds(tokenDurationSeconds(config, fallbackSeconds));
  }

  private long tokenDurationSeconds(CollectionSchema.TokenConfig config, long fallbackSeconds) {
    if (config == null || config.duration <= 0) {
      return fallbackSeconds;
    }
    return config.duration;
  }

  private String bearerTokenSigningSecret(Map<String, Object> claims) {
    CollectionSchema collection = collectionForClaims(claims);
    if (collection == null) {
      return "";
    }
    return tokenSigningSecret(collection.authToken, claims.get("tokenKey"));
  }

  private String fileTokenSigningSecret(Map<String, Object> claims) {
    CollectionSchema collection = collectionForClaims(claims);
    if (collection == null) {
      return "";
    }
    return tokenSigningSecret(collection.fileToken, claims.get("tokenKey"));
  }

  private CollectionSchema collectionForClaims(Map<String, Object> claims) {
    if (claims == null) {
      return null;
    }
    CollectionSchema collection =
        findCollectionOrNull(String.valueOf(claims.getOrDefault("collectionId", "")));
    if (collection == null) {
      collection = findCollectionOrNull(String.valueOf(claims.getOrDefault("collectionName", "")));
    }
    return collection;
  }

  private String tokenSigningSecret(CollectionSchema.TokenConfig config, Object tokenKeyValue) {
    String tokenKey = textSetting(tokenKeyValue);
    String secret = config == null ? "" : textSetting(config.secret);
    return tokenKey + secret;
  }

  private CollectionSchema.TokenConfig tokenConfigForAction(
      String tokenType, CollectionSchema collection) {
    if (collection == null || tokenType == null) {
      return null;
    }
    return switch (tokenType) {
      case "passwordReset" -> collection.passwordResetToken;
      case "verification" -> collection.verificationToken;
      case "emailChange" -> collection.emailChangeToken;
      case "file" -> collection.fileToken;
      default -> collection.authToken;
    };
  }

  private void ensureSuperuserCollection() {
    CollectionSchema existing = collectionsByName.get(SUPERUSERS);
    if (existing != null) {
      existing.id = SystemCollections.SUPERUSERS_ID;
      existing.name = SUPERUSERS;
      existing.type = "auth";
      existing.system = true;
      existing.fields.removeIf(
          field -> field != null && "name".equals(field.name) && "field_name".equals(field.id));
      AuthCollectionFields.normalize(existing);
      for (FieldSchema field : AuthSystemCollections.superusers().fields) {
        if (existing.fields.stream()
            .noneMatch(candidate -> Objects.equals(candidate.name, field.name))) {
          existing.fields.add(field);
        }
      }
      normalizeCollection(existing, true);
      return;
    }
    CollectionSchema superusers = AuthSystemCollections.superusers();
    normalizeCollection(superusers, false);
    collectionsByName.put(superusers.name, superusers);
  }

  private void ensureAuthSystemCollections() {
    for (CollectionSchema defaults : AuthSystemCollections.defaults()) {
      CollectionSchema existing = findCollectionOrNull(defaults.name);
      if (existing == null) {
        normalizeCollection(defaults, false);
        collectionsByName.put(defaults.name, defaults);
        continue;
      }
      defaults.created = existing.created;
      defaults.updated = existing.updated;
      normalizeCollection(defaults, true);
      collectionsByName.remove(existing.name);
      collectionsByName.put(defaults.name, defaults);
    }
  }

  private void migrateSystemCollectionIds() {
    for (SystemCollections.Definition definition : SystemCollections.definitions()) {
      CollectionSchema collection = collectionsByName.get(definition.name());
      if (collection == null || Objects.equals(collection.id, definition.officialId())) {
        continue;
      }
      String previousId = collection.id;
      migrateRecordFile(previousId, definition.officialId(), ".jsonl");
      migrateRecordFile(previousId, definition.officialId(), ".json");
      migrateStorageDirectory(previousId, definition.officialId());
      rewriteCollectionIdReferences(previousId, definition.officialId());
      collection.id = definition.officialId();
    }
  }

  private void rewriteCollectionIdReferences(String previousId, String officialId) {
    for (List<Map<String, Object>> records : List.of(authOrigins, externalAuths, mfas, otps)) {
      for (Map<String, Object> record : records) {
        replaceMapValue(record, "collectionRef", previousId, officialId);
      }
    }
    for (Map<String, Object> request : authRequests) {
      replaceMapValue(request, "collectionId", previousId, officialId);
    }
    for (CollectionSchema collection : collectionsByName.values()) {
      if (collection.fields == null) {
        continue;
      }
      for (FieldSchema field : collection.fields) {
        if (Objects.equals(field.collectionId, previousId)) {
          field.collectionId = officialId;
        }
        if (field.collectionIds != null && field.collectionIds.contains(previousId)) {
          field.collectionIds =
              field.collectionIds.stream()
                  .map(value -> Objects.equals(value, previousId) ? officialId : value)
                  .toList();
        }
        if (field.options != null) {
          field.options.replaceAll((key, value) -> replaceJsonValue(value, previousId, officialId));
        }
      }
    }
  }

  private void replaceMapValue(
      Map<String, Object> record, String key, String previousId, String officialId) {
    if (Objects.equals(record.get(key), previousId)) {
      record.put(key, officialId);
    }
  }

  private JsonNode replaceJsonValue(JsonNode value, String previousId, String officialId) {
    if (value == null || value.isNull()) {
      return value;
    }
    if (value.isTextual()) {
      return Objects.equals(value.asText(), previousId)
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
      copy.properties()
          .forEach(
              entry -> copy.set(
                  entry.getKey(), replaceJsonValue(entry.getValue(), previousId, officialId)));
      return copy;
    }
    return value;
  }

  private void migrateRecordFile(String previousId, String officialId, String extension) {
    Path source = recordsDir.resolve(previousId + extension);
    if (!Files.exists(source)) {
      return;
    }
    Path target = recordsDir.resolve(officialId + extension);
    try {
      LinkedHashMap<String, Map<String, Object>> merged = new LinkedHashMap<>();
      if (Files.exists(target)) {
        mergeMigratedRecords(merged, readRecordFile(target), previousId, officialId);
      }
      mergeMigratedRecords(merged, readRecordFile(source), previousId, officialId);
      Files.createDirectories(recordsDir);
      if (".jsonl".equals(extension)) {
        try (java.io.BufferedWriter writer = Files.newBufferedWriter(target)) {
          for (Map<String, Object> record : merged.values()) {
            writer.write(mapper.writeValueAsString(record));
            writer.newLine();
          }
        }
      } else {
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), merged.values());
      }
      Files.deleteIfExists(source);
    } catch (IOException e) {
      throw new IllegalStateException(
          "failed to migrate system collection records from " + previousId, e);
    }
  }

  private List<Map<String, Object>> readRecordFile(Path path) throws IOException {
    List<Map<String, Object>> records = new ArrayList<>();
    try (java.io.BufferedReader reader = Files.newBufferedReader(path)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.isBlank()) {
          records.add(mapper.readValue(line, STRING_OBJECT_MAP));
        }
      }
      return records;
    } catch (Exception ignored) {
      return mapper.readValue(path.toFile(), RECORD_LIST);
    }
  }

  private void mergeMigratedRecords(
      Map<String, Map<String, Object>> merged,
      List<Map<String, Object>> records,
      String previousId,
      String officialId) {
    for (Map<String, Object> record : records) {
      replaceMapValue(record, "collectionId", previousId, officialId);
      String id = String.valueOf(record.getOrDefault("id", ""));
      Map<String, Object> existing = merged.putIfAbsent(id, record);
      if (existing != null && !Objects.equals(existing, record)) {
        throw new IllegalStateException(
            "conflicting records found while migrating system collection " + previousId);
      }
    }
  }

  private void migrateStorageDirectory(String previousId, String officialId) {
    Path source = storageDir.resolve(previousId);
    if (!Files.exists(source)) {
      return;
    }
    Path target = storageDir.resolve(officialId);
    try {
      if (!Files.exists(target)) {
        Files.createDirectories(storageDir);
        Files.move(source, target);
        return;
      }
      try (Stream<Path> paths = Files.walk(source)) {
        for (Path path : paths.sorted().toList()) {
          Path relative = source.relativize(path);
          Path destination = target.resolve(relative);
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
      try (Stream<Path> paths = Files.walk(source)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException(
          "failed to migrate system collection storage from " + previousId, e);
    }
  }

  @Override
  public CollectionSchema getCollection(String nameOrId) {
    return findCollectionOrNull(nameOrId);
  }

  @Override
  public Map<String, Object> getRecord(CollectionSchema collection, String id) {
    return findRecordOrNull(collection, id);
  }

  @Override
  public List<Map<String, Object>> recordsForRule(String collectionName) {
    CollectionSchema collection = findCollectionOrNull(collectionName);
    return collection == null ? List.of() : records(collection);
  }

  @Override
  public Map<String, Object> findRecordByEmail(CollectionSchema collection, String email) {
    if (!"auth".equals(collection.type))
      return null;
    return findAuthRecordByEmail(collection, email);
  }

  @Override
  public void updateRecordField(
      CollectionSchema collection, String recordId, Map<String, Object> fields) {
    Map<String, Object> record = findRecordOrNull(collection, recordId);
    if (record == null)
      return;
    boolean securityChanged =
        "auth".equals(collection.type)
            && (fields.containsKey(passwordField(collection))
                && !Objects.equals(
                    record.get(passwordField(collection)),
                    fields.get(passwordField(collection)))
                || fields.containsKey("tokenKey")
                    && !Objects.equals(record.get("tokenKey"), fields.get("tokenKey")));
    record.putAll(fields);
    record.put("updated", now());
    if (securityChanged) {
      clearAuthSecurityState(collection.id, recordId);
    }
    saveRecords(collection);
  }

  @Override
  public boolean canView(
      CollectionSchema collection,
      Map<String, Object> record,
      Map<String, String> query,
      RequestPrincipal principal) {
    return canViewExpandedRecord(collection, record, query, principal);
  }

  @Override
  public boolean canView(
      CollectionSchema collection,
      Map<String, Object> record,
      RuleRequestContext request,
      RequestPrincipal principal) {
    return canViewExpandedRecord(collection, record, request, principal);
  }

  private CollectionSchema findCollection(String idOrName) {
    CollectionSchema collection = findCollectionOrNull(idOrName);
    if (collection == null) {
      throw new ApiException(404, "Collection not found.");
    }
    return collection;
  }

  private CollectionSchema resolveRuleCollection(CollectionSchema candidate, String idOrName) {
    if (candidate != null
        && (Objects.equals(candidate.id, idOrName) || Objects.equals(candidate.name, idOrName))) {
      return candidate;
    }
    return findCollectionOrNull(idOrName);
  }

  private CollectionSchema findCollectionOrNull(String idOrName) {
    if (idOrName == null || idOrName.isBlank()) {
      return null;
    }
    idOrName = SystemCollections.canonicalIdentifier(idOrName);
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

  private Set<String> collectionIndexNames(String excludedCollectionId) {
    return collectionIndexNames(collectionsByName.values(), excludedCollectionId);
  }

  private Set<String> collectionIndexNames(
      Collection<CollectionSchema> collections, String excludedCollectionId) {
    Set<String> names = new LinkedHashSet<>();
    for (CollectionSchema collection : collections) {
      if (Objects.equals(collection.id, excludedCollectionId) || collection.indexes == null) {
        continue;
      }
      for (String index : collection.indexes) {
        String name = CollectionIndexSupport.indexName(index);
        if (!name.isBlank()) {
          names.add(name);
        }
      }
    }
    return names;
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
    switch (collection.name) {
      case AuthSystemCollections.AUTH_ORIGINS :
        return authOrigins;
      case AuthSystemCollections.EXTERNAL_AUTHS :
        return externalAuths;
      case AuthSystemCollections.MFAS :
        return mfas;
      case AuthSystemCollections.OTPS :
        return otps;
      default :
        break;
    }
    if ("view".equals(collection.type)) {
      if (!evaluatingViewCollections.add(collection.id)) {
        throw new IllegalArgumentException("circular view collection query detected");
      }
      try {
        Map<String, Object> preview = previewViewQuery(collection.viewQuery, SQL_MAX_ROWS);
        return mapper.convertValue(preview.get("sample"), RECORD_LIST);
      } finally {
        evaluatingViewCollections.remove(collection.id);
      }
    }
    return recordsByCollectionId.computeIfAbsent(collection.id, ignored -> new ArrayList<>());
  }

  private boolean matchesFilter(
      CollectionSchema collection,
      Map<String, Object> record,
      String filter,
      RuleRequestContext request,
      RequestPrincipal principal) {
    if (filter == null || filter.isBlank()) {
      return true;
    }
    return RuleEvaluator.matches(
        filter,
        RecordFieldResolverSupport.context(
            this,
            collection,
            record,
            null,
            request,
            "GET",
            principal,
            isSuperuser(principal),
            !isSuperuser(principal)));
  }

  private RuleEvaluator.Context ruleContext(
      Map<String, Object> record,
      Map<String, Object> body,
      Map<String, String> query,
      String method,
      RequestPrincipal principal) {
    return RuleEvaluator.context(
        record,
        body,
        query,
        method,
        principal,
        collectionName -> {
          CollectionSchema collection = findCollectionOrNull(collectionName);
          return collection == null ? List.of() : records(collection);
        });
  }

  private Object readPath(Object source, String path) {
    if (source == null || path == null || path.isBlank()) {
      return "";
    }
    Object current = source;
    for (String part : path.split("\\.")) {
      if (part.isBlank()) {
        return "";
      }
      if (current instanceof Map<?, ?> map) {
        current = Unsafe.stringObjectMap(map).get(part);
      } else {
        return "";
      }
    }
    return current == null ? "" : current;
  }

  private Map<String, Object> backupInfo(Path path) {
    try {
      Map<String, Object> info = new LinkedHashMap<>();
      info.put("key", path.getFileName().toString());
      info.put("size", Files.size(path));
      info.put(
          "modified",
          DateTimeFormatter.ISO_INSTANT.format(Files.getLastModifiedTime(path).toInstant()));
      return info;
    } catch (IOException e) {
      throw new IllegalStateException("failed to read backup metadata", e);
    }
  }

  private void publishBackup(Path temporary, Path target) throws IOException {
    try {
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temporary, target);
    }
  }

  private void deleteTemporaryBackup(Path temporary) {
    if (temporary == null) {
      return;
    }
    try {
      Files.deleteIfExists(temporary);
    } catch (IOException ignored) {
      // best effort cleanup
    }
  }

  private Path backupPath(String key) {
    String safeKey = backupKey(key);
    Path path = backupsDir.resolve(safeKey).normalize();
    if (!path.startsWith(backupsDir.normalize())) {
      throw new ApiException(
          400, "Invalid backup key.", ApiErrors.invalidField("key", "Invalid backup key."));
    }
    return path;
  }

  private String backupKey(String value) {
    String filename =
        value == null || value.isBlank() ? "backup.zip" : Path.of(value).getFileName().toString();
    String sanitized = filename.replaceAll("[^A-Za-z0-9._@-]", "_").replaceAll("_+", "_");
    if (sanitized.isBlank() || ".".equals(sanitized) || "..".equals(sanitized)) {
      sanitized = "backup";
    }
    if (!sanitized.endsWith(".zip")) {
      sanitized += ".zip";
    }
    return sanitized.length() > 120 ? sanitized.substring(sanitized.length() - 120) : sanitized;
  }

  private String createBackupKey(String requested) {
    if (requested == null || requested.isBlank()) {
      return "pb_backup_" + BACKUP_TIMESTAMP.format(Instant.now()) + ".zip";
    }
    String key = requested.trim();
    if (key.length() > 150 || !key.matches("^(@auto_pb_backup_)?[a-z0-9_-]+\\.zip$")) {
      throw new ApiException(
          400,
          "An error occurred while validating the submitted data.",
          ApiErrors.fieldError("name", "validation_match_invalid", "Must be in a valid format."));
    }
    return key;
  }

  private void zipDirectory(Path root, ZipOutputStream zip) throws IOException {
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths
          .filter(Files::isRegularFile)
          .filter(path -> !Files.isSymbolicLink(path))
          .filter(path -> !isBackupExcluded(path))
          .collect(Collectors.toList())) {
        if (!Files.exists(path)) {
          continue;
        }
        String entryName = root.relativize(path).toString().replace('\\', '/');
        zip.putNextEntry(new ZipEntry(entryName));
        try {
          Files.copy(path, zip);
        } catch (NoSuchFileException ignored) {
          zip.closeEntry();
          continue;
        }
        zip.closeEntry();
      }
    }
  }

  private boolean isBackupExcluded(Path path) {
    Path normalized = path.normalize();
    return normalized.startsWith(backupsDir.normalize())
        || normalized.equals(secretFile.normalize());
  }

  private void unzipBackup(Path backup, Path target) throws IOException {
    try (InputStream input = Files.newInputStream(backup);
        ZipInputStream zip = new ZipInputStream(input)) {
      validateZipEntries(zip, target.toAbsolutePath().normalize());
    }
    try (InputStream input = Files.newInputStream(backup);
        ZipInputStream zip = new ZipInputStream(input)) {
      ZipBudget budget = new ZipBudget();
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        budget.begin(entry);
        Path out = safeZipTarget(target, entry);
        if (entry.isDirectory()) {
          Files.createDirectories(out);
        } else {
          Files.createDirectories(out.getParent());
          try (OutputStream output = Files.newOutputStream(out)) {
            drainZipEntry(zip, budget, output);
          }
        }
        zip.closeEntry();
      }
    }
  }

  private void validateZipEntries(ZipInputStream zip, Path target) throws IOException {
    ZipBudget budget = new ZipBudget();
    ZipEntry entry;
    boolean foundEntry = false;
    while ((entry = zip.getNextEntry()) != null) {
      foundEntry = true;
      budget.begin(entry);
      safeZipTarget(target, entry);
      drainZipEntry(zip, budget, OutputStream.nullOutputStream());
      zip.closeEntry();
    }
    if (!foundEntry) {
      throw invalidBackupArchive();
    }
  }

  private Path safeZipTarget(Path target, ZipEntry entry) {
    String name = entry.getName();
    if (name == null
        || name.isBlank()
        || name.startsWith("/")
        || name.contains("\\")
        || name.contains("..")) {
      throw invalidBackupArchive();
    }
    if ("pb_secret".equals(name) || name.startsWith("backups/")) {
      throw invalidBackupArchive();
    }
    Path out = target.resolve(name).normalize();
    if (!out.startsWith(target)) {
      throw invalidBackupArchive();
    }
    return out;
  }

  private void drainZipEntry(InputStream input, ZipBudget budget, OutputStream output)
      throws IOException {
    byte[] buffer = new byte[8192];
    int read;
    while ((read = input.read(buffer)) >= 0) {
      if (read == 0) {
        continue;
      }
      budget.add(read);
      output.write(buffer, 0, read);
    }
  }

  private static final class ZipBudget {
    private int entries;
    private long bytes;

    private void begin(ZipEntry entry) {
      if (++entries > MAX_BACKUP_ENTRIES) {
        throw new ApiException(400, "Invalid backup archive.");
      }
      long declaredSize = entry.getSize();
      if (declaredSize > MAX_BACKUP_UNCOMPRESSED_BYTES) {
        throw new ApiException(400, "Invalid backup archive.");
      }
    }

    private void add(long count) {
      if (count < 0 || bytes > MAX_BACKUP_UNCOMPRESSED_BYTES - count) {
        throw new ApiException(400, "Invalid backup archive.");
      }
      bytes += count;
    }
  }

  private ApiException invalidBackupArchive() {
    return new ApiException(
        400, "Invalid backup archive.", ApiErrors.invalidField("file", "Invalid backup archive."));
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
      for (Path item : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
        Files.deleteIfExists(item);
      }
    } catch (IOException e) {
      throw new IllegalStateException("failed to delete " + path, e);
    }
  }

  private void saveAll() {
    saveSettings();
    saveLogs();
    saveAuthRequests();
    saveAuthOrigins();
    saveExternalAuths();
    saveMfas();
    saveOtps();
    saveSchema();
    for (CollectionSchema collection : collectionsByName.values()) {
      saveRecords(collection);
    }
  }

  private void saveSettings() {
    try {
      FilePermissionSupport.secureDirectory(dataDir);
      mapper.writerWithDefaultPrettyPrinter().writeValue(settingsFile.toFile(), settings);
      FilePermissionSupport.secureFile(settingsFile);
    } catch (IOException e) {
      throw new IllegalStateException("failed to save settings", e);
    }
  }

  private void saveAuthOrigins() {
    try {
      FilePermissionSupport.secureDirectory(dataDir);
      mapper.writerWithDefaultPrettyPrinter().writeValue(authOriginsFile.toFile(), authOrigins);
      FilePermissionSupport.secureFile(authOriginsFile);
    } catch (IOException e) {
      throw new IllegalStateException("failed to save auth origins", e);
    }
  }

  private void saveLogs() {
    try {
      FilePermissionSupport.secureDirectory(dataDir);
      mapper.writerWithDefaultPrettyPrinter().writeValue(logsFile.toFile(), logs);
      FilePermissionSupport.secureFile(logsFile);
    } catch (IOException e) {
      throw new IllegalStateException("failed to save logs", e);
    }
  }

  private void saveAuthRequests() {
    try {
      FilePermissionSupport.secureDirectory(dataDir);
      mapper.writerWithDefaultPrettyPrinter().writeValue(authRequestsFile.toFile(), authRequests);
      FilePermissionSupport.secureFile(authRequestsFile);
    } catch (IOException e) {
      throw new IllegalStateException("failed to save auth requests", e);
    }
  }

  private void saveExternalAuths() {
    try {
      FilePermissionSupport.secureDirectory(dataDir);
      mapper.writerWithDefaultPrettyPrinter().writeValue(externalAuthsFile.toFile(), externalAuths);
      FilePermissionSupport.secureFile(externalAuthsFile);
    } catch (IOException e) {
      throw new IllegalStateException("failed to save external auths", e);
    }
  }

  private void saveMfas() {
    try {
      FilePermissionSupport.secureDirectory(dataDir);
      mapper.writerWithDefaultPrettyPrinter().writeValue(mfasFile.toFile(), mfas);
      FilePermissionSupport.secureFile(mfasFile);
    } catch (IOException e) {
      throw new IllegalStateException("failed to save mfas", e);
    }
  }

  private void saveOtps() {
    try {
      FilePermissionSupport.secureDirectory(dataDir);
      mapper.writerWithDefaultPrettyPrinter().writeValue(otpsFile.toFile(), otps);
      FilePermissionSupport.secureFile(otpsFile);
    } catch (IOException e) {
      throw new IllegalStateException("failed to save otps", e);
    }
  }

  private void saveSchema() {
    try {
      FilePermissionSupport.secureDirectory(dataDir);
      Map<String, Object> root = new LinkedHashMap<>();
      root.put(
          "collections",
          collectionsByName.values().stream().map(this::collectionPersistenceMap).toList());
      mapper.writerWithDefaultPrettyPrinter().writeValue(schemaFile.toFile(), root);
      FilePermissionSupport.secureFile(schemaFile);
    } catch (IOException e) {
      throw new IllegalStateException("failed to save schema", e);
    }
  }

  private void saveRecords(CollectionSchema collection) {
    switch (collection.name) {
      case AuthSystemCollections.AUTH_ORIGINS -> {
        saveAuthOrigins();
        return;
      }
      case AuthSystemCollections.EXTERNAL_AUTHS -> {
        saveExternalAuths();
        return;
      }
      case AuthSystemCollections.MFAS -> {
        saveMfas();
        return;
      }
      case AuthSystemCollections.OTPS -> {
        saveOtps();
        return;
      }
      default -> {
      }
    }
    try {
      FilePermissionSupport.secureDirectory(recordsDir);
      try (java.io.BufferedWriter writer = Files.newBufferedWriter(recordsFile(collection))) {
        for (Map<String, Object> record : records(collection)) {
          writer.write(mapper.writeValueAsString(record));
          writer.newLine();
        }
      }
      FilePermissionSupport.secureFile(recordsFile(collection));
    } catch (IOException e) {
      throw new IllegalStateException("failed to save records", e);
    }
  }

  private Path recordsFile(CollectionSchema collection) {
    return recordsDir.resolve(collection.id + ".jsonl");
  }

  private Path collectionStorageDir(CollectionSchema collection) {
    return storageDir.resolve(collection.id);
  }

  private CollectionSchema copyCollection(CollectionSchema collection) {
    CollectionSchema copy = mapper.convertValue(collection, CollectionSchema.class);
    copy.authRule = collection.authRule;
    copy.manageRule = collection.manageRule;
    return copy;
  }

  private static String readOrCreateSecret(Path secretFile) throws IOException {
    if (Files.exists(secretFile)) {
      FilePermissionSupport.secureFile(secretFile);
      String existing = Files.readString(secretFile, StandardCharsets.UTF_8).trim();
      if (existing.length() < 32) {
        throw new IOException("runtime token secret is too short");
      }
      return existing;
    }
    String secret = IdGenerator.prefixed("secret_") + IdGenerator.prefixed("_");
    FilePermissionSupport.createPrivateFile(secretFile);
    Files.writeString(secretFile, secret, StandardCharsets.UTF_8);
    FilePermissionSupport.secureFile(secretFile);
    return secret;
  }

  private static String now() {
    return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
  }

  private static String normalizeType(String type) {
    return type == null ? "text" : type.trim().toLowerCase(Locale.ROOT);
  }

  private static String requiredText(JsonNode body, String field) {
    return requiredText(body, field, "Failed to submit request.");
  }

  private static String requiredText(JsonNode body, String field, String message) {
    JsonNode value = body == null ? null : body.get(field);
    if (value == null || value.isNull() || value.asText().isBlank()) {
      throw new ApiException(400, message, ApiErrors.requiredField(field));
    }
    return value.asText();
  }

  private static String bodyText(JsonNode body, String field, String fallback) {
    JsonNode value = body == null ? null : body.get(field);
    if (value == null || value.isNull()) {
      return fallback;
    }
    String text = value.asText("").trim();
    return text.isBlank() ? fallback : text;
  }

  private static String bodyOrQueryText(
      JsonNode body, Map<String, String> query, String field, String fallback) {
    String value = bodyText(body, field, null);
    if (value != null) {
      return value;
    }
    if (query == null) {
      return fallback;
    }
    String queryValue = query.get(field);
    if (queryValue == null || queryValue.isBlank()) {
      return fallback;
    }
    return queryValue.trim();
  }

  private static String nullableText(JsonNode value) {
    return value == null || value.isNull() ? null : value.asText();
  }

  private static Map<String, Object> fieldError(String field, String code, String message) {
    return Map.of(field, validationError(code, message));
  }

  private static Map<String, Object> validationError(String code, String message) {
    return Map.of("code", code, "message", message);
  }

  private record TestEmailContent(String subject, String text, String html) {
  }

  private record CronJob(String id, String expression) {
  }

  private record SqlResult(
      long affectedRows, List<Map<String, Object>> columns, List<List<Object>> rows) {
  }

  private record SqlFrom(String table, String where, String orderBy, int limit, int offset) {
  }

  private record SqlClauseIndex(String keyword, int index) {
  }

  private record SqlSelectExpression(
      String source,
      String name,
      boolean count,
      boolean all,
      Object literal,
      boolean literalOnly) {
  }

  private record SqlColumnDefinition(String name, String type, boolean required, boolean unique) {
  }

  private record FileChanges(
      Map<String, Object> values,
      Map<String, UploadedFile> writes,
      Map<String, List<String>> removals) {
    private boolean isEmpty() {
      return values.isEmpty() && writes.isEmpty() && removals.isEmpty();
    }
  }

  private record AuthAction(
      CollectionSchema collection, Map<String, Object> record, Map<String, Object> claims) {
  }
}
