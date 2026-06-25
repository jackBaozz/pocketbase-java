package io.github.jackbaozz.pocketbase.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AdminUiSmokeTest {
    private static final Pattern SCRIPT_ASSET = Pattern.compile("src=\"(/_/assets/[^\"]+\\.js)\"");

    private LocalPocketBase server;
    private String baseUrl;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path dataDir;

    @BeforeAll
    static void initAll() {
        TestDatabaseFactory.init();
    }

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

    @Test
    void testAdminUiIndexPageLoading() throws Exception {
        // 1. Test GET /_/ should serve index.html
        HttpRequest requestIndex = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/_/"))
                .GET()
                .build();
        HttpResponse<String> responseIndex = httpClient.send(requestIndex, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, responseIndex.statusCode());
        assertTrue(responseIndex.headers().firstValue("Content-Type").orElse("").contains("text/html"));
        assertTrue(responseIndex.body().contains("<div id=\"root\"></div>"));

        // 2. Test GET /_/index.html directly
        HttpRequest requestIndexHtml = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/_/index.html"))
                .GET()
                .build();
        HttpResponse<String> responseIndexHtml = httpClient.send(requestIndexHtml, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, responseIndexHtml.statusCode());
        assertTrue(responseIndexHtml.body().contains("<div id=\"root\"></div>"));
    }

    @Test
    void testAdminUiAssetsServing() throws Exception {
        HttpRequest requestIndex = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/_/"))
                .GET()
                .build();
        HttpResponse<String> responseIndex = httpClient.send(requestIndex, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, responseIndex.statusCode());

        Matcher matcher = SCRIPT_ASSET.matcher(responseIndex.body());
        assertTrue(matcher.find(), "Admin index.html should reference a compiled JS asset");

        HttpRequest requestJs = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + matcher.group(1)))
                .GET()
                .build();
        HttpResponse<String> responseJs = httpClient.send(requestJs, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, responseJs.statusCode());
        String contentType = responseJs.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.contains("javascript") || contentType.contains("application/octet-stream") || contentType.contains("text/javascript"));
        assertTrue(responseJs.body().length() > 0);
    }

    @Test
    void testAdminUiSuperuserWorkflowParity() throws Exception {
        // 1. Bootstrap superuser
        HttpRequest bootstrap = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/bootstrap/superuser"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"ui-smoke@example.com\",\"password\":\"password123\"}"))
                .build();
        HttpResponse<String> bootstrapRes = httpClient.send(bootstrap, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, bootstrapRes.statusCode());

        // 2. Login through UI auth-with-password endpoint
        HttpRequest login = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/collections/_superusers/auth-with-password"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"identity\":\"ui-smoke@example.com\",\"password\":\"password123\"}"))
                .build();
        HttpResponse<String> loginRes = httpClient.send(login, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loginRes.statusCode());

        JsonNode loginBody = mapper.readTree(loginRes.body());
        String token = loginBody.get("token").asText();
        assertNotNull(token);

        // 3. Fetch app settings (first operation performed by Admin UI after login)
        HttpRequest settingsReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/settings"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> settingsRes = httpClient.send(settingsReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, settingsRes.statusCode());

        JsonNode settings = mapper.readTree(settingsRes.body());
        assertNotNull(settings.get("meta"));
    }
}
