package io.github.jackbaozz.pocketbase.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class RouteConformanceTest {

    private LocalPocketBase server;
    private String baseUrl;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path dataDir;

    @BeforeEach
    void setUp() throws Exception {
        ServerConfig config = new ServerConfig("127.0.0.1", 0, dataDir, null, null);
        server = LocalPocketBase.start(config);
        baseUrl = "http://localhost:" + server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private static class RouteInfo {
        String method;
        String path;
        boolean authRequired; // to determine if we should send a mock token or not for correct test scope

        RouteInfo(String method, String path) {
            this.method = method;
            this.path = path;
        }
    }

    // List of official routes from docs/SDP-PocketBase-Compatibility-Plan.md
    private final List<RouteInfo> officialRoutes = Arrays.asList(
            new RouteInfo("GET", "/api/settings"),
            new RouteInfo("PATCH", "/api/settings"),
            new RouteInfo("POST", "/api/settings/test/s3"),
            new RouteInfo("POST", "/api/settings/test/email"),
            new RouteInfo("POST", "/api/settings/apple/generate-client-secret"),

            new RouteInfo("GET", "/api/collections"),
            new RouteInfo("POST", "/api/collections"),
            new RouteInfo("GET", "/api/collections/mock_collection"),
            new RouteInfo("PATCH", "/api/collections/mock_collection"),
            new RouteInfo("DELETE", "/api/collections/mock_collection"),
            new RouteInfo("DELETE", "/api/collections/mock_collection/truncate"),
            new RouteInfo("PUT", "/api/collections/import"),
            new RouteInfo("GET", "/api/collections/meta/scaffolds"),
            new RouteInfo("GET", "/api/collections/meta/oauth2-providers"),
            new RouteInfo("POST", "/api/collections/meta/dry-run-view"),

            new RouteInfo("GET", "/api/collections/mock_collection/records"),
            new RouteInfo("POST", "/api/collections/mock_collection/records"),
            new RouteInfo("GET", "/api/collections/mock_collection/records/mock_id"),
            new RouteInfo("PATCH", "/api/collections/mock_collection/records/mock_id"),
            new RouteInfo("DELETE", "/api/collections/mock_collection/records/mock_id"),

            new RouteInfo("GET", "/api/oauth2-redirect"),
            new RouteInfo("POST", "/api/oauth2-redirect"),
            new RouteInfo("GET", "/api/collections/mock_collection/auth-methods"),
            new RouteInfo("POST", "/api/collections/mock_collection/auth-refresh"),
            new RouteInfo("POST", "/api/collections/mock_collection/auth-with-password"),
            new RouteInfo("POST", "/api/collections/mock_collection/auth-with-oauth2"),
            new RouteInfo("POST", "/api/collections/mock_collection/request-otp"),
            new RouteInfo("POST", "/api/collections/mock_collection/auth-with-otp"),
            new RouteInfo("POST", "/api/collections/mock_collection/request-password-reset"),
            new RouteInfo("POST", "/api/collections/mock_collection/confirm-password-reset"),
            new RouteInfo("POST", "/api/collections/mock_collection/request-verification"),
            new RouteInfo("POST", "/api/collections/mock_collection/confirm-verification"),
            new RouteInfo("POST", "/api/collections/mock_collection/request-email-change"),
            new RouteInfo("POST", "/api/collections/mock_collection/confirm-email-change"),
            new RouteInfo("POST", "/api/collections/mock_collection/impersonate/mock_id"),

            new RouteInfo("GET", "/api/logs"),
            new RouteInfo("GET", "/api/logs/stats"),
            new RouteInfo("GET", "/api/logs/mock_id"),

            new RouteInfo("GET", "/api/backups"),
            new RouteInfo("POST", "/api/backups"),
            new RouteInfo("POST", "/api/backups/upload"),
            new RouteInfo("GET", "/api/backups/mock_key"),
            new RouteInfo("DELETE", "/api/backups/mock_key"),
            new RouteInfo("POST", "/api/backups/mock_key/restore"),

            new RouteInfo("GET", "/api/crons"),
            new RouteInfo("POST", "/api/crons/mock_id"),

            new RouteInfo("POST", "/api/files/token"),
            new RouteInfo("GET", "/api/files/mock_collection/mock_recordId/mock_filename"),

            new RouteInfo("POST", "/api/batch"),
            new RouteInfo("GET", "/api/realtime"),
            new RouteInfo("POST", "/api/realtime"),
            new RouteInfo("GET", "/api/health"),
            new RouteInfo("POST", "/api/sql")
    );

    @Test
    void testOfficialRoutesShouldNotReturn404() throws Exception {
        for (RouteInfo route : officialRoutes) {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + route.path));
            
            if (route.method.equals("POST") || route.method.equals("PATCH") || route.method.equals("PUT")) {
                builder.method(route.method, HttpRequest.BodyPublishers.ofString("{}"));
                builder.header("Content-Type", "application/json");
            } else {
                builder.method(route.method, HttpRequest.BodyPublishers.noBody());
            }

            if ("GET".equals(route.method) && "/api/realtime".equals(route.path)) {
                HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream ignored = response.body()) {
                    assertNotEquals(404, response.statusCode(), "Route is missing: " + route.method + " " + route.path);
                }
                continue;
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            // A 404 might mean the route is entirely missing (which fails the manifest conformance).
            // A missing collection or auth failure should return 400, 401, 403, 404 (with specific code), etc.
            // But we must be careful: if a route exists but is designed to return 404 when "mock_collection" is not found.
            // In pocketbase, if route is totally unknown, it might return standard 404 html or simple 404 payload.
            // Our HttpApi returns ApiException(404, "Not found.") when totally unmatched.
            // But we can check that it doesn't just fall through to the root 404 handler.
            // Actually, if we hit a known route with an unknown collection, we might get a 404 "Collection not found."
            // So we can differentiate by checking the exact message or response format.
            
            // To be precise, let's just make sure it doesn't return exactly "Not found." 
            // which is the fallback for unmapped routes in HttpApi.java
            boolean isGlobal404 = response.statusCode() == 404 && response.body().contains("\"message\":\"Not found.\"");
            
            assertNotEquals(true, isGlobal404, "Route is missing: " + route.method + " " + route.path);
        }
    }

    @Test
    void testUnknownMethodReturns405Or404() throws Exception {
        String token = superuserToken();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/settings"))
                .header("Authorization", "Bearer " + token)
                .method("DELETE", HttpRequest.BodyPublishers.noBody()) // DELETE /api/settings is not allowed
                .build();
                ;
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        // As per SDP, we want official style 404/405
        // HttpApi throws ApiException(405, "Method not allowed.")
        assertEquals(405, response.statusCode());
        assertEquals(true, response.body().contains("Method not allowed."));
    }

    private String superuserToken() throws Exception {
        HttpRequest bootstrap = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/bootstrap/superuser"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"route@example.com\",\"password\":\"password123\"}"))
                .build();
        httpClient.send(bootstrap, HttpResponse.BodyHandlers.ofString());

        HttpRequest login = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/collections/_superusers/auth-with-password"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"identity\":\"route@example.com\",\"password\":\"password123\"}"))
                .build();
        HttpResponse<String> response = httpClient.send(login, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(response.body()).get("token").asText();
    }
}
