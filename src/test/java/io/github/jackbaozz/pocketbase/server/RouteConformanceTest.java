package io.github.jackbaozz.pocketbase.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jackbaozz.pocketbase.server.internal.HttpApi;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        TestDatabaseFactory.init();
        server = LocalPocketBase.start(config);
        baseUrl = "http://localhost:" + server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private List<HttpApi.Route> loadOfficialManifest() throws Exception {
        try (InputStream is = RouteConformanceTest.class.getResourceAsStream("/official-route-manifest.json")) {
            if (is == null) {
                throw new IllegalStateException("Missing official-route-manifest.json");
            }
            List<Map<String, String>> list = mapper.readValue(is, new TypeReference<List<Map<String, String>>>() {});
            List<HttpApi.Route> routes = new ArrayList<>();
            for (Map<String, String> m : list) {
                routes.add(new HttpApi.Route(m.get("method"), m.get("path")));
            }
            return routes;
        }
    }

    private String resolveMockPath(String path) {
        return path.replace("{collection}", "mock_collection")
                   .replace("{id}", "mock_id")
                   .replace("{key}", "mock_key")
                   .replace("{filename}", "mock_filename")
                   .replace("{recordId}", "mock_recordId");
    }

    @Test
    void testAllOfficialRoutesAreRegisteredLocally() throws Exception {
        List<HttpApi.Route> official = loadOfficialManifest();
        List<HttpApi.Route> local = HttpApi.REGISTERED_ROUTES;

        for (HttpApi.Route offRoute : official) {
            boolean found = local.stream().anyMatch(locRoute ->
                    locRoute.method().equalsIgnoreCase(offRoute.method()) &&
                    locRoute.path().equals(offRoute.path())
            );
            assertTrue(found, "Official route is not registered locally: " + offRoute.method() + " " + offRoute.path());
        }
    }

    @Test
    void testOfficialRoutesShouldNotReturn404() throws Exception {
        List<HttpApi.Route> officialRoutes = loadOfficialManifest();
        for (HttpApi.Route route : officialRoutes) {
            String resolvedPath = resolveMockPath(route.path());
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + resolvedPath));

            if (route.method().equals("POST") || route.method().equals("PATCH") || route.method().equals("PUT")) {
                builder.method(route.method(), HttpRequest.BodyPublishers.ofString("{}"));
                builder.header("Content-Type", "application/json");
            } else {
                builder.method(route.method(), HttpRequest.BodyPublishers.noBody());
            }

            if ("GET".equals(route.method()) && "/api/realtime".equals(route.path())) {
                HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream ignored = response.body()) {
                    assertNotEquals(404, response.statusCode(), "Route is missing: " + route.method() + " " + route.path());
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

            assertNotEquals(true, isGlobal404, "Route is missing: " + route.method() + " " + route.path());
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
