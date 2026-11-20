package io.github.jackbaozz.pocketbase.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.internal.RecordProcessor;
import io.github.jackbaozz.pocketbase.server.internal.TokenService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V042CompatibilityTest {
  @TempDir
  Path dataDir;
  private LocalPocketBase server;
  private final HttpClient client = HttpClient.newHttpClient();
  private final ObjectMapper mapper = new ObjectMapper();
  private String adminToken;

  @BeforeEach
  void start() throws Exception {
    server = TestDatabaseFactory.start(new ServerConfig("127.0.0.1", 0, dataDir, null, null, null));
    assertEquals(200, request("POST", "/api/bootstrap/superuser", null,
        Map.of("email", "admin@example.test", "password", "Password_123456")).statusCode());
    adminToken = json(request("POST", "/api/collections/_superusers/auth-with-password", null,
        Map.of("identity", "admin@example.test", "password", "Password_123456"))).path("token").asText();
  }

  @AfterEach
  void close() {
    if (server != null)
      server.close();
  }

  @Test
  void rejectsUnknownTokensEvenOnPublicRoutesWithoutBlockingAnonymousOrValidRequests() throws Exception {
    for (String header : new String[] {"unknown", "Bearer unknown", "bearer unknown"}) {
      var response = request("GET", "/api/health", header, null);
      assertEquals(401, response.statusCode());
      assertEquals("Missing or invalid authentication.", json(response).path("message").asText());
      assertEquals(0, json(response).path("data").size());
    }
    assertEquals(200, request("GET", "/api/health", null, null).statusCode());
    assertEquals(200, request("GET", "/api/health", adminToken, null).statusCode());
    assertEquals(200, request("GET", "/api/health", "bEaReR " + adminToken, null).statusCode());
    assertEquals(204, request("OPTIONS", "/api/health", "Bearer unknown", null).statusCode());
    createAuthCollection("public_users", List.of());
    assertEquals(200, request("GET", "/api/collections/public_users/records", null, null).statusCode());
    assertEquals(401, request("GET", "/api/collections/public_users/records", "unknown", null).statusCode());
  }

  @Test
  void rejectsRevokedTokensAndAllowsLoginAgainWithoutTheStaleHeader() throws Exception {
    createAuthCollection("short_lived", List.of());
    JsonNode record = json(request("POST", "/api/collections/short_lived/records", adminToken,
        Map.of("email", "user@example.test", "password", "Password_123456", "passwordConfirm", "Password_123456")));
    String token = json(request("POST", "/api/collections/short_lived/auth-with-password", null,
        Map.of("identity", "user@example.test", "password", "Password_123456"))).path("token").asText();
    assertEquals(200, request("GET", "/api/health", token, null).statusCode());
    assertEquals(200, request("PATCH", "/api/collections/short_lived/records/" + record.path("id").asText(),
        adminToken, Map.of("password", "Changed_123456", "passwordConfirm", "Changed_123456")).statusCode());
    assertEquals(401, request("GET", "/api/health", token, null).statusCode());
    assertEquals(200, request("POST", "/api/collections/short_lived/auth-with-password", null,
        Map.of("identity", "user@example.test", "password", "Changed_123456")).statusCode());
  }

  @Test
  void rejectsTokensAfterTheirRecordOrCollectionIsDeleted() throws Exception {
    createAuthCollection("deleted_users", List.of());
    for (boolean deleteCollection : new boolean[] {false, true}) {
      JsonNode record = json(request("POST", "/api/collections/deleted_users/records", adminToken,
          Map.of("email", "deleted@example.test", "password", "Password_123456", "passwordConfirm",
              "Password_123456")));
      String token = json(request("POST", "/api/collections/deleted_users/auth-with-password", null,
          Map.of("identity", "deleted@example.test", "password", "Password_123456"))).path("token").asText();
      String path =
          "/api/collections/deleted_users" + (deleteCollection ? "" : "/records/" + record.path("id").asText());
      assertEquals(204, request("DELETE", path, adminToken, null).statusCode());
      assertEquals(401, request("GET", "/api/health", token, null).statusCode());
    }
  }

  @Test
  void tokenVerifierRejectsExpiredTokensEvenWithAValidSignature() {
    var tokens = new TokenService(mapper, "isolated-expiration-test-key");
    String expired = tokens.create(Map.of("type", "auth", "sub", "test-user"), Duration.ofSeconds(-5));
    String valid = tokens.create(Map.of("type", "auth", "sub", "test-user"), Duration.ofMinutes(1));
    assertTrue(tokens.verify(expired).isEmpty());
    assertTrue(tokens.verify(valid).isPresent());
  }

  @Test
  void providerOrderSurvivesRedactedPatchAndRestart() throws Exception {
    createAuthCollection("ordered_providers", List.of(
        Map.of("name", "github", "clientId", "github-client", "clientSecret", "github-test-secret"),
        Map.of("name", "google", "clientId", "google-client", "clientSecret", "google-test-secret")));
    assertEquals(200, request("PATCH", "/api/collections/ordered_providers", adminToken,
        Map.of("oauth2", Map.of("providers", List.of(Map.of("name", "google"), Map.of("name", "github")))))
        .statusCode());
    assertProviderOrder();
    server.close();
    server = TestDatabaseFactory.start(new ServerConfig("127.0.0.1", 0, dataDir, null, null, null));
    assertProviderOrder();
  }

  @Test
  void jpegXlRequiresAMatchingSignatureAndNeverGeneratesThumbnails() throws Exception {
    JsonNode collection = json(request("POST", "/api/collections", adminToken,
        Map.of("name", "jxl_assets", "viewRule", "", "fields", List.of(
            Map.of("name", "image", "type", "file", "maxSelect", 1, "thumbs", List.of("20x20"))))));
    byte[][] signatures = {{(byte) 0xff, 0x0a, 0, 0},
        {0, 0, 0, 12, 'J', 'X', 'L', ' ', 13, 10, (byte) 0x87, 10},
        "<script>not a JPEG XL</script>".getBytes(StandardCharsets.UTF_8)};
    for (int i = 0; i < signatures.length; i++) {
      String boundary = "jxl-test-boundary";
      var content = new java.io.ByteArrayOutputStream();
      content.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"image.jxl\"\r\n"
          + "Content-Type: image/jxl\r\n\r\n").getBytes(StandardCharsets.UTF_8));
      content.write(signatures[i]);
      content.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
      var upload = client.send(
          HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/collections/jxl_assets/records"))
              .header("Authorization", adminToken).header("Content-Type", "multipart/form-data; boundary=" + boundary)
              .POST(HttpRequest.BodyPublishers.ofByteArray(content.toByteArray())).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, upload.statusCode(), upload.body());
      JsonNode record = json(upload);
      String path = "/api/files/" + collection.path("id").asText() + "/" + record.path("id").asText()
          + "/" + record.path("image").asText();
      var file = client.send(HttpRequest.newBuilder(URI.create(server.baseUrl() + path + "?thumb=20x20")).GET().build(),
          HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, file.statusCode());
      assertArrayEquals(signatures[i], file.body());
      assertEquals(i < 2 ? "image/jxl" : "application/octet-stream",
          file.headers().firstValue("Content-Type").orElseThrow());
      assertTrue(
          file.headers().firstValue("Content-Disposition").orElseThrow().startsWith(i < 2 ? "inline" : "attachment"));
    }
  }

  @Test
  void internalLogCountIgnoresPaginationAndMatchesFilters() throws Exception {
    // Drain bootstrap/login activity logs before exercising deterministic direct writes.
    server.close();
    server = TestDatabaseFactory.start(new ServerConfig("127.0.0.1", 0, dataDir, null, null, null));
    server.store().truncateLogs();
    server.store().recordActivityLog("GET", "/ok", 200, 1, null, Map.of(), "127.0.0.1");
    server.store().recordActivityLog("GET", "/error", 400, 1, null, Map.of(), "127.0.0.1");
    assertEquals(2, server.store().countLogs());
    assertEquals(1, server.store().countLogs(Map.of("filter", "level = 8", "page", "999", "perPage", "1")));
  }

  private void assertProviderOrder() throws Exception {
    JsonNode methods = json(request("GET", "/api/collections/ordered_providers/auth-methods", null, null));
    assertEquals("google", methods.path("oauth2").path("providers").get(0).path("name").asText());
    assertEquals("github", methods.path("oauth2").path("providers").get(1).path("name").asText());
    // Inspect server-side configuration rather than the deliberately redacted HTTP response.
    var providers = ((RecordProcessor.StoreContext) server.store()).getCollection("ordered_providers").oauth2.providers;
    assertEquals("google-test-secret", providers.get(0).clientSecret);
    assertEquals("github-test-secret", providers.get(1).clientSecret);
  }

  private void createAuthCollection(String name, List<Map<String, String>> providers) throws Exception {
    assertEquals(200, request("POST", "/api/collections", adminToken,
        Map.of("name", name, "type", "auth", "listRule", "", "viewRule", "",
            "fields", List.of(), "oauth2", Map.of("enabled", !providers.isEmpty(), "providers", providers)))
        .statusCode());
  }

  private JsonNode json(HttpResponse<String> response) throws Exception {
    return mapper.readTree(response.body());
  }

  private HttpResponse<String> request(String method, String path, String token, Object body) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(server.baseUrl() + path));
    if (token != null)
      builder.header("Authorization", token);
    if (body != null)
      builder.header("Content-Type", "application/json");
    if ("OPTIONS".equals(method))
      builder.header("Origin", "https://client.test")
          .header("Access-Control-Request-Method", "GET");
    builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
}
