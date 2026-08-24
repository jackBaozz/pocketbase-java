package io.github.jackbaozz.pocketbase.server.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jackbaozz.pocketbase.server.LocalPocketBase;
import io.github.jackbaozz.pocketbase.server.ServerConfig;
import io.github.jackbaozz.pocketbase.server.TestDatabaseFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class HttpFileSupportTest {

  private LocalPocketBase server;
  private String baseUrl;
  private final HttpClient httpClient = HttpClient.newHttpClient();

  @TempDir
  Path dataDir;

  @BeforeEach
  void setUp() throws Exception {
    ServerConfig config = new ServerConfig("127.0.0.1", 0, dataDir, null, null, null);
    TestDatabaseFactory.init();
    server = TestDatabaseFactory.start(config);
    baseUrl = "http://localhost:" + server.port();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.close();
    }
  }

  @Test
  void testCrossOriginOpenerPolicyHeaderOnAllResponses() throws Exception {
    // 1. Health (public JSON 200)
    HttpRequest healthReq = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/api/health")).GET().build();
    HttpResponse<String> healthRes = httpClient.send(healthReq, HttpResponse.BodyHandlers.ofString());
    assertEquals("same-origin", healthRes.headers().firstValue("Cross-Origin-Opener-Policy").orElse(null));

    // 2. 404 response
    HttpRequest notFoundReq = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/api/unknown-endpoint")).GET().build();
    HttpResponse<String> notFoundRes = httpClient.send(notFoundReq, HttpResponse.BodyHandlers.ofString());
    assertEquals("same-origin", notFoundRes.headers().firstValue("Cross-Origin-Opener-Policy").orElse(null));

    // 3. OPTIONS response (204)
    HttpRequest optionsReq = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/api/health")).method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
    HttpResponse<String> optionsRes = httpClient.send(optionsReq, HttpResponse.BodyHandlers.ofString());
    assertEquals("same-origin", optionsRes.headers().firstValue("Cross-Origin-Opener-Policy").orElse(null));

    // 4. Admin UI root (200)
    HttpRequest adminReq = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/_")).GET().build();
    HttpResponse<String> adminRes = httpClient.send(adminReq, HttpResponse.BodyHandlers.ofString());
    assertEquals("same-origin", adminRes.headers().firstValue("Cross-Origin-Opener-Policy").orElse(null));
  }

  @Test
  void testContentDispositionHeaderSanitization() {
    String input1 = "my file (1).txt";
    String sanitized1 = input1.replace("\\", "_").replace("\"", "_").replace("\r", "_").replace("\n", "_");
    assertEquals("my file (1).txt", sanitized1);

    String input2 = "malicious\"\r\nInjected: header\r\n.txt";
    String sanitized2 = input2.replace("\\", "_").replace("\"", "_").replace("\r", "_").replace("\n", "_");
    assertEquals("malicious___Injected: header__.txt", sanitized2);
    assertFalse(sanitized2.contains("\""));
    assertFalse(sanitized2.contains("\r"));
    assertFalse(sanitized2.contains("\n"));
  }
}
