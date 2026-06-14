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
    private String lastRefreshAuthorization;
    private String lastFilesAuthorization;
    private String lastBody;
    private String lastAuthBody;
    private String lastQuery;
    private String lastCollectionsQuery;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/collections", this::handleCollections);
        server.createContext("/api/collections/posts/records", this::handlePosts);
        server.createContext("/api/collections/users/auth-with-password", this::handleAuth);
        server.createContext("/api/collections/users/auth-refresh", this::handleAuthRefresh);
        server.createContext("/api/files/token", this::handleFileToken);
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
        JsonNode record = client.collection("posts").create(Map.of("title", "created"), RecordQuery.builder()
                .expand("author")
                .fields("id,title")
                .build());

        assertEquals("new-id", record.get("id").asText());
        assertTrue(lastBody.contains("\"title\":\"created\""));
        assertTrue(lastQuery.contains("expand=author"));
        assertTrue(lastQuery.contains("fields=id%2Ctitle"));
    }

    @Test
    void updateRecordBuildsQueryOptions() {
        JsonNode record = client.collection("posts").update("abc123", Map.of("title", "updated"), RecordQuery.builder()
                .fields("id,title")
                .build());

        assertEquals("abc123", record.get("id").asText());
        assertTrue(lastBody.contains("\"title\":\"updated\""));
        assertTrue(lastQuery.contains("fields=id%2Ctitle"));
    }

    @Test
    void listCollectionsBuildsQueryOptions() {
        JsonNode collections = client.collections().list(ListOptions.builder()
                .page(1)
                .perPage(10)
                .sort("-name")
                .filter("type = 'base'")
                .fields("id,name")
                .build());

        assertEquals(1, collections.get("totalItems").asInt());
        assertEquals("posts", collections.get("items").get(0).get("name").asText());
        assertTrue(lastCollectionsQuery.contains("page=1"));
        assertTrue(lastCollectionsQuery.contains("perPage=10"));
        assertTrue(lastCollectionsQuery.contains("sort=-name"));
        assertTrue(lastCollectionsQuery.contains("filter=type%20%3D%20%27base%27"));
        assertTrue(lastCollectionsQuery.contains("fields=id%2Cname"));
    }

    @Test
    void getCollectionBuildsFieldsQueryOption() {
        JsonNode collection = client.collections().getOne("posts", ListOptions.builder()
                .fields("id,name")
                .build());

        assertEquals("posts", collection.get("name").asText());
        assertTrue(lastCollectionsQuery.contains("fields=id%2Cname"));
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
    void authRefreshStoresNewBearerTokenForLaterRequests() {
        client.authStore().save("old-token", null);

        AuthResponse response = client.collection("users").authRefresh();
        client.collection("posts").list();

        assertEquals("refresh-token", response.token());
        assertEquals("Bearer old-token", lastRefreshAuthorization);
        assertEquals("Bearer refresh-token", lastAuthorization);
    }

    @Test
    void fileTokenUsesCurrentBearerToken() {
        client.authStore().save("auth-token", null);

        String token = client.files().getToken();

        assertEquals("file-token", token);
        assertEquals("Bearer auth-token", lastFilesAuthorization);
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
        if ("PATCH".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 200, """
                    {"id":"abc123","title":"updated"}
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

    private void handleCollections(HttpExchange exchange) throws IOException {
        lastCollectionsQuery = exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery();
        if (exchange.getRequestURI().getPath().endsWith("/posts")) {
            sendJson(exchange, 200, """
                    {"id":"pbc_posts","name":"posts"}
                    """);
            return;
        }

        sendJson(exchange, 200, """
                {
                  "page": 1,
                  "perPage": 10,
                  "totalItems": 1,
                  "totalPages": 1,
                  "items": [
                    {"id": "pbc_posts", "name": "posts"}
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

    private void handleAuthRefresh(HttpExchange exchange) throws IOException {
        lastRefreshAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        sendJson(exchange, 200, """
                {
                  "token": "refresh-token",
                  "record": {"id": "user-id", "email": "demo@example.com"},
                  "meta": null
                }
                """);
    }

    private void handleFileToken(HttpExchange exchange) throws IOException {
        lastFilesAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        sendJson(exchange, 200, """
                {"token":"file-token"}
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
