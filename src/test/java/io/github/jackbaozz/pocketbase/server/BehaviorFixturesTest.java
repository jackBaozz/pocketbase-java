package io.github.jackbaozz.pocketbase.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BehaviorFixturesTest {

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
        bootstrapSuperuser();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private void bootstrapSuperuser() throws Exception {
        HttpRequest bootstrap = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/bootstrap/superuser"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"admin@example.com\",\"password\":\"password123\"}"))
                .build();
        httpClient.send(bootstrap, HttpResponse.BodyHandlers.ofString());
    }

    private String getSuperuserToken() throws Exception {
        HttpRequest login = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/collections/_superusers/auth-with-password"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"identity\":\"admin@example.com\",\"password\":\"password123\"}"))
                .build();
        HttpResponse<String> response = httpClient.send(login, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(response.body()).get("token").asText();
    }

    @Test
    void testAuthRequiredOnSettings() throws Exception {
        // 1. Anonymous request should fail
        HttpRequest anonymousRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/settings"))
                .GET()
                .build();
        HttpResponse<String> response1 = httpClient.send(anonymousRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response1.statusCode());
        JsonNode errNode = mapper.readTree(response1.body());
        assertEquals(401, errNode.get("status").asInt());
        assertFalse(errNode.has("code"));
        assertEquals("Missing or invalid auth token.", errNode.get("message").asText());

        // 2. Authenticated superuser request should succeed
        String token = getSuperuserToken();
        HttpRequest authRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/settings"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> response2 = httpClient.send(authRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response2.statusCode());
        JsonNode settingsNode = mapper.readTree(response2.body());
        assertNotNull(settingsNode.get("meta"));
    }

    @Test
    void testValidationErrorResponseFormat() throws Exception {
        String token = getSuperuserToken();

        // 1. Test missing name with valid type
        String missingNameJson = "{\"type\":\"base\"}";
        HttpRequest request1 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/collections"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(missingNameJson))
                .build();

        HttpResponse<String> response1 = httpClient.send(request1, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response1.statusCode());
        JsonNode body1 = mapper.readTree(response1.body());
        assertEquals(400, body1.get("status").asInt());
        assertFalse(body1.has("code"));
        assertEquals("Failed to create collection.", body1.get("message").asText());
        JsonNode nameError = body1.get("data").get("name");
        assertNotNull(nameError, "Missing validation error for name field");
        assertEquals("validation_required", nameError.get("code").asText());
        assertEquals("Cannot be blank.", nameError.get("message").asText());

        // 2. Test valid name with invalid type
        String invalidTypeJson = "{\"name\":\"valid_name\",\"type\":\"invalid_type\"}";
        HttpRequest request2 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/collections"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(invalidTypeJson))
                .build();

        HttpResponse<String> response2 = httpClient.send(request2, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response2.statusCode());
        JsonNode body2 = mapper.readTree(response2.body());
        assertEquals(400, body2.get("status").asInt());
        assertFalse(body2.has("code"));
        JsonNode typeError = body2.get("data").get("type");
        assertNotNull(typeError, "Missing validation error for type field");
        assertEquals("validation_invalid_value", typeError.get("code").asText());
    }

    @Test
    void testAuthValidationErrorResponseFormat() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/collections/_superusers/auth-with-password"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"password123\"}"))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        JsonNode body = mapper.readTree(response.body());
        assertEquals(400, body.get("status").asInt());
        assertFalse(body.has("code"));
        assertEquals("Failed to authenticate.", body.get("message").asText());
        JsonNode identityError = body.get("data").get("identity");
        assertNotNull(identityError, "Missing validation error for identity field");
        assertEquals("validation_required", identityError.get("code").asText());
        assertEquals("Cannot be blank.", identityError.get("message").asText());
    }

    @Test
    void testRecordsValidationAndCrud() throws Exception {
        String token = getSuperuserToken();

        // 1. Create a posts collection with a required field 'title' and a number field 'views'
        String collectionJson = "{"
                + "\"name\":\"posts\","
                + "\"type\":\"base\","
                + "\"createRule\":\"\","
                + "\"listRule\":\"\","
                + "\"viewRule\":\"\","
                + "\"updateRule\":\"\","
                + "\"deleteRule\":\"\","
                + "\"schema\":["
                + "  {\"name\":\"title\",\"type\":\"text\",\"required\":true},"
                + "  {\"name\":\"views\",\"type\":\"number\"}"
                + "]"
                + "}";
        HttpRequest createCollReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/collections"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(collectionJson))
                .build();
        HttpResponse<String> createCollRes = httpClient.send(createCollReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, createCollRes.statusCode());

        // 2. Try creating a record missing the required field 'title'
        String invalidRecordJson = "{\"views\": 10}";
        HttpRequest createInvalidRecordReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/collections/posts/records"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(invalidRecordJson))
                .build();
        HttpResponse<String> createInvalidRes = httpClient.send(createInvalidRecordReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, createInvalidRes.statusCode());
        JsonNode invalidBody = mapper.readTree(createInvalidRes.body());
        assertEquals(400, invalidBody.get("status").asInt());
        assertFalse(invalidBody.has("code"));
        assertEquals("Failed to create record.", invalidBody.get("message").asText());
        assertTrue(invalidBody.get("data").has("title"));
        assertEquals("validation_required", invalidBody.get("data").get("title").get("code").asText());
        assertEquals("Cannot be blank.", invalidBody.get("data").get("title").get("message").asText());

        // 3. Create a valid record
        String validRecordJson = "{\"title\":\"My First Post\",\"views\":100}";
        HttpRequest createRecordReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/collections/posts/records"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(validRecordJson))
                .build();
        HttpResponse<String> createRes = httpClient.send(createRecordReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, createRes.statusCode());
        JsonNode record = mapper.readTree(createRes.body());
        String recordId = record.get("id").asText();
        assertEquals("My First Post", record.get("title").asText());
        assertEquals(100, record.get("views").asInt());

        // 4. Read the created record
        HttpRequest getRecordReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/collections/posts/records/" + recordId))
                .GET()
                .build();
        HttpResponse<String> getRes = httpClient.send(getRecordReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, getRes.statusCode());
        JsonNode fetchedRecord = mapper.readTree(getRes.body());
        assertEquals(recordId, fetchedRecord.get("id").asText());
        assertEquals("My First Post", fetchedRecord.get("title").asText());

        // 5. Update the record
        String updateJson = "{\"title\":\"Updated Title\"}";
        HttpRequest updateReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/collections/posts/records/" + recordId))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(updateJson))
                .build();
        HttpResponse<String> updateRes = httpClient.send(updateReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, updateRes.statusCode());
        JsonNode updatedRecord = mapper.readTree(updateRes.body());
        assertEquals("Updated Title", updatedRecord.get("title").asText());
        assertEquals(100, updatedRecord.get("views").asInt()); // preserved

        // 6. Filter records using a quoted string literal
        String quotedTitleJson = "{\"title\":\"Bob's Post\",\"views\":7}";
        HttpRequest createQuotedRecordReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/collections/posts/records"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(quotedTitleJson))
                .build();
        HttpResponse<String> createQuotedRes = httpClient.send(createQuotedRecordReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, createQuotedRes.statusCode());
        String quotedFilter = URLEncoder.encode("title = 'Bob\\'s Post'", StandardCharsets.UTF_8);
        HttpRequest filteredListReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/collections/posts/records?filter=" + quotedFilter))
                .GET()
                .build();
        HttpResponse<String> filteredListRes = httpClient.send(filteredListReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, filteredListRes.statusCode());
        JsonNode filteredList = mapper.readTree(filteredListRes.body());
        assertEquals(1, filteredList.get("totalItems").asInt());
        assertEquals("Bob's Post", filteredList.get("items").get(0).get("title").asText());
        String containsFilter = URLEncoder.encode("title ~ 'Bob'", StandardCharsets.UTF_8);
        HttpRequest containsListReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/collections/posts/records?filter=" + containsFilter))
                .GET()
                .build();
        HttpResponse<String> containsListRes = httpClient.send(containsListReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, containsListRes.statusCode());
        JsonNode containsList = mapper.readTree(containsListRes.body());
        assertEquals(1, containsList.get("totalItems").asInt());
        assertEquals("Bob's Post", containsList.get("items").get(0).get("title").asText());

        // 7. Delete the record
        HttpRequest deleteReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/collections/posts/records/" + recordId))
                .method("DELETE", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> deleteRes = httpClient.send(deleteReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, deleteRes.statusCode());
    }

    @Test
    void testNonExistentCollectionReturns404() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/collections/non_existent_collection/records/some_id"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
        JsonNode body = mapper.readTree(response.body());
        assertEquals("Collection not found.", body.get("message").asText());
    }

    @Test
    void testStoragePropertyIsPassed() {
        String storage = System.getProperty("storage");
        assertNotNull(storage);
        System.out.println("Current storage system property is: " + storage);
    }
}
