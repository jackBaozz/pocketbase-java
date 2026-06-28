package io.github.jackbaozz.pocketbase.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.jackbaozz.pocketbase.client.AuthResponse;
import io.github.jackbaozz.pocketbase.client.PocketBaseClient;
import io.github.jackbaozz.pocketbase.client.RecordList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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
    void acceptsOfficialSdkAuthorizationHeaderWithoutBearerPrefix() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/settings"))
                        .header("Accept", "application/json")
                        .header("Authorization", token)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        assertEquals(200, response.statusCode());
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

        JsonNode fullPage = request("GET", "/api/collections?filter=" + filter + "&sort=name&page=1&perPage=10", token, null);
        JsonNode alpha = null;
        for (JsonNode collection : fullPage.get("items")) {
            if ("collection_api_alpha".equals(collection.get("name").asText())) {
                alpha = collection;
                break;
            }
        }
        assertNotNull(alpha);
        assertTrue(fieldNames(alpha).contains("name"));

        HttpResponse<String> invalidFilter = rawRequest(
                "GET",
                "/api/collections?filter=" + URLEncoder.encode("name #", StandardCharsets.UTF_8),
                token,
                null
        );
        assertEquals(400, invalidFilter.statusCode());
        assertFieldErrorMessageStartsWith(invalidFilter, 400, "Invalid filter.", "filter", "validation_invalid_value", "Invalid filter");

        JsonNode single = request("GET", "/api/collections/collection_api_alpha?fields=id,name", token, null);
        assertEquals("collection_api_alpha", single.get("name").asText());
        assertTrue(single.has("id"));
        assertFalse(single.has("type"));
    }

    @Test
    void collectionMetaApisReturnScaffoldsAndOAuth2Providers() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        assertEquals(401, rawRequest("GET", "/api/collections/meta/scaffolds", null, null).statusCode());
        assertEquals(401, rawRequest("GET", "/api/collections/meta/oauth2-providers", null, null).statusCode());

        JsonNode scaffolds = request("GET", "/api/collections/meta/scaffolds", token, null);
        assertEquals("base", scaffolds.get("base").get("type").asText());
        assertEquals("auth", scaffolds.get("auth").get("type").asText());
        assertEquals("view", scaffolds.get("view").get("type").asText());
        assertTrue(fieldNames(scaffolds.get("auth")).containsAll(List.of("email", "password", "verified")));
        assertTrue(scaffolds.get("view").has("viewQuery"));

        JsonNode providers = request("GET", "/api/collections/meta/oauth2-providers", token, null);
        List<String> names = providerNames(providers);
        assertTrue(names.containsAll(List.of("apple", "github", "google", "microsoft", "oidc")));
        assertTrue(providers.get(0).has("displayName"));
        assertTrue(providers.get(0).has("logo"));
    }

    @Test
    void unsupportedMethodsAndBadJsonUseOfficialEnvelope() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        HttpResponse<String> unsupported = rawRequest("DELETE", "/api/settings", token, null);
        assertEquals(405, unsupported.statusCode());
        assertErrorEnvelope(unsupported, 405, "Method not allowed.");

        HttpResponse<String> badJson = rawJsonRequest("POST", "/api/collections", token, "{\"name\":");
        assertEquals(400, badJson.statusCode());
        assertFieldError(badJson, 400, "Failed to read request body.", "body", "validation_invalid_value", "Invalid JSON payload.");

        HttpResponse<String> settingsArray = rawJsonRequest("PATCH", "/api/settings", token, "[]");
        assertEquals(400, settingsArray.statusCode());
        assertFieldError(settingsArray, 400, "Settings payload must be a JSON object.", "body", "validation_invalid_value", "Request body must be a JSON object.");

        HttpResponse<String> collectionArray = rawJsonRequest("POST", "/api/collections", token, "[]");
        assertEquals(400, collectionArray.statusCode());
        assertFieldError(collectionArray, 400, "Collection payload must be a JSON object.", "body", "validation_invalid_value", "Request body must be a JSON object.");

        request("POST", "/api/collections", token, Map.of(
                "name", "payload_posts",
                "fields", List.of(Map.of("name", "title", "type", "text", "required", true))
        ));
        HttpResponse<String> recordArray = rawJsonRequest("POST", "/api/collections/payload_posts/records", token, "[]");
        assertEquals(400, recordArray.statusCode());
        assertFieldError(recordArray, 400, "Record payload must be a JSON object.", "body", "validation_invalid_value", "Request body must be a JSON object.");

        request("POST", "/api/collections/payload_posts/records", token, Map.of("title", "filter target"));
        HttpResponse<String> recordFilter = rawRequest(
                "GET",
                "/api/collections/payload_posts/records?filter=" + URLEncoder.encode("title #", StandardCharsets.UTF_8),
                token,
                null
        );
        assertEquals(400, recordFilter.statusCode());
        assertFieldErrorMessageStartsWith(recordFilter, 400, "Invalid filter.", "filter", "validation_invalid_value", "Invalid filter");

        HttpResponse<String> dryRunArray = rawJsonRequest("POST", "/api/collections/meta/dry-run-view", token, "[]");
        assertEquals(400, dryRunArray.statusCode());
        assertFieldError(dryRunArray, 400, "An error occurred while loading the submitted data.", "body", "validation_invalid_value", "Request body must be a JSON object.");
    }

    @Test
    void dryRunViewPreviewsSelectQueriesAndRejectsWriteStatements() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        assertEquals(401, rawRequest("POST", "/api/collections/meta/dry-run-view", null, Map.of(
                "query", "select 1"
        )).statusCode());

        JsonNode preview = request("POST", "/api/collections/meta/dry-run-view", token, Map.of(
                "query", "select 1 as one; select 'last' as final_value"
        ));
        assertEquals("final_value", preview.get("columns").get(0).get("name").asText());
        assertEquals("last", preview.get("rows").get(0).get(0).asText());

        HttpResponse<String> writeQuery = rawRequest("POST", "/api/collections/meta/dry-run-view", token, Map.of(
                "query", "insert into t values (1)"
        ));
        assertEquals(400, writeQuery.statusCode());
        assertFieldError(
                writeQuery,
                400,
                "Invalid view query. Raw error:\nwrite statements are not allowed",
                "query",
                "validation_invalid_value",
                "Invalid view query. Raw error:\nwrite statements are not allowed"
        );

        HttpResponse<String> missingQuery = rawRequest("POST", "/api/collections/meta/dry-run-view", token, Map.of(
                "query", ""
        ));
        assertEquals(400, missingQuery.statusCode());
        assertFieldError(missingQuery, 400, "An error occurred while validating the submitted data.", "query", "validation_required", "Cannot be blank.");
    }

    @Test
    void collectionImportAndTruncateApisMatchOfficialRoutes() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        assertEquals(401, rawRequest("PUT", "/api/collections/import", null, Map.of(
                "collections", List.of()
        )).statusCode());

        JsonNode keep = request("POST", "/api/collections", token, Map.of(
                "name", "import_keep",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(
                        Map.of("name", "title", "type", "text", "required", true),
                        Map.of("name", "attachment", "type", "file")
                )
        ));
        JsonNode obsolete = request("POST", "/api/collections", token, Map.of(
                "name", "import_obsolete",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(Map.of("name", "title", "type", "text", "required", true))
        ));

        JsonNode keptRecord = multipartRequest("POST", "/api/collections/import_keep/records", token, Map.of(
                "title", "kept"
        ), Map.of(
                "attachment", new MultipartFile("kept.txt", "text/plain", "kept file".getBytes(StandardCharsets.UTF_8))
        ));
        String keptFilename = keptRecord.get("attachment").asText();
        request("POST", "/api/collections/import_obsolete/records", token, Map.of("title", "obsolete"));

        HttpResponse<String> emptyImport = rawRequest("PUT", "/api/collections/import", token, Map.of(
                "collections", List.of()
        ));
        assertEquals(400, emptyImport.statusCode());
        assertFieldError(emptyImport, 400, "Failed to import collections.", "collections", "validation_required", "Cannot be blank.");
        JsonNode stillThere = request("GET", "/api/collections/" + obsolete.get("id").asText(), token, null);
        assertEquals("import_obsolete", stillThere.get("name").asText());

        HttpResponse<String> imported = rawRequest("PUT", "/api/collections/import", token, Map.of(
                "deleteMissing", true,
                "collections", List.of(
                        Map.of(
                                "id", keep.get("id").asText(),
                                "name", "import_keep_renamed",
                                "type", "base",
                                "listRule", "",
                                "viewRule", "",
                                "fields", List.of(
                                        Map.of("name", "title", "type", "text", "required", true),
                                        Map.of("name", "status", "type", "text"),
                                        Map.of("name", "attachment", "type", "file")
                                )
                        ),
                        Map.of(
                                "name", "import_auth_users",
                                "type", "auth"
                        )
                )
        ));
        assertEquals(204, imported.statusCode());

        HttpResponse<String> oldName = rawRequest("GET", "/api/collections/import_keep", token, null);
        assertEquals(404, oldName.statusCode());
        JsonNode renamed = request("GET", "/api/collections/import_keep_renamed", token, null);
        assertEquals(keep.get("id").asText(), renamed.get("id").asText());
        assertEquals(3, renamed.get("fields").size());

        JsonNode records = request("GET", "/api/collections/import_keep_renamed/records", null, null);
        assertEquals(1, records.get("totalItems").asInt());
        assertEquals("kept", records.get("items").get(0).get("title").asText());
        HttpResponse<String> keptFile = rawRequest(
                "GET",
                "/api/files/import_keep_renamed/" + keptRecord.get("id").asText() + "/" + keptFilename,
                null,
                null
        );
        assertEquals(200, keptFile.statusCode());

        assertEquals(404, rawRequest("GET", "/api/collections/import_obsolete", token, null).statusCode());
        assertFalse(Files.exists(tempDir.resolve("records").resolve(obsolete.get("id").asText() + ".json")));

        JsonNode authCollection = request("GET", "/api/collections/import_auth_users", token, null);
        assertEquals("auth", authCollection.get("type").asText());
        assertTrue(fieldNames(authCollection).containsAll(List.of("email", "password", "verified")));
        JsonNode superusers = request("GET", "/api/collections/_superusers", token, null);
        assertEquals("_superusers", superusers.get("name").asText());

        HttpResponse<String> deleteSystem = rawRequest("DELETE", "/api/collections/_superusers", token, null);
        assertEquals(400, deleteSystem.statusCode());
        assertErrorEnvelope(deleteSystem, 400, "System collections cannot be deleted.");

        HttpResponse<String> truncateSystem = rawRequest("DELETE", "/api/collections/_superusers/truncate", token, null);
        assertEquals(400, truncateSystem.statusCode());
        assertErrorEnvelope(truncateSystem, 400, "System collections cannot be truncated.");

        HttpResponse<String> truncated = rawRequest("DELETE", "/api/collections/import_keep_renamed/truncate", token, null);
        assertEquals(204, truncated.statusCode());
        JsonNode empty = request("GET", "/api/collections/import_keep_renamed/records", null, null);
        assertEquals(0, empty.get("totalItems").asInt());
        assertFalse(Files.exists(tempDir.resolve("storage").resolve(keep.get("id").asText())));
    }

    @Test
    void actionErrorsUseOfficialEnvelope() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        request("POST", "/api/collections", token, Map.of(
                "name", "action_base_posts",
                "fields", List.of(Map.of("name", "title", "type", "text"))
        ));
        HttpResponse<String> baseAuth = rawRequest("POST", "/api/collections/action_base_posts/auth-with-password", null, Map.of(
                "identity", "dev@example.com",
                "password", "secret123"
        ));
        assertEquals(400, baseAuth.statusCode());
        assertErrorEnvelope(baseAuth, 400, "The collection is not an auth collection.");

        request("POST", "/api/collections", token, Map.of(
                "name", "readonly_view",
                "type", "view",
                "options", Map.of("query", "select 'view-record' as id")
        ));
        HttpResponse<String> createViewRecord = rawRequest("POST", "/api/collections/readonly_view/records", token, Map.of(
                "title", "should fail"
        ));
        assertEquals(400, createViewRecord.statusCode());
        assertErrorEnvelope(createViewRecord, 400, "View collections are read-only.");

        HttpResponse<String> updateViewRecord = rawRequest("PATCH", "/api/collections/readonly_view/records/view-record", token, Map.of(
                "title", "should fail"
        ));
        assertEquals(400, updateViewRecord.statusCode());
        assertErrorEnvelope(updateViewRecord, 400, "View collections are read-only.");
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

        HttpResponse<String> missingUpload = rawMultipartRequest("POST", "/api/backups", token, Map.of(), Map.of());
        assertEquals(400, missingUpload.statusCode());
        assertFieldError(missingUpload, 400, "Backup file is required.", "file", "validation_required", "Cannot be blank.");

        HttpResponse<String> missingMultipartBoundary = rawBodyRequest(
                "POST",
                "/api/backups",
                token,
                "multipart/form-data",
                "not a valid multipart payload".getBytes(StandardCharsets.UTF_8)
        );
        assertEquals(400, missingMultipartBoundary.statusCode());
        assertFieldError(missingMultipartBoundary, 400, "Failed to read request body.", "body", "validation_required", "Cannot be blank.");

        HttpResponse<String> invalidUpload = rawMultipartRequest("POST", "/api/backups", token, Map.of(), Map.of(
                "file", new MultipartFile("broken.zip", "application/zip", "not a zip".getBytes(StandardCharsets.UTF_8))
        ));
        assertEquals(400, invalidUpload.statusCode());
        assertFieldError(invalidUpload, 400, "Invalid backup archive.", "file", "validation_invalid_value", "Invalid backup archive.");

        JsonNode backup = request("POST", "/api/backups", token, Map.of("name", "snap.zip"));
        assertEquals("snap.zip", backup.get("key").asText());
        assertTrue(backup.get("size").asLong() > 0);

        HttpResponse<String> duplicateBackup = rawRequest("POST", "/api/backups", token, Map.of("name", "snap.zip"));
        assertEquals(400, duplicateBackup.statusCode());
        assertFieldError(duplicateBackup, 400, "Backup already exists.", "name", "validation_not_unique", "Value must be unique.");

        HttpResponse<String> duplicateUpload = rawMultipartRequest("POST", "/api/backups", token, Map.of(), Map.of(
                "file", new MultipartFile("snap.zip", "application/zip", Files.readAllBytes(tempDir.resolve("backups").resolve("snap.zip")))
        ));
        assertEquals(400, duplicateUpload.statusCode());
        assertFieldError(duplicateUpload, 400, "Backup already exists.", "file", "validation_not_unique", "Value must be unique.");

        JsonNode backups = request("GET", "/api/backups", token, null);
        assertEquals(1, backups.get("totalItems").asInt());
        assertEquals("snap.zip", backups.get("items").get(0).get("key").asText());

        HttpResponse<String> missingDownload = rawRequest("GET", "/api/backups/missing.zip", token, null);
        assertEquals(404, missingDownload.statusCode());
        assertErrorEnvelope(missingDownload, 404, "Backup not found.");

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

        appendZipEntry(
                tempDir.resolve("backups").resolve("snap.zip"),
                tempDir.resolve("backups").resolve("evil.zip"),
                "storage/../escape.txt",
                "malicious restore entry".getBytes(StandardCharsets.UTF_8)
        );
        HttpResponse<String> evilRestore = rawRequest("POST", "/api/backups/evil.zip/restore", token, null);
        assertEquals(400, evilRestore.statusCode());
        assertFieldError(evilRestore, 400, "Invalid backup archive.", "file", "validation_invalid_value", "Invalid backup archive.");
        JsonNode stillChanged = request("GET", "/api/collections/tasks/records", null, null);
        assertEquals(2, stillChanged.get("totalItems").asInt());

        request("POST", "/api/backups/snap.zip/restore", token, null);
        JsonNode restored = request("GET", "/api/collections/tasks/records", null, null);
        assertEquals(1, restored.get("totalItems").asInt());
        assertEquals("before backup", restored.get("items").get(0).get("name").asText());

        HttpResponse<String> deleted = rawRequest("DELETE", "/api/backups/snap.zip", token, null);
        assertEquals(204, deleted.statusCode());

        HttpResponse<String> deleteMissing = rawRequest("DELETE", "/api/backups/snap.zip", token, null);
        assertEquals(404, deleteMissing.statusCode());
        assertErrorEnvelope(deleteMissing, 404, "Backup not found.");
    }

    @Test
    void settingsAndLogsApisRequireSuperuserPersistAndOmitSecrets() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        assertEquals(401, rawRequest("GET", "/api/settings", null, null).statusCode());
        assertEquals(401, rawRequest("GET", "/api/logs", null, null).statusCode());

        JsonNode settings = request("GET", "/api/settings", token, null);
        assertEquals("pocketbase-java", settings.get("meta").get("appName").asText());
        assertTrue(settings.has("superuserIPs"));
        assertTrue(settings.get("meta").has("accentColor"));
        assertTrue(settings.get("logs").has("logIP"));
        assertTrue(settings.get("rateLimits").has("excludedIPs"));
        assertTrue(settings.get("rateLimits").get("rules").size() >= 4);
        assertTrue(settings.get("trustedProxy").has("useLeftmostIP"));

        JsonNode updated = request("PATCH", "/api/settings", token, Map.of(
                "meta", Map.of(
                        "appName", "Demo Console",
                        "appUrl", "https://example.test"
                ),
                "logs", Map.of(
                        "maxDays", 14,
                        "logIp", true,
                        "logAuthId", true
                ),
                "smtp", Map.of(
                        "enabled", true,
                        "host", "smtp.example.test",
                        "password", "smtp-secret"
                ),
                "s3", Map.of(
                        "accessKey", "access-secret",
                        "secret", "storage-secret"
                )
        ));
        assertEquals("Demo Console", updated.get("meta").get("appName").asText());
        assertEquals("https://example.test", updated.get("meta").get("appURL").asText());
        assertTrue(updated.get("logs").get("logIP").asBoolean());
        assertFalse(updated.get("smtp").has("password"));
        assertEquals("access-secret", updated.get("s3").get("accessKey").asText());
        assertFalse(updated.get("s3").has("secret"));

        request("PATCH", "/api/settings", token, Map.of(
                "smtp", Map.of("password", "******"),
                "s3", Map.of("secret", "******")
        ));
        if ("sqlite".equals(System.getProperty("storage"))) {
            try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("pocketbase.db").toAbsolutePath());
                 java.sql.Statement stmt = conn.createStatement();
                 java.sql.ResultSet rs = stmt.executeQuery("SELECT value FROM _params WHERE key = 'settings'")) {
                assertTrue(rs.next());
                String settingsVal = rs.getString(1);
                assertTrue(settingsVal.contains("smtp-secret"));
                assertTrue(settingsVal.contains("storage-secret"));
            }
        } else {
            String settingsFile = Files.readString(tempDir.resolve("pb_settings.json"), StandardCharsets.UTF_8);
            assertTrue(settingsFile.contains("smtp-secret"));
            assertTrue(settingsFile.contains("storage-secret"));
        }

        JsonNode filteredLogs = request(
                "GET",
                "/api/logs?perPage=50&sort=-created&filter=" + URLEncoder.encode("data.status = 200", StandardCharsets.UTF_8),
                token,
                null
        );
        assertTrue(filteredLogs.get("totalItems").asInt() >= 1);
        JsonNode log = filteredLogs.get("items").get(0);
        assertTrue(log.hasNonNull("id"));
        assertEquals(200, log.get("data").get("status").asInt());
        assertTrue(log.get("data").hasNonNull("method"));
        assertTrue(log.get("data").hasNonNull("url"));
        assertTrue(log.get("data").hasNonNull("authId"));
        assertTrue(log.get("data").get("execTime").asDouble() >= 0.0D);

        HttpResponse<String> invalidLogFilter = rawRequest(
                "GET",
                "/api/logs?filter=" + URLEncoder.encode("data.status #", StandardCharsets.UTF_8),
                token,
                null
        );
        assertEquals(400, invalidLogFilter.statusCode());
        assertFieldErrorMessageStartsWith(invalidLogFilter, 400, "Invalid filter.", "filter", "validation_invalid_value", "Invalid filter");

        JsonNode singleLog = request("GET", "/api/logs/" + log.get("id").asText(), token, null);
        assertEquals(log.get("id").asText(), singleLog.get("id").asText());

        JsonNode stats = request("GET", "/api/logs/stats", token, null);
        assertTrue(stats.isArray());
        assertTrue(stats.size() >= 1);
        assertTrue(stats.get(0).get("total").asInt() >= 1);
        assertTrue(stats.get(0).get("date").asText().endsWith(":00:00.000Z"));
        assertTrue(stats.get(0).get("date").asText().contains(" "));

        JsonNode rowidLogs = request("GET", "/api/logs?perPage=1&sort=-@rowid", token, null);
        assertTrue(rowidLogs.get("totalItems").asInt() >= 1);

        assertEquals(401, rawRequest("POST", "/api/settings/test/email", null, Map.of(
                "email", "dev@example.com",
                "template", "verification"
        )).statusCode());

        HttpResponse<String> invalidS3 = rawRequest("POST", "/api/settings/test/s3", token, Map.of(
                "filesystem", "invalid"
        ));
        assertEquals(400, invalidS3.statusCode());
        assertFieldError(invalidS3, 400, "Failed to test the S3 filesystem.", "filesystem", "validation_invalid_value", "Must be either storage or backups.");

        HttpResponse<String> missingS3 = rawRequest("POST", "/api/settings/test/s3", token, Map.of());
        assertEquals(400, missingS3.statusCode());
        assertFieldError(missingS3, 400, "Failed to test the S3 filesystem.", "filesystem", "validation_required", "Cannot be blank.");

        HttpResponse<String> disabledS3 = rawRequest("POST", "/api/settings/test/s3", token, Map.of(
                "filesystem", "storage"
        ));
        assertEquals(400, disabledS3.statusCode());
        assertFieldError(
                disabledS3,
                400,
                "Failed to test the S3 filesystem. Raw error: \nS3 storage filesystem is not enabled",
                "filesystem",
                "validation_invalid_value",
                "S3 storage filesystem is not enabled"
        );

        HttpResponse<String> missingS3Bucket = rawRequest("POST", "/api/settings/test/s3", token, Map.of(
                "filesystem", "storage",
                "s3", Map.of(
                        "enabled", true,
                        "region", "us-east-1",
                        "accessKey", "access-key",
                        "secret", "secret-key"
                )
        ));
        assertEquals(400, missingS3Bucket.statusCode());
        assertFieldError(missingS3Bucket, 400, "Failed to test the S3 filesystem.", "bucket", "validation_required", "Cannot be blank.");

        HttpResponse<String> missingBackupS3Secret = rawRequest("POST", "/api/settings/test/s3", token, Map.of(
                "filesystem", "backups",
                "backups", Map.of(
                        "s3", Map.of(
                                "enabled", true,
                                "bucket", "backup-bucket",
                                "region", "us-east-1",
                                "accessKey", "backup-access-key"
                        )
                )
        ));
        assertEquals(400, missingBackupS3Secret.statusCode());
        assertFieldError(missingBackupS3Secret, 400, "Failed to test the S3 filesystem.", "secret", "validation_required", "Cannot be blank.");

        HttpResponse<String> queuedEmail = rawRequest("POST", "/api/settings/test/email", token, Map.of(
                "email", "dev@example.com",
                "template", "verification",
                "smtp", Map.of("enabled", false)
        ));
        assertEquals(204, queuedEmail.statusCode());
        JsonNode emailRequests = mapper.readTree(tempDir.resolve("auth_requests.json").toFile());
        JsonNode queued = emailRequests.get(emailRequests.size() - 1);
        assertEquals("testEmail", queued.get("type").asText());
        assertEquals("verification", queued.get("template").asText());
        assertEquals("dev@example.com", queued.get("email").asText());

        HttpResponse<String> invalidEmail = rawRequest("POST", "/api/settings/test/email", token, Map.of(
                "email", "dev@example.com",
                "template", "unknown"
        ));
        assertEquals(400, invalidEmail.statusCode());
        assertFieldError(invalidEmail, 400, "Failed to send the test email.", "template", "validation_invalid_value", "Invalid email template.");

        HttpResponse<String> missingTemplate = rawRequest("POST", "/api/settings/test/email", token, Map.of(
                "email", "dev@example.com"
        ));
        assertEquals(400, missingTemplate.statusCode());
        assertFieldError(missingTemplate, 400, "Failed to send the test email.", "template", "validation_required", "Cannot be blank.");

        try (FakeSmtpServer smtp = FakeSmtpServer.start("421 test smtp down")) {
            HttpResponse<String> smtpFailure = rawRequest("POST", "/api/settings/test/email", token, Map.of(
                    "email", "dev@example.com",
                    "template", "verification",
                    "smtp", Map.of(
                            "enabled", true,
                            "host", "127.0.0.1",
                            "port", smtp.port()
                    )
            ));
            assertEquals(400, smtpFailure.statusCode());
            assertFieldError(
                    smtpFailure,
                    400,
                    "Failed to send the test email. Raw error: \nSMTP command failed: 421 test smtp down",
                    "smtp",
                    "validation_invalid_value",
                    "SMTP command failed: 421 test smtp down"
            );
        }

        HttpResponse<String> missingAppleClientId = rawRequest("POST", "/api/settings/apple/generate-client-secret", token, Map.of(
                "teamId", "TEAMID1234",
                "keyId", "KEYID12345",
                "privateKey", ecPrivateKeyPem(),
                "duration", 3600
        ));
        assertEquals(400, missingAppleClientId.statusCode());
        assertFieldError(missingAppleClientId, 400, "Invalid client secret data.", "clientId", "validation_required", "Cannot be blank.");

        HttpResponse<String> invalidAppleDuration = rawRequest("POST", "/api/settings/apple/generate-client-secret", token, Map.of(
                "clientId", "com.example.service",
                "teamId", "TEAMID1234",
                "keyId", "KEYID12345",
                "privateKey", ecPrivateKeyPem(),
                "duration", 0
        ));
        assertEquals(400, invalidAppleDuration.statusCode());
        assertFieldError(invalidAppleDuration, 400, "Invalid client secret data.", "duration", "validation_invalid_value", "Must be between 1 and 15777000 seconds.");

        HttpResponse<String> invalidApplePrivateKey = rawRequest("POST", "/api/settings/apple/generate-client-secret", token, Map.of(
                "clientId", "com.example.service",
                "teamId", "TEAMID1234",
                "keyId", "KEYID12345",
                "privateKey", "-----BEGIN PRIVATE KEY-----\nnot-valid-base64\n-----END PRIVATE KEY-----",
                "duration", 3600
        ));
        assertEquals(400, invalidApplePrivateKey.statusCode());
        assertFieldError(invalidApplePrivateKey, 400, "Invalid client secret data.", "privateKey", "validation_invalid_value", "Must be a valid PKCS#8 EC private key PEM.");

        JsonNode apple = request("POST", "/api/settings/apple/generate-client-secret", token, Map.of(
                "clientId", "com.example.service",
                "teamId", "TEAMID1234",
                "keyId", "KEYID12345",
                "privateKey", ecPrivateKeyPem(),
                "duration", 3600
        ));
        assertAppleClientSecret(apple.get("secret").asText());

        server.close();
        start();
        JsonNode persisted = request("GET", "/api/settings", token, null);
        assertEquals("Demo Console", persisted.get("meta").get("appName").asText());
        assertFalse(persisted.get("smtp").has("password"));
        assertFalse(persisted.get("s3").has("secret"));
    }

    @Test
    void settingsTestEmailCanSendThroughConfiguredSmtp() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        try (FakeSmtpServer smtp = FakeSmtpServer.start()) {
            request("PATCH", "/api/settings", token, Map.of(
                    "meta", Map.of(
                            "senderName", "PocketBase Java",
                            "senderAddress", "noreply@example.com"
                    ),
                    "smtp", Map.of(
                            "enabled", true,
                            "host", "127.0.0.1",
                            "port", smtp.port(),
                            "tls", false
                    )
            ));

            HttpResponse<String> response = rawRequest("POST", "/api/settings/test/email", token, Map.of(
                    "email", "dev@example.com",
                    "template", "password-reset"
            ));

            assertEquals(204, response.statusCode());
            assertTrue(smtp.message().contains("Reset password request"));
            assertTrue(smtp.message().contains("dev@example.com"));
        }
    }

    @Test
    void cronsApisListBuiltInsAndRunAutoBackup() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        assertEquals(401, rawRequest("GET", "/api/crons", null, null).statusCode());
        assertEquals(401, rawRequest("POST", "/api/crons/__pbLogsCleanup__", null, null).statusCode());

        JsonNode crons = request("GET", "/api/crons", token, null);
        assertTrue(cronExists(crons, "__pbLogsCleanup__", "0 */6 * * *"));
        assertTrue(cronExists(crons, "__pbDBOptimize__", "0 0 * * *"));
        assertTrue(cronExists(crons, "__pbMFACleanup__", "0 * * * *"));
        assertTrue(cronExists(crons, "__pbOTPCleanup__", "0 * * * *"));
        assertFalse(cronExists(crons, "__pbAutoBackup__", "* * * * *"));

        assertEquals(404, rawRequest("POST", "/api/crons/missing", token, null).statusCode());

        request("PATCH", "/api/settings", token, Map.of(
                "backups", Map.of(
                        "cron", "* * * * *",
                        "cronMaxKeep", 1
                )
        ));
        JsonNode withAutoBackup = request("GET", "/api/crons", token, null);
        assertTrue(cronExists(withAutoBackup, "__pbAutoBackup__", "* * * * *"));

        HttpResponse<String> run = rawRequest("POST", "/api/crons/__pbAutoBackup__", token, null);
        assertEquals(204, run.statusCode());
        assertTrue(waitForAutoBackupCount(1));
    }

    @Test
    void sqlApiRunsSuperuserQueriesAndMutatesJsonCollections() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        assertEquals(401, rawRequest("POST", "/api/sql", null, Map.of("query", "select 1")).statusCode());

        request("POST", "/api/collections", token, Map.of(
                "name", "sql_users",
                "type", "auth"
        ));
        request("POST", "/api/collections/sql_users/records", token, Map.of(
                "email", "sql-user@example.com",
                "password", "secret123",
                "passwordConfirm", "secret123",
                "verified", true
        ));
        JsonNode userAuth = request("POST", "/api/collections/sql_users/auth-with-password", null, Map.of(
                "identity", "sql-user@example.com",
                "password", "secret123"
        ));
        assertEquals(403, rawRequest("POST", "/api/sql", userAuth.get("token").asText(), Map.of("query", "select 1")).statusCode());

        JsonNode one = request("POST", "/api/sql", token, Map.of("query", "select 1"));
        assertEquals(0, one.get("affectedRows").asInt());
        assertEquals("1", one.get("columns").get(0).get("name").asText());
        assertEquals("1", one.get("rows").get(0).get(0).asText());

        JsonNode second = request("POST", "/api/sql", token, Map.of("query", "select 1; select 2"));
        assertEquals("2", second.get("columns").get(0).get("name").asText());
        assertEquals("2", second.get("rows").get(0).get(0).asText());

        HttpResponse<String> missingQuery = rawRequest("POST", "/api/sql", token, Map.of("query", ""));
        assertEquals(400, missingQuery.statusCode());
        assertFieldError(missingQuery, 400, "An error occurred while validating the submitted data.", "query", "validation_required", "Cannot be blank.");

        HttpResponse<String> tooLongQuery = rawRequest("POST", "/api/sql", token, Map.of("query", "a".repeat(5001)));
        assertEquals(400, tooLongQuery.statusCode());
        assertFieldError(tooLongQuery, 400, "An error occurred while validating the submitted data.", "query", "validation_invalid_value", "query must be at most 5000 characters.");

        HttpResponse<String> arrayPayload = rawJsonRequest("POST", "/api/sql", token, "[]");
        assertEquals(400, arrayPayload.statusCode());
        assertFieldError(arrayPayload, 400, "An error occurred while loading the submitted data.", "body", "validation_invalid_value", "Request body must be a JSON object.");

        JsonNode create = request("POST", "/api/sql", token, Map.of(
                "query", "create table sql_posts(id text primary key, title text not null, published bool, views int)"
        ));
        assertEquals(0, create.get("affectedRows").asInt());
        JsonNode collection = request("GET", "/api/collections/sql_posts", token, null);
        assertEquals("sql_posts", collection.get("name").asText());

        JsonNode inserted = request("POST", "/api/sql", token, Map.of(
                "query", "insert into sql_posts (id,title,published,views) values ('post_one','Hello SQL',true,7)"
        ));
        assertEquals(1, inserted.get("affectedRows").asInt());

        JsonNode selected = request("POST", "/api/sql", token, Map.of(
                "query", "select id,title,views from sql_posts where published = true order by created desc limit 1"
        ));
        assertEquals("post_one", selected.get("rows").get(0).get(0).asText());
        assertEquals("Hello SQL", selected.get("rows").get(0).get(1).asText());
        assertEquals("7", selected.get("rows").get(0).get(2).asText());

        JsonNode count = request("POST", "/api/sql", token, Map.of(
                "query", "select count(*) as total from sql_posts where title = 'Hello SQL'"
        ));
        assertEquals("total", count.get("columns").get(0).get("name").asText());
        assertEquals("1", count.get("rows").get(0).get(0).asText());

        JsonNode updated = request("POST", "/api/sql", token, Map.of(
                "query", "update sql_posts set title = 'Updated SQL', views = 8 where id = 'post_one'"
        ));
        assertEquals(1, updated.get("affectedRows").asInt());
        JsonNode afterUpdate = request("POST", "/api/sql", token, Map.of(
                "query", "select title,views from sql_posts where id = 'post_one'"
        ));
        assertEquals("Updated SQL", afterUpdate.get("rows").get(0).get(0).asText());
        assertEquals("8", afterUpdate.get("rows").get(0).get(1).asText());

        JsonNode deleted = request("POST", "/api/sql", token, Map.of(
                "query", "delete from sql_posts where id = 'post_one'"
        ));
        assertEquals(1, deleted.get("affectedRows").asInt());
        JsonNode empty = request("POST", "/api/sql", token, Map.of(
                "query", "select count(*) as total from sql_posts"
        ));
        assertEquals("0", empty.get("rows").get(0).get(0).asText());

        HttpResponse<String> rolledBack = rawRequest("POST", "/api/sql", token, Map.of(
                "query", "create table sql_tx(id text primary key, title text);"
                        + "insert into sql_tx (id,title) values ('one','ok');"
                        + "invalid"
        ));
        assertEquals(400, rolledBack.statusCode());
        assertMessageAndFieldErrorStartWith(
                rolledBack,
                400,
                "Failed to execute query. Raw error:\n",
                "query",
                "validation_invalid_value",
                "Failed to execute query. Raw error:\n"
        );
        assertEquals(404, rawRequest("GET", "/api/collections/sql_tx", token, null).statusCode());
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
        JsonNode failedBody = mapper.readTree(failed.body());
        assertEquals(400, failedBody.get("status").asInt());
        assertFalse(failedBody.has("code"));
        assertEquals("Batch request failed.", failedBody.get("message").asText());
        assertEquals(1, failedBody.get("data").get("index").asInt());
        JsonNode nested = failedBody.get("data").get("response");
        assertEquals(404, nested.get("status").asInt());
        assertFalse(nested.has("code"));
        assertEquals("Record not found.", nested.get("message").asText());

        JsonNode afterRollback = request("GET", "/api/collections/batch_posts/records", null, null);
        assertEquals(1, afterRollback.get("totalItems").asInt());
        assertEquals("batch_one", afterRollback.get("items").get(0).get("id").asText());
    }

    @Test
    void batchValidationErrorsUseOfficialEnvelope() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        HttpResponse<String> missingRequests = rawRequest("POST", "/api/batch", token, Map.of());
        assertEquals(400, missingRequests.statusCode());
        assertFieldError(missingRequests, 400, "Failed to process batch.", "requests", "validation_required", "Cannot be blank.");

        HttpResponse<String> invalidMultipartJson = rawMultipartRequest("POST", "/api/batch", token, Map.of(
                "@jsonPayload", "{bad"
        ), Map.of());
        assertEquals(400, invalidMultipartJson.statusCode());
        assertFieldError(invalidMultipartJson, 400, "Failed to process batch.", "@jsonPayload", "validation_invalid_value", "Invalid JSON payload.");

        HttpResponse<String> missingMethod = rawRequest("POST", "/api/batch", token, Map.of(
                "requests", List.of(Map.of("url", "/api/collections/posts/records"))
        ));
        assertEquals(400, missingMethod.statusCode());
        JsonNode body = mapper.readTree(missingMethod.body());
        assertEquals(400, body.get("status").asInt());
        assertEquals("Batch request failed.", body.get("message").asText());
        assertEquals(0, body.get("data").get("index").asInt());
        JsonNode response = body.get("data").get("response");
        assertEquals(400, response.get("status").asInt());
        assertEquals("Batch request failed.", response.get("message").asText());
        assertEquals("validation_required", response.get("data").get("method").get("code").asText());
        assertEquals("Cannot be blank.", response.get("data").get("method").get("message").asText());

        HttpResponse<String> unsupportedTarget = rawRequest("POST", "/api/batch", token, Map.of(
                "requests", List.of(Map.of("method", "GET", "url", "/api/settings"))
        ));
        assertEquals(400, unsupportedTarget.statusCode());
        JsonNode unsupportedBody = mapper.readTree(unsupportedTarget.body());
        assertEquals(400, unsupportedBody.get("status").asInt());
        assertEquals("Batch request failed.", unsupportedBody.get("message").asText());
        assertEquals(0, unsupportedBody.get("data").get("index").asInt());
        JsonNode unsupportedResponse = unsupportedBody.get("data").get("response");
        assertEquals(400, unsupportedResponse.get("status").asInt());
        assertEquals("Only record batch requests are supported.", unsupportedResponse.get("message").asText());
        assertEquals("validation_invalid_value", unsupportedResponse.get("data").get("url").get("code").asText());
        assertEquals("Only record batch requests are supported.", unsupportedResponse.get("data").get("url").get("message").asText());

        HttpResponse<String> malformedTarget = rawRequest("POST", "/api/batch", token, Map.of(
                "requests", List.of(Map.of("method", "GET", "url", "%"))
        ));
        assertEquals(400, malformedTarget.statusCode());
        JsonNode malformedBody = mapper.readTree(malformedTarget.body());
        assertEquals(400, malformedBody.get("status").asInt());
        assertEquals("Batch request failed.", malformedBody.get("message").asText());
        assertEquals(0, malformedBody.get("data").get("index").asInt());
        JsonNode malformedResponse = malformedBody.get("data").get("response");
        assertEquals(400, malformedResponse.get("status").asInt());
        assertEquals("Batch request failed.", malformedResponse.get("message").asText());
        assertEquals("validation_invalid_value", malformedResponse.get("data").get("url").get("code").asText());
        assertEquals("Invalid batch request URL.", malformedResponse.get("data").get("url").get("message").asText());
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
        JsonNode failedBody = mapper.readTree(failed.body());
        assertEquals(400, failedBody.get("status").asInt());
        assertFalse(failedBody.has("code"));
        assertEquals("Batch request failed.", failedBody.get("message").asText());
        assertEquals(1, failedBody.get("data").get("index").asInt());
        JsonNode failedResponse = failedBody.get("data").get("response");
        assertEquals(404, failedResponse.get("status").asInt());
        assertEquals("Record not found.", failedResponse.get("message").asText());
        assertTrue(failedResponse.get("data").isObject());

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
    void structuredFieldValuesRoundtripAsOfficialJsonTypes() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        JsonNode people = request("POST", "/api/collections", token, Map.of(
                "name", "typed_people",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(Map.of("name", "name", "type", "text", "required", true))
        ));
        request("POST", "/api/collections", token, Map.of(
                "name", "typed_posts",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(
                        Map.of("name", "title", "type", "text", "required", true),
                        Map.of("name", "labels", "type", "select", "options", Map.of(
                                "values", List.of("alpha", "beta", "gamma"),
                                "maxSelect", 3
                        )),
                        Map.of("name", "authors", "type", "relation", "collectionId", people.get("id").asText(), "maxSelect", 3),
                        Map.of("name", "meta", "type", "json"),
                        Map.of("name", "location", "type", "geoPoint"),
                        Map.of("name", "published", "type", "bool")
                )
        ));
        JsonNode ada = request("POST", "/api/collections/typed_people/records", token, Map.of("name", "Ada"));
        JsonNode linus = request("POST", "/api/collections/typed_people/records", token, Map.of("name", "Linus"));

        JsonNode created = request("POST", "/api/collections/typed_posts/records", token, Map.of(
                "title", "Typed payload",
                "labels", List.of("alpha", "beta"),
                "authors", List.of(ada.get("id").asText(), linus.get("id").asText()),
                "meta", Map.of("rating", 5, "tags", List.of("x", "y")),
                "location", Map.of("lat", 12.34, "lon", 56.78),
                "published", true
        ));

        assertTrue(created.get("labels").isArray());
        assertEquals(2, created.get("labels").size());
        assertEquals("alpha", created.get("labels").get(0).asText());
        assertTrue(created.get("authors").isArray());
        assertEquals(2, created.get("authors").size());
        assertTrue(created.get("meta").isObject());
        assertEquals(5, created.get("meta").get("rating").asInt());
        assertTrue(created.get("location").isObject());
        assertEquals(12.34, created.get("location").get("lat").asDouble(), 0.0001);
        assertTrue(created.get("published").asBoolean());

        JsonNode listed = request("GET", "/api/collections/typed_posts/records", null, null);
        JsonNode item = listed.get("items").get(0);
        assertTrue(item.get("labels").isArray());
        assertEquals("beta", item.get("labels").get(1).asText());
        assertTrue(item.get("authors").isArray());
        assertEquals(linus.get("id").asText(), item.get("authors").get(1).asText());
        assertTrue(item.get("meta").isObject());
        assertEquals("y", item.get("meta").get("tags").get(1).asText());
        assertTrue(item.get("location").isObject());
        assertEquals(56.78, item.get("location").get("lon").asDouble(), 0.0001);
        assertTrue(item.get("published").asBoolean());
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
        HttpResponse<String> idAsIdentity = rawRequest("POST", "/api/collections/users/auth-with-password", null, Map.of(
                "identity", auth.get("record").get("id").asText(),
                "password", "secret456"
        ));
        assertEquals(400, idAsIdentity.statusCode());
        assertErrorEnvelope(idAsIdentity, 400, "Failed to authenticate.");
        HttpResponse<String> disabledIdentityField = rawRequest("POST", "/api/collections/users/auth-with-password", null, Map.of(
                "identityField", "username",
                "identity", "demo@example.com",
                "password", "secret456"
        ));
        assertEquals(400, disabledIdentityField.statusCode());
        assertErrorEnvelope(disabledIdentityField, 400, "Failed to authenticate.");

        HttpResponse<String> duplicate = rawRequest("POST", "/api/collections/users/records", token, Map.of(
                "email", "demo@example.com",
                "password", "another-secret"
        ));
        assertEquals(400, duplicate.statusCode());
        assertFieldError(duplicate, 400, "Failed to create record.", "email", "validation_not_unique", "Value must be unique.");

        JsonNode methods = request("GET", "/api/collections/users/auth-methods", null, null);
        assertTrue(methods.get("password").get("enabled").asBoolean());
        assertEquals("email", methods.get("password").get("identityFields").get(0).asText());
        assertFalse(methods.get("oauth2").get("enabled").asBoolean());
        assertTrue(methods.get("oauth2").get("providers").isArray());
        assertFalse(methods.get("mfa").get("enabled").asBoolean());
        assertFalse(methods.get("otp").get("enabled").asBoolean());
        assertTrue(methods.get("emailPassword").asBoolean());
    }

    @Test
    void otpEndpointsIssueCodeAndAuthenticateAuthRecord() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        request("POST", "/api/collections", token, Map.of(
                "name", "otp_users",
                "type", "auth",
                "otp", Map.of(
                        "enabled", true,
                        "duration", 300,
                        "length", 6
                )
        ));
        JsonNode user = request("POST", "/api/collections/otp_users/records", token, Map.of(
                "email", "otp@example.com",
                "password", "secret456",
                "verified", false
        ));

        JsonNode methods = request("GET", "/api/collections/otp_users/auth-methods", null, null);
        assertTrue(methods.get("password").get("enabled").asBoolean());
        assertTrue(methods.get("otp").get("enabled").asBoolean());
        assertEquals(300, methods.get("otp").get("duration").asInt());

        JsonNode missingUserOtp = request("POST", "/api/collections/otp_users/request-otp", null, Map.of(
                "email", "missing@example.com"
        ));
        assertTrue(missingUserOtp.hasNonNull("otpId"));

        JsonNode otpRequest = request("POST", "/api/collections/otp_users/request-otp", null, Map.of(
                "email", "otp@example.com"
        ));
        String otpId = otpRequest.get("otpId").asText();
        String otpPassword = otpRequestPassword("otp@example.com", otpId);

        HttpResponse<String> wrongOtpPassword = rawRequest("POST", "/api/collections/otp_users/auth-with-otp", null, Map.of(
                "otpId", otpId,
                "password", "000000"
        ));
        assertEquals(400, wrongOtpPassword.statusCode());
        assertFieldError(wrongOtpPassword, 400, "Invalid or expired OTP.", "otpId", "validation_invalid_value", "Invalid or expired OTP.");

        JsonNode auth = request("POST", "/api/collections/otp_users/auth-with-otp", null, Map.of(
                "otpId", otpId,
                "password", otpPassword
        ));
        assertTrue(auth.hasNonNull("token"));
        assertEquals(user.get("id").asText(), auth.get("record").get("id").asText());

        JsonNode verified = request("GET", "/api/collections/otp_users/records/" + user.get("id").asText(), token, null);
        assertTrue(verified.get("verified").asBoolean());
        HttpResponse<String> passwordAfterOtp = rawRequest("POST", "/api/collections/otp_users/auth-with-password", null, Map.of(
                "identity", "otp@example.com",
                "password", "secret456"
        ));
        assertEquals(400, passwordAfterOtp.statusCode());
        assertErrorEnvelope(passwordAfterOtp, 400, "Failed to authenticate.");

        HttpResponse<String> reusedOtp = rawRequest("POST", "/api/collections/otp_users/auth-with-otp", null, Map.of(
                "otpId", otpId,
                "password", otpPassword
        ));
        assertEquals(400, reusedOtp.statusCode());
        assertFieldError(reusedOtp, 400, "Invalid or expired OTP.", "otpId", "validation_invalid_value", "Invalid or expired OTP.");

        JsonNode lockedOtp = request("POST", "/api/collections/otp_users/request-otp", null, Map.of(
                "email", "otp@example.com"
        ));
        String lockedOtpId = lockedOtp.get("otpId").asText();
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> failedOtpAttempt = rawRequest("POST", "/api/collections/otp_users/auth-with-otp", null, Map.of(
                    "otpId", lockedOtpId,
                    "password", "00000" + i
            ));
            assertEquals(400, failedOtpAttempt.statusCode());
            assertFieldError(failedOtpAttempt, 400, "Invalid or expired OTP.", "otpId", "validation_invalid_value", "Invalid or expired OTP.");
        }
        HttpResponse<String> lockedOtpAttempt = rawRequest("POST", "/api/collections/otp_users/auth-with-otp", null, Map.of(
                "otpId", lockedOtpId,
                "password", "123456"
        ));
        assertEquals(429, lockedOtpAttempt.statusCode());
        assertFieldError(lockedOtpAttempt, 429, "Too many failed OTP attempts.", "otpId", "validation_invalid_value", "Too many failed OTP attempts.");
    }

    @Test
    void authMethodsReflectConfiguredPasswordOtpMfaAndOauth2() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        request("POST", "/api/collections", token, Map.of(
                "name", "auth_config_users",
                "type", "auth",
                "passwordAuth", Map.of(
                        "enabled", true,
                        "identityFields", List.of("email", "username")
                ),
                "otp", Map.of(
                        "enabled", true,
                        "duration", 420,
                        "length", 8
                ),
                "mfa", Map.of(
                        "enabled", true,
                        "duration", 900
                ),
                "oauth2", Map.of(
                        "enabled", true,
                        "providers", List.of(
                                Map.of("name", "github"),
                                Map.of("name", "google")
                        )
                )
        ));

        JsonNode methods = request("GET", "/api/collections/auth_config_users/auth-methods", null, null);
        assertTrue(methods.get("password").get("enabled").asBoolean());
        assertEquals(2, methods.get("password").get("identityFields").size());
        assertTrue(methods.get("usernamePassword").asBoolean());
        assertTrue(methods.get("emailPassword").asBoolean());
        assertTrue(methods.get("otp").get("enabled").asBoolean());
        assertEquals(420, methods.get("otp").get("duration").asInt());
        assertTrue(methods.get("mfa").get("enabled").asBoolean());
        assertEquals(900, methods.get("mfa").get("duration").asInt());
        assertTrue(methods.get("oauth2").get("enabled").asBoolean());
        assertEquals(2, methods.get("oauth2").get("providers").size());
        assertEquals("github", methods.get("oauth2").get("providers").get(0).get("name").asText());
        assertTrue(methods.get("oauth2").get("providers").get(0).has("displayName"));
        assertTrue(methods.get("oauth2").get("providers").get(0).has("authURL"));
        assertEquals(2, methods.get("authProviders").size());

        JsonNode collections = request("GET", "/api/collections?filter=" + URLEncoder.encode("name = 'auth_config_users'", StandardCharsets.UTF_8), token, null);
        JsonNode listed = collections.get("items").get(0);
        assertTrue(listed.get("oauth2").get("enabled").asBoolean());
        assertEquals("github", listed.get("oauth2").get("providers").get(0).get("name").asText());
    }

    @Test
    void authCollectionTokenDurationsDriveIssuedJwtTtls() throws Exception {
        start();
        bootstrapSuperuser();
        String superuserToken = loginToken();

        request("POST", "/api/collections", superuserToken, Map.of(
                "name", "token_users",
                "type", "auth",
                "authToken", Map.of("duration", 61),
                "passwordResetToken", Map.of("duration", 91),
                "verificationToken", Map.of("duration", 121),
                "emailChangeToken", Map.of("duration", 151),
                "fileToken", Map.of("duration", 181)
        ));
        JsonNode user = request("POST", "/api/collections/token_users/records", superuserToken, Map.of(
                "email", "token-user@example.com",
                "password", "secret456",
                "verified", false
        ));

        JsonNode auth = request("POST", "/api/collections/token_users/auth-with-password", null, Map.of(
                "identity", "token-user@example.com",
                "password", "secret456"
        ));
        assertTokenLifetime(auth.get("token").asText(), 61);

        JsonNode refreshed = request("POST", "/api/collections/token_users/auth-refresh", auth.get("token").asText(), null);
        assertTokenLifetime(refreshed.get("token").asText(), 61);

        JsonNode fileToken = request("POST", "/api/files/token", auth.get("token").asText(), null);
        assertTokenLifetime(fileToken.get("token").asText(), 181);

        request("POST", "/api/collections/token_users/request-password-reset", null, Map.of(
                "email", "token-user@example.com"
        ));
        assertTokenLifetime(authRequestToken("passwordReset", "token-user@example.com"), 91);

        request("POST", "/api/collections/token_users/request-verification", null, Map.of(
                "email", "token-user@example.com"
        ));
        assertTokenLifetime(authRequestToken("verification", "token-user@example.com"), 121);

        JsonNode verifiedAuth = request("POST", "/api/collections/token_users/auth-with-password", null, Map.of(
                "identity", "token-user@example.com",
                "password", "secret456"
        ));
        request("POST", "/api/collections/token_users/request-email-change", verifiedAuth.get("token").asText(), Map.of(
                "newEmail", "token-user-next@example.com"
        ));
        assertTokenLifetime(authRequestToken("emailChange", "token-user@example.com"), 151);

        JsonNode impersonated = request("POST", "/api/collections/token_users/impersonate/" + user.get("id").asText(),
                superuserToken,
                Map.of());
        assertTokenLifetime(impersonated.get("token").asText(), 61);
    }

    @Test
    void rotatingCollectionTokenSecretsInvalidatesIssuedAuthFileAndResetTokens() throws Exception {
        start();
        bootstrapSuperuser();
        String superuserToken = loginToken();

        request("POST", "/api/collections", superuserToken, Map.of(
                "name", "secret_users",
                "type", "auth",
                "authToken", Map.of("duration", 61, "secret", "auth-a"),
                "passwordResetToken", Map.of("duration", 91, "secret", "reset-a"),
                "fileToken", Map.of("duration", 181, "secret", "file-a")
        ));
        JsonNode user = request("POST", "/api/collections/secret_users/records", superuserToken, Map.of(
                "email", "secret-user@example.com",
                "password", "secret456",
                "verified", true
        ));

        JsonNode auth = request("POST", "/api/collections/secret_users/auth-with-password", null, Map.of(
                "identity", "secret-user@example.com",
                "password", "secret456"
        ));
        String authToken = auth.get("token").asText();

        request("PATCH", "/api/collections/secret_users", superuserToken, Map.of(
                "id", auth.get("record").get("collectionId").asText(),
                "name", "secret_users",
                "type", "auth",
                "fields", List.of(),
                "authToken", Map.of("duration", 61, "secret", "auth-b"),
                "passwordResetToken", Map.of("duration", 91, "secret", "reset-a"),
                "fileToken", Map.of("duration", 181, "secret", "file-a")
        ));
        assertEquals(401, rawRequest("POST", "/api/collections/secret_users/auth-refresh", authToken, null).statusCode());

        JsonNode freshAuth = request("POST", "/api/collections/secret_users/auth-with-password", null, Map.of(
                "identity", "secret-user@example.com",
                "password", "secret456"
        ));
        String freshToken = freshAuth.get("token").asText();

        request("POST", "/api/collections", superuserToken, Map.of(
                "name", "secret_assets",
                "listRule", "owner = @request.auth.id",
                "viewRule", "owner = @request.auth.id",
                "fields", List.of(
                        Map.of("name", "owner", "type", "text", "required", true),
                        Map.of("name", "attachment", "type", "file", "required", true, "protected", true)
                )
        ));
        JsonNode asset = multipartRequest("POST", "/api/collections/secret_assets/records", superuserToken, Map.of(
                "owner", user.get("id").asText()
        ), Map.of(
                "attachment", new MultipartFile("secret.txt", "text/plain", "secret payload".getBytes(StandardCharsets.UTF_8))
        ));
        String filename = asset.get("attachment").asText();
        String filePath = "/api/files/secret_assets/" + asset.get("id").asText() + "/" + filename;

        JsonNode fileToken = request("POST", "/api/files/token", freshToken, null);
        assertEquals(200, rawRequest("GET", filePath + "?token=" + fileToken.get("token").asText(), null, null).statusCode());

        request("PATCH", "/api/collections/secret_users", superuserToken, Map.of(
                "id", freshAuth.get("record").get("collectionId").asText(),
                "name", "secret_users",
                "type", "auth",
                "fields", List.of(),
                "authToken", Map.of("duration", 61, "secret", "auth-b"),
                "passwordResetToken", Map.of("duration", 91, "secret", "reset-a"),
                "fileToken", Map.of("duration", 181, "secret", "file-b")
        ));
        assertEquals(403, rawRequest("GET", filePath + "?token=" + fileToken.get("token").asText(), null, null).statusCode());

        request("POST", "/api/collections/secret_users/request-password-reset", null, Map.of(
                "email", "secret-user@example.com"
        ));
        String resetToken = authRequestToken("passwordReset", "secret-user@example.com");

        request("PATCH", "/api/collections/secret_users", superuserToken, Map.of(
                "id", freshAuth.get("record").get("collectionId").asText(),
                "name", "secret_users",
                "type", "auth",
                "fields", List.of(),
                "authToken", Map.of("duration", 61, "secret", "auth-b"),
                "passwordResetToken", Map.of("duration", 91, "secret", "reset-b"),
                "fileToken", Map.of("duration", 181, "secret", "file-b")
        ));
        HttpResponse<String> staleResetToken = rawRequest("POST", "/api/collections/secret_users/confirm-password-reset", null, Map.of(
                "token", resetToken,
                "password", "newsecret456",
                "passwordConfirm", "newsecret456"
        ));
        assertEquals(400, staleResetToken.statusCode());
        assertFieldError(staleResetToken, 400, "Invalid or expired token.", "token", "validation_invalid_value", "Invalid or expired token.");
    }

    @Test
    void expiredAndWrongCollectionTokensAreRejected() throws Exception {
        start();
        bootstrapSuperuser();
        String superuserToken = loginToken();

        request("POST", "/api/collections", superuserToken, Map.of(
                "name", "timed_users",
                "type", "auth",
                "authToken", Map.of("duration", 1),
                "passwordResetToken", Map.of("duration", 1)
        ));
        request("POST", "/api/collections", superuserToken, Map.of(
                "name", "other_users",
                "type", "auth"
        ));
        request("POST", "/api/collections/timed_users/records", superuserToken, Map.of(
                "email", "timed@example.com",
                "password", "secret456",
                "verified", true
        ));
        request("POST", "/api/collections/other_users/records", superuserToken, Map.of(
                "email", "other@example.com",
                "password", "secret456",
                "verified", true
        ));

        JsonNode auth = request("POST", "/api/collections/timed_users/auth-with-password", null, Map.of(
                "identity", "timed@example.com",
                "password", "secret456"
        ));
        Thread.sleep(2100L);
        assertEquals(401, rawRequest("POST", "/api/collections/timed_users/auth-refresh", auth.get("token").asText(), null).statusCode());

        request("POST", "/api/collections/timed_users/request-password-reset", null, Map.of(
                "email", "timed@example.com"
        ));
        String resetToken = authRequestToken("passwordReset", "timed@example.com");
        HttpResponse<String> wrongCollectionReset = rawRequest("POST", "/api/collections/other_users/confirm-password-reset", null, Map.of(
                "token", resetToken,
                "password", "newsecret456",
                "passwordConfirm", "newsecret456"
        ));
        assertEquals(400, wrongCollectionReset.statusCode());
        assertFieldError(wrongCollectionReset, 400, "Invalid or expired token.", "token", "validation_invalid_value", "Invalid or expired token.");
        Thread.sleep(2100L);
        HttpResponse<String> expiredReset = rawRequest("POST", "/api/collections/timed_users/confirm-password-reset", null, Map.of(
                "token", resetToken,
                "password", "newsecret456",
                "passwordConfirm", "newsecret456"
        ));
        assertEquals(400, expiredReset.statusCode());
        assertFieldError(expiredReset, 400, "Invalid or expired token.", "token", "validation_invalid_value", "Invalid or expired token.");
    }

    @Test
    void mfaRequiresADifferentSecondAuthMethodAcrossPasswordOtpAndOauth2() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        request("POST", "/api/collections", token, Map.of(
                "name", "mfa_users",
                "type", "auth",
                "mfa", Map.of(
                        "enabled", true,
                        "duration", 900,
                        "rule", "true"
                ),
                "otp", Map.of(
                        "enabled", true,
                        "duration", 300,
                        "length", 6
                )
        ));
        request("POST", "/api/collections/mfa_users/records", token, Map.of(
                "email", "mfa@example.com",
                "password", "secret456",
                "verified", true
        ));

        HttpResponse<String> passwordFirst = rawRequest("POST", "/api/collections/mfa_users/auth-with-password", null, Map.of(
                "identity", "mfa@example.com",
                "password", "secret456"
        ));
        assertEquals(401, passwordFirst.statusCode());
        String passwordMfaId = mapper.readTree(passwordFirst.body()).get("mfaId").asText();

        HttpResponse<String> samePasswordMethod = rawRequest("POST", "/api/collections/mfa_users/auth-with-password", null, Map.of(
                "identity", "mfa@example.com",
                "password", "secret456",
                "mfaId", passwordMfaId
        ));
        assertEquals(400, samePasswordMethod.statusCode());
        assertFieldError(samePasswordMethod, 400, "MFA requires a different auth method.", "mfaId", "validation_invalid_value", "MFA requires a different auth method.");

        JsonNode otpRequest = request("POST", "/api/collections/mfa_users/request-otp", null, Map.of(
                "email", "mfa@example.com"
        ));
        String otpId = otpRequest.get("otpId").asText();
        String otpPassword = otpRequestPassword("mfa@example.com", otpId);
        JsonNode passwordCompleted = request("POST", "/api/collections/mfa_users/auth-with-otp", null, Map.of(
                "otpId", otpId,
                "password", otpPassword,
                "mfaId", passwordMfaId
        ));
        assertTrue(passwordCompleted.hasNonNull("token"));
        HttpResponse<String> reusedMfaId = rawRequest("POST", "/api/collections/mfa_users/auth-with-password", null, Map.of(
                "identity", "mfa@example.com",
                "password", "secret456",
                "mfaId", passwordMfaId
        ));
        assertEquals(400, reusedMfaId.statusCode());
        assertFieldError(reusedMfaId, 400, "Missing or invalid MFA ID.", "mfaId", "validation_invalid_value", "Missing or invalid MFA ID.");

        JsonNode otpRequest2 = request("POST", "/api/collections/mfa_users/request-otp", null, Map.of(
                "email", "mfa@example.com"
        ));
        String otpId2 = otpRequest2.get("otpId").asText();
        String otpPassword2 = otpRequestPassword("mfa@example.com", otpId2);
        HttpResponse<String> otpFirst = rawRequest("POST", "/api/collections/mfa_users/auth-with-otp", null, Map.of(
                "otpId", otpId2,
                "password", otpPassword2
        ));
        assertEquals(401, otpFirst.statusCode());
        String otpMfaId = mapper.readTree(otpFirst.body()).get("mfaId").asText();

        JsonNode otpRequest3 = request("POST", "/api/collections/mfa_users/request-otp", null, Map.of(
                "email", "mfa@example.com"
        ));
        String otpId3 = otpRequest3.get("otpId").asText();
        String otpPassword3 = otpRequestPassword("mfa@example.com", otpId3);
        HttpResponse<String> sameOtpMethod = rawRequest("POST", "/api/collections/mfa_users/auth-with-otp", null, Map.of(
                "otpId", otpId3,
                "password", otpPassword3,
                "mfaId", otpMfaId
        ));
        assertEquals(400, sameOtpMethod.statusCode());
        assertFieldError(sameOtpMethod, 400, "MFA requires a different auth method.", "mfaId", "validation_invalid_value", "MFA requires a different auth method.");
        JsonNode otpCompleted = request("POST", "/api/collections/mfa_users/auth-with-password?mfaId="
                + URLEncoder.encode(otpMfaId, StandardCharsets.UTF_8), null, Map.of(
                "identity", "mfa@example.com",
                "password", "secret456"
        ));
        assertTrue(otpCompleted.hasNonNull("token"));

        try (FakeOAuth2Server oauth = FakeOAuth2Server.start()) {
            request("POST", "/api/collections", token, Map.of(
                    "name", "mfa_oauth_users",
                    "type", "auth",
                    "mfa", Map.of(
                            "enabled", true,
                            "duration", 900,
                            "rule", "true"
                    ),
                    "oauth2", Map.of(
                            "enabled", true,
                            "providers", List.of(
                                    Map.of(
                                            "name", "oidc",
                                            "clientId", "client-123",
                                            "clientSecret", "secret-456",
                                            "authURL", oauth.baseUrl() + "/authorize",
                                            "tokenURL", oauth.baseUrl() + "/token",
                                            "userInfoURL", oauth.baseUrl() + "/userinfo",
                                            "scopes", List.of("openid", "email", "profile"),
                                            "pkce", true
                                    )
                            )
                    )
            ));
            request("POST", "/api/collections/mfa_oauth_users/records", token, Map.of(
                    "email", "oidc@example.com",
                    "password", "secret456",
                    "verified", true
            ));

            JsonNode methods = request("GET", "/api/collections/mfa_oauth_users/auth-methods", null, null);
            JsonNode provider = methods.get("oauth2").get("providers").get(0);

            HttpResponse<String> oauthFirst = rawRequest("POST", "/api/collections/mfa_oauth_users/auth-with-oauth2", null, Map.of(
                    "provider", "oidc",
                    "code", "first-code",
                    "codeVerifier", provider.get("codeVerifier").asText(),
                    "redirectURL", "http://127.0.0.1/callback"
            ));
            assertEquals(401, oauthFirst.statusCode());
            String oauthMfaId = mapper.readTree(oauthFirst.body()).get("mfaId").asText();

            HttpResponse<String> sameOauthMethod = rawRequest("POST", "/api/collections/mfa_oauth_users/auth-with-oauth2", null, Map.of(
                    "provider", "oidc",
                    "code", "second-code",
                    "codeVerifier", provider.get("codeVerifier").asText(),
                    "redirectURL", "http://127.0.0.1/callback",
                    "mfaId", oauthMfaId
            ));
            assertEquals(400, sameOauthMethod.statusCode());
            assertFieldError(sameOauthMethod, 400, "MFA requires a different auth method.", "mfaId", "validation_invalid_value", "MFA requires a different auth method.");

            JsonNode oauthCompleted = request("POST", "/api/collections/mfa_oauth_users/auth-with-password", null, Map.of(
                    "identity", "oidc@example.com",
                    "password", "secret456",
                    "mfaId", oauthMfaId
            ));
            assertTrue(oauthCompleted.hasNonNull("token"));
        }
    }

    @Test
    void superuserMfaCanEscalateFromPasswordToOtp() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        request("PATCH", "/api/collections/_superusers", token, Map.of(
                "id", "pbc_superusers",
                "name", "_superusers",
                "type", "auth",
                "fields", List.of(),
                "options", Map.of(
                        "passwordAuth", Map.of(
                                "enabled", true,
                                "identityFields", List.of("email")
                        ),
                        "otp", Map.of(
                                "enabled", true,
                                "duration", 300,
                                "length", 6
                        ),
                        "mfa", Map.of(
                                "enabled", true,
                                "duration", 900,
                                "rule", "true"
                        )
                )
        ));

        HttpResponse<String> passwordFirst = rawRequest("POST", "/api/collections/_superusers/auth-with-password", null, Map.of(
                "identity", "root@example.com",
                "password", "secret123"
        ));
        assertEquals(401, passwordFirst.statusCode());
        String passwordMfaId = mapper.readTree(passwordFirst.body()).get("mfaId").asText();

        JsonNode otpRequest = request("POST", "/api/collections/_superusers/request-otp", null, Map.of(
                "email", "root@example.com"
        ));
        String otpId = otpRequest.get("otpId").asText();
        String otpPassword = otpRequestPassword("root@example.com", otpId);

        JsonNode completed = request("POST", "/api/collections/_superusers/auth-with-otp", null, Map.of(
                "otpId", otpId,
                "password", otpPassword,
                "mfaId", passwordMfaId
        ));
        assertTrue(completed.hasNonNull("token"));

        HttpResponse<String> sameMethod = rawRequest("POST", "/api/collections/_superusers/auth-with-password", null, Map.of(
                "identity", "root@example.com",
                "password", "secret123",
                "mfaId", passwordMfaId
        ));
        assertEquals(400, sameMethod.statusCode());
        assertFieldError(sameMethod, 400, "Missing or invalid MFA ID.", "mfaId", "validation_invalid_value", "Missing or invalid MFA ID.");
    }

    @Test
    void oauth2EndpointsExchangeCodeAndReuseLinkedAuthRecord() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        try (FakeOAuth2Server oauth = FakeOAuth2Server.start()) {
            request("POST", "/api/collections", token, Map.of(
                    "name", "oauth_users",
                    "type", "auth",
                    "createRule", "",
                    "oauth2", Map.of(
                            "enabled", true,
                            "providers", List.of(
                                    Map.of(
                                            "name", "oidc",
                                            "clientId", "client-123",
                                            "clientSecret", "secret-456",
                                            "authURL", oauth.baseUrl() + "/authorize",
                                            "tokenURL", oauth.baseUrl() + "/token",
                                            "userInfoURL", oauth.baseUrl() + "/userinfo",
                                            "scopes", List.of("openid", "email", "profile"),
                                            "pkce", true
                                    )
                            )
                    )
            ));

            JsonNode methods = request("GET", "/api/collections/oauth_users/auth-methods", null, null);
            JsonNode provider = methods.get("oauth2").get("providers").get(0);
            assertEquals("oidc", provider.get("name").asText());
            assertTrue(provider.get("authURL").asText().contains("client_id=client-123"));
            assertTrue(provider.get("authURL").asText().contains("scope=openid%20email%20profile"));
            assertTrue(provider.get("codeVerifier").asText().length() >= 10);

            JsonNode firstAuth = request("POST", "/api/collections/oauth_users/auth-with-oauth2", null, Map.of(
                    "provider", "oidc",
                    "code", "first-code",
                    "codeVerifier", provider.get("codeVerifier").asText(),
                    "redirectURL", "http://127.0.0.1/callback",
                    "createData", Map.of("name", "OIDC User")
            ));
            String recordId = firstAuth.get("record").get("id").asText();
            assertEquals("oidc@example.com", firstAuth.get("record").get("email").asText());
            assertTrue(firstAuth.get("record").get("verified").asBoolean());
            assertTrue(firstAuth.get("meta").get("isNew").asBoolean());
            assertEquals("oidc-user", firstAuth.get("meta").get("preferred_username").asText());
            assertTrue(oauth.lastTokenBody().contains("code=first-code"));
            assertTrue(oauth.lastTokenBody().contains("code_verifier="));

            JsonNode secondAuth = request("POST", "/api/collections/oauth_users/auth-with-oauth2", null, Map.of(
                    "provider", "oidc",
                    "code", "second-code",
                    "codeVerifier", provider.get("codeVerifier").asText(),
                    "redirectURL", "http://127.0.0.1/callback"
            ));
            assertEquals(recordId, secondAuth.get("record").get("id").asText());
            assertFalse(secondAuth.get("meta").get("isNew").asBoolean());
        }

        HttpResponse<String> redirect = rawRequest("GET", "/api/oauth2-redirect?state=test-state&code=abc123", null, null);
        assertEquals(200, redirect.statusCode());
        assertTrue(redirect.body().contains("postMessage"));
        assertTrue(redirect.body().contains("pocketbase-java-oauth2"));
    }

    @Test
    void oauth2ExchangeErrorsUseOfficialEnvelope() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        request("POST", "/api/collections", token, Map.of(
                "name", "oauth_missing_token_url_users",
                "type", "auth",
                "oauth2", Map.of(
                        "enabled", true,
                        "providers", List.of(Map.of(
                                "name", "github",
                                "clientId", "client-123",
                                "authURL", "http://127.0.0.1/authorize"
                        ))
                )
        ));
        HttpResponse<String> missingTokenUrl = rawRequest("POST", "/api/collections/oauth_missing_token_url_users/auth-with-oauth2", null, Map.of(
                "provider", "github",
                "code", "bad-code",
                "redirectURL", "http://127.0.0.1/callback"
        ));
        assertEquals(400, missingTokenUrl.statusCode());
        assertFieldError(missingTokenUrl, 400, "Failed to authenticate.", "provider", "validation_invalid_value", "OAuth2 provider tokenURL is required.");

        try (FakeOAuth2Server oauth = FakeOAuth2Server.start(400, "{\"error\":\"invalid_grant\"}", 200, "{}")) {
            request("POST", "/api/collections", token, Map.of(
                    "name", "oauth_error_users",
                    "type", "auth",
                    "oauth2", Map.of(
                            "enabled", true,
                            "providers", List.of(Map.of(
                                    "name", "oidc",
                                    "clientId", "client-123",
                                    "clientSecret", "secret-456",
                                    "authURL", oauth.baseUrl() + "/authorize",
                                    "tokenURL", oauth.baseUrl() + "/token",
                                    "userInfoURL", oauth.baseUrl() + "/userinfo"
                            ))
                    )
            ));

            HttpResponse<String> missingProvider = rawRequest("POST", "/api/collections/oauth_error_users/auth-with-oauth2", null, Map.of(
                    "provider", "missing",
                    "code", "bad-code",
                    "redirectURL", "http://127.0.0.1/callback"
            ));
            assertEquals(400, missingProvider.statusCode());
            assertFieldError(
                    missingProvider,
                    400,
                    "Failed to authenticate.",
                    "provider",
                    "validation_invalid_value",
                    "Provider with name missing is missing or is not enabled."
            );

            HttpResponse<String> tokenFailure = rawRequest("POST", "/api/collections/oauth_error_users/auth-with-oauth2", null, Map.of(
                    "provider", "oidc",
                    "code", "bad-code",
                    "redirectURL", "http://127.0.0.1/callback"
            ));
            assertEquals(400, tokenFailure.statusCode());
            assertFieldError(tokenFailure, 400, "Failed to fetch OAuth2 token.", "provider", "validation_invalid_value", "{\"error\":\"invalid_grant\"}");
        }

        try (FakeOAuth2Server oauth = FakeOAuth2Server.start(200, "{\"access_token\":\"token-123\"}", 200, "{}")) {
            request("POST", "/api/collections", token, Map.of(
                    "name", "oauth_userinfo_error_users",
                    "type", "auth",
                    "oauth2", Map.of(
                            "enabled", true,
                            "providers", List.of(Map.of(
                                    "name", "oidc",
                                    "clientId", "client-123",
                                    "clientSecret", "secret-456",
                                    "authURL", oauth.baseUrl() + "/authorize",
                                    "tokenURL", oauth.baseUrl() + "/token",
                                    "userInfoURL", oauth.baseUrl() + "/userinfo"
                            ))
                    )
            ));

            HttpResponse<String> userInfoFailure = rawRequest("POST", "/api/collections/oauth_userinfo_error_users/auth-with-oauth2", null, Map.of(
                    "provider", "oidc",
                    "code", "bad-userinfo",
                    "redirectURL", "http://127.0.0.1/callback"
            ));
            assertEquals(400, userInfoFailure.statusCode());
            assertFieldError(userInfoFailure, 400, "Failed to fetch OAuth2 user.", "provider", "validation_invalid_value", "OAuth2 user info is empty.");
        }
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
    void authLifecycleEndpointsVerifyResetChangeEmailAndImpersonate() throws Exception {
        start();
        bootstrapSuperuser();
        String superuserToken = loginToken();

        request("POST", "/api/collections", superuserToken, Map.of(
                "name", "auth_lifecycle_users",
                "type", "auth",
                "fields", List.of(Map.of("name", "displayName", "type", "text"))
        ));
        JsonNode user = request("POST", "/api/collections/auth_lifecycle_users/records", superuserToken, Map.of(
                "email", "lifecycle@example.com",
                "password", "secret456",
                "displayName", "Lifecycle",
                "verified", false
        ));
        String userId = user.get("id").asText();

        JsonNode auth = request("POST", "/api/collections/auth_lifecycle_users/auth-with-password", null, Map.of(
                "identity", "lifecycle@example.com",
                "password", "secret456"
        ));
        String userToken = auth.get("token").asText();

        HttpResponse<String> verificationRequest = rawRequest(
                "POST",
                "/api/collections/auth_lifecycle_users/request-verification",
                null,
                Map.of("email", "lifecycle@example.com")
        );
        assertEquals(204, verificationRequest.statusCode());
        String verificationToken = authRequestToken("verification", "lifecycle@example.com");
        HttpResponse<String> requestTokenAsBearer = rawRequest(
                "GET",
                "/api/collections/auth_lifecycle_users/records/" + userId,
                verificationToken,
                null
        );
        assertEquals(404, requestTokenAsBearer.statusCode());
        assertErrorEnvelope(requestTokenAsBearer, 404, "Record not found.");

        HttpResponse<String> verified = rawRequest(
                "POST",
                "/api/collections/auth_lifecycle_users/confirm-verification",
                null,
                Map.of("token", verificationToken)
        );
        assertEquals(204, verified.statusCode());
        JsonNode verifiedRecord = request(
                "GET",
                "/api/collections/auth_lifecycle_users/records/" + userId,
                superuserToken,
                null
        );
        assertTrue(verifiedRecord.get("verified").asBoolean());

        request("POST", "/api/collections/auth_lifecycle_users/request-password-reset", null, Map.of(
                "email", "lifecycle@example.com"
        ));
        String resetToken = authRequestToken("passwordReset", "lifecycle@example.com");
        HttpResponse<String> mismatch = rawRequest(
                "POST",
                "/api/collections/auth_lifecycle_users/confirm-password-reset",
                null,
                Map.of("token", resetToken, "password", "newsecret456", "passwordConfirm", "different")
        );
        assertEquals(400, mismatch.statusCode());
        assertFieldError(mismatch, 400, "passwordConfirm does not match password.", "passwordConfirm", "validation_invalid_value", "Passwords do not match.");

        HttpResponse<String> reset = rawRequest(
                "POST",
                "/api/collections/auth_lifecycle_users/confirm-password-reset",
                null,
                Map.of("token", resetToken, "password", "newsecret456", "passwordConfirm", "newsecret456")
        );
        assertEquals(204, reset.statusCode());
        assertEquals(401, rawRequest("POST", "/api/collections/auth_lifecycle_users/auth-refresh", userToken, null).statusCode());
        HttpResponse<String> oldPassword = rawRequest("POST", "/api/collections/auth_lifecycle_users/auth-with-password", null, Map.of(
                "identity", "lifecycle@example.com",
                "password", "secret456"
        ));
        assertEquals(400, oldPassword.statusCode());
        assertErrorEnvelope(oldPassword, 400, "Failed to authenticate.");

        JsonNode newAuth = request("POST", "/api/collections/auth_lifecycle_users/auth-with-password", null, Map.of(
                "identity", "lifecycle@example.com",
                "password", "newsecret456"
        ));
        String newUserToken = newAuth.get("token").asText();

        HttpResponse<String> emailChangeRequest = rawRequest(
                "POST",
                "/api/collections/auth_lifecycle_users/request-email-change",
                newUserToken,
                Map.of("newEmail", "changed@example.com")
        );
        assertEquals(204, emailChangeRequest.statusCode());
        String emailChangeToken = authRequestToken("emailChange", "lifecycle@example.com");
        HttpResponse<String> wrongEmailChangePassword = rawRequest("POST", "/api/collections/auth_lifecycle_users/confirm-email-change", null, Map.of(
                "token", emailChangeToken,
                "password", "wrong-password"
        ));
        assertEquals(400, wrongEmailChangePassword.statusCode());
        assertFieldError(wrongEmailChangePassword, 400, "Invalid password.", "password", "validation_invalid_value", "Invalid password.");
        assertEquals(204, rawRequest("POST", "/api/collections/auth_lifecycle_users/confirm-email-change", null, Map.of(
                "token", emailChangeToken,
                "password", "newsecret456"
        )).statusCode());
        HttpResponse<String> oldEmailAuth = rawRequest("POST", "/api/collections/auth_lifecycle_users/auth-with-password", null, Map.of(
                "identity", "lifecycle@example.com",
                "password", "newsecret456"
        ));
        assertEquals(400, oldEmailAuth.statusCode());
        assertErrorEnvelope(oldEmailAuth, 400, "Failed to authenticate.");
        JsonNode changedAuth = request("POST", "/api/collections/auth_lifecycle_users/auth-with-password", null, Map.of(
                "identity", "changed@example.com",
                "password", "newsecret456"
        ));

        HttpResponse<String> forbiddenImpersonate = rawRequest(
                "POST",
                "/api/collections/auth_lifecycle_users/impersonate/" + userId,
                changedAuth.get("token").asText(),
                Map.of("duration", 120)
        );
        assertEquals(403, forbiddenImpersonate.statusCode());
        JsonNode impersonated = request(
                "POST",
                "/api/collections/auth_lifecycle_users/impersonate/" + userId,
                superuserToken,
                Map.of("duration", 120)
        );
        assertEquals(userId, impersonated.get("record").get("id").asText());
        assertTrue(impersonated.hasNonNull("token"));
        assertEquals(401, rawRequest(
                "POST",
                "/api/collections/auth_lifecycle_users/auth-refresh",
                impersonated.get("token").asText(),
                null
        ).statusCode());
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
        assertErrorEnvelope(forgedCreate, 400, "The record failed the collection create rule.");

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

        String filePath = "/api/files/assets/" + created.get("id").asText() + "/" + filename;
        HttpResponse<String> file = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + filePath)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(200, file.statusCode());
        assertEquals("text/plain; charset=utf-8", file.headers().firstValue("Content-Type").orElse(""));
        assertEquals("hello from multipart", file.body());

        HttpResponse<String> clampedRange = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + filePath))
                        .header("Range", "bytes=0-999999")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(206, clampedRange.statusCode());
        assertEquals("bytes 0-19/20", clampedRange.headers().firstValue("Content-Range").orElse(""));
        assertEquals("20", clampedRange.headers().firstValue("Content-Length").orElse(""));
        assertEquals("hello from multipart", clampedRange.body());

        HttpResponse<String> suffixRange = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + filePath))
                        .header("Range", "bytes=-9")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(206, suffixRange.statusCode());
        assertEquals("bytes 11-19/20", suffixRange.headers().firstValue("Content-Range").orElse(""));
        assertEquals("multipart", suffixRange.body());

        HttpResponse<String> unsatisfiableRange = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + filePath))
                        .header("Range", "bytes=20-25")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(416, unsatisfiableRange.statusCode());
        assertEquals("bytes */20", unsatisfiableRange.headers().firstValue("Content-Range").orElse(""));

        HttpResponse<String> missingFile = rawRequest(
                "GET",
                "/api/files/assets/" + created.get("id").asText() + "/missing.txt",
                null,
                null
        );
        assertEquals(404, missingFile.statusCode());
        assertErrorEnvelope(missingFile, 404, "File not found.");
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
        assertFieldError(
                badMime,
                400,
                "File `bad.png` MIME type is not allowed for field `attachment`.",
                "attachment",
                "validation_invalid_value",
                "File `bad.png` MIME type is not allowed for field `attachment`."
        );

        HttpResponse<String> tooLarge = rawMultipartRequest("POST", "/api/collections/validated_assets/records", token, Map.of(
                "title", "Too large"
        ), Map.of(
                "attachment", new MultipartFile("large.txt", "text/plain", "this payload is too large".getBytes(StandardCharsets.UTF_8))
        ));
        assertEquals(400, tooLarge.statusCode());
        assertFieldError(
                tooLarge,
                400,
                "File `large.txt` exceeds maxSize for field `attachment`.",
                "attachment",
                "validation_invalid_value",
                "File `large.txt` exceeds maxSize for field `attachment`."
        );
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
        assertFieldError(publicFile, 403, "Protected file token required.", "token", "validation_required", "Cannot be blank.");

        JsonNode aliceFileToken = request("POST", "/api/files/token", aliceToken, null);
        HttpResponse<String> fileTokenAsBearer = rawRequest("GET", filePath, aliceFileToken.get("token").asText(), null);
        assertEquals(403, fileTokenAsBearer.statusCode());
        assertFieldError(fileTokenAsBearer, 403, "Protected file token required.", "token", "validation_required", "Cannot be blank.");

        HttpResponse<String> aliceFile = rawRequest("GET", filePath + "?token=" + aliceFileToken.get("token").asText(), null, null);
        assertEquals(200, aliceFile.statusCode());
        assertEquals("protected payload", aliceFile.body());

        JsonNode bobFileToken = request("POST", "/api/files/token", bobToken, null);
        HttpResponse<String> bobFile = rawRequest("GET", filePath + "?token=" + bobFileToken.get("token").asText(), null, null);
        assertEquals(403, bobFile.statusCode());
        assertFieldError(bobFile, 403, "Protected file is not accessible.", "token", "validation_invalid_value", "Protected file is not accessible.");
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
    void realtimeValidationErrorsUseOfficialEnvelope() throws Exception {
        start();
        bootstrapSuperuser();
        String token = loginToken();

        HttpResponse<String> missingClient = rawRequest("POST", "/api/realtime", token, Map.of(
                "subscriptions", List.of("updates/*")
        ));
        assertEquals(400, missingClient.statusCode());
        assertFieldError(missingClient, 400, "Failed to subscribe.", "clientId", "validation_required", "Cannot be blank.");

        HttpResponse<String> arrayPayload = rawJsonRequest("POST", "/api/realtime", token, "[]");
        assertEquals(400, arrayPayload.statusCode());
        assertFieldError(arrayPayload, 400, "Realtime subscription payload must be an object.", "body", "validation_invalid_value", "Request body must be a JSON object.");

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

            HttpResponse<String> invalidOptions = rawRequest("POST", "/api/realtime", token, Map.of(
                    "clientId", clientId,
                    "subscriptions", List.of("updates/*"),
                    "options", "[]"
            ));
            assertEquals(400, invalidOptions.statusCode());
            assertFieldError(invalidOptions, 400, "Failed to subscribe.", "options", "validation_invalid_value", "Realtime subscription options must be an object.");
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
        TestDatabaseFactory.init();
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

    private String authRequestToken(String type, String email) throws IOException {
        JsonNode requests = mapper.readTree(tempDir.resolve("auth_requests.json").toFile());
        for (int i = requests.size() - 1; i >= 0; i--) {
            JsonNode request = requests.get(i);
            if (type.equals(request.path("type").asText())
                    && email.equalsIgnoreCase(request.path("email").asText())
                    && request.hasNonNull("token")) {
                return request.get("token").asText();
            }
        }
        throw new AssertionError("No auth request token for " + type + " / " + email);
    }

    private String otpRequestPassword(String email, String otpId) throws IOException {
        JsonNode requests = mapper.readTree(tempDir.resolve("auth_requests.json").toFile());
        for (int i = requests.size() - 1; i >= 0; i--) {
            JsonNode request = requests.get(i);
            if ("otp".equals(request.path("type").asText())
                    && email.equalsIgnoreCase(request.path("email").asText())
                    && otpId.equals(request.path("otpId").asText())
                    && request.hasNonNull("password")) {
                return request.get("password").asText();
            }
        }
        throw new AssertionError("No OTP request for " + email + " / " + otpId);
    }

    private boolean cronExists(JsonNode crons, String id, String expression) {
        for (JsonNode cron : crons) {
            if (id.equals(cron.path("id").asText()) && expression.equals(cron.path("expression").asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean waitForAutoBackupCount(int expected) throws Exception {
        Path backups = tempDir.resolve("backups");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Files.exists(backups)) {
                try (var paths = Files.list(backups)) {
                    long count = paths
                            .filter(path -> path.getFileName().toString().startsWith("@auto_pb_backup_"))
                            .count();
                    if (count >= expected) {
                        return true;
                    }
                }
            }
            Thread.sleep(50);
        }
        return false;
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

    private HttpResponse<String> rawJsonRequest(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> rawBodyRequest(String method, String path, String token, String contentType, byte[] body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
                .header("Accept", "application/json")
                .header("Content-Type", contentType)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
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

    private void assertFieldError(
            HttpResponse<String> response,
            int status,
            String message,
            String field,
            String code,
            String fieldMessage
    ) throws IOException {
        JsonNode body = mapper.readTree(response.body());
        assertEquals(status, body.get("status").asInt());
        assertFalse(body.has("code"));
        assertEquals(message, body.get("message").asText());
        JsonNode fieldError = body.get("data").get(field);
        assertNotNull(fieldError, "Missing validation error for " + field);
        assertEquals(code, fieldError.get("code").asText());
        assertEquals(fieldMessage, fieldError.get("message").asText());
    }

    private void assertFieldErrorMessageStartsWith(
            HttpResponse<String> response,
            int status,
            String message,
            String field,
            String code,
            String fieldMessagePrefix
    ) throws IOException {
        JsonNode body = mapper.readTree(response.body());
        assertEquals(status, body.get("status").asInt());
        assertFalse(body.has("code"));
        assertEquals(message, body.get("message").asText());
        JsonNode fieldError = body.get("data").get(field);
        assertNotNull(fieldError, "Missing validation error for " + field);
        assertEquals(code, fieldError.get("code").asText());
        assertTrue(fieldError.get("message").asText().startsWith(fieldMessagePrefix));
    }

    private void assertMessageAndFieldErrorStartWith(
            HttpResponse<String> response,
            int status,
            String messagePrefix,
            String field,
            String code,
            String fieldMessagePrefix
    ) throws IOException {
        JsonNode body = mapper.readTree(response.body());
        assertEquals(status, body.get("status").asInt());
        assertFalse(body.has("code"));
        assertTrue(body.get("message").asText().startsWith(messagePrefix));
        JsonNode fieldError = body.get("data").get(field);
        assertNotNull(fieldError, "Missing validation error for " + field);
        assertEquals(code, fieldError.get("code").asText());
        assertTrue(fieldError.get("message").asText().startsWith(fieldMessagePrefix));
    }

    private void assertErrorEnvelope(HttpResponse<String> response, int status, String message) throws IOException {
        JsonNode body = mapper.readTree(response.body());
        assertEquals(status, body.get("status").asInt());
        assertFalse(body.has("code"));
        assertEquals(message, body.get("message").asText());
        assertTrue(body.get("data").isObject());
        assertEquals(0, body.get("data").size());
    }

    private void appendZipEntry(Path source, Path target, String entryName, byte[] entryBytes) throws IOException {
        try (InputStream input = Files.newInputStream(source);
             ZipInputStream zipInput = new ZipInputStream(input);
             ZipOutputStream zipOutput = new ZipOutputStream(Files.newOutputStream(target))) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                ZipEntry copy = new ZipEntry(entry.getName());
                zipOutput.putNextEntry(copy);
                zipInput.transferTo(zipOutput);
                zipOutput.closeEntry();
                zipInput.closeEntry();
            }
            zipOutput.putNextEntry(new ZipEntry(entryName));
            zipOutput.write(entryBytes);
            zipOutput.closeEntry();
        }
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

    private List<String> fieldNames(JsonNode collection) {
        List<String> names = new java.util.ArrayList<>();
        collection.get("fields").forEach(field -> names.add(field.get("name").asText()));
        return names;
    }

    private List<String> providerNames(JsonNode providers) {
        List<String> names = new java.util.ArrayList<>();
        providers.forEach(provider -> names.add(provider.get("name").asText()));
        return names;
    }

    private String ecPrivateKeyPem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        byte[] encoded = generator.generateKeyPair().getPrivate().getEncoded();
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(encoded)
                + "\n-----END PRIVATE KEY-----";
    }

    private void assertAppleClientSecret(String secret) throws IOException {
        String[] parts = secret.split("\\.");
        assertEquals(3, parts.length);
        JsonNode header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode payload = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertEquals("ES256", header.get("alg").asText());
        assertEquals("KEYID12345", header.get("kid").asText());
        assertEquals("TEAMID1234", payload.get("iss").asText());
        assertEquals("com.example.service", payload.get("sub").asText());
        assertEquals("https://appleid.apple.com", payload.get("aud").asText());
        assertTrue(parts[2].length() > 80);
    }

    private void assertTokenLifetime(String token, long expectedSeconds) throws IOException {
        JsonNode payload = jwtPayload(token);
        assertEquals(expectedSeconds, payload.get("exp").asLong() - payload.get("iat").asLong());
    }

    private JsonNode jwtPayload(String token) throws IOException {
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length);
        return mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
    }

    private record MultipartFile(String name, String contentType, byte[] bytes) {
    }

    private record SseEvent(String event, String data) {
    }

    private static final class FakeSmtpServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final CountDownLatch messageReceived = new CountDownLatch(1);
        private final AtomicReference<String> message = new AtomicReference<>("");
        private final String greeting;

        private FakeSmtpServer(ServerSocket serverSocket, String greeting) {
            this.serverSocket = serverSocket;
            this.greeting = greeting;
            executor.submit(this::serveOne);
        }

        static FakeSmtpServer start() throws IOException {
            return start("220 fake-smtp");
        }

        static FakeSmtpServer start(String greeting) throws IOException {
            return new FakeSmtpServer(new ServerSocket(0), greeting);
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        String message() throws InterruptedException {
            assertTrue(messageReceived.await(5, TimeUnit.SECONDS));
            return message.get();
        }

        private void serveOne() {
            try (Socket socket = serverSocket.accept();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                write(writer, greeting);
                if (!greeting.startsWith("220")) {
                    messageReceived.countDown();
                    return;
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    String upper = line.toUpperCase();
                    if (upper.startsWith("EHLO") || upper.startsWith("HELO")) {
                        write(writer, "250-fake-smtp");
                        write(writer, "250 OK");
                    } else if (upper.startsWith("MAIL FROM") || upper.startsWith("RCPT TO")) {
                        write(writer, "250 OK");
                    } else if (upper.startsWith("DATA")) {
                        write(writer, "354 End data");
                        StringBuilder body = new StringBuilder();
                        while ((line = reader.readLine()) != null && !".".equals(line)) {
                            body.append(line).append('\n');
                        }
                        message.set(body.toString());
                        messageReceived.countDown();
                        write(writer, "250 OK");
                    } else if (upper.startsWith("QUIT")) {
                        write(writer, "221 Bye");
                        return;
                    } else {
                        write(writer, "250 OK");
                    }
                }
            } catch (IOException ignored) {
                messageReceived.countDown();
            }
        }

        private static void write(BufferedWriter writer, String line) throws IOException {
            writer.write(line);
            writer.write("\r\n");
            writer.flush();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            executor.shutdownNow();
        }
    }

    private static final class FakeOAuth2Server implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> lastTokenBody = new AtomicReference<>("");

        private FakeOAuth2Server(HttpServer server) {
            this.server = server;
        }

        static FakeOAuth2Server start() throws IOException {
            return start(200, """
                    {"access_token":"token-123","token_type":"Bearer"}
                    """, 200, """
                    {
                      "sub":"oauth-sub-123",
                      "email":"oidc@example.com",
                      "name":"OIDC User",
                      "preferred_username":"oidc-user"
                    }
                    """);
        }

        static FakeOAuth2Server start(int tokenStatus, String tokenBody, int userInfoStatus, String userInfoBody) throws IOException {
            HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            FakeOAuth2Server fake = new FakeOAuth2Server(server);
            server.createContext("/token", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                fake.lastTokenBody.set(body);
                byte[] bytes = tokenBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(tokenStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.createContext("/userinfo", exchange -> {
                byte[] bytes = userInfoBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(userInfoStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return fake;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String lastTokenBody() {
            return lastTokenBody.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
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
