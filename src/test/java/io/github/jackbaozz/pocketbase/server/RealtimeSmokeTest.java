package io.github.jackbaozz.pocketbase.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealtimeSmokeTest {
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
        if (client != null) {
            client.realtime().disconnect();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void testRealtimeSubscription() throws Exception {
        bootstrapSuperuser();
        String token = loginToken();
        request("POST", "/api/collections", token, Map.of(
                "name", "realtime_posts",
                "listRule", "",
                "viewRule", "",
                "createRule", "",
                "fields", List.of(Map.of("name", "title", "type", "text", "required", true))
        ));
        client = PocketBaseClient.builder(server.baseUrl()).bearerToken(token).build();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JsonNode> receivedEvent = new AtomicReference<>();

        client.realtime().subscribe("realtime_posts", data -> {
            receivedEvent.set(data);
            latch.countDown();
        });

        JsonNode created = client.collection("realtime_posts").create(Map.of("title", "hello realtime"));

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Realtime create event was not delivered.");
        assertNotNull(receivedEvent.get());
        assertEquals("create", receivedEvent.get().get("action").asText());
        assertEquals(created.get("id").asText(), receivedEvent.get().get("record").get("id").asText());
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
