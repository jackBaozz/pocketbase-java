package io.github.jackbaozz.pocketbase.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.internal.LogPersistenceSanitizer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LogApiAndSettingsTest {

  private LocalPocketBase server;
  private String baseUrl;
  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper mapper = new ObjectMapper();

  @TempDir
  Path dataDir;

  @BeforeAll
  static void initAll() {
    TestDatabaseFactory.init();
  }

  @BeforeEach
  void setUp() throws Exception {
    ServerConfig config = new ServerConfig("127.0.0.1", 0, dataDir, null, null, null);
    server = TestDatabaseFactory.start(config);
    baseUrl = "http://localhost:" + server.port();
    bootstrapSuperuser();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.close();
    }
  }

  private void bootstrapSuperuser() throws Exception {
    HttpRequest bootstrap =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/bootstrap/superuser"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"email\":\"admin@example.com\",\"password\":\"Password_123\"}"))
            .build();
    httpClient.send(bootstrap, HttpResponse.BodyHandlers.ofString());
  }

  private String getSuperuserToken() throws Exception {
    HttpRequest login =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/collections/_superusers/auth-with-password"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"identity\":\"admin@example.com\",\"password\":\"Password_123\"}"))
            .build();
    HttpResponse<String> response = httpClient.send(login, HttpResponse.BodyHandlers.ofString());
    return mapper.readTree(response.body()).get("token").asText();
  }

  @Test
  void testDeleteLogsEndpointSecurityAndBehavior() throws Exception {
    // 1. Anonymous DELETE -> 401
    HttpRequest anonReq =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/logs"))
            .DELETE()
            .build();
    HttpResponse<String> anonRes = httpClient.send(anonReq, HttpResponse.BodyHandlers.ofString());
    assertEquals(401, anonRes.statusCode());

    // 2. Superuser DELETE -> 204
    String token = getSuperuserToken();
    HttpRequest superuserDeleteReq =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/logs"))
            .header("Authorization", "Bearer " + token)
            .DELETE()
            .build();
    HttpResponse<String> superuserDeleteRes =
        httpClient.send(superuserDeleteReq, HttpResponse.BodyHandlers.ofString());
    assertEquals(204, superuserDeleteRes.statusCode());

    // 3. GET /api/logs should now return 0 items
    HttpRequest listReq =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/logs"))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
    HttpResponse<String> listRes = httpClient.send(listReq, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, listRes.statusCode());
    JsonNode listBody = mapper.readTree(listRes.body());
    assertEquals(0, listBody.get("totalItems").asInt());

    // 4. Repeated DELETE is idempotent (returns 204)
    HttpResponse<String> repeatRes =
        httpClient.send(superuserDeleteReq, HttpResponse.BodyHandlers.ofString());
    assertEquals(204, repeatRes.statusCode());
  }

  @Test
  void testLogSettingsAndRanges() throws Exception {
    String token = getSuperuserToken();

    // 1. GET settings returns default maxDataSize = 0 and logAuthId = false
    HttpRequest getReq =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/settings"))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
    HttpResponse<String> getRes = httpClient.send(getReq, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, getRes.statusCode());
    JsonNode settingsNode = mapper.readTree(getRes.body());
    JsonNode logsNode = settingsNode.get("logs");
    assertNotNull(logsNode);
    assertEquals(0, logsNode.get("maxDataSize").asLong());
    assertEquals(false, logsNode.get("logAuthId").asBoolean());
    assertEquals(5, logsNode.get("maxDays").asLong());
    assertEquals(0, logsNode.get("minLevel").asLong());

    // 2. PATCH settings with valid maxDataSize, negative minLevel, and large maxDays
    String patchJson = "{\"logs\":{\"maxDataSize\":4096,\"minLevel\":-4,\"maxDays\":999999,\"logAuthId\":true}}";
    HttpRequest patchReq =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/settings"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(patchJson))
            .build();
    HttpResponse<String> patchRes = httpClient.send(patchReq, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, patchRes.statusCode());
    JsonNode patchedLogs = mapper.readTree(patchRes.body()).get("logs");
    assertEquals(4096, patchedLogs.get("maxDataSize").asLong());
    assertEquals(-4, patchedLogs.get("minLevel").asLong());
    assertEquals(999999, patchedLogs.get("maxDays").asLong());
    assertEquals(true, patchedLogs.get("logAuthId").asBoolean());
  }
}
