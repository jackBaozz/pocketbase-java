package io.github.jackbaozz.pocketbase;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PocketBaseClientTest {
    private HttpServer server;
    private PocketBaseClient client;
    private String lastAuthorization;
    private String lastBody;
    private String lastAuthBody;
    private String lastQuery;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/collections/posts/records", this::handlePosts);
        server.createContext("/api/collections/users/auth-with-password", this::handleAuth);
        server.createContext("/api/collections/fail/records", this::handleFailure);
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = PocketBaseClient.builder(baseUrl).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void listRecordsBuildsQueryAndParsesResponse() {
        RecordList records = client.collection("posts").list(ListOptions.builder()
                .page(2)
                .perPage(5)
                .sort("-created")
                .filter("published = true")
                .build());

        assertEquals(2, records.page());
        assertEquals(1, records.items().size());
        assertEquals("hello", records.items().get(0).get("title").asText());
        assertTrue(lastQuery.contains("page=2"));
        assertTrue(lastQuery.contains("perPage=5"));
        assertTrue(lastQuery.contains("sort=-created"));
        assertTrue(lastQuery.contains("filter=published%20%3D%20true"));
    }

    @Test
    void createRecordSendsJsonBody() {
        JsonNode record = client.collection("posts").create(Map.of("title", "created"));

        assertEquals("new-id", record.get("id").asText());
        assertTrue(lastBody.contains("\"title\":\"created\""));
    }

    @Test
    void authWithPasswordStoresBearerTokenForLaterRequests() throws Exception {
        AuthResponse response = client.collection("users").authWithPassword("demo@example.com", "secret");
        client.collection("posts").list();

        JsonNode authBody = client.objectMapper().readTree(lastAuthBody);
        assertEquals("jwt-token", response.token());
        assertTrue(client.authStore().isValid());
        assertEquals("Bearer jwt-token", lastAuthorization);
        assertEquals("demo@example.com", authBody.get("identity").asText());
        assertEquals("secret", authBody.get("password").asText());
    }

    @Test
    void nonSuccessResponseThrowsPocketBaseException() {
        PocketBaseException exception = assertThrows(
                PocketBaseException.class,
                () -> client.collection("fail").list()
        );

        assertEquals(400, exception.statusCode());
        assertNotNull(exception.error());
        assertEquals("validation failed", exception.error().message());
    }

    private void handlePosts(HttpExchange exchange) throws IOException {
        lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        lastQuery = exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery();
        lastBody = readBody(exchange);

        if ("POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 200, """
                    {"id":"new-id","title":"created"}
                    """);
            return;
        }

        sendJson(exchange, 200, """
                {
                  "page": 2,
                  "perPage": 5,
                  "totalItems": 1,
                  "totalPages": 1,
                  "items": [
                    {"id": "abc123", "title": "hello"}
                  ]
                }
                """);
    }

    private void handleAuth(HttpExchange exchange) throws IOException {
        lastAuthBody = readBody(exchange);
        sendJson(exchange, 200, """
                {
                  "token": "jwt-token",
                  "record": {"id": "user-id", "email": "demo@example.com"},
                  "meta": null
                }
                """);
    }

    private void handleFailure(HttpExchange exchange) throws IOException {
        sendJson(exchange, 400, """
                {"status":400,"message":"validation failed","data":{"title":{"message":"required"}}}
                """);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
