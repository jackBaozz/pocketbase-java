package io.github.jackbaozz.pocketbase.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.AuthResponse;
import io.github.jackbaozz.pocketbase.PocketBaseClient;
import io.github.jackbaozz.pocketbase.PocketBaseException;
import io.github.jackbaozz.pocketbase.RecordList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPocketBaseServerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @TempDir
    Path tempDir;

    private LocalPocketBase server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void sdkCanUseEmbeddedServerCollectionsAndRecords() throws Exception {
        start();
        bootstrapSuperuser();

        PocketBaseClient client = PocketBaseClient.builder(server.baseUrl()).build();
        AuthResponse auth = client.collection("_superusers").authWithPassword("root@example.com", "secret123");
        assertNotNull(auth.token());

        JsonNode collection = client.collections().create(Map.of(
                "name", "posts",
                "type", "base",
                "fields", List.of(
                        Map.of("name", "title", "type", "text", "required", true),
                        Map.of("name", "published", "type", "bool")
                )
        ));
        assertEquals("posts", collection.get("name").asText());

        JsonNode created = client.collection("posts").create(Map.of(
                "title", "Hello Java",
                "published", true
        ));
        assertEquals("Hello Java", created.get("title").asText());

        RecordList page = client.collection("posts").list();
        assertEquals(1, page.totalItems());
        assertEquals("Hello Java", page.items().get(0).get("title").asText());
    }

    @Test
    void collectionListSupportsFilterSortAndFields() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        request("POST", "/api/collections", token, Map.of(
                "name", "collection_api_alpha",
                "fields", List.of(Map.of("name", "name", "type", "text"))
        ));
        request("POST", "/api/collections", token, Map.of(
                "name", "collection_api_beta",
                "fields", List.of(Map.of("name", "name", "type", "text"))
        ));

        String filter = URLEncoder.encode("name ~ 'collection_api_'", StandardCharsets.UTF_8);
        JsonNode page = request("GET", "/api/collections?filter=" + filter
                + "&sort=-name&page=1&perPage=1&fields=id,name,type", token, null);

        assertEquals(2, page.get("totalItems").asInt());
        assertEquals(2, page.get("totalPages").asInt());
        JsonNode item = page.get("items").get(0);
        assertEquals("collection_api_beta", item.get("name").asText());
        assertEquals("base", item.get("type").asText());
        assertFalse(item.has("fields"));

        JsonNode single = request("GET", "/api/collections/collection_api_alpha?fields=id,name", token, null);
        assertEquals("collection_api_alpha", single.get("name").asText());
        assertTrue(single.has("id"));
        assertFalse(single.has("type"));
    }

    @Test
    void recordsPersistAcrossServerRestart() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        request("POST", "/api/collections", token, Map.of(
                "name", "tasks",
                "listRule", "",
                "fields", List.of(Map.of("name", "name", "type", "text", "required", true))
        ));
        request("POST", "/api/collections/tasks/records", token, Map.of("name", "persist me"));
        server.close();

        start();
        JsonNode page = request("GET", "/api/collections/tasks/records", null, null);

        assertEquals(1, page.get("totalItems").asInt());
        assertEquals("persist me", page.get("items").get(0).get("name").asText());
    }

    @Test
    void backupsCanBeCreatedDownloadedRestoredAndDeleted() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        request("POST", "/api/collections", token, Map.of(
                "name", "tasks",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(Map.of("name", "name", "type", "text", "required", true))
        ));
        request("POST", "/api/collections/tasks/records", token, Map.of("name", "before backup"));

        JsonNode backup = request("POST", "/api/backups", token, Map.of("name", "snap.zip"));
        assertEquals("snap.zip", backup.get("key").asText());
        assertTrue(backup.get("size").asLong() > 0);

        JsonNode backups = request("GET", "/api/backups", token, null);
        assertEquals(1, backups.get("totalItems").asInt());
        assertEquals("snap.zip", backups.get("items").get(0).get("key").asText());

        HttpResponse<byte[]> download = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/backups/snap.zip"))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );
        assertEquals(200, download.statusCode());
        assertEquals("application/zip", download.headers().firstValue("Content-Type").orElse(""));
        assertTrue(download.body().length > 0);

        request("POST", "/api/collections/tasks/records", token, Map.of("name", "after backup"));
        JsonNode changed = request("GET", "/api/collections/tasks/records", null, null);
        assertEquals(2, changed.get("totalItems").asInt());

        request("POST", "/api/backups/snap.zip/restore", token, null);
        JsonNode restored = request("GET", "/api/collections/tasks/records", null, null);
        assertEquals(1, restored.get("totalItems").asInt());
        assertEquals("before backup", restored.get("items").get(0).get("name").asText());

        HttpResponse<String> deleted = rawRequest("DELETE", "/api/backups/snap.zip", token, null);
        assertEquals(204, deleted.statusCode());
    }

    @Test
    void batchExecutesRecordOperationsAndRollsBackOnFailure() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        request("POST", "/api/collections", token, Map.of(
                "name", "batch_posts",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(Map.of("name", "title", "type", "text", "required", true))
        ));

        JsonNode batch = request("POST", "/api/batch", token, Map.of(
                "requests", List.of(
                        Map.of(
                                "method", "POST",
                                "url", "/api/collections/batch_posts/records",
                                "body", Map.of("id", "batch_one", "title", "created")
                        ),
                        Map.of(
                                "method", "PUT",
                                "url", "/api/collections/batch_posts/records/batch_two",
                                "body", Map.of("title", "upserted")
                        ),
                        Map.of(
                                "method", "PATCH",
                                "url", "/api/collections/batch_posts/records/batch_one",
                                "body", Map.of("title", "updated")
                        ),
                        Map.of(
                                "method", "DELETE",
                                "url", "/api/collections/batch_posts/records/batch_two"
                        )
                )
        ));
        assertEquals(4, batch.get("responses").size());
        assertEquals(204, batch.get("responses").get(3).get("status").asInt());

        JsonNode page = request("GET", "/api/collections/batch_posts/records", null, null);
        assertEquals(1, page.get("totalItems").asInt());
        assertEquals("updated", page.get("items").get(0).get("title").asText());

        HttpResponse<String> failed = rawRequest("POST", "/api/batch", token, Map.of(
                "requests", List.of(
                        Map.of(
                                "method", "POST",
                                "url", "/api/collections/batch_posts/records",
                                "body", Map.of("id", "rollback_me", "title", "rollback")
                        ),
                        Map.of(
                                "method", "PATCH",
                                "url", "/api/collections/batch_posts/records/missing",
                                "body", Map.of("title", "fail")
                        )
                )
        ));
        assertEquals(400, failed.statusCode());
        assertTrue(failed.body().contains("\"index\":1"));

        JsonNode afterRollback = request("GET", "/api/collections/batch_posts/records", null, null);
        assertEquals(1, afterRollback.get("totalItems").asInt());
        assertEquals("batch_one", afterRollback.get("items").get(0).get("id").asText());
    }

    @Test
    void multipartBatchUploadsFilesAndRollsBackStorageOnFailure() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        request("POST", "/api/collections", token, Map.of(
                "name", "batch_assets",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(
                        Map.of("name", "title", "type", "text", "required", true),
                        Map.of("name", "attachment", "type", "file", "required", true)
                )
        ));

        String payload = """
                {
                  "requests": [
                    {
                      "method": "POST",
                      "url": "/api/collections/batch_assets/records?fields=id,attachment",
                      "body": {"id": "batch_asset_one", "title": "Batch upload"}
                    }
                  ]
                }
                """;
        JsonNode batch = multipartRequest("POST", "/api/batch", token, Map.of(
                "@jsonPayload", payload
        ), Map.of(
                "requests.0.attachment", new MultipartFile(
                        "batch upload.txt",
                        "text/plain",
                        "hello from multipart batch".getBytes(StandardCharsets.UTF_8)
                )
        ));

        JsonNode body = batch.get("responses").get(0).get("body");
        assertEquals(200, batch.get("responses").get(0).get("status").asInt());
        assertEquals("batch_asset_one", body.get("id").asText());
        String filename = body.get("attachment").asText();
        assertTrue(filename.startsWith("batch_upload_"));

        HttpResponse<String> file = rawRequest(
                "GET",
                "/api/files/batch_assets/batch_asset_one/" + filename,
                null,
                null
        );
        assertEquals(200, file.statusCode());
        assertEquals("hello from multipart batch", file.body());

        String failedPayload = """
                {
                  "requests": [
                    {
                      "method": "POST",
                      "url": "/api/collections/batch_assets/records",
                      "body": {"id": "rollback_asset", "title": "Rollback file"}
                    },
                    {
                      "method": "PATCH",
                      "url": "/api/collections/batch_assets/records/missing",
                      "body": {"title": "fail"}
                    }
                  ]
                }
                """;
        HttpResponse<String> failed = rawMultipartRequest("POST", "/api/batch", token, Map.of(
                "@jsonPayload", failedPayload
        ), Map.of(
                "requests[0].attachment", new MultipartFile(
                        "rollback file.txt",
                        "text/plain",
                        "this file must be rolled back".getBytes(StandardCharsets.UTF_8)
                )
        ));
        assertEquals(400, failed.statusCode());
        assertTrue(failed.body().contains("\"index\":1"));

        HttpResponse<String> rolledBackRecord = rawRequest(
                "GET",
                "/api/collections/batch_assets/records/rollback_asset",
                token,
                null
        );
        assertEquals(404, rolledBackRecord.statusCode());
        assertFalse(storageContainsFilename("rollback_file_"));
    }

    @Test
    void relationExpandResolvesVisibleRecords() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        JsonNode authors = request("POST", "/api/collections", token, Map.of(
                "name", "authors",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(Map.of("name", "name", "type", "text", "required", true))
        ));
        request("POST", "/api/collections", token, Map.of(
                "name", "posts",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(
                        Map.of("name", "title", "type", "text", "required", true),
                        Map.of("name", "author", "type", "relation", "collectionId", authors.get("id").asText())
                )
        ));
        JsonNode author = request("POST", "/api/collections/authors/records", token, Map.of("name", "Ada"));
        JsonNode post = request("POST", "/api/collections/posts/records", token, Map.of(
                "title", "Expandable",
                "author", author.get("id").asText()
        ));

        JsonNode page = request("GET", "/api/collections/posts/records?expand=author", null, null);
        JsonNode expanded = page.get("items").get(0).get("expand").get("author");
        assertEquals(author.get("id").asText(), expanded.get("id").asText());
        assertEquals("Ada", expanded.get("name").asText());

        JsonNode single = request("GET", "/api/collections/posts/records/" + post.get("id").asText() + "?expand=author", null, null);
        assertEquals("Ada", single.get("expand").get("author").get("name").asText());
    }

    @Test
    void recordResponsesHonorFieldsQueryIncludingExpandedRelations() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        JsonNode authors = request("POST", "/api/collections", token, Map.of(
                "name", "field_authors",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(Map.of("name", "name", "type", "text", "required", true))
        ));
        request("POST", "/api/collections", token, Map.of(
                "name", "field_posts",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(
                        Map.of("name", "title", "type", "text", "required", true),
                        Map.of("name", "body", "type", "text"),
                        Map.of("name", "author", "type", "relation", "collectionId", authors.get("id").asText())
                )
        ));
        JsonNode author = request("POST", "/api/collections/field_authors/records", token, Map.of("name", "Ada"));
        JsonNode post = request("POST", "/api/collections/field_posts/records", token, Map.of(
                "title", "Fields",
                "body", "Hidden by fields",
                "author", author.get("id").asText()
        ));

        JsonNode page = request("GET", "/api/collections/field_posts/records?fields=id,title", null, null);
        JsonNode item = page.get("items").get(0);
        assertEquals(post.get("id").asText(), item.get("id").asText());
        assertEquals("Fields", item.get("title").asText());
        assertFalse(item.has("body"));
        assertFalse(item.has("collectionName"));

        JsonNode single = request("GET", "/api/collections/field_posts/records/" + post.get("id").asText()
                + "?expand=author&fields=id,expand.author.name", null, null);
        assertEquals(post.get("id").asText(), single.get("id").asText());
        assertFalse(single.has("title"));
        assertEquals("Ada", single.get("expand").get("author").get("name").asText());
        assertFalse(single.get("expand").get("author").has("id"));

        JsonNode created = request("POST", "/api/collections/field_posts/records"
                + "?expand=author&fields=id,expand.author.name", token, Map.of(
                "title", "Created fields",
                "body", "Hidden create body",
                "author", author.get("id").asText()
        ));
        assertTrue(created.hasNonNull("id"));
        assertFalse(created.has("title"));
        assertEquals("Ada", created.get("expand").get("author").get("name").asText());
        assertFalse(created.get("expand").get("author").has("id"));

        JsonNode updated = request("PATCH", "/api/collections/field_posts/records/" + post.get("id").asText()
                + "?fields=id,title", token, Map.of(
                "title", "Updated fields",
                "body", "Still hidden by fields"
        ));
        assertEquals(post.get("id").asText(), updated.get("id").asText());
        assertEquals("Updated fields", updated.get("title").asText());
        assertFalse(updated.has("body"));
        assertFalse(updated.has("collectionName"));
    }

    @Test
    void accessRulesCanReferenceOtherCollectionFields() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        request("POST", "/api/collections", token, Map.of(
                "name", "news",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(Map.of("name", "categoryId", "type", "text", "required", true))
        ));
        request("POST", "/api/collections", token, Map.of(
                "name", "categories",
                "listRule", "@collection.news.categoryId ?= id",
                "viewRule", "@collection.news.categoryId ?= id",
                "fields", List.of(Map.of("name", "name", "type", "text", "required", true))
        ));

        JsonNode visible = request("POST", "/api/collections/categories/records", token, Map.of(
                "id", "cat_visible",
                "name", "Visible"
        ));
        JsonNode hidden = request("POST", "/api/collections/categories/records", token, Map.of(
                "id", "cat_hidden",
                "name", "Hidden"
        ));
        request("POST", "/api/collections/news/records", token, Map.of(
                "title", "Published",
                "categoryId", visible.get("id").asText()
        ));

        JsonNode page = request("GET", "/api/collections/categories/records", null, null);
        assertEquals(1, page.get("totalItems").asInt());
        assertEquals(visible.get("id").asText(), page.get("items").get(0).get("id").asText());

        JsonNode single = request("GET", "/api/collections/categories/records/" + visible.get("id").asText(), null, null);
        assertEquals("Visible", single.get("name").asText());

        HttpResponse<String> hiddenResponse = rawRequest("GET", "/api/collections/categories/records/" + hidden.get("id").asText(), null, null);
        assertEquals(404, hiddenResponse.statusCode());
    }

    @Test
    void authCollectionsHashPasswordsAndRejectDuplicateEmail() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        request("POST", "/api/collections", token, Map.of(
                "name", "users",
                "type", "auth",
                "fields", List.of(Map.of("name", "displayName", "type", "text"))
        ));
        request("POST", "/api/collections/users/records", token, Map.of(
                "email", "demo@example.com",
                "password", "secret456",
                "displayName", "Demo"
        ));

        JsonNode auth = request("POST", "/api/collections/users/auth-with-password", null, Map.of(
                "identity", "demo@example.com",
                "password", "secret456"
        ));
        assertTrue(auth.hasNonNull("token"));
        assertFalse(auth.get("record").has("password"));

        PocketBaseException duplicate = assertThrows(PocketBaseException.class, () -> {
            PocketBaseClient authed = PocketBaseClient.builder(server.baseUrl()).bearerToken(token).build();
            authed.collection("users").create(Map.of(
                    "email", "demo@example.com",
                    "password", "another-secret"
            ));
        });
        assertEquals(400, duplicate.statusCode());
        assertTrue(duplicate.responseBody().contains("Value must be unique"));
    }

    @Test
    void authRefreshReissuesTokenForMatchingAuthRecord() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        request("POST", "/api/collections", token, Map.of(
                "name", "users",
                "type", "auth",
                "fields", List.of(Map.of("name", "displayName", "type", "text"))
        ));
        JsonNode user = request("POST", "/api/collections/users/records", token, Map.of(
                "email", "refresh@example.com",
                "password", "secret456",
                "displayName", "Refresh"
        ));

        JsonNode auth = request("POST", "/api/collections/users/auth-with-password", null, Map.of(
                "identity", "refresh@example.com",
                "password", "secret456"
        ));
        JsonNode refreshed = request("POST", "/api/collections/users/auth-refresh", auth.get("token").asText(), null);

        assertTrue(refreshed.hasNonNull("token"));
        assertEquals(user.get("id").asText(), refreshed.get("record").get("id").asText());
        assertFalse(refreshed.get("record").has("password"));

        HttpResponse<String> mismatch = rawRequest(
                "POST",
                "/api/collections/_superusers/auth-refresh",
                auth.get("token").asText(),
                null
        );
        assertEquals(401, mismatch.statusCode());
    }

    @Test
    void authResponsesHonorQueryFieldsAndExpand() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        JsonNode teams = request("POST", "/api/collections", token, Map.of(
                "name", "auth_teams",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(Map.of("name", "name", "type", "text", "required", true))
        ));
        request("POST", "/api/collections", token, Map.of(
                "name", "auth_query_users",
                "type", "auth",
                "fields", List.of(
                        Map.of("name", "displayName", "type", "text"),
                        Map.of("name", "team", "type", "relation", "collectionId", teams.get("id").asText())
                )
        ));
        JsonNode team = request("POST", "/api/collections/auth_teams/records", token, Map.of("name", "Core"));
        JsonNode user = request("POST", "/api/collections/auth_query_users/records", token, Map.of(
                "email", "query@example.com",
                "password", "secret456",
                "displayName", "Query",
                "team", team.get("id").asText()
        ));

        JsonNode auth = request("POST", "/api/collections/auth_query_users/auth-with-password"
                + "?expand=team&fields=token,record.id,record.expand.team.name", null, Map.of(
                "identity", "query@example.com",
                "password", "secret456"
        ));
        assertTrue(auth.hasNonNull("token"));
        assertEquals(user.get("id").asText(), auth.get("record").get("id").asText());
        assertFalse(auth.get("record").has("email"));
        assertFalse(auth.get("record").has("displayName"));
        assertEquals("Core", auth.get("record").get("expand").get("team").get("name").asText());
        assertFalse(auth.get("record").get("expand").get("team").has("id"));

        JsonNode refreshed = request("POST", "/api/collections/auth_query_users/auth-refresh"
                + "?expand=team&fields=token,record.expand.team.name", auth.get("token").asText(), null);
        assertTrue(refreshed.hasNonNull("token"));
        assertFalse(refreshed.get("record").has("id"));
        assertEquals("Core", refreshed.get("record").get("expand").get("team").get("name").asText());

        JsonNode recordOnly = request("POST", "/api/collections/auth_query_users/auth-refresh"
                + "?fields=record.*", auth.get("token").asText(), null);
        assertFalse(recordOnly.has("token"));
        assertEquals("query@example.com", recordOnly.get("record").get("email").asText());
        assertEquals("Query", recordOnly.get("record").get("displayName").asText());
    }

    @Test
    void servesAdminUi() throws Exception {
        start();
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.baseUrl() + "/_/")).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("pocketbase-java"));
        assertTrue(response.body().contains("/_/assets/"));

        Matcher asset = Pattern.compile("src=\"(/_/assets/[^\"]+\\.js)\"").matcher(response.body());
        assertTrue(asset.find());
        HttpRequest assetRequest = HttpRequest.newBuilder(URI.create(server.baseUrl() + asset.group(1))).GET().build();
        HttpResponse<String> assetResponse = http.send(assetRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, assetResponse.statusCode());
        assertTrue(assetResponse.body().contains("pocketbase-java"));
    }

    @Test
    void collectionRulesFilterAndProtectRecordOperations() throws Exception {
        start();
        bootstrapSuperuser();
        String superuserToken = loginToken();

        request("POST", "/api/collections", superuserToken, Map.of(
                "name", "users",
                "type", "auth",
                "fields", List.of(Map.of("name", "displayName", "type", "text"))
        ));
        request("POST", "/api/collections/users/records", superuserToken, Map.of(
                "email", "alice@example.com",
                "password", "secret456",
                "displayName", "Alice"
        ));
        request("POST", "/api/collections/users/records", superuserToken, Map.of(
                "email", "bob@example.com",
                "password", "secret456",
                "displayName", "Bob"
        ));

        JsonNode aliceAuth = request("POST", "/api/collections/users/auth-with-password", null, Map.of(
                "identity", "alice@example.com",
                "password", "secret456"
        ));
        JsonNode bobAuth = request("POST", "/api/collections/users/auth-with-password", null, Map.of(
                "identity", "bob@example.com",
                "password", "secret456"
        ));
        String aliceToken = aliceAuth.get("token").asText();
        String aliceId = aliceAuth.get("record").get("id").asText();
        String bobToken = bobAuth.get("token").asText();
        String bobId = bobAuth.get("record").get("id").asText();

        String ownerRule = "public = true || owner = @request.auth.id";
        request("POST", "/api/collections", superuserToken, Map.of(
                "name", "documents",
                "listRule", ownerRule,
                "viewRule", ownerRule,
                "createRule", "owner = @request.auth.id",
                "updateRule", "owner = @request.auth.id",
                "deleteRule", "owner = @request.auth.id",
                "fields", List.of(
                        Map.of("name", "owner", "type", "text", "required", true),
                        Map.of("name", "title", "type", "text", "required", true),
                        Map.of("name", "public", "type", "bool")
                )
        ));

        JsonNode aliceDocument = request("POST", "/api/collections/documents/records", aliceToken, Map.of(
                "owner", aliceId,
                "title", "Alice private",
                "public", false
        ));
        request("POST", "/api/collections/documents/records", superuserToken, Map.of(
                "owner", bobId,
                "title", "Bob private",
                "public", false
        ));

        HttpResponse<String> forgedCreate = rawRequest("POST", "/api/collections/documents/records", aliceToken, Map.of(
                "owner", bobId,
                "title", "Forged",
                "public", false
        ));
        assertEquals(400, forgedCreate.statusCode());
        assertTrue(forgedCreate.body().contains("create rule"));

        JsonNode alicePage = request("GET", "/api/collections/documents/records", aliceToken, null);
        assertEquals(1, alicePage.get("totalItems").asInt());
        assertEquals("Alice private", alicePage.get("items").get(0).get("title").asText());

        HttpResponse<String> bobView = rawRequest("GET", "/api/collections/documents/records/"
                + aliceDocument.get("id").asText(), bobToken, null);
        assertEquals(404, bobView.statusCode());

        HttpResponse<String> bobUpdate = rawRequest("PATCH", "/api/collections/documents/records/"
                + aliceDocument.get("id").asText(), bobToken, Map.of("title", "Bob edit"));
        assertEquals(404, bobUpdate.statusCode());

        request("PATCH", "/api/collections/documents/records/" + aliceDocument.get("id").asText(), aliceToken, Map.of(
                "title", "Alice edited"
        ));
        JsonNode updated = request("GET", "/api/collections/documents/records/" + aliceDocument.get("id").asText(), aliceToken, null);
        assertEquals("Alice edited", updated.get("title").asText());
    }

    @Test
    void multipartFileUploadsAreStoredAndServedFromApiFiles() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        request("POST", "/api/collections", token, Map.of(
                "name", "assets",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(
                        Map.of("name", "title", "type", "text", "required", true),
                        Map.of("name", "attachment", "type", "file", "required", true)
                )
        ));

        JsonNode created = multipartRequest("POST", "/api/collections/assets/records", token, Map.of(
                "title", "Uploaded doc"
        ), Map.of(
                "attachment", new MultipartFile("hello world.txt", "text/plain", "hello from multipart".getBytes(StandardCharsets.UTF_8))
        ));

        String filename = created.get("attachment").asText();
        assertTrue(filename.startsWith("hello_world_"));
        assertTrue(filename.endsWith(".txt"));

        HttpResponse<String> file = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/files/assets/"
                        + created.get("id").asText() + "/" + filename)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(200, file.statusCode());
        assertEquals("text/plain; charset=utf-8", file.headers().firstValue("Content-Type").orElse(""));
        assertEquals("hello from multipart", file.body());
    }

    @Test
    void imageFileThumbsAreGeneratedOnlyForConfiguredSizes() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        request("POST", "/api/collections", token, Map.of(
                "name", "image_assets",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(
                        Map.of("name", "title", "type", "text", "required", true),
                        Map.of(
                                "name", "image",
                                "type", "file",
                                "required", true,
                                "mimeTypes", List.of("image/png"),
                                "thumbs", List.of("8x4", "0x4")
                        )
                )
        ));

        JsonNode created = multipartRequest("POST", "/api/collections/image_assets/records", token, Map.of(
                "title", "Image"
        ), Map.of(
                "image", new MultipartFile("wide.png", "image/png", pngBytes(16, 8))
        ));

        String filename = created.get("image").asText();
        String filePath = "/api/files/image_assets/" + created.get("id").asText() + "/" + filename;
        HttpResponse<byte[]> thumb = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + filePath + "?thumb=8x4")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );
        assertEquals(200, thumb.statusCode());
        assertEquals("image/png", thumb.headers().firstValue("Content-Type").orElse(""));
        assertEquals(List.of(8, 4), imageSize(thumb.body()));

        HttpResponse<byte[]> originalFallback = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + filePath + "?thumb=12x6")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );
        assertEquals(200, originalFallback.statusCode());
        assertEquals(List.of(16, 8), imageSize(originalFallback.body()));

        HttpResponse<byte[]> download = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + filePath + "?thumb=8x4&download=1")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );
        assertEquals("attachment; filename=\"" + filename + "\"", download.headers().firstValue("Content-Disposition").orElse(""));
    }

    @Test
    void fileFieldsValidateMimeTypesAndMaxSize() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        request("POST", "/api/collections", token, Map.of(
                "name", "validated_assets",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(
                        Map.of("name", "title", "type", "text", "required", true),
                        Map.of(
                                "name", "attachment",
                                "type", "file",
                                "required", true,
                                "mimeTypes", List.of("text/plain"),
                                "maxSize", 12
                        )
                )
        ));

        JsonNode created = multipartRequest("POST", "/api/collections/validated_assets/records", token, Map.of(
                "title", "Valid"
        ), Map.of(
                "attachment", new MultipartFile("ok.txt", "text/plain", "small".getBytes(StandardCharsets.UTF_8))
        ));
        assertTrue(created.get("attachment").asText().startsWith("ok_"));

        HttpResponse<String> badMime = rawMultipartRequest("POST", "/api/collections/validated_assets/records", token, Map.of(
                "title", "Bad mime"
        ), Map.of(
                "attachment", new MultipartFile("bad.png", "image/png", "small".getBytes(StandardCharsets.UTF_8))
        ));
        assertEquals(400, badMime.statusCode());
        assertTrue(badMime.body().contains("MIME type is not allowed"));

        HttpResponse<String> tooLarge = rawMultipartRequest("POST", "/api/collections/validated_assets/records", token, Map.of(
                "title", "Too large"
        ), Map.of(
                "attachment", new MultipartFile("large.txt", "text/plain", "this payload is too large".getBytes(StandardCharsets.UTF_8))
        ));
        assertEquals(400, tooLarge.statusCode());
        assertTrue(tooLarge.body().contains("exceeds maxSize"));
    }

    @Test
    void protectedFilesRequireFileTokenAndViewRuleAccess() throws Exception {
        start();
        bootstrapSuperuser();
        String superuserToken = loginToken();

        request("POST", "/api/collections", superuserToken, Map.of(
                "name", "users",
                "type", "auth",
                "fields", List.of(Map.of("name", "displayName", "type", "text"))
        ));
        request("POST", "/api/collections/users/records", superuserToken, Map.of(
                "email", "alice-file@example.com",
                "password", "secret456",
                "displayName", "Alice"
        ));
        request("POST", "/api/collections/users/records", superuserToken, Map.of(
                "email", "bob-file@example.com",
                "password", "secret456",
                "displayName", "Bob"
        ));
        JsonNode aliceAuth = request("POST", "/api/collections/users/auth-with-password", null, Map.of(
                "identity", "alice-file@example.com",
                "password", "secret456"
        ));
        JsonNode bobAuth = request("POST", "/api/collections/users/auth-with-password", null, Map.of(
                "identity", "bob-file@example.com",
                "password", "secret456"
        ));
        String aliceToken = aliceAuth.get("token").asText();
        String bobToken = bobAuth.get("token").asText();
        String aliceId = aliceAuth.get("record").get("id").asText();

        request("POST", "/api/collections", superuserToken, Map.of(
                "name", "secure_assets",
                "listRule", "owner = @request.auth.id",
                "viewRule", "owner = @request.auth.id",
                "fields", List.of(
                        Map.of("name", "owner", "type", "text", "required", true),
                        Map.of("name", "attachment", "type", "file", "required", true, "protected", true)
                )
        ));
        JsonNode created = multipartRequest("POST", "/api/collections/secure_assets/records", superuserToken, Map.of(
                "owner", aliceId
        ), Map.of(
                "attachment", new MultipartFile("secret.txt", "text/plain", "protected payload".getBytes(StandardCharsets.UTF_8))
        ));
        String filename = created.get("attachment").asText();
        String filePath = "/api/files/secure_assets/" + created.get("id").asText() + "/" + filename;

        HttpResponse<String> publicFile = rawRequest("GET", filePath, null, null);
        assertEquals(403, publicFile.statusCode());

        JsonNode aliceFileToken = request("POST", "/api/files/token", aliceToken, null);
        HttpResponse<String> fileTokenAsBearer = rawRequest("GET", filePath, aliceFileToken.get("token").asText(), null);
        assertEquals(403, fileTokenAsBearer.statusCode());

        HttpResponse<String> aliceFile = rawRequest("GET", filePath + "?token=" + aliceFileToken.get("token").asText(), null, null);
        assertEquals(200, aliceFile.statusCode());
        assertEquals("protected payload", aliceFile.body());

        JsonNode bobFileToken = request("POST", "/api/files/token", bobToken, null);
        HttpResponse<String> bobFile = rawRequest("GET", filePath + "?token=" + bobFileToken.get("token").asText(), null, null);
        assertEquals(403, bobFile.statusCode());
    }

    @Test
    void realtimeSendsRecordCreateUpdateAndDeleteEvents() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        request("POST", "/api/collections", token, Map.of(
                "name", "updates",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(Map.of("name", "title", "type", "text", "required", true))
        ));

        HttpResponse<InputStream> response = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/realtime"))
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );
        assertEquals(200, response.statusCode());

        try (SseReader events = new SseReader(response.body())) {
            SseEvent connect = events.next("PB_CONNECT");
            String clientId = mapper.readTree(connect.data()).get("clientId").asText();

            request("POST", "/api/realtime", token, Map.of(
                    "clientId", clientId,
                    "subscriptions", List.of("updates/*")
            ));

            JsonNode created = request("POST", "/api/collections/updates/records", token, Map.of("title", "created"));
            SseEvent createEvent = events.next("updates/*");
            JsonNode createData = mapper.readTree(createEvent.data());
            assertEquals("create", createData.get("action").asText());
            assertEquals("created", createData.get("record").get("title").asText());

            request("PATCH", "/api/collections/updates/records/" + created.get("id").asText(), token, Map.of("title", "updated"));
            SseEvent updateEvent = events.next("updates/*");
            JsonNode updateData = mapper.readTree(updateEvent.data());
            assertEquals("update", updateData.get("action").asText());
            assertEquals("updated", updateData.get("record").get("title").asText());

            request("DELETE", "/api/collections/updates/records/" + created.get("id").asText(), token, null);
            SseEvent deleteEvent = events.next("updates/*");
            JsonNode deleteData = mapper.readTree(deleteEvent.data());
            assertEquals("delete", deleteData.get("action").asText());
            assertEquals(created.get("id").asText(), deleteData.get("record").get("id").asText());
        }
    }

    @Test
    void realtimeRejectsAuthorizationChangesForExistingClient() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        HttpResponse<InputStream> response = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/realtime"))
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );
        assertEquals(200, response.statusCode());

        try (SseReader events = new SseReader(response.body())) {
            SseEvent connect = events.next("PB_CONNECT");
            String clientId = mapper.readTree(connect.data()).get("clientId").asText();

            HttpResponse<String> initial = rawRequest("POST", "/api/realtime", token, Map.of(
                    "clientId", clientId,
                    "subscriptions", List.of("updates/*")
            ));
            assertEquals(204, initial.statusCode());

            HttpResponse<String> changedAuth = rawRequest("POST", "/api/realtime", null, Map.of(
                    "clientId", clientId,
                    "subscriptions", List.of("updates/*")
            ));
            assertEquals(403, changedAuth.statusCode());
        }
    }

    @Test
    void realtimeSingleRecordSubscriptionsUseViewRuleAndRecordId() throws Exception {
        start();
        bootstrapSuperuser();
        String superuserToken = loginToken();

        request("POST", "/api/collections", superuserToken, Map.of(
                "name", "realtime_users",
                "type", "auth",
                "fields", List.of(Map.of("name", "displayName", "type", "text"))
        ));
        request("POST", "/api/collections/realtime_users/records", superuserToken, Map.of(
                "email", "alice-realtime@example.com",
                "password", "secret456",
                "displayName", "Alice"
        ));
        request("POST", "/api/collections/realtime_users/records", superuserToken, Map.of(
                "email", "bob-realtime@example.com",
                "password", "secret456",
                "displayName", "Bob"
        ));
        JsonNode aliceAuth = request("POST", "/api/collections/realtime_users/auth-with-password", null, Map.of(
                "identity", "alice-realtime@example.com",
                "password", "secret456"
        ));
        String aliceToken = aliceAuth.get("token").asText();
        String aliceId = aliceAuth.get("record").get("id").asText();

        request("POST", "/api/collections", superuserToken, Map.of(
                "name", "owned_updates",
                "viewRule", "owner = @request.auth.id",
                "fields", List.of(
                        Map.of("name", "owner", "type", "text", "required", true),
                        Map.of("name", "title", "type", "text", "required", true)
                )
        ));
        JsonNode aliceRecord = request("POST", "/api/collections/owned_updates/records", superuserToken, Map.of(
                "owner", aliceId,
                "title", "Alice private"
        ));
        JsonNode bobRecord = request("POST", "/api/collections/owned_updates/records", superuserToken, Map.of(
                "owner", "not-" + aliceId,
                "title", "Bob private"
        ));

        HttpResponse<InputStream> response = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/realtime"))
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );
        assertEquals(200, response.statusCode());

        try (SseReader events = new SseReader(response.body())) {
            SseEvent connect = events.next("PB_CONNECT");
            String clientId = mapper.readTree(connect.data()).get("clientId").asText();
            String topic = "owned_updates/" + aliceRecord.get("id").asText();

            request("POST", "/api/realtime", aliceToken, Map.of(
                    "clientId", clientId,
                    "subscriptions", List.of(topic)
            ));

            request("PATCH", "/api/collections/owned_updates/records/" + bobRecord.get("id").asText(), superuserToken, Map.of(
                    "title", "Bob updated"
            ));
            request("PATCH", "/api/collections/owned_updates/records/" + aliceRecord.get("id").asText(), superuserToken, Map.of(
                    "title", "Alice updated"
            ));

            SseEvent event = events.next(topic);
            JsonNode data = mapper.readTree(event.data());
            assertEquals("update", data.get("action").asText());
            assertEquals(aliceRecord.get("id").asText(), data.get("record").get("id").asText());
            assertEquals("Alice updated", data.get("record").get("title").asText());
        }
    }

    @Test
    void realtimeAcceptsOfficialQueryOptionsFilter() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        request("POST", "/api/collections", token, Map.of(
                "name", "updates",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(
                        Map.of("name", "title", "type", "text", "required", true),
                        Map.of("name", "status", "type", "text")
                )
        ));

        HttpResponse<InputStream> response = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/realtime"))
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );
        assertEquals(200, response.statusCode());

        try (SseReader events = new SseReader(response.body())) {
            SseEvent connect = events.next("PB_CONNECT");
            String clientId = mapper.readTree(connect.data()).get("clientId").asText();
            String options = URLEncoder.encode(
                    "{\"query\":{\"filter\":\"status = 'active'\"}}",
                    StandardCharsets.UTF_8
            );

            request("POST", "/api/realtime?clientId=" + clientId
                    + "&subscriptions%5B0%5D=updates/*&options=" + options, token, null);

            request("POST", "/api/collections/updates/records", token, Map.of(
                    "title", "inactive",
                    "status", "inactive"
            ));
            JsonNode active = request("POST", "/api/collections/updates/records", token, Map.of(
                    "title", "active",
                    "status", "active"
            ));

            SseEvent event = events.next("updates/*");
            JsonNode data = mapper.readTree(event.data());
            assertEquals("create", data.get("action").asText());
            assertEquals(active.get("id").asText(), data.get("record").get("id").asText());
            assertEquals("active", data.get("record").get("title").asText());
        }
    }

    @Test
    void realtimeOptionsQueryExpandRelationRecords() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        JsonNode authors = request("POST", "/api/collections", token, Map.of(
                "name", "authors",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(Map.of("name", "name", "type", "text", "required", true))
        ));
        request("POST", "/api/collections", token, Map.of(
                "name", "posts",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(
                        Map.of("name", "title", "type", "text", "required", true),
                        Map.of("name", "author", "type", "relation", "collectionId", authors.get("id").asText())
                )
        ));
        JsonNode author = request("POST", "/api/collections/authors/records", token, Map.of("name", "Ada"));

        HttpResponse<InputStream> response = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/realtime"))
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );
        assertEquals(200, response.statusCode());

        try (SseReader events = new SseReader(response.body())) {
            SseEvent connect = events.next("PB_CONNECT");
            String clientId = mapper.readTree(connect.data()).get("clientId").asText();
            String options = URLEncoder.encode(
                    "{\"query\":{\"expand\":\"author\",\"fields\":\"id,expand.author.name\"}}",
                    StandardCharsets.UTF_8
            );

            request("POST", "/api/realtime?clientId=" + clientId
                    + "&subscriptions%5B0%5D=posts/*&options=" + options, token, null);

            JsonNode post = request("POST", "/api/collections/posts/records", token, Map.of(
                    "title", "Expandable realtime",
                    "author", author.get("id").asText()
            ));

            SseEvent event = events.next("posts/*");
            JsonNode data = mapper.readTree(event.data());
            JsonNode record = data.get("record");
            assertEquals(post.get("id").asText(), record.get("id").asText());
            assertFalse(record.has("title"));
            assertEquals("Ada", record.get("expand").get("author").get("name").asText());
            assertFalse(record.get("expand").get("author").has("id"));
        }
    }

    @Test
    void realtimeAcceptsMultipartSubscriptionBody() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        request("POST", "/api/collections", token, Map.of(
                "name", "multipart_updates",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(Map.of("name", "title", "type", "text", "required", true))
        ));

        HttpResponse<InputStream> response = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/realtime"))
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );
        assertEquals(200, response.statusCode());

        try (SseReader events = new SseReader(response.body())) {
            SseEvent connect = events.next("PB_CONNECT");
            String clientId = mapper.readTree(connect.data()).get("clientId").asText();

            HttpResponse<String> subscribe = rawMultipartRequest("POST", "/api/realtime", token, Map.of(
                    "clientId", clientId,
                    "subscriptions[0]", "multipart_updates/*"
            ), Map.of());
            assertEquals(204, subscribe.statusCode());

            JsonNode created = request("POST", "/api/collections/multipart_updates/records", token, Map.of("title", "created"));
            SseEvent event = events.next("multipart_updates/*");
            JsonNode data = mapper.readTree(event.data());
            assertEquals("create", data.get("action").asText());
            assertEquals(created.get("id").asText(), data.get("record").get("id").asText());
        }
    }

    private void start() throws IOException {
        server = LocalPocketBase.start(new ServerConfig("127.0.0.1", 0, tempDir, null, null));
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

    private JsonNode multipartRequest(
            String method,
            String path,
            String token,
            Map<String, String> fields,
            Map<String, MultipartFile> files
    ) throws Exception {
        HttpResponse<String> response = rawMultipartRequest(method, path, token, fields, files);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AssertionError(response.statusCode() + " " + response.body());
        }
        return mapper.readTree(response.body());
    }

    private HttpResponse<String> rawMultipartRequest(
            String method,
            String path,
            String token,
            Map<String, String> fields,
            Map<String, MultipartFile> files
    ) throws Exception {
        String boundary = "----pocketbase-java-test-boundary";
        byte[] body = multipartBody(boundary, fields, files);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private byte[] multipartBody(String boundary, Map<String, String> fields, Map<String, MultipartFile> files) {
        List<byte[]> chunks = new java.util.ArrayList<>();
        fields.forEach((name, value) -> {
            String part = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                    + value + "\r\n";
            chunks.add(part.getBytes(StandardCharsets.UTF_8));
        });
        files.forEach((name, file) -> {
            String partHead = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + file.name() + "\"\r\n"
                    + "Content-Type: " + file.contentType() + "\r\n\r\n";
            chunks.add(partHead.getBytes(StandardCharsets.UTF_8));
            chunks.add(file.bytes());
            chunks.add("\r\n".getBytes(StandardCharsets.UTF_8));
        });
        chunks.add(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        int size = chunks.stream().mapToInt(bytes -> bytes.length).sum();
        byte[] body = new byte[size];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, body, offset, chunk.length);
            offset += chunk.length;
        }
        return body;
    }

    private boolean storageContainsFilename(String text) throws IOException {
        Path storage = tempDir.resolve("storage");
        if (!Files.exists(storage)) {
            return false;
        }
        try (var paths = Files.walk(storage)) {
            return paths.anyMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().contains(text));
        }
    }

    private byte[] pngBytes(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width / 2, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private List<Integer> imageSize(byte[] bytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(image);
        return List.of(image.getWidth(), image.getHeight());
    }

    private record MultipartFile(String name, String contentType, byte[] bytes) {
    }

    private record SseEvent(String event, String data) {
    }

    private static final class SseReader implements AutoCloseable {
        private final BufferedReader reader;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        private SseReader(InputStream input) {
            this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        }

        private SseEvent next(String expectedEvent) throws Exception {
            return executor.submit(() -> {
                String event = null;
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        if (event != null) {
                            SseEvent next = new SseEvent(event, data.toString());
                            if (expectedEvent == null || expectedEvent.equals(next.event())) {
                                return next;
                            }
                        }
                        event = null;
                        data.setLength(0);
                        continue;
                    }
                    if (line.startsWith("event:")) {
                        event = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:")) {
                        if (!data.isEmpty()) {
                            data.append('\n');
                        }
                        data.append(line.substring("data:".length()).trim());
                    }
                }
                throw new AssertionError("SSE stream ended before event " + expectedEvent);
            }).get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws IOException {
            executor.shutdownNow();
            reader.close();
        }
    }
}
