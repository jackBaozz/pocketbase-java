package io.github.jackbaozz.pocketbase.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuthActionPersistenceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private LocalPocketBase server;
    private String previousStorage;

    @TempDir
    Path dataDir;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
        if (previousStorage == null) {
            System.clearProperty("storage");
        } else {
            System.setProperty("storage", previousStorage);
        }
    }

    @Test
    void relationalAuthActionTokensArePersistedAndConsumedOnce() throws Exception {
        previousStorage = System.getProperty("storage");
        System.setProperty("storage", "sqlite");
        server = LocalPocketBase.start(new ServerConfig("127.0.0.1", 0, dataDir, null, null, null));
        bootstrapSuperuser();
        String superuser = loginSuperuser();

        request("POST", "/api/collections", superuser, Map.of(
                "name", "reset_users",
                "type", "auth",
                "passwordResetToken", Map.of("duration", 120)
        ));
        request("POST", "/api/collections/reset_users/records", superuser, Map.of(
                "email", "reset@example.com",
                "password", "secret123",
                "passwordConfirm", "secret123",
                "verified", true
        ));
        request("POST", "/api/collections/reset_users/request-password-reset", null, Map.of(
                "email", "reset@example.com"
        ));

        String token = authRequestToken("passwordReset", "reset@example.com");
        assertEquals(1, authRequestCount(token));

        request("POST", "/api/collections/reset_users/confirm-password-reset", null, Map.of(
                "token", token,
                "password", "newsecret123",
                "passwordConfirm", "newsecret123"
        ));
        assertEquals(0, authRequestCount(token));

        HttpResponse<String> reused = rawRequest("POST", "/api/collections/reset_users/confirm-password-reset", null, Map.of(
                "token", token,
                "password", "anothersecret123",
                "passwordConfirm", "anothersecret123"
        ));
        assertEquals(400, reused.statusCode());
        assertEquals("Invalid or expired token.", mapper.readTree(reused.body()).get("message").asText());
    }

    private void bootstrapSuperuser() throws Exception {
        request("POST", "/api/bootstrap/superuser", null, Map.of(
                "email", "root@example.com",
                "password", "secret123"
        ));
    }

    private String loginSuperuser() throws Exception {
        JsonNode auth = request("POST", "/api/collections/_superusers/auth-with-password", null, Map.of(
                "identity", "root@example.com",
                "password", "secret123"
        ));
        return auth.get("token").asText();
    }

    private String authRequestToken(String type, String email) throws Exception {
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("pocketbase.db").toAbsolutePath());
             var ps = conn.prepareStatement("SELECT token FROM _authRequests WHERE type = ? AND email = ? ORDER BY created DESC LIMIT 1")) {
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
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("pocketbase.db").toAbsolutePath());
             var ps = conn.prepareStatement("SELECT COUNT(*) FROM _authRequests WHERE token = ?")) {
            ps.setString(1, token);
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

    private HttpResponse<String> rawRequest(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
                .header("Accept", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
