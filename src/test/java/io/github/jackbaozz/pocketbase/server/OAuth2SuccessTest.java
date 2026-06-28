package io.github.jackbaozz.pocketbase.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.testutils.MockOAuth2Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OAuth2SuccessTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private LocalPocketBase server;
    private MockOAuth2Server mockServer;

    @TempDir
    Path dataDir;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockOAuth2Server(0);
        mockServer.start();

        ServerConfig config = new ServerConfig("127.0.0.1", 0, dataDir, null, null);
        TestDatabaseFactory.init();
        server = LocalPocketBase.start(config);
        bootstrapSuperuser();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    @Test
    void authWithOAuth2FlowLinksAccounts() throws Exception {
        String token = loginToken();
        
        // Setup collection
        request("POST", "/api/collections", token, Map.of(
                "name", "users",
                "type", "auth",
                "oauth2", Map.of(
                        "enabled", true,
                        "providers", List.of(Map.of(
                                "name", "oidc",
                                "clientId", "client-123",
                                "clientSecret", "secret-123",
                                "authURL", mockServer.baseUrl() + "/auth",
                                "tokenURL", mockServer.baseUrl() + "/token",
                                "userInfoURL", mockServer.baseUrl() + "/userinfo"
                        ))
                )
        ));

        // Create a user first to test linking
        request("POST", "/api/collections/users/records", token, Map.of(
                "email", "mock@example.com",
                "password", "password123",
                "passwordConfirm", "password123"
        ));

        // Test OAuth2
        JsonNode response = request("POST", "/api/collections/users/auth-with-oauth2", null, Map.of(
                "provider", "oidc",
                "code", "mock_code_123",
                "codeVerifier", "verifier123",
                "redirectURL", "http://localhost/redirect"
        ));

        assertNotNull(response.get("token"));
        assertEquals("mock@example.com", response.get("record").get("email").asText());
        
        assertEquals("Mock User", response.get("meta").get("name").asText());
    }

    private void bootstrapSuperuser() throws Exception {
        request("POST", "/api/bootstrap/superuser", null, Map.of(
                "email", "root@example.com",
                "password", "secret123"
        ));
    }

    private String loginToken() throws Exception {
        JsonNode auth = request("POST", "/api/collections/_superusers/auth-with-password", null, Map.of(
                "identity", "root@example.com",
                "password", "secret123"
        ));
        return auth.get("token").asText();
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
