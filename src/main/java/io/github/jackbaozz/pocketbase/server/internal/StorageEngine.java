package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Interface that abstracts the data store for the PocketBase-like server, allowing either JSON
 * files (legacy) or SQLite (parity baseline) as backends.
 */
public interface StorageEngine {

  ObjectMapper mapper();

  boolean canBackup();

  boolean hasSuperusers();

  Map<String, Object> runSql(JsonNode body);

  Map<String, Object> getSettings(Map<String, String> query);

  Map<String, Object> updateSettings(JsonNode body, Map<String, String> query);

  void testS3(JsonNode body);

  void testEmail(JsonNode body);

  Map<String, Object> generateAppleClientSecret(JsonNode body);

  Map<String, Object> listLogs(Map<String, String> query);

  List<Map<String, Object>> logStats(Map<String, String> query);

  Map<String, Object> getLog(String id, Map<String, String> query);

  List<Map<String, Object>> listCrons();

  void runCron(String id);

  Map<String, Object> fileToken(RequestPrincipal principal);

  List<Map<String, Object>> listBackups();

  void deleteBackup(String key);

  Map<String, Object> restoreBackup(String key);

  Map<String, Object> createBackup(JsonNode body);

  Map<String, Object> uploadBackup(String filename, byte[] bytes);

  Map<String, Object> bootstrapSuperuser(JsonNode body);

  default Map<String, Object> authWithPassword(
      String collection, JsonNode body, Map<String, String> query, AuthOriginContext origin) {
    return authWithPassword(collection, body, RuleRequestContext.of(query, Map.of()), origin);
  }

  Map<String, Object> authWithPassword(
      String collection, JsonNode body, RuleRequestContext request, AuthOriginContext origin);

  default Map<String, Object> authWithOAuth2(
      String collection,
      JsonNode body,
      Map<String, String> query,
      RequestPrincipal principal,
      AuthOriginContext origin) {
    return authWithOAuth2(
        collection, body, RuleRequestContext.of(query, Map.of()), principal, origin);
  }

  Map<String, Object> authWithOAuth2(
      String collection,
      JsonNode body,
      RuleRequestContext request,
      RequestPrincipal principal,
      AuthOriginContext origin);

  Map<String, Object> authRefresh(
      String collection, RequestPrincipal principal, Map<String, String> query);

  Map<String, Object> authMethods(String collection);

  void requestPasswordReset(String collection, JsonNode body);

  void confirmPasswordReset(String collection, JsonNode body);

  void requestVerification(String collection, JsonNode body);

  void confirmVerification(String collection, JsonNode body);

  void requestEmailChange(String collection, JsonNode body, RequestPrincipal principal);

  void confirmEmailChange(String collection, JsonNode body);

  Map<String, Object> impersonate(
      String collection, String id, JsonNode body, Map<String, String> query);

  Map<String, Object> listCollections(Map<String, String> query);

  CollectionSchema createCollection(JsonNode body);

  Map<String, Object> importCollections(JsonNode body, boolean dryRun);

  Map<String, Object> collectionScaffolds();

  Map<String, Object> dryRunView(JsonNode body);

  List<Map<String, Object>> oauth2ProviderMetadata();

  Map<String, Object> getCollection(String collection, Map<String, String> query);

  CollectionSchema updateCollection(String collection, JsonNode body);

  void deleteCollection(String collection);

  void truncateCollection(String collection);

  Map<String, Object> requestOtp(String collection, JsonNode body);

  default Map<String, Object> authWithOtp(
      String collection, JsonNode body, Map<String, String> query, AuthOriginContext origin) {
    return authWithOtp(collection, body, RuleRequestContext.of(query, Map.of()), origin);
  }

  Map<String, Object> authWithOtp(
      String collection, JsonNode body, RuleRequestContext request, AuthOriginContext origin);

  default Map<String, Object> listRecords(
      String collection, Map<String, String> query, RequestPrincipal principal) {
    return listRecords(collection, RuleRequestContext.of(query, Map.of()), principal);
  }

  Map<String, Object> listRecords(
      String collection, RuleRequestContext request, RequestPrincipal principal);

  default Map<String, Object> getRecord(
      String collection, String id, Map<String, String> query, RequestPrincipal principal) {
    return getRecord(collection, id, RuleRequestContext.of(query, Map.of()), principal);
  }

  Map<String, Object> getRecord(
      String collection, String id, RuleRequestContext request, RequestPrincipal principal);

  default Map<String, Object> createRecord(
      String collection,
      JsonNode body,
      Map<String, List<UploadedFile>> files,
      Map<String, String> query,
      RequestPrincipal principal) {
    return createRecord(collection, body, files, RuleRequestContext.of(query, Map.of()), principal);
  }

  Map<String, Object> createRecord(
      String collection,
      JsonNode body,
      Map<String, List<UploadedFile>> files,
      RuleRequestContext request,
      RequestPrincipal principal);

  default Map<String, Object> updateRecord(
      String collection,
      String id,
      JsonNode body,
      Map<String, List<UploadedFile>> files,
      Map<String, String> query,
      RequestPrincipal principal) {
    return updateRecord(
        collection, id, body, files, RuleRequestContext.of(query, Map.of()), principal);
  }

  Map<String, Object> updateRecord(
      String collection,
      String id,
      JsonNode body,
      Map<String, List<UploadedFile>> files,
      RuleRequestContext request,
      RequestPrincipal principal);

  default Map<String, Object> upsertRecord(
      String collection,
      String id,
      JsonNode body,
      Map<String, List<UploadedFile>> files,
      Map<String, String> query,
      RequestPrincipal principal) {
    return upsertRecord(
        collection, id, body, files, RuleRequestContext.of(query, Map.of()), principal);
  }

  Map<String, Object> upsertRecord(
      String collection,
      String id,
      JsonNode body,
      Map<String, List<UploadedFile>> files,
      RuleRequestContext request,
      RequestPrincipal principal);

  default void deleteRecord(String collection, String id, RequestPrincipal principal) {
    deleteRecord(collection, id, RuleRequestContext.empty(), principal);
  }

  void deleteRecord(
      String collection, String id, RuleRequestContext request, RequestPrincipal principal);

  Optional<RequestPrincipal> verifyFileToken(String token);

  Path filePath(String collection, String recordId, String filename, RequestPrincipal principal);

  default Path filePath(
      String collection,
      String recordId,
      String filename,
      RuleRequestContext request,
      RequestPrincipal principal) {
    return filePath(collection, recordId, filename, principal);
  }

  boolean fileThumbAllowed(String collection, String recordId, String filename, String thumb);

  Path backupFile(String key);

  Optional<Map<String, Object>> verifyToken(String token);

  void recordActivityLog(
      String method,
      String url,
      int status,
      long duration,
      RequestPrincipal principal,
      Map<String, String> headers,
      String remoteIp);

  void realtimeHub(RealtimeHub hub);

  // Support transaction demarcation
  <T> T transactional(Supplier<T> action);

  default void close() {
  }
}
