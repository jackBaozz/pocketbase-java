package io.github.jackbaozz.pocketbase.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorsCompatibilityTest {
  @TempDir
  Path dataDir;
  private final HttpClient client = HttpClient.newHttpClient();

  private LocalPocketBase start(String... origins) throws Exception {
    return TestDatabaseFactory.start(new ServerConfig(
        "127.0.0.1", 0, dataDir, null, null, null, null, "sqlite", null, null, null, null,
        List.of(origins)));
  }

  private HttpResponse<String> request(LocalPocketBase server, String method, String path, String origin)
      throws Exception {
    var request = HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
        .method(method, HttpRequest.BodyPublishers.noBody());
    if (origin != null)
      request.header("Origin", origin);
    if ("OPTIONS".equals(method)) {
      request.header("Access-Control-Request-Method", "POST");
      request.header("Access-Control-Request-Headers", "Authorization, X-Custom-Header");
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void appliesDefaultAndConfiguredOriginsToSuccessAndErrorResponses() throws Exception {
    try (var server = start()) {
      assertEquals("*", request(server, "GET", "/api/health", "https://client.test")
          .headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
      assertTrue(request(server, "GET", "/api/health", null)
          .headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }
    try (var server = start("https://trusted.*.example.com")) {
      var allowed = request(server, "GET", "/api/health", "https://trusted.a.example.com");
      assertEquals(200, allowed.statusCode());
      assertEquals("https://trusted.a.example.com",
          allowed.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
      var denied = request(server, "GET", "/api/health", "https://evil.a.example.com");
      assertEquals(200, denied.statusCode());
      assertTrue(denied.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
      var error = request(server, "GET", "/api/settings", "https://trusted.a.example.com");
      assertEquals(401, error.statusCode());
      assertEquals("https://trusted.a.example.com",
          error.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
      assertTrue(allowed.headers().allValues("Vary").contains("Origin"));
    }
  }

  @Test
  void preflightReflectsRequestedHeadersAndDoesNotAuthorizeDisallowedOrigins() throws Exception {
    try (var server = start("https://admin.example.com")) {
      var allowed = request(server, "OPTIONS", "/api/settings", "https://admin.example.com");
      assertEquals(204, allowed.statusCode());
      assertEquals("", allowed.body());
      assertEquals("Authorization, X-Custom-Header",
          allowed.headers().firstValue("Access-Control-Allow-Headers").orElseThrow());
      assertTrue(allowed.headers().allValues("Vary").contains("Access-Control-Request-Headers"));
      var denied = request(server, "OPTIONS", "/api/settings", "https://attacker.test");
      assertEquals(204, denied.statusCode());
      assertTrue(denied.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
      assertTrue(denied.headers().firstValue("Access-Control-Allow-Headers").isEmpty());
    }
  }

  @Test
  void preflightsDoNotConsumeRateLimits() throws Exception {
    try (var server = start("https://admin.example.com")) {
      var settings = server.store().mapper().createObjectNode();
      settings.set("rateLimits", server.store().mapper().valueToTree(Map.of(
          "enabled", true, "rules", List.of(Map.of(
              "label", "/api/health", "audience", "@guest", "duration", 60, "maxRequests", 1)))));
      server.store().updateSettings(settings, Map.of());
      for (int i = 0; i < 3; i++) {
        assertEquals(204, request(server, "OPTIONS", "/api/health", "https://admin.example.com").statusCode());
      }
      assertEquals(200, request(server, "GET", "/api/health", "https://admin.example.com").statusCode());
      assertEquals(429, request(server, "GET", "/api/health", "https://admin.example.com").statusCode());
    }
  }
}
