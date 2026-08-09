package io.github.jackbaozz.pocketbase.server;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthActionPersistenceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient http = HttpClient.newHttpClient();
  private LocalPocketBase server;

  @TempDir
  Path dataDir;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.close();
    }
  }

  /**
   * These helpers inspect the local SQLite file (and the SMTP outbox JSON) directly, so the suite is
   * SQLite-only. Follow the matrix `-Dstorage=` value instead of forcing sqlite, otherwise the
   * mysql/postgresql CI jobs appear red for an intentionally sqlite-bound test.
   */
  private void assumeSqliteStorage() {
    String storage = System.getProperty("storage", "sqlite").trim().toLowerCase(Locale.ROOT);
    Assumptions.assumeTrue(
        storage.isEmpty() || "sqlite".equals(storage),
        () -> "AuthActionPersistenceTest is SQLite-file specific; current storage=" + storage);
  }

  @Test
  void relationalAuthActionTokensArePersistedAndConsumedOnce() throws Exception {
    assumeSqliteStorage();
    server = LocalPocketBase.start(new ServerConfig("127.0.0.1", 0, dataDir, null, null, null));
    bootstrapSuperuser();
    String superuser = loginSuperuser();

    request(
        "POST",
        "/api/collections",
        superuser,
        Map.of(
            "name", "reset_users",
            "type", "auth",
            "passwordResetToken", Map.of("duration", 120)));
    request(
        "POST",
        "/api/collections/reset_users/records",
        superuser,
        Map.of(
            "email", "reset@example.com",
            "password", "Secret_123",
            "passwordConfirm", "Secret_123",
            "verified", true));
    request(
        "POST",
        "/api/collections/reset_users/request-password-reset",
        null,
        Map.of("email", "reset@example.com"));

    String token = authRequestToken("passwordReset", "reset@example.com");
    assertEquals(1, authRequestCount(token));

    HttpResponse<String> rejected =
        rawRequest(
            "POST",
            "/api/collections/reset_users/confirm-password-reset",
            null,
            Map.of(
                "token", token,
                "password", "NewSecret_123",
                "passwordConfirm", "does-not-match"));
    assertEquals(400, rejected.statusCode());
    assertEquals(
        1, authRequestCount(token), "failed auth updates must not consume the one-time token");

    request(
        "POST",
        "/api/collections/reset_users/confirm-password-reset",
        null,
        Map.of(
            "token", token,
            "password", "NewSecret_123",
            "passwordConfirm", "NewSecret_123"));
    assertEquals(0, authRequestCount(token));

    HttpResponse<String> reused =
        rawRequest(
            "POST",
            "/api/collections/reset_users/confirm-password-reset",
            null,
            Map.of(
                "token", token,
                "password", "AnotherSecret_123",
                "passwordConfirm", "AnotherSecret_123"));
    assertEquals(400, reused.statusCode());
    assertEquals(
        "Invalid or expired token.", mapper.readTree(reused.body()).get("message").asText());
  }

  @Test
  void relationalOtpRejectsAndDeletesExpiredCodes() throws Exception {
    assumeSqliteStorage();
    server = LocalPocketBase.start(new ServerConfig("127.0.0.1", 0, dataDir, null, null, null));
    bootstrapSuperuser();
    String superuser = loginSuperuser();

    request(
        "POST",
        "/api/collections",
        superuser,
        Map.of(
            "name", "otp_expiry_users",
            "type", "auth",
            "otp", Map.of("enabled", true, "duration", 60, "length", 6)));
    request(
        "POST",
        "/api/collections/otp_expiry_users/records",
        superuser,
        Map.of(
            "email", "otp-expiry@example.com",
            "password", "Secret_123",
            "passwordConfirm", "Secret_123",
            "verified", false));

    JsonNode otp =
        request(
            "POST",
            "/api/collections/otp_expiry_users/request-otp",
            null,
            Map.of("email", "otp-expiry@example.com"));
    String otpId = otp.get("otpId").asText();
    String password = otpRequestPassword("otp-expiry@example.com", otpId);
    expireOtp(otpId);

    HttpResponse<String> expired =
        rawRequest(
            "POST",
            "/api/collections/otp_expiry_users/auth-with-otp",
            null,
            Map.of(
                "otpId", otpId,
                "password", password));
    assertEquals(400, expired.statusCode());
    assertEquals(
        "Invalid or expired OTP.", mapper.readTree(expired.body()).get("message").asText());
    assertEquals(0, otpCount(otpId));
  }

  private void bootstrapSuperuser() throws Exception {
    request(
        "POST",
        "/api/bootstrap/superuser",
        null,
        Map.of(
            "email", "root@example.com",
            "password", "Secret_123"));
  }

  private String loginSuperuser() throws Exception {
    JsonNode auth =
        request(
            "POST",
            "/api/collections/_superusers/auth-with-password",
            null,
            Map.of(
                "identity", "root@example.com",
                "password", "Secret_123"));
    return auth.get("token").asText();
  }

  private String authRequestToken(String type, String email) throws Exception {
    try (var conn =
        DriverManager.getConnection(
            "jdbc:sqlite:" + dataDir.resolve("pocketbase.db").toAbsolutePath());
        var ps =
            conn.prepareStatement(
                "SELECT token FROM _authRequests WHERE type = ? AND email = ? ORDER BY created DESC LIMIT 1")) {
      ps.setString(1, type);
      ps.setString(2, email);
      try (var rs = ps.executeQuery()) {
        if (rs.next()) {
          return rs.getString("token");
        }
      }
    }
    throw new AssertionError("No auth request token found for " + type + " / " + email);
  }

  private int authRequestCount(String token) throws Exception {
    try (var conn =
        DriverManager.getConnection(
            "jdbc:sqlite:" + dataDir.resolve("pocketbase.db").toAbsolutePath());
        var ps = conn.prepareStatement("SELECT COUNT(*) FROM _authRequests WHERE token = ?")) {
      ps.setString(1, token);
      try (var rs = ps.executeQuery()) {
        return rs.next() ? rs.getInt(1) : 0;
      }
    }
  }

  private String otpRequestPassword(String email, String otpId) throws Exception {
    JsonNode requests =
        mapper.readTree(
            Files.readString(dataDir.resolve("auth_requests.json"), StandardCharsets.UTF_8));
    for (int i = requests.size() - 1; i >= 0; i--) {
      JsonNode request = requests.get(i);
      if ("otp".equals(request.path("type").asText())
          && email.equalsIgnoreCase(request.path("email").asText())
          && otpId.equals(request.path("otpId").asText())) {
        return request.path("password").asText();
      }
    }
    throw new AssertionError("No OTP outbox entry found for " + email + " / " + otpId);
  }

  private void expireOtp(String otpId) throws Exception {
    try (var conn =
        DriverManager.getConnection(
            "jdbc:sqlite:" + dataDir.resolve("pocketbase.db").toAbsolutePath());
        var ps = conn.prepareStatement("UPDATE _otps SET created = ? WHERE id = ?")) {
      ps.setString(1, "1970-01-01T00:00:00Z");
      ps.setString(2, otpId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private int otpCount(String otpId) throws Exception {
    try (var conn =
        DriverManager.getConnection(
            "jdbc:sqlite:" + dataDir.resolve("pocketbase.db").toAbsolutePath());
        var ps = conn.prepareStatement("SELECT COUNT(*) FROM _otps WHERE id = ?")) {
      ps.setString(1, otpId);
      try (var rs = ps.executeQuery()) {
        return rs.next() ? rs.getInt(1) : 0;
      }
    }
  }

  private JsonNode request(String method, String path, String token, Object body) throws Exception {
    HttpResponse<String> response = rawRequest(method, path, token, body);
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new AssertionError(response.statusCode() + " " + response.body());
    }
    return response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
  }

  private HttpResponse<String> rawRequest(String method, String path, String token, Object body)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
            .header("Accept", "application/json");
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder.header("Content-Type", "application/json");
      builder.method(
          method,
          HttpRequest.BodyPublishers.ofString(
              mapper.writeValueAsString(body), StandardCharsets.UTF_8));
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }
}
