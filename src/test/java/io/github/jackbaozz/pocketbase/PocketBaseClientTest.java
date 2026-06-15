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
    private String lastSettingsAuthorization;
    private String lastLogsAuthorization;
    private String lastBody;
    private String lastAuthBody;
    private String lastSettingsBody;
    private String lastCollectionsBody;
    private String lastQuery;
    private String lastCollectionsQuery;
    private String lastCollectionsPath;
    private String lastCollectionsMethod;
    private String lastSettingsPath;
    private String lastLogsPath;
    private String lastLogsQuery;
    private String lastCronsAuthorization;
    private String lastCronsPath;
    private String lastCronsMethod;
    private String lastSqlAuthorization;
    private String lastSqlPath;
    private String lastSqlBody;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/collections", this::handleCollections);
        server.createContext("/api/collections/posts/records", this::handlePosts);
        server.createContext("/api/collections/users/auth-with-password", this::handleAuth);
        server.createContext("/api/collections/users/auth-refresh", this::handleAuthRefresh);
        server.createContext("/api/files/token", this::handleFileToken);
        server.createContext("/api/settings", this::handleSettings);
        server.createContext("/api/logs", this::handleLogs);
        server.createContext("/api/crons", this::handleCrons);
        server.createContext("/api/sql", this::handleSql);
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
    void collectionMetaMethodsUseOfficialRoutes() {
        client.authStore().save("super-token", null);

        JsonNode scaffolds = client.collections().scaffolds();
        assertEquals("auth", scaffolds.get("auth").get("type").asText());
        assertEquals("/api/collections/meta/scaffolds", lastCollectionsPath);

        JsonNode providers = client.collections().oauth2Providers();
        assertEquals("github", providers.get(0).get("name").asText());
        assertEquals("/api/collections/meta/oauth2-providers", lastCollectionsPath);
    }

    @Test
    void collectionImportAndTruncateUseOfficialRoutes() {
        client.authStore().save("super-token", null);

        client.collections().importCollections(Map.of(
                "deleteMissing", true,
                "collections", java.util.List.of(Map.of("name", "posts"))
        ));
        assertEquals("PUT", lastCollectionsMethod);
        assertEquals("/api/collections/import", lastCollectionsPath);
        assertTrue(lastCollectionsBody.contains("\"deleteMissing\":true"));

        client.collections().truncate("posts");
        assertEquals("DELETE", lastCollectionsMethod);
        assertEquals("/api/collections/posts/truncate", lastCollectionsPath);
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
    void otpEndpointsBuildOfficialRoutesAndPersistBearerToken() {
        JsonNode otpRequest = client.collection("users").requestOtp("demo@example.com");
        AuthResponse response = client.collection("users").authWithOtp("otp_123", "123456");

        assertEquals("otp_123", otpRequest.get("otpId").asText());
        assertEquals("jwt-token", response.token());
        assertEquals("/api/collections/users/auth-with-otp", lastCollectionsPath);
        assertTrue(lastCollectionsBody.contains("\"otpId\":\"otp_123\""));
        assertTrue(lastCollectionsBody.contains("\"password\":\"123456\""));
        assertTrue(client.authStore().isValid());
    }

    @Test
    void oauth2EndpointBuildsOfficialRouteAndStoresBearerToken() {
        AuthResponse response = client.collection("users").authWithOAuth2(
                "oidc",
                "code-123",
                "https://example.com/callback",
                "verifier-xyz",
                Map.of("name", "Demo"),
                RecordQuery.defaults()
        );

        assertEquals("jwt-token", response.token());
        assertEquals("/api/collections/users/auth-with-oauth2", lastCollectionsPath);
        assertTrue(lastCollectionsBody.contains("\"provider\":\"oidc\""));
        assertTrue(lastCollectionsBody.contains("\"code\":\"code-123\""));
        assertTrue(lastCollectionsBody.contains("\"redirectURL\":\"https://example.com/callback\""));
        assertTrue(lastCollectionsBody.contains("\"codeVerifier\":\"verifier-xyz\""));
        assertTrue(client.authStore().isValid());
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
    void settingsServiceBuildsOfficialSettingsRoutes() {
        client.authStore().save("super-token", null);

        JsonNode settings = client.settings().get("meta.appName");
        JsonNode updated = client.settings().update(Map.of("meta", Map.of("appName", "Demo")));
        client.settings().testEmail("dev@example.com", "verification");
        client.settings().testS3("storage");
        String secret = client.settings().generateAppleClientSecret(Map.of(
                "clientId", "com.example.service",
                "teamId", "TEAMID1234",
                "keyId", "KEYID12345",
                "privateKey", "-----BEGIN PRIVATE KEY-----\\nabc\\n-----END PRIVATE KEY-----",
                "duration", 3600
        ));

        assertEquals("pocketbase-java", settings.get("meta").get("appName").asText());
        assertEquals("Demo", updated.get("meta").get("appName").asText());
        assertEquals("apple-secret", secret);
        assertEquals("Bearer super-token", lastSettingsAuthorization);
        assertEquals("/api/settings/apple/generate-client-secret", lastSettingsPath);
        assertTrue(lastSettingsBody.contains("\"duration\":3600"));
    }

    @Test
    void logsServiceBuildsListViewAndStatsRoutes() {
        client.authStore().save("super-token", null);

        RecordList logs = client.logs().list(ListOptions.builder()
                .page(2)
                .perPage(10)
                .filter("data.status >= 400")
                .sort("-created")
                .build());
        JsonNode log = client.logs().getOne("log123");
        JsonNode stats = client.logs().stats(Map.of("filter", "data.status >= 400"));

        assertEquals(1, logs.totalItems());
        assertEquals("log123", logs.items().get(0).get("id").asText());
        assertEquals("log123", log.get("id").asText());
        assertEquals(1, stats.get(0).get("total").asInt());
        assertEquals("Bearer super-token", lastLogsAuthorization);
        assertEquals("/api/logs/stats", lastLogsPath);
        assertTrue(lastLogsQuery.contains("filter=data.status%20%3E%3D%20400"));
    }

    @Test
    void cronsServiceBuildsListAndRunRoutes() {
        client.authStore().save("super-token", null);

        JsonNode crons = client.crons().list();
        client.crons().run("__pbLogsCleanup__");

        assertEquals("__pbLogsCleanup__", crons.get(0).get("id").asText());
        assertEquals("Bearer super-token", lastCronsAuthorization);
        assertEquals("POST", lastCronsMethod);
        assertEquals("/api/crons/__pbLogsCleanup__", lastCronsPath);
    }

    @Test
    void sqlServiceBuildsOfficialRunRoute() {
        client.authStore().save("super-token", null);

        JsonNode result = client.sql().run("select 1");

        assertEquals("1", result.get("rows").get(0).get(0).asText());
        assertEquals("Bearer super-token", lastSqlAuthorization);
        assertEquals("/api/sql", lastSqlPath);
        assertTrue(lastSqlBody.contains("\"query\":\"select 1\""));
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
        lastCollectionsPath = exchange.getRequestURI().getPath();
        lastCollectionsMethod = exchange.getRequestMethod();
        lastCollectionsQuery = exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery();
        lastCollectionsBody = readBody(exchange);
        if (lastCollectionsPath.endsWith("/import") && "PUT".equals(lastCollectionsMethod)) {
            sendNoContent(exchange);
            return;
        }
        if (lastCollectionsPath.endsWith("/truncate") && "DELETE".equals(lastCollectionsMethod)) {
            sendNoContent(exchange);
            return;
        }
        if (lastCollectionsPath.endsWith("/meta/scaffolds")) {
            sendJson(exchange, 200, """
                    {
                      "base": {"type": "base", "fields": []},
                      "auth": {"type": "auth", "fields": [{"name": "email"}]},
                      "view": {"type": "view", "fields": [], "viewQuery": ""}
                    }
                    """);
            return;
        }
        if (lastCollectionsPath.endsWith("/meta/oauth2-providers")) {
            sendJson(exchange, 200, """
                    [
                      {"name": "github", "displayName": "GitHub", "logo": ""},
                      {"name": "google", "displayName": "Google", "logo": ""}
                    ]
                    """);
            return;
        }
        if (lastCollectionsPath.endsWith("/request-otp")) {
            sendJson(exchange, 200, """
                    {"otpId":"otp_123"}
                    """);
            return;
        }
        if (lastCollectionsPath.endsWith("/auth-with-otp")) {
            sendJson(exchange, 200, """
                    {
                      "token": "jwt-token",
                      "record": {"id": "user-id", "email": "demo@example.com"},
                      "meta": null
                    }
                    """);
            return;
        }
        if (lastCollectionsPath.endsWith("/auth-with-oauth2")) {
            sendJson(exchange, 200, """
                    {
                      "token": "jwt-token",
                      "record": {"id": "user-id", "email": "demo@example.com"},
                      "meta": {"isNew": true}
                    }
                    """);
            return;
        }
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

    private void handleSettings(HttpExchange exchange) throws IOException {
        lastSettingsAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        lastSettingsPath = exchange.getRequestURI().getPath();
        lastSettingsBody = readBody(exchange);
        if (lastSettingsPath.endsWith("/test/email") || lastSettingsPath.endsWith("/test/s3")) {
            sendNoContent(exchange);
            return;
        }
        if (lastSettingsPath.endsWith("/apple/generate-client-secret")) {
            sendJson(exchange, 200, """
                    {"secret":"apple-secret"}
                    """);
            return;
        }
        if ("PATCH".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 200, """
                    {"meta":{"appName":"Demo"}}
                    """);
            return;
        }
        sendJson(exchange, 200, """
                {"meta":{"appName":"pocketbase-java"}}
                """);
    }

    private void handleLogs(HttpExchange exchange) throws IOException {
        lastLogsAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        lastLogsPath = exchange.getRequestURI().getPath();
        lastLogsQuery = exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery();
        if (lastLogsPath.endsWith("/stats")) {
            sendJson(exchange, 200, """
                    [{"date":"2026-06-14 10:00:00","total":1}]
                    """);
            return;
        }
        if (lastLogsPath.endsWith("/log123")) {
            sendJson(exchange, 200, """
                    {"id":"log123","message":"GET /api/test"}
                    """);
            return;
        }
        sendJson(exchange, 200, """
                {
                  "page": 2,
                  "perPage": 10,
                  "totalItems": 1,
                  "totalPages": 1,
                  "items": [
                    {"id": "log123", "message": "GET /api/test"}
                  ]
                }
                """);
    }

    private void handleCrons(HttpExchange exchange) throws IOException {
        lastCronsAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        lastCronsPath = exchange.getRequestURI().getPath();
        lastCronsMethod = exchange.getRequestMethod();
        if ("POST".equals(lastCronsMethod)) {
            sendNoContent(exchange);
            return;
        }
        sendJson(exchange, 200, """
                [
                  {"id":"__pbLogsCleanup__","expression":"0 */6 * * *"},
                  {"id":"__pbDBOptimize__","expression":"0 0 * * *"}
                ]
                """);
    }

    private void handleSql(HttpExchange exchange) throws IOException {
        lastSqlAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        lastSqlPath = exchange.getRequestURI().getPath();
        lastSqlBody = readBody(exchange);
        sendJson(exchange, 200, """
                {
                  "execTime": 0,
                  "affectedRows": 0,
                  "columns": [{"name":"1","type":"","nullable":true}],
                  "rows": [["1"]]
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

    private static void sendNoContent(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }
}
