package io.github.jackbaozz.pocketbase.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OAuth2FailuresTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private LocalPocketBase server;

    @TempDir
    Path dataDir;

    @BeforeEach
    void setUp() throws Exception {
        ServerConfig config = new ServerConfig("127.0.0.1", 0, dataDir, null, null, null);
        TestDatabaseFactory.init();
        server = LocalPocketBase.start(config);
        bootstrapSuperuser();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void oidcProviderRequiresAuthAndTokenEndpoints() throws Exception {
        String token = loginToken();

        HttpResponse<String> response = rawRequest("POST", "/api/collections", token, Map.of(
                "name", "bad_oidc_users",
                "type", "auth",
                "oauth2", Map.of(
                        "enabled", true,
                        "providers", List.of(Map.of(
                                "name", "oidc",
                                "clientId", "client-123"
                        ))
                )
        ));

        assertEquals(400, response.statusCode());
        assertFieldError(response, "OIDC requires authURL and tokenURL.", "authURL", "validation_required", "Cannot be blank.");
        assertFieldError(response, "OIDC requires authURL and tokenURL.", "tokenURL", "validation_required", "Cannot be blank.");
    }

    @Test
    void appleAuthMethodsIncludeProviderSpecificFormPostMode() throws Exception {
        String token = loginToken();
        request("POST", "/api/collections", token, Map.of(
                "name", "apple_users",
                "type", "auth",
                "oauth2", Map.of(
                        "enabled", true,
                        "providers", List.of(Map.of(
                                "name", "apple",
                                "clientId", "apple-client",
                                "clientSecret", "apple-secret",
                                "authURL", "https://appleid.apple.com/auth/authorize",
                                "tokenURL", "https://appleid.apple.com/auth/token",
                                "scopes", List.of("name", "email")
                        ))
                )
        ));

        JsonNode methods = request("GET", "/api/collections/apple_users/auth-methods", null, null);
        String authURL = methods.get("oauth2").get("providers").get(0).get("authURL").asText();
        assertTrue(authURL.contains("response_mode=form_post"));
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

    private void assertFieldError(
            HttpResponse<String> response,
            String message,
            String field,
            String code,
            String fieldMessage
    ) throws Exception {
        JsonNode body = mapper.readTree(response.body());
        assertEquals(400, body.get("status").asInt());
        assertFalse(body.has("code"));
        assertEquals(message, body.get("message").asText());
        JsonNode fieldError = body.get("data").get(field);
        assertEquals(code, fieldError.get("code").asText());
        assertEquals(fieldMessage, fieldError.get("message").asText());
    }
}
