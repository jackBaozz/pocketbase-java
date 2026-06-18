package io.github.jackbaozz.pocketbase.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.client.BatchResponse;
import io.github.jackbaozz.pocketbase.client.PocketBaseClient;
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

class BatchSmokeTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private LocalPocketBase server;
    private PocketBaseClient client;

    @TempDir
    Path dataDir;

    @BeforeEach
    void setUp() throws Exception {
        ServerConfig config = new ServerConfig("127.0.0.1", 0, dataDir, null, null);
        server = LocalPocketBase.start(config);
        client = PocketBaseClient.builder("http://localhost:" + server.port()).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void testBatchRequest() throws Exception {
        bootstrapSuperuser();
        String token = loginToken();
        request("POST", "/api/collections", token, Map.of(
                "name", "batch_smoke_posts",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(Map.of("name", "title", "type", "text", "required", true))
        ));
        client = PocketBaseClient.builder(server.baseUrl()).bearerToken(token).build();

        BatchResponse response = client.batch()
                .addRequest(Map.of(
                        "method", "POST",
                        "url", "/api/collections/batch_smoke_posts/records",
                        "body", Map.of("id", "batch_smoke_one", "title", "created")
                ))
                .send();

        assertEquals(1, response.responses().size());
        assertEquals(200, response.responses().get(0).status());
        assertEquals("batch_smoke_one", response.responses().get(0).body().get("id").asText());
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
