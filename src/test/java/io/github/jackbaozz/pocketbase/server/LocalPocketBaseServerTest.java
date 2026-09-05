package io.github.jackbaozz.pocketbase.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.jackbaozz.pocketbase.client.AuthResponse;
import io.github.jackbaozz.pocketbase.client.PocketBaseClient;
import io.github.jackbaozz.pocketbase.client.RecordList;
import io.github.jackbaozz.pocketbase.server.internal.SystemCollections;
import io.github.jackbaozz.pocketbase.server.internal.TokenService;
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
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
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
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    AuthResponse auth =
        client.collection("_superusers").authWithPassword("root@example.com", "Secret_123");
    assertNotNull(auth.token());

    JsonNode collection =
        client
            .collections()
            .create(
                Map.of(
                    "name", "posts",
                    "type", "base",
                    "fields",
                    List.of(
                        Map.of("name", "title", "type", "text", "required", true),
                        Map.of("name", "published", "type", "bool"))));
    assertEquals("posts", collection.get("name").asText());

    JsonNode created =
        client.collection("posts").create(Map.of("title", "Hello Java", "published", true));
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

    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/settings"))
                .header("Accept", "application/json")
                .header("Authorization", token)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode());
  }

  @Test
  void collectionListSupportsFilterSortAndFields() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name",
            "collection_api_alpha",
            "fields",
            List.of(Map.of("name", "name", "type", "text"))));
    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name",
            "collection_api_beta",
            "fields",
            List.of(Map.of("name", "name", "type", "text"))));

    String filter = URLEncoder.encode("name ~ 'collection_api_'", StandardCharsets.UTF_8);
    JsonNode page =
        request(
            "GET",
            "/api/collections?filter="
                + filter
                + "&sort=-name&page=1&perPage=1&fields=id,name,type",
            token,
            null);

    assertEquals(2, page.get("totalItems").asInt());
    assertEquals(2, page.get("totalPages").asInt());
    JsonNode item = page.get("items").get(0);
    assertEquals("collection_api_beta", item.get("name").asText());
    assertEquals("base", item.get("type").asText());
    assertFalse(item.has("fields"));

    JsonNode fullPage =
        request(
            "GET",
            "/api/collections?filter=" + filter + "&sort=name&page=1&perPage=10",
            token,
            null);
    JsonNode alpha = null;
    for (JsonNode collection : fullPage.get("items")) {
      if ("collection_api_alpha".equals(collection.get("name").asText())) {
        alpha = collection;
        break;
      }
    }
    assertNotNull(alpha);
    assertTrue(fieldNames(alpha).contains("name"));

    HttpResponse<String> invalidFilter =
        rawRequest(
            "GET",
            "/api/collections?filter=" + URLEncoder.encode("name #", StandardCharsets.UTF_8),
            token,
            null);
    assertEquals(400, invalidFilter.statusCode());
    assertFieldErrorMessageStartsWith(
        invalidFilter,
        400,
        "Invalid filter.",
        "filter",
        "validation_invalid_value",
        "Invalid filter");

    JsonNode single =
        request("GET", "/api/collections/collection_api_alpha?fields=id,name", token, null);
    assertEquals("collection_api_alpha", single.get("name").asText());
    assertTrue(single.has("id"));
    assertFalse(single.has("type"));
  }

  @Test
  void searchPaginationMatchesOfficialProviderContract() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "search_contract_posts",
            "listRule", "",
            "viewRule", "",
            "fields", List.of(Map.of("name", "title", "type", "text"))));
    JsonNode recordB =
        request(
            "POST", "/api/collections/search_contract_posts/records", token, Map.of("title", "B"));
    request("POST", "/api/collections/search_contract_posts/records", token, Map.of("title", "A"));
    request("POST", "/api/collections/search_contract_posts/records", token, Map.of("title", "C"));

    JsonNode second =
        request(
            "GET",
            "/api/collections/search_contract_posts/records?page=2&perPage=1&sort=%2Btitle&skipTotal=1&fields=id",
            token,
            null);
    assertEquals(2, second.get("page").asInt());
    assertEquals(1, second.get("perPage").asInt());
    assertEquals(-1, second.get("totalItems").asInt());
    assertEquals(-1, second.get("totalPages").asInt());
    assertEquals(recordB.get("id").asText(), second.get("items").get(0).get("id").asText());
    assertFalse(second.get("items").get(0).has("title"));
    assertFalse(second.get("items").get(0).has("collectionName"));

    JsonNode normalized =
        request(
            "GET",
            "/api/collections/search_contract_posts/records?page=0&perPage=0&sort=title",
            token,
            null);
    assertEquals(1, normalized.get("page").asInt());
    assertEquals(30, normalized.get("perPage").asInt());
    assertEquals(3, normalized.get("totalItems").asInt());

    JsonNode capped =
        request(
            "GET",
            "/api/collections/search_contract_posts/records?perPage=9999&skipTotal=True",
            token,
            null);
    assertEquals(1000, capped.get("perPage").asInt());
    assertEquals(-1, capped.get("totalItems").asInt());
    assertEquals(3, capped.get("items").size());

    String collectionFilter =
        URLEncoder.encode("name = 'search_contract_posts'", StandardCharsets.UTF_8);
    JsonNode collections =
        request("GET", "/api/collections?filter=" + collectionFilter + "&skipTotal=t", token, null);
    assertEquals(30, collections.get("perPage").asInt());
    assertEquals(-1, collections.get("totalItems").asInt());
    assertEquals(-1, collections.get("totalPages").asInt());
    assertEquals(1, collections.get("items").size());

    JsonNode logs = request("GET", "/api/logs?perPage=9999&skipTotal=T&sort=-@rowid", token, null);
    assertEquals(1000, logs.get("perPage").asInt());
    assertEquals(-1, logs.get("totalItems").asInt());
    assertEquals(-1, logs.get("totalPages").asInt());
    assertTrue(logs.get("items").size() >= 1);

    assertEquals(
        400,
        rawRequest(
            "GET", "/api/collections/search_contract_posts/records?page=invalid", token, null)
            .statusCode());
    assertEquals(
        400,
        rawRequest(
            "GET",
            "/api/collections/search_contract_posts/records?perPage=invalid",
            token,
            null)
            .statusCode());
    assertEquals(
        400,
        rawRequest(
            "GET",
            "/api/collections/search_contract_posts/records?skipTotal=invalid",
            token,
            null)
            .statusCode());
    assertEquals(
        400,
        rawRequest(
            "GET",
            "/api/collections/search_contract_posts/records?sort=title,title,title,title,title,title,title,title,title",
            token,
            null)
            .statusCode());
    assertEquals(
        400,
        rawRequest(
            "GET",
            "/api/collections/search_contract_posts/records?sort=" + "a".repeat(256),
            token,
            null)
            .statusCode());
    assertEquals(
        400,
        rawRequest(
            "GET",
            "/api/collections/search_contract_posts/records?filter=" + "a".repeat(3501),
            token,
            null)
            .statusCode());

    String superuserFilter =
        URLEncoder.encode("@collection.search_contract_posts.title = 'B'", StandardCharsets.UTF_8);
    assertEquals(
        403,
        rawRequest(
            "GET",
            "/api/collections/search_contract_posts/records?filter=" + superuserFilter,
            null,
            null)
            .statusCode());
    assertEquals(
        403,
        rawRequest(
            "GET",
            "/api/collections/search_contract_posts/records?sort=@request.auth.id",
            null,
            null)
            .statusCode());
  }

  @Test
  void hiddenRecordFieldsAreWritableOnlyBySuperusers() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    JsonNode hiddenCollection =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "hidden_field_posts",
                "createRule", "",
                "listRule", "",
                "viewRule", "",
                "updateRule", "",
                "fields",
                List.of(
                    Map.of("name", "title", "type", "text"),
                    Map.of("name", "secret", "type", "text", "hidden", true),
                    Map.of(
                        "name",
                        "attachment",
                        "type",
                        "file",
                        "hidden",
                        true,
                        "maxSelect",
                        1))));

    String recordId = "hidden123456789";
    JsonNode created =
        multipartRequest(
            "POST",
            "/api/collections/hidden_field_posts/records",
            null,
            Map.of("id", recordId, "title", "guest create", "secret", "guest secret"),
            Map.of(
                "attachment",
                new MultipartFile(
                    "guest.txt", "text/plain", "guest file".getBytes(StandardCharsets.UTF_8))));
    assertEquals(recordId, created.get("id").asText());
    assertFalse(created.has("secret"));
    assertFalse(created.has("attachment"));
    Path hiddenStorage =
        tempDir.resolve("storage").resolve(hiddenCollection.get("id").asText()).resolve(recordId);
    assertFalse(Files.exists(hiddenStorage));

    JsonNode storedAfterCreate =
        request("GET", "/api/collections/hidden_field_posts/records/" + recordId, token, null);
    assertEquals("", storedAfterCreate.path("secret").asText(""));
    assertEquals("", storedAfterCreate.path("attachment").asText(""));

    JsonNode guestUpdated =
        request(
            "PATCH",
            "/api/collections/hidden_field_posts/records/" + recordId,
            null,
            Map.of("title", "guest update", "secret", "guest overwrite"));
    assertEquals("guest update", guestUpdated.get("title").asText());
    assertFalse(guestUpdated.has("secret"));

    JsonNode superuserUpdated =
        request(
            "PATCH",
            "/api/collections/hidden_field_posts/records/" + recordId,
            token,
            Map.of("secret", "superuser secret"));
    assertEquals("superuser secret", superuserUpdated.get("secret").asText());

    JsonNode superuserFileUpdated =
        multipartRequest(
            "PATCH",
            "/api/collections/hidden_field_posts/records/" + recordId,
            token,
            Map.of(),
            Map.of(
                "attachment",
                new MultipartFile(
                    "superuser.txt",
                    "text/plain",
                    "superuser file".getBytes(StandardCharsets.UTF_8))));
    String superuserFilename = superuserFileUpdated.get("attachment").asText();
    assertTrue(superuserFilename.startsWith("superuser_"));

    multipartRequest(
        "PATCH",
        "/api/collections/hidden_field_posts/records/" + recordId,
        null,
        Map.of("secret", "second guest overwrite", "attachment-", superuserFilename),
        Map.of(
            "attachment",
            new MultipartFile(
                "guest-overwrite.txt",
                "text/plain",
                "guest overwrite".getBytes(StandardCharsets.UTF_8))));
    JsonNode storedAfterGuestUpdate =
        request("GET", "/api/collections/hidden_field_posts/records/" + recordId, token, null);
    assertEquals("superuser secret", storedAfterGuestUpdate.get("secret").asText());
    assertEquals(superuserFilename, storedAfterGuestUpdate.get("attachment").asText());
    try (var files = Files.list(hiddenStorage)) {
      assertEquals(
          List.of(superuserFilename),
          files.map(path -> path.getFileName().toString()).sorted().toList());
    }
    assertEquals(
        "superuser file",
        Files.readString(hiddenStorage.resolve(superuserFilename), StandardCharsets.UTF_8));

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "hidden_field_auth",
            "type", "auth",
            "createRule", "",
            "listRule", "",
            "viewRule", ""));
    request(
        "POST",
        "/api/collections/hidden_field_auth/records",
        null,
        Map.of(
            "email", "hidden-password@example.com",
            "password", "Secret_456",
            "passwordConfirm", "Secret_456"));
    JsonNode auth =
        request(
            "POST",
            "/api/collections/hidden_field_auth/auth-with-password",
            null,
            Map.of("identity", "hidden-password@example.com", "password", "Secret_456"));
    assertTrue(auth.hasNonNull("token"));
  }

  @Test
  void searchFieldsRejectUnknownAndHiddenClientFields() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "search_field_posts",
            "listRule", "secret = 'match'",
            "viewRule", "",
            "fields",
            List.of(
                Map.of("name", "title", "type", "text"),
                Map.of("name", "secret", "type", "text", "hidden", true))));
    request(
        "POST",
        "/api/collections/search_field_posts/records",
        token,
        Map.of("title", "visible", "secret", "match"));
    request(
        "POST",
        "/api/collections/search_field_posts/records",
        token,
        Map.of("title", "excluded", "secret", "other"));

    JsonNode ruleFiltered =
        request("GET", "/api/collections/search_field_posts/records?sort=title", null, null);
    assertEquals(1, ruleFiltered.get("totalItems").asInt());
    assertEquals("visible", ruleFiltered.get("items").get(0).get("title").asText());
    assertFalse(ruleFiltered.get("items").get(0).has("secret"));

    String visibleFilter = URLEncoder.encode("title = 'visible'", StandardCharsets.UTF_8);
    assertEquals(
        1,
        request(
            "GET",
            "/api/collections/search_field_posts/records?filter=" + visibleFilter,
            null,
            null)
            .get("totalItems")
            .asInt());

    String hiddenFilter = URLEncoder.encode("secret = 'match'", StandardCharsets.UTF_8);
    assertEquals(
        400,
        rawRequest(
            "GET",
            "/api/collections/search_field_posts/records?filter=" + hiddenFilter,
            null,
            null)
            .statusCode());
    assertEquals(
        400,
        rawRequest("GET", "/api/collections/search_field_posts/records?sort=secret", null, null)
            .statusCode());

    JsonNode superuserHidden =
        request(
            "GET",
            "/api/collections/search_field_posts/records?filter=" + hiddenFilter + "&sort=secret",
            token,
            null);
    assertEquals(1, superuserHidden.get("totalItems").asInt());
    assertEquals("match", superuserHidden.get("items").get(0).get("secret").asText());

    String unknownFilter = URLEncoder.encode("missing = 'value'", StandardCharsets.UTF_8);
    assertEquals(
        400,
        rawRequest(
            "GET",
            "/api/collections/search_field_posts/records?filter=" + unknownFilter,
            token,
            null)
            .statusCode());
    assertEquals(
        400,
        rawRequest("GET", "/api/collections/search_field_posts/records?sort=missing", token, null)
            .statusCode());
    assertEquals(
        400,
        rawRequest("GET", "/api/collections?filter=" + unknownFilter, token, null).statusCode());
    assertEquals(400, rawRequest("GET", "/api/collections?sort=missing", token, null).statusCode());
    assertEquals(
        400, rawRequest("GET", "/api/logs?filter=" + unknownFilter, token, null).statusCode());
    assertEquals(400, rawRequest("GET", "/api/logs?sort=missing", token, null).statusCode());
    assertEquals(
        400,
        rawRequest("GET", "/api/logs/stats?filter=" + unknownFilter, token, null).statusCode());
  }

  @Test
  void filterModifiersMatchOfficialResolverSemantics() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "filter_modifier_posts",
            "listRule", "",
            "viewRule", "",
            "fields",
            List.of(
                Map.of("name", "title", "type", "text"),
                Map.of(
                    "name", "tags",
                    "type", "select",
                    "options",
                    Map.of("values", List.of("blue", "red", "green"), "maxSelect", 3)))));
    request(
        "POST",
        "/api/collections/filter_modifier_posts/records",
        token,
        Map.of("title", "Bravo", "tags", List.of("blue")));
    request(
        "POST",
        "/api/collections/filter_modifier_posts/records",
        token,
        Map.of("title", "alpha", "tags", List.of("red", "green")));
    request(
        "POST",
        "/api/collections/filter_modifier_posts/records",
        token,
        Map.of("title", "CHARLIE", "tags", List.of()));

    String lowerFilter = URLEncoder.encode("title:lower = 'alpha'", StandardCharsets.UTF_8);
    JsonNode lower =
        request(
            "GET",
            "/api/collections/filter_modifier_posts/records?filter=" + lowerFilter,
            null,
            null);
    assertEquals(1, lower.get("totalItems").asInt());
    assertEquals("alpha", lower.get("items").get(0).get("title").asText());

    String lengthFilter = URLEncoder.encode("tags:length = 2", StandardCharsets.UTF_8);
    JsonNode length =
        request(
            "GET",
            "/api/collections/filter_modifier_posts/records?filter=" + lengthFilter,
            null,
            null);
    assertEquals(1, length.get("totalItems").asInt());
    assertEquals("alpha", length.get("items").get(0).get("title").asText());

    String eachFilter = URLEncoder.encode("tags:each = 'green'", StandardCharsets.UTF_8);
    assertEquals(
        1,
        request(
            "GET",
            "/api/collections/filter_modifier_posts/records?filter=" + eachFilter,
            null,
            null)
            .get("totalItems")
            .asInt());

    JsonNode sorted =
        request(
            "GET",
            "/api/collections/filter_modifier_posts/records?sort=title:lower&fields=title",
            null,
            null);
    assertEquals(
        List.of("alpha", "Bravo", "CHARLIE"),
        List.of(
            sorted.get("items").get(0).get("title").asText(),
            sorted.get("items").get(1).get("title").asText(),
            sorted.get("items").get(2).get("title").asText()));

    String issetFilter =
        URLEncoder.encode("@request.query.flag:isset = true", StandardCharsets.UTF_8);
    assertEquals(
        3,
        request(
            "GET",
            "/api/collections/filter_modifier_posts/records?filter=" + issetFilter + "&flag=",
            token,
            null)
            .get("totalItems")
            .asInt());
    assertEquals(
        0,
        request(
            "GET",
            "/api/collections/filter_modifier_posts/records?filter=" + issetFilter,
            token,
            null)
            .get("totalItems")
            .asInt());

    String collectionLower =
        URLEncoder.encode("name:lower = 'filter_modifier_posts'", StandardCharsets.UTF_8);
    assertEquals(
        1,
        request("GET", "/api/collections?filter=" + collectionLower, token, null)
            .get("totalItems")
            .asInt());

    String logLower = URLEncoder.encode("message:lower ~ 'get'", StandardCharsets.UTF_8);
    assertTrue(
        request("GET", "/api/logs?filter=" + logLower, token, null).get("totalItems").asInt() >= 1);

    String invalidModifier = URLEncoder.encode("title:unknown = 'alpha'", StandardCharsets.UTF_8);
    HttpResponse<String> invalid =
        rawRequest(
            "GET",
            "/api/collections/filter_modifier_posts/records?filter=" + invalidModifier,
            token,
            null);
    assertEquals(400, invalid.statusCode());
    assertFieldErrorMessageStartsWith(
        invalid,
        400,
        "Invalid filter.",
        "filter",
        "validation_invalid_value",
        "Unknown filter modifier");
  }

  @Test
  void filterTokenFunctionsMatchOfficialStrftimeAndGeoDistanceSemantics() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "filter_function_places",
            "listRule", "",
            "viewRule", "",
            "fields",
            List.of(
                Map.of("name", "title", "type", "text"),
                Map.of("name", "occurred", "type", "text"),
                Map.of("name", "location", "type", "geoPoint"))));
    request(
        "POST",
        "/api/collections/filter_function_places/records",
        token,
        Map.of(
            "title", "Near July",
            "occurred", "2026-07-18T10:30:45.123Z",
            "location", Map.of("lon", 0.1, "lat", 0)));
    request(
        "POST",
        "/api/collections/filter_function_places/records",
        token,
        Map.of(
            "title", "Far August",
            "occurred", "2026-08-03T09:15:00Z",
            "location", Map.of("lon", 40, "lat", 20)));

    JsonNode month =
        request(
            "GET",
            "/api/collections/filter_function_places/records?filter="
                + URLEncoder.encode(
                    "strftime('%Y-%m', occurred) = '2026-07'", StandardCharsets.UTF_8),
            null,
            null);
    assertEquals(1, month.get("totalItems").asInt());
    assertEquals("Near July", month.get("items").get(0).get("title").asText());

    JsonNode shifted =
        request(
            "GET",
            "/api/collections/filter_function_places/records?filter="
                + URLEncoder.encode(
                    "strftime('%F', occurred, 'start of month', '+1 month') = '2026-08-01'",
                    StandardCharsets.UTF_8),
            null,
            null);
    assertEquals(1, shifted.get("totalItems").asInt());
    assertEquals("Near July", shifted.get("items").get(0).get("title").asText());

    JsonNode distance =
        request(
            "GET",
            "/api/collections/filter_function_places/records?filter="
                + URLEncoder.encode(
                    "geoDistance(location.lon, location.lat, 0, 0) < 20", StandardCharsets.UTF_8),
            null,
            null);
    assertEquals(1, distance.get("totalItems").asInt());
    assertEquals("Near July", distance.get("items").get(0).get("title").asText());

    HttpResponse<String> invalid =
        rawRequest(
            "GET",
            "/api/collections/filter_function_places/records?filter="
                + URLEncoder.encode("geoDistance(location.lon, 0) < 20", StandardCharsets.UTF_8),
            token,
            null);
    assertFieldErrorMessageStartsWith(
        invalid,
        400,
        "Invalid filter.",
        "filter",
        "validation_invalid_value",
        "[geoDistance] expected 4 arguments");
  }

  @Test
  void relationFilterAndSortPathsMatchOfficialMultiMatchSemantics() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    JsonNode teams =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "relation_teams",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(Map.of("name", "name", "type", "text"))));
    JsonNode authors =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "relation_authors",
                "listRule", "",
                "viewRule", "",
                "fields",
                List.of(
                    Map.of("name", "name", "type", "text"),
                    Map.of("name", "secret", "type", "text", "hidden", true),
                    Map.of(
                        "name",
                        "team",
                        "type",
                        "relation",
                        "collectionId",
                        teams.get("id").asText()))));
    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "relation_posts",
            "listRule", "",
            "viewRule", "",
            "fields",
            List.of(
                Map.of("name", "title", "type", "text"),
                Map.of(
                    "name",
                    "author",
                    "type",
                    "relation",
                    "collectionId",
                    authors.get("id").asText()),
                Map.of(
                    "name",
                    "reviewers",
                    "type",
                    "relation",
                    "collectionId",
                    authors.get("id").asText(),
                    "maxSelect",
                    3))));

    JsonNode math =
        request("POST", "/api/collections/relation_teams/records", token, Map.of("name", "Math"));
    JsonNode compilers =
        request(
            "POST", "/api/collections/relation_teams/records", token, Map.of("name", "Compilers"));
    JsonNode kernels =
        request(
            "POST", "/api/collections/relation_teams/records", token, Map.of("name", "Kernels"));
    JsonNode ada =
        request(
            "POST",
            "/api/collections/relation_authors/records",
            token,
            Map.of(
                "name", "Ada",
                "secret", "math-secret",
                "team", math.get("id").asText()));
    JsonNode grace =
        request(
            "POST",
            "/api/collections/relation_authors/records",
            token,
            Map.of(
                "name", "Grace",
                "secret", "compiler-secret",
                "team", compilers.get("id").asText()));
    JsonNode linus =
        request(
            "POST",
            "/api/collections/relation_authors/records",
            token,
            Map.of(
                "name", "Linus",
                "secret", "kernel-secret",
                "team", kernels.get("id").asText()));

    request(
        "POST",
        "/api/collections/relation_posts/records",
        token,
        Map.of(
            "title", "Mixed reviewers",
            "author", ada.get("id").asText(),
            "reviewers", List.of(ada.get("id").asText(), grace.get("id").asText())));
    request(
        "POST",
        "/api/collections/relation_posts/records",
        token,
        Map.of(
            "title", "Grace only",
            "author", grace.get("id").asText(),
            "reviewers", List.of(grace.get("id").asText())));
    request(
        "POST",
        "/api/collections/relation_posts/records",
        token,
        Map.of(
            "title", "Ada review",
            "author", linus.get("id").asText(),
            "reviewers", List.of(ada.get("id").asText())));

    JsonNode direct =
        request(
            "GET",
            "/api/collections/relation_posts/records?filter="
                + URLEncoder.encode("author.name = 'Ada'", StandardCharsets.UTF_8),
            null,
            null);
    assertEquals(1, direct.get("totalItems").asInt());
    assertEquals("Mixed reviewers", direct.get("items").get(0).get("title").asText());

    JsonNode nested =
        request(
            "GET",
            "/api/collections/relation_posts/records?filter="
                + URLEncoder.encode("author.team.name = 'Math'", StandardCharsets.UTF_8),
            null,
            null);
    assertEquals(1, nested.get("totalItems").asInt());
    assertEquals("Mixed reviewers", nested.get("items").get(0).get("title").asText());

    JsonNode allMatch =
        request(
            "GET",
            "/api/collections/relation_posts/records?filter="
                + URLEncoder.encode("reviewers.name = 'Ada'", StandardCharsets.UTF_8),
            null,
            null);
    assertEquals(1, allMatch.get("totalItems").asInt());
    assertEquals("Ada review", allMatch.get("items").get(0).get("title").asText());

    JsonNode anyMatch =
        request(
            "GET",
            "/api/collections/relation_posts/records?filter="
                + URLEncoder.encode("reviewers.name ?= 'Ada'", StandardCharsets.UTF_8),
            null,
            null);
    assertEquals(2, anyMatch.get("totalItems").asInt());

    JsonNode sorted =
        request(
            "GET",
            "/api/collections/relation_posts/records?sort=author.name&fields=title",
            null,
            null);
    assertEquals(
        List.of("Mixed reviewers", "Grace only", "Ada review"),
        List.of(
            sorted.get("items").get(0).get("title").asText(),
            sorted.get("items").get(1).get("title").asText(),
            sorted.get("items").get(2).get("title").asText()));

    JsonNode backRelation =
        request(
            "GET",
            "/api/collections/relation_authors/records?filter="
                + URLEncoder.encode(
                    "relation_posts_via_author.title ?= 'Mixed reviewers'", StandardCharsets.UTF_8),
            null,
            null);
    assertEquals(1, backRelation.get("totalItems").asInt());
    assertEquals("Ada", backRelation.get("items").get(0).get("name").asText());

    assertEquals(
        400,
        rawRequest(
            "GET",
            "/api/collections/relation_posts/records?filter="
                + URLEncoder.encode("author.secret = 'math-secret'", StandardCharsets.UTF_8),
            null,
            null)
            .statusCode());
    assertEquals(
        1,
        request(
            "GET",
            "/api/collections/relation_posts/records?filter="
                + URLEncoder.encode("author.secret = 'math-secret'", StandardCharsets.UTF_8),
            token,
            null)
            .get("totalItems")
            .asInt());

    request(
        "PATCH", "/api/collections/relation_authors", token, Map.of("listRule", "name != 'Grace'"));
    String graceFilter = URLEncoder.encode("author.name = 'Grace'", StandardCharsets.UTF_8);
    assertEquals(
        0,
        request("GET", "/api/collections/relation_posts/records?filter=" + graceFilter, null, null)
            .get("totalItems")
            .asInt());
    assertEquals(
        1,
        request("GET", "/api/collections/relation_posts/records?filter=" + graceFilter, token, null)
            .get("totalItems")
            .asInt());

    request(
        "PATCH",
        "/api/collections/relation_posts",
        token,
        Map.of("listRule", "author.team.name != 'Kernels'"));
    assertEquals(
        2,
        request("GET", "/api/collections/relation_posts/records", null, null)
            .get("totalItems")
            .asInt());
    assertEquals(
        3,
        request("GET", "/api/collections/relation_posts/records", token, null)
            .get("totalItems")
            .asInt());
  }

  @Test
  void collectionRulesValidateCompleteResolverPathsOnCreateUpdateAndImport() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    JsonNode teams =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "rule_teams",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(Map.of("name", "name", "type", "text"))));
    JsonNode authors =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "rule_authors",
                "listRule", "",
                "viewRule", "",
                "fields",
                List.of(
                    Map.of("name", "name", "type", "text"),
                    Map.of(
                        "name",
                        "team",
                        "type",
                        "relation",
                        "collectionId",
                        teams.get("id").asText()))));

    HttpResponse<String> invalidCreate =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "invalid_rule_posts",
                "listRule", "author.missing = ''",
                "fields",
                List.of(
                    Map.of(
                        "name", "author",
                        "type", "relation",
                        "collectionId", authors.get("id").asText()))));
    assertEquals(400, invalidCreate.statusCode());
    assertTrue(mapper.readTree(invalidCreate.body()).get("data").has("listRule"));
    assertEquals(
        404, rawRequest("GET", "/api/collections/invalid_rule_posts", token, null).statusCode());

    String validListRule =
        "author.team.name != ''"
            + " && strftime('%Y', created) != ''"
            + " && @collection.rule_teams.name ?!= ''";
    JsonNode posts =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "rule_posts",
                "listRule", validListRule,
                "viewRule", "",
                "updateRule", "@request.body.author.team.name != ''",
                "fields",
                List.of(
                    Map.of("name", "title", "type", "text"),
                    Map.of(
                        "name",
                        "author",
                        "type",
                        "relation",
                        "collectionId",
                        authors.get("id").asText()))));
    assertEquals(validListRule, posts.get("listRule").asText());

    JsonNode backRule =
        request(
            "PATCH",
            "/api/collections/rule_authors",
            token,
            Map.of("listRule", "rule_posts_via_author.title ?!= ''"));
    assertEquals("rule_posts_via_author.title ?!= ''", backRule.get("listRule").asText());

    HttpResponse<String> invalidNested =
        rawRequest(
            "PATCH",
            "/api/collections/rule_posts",
            token,
            Map.of("listRule", "author.missing = ''"));
    assertEquals(400, invalidNested.statusCode());
    assertTrue(mapper.readTree(invalidNested.body()).get("data").has("listRule"));

    HttpResponse<String> invalidCollection =
        rawRequest(
            "PATCH",
            "/api/collections/rule_posts",
            token,
            Map.of("listRule", "@collection.rule_teams.missing = ''"));
    assertEquals(400, invalidCollection.statusCode());
    assertTrue(mapper.readTree(invalidCollection.body()).get("data").has("listRule"));

    HttpResponse<String> invalidBodyChanged =
        rawRequest(
            "PATCH",
            "/api/collections/rule_posts",
            token,
            Map.of("updateRule", "@request.body.missing:changed = false"));
    assertEquals(400, invalidBodyChanged.statusCode());
    assertTrue(mapper.readTree(invalidBodyChanged.body()).get("data").has("updateRule"));

    JsonNode unchanged = request("GET", "/api/collections/rule_posts", token, null);
    assertEquals(validListRule, unchanged.get("listRule").asText());
    assertEquals("@request.body.author.team.name != ''", unchanged.get("updateRule").asText());

    String importedAuthorsId = "pbc_1200000001";
    String importedPostsId = "pbc_1200000002";
    HttpResponse<String> imported =
        rawRequest(
            "PUT",
            "/api/collections/import",
            token,
            Map.of(
                "collections",
                List.of(
                    Map.of(
                        "id", importedPostsId,
                        "name", "rule_import_posts",
                        "type", "base",
                        "listRule", "author.name != ''",
                        "fields",
                        List.of(
                            Map.of("name", "title", "type", "text"),
                            Map.of(
                                "name", "author",
                                "type", "relation",
                                "collectionId", importedAuthorsId))),
                    Map.of(
                        "id", importedAuthorsId,
                        "name", "rule_import_authors",
                        "type", "base",
                        "listRule", "",
                        "fields", List.of(Map.of("name", "name", "type", "text"))))));
    assertEquals(204, imported.statusCode());
    assertEquals(
        "author.name != ''",
        request("GET", "/api/collections/rule_import_posts", token, null).get("listRule").asText());
  }

  @Test
  void collectionIndexesAndTimestampsPersistWithOfficialValidation() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    HttpResponse<String> invalid =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "invalid_index_posts",
                "fields", List.of(Map.of("name", "title", "type", "text")),
                "indexes", List.of("create index idx_invalid on invalid_index_posts (missing)")));
    assertEquals(400, invalid.statusCode());
    assertEquals(
        "validation_invalid_index_expression",
        mapper.readTree(invalid.body()).get("data").get("indexes").get("0").get("code").asText());
    HttpResponse<String> invalidWhere =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "invalid_partial_index_posts",
                "fields", List.of(Map.of("name", "title", "type", "text")),
                "indexes",
                List.of(
                    "create index idx_invalid_where on invalid_partial_index_posts (title) where missing = 1")));
    assertEquals(400, invalidWhere.statusCode());
    assertEquals(
        404,
        rawRequest("GET", "/api/collections/invalid_partial_index_posts", token, null)
            .statusCode());

    HttpResponse<String> invalidMultiPart =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "invalid_multipart_index_posts",
                "fields", List.of(Map.of("name", "title", "type", "text")),
                "indexes", List.of("create index a.b.c on invalid_multipart_index_posts (title)")));
    assertEquals(400, invalidMultiPart.statusCode());
    assertEquals(
        "validation_invalid_index_expression",
        mapper
            .readTree(invalidMultiPart.body())
            .get("data")
            .get("indexes")
            .get("0")
            .get("code")
            .asText());
    assertEquals(
        404,
        rawRequest("GET", "/api/collections/invalid_multipart_index_posts", token, null)
            .statusCode());

    HttpResponse<String> duplicated =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name",
                "duplicate_index_posts",
                "indexes",
                List.of(
                    "create index idx_duplicate on duplicate_index_posts (created)",
                    "create index idx_duplicate on duplicate_index_posts (updated)")));
    assertEquals(400, duplicated.statusCode());
    assertEquals(
        "validation_duplicated_index_name",
        mapper
            .readTree(duplicated.body())
            .get("data")
            .get("indexes")
            .get("1")
            .get("code")
            .asText());

    JsonNode created =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "indexed_posts",
                "fields",
                List.of(
                    Map.of("name", "title", "type", "text"),
                    Map.of("name", "count", "type", "number")),
                "indexes",
                List.of(
                    "create index idx_indexed_title on anything (title)",
                    "create unique index idx_indexed_count on indexed_posts (count)")));
    String collectionId = created.get("id").asText();
    String createdAt = created.get("created").asText();
    String firstUpdatedAt = created.get("updated").asText();
    assertFalse(createdAt.isBlank());
    assertEquals(createdAt, firstUpdatedAt);
    assertEquals(2, created.get("indexes").size());
    assertTrue(created.get("indexes").get(0).asText().contains("ON `indexed_posts`"));

    HttpResponse<String> reusedName =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name",
                "other_indexed_posts",
                "indexes",
                List.of("create index idx_indexed_title on other_indexed_posts (created)")));
    assertEquals(400, reusedName.statusCode());
    assertEquals(
        "validation_existing_index_name",
        mapper
            .readTree(reusedName.body())
            .get("data")
            .get("indexes")
            .get("0")
            .get("code")
            .asText());

    Thread.sleep(5);
    JsonNode updated =
        request(
            "PATCH",
            "/api/collections/indexed_posts",
            token,
            Map.of(
                "indexes",
                List.of("create index idx_indexed_count_v2 on stale_table (count desc)")));
    assertEquals(createdAt, updated.get("created").asText());
    assertFalse(firstUpdatedAt.equals(updated.get("updated").asText()));
    assertEquals(1, updated.get("indexes").size());
    assertTrue(updated.get("indexes").get(0).asText().contains("ON `indexed_posts`"));

    HttpResponse<String> invalidUpdate =
        rawRequest(
            "PATCH",
            "/api/collections/indexed_posts",
            token,
            Map.of(
                "indexes",
                List.of(
                    "create index idx_broken_update on indexed_posts (count) where missing = 1")));
    assertEquals(400, invalidUpdate.statusCode());
    assertEquals(
        updated.get("indexes"),
        request("GET", "/api/collections/indexed_posts", token, null).get("indexes"));

    HttpResponse<String> invalidMultiPartUpdate =
        rawRequest(
            "PATCH",
            "/api/collections/indexed_posts",
            token,
            Map.of(
                "indexes",
                List.of("create index a.b.c on indexed_posts (title)")));
    assertEquals(400, invalidMultiPartUpdate.statusCode());
    assertEquals(
        "validation_invalid_index_expression",
        mapper
            .readTree(invalidMultiPartUpdate.body())
            .get("data")
            .get("indexes")
            .get("0")
            .get("code")
            .asText());
    assertEquals(
        updated.get("indexes"),
        request("GET", "/api/collections/indexed_posts", token, null).get("indexes"));

    HttpResponse<String> invalidBatchImport =
        rawRequest(
            "PUT",
            "/api/collections/import",
            token,
            Map.of(
                "collections",
                List.of(
                    Map.of(
                        "name", "import_atomic_valid",
                        "type", "base",
                        "fields", List.of(Map.of("name", "title", "type", "text"))),
                    Map.of(
                        "name", "import_atomic_invalid",
                        "type", "base",
                        "fields", List.of(Map.of("name", "title", "type", "text")),
                        "indexes", List.of("create index a.b.c on import_atomic_invalid (title)")))));
    assertEquals(400, invalidBatchImport.statusCode());
    assertEquals(
        404,
        rawRequest("GET", "/api/collections/import_atomic_valid", token, null).statusCode());
    assertEquals(
        404,
        rawRequest("GET", "/api/collections/import_atomic_invalid", token, null).statusCode());

    if (Files.exists(tempDir.resolve("pocketbase.db"))) {
      assertEquals(List.of("idx_indexed_count_v2"), sqliteCustomIndexNames("indexed_posts"));
    }

    JsonNode authCollection =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "indexed_auth_users",
                "type", "auth"));
    assertEquals(2, authCollection.get("indexes").size());
    assertTrue(
        authCollection
            .get("indexes")
            .toString()
            .contains("idx_tokenKey_" + authCollection.get("id").asText()));
    assertTrue(
        authCollection
            .get("indexes")
            .toString()
            .contains("idx_email_" + authCollection.get("id").asText()));
    if (Files.exists(tempDir.resolve("pocketbase.db"))) {
      assertEquals(
          List.of(
              "idx_email_" + authCollection.get("id").asText(),
              "idx_tokenKey_" + authCollection.get("id").asText()),
          sqliteCustomIndexNames("indexed_auth_users"));
    }

    server.close();
    start();
    token = loginToken();
    JsonNode restarted = request("GET", "/api/collections/" + collectionId, token, null);
    assertEquals(createdAt, restarted.get("created").asText());
    assertEquals(updated.get("updated").asText(), restarted.get("updated").asText());
    assertEquals(updated.get("indexes"), restarted.get("indexes"));
    assertEquals(
        2,
        request("GET", "/api/collections/" + authCollection.get("id").asText(), token, null)
            .get("indexes")
            .size());

    JsonNode selected =
        request(
            "GET",
            "/api/collections/" + collectionId + "?fields=id,indexes,created,updated",
            token,
            null);
    assertTrue(selected.has("indexes"));
    assertTrue(selected.has("created"));
    assertTrue(selected.has("updated"));
    assertFalse(selected.has("name"));
  }

  @Test
  void generatedCollectionAndFieldIdsMatchOfficialChecksumsAndRemainStable() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    JsonNode created =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "checksum_posts",
                "type", "base",
                "fields",
                List.of(
                    Map.of("name", "title", "type", "text"),
                    Map.of("name", "count", "type", "number"))));
    assertEquals("pbc_1988362547", created.get("id").asText());
    assertEquals(List.of("id", "title", "count"), fieldNames(created));
    assertEquals("text3208210256", created.get("fields").get(0).get("id").asText());
    assertEquals("text724990059", created.get("fields").get(1).get("id").asText());
    assertEquals("number2245608546", created.get("fields").get(2).get("id").asText());

    JsonNode renamed =
        request(
            "PATCH",
            "/api/collections/checksum_posts",
            token,
            Map.of(
                "name",
                "checksum_posts_renamed",
                "fields",
                List.of(
                    Map.of(
                        "id",
                        created.get("fields").get(0).get("id").asText(),
                        "name",
                        "id",
                        "type",
                        "text",
                        "required",
                        true,
                        "system",
                        true),
                    Map.of(
                        "id", created.get("fields").get(1).get("id").asText(),
                        "name", "title",
                        "type", "text"),
                    Map.of("name", "published", "type", "bool"))));
    assertEquals("pbc_1988362547", renamed.get("id").asText());
    assertEquals("text724990059", renamed.get("fields").get(1).get("id").asText());
    assertEquals("bool1748787223", renamed.get("fields").get(2).get("id").asText());

    JsonNode explicit =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "id", "custom_collection_id",
                "name", "explicit_ids",
                "fields",
                List.of(
                    Map.of(
                        "id", "custom_field_id",
                        "name", "title",
                        "type", "text"))));
    assertEquals("custom_collection_id", explicit.get("id").asText());
    assertEquals("custom_field_id", explicit.get("fields").get(1).get("id").asText());

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "id", "pbc_1702033289",
            "name", "checksum_collision_blocker"));
    JsonNode collision =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "checksum_users",
                "type", "auth"));
    assertEquals("pbc_17020332892", collision.get("id").asText());
    assertTrue(collision.get("indexes").toString().contains("pbc_17020332892"));

    assertEquals(
        204,
        rawRequest(
            "PUT",
            "/api/collections/import",
            token,
            Map.of(
                "collections",
                List.of(
                    Map.of(
                        "name", "checksum_import",
                        "type", "base",
                        "fields", List.of(Map.of("name", "newField", "type", "text"))))))
            .statusCode());
    JsonNode imported = request("GET", "/api/collections/checksum_import", token, null);
    assertEquals("pbc_2131258802", imported.get("id").asText());
    assertEquals(List.of("id", "newField"), fieldNames(imported));
    assertEquals("text872197786", imported.get("fields").get(1).get("id").asText());

    server.close();
    start();
    token = loginToken();
    JsonNode restarted = request("GET", "/api/collections/checksum_posts_renamed", token, null);
    assertEquals("pbc_1988362547", restarted.get("id").asText());
    assertEquals("text724990059", restarted.get("fields").get(1).get("id").asText());
    assertEquals("bool1748787223", restarted.get("fields").get(2).get("id").asText());
  }

  @Test
  void legacyRelationalCollectionMetadataColumnsUpgradeOnRestart() throws Exception {
    if (!"sqlite".equals(System.getProperty("storage"))) {
      return;
    }
    start();
    bootstrapSuperuser();
    String token = loginToken();
    JsonNode collection =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "legacy_index_metadata",
                "fields", List.of(Map.of("name", "title", "type", "text")),
                "indexes",
                List.of("create index idx_legacy_title on legacy_index_metadata (title)")));
    server.close();

    try (var connection =
        java.sql.DriverManager.getConnection(
            "jdbc:sqlite:" + tempDir.resolve("pocketbase.db").toAbsolutePath());
        var statement = connection.createStatement()) {
      statement.execute("ALTER TABLE _collections DROP COLUMN indexes");
      statement.execute("ALTER TABLE _collections DROP COLUMN created");
      statement.execute("ALTER TABLE _collections DROP COLUMN updated");
    }

    start();
    token = loginToken();
    JsonNode migrated =
        request("GET", "/api/collections/" + collection.get("id").asText(), token, null);
    assertFalse(migrated.get("created").asText().isBlank());
    assertFalse(migrated.get("updated").asText().isBlank());
    assertEquals(1, migrated.get("indexes").size());
    assertTrue(migrated.get("indexes").get(0).asText().contains("idx_legacy_title"));
    assertEquals(List.of("idx_legacy_title"), sqliteCustomIndexNames("legacy_index_metadata"));
  }

  @Test
  void collectionMetaApisReturnScaffoldsAndOAuth2Providers() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    assertEquals(
        401, rawRequest("GET", "/api/collections/meta/scaffolds", null, null).statusCode());
    assertEquals(
        401, rawRequest("GET", "/api/collections/meta/oauth2-providers", null, null).statusCode());

    JsonNode scaffolds = request("GET", "/api/collections/meta/scaffolds", token, null);
    assertEquals("base", scaffolds.get("base").get("type").asText());
    assertEquals("auth", scaffolds.get("auth").get("type").asText());
    assertEquals("view", scaffolds.get("view").get("type").asText());
    assertAuthSystemFields(scaffolds.get("auth"));
    assertTrue(scaffolds.get("view").has("viewQuery"));

    JsonNode providers = request("GET", "/api/collections/meta/oauth2-providers", token, null);
    List<String> names = providerNames(providers);
    assertEquals(32, providers.size());
    assertEquals("apple", providers.get(0).get("name").asText());
    assertTrue(
        names.containsAll(
            List.of(
                "apple", "github", "google", "instagram2", "microsoft", "oidc", "oidc2", "oidc3")));
    assertFalse(names.contains("instagram"));
    assertTrue(providers.get(0).has("displayName"));
    assertTrue(providers.get(0).get("logo").asText().startsWith("<svg"));
  }

  @Test
  void authSystemFieldsAreProtectedAndLegacyCollectionsUpgradeOnRestart() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    JsonNode superusers = request("GET", "/api/collections/_superusers", token, null);
    assertAuthSystemFields(superusers);
    JsonNode superuserAuth =
        request(
            "POST",
            "/api/collections/_superusers/auth-with-password",
            null,
            Map.of(
                "identity", "root@example.com",
                "password", "Secret_123"));
    assertFalse(superuserAuth.get("record").get("emailVisibility").asBoolean());
    assertTrue(superuserAuth.get("record").get("verified").asBoolean());

    JsonNode collection =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "legacy_auth_fields",
                "type", "auth",
                "fields", List.of(Map.of("name", "displayName", "type", "text"))));
    assertAuthSystemFields(collection);
    assertEquals("displayName", collection.get("fields").get(6).get("name").asText());

    JsonNode record =
        request(
            "POST",
            "/api/collections/legacy_auth_fields/records",
            token,
            Map.of(
                "email", "legacy@example.com",
                "password", "Secret_456",
                "passwordConfirm", "Secret_456",
                "displayName", "Legacy"));
    assertFalse(record.get("emailVisibility").asBoolean());
    assertFalse(record.get("verified").asBoolean());

    server.close();
    downgradeAuthSystemFieldsFixture("legacy_auth_fields", collection.get("id").asText());
    start();
    token = loginToken();

    JsonNode migrated = request("GET", "/api/collections/legacy_auth_fields", token, null);
    assertAuthSystemFields(migrated);
    assertEquals("displayName", migrated.get("fields").get(6).get("name").asText());

    JsonNode migratedRecord =
        request(
            "GET",
            "/api/collections/legacy_auth_fields/records/" + record.get("id").asText(),
            token,
            null);
    assertFalse(migratedRecord.get("emailVisibility").asBoolean());
    assertFalse(migratedRecord.get("verified").asBoolean());

    JsonNode updated =
        request(
            "PATCH",
            "/api/collections/legacy_auth_fields",
            token,
            Map.of("fields", migrated.get("fields")));
    assertAuthSystemFields(updated);
  }

  @Test
  void collectionReservedAndSystemFieldsMatchOfficialValidation() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    HttpResponse<String> reservedCreate =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name",
                "reserved_schema_fields",
                "fields",
                List.of(
                    Map.of("name", "expand", "type", "text"),
                    Map.of("name", "collectionId", "type", "text"),
                    Map.of("name", "collectionName", "type", "text"))));
    assertEquals(400, reservedCreate.statusCode());
    JsonNode reservedBody = mapper.readTree(reservedCreate.body());
    assertEquals("Failed to create collection.", reservedBody.get("message").asText());
    JsonNode reservedErrors = reservedBody.get("data").get("fields");
    assertEquals(
        "validation_not_in_invalid", reservedErrors.get("1").get("name").get("code").asText());
    assertEquals(
        "validation_not_in_invalid", reservedErrors.get("2").get("name").get("code").asText());
    assertEquals(
        "validation_not_in_invalid", reservedErrors.get("3").get("name").get("code").asText());

    JsonNode created =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name",
                "protected_schema_fields",
                "fields",
                List.of(Map.of("name", "title", "type", "text"))));

    HttpResponse<String> removedSystemField =
        rawRequest(
            "PATCH",
            "/api/collections/protected_schema_fields",
            token,
            Map.of(
                "fields",
                List.of(
                    Map.of(
                        "id", created.get("fields").get(1).get("id").asText(),
                        "name", "title",
                        "type", "text"))));
    assertEquals(400, removedSystemField.statusCode());
    assertFieldError(
        removedSystemField,
        400,
        "Failed to update collection.",
        "fields",
        "validation_system_field_change",
        "System fields cannot be deleted or renamed.");

    ArrayNode renamedFields = created.get("fields").deepCopy();
    ((ObjectNode) renamedFields.get(0)).put("name", "renamed_id");
    HttpResponse<String> renamedSystemField =
        rawRequest(
            "PATCH",
            "/api/collections/protected_schema_fields",
            token,
            Map.of("fields", renamedFields));
    assertEquals(400, renamedSystemField.statusCode());
    assertFieldError(
        renamedSystemField,
        400,
        "Failed to update collection.",
        "fields",
        "validation_system_field_change",
        "System fields cannot be deleted or renamed.");

    ArrayNode validFields = created.get("fields").deepCopy();
    ObjectNode published = mapper.createObjectNode();
    published.put("name", "published");
    published.put("type", "bool");
    validFields.add(published);
    JsonNode updated =
        request(
            "PATCH",
            "/api/collections/protected_schema_fields",
            token,
            Map.of("fields", validFields));
    assertEquals(List.of("id", "title", "published"), fieldNames(updated));

    HttpResponse<String> changedCollectionType =
        rawRequest(
            "PATCH", "/api/collections/protected_schema_fields", token, Map.of("type", "auth"));
    assertEquals(400, changedCollectionType.statusCode());
    assertEquals(
        "validation_collection_type_change",
        mapper.readTree(changedCollectionType.body()).get("data").get("type").get("code").asText());

    HttpResponse<String> changedSystemFlag =
        rawRequest(
            "PATCH", "/api/collections/protected_schema_fields", token, Map.of("system", true));
    assertEquals(400, changedSystemFlag.statusCode());
    assertEquals(
        "validation_collection_system_flag_change",
        mapper.readTree(changedSystemFlag.body()).get("data").get("system").get("code").asText());

    ArrayNode changedFieldTypes = updated.get("fields").deepCopy();
    ((ObjectNode) changedFieldTypes.get(1)).put("type", "number");
    HttpResponse<String> changedFieldType =
        rawRequest(
            "PATCH",
            "/api/collections/protected_schema_fields",
            token,
            Map.of("fields", changedFieldTypes));
    assertEquals(400, changedFieldType.statusCode());
    assertEquals(
        "validation_field_type_change",
        mapper
            .readTree(changedFieldType.body())
            .get("data")
            .get("fields")
            .get("1")
            .get("code")
            .asText());

    HttpResponse<String> changedTypeImport =
        rawRequest(
            "PUT",
            "/api/collections/import",
            token,
            Map.of(
                "collections",
                List.of(
                    Map.of(
                        "id", updated.get("id").asText(),
                        "name", "protected_schema_fields",
                        "type", "auth",
                        "fields", updated.get("fields"),
                        "indexes", updated.get("indexes")))));
    assertEquals(400, changedTypeImport.statusCode());
    assertEquals(
        "validation_collection_type_change",
        mapper.readTree(changedTypeImport.body()).get("data").get("type").get("code").asText());
    JsonNode afterRejectedMetadataChanges =
        request("GET", "/api/collections/protected_schema_fields", token, null);
    assertEquals("base", afterRejectedMetadataChanges.get("type").asText());
    assertEquals("text", afterRejectedMetadataChanges.get("fields").get(1).get("type").asText());

    ArrayNode reservedUpdateFields = updated.get("fields").deepCopy();
    ObjectNode expand = mapper.createObjectNode();
    expand.put("name", "expand");
    expand.put("type", "text");
    reservedUpdateFields.add(expand);
    HttpResponse<String> reservedUpdate =
        rawRequest(
            "PATCH",
            "/api/collections/protected_schema_fields",
            token,
            Map.of("fields", reservedUpdateFields));
    assertEquals(400, reservedUpdate.statusCode());
    assertEquals(
        "validation_not_in_invalid",
        mapper
            .readTree(reservedUpdate.body())
            .get("data")
            .get("fields")
            .get("3")
            .get("name")
            .get("code")
            .asText());

    HttpResponse<String> invalidImport =
        rawRequest(
            "PUT",
            "/api/collections/import",
            token,
            Map.of(
                "collections",
                List.of(
                    Map.of(
                        "id",
                        created.get("id").asText(),
                        "name",
                        "protected_schema_fields",
                        "type",
                        "base",
                        "fields",
                        List.of(
                            Map.of(
                                "id", created.get("fields").get(1).get("id").asText(),
                                "name", "title",
                                "type", "text"))))));
    assertEquals(400, invalidImport.statusCode());
    assertFieldError(
        invalidImport,
        400,
        "Failed to import collections.",
        "fields",
        "validation_system_field_change",
        "System fields cannot be deleted or renamed.");
  }

  @Test
  void collectionModelIdentifiersAndDuplicatesMatchOfficialValidation() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    JsonNode original =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name",
                "ModelNames",
                "fields",
                List.of(Map.of("id", "fieldtitle", "name", "Title", "type", "text"))));

    HttpResponse<String> invalidId =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "id", "!invalid",
                "name", "invalid_collection_id"));
    assertEquals(400, invalidId.statusCode());
    assertEquals(
        "validation_match_invalid",
        mapper.readTree(invalidId.body()).get("data").get("id").get("code").asText());

    HttpResponse<String> duplicateId =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of("id", original.get("id").asText(), "name", "duplicate_collection_id"));
    assertEquals(400, duplicateId.statusCode());
    assertEquals(
        "validation_invalid_or_existing_id",
        mapper.readTree(duplicateId.body()).get("data").get("id").get("code").asText());

    HttpResponse<String> longId =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of("id", "i".repeat(101), "name", "long_collection_id"));
    assertEquals(400, longId.statusCode());
    JsonNode longIdError = mapper.readTree(longId.body()).get("data").get("id");
    assertEquals("validation_length_out_of_range", longIdError.get("code").asText());
    assertEquals(1, longIdError.get("params").get("min").asInt());
    assertEquals(100, longIdError.get("params").get("max").asInt());

    HttpResponse<String> duplicateCase =
        rawRequest("POST", "/api/collections", token, Map.of("name", "modelnames"));
    assertEquals(400, duplicateCase.statusCode());
    assertEquals(
        "validation_collection_name_exists",
        mapper.readTree(duplicateCase.body()).get("data").get("name").get("code").asText());

    HttpResponse<String> collectionIdName =
        rawRequest("POST", "/api/collections", token, Map.of("name", original.get("id").asText()));
    assertEquals(400, collectionIdName.statusCode());
    assertEquals(
        "validation_collection_name_id_duplicate",
        mapper.readTree(collectionIdName.body()).get("data").get("name").get("code").asText());

    HttpResponse<String> internalTable =
        rawRequest("POST", "/api/collections", token, Map.of("name", "_COLLECTIONS"));
    assertEquals(400, internalTable.statusCode());
    assertEquals(
        "validation_collection_name_invalid",
        mapper.readTree(internalTable.body()).get("data").get("name").get("code").asText());

    HttpResponse<String> viaName =
        rawRequest("POST", "/api/collections", token, Map.of("name", "alpha_VIA_beta"));
    assertEquals(400, viaName.statusCode());
    assertEquals(
        "validation_found_via",
        mapper.readTree(viaName.body()).get("data").get("name").get("code").asText());

    HttpResponse<String> invalidFormat =
        rawRequest("POST", "/api/collections", token, Map.of("name", "bad-name"));
    assertEquals(400, invalidFormat.statusCode());
    assertEquals(
        "validation_match_invalid",
        mapper.readTree(invalidFormat.body()).get("data").get("name").get("code").asText());

    HttpResponse<String> longName =
        rawRequest("POST", "/api/collections", token, Map.of("name", "a".repeat(256)));
    assertEquals(400, longName.statusCode());
    JsonNode longNameError = mapper.readTree(longName.body()).get("data").get("name");
    assertEquals("validation_length_out_of_range", longNameError.get("code").asText());
    assertEquals(1, longNameError.get("params").get("min").asInt());
    assertEquals(255, longNameError.get("params").get("max").asInt());

    String validLongCollectionName = "v".repeat(100);
    JsonNode validLongIdentifiers =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name",
                validLongCollectionName,
                "fields",
                List.of(Map.of("id", "numericfield", "name", "1field", "type", "text"))));
    assertEquals(validLongCollectionName, validLongIdentifiers.get("name").asText());
    assertEquals(List.of("id", "1field"), fieldNames(validLongIdentifiers));

    HttpResponse<String> invalidType =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "upper_type",
                "type", "BASE"));
    assertEquals(400, invalidType.statusCode());
    assertEquals(
        "validation_in_invalid",
        mapper.readTree(invalidType.body()).get("data").get("type").get("code").asText());

    HttpResponse<String> duplicateFieldNames =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name",
                "duplicate_field_names",
                "fields",
                List.of(
                    Map.of("id", "fieldone", "name", "Test", "type", "text"),
                    Map.of("id", "fieldtwo", "name", "test", "type", "bool"))));
    assertEquals(400, duplicateFieldNames.statusCode());
    JsonNode duplicateNameError =
        mapper.readTree(duplicateFieldNames.body()).get("data").get("fields").get("2").get("name");
    assertEquals("validation_duplicated_field_name", duplicateNameError.get("code").asText());
    assertEquals("test", duplicateNameError.get("params").get("fieldName").asText());

    JsonNode replacedDuplicateId =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name",
                "duplicate_field_ids",
                "fields",
                List.of(
                    Map.of("id", "samefield", "name", "first", "type", "text"),
                    Map.of("id", "samefield", "name", "second", "type", "bool"))));
    assertEquals(List.of("id", "second"), fieldNames(replacedDuplicateId));

    HttpResponse<String> missingPrimaryKey =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name",
                "missing_primary_key",
                "fields",
                List.of(
                    Map.of(
                        "id", "text3208210256",
                        "name", "title",
                        "type", "text"))));
    assertEquals(400, missingPrimaryKey.statusCode());
    assertEquals(
        "validation_missing_primary_key",
        mapper.readTree(missingPrimaryKey.body()).get("data").get("fields").get("code").asText());

    HttpResponse<String> invalidFieldSettings =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name",
                "invalid_field_settings",
                "fields",
                List.of(
                    Map.of("id", "fieldnull", "name", "null", "type", "text"),
                    Map.of("id", "fieldformat", "name", "bad-name", "type", "text"),
                    Map.of("id", "fieldvia", "name", "a_VIA_b", "type", "text"),
                    Map.of("id", "f".repeat(101), "name", "longId", "type", "text"),
                    Map.of("id", "longname", "name", "n".repeat(101), "type", "text"))));
    assertEquals(400, invalidFieldSettings.statusCode());
    JsonNode fieldErrors = mapper.readTree(invalidFieldSettings.body()).get("data").get("fields");
    assertEquals(
        "validation_not_in_invalid", fieldErrors.get("1").get("name").get("code").asText());
    assertEquals("validation_match_invalid", fieldErrors.get("2").get("name").get("code").asText());
    assertEquals("validation_found_via", fieldErrors.get("3").get("name").get("code").asText());
    assertEquals(
        "validation_length_out_of_range", fieldErrors.get("4").get("id").get("code").asText());
    assertEquals(
        "validation_length_out_of_range", fieldErrors.get("5").get("name").get("code").asText());

    HttpResponse<String> reservedAuthFields =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "reserved_auth_fields",
                "type", "auth",
                "fields",
                List.of(
                    Map.of("id", "oldpassword", "name", "oldPassword", "type", "text"),
                    Map.of(
                        "id", "passwordconfirm", "name", "passwordConfirm", "type", "text"))));
    assertEquals(400, reservedAuthFields.statusCode());
    JsonNode authFieldErrors = mapper.readTree(reservedAuthFields.body()).get("data").get("fields");
    assertEquals(
        "validation_reserved_field_name",
        authFieldErrors.get("6").get("name").get("code").asText());
    assertEquals(
        "validation_reserved_field_name",
        authFieldErrors.get("7").get("name").get("code").asText());

    request("POST", "/api/collections", token, Map.of("name", "model_names_target"));
    HttpResponse<String> aggregateUpdate =
        rawRequest(
            "PATCH",
            "/api/collections/ModelNames",
            token,
            Map.of("name", "model_names_target", "type", "auth"));
    assertEquals(400, aggregateUpdate.statusCode());
    JsonNode aggregateErrors = mapper.readTree(aggregateUpdate.body()).get("data");
    assertEquals(
        "validation_collection_name_exists", aggregateErrors.get("name").get("code").asText());
    assertEquals(
        "validation_collection_type_change", aggregateErrors.get("type").get("code").asText());
    JsonNode unchanged = request("GET", "/api/collections/ModelNames", token, null);
    assertEquals("base", unchanged.get("type").asText());
    assertEquals("ModelNames", unchanged.get("name").asText());

    JsonNode caseRenamed =
        request("PATCH", "/api/collections/ModelNames", token, Map.of("name", "MODELNAMES"));
    assertEquals("MODELNAMES", caseRenamed.get("name").asText());

    HttpResponse<String> invalidImport =
        rawRequest(
            "PUT",
            "/api/collections/import",
            token,
            Map.of("collections", List.of(Map.of("name", "invalid_via_import"))));
    assertEquals(400, invalidImport.statusCode());
    assertEquals(
        "validation_found_via",
        mapper.readTree(invalidImport.body()).get("data").get("name").get("code").asText());
    assertEquals(
        404, rawRequest("GET", "/api/collections/invalid_via_import", token, null).statusCode());
  }

  @Test
  void superuserRecordsCrudMatchesOfficialSdkFlow() throws Exception {
    start();
    bootstrapSuperuser();
    String rootToken = loginToken();

    assertEquals(
        403, rawRequest("GET", "/api/collections/_superusers/records", null, null).statusCode());
    assertEquals(
        403,
        rawRequest("GET", "/api/collections/_superusers/records/missing", null, null).statusCode());

    request(
        "POST",
        "/api/collections",
        rootToken,
        Map.of(
            "name", "regular_auth_users",
            "type", "auth"));
    request(
        "POST",
        "/api/collections/regular_auth_users/records",
        rootToken,
        Map.of(
            "email", "regular@example.com",
            "password", "Secret_456",
            "passwordConfirm", "Secret_456"));
    String regularToken =
        request(
            "POST",
            "/api/collections/regular_auth_users/auth-with-password",
            null,
            Map.of(
                "identity", "regular@example.com",
                "password", "Secret_456"))
            .get("token")
            .asText();
    assertEquals(
        403,
        rawRequest("GET", "/api/collections/_superusers/records", regularToken, null).statusCode());

    JsonNode second =
        request(
            "POST",
            "/api/collections/_superusers/records",
            rootToken,
            Map.of(
                "email", "second-root@example.com",
                "password", "Secret_456",
                "passwordConfirm", "Secret_456",
                "verified", false));
    String secondId = second.get("id").asText();
    assertTrue(second.get("verified").asBoolean());
    assertFalse(second.get("emailVisibility").asBoolean());
    assertFalse(second.has("password"));
    assertFalse(second.has("tokenKey"));

    JsonNode page = request("GET", "/api/collections/_superusers/records", rootToken, null);
    assertEquals(30, page.get("perPage").asInt());
    assertEquals(2, page.get("totalItems").asInt());
    assertEquals(
        secondId,
        request("GET", "/api/collections/_superusers/records/" + secondId, rootToken, null)
            .get("id")
            .asText());

    String secondToken =
        request(
            "POST",
            "/api/collections/_superusers/auth-with-password",
            null,
            Map.of(
                "identity", "second-root@example.com",
                "password", "Secret_456"))
            .get("token")
            .asText();
    JsonNode renamed =
        request(
            "PATCH",
            "/api/collections/_superusers/records/" + secondId,
            rootToken,
            Map.of("email", "renamed-root@example.com", "verified", false));
    assertEquals("renamed-root@example.com", renamed.get("email").asText());
    assertTrue(renamed.get("verified").asBoolean());
    assertEquals(
        401,
        rawRequest("POST", "/api/collections/_superusers/auth-refresh", secondToken, null)
            .statusCode());

    String renamedToken =
        request(
            "POST",
            "/api/collections/_superusers/auth-with-password",
            null,
            Map.of(
                "identity", "renamed-root@example.com",
                "password", "Secret_456"))
            .get("token")
            .asText();
    request(
        "PATCH",
        "/api/collections/_superusers/records/" + secondId,
        rootToken,
        Map.of(
            "password", "Changed_456",
            "passwordConfirm", "Changed_456"));
    assertEquals(
        401,
        rawRequest("POST", "/api/collections/_superusers/auth-refresh", renamedToken, null)
            .statusCode());
    assertTrue(
        request(
            "POST",
            "/api/collections/_superusers/auth-with-password",
            null,
            Map.of(
                "identity", "renamed-root@example.com",
                "password", "Changed_456"))
            .hasNonNull("token"));

    assertEquals(
        204,
        rawRequest("DELETE", "/api/collections/_superusers/records/" + secondId, rootToken, null)
            .statusCode());
    String rootId =
        request("POST", "/api/collections/_superusers/auth-refresh", rootToken, null)
            .get("record")
            .get("id")
            .asText();
    HttpResponse<String> deleteLast =
        rawRequest("DELETE", "/api/collections/_superusers/records/" + rootId, rootToken, null);
    assertEquals(400, deleteLast.statusCode());
    assertErrorEnvelope(deleteLast, 400, "You can't delete the only existing superuser.");
  }

  @Test
  void authSupportCollectionsCrudMatchesOfficialOwnershipRules() throws Exception {
    start();
    bootstrapSuperuser();
    String rootToken = loginToken();

    JsonNode authCollection =
        request(
            "POST",
            "/api/collections",
            rootToken,
            Map.of(
                "name", "system_record_users",
                "type", "auth"));
    String authCollectionId = authCollection.get("id").asText();
    JsonNode owner =
        request(
            "POST",
            "/api/collections/system_record_users/records",
            rootToken,
            Map.of(
                "email", "system-owner@example.com",
                "password", "Secret_456",
                "passwordConfirm", "Secret_456"));
    String ownerId = owner.get("id").asText();
    String ownerToken =
        request(
            "POST",
            "/api/collections/system_record_users/auth-with-password",
            null,
            Map.of(
                "identity", "system-owner@example.com",
                "password", "Secret_456"))
            .get("token")
            .asText();

    Map<String, Map<String, Object>> payloads =
        Map.of(
            "_authOrigins",
            Map.of(
                "collectionRef", authCollectionId,
                "recordRef", ownerId,
                "fingerprint", "browser-fingerprint"),
            "_externalAuths",
            Map.of(
                "collectionRef",
                authCollectionId,
                "recordRef",
                ownerId,
                "provider",
                "github",
                "providerId",
                "provider-user-1"),
            "_mfas",
            Map.of(
                "collectionRef", authCollectionId,
                "recordRef", ownerId,
                "method", "password"),
            "_otps",
            Map.of(
                "collectionRef",
                authCollectionId,
                "recordRef",
                ownerId,
                "password",
                "Pass_1234",
                "sentTo",
                "system-owner@example.com"));

    for (Map.Entry<String, Map<String, Object>> entry : payloads.entrySet()) {
      String collection = entry.getKey();
      String recordsPath = "/api/collections/" + collection + "/records";

      JsonNode systemSchema = request("GET", "/api/collections/" + collection, rootToken, null);
      assertTrue(systemSchema.get("system").asBoolean());
      assertEquals(200, rawRequest("GET", recordsPath, null, null).statusCode());
      assertEquals(0, request("GET", recordsPath, null, null).get("totalItems").asInt());
      assertEquals(403, rawRequest("POST", recordsPath, null, entry.getValue()).statusCode());
      assertEquals(403, rawRequest("POST", recordsPath, ownerToken, entry.getValue()).statusCode());

      JsonNode created = request("POST", recordsPath, rootToken, entry.getValue());
      String id = created.get("id").asText();
      assertEquals(ownerId, created.get("recordRef").asText());
      assertEquals(authCollectionId, created.get("collectionRef").asText());
      assertFalse(created.has("password"));

      String idFilter = URLEncoder.encode("id = '" + id + "'", StandardCharsets.UTF_8);
      JsonNode ownerPage = request("GET", recordsPath + "?filter=" + idFilter, ownerToken, null);
      assertEquals(30, ownerPage.get("perPage").asInt());
      assertEquals(1, ownerPage.get("totalItems").asInt());
      assertEquals(id, ownerPage.get("items").get(0).get("id").asText());
      assertEquals(404, rawRequest("GET", recordsPath + "/" + id, null, null).statusCode());
      assertEquals(id, request("GET", recordsPath + "/" + id, ownerToken, null).get("id").asText());
      assertEquals(
          403,
          rawRequest("PATCH", recordsPath + "/" + id, ownerToken, entry.getValue()).statusCode());

      if ("_authOrigins".equals(collection) || "_externalAuths".equals(collection)) {
        assertEquals(
            204, rawRequest("DELETE", recordsPath + "/" + id, ownerToken, null).statusCode());
      } else {
        assertEquals(
            403, rawRequest("DELETE", recordsPath + "/" + id, ownerToken, null).statusCode());
        assertEquals(
            204, rawRequest("DELETE", recordsPath + "/" + id, rootToken, null).statusCode());
      }
    }

    assertEquals(
        400,
        rawRequest(
            "POST",
            "/api/collections/_mfas/records",
            rootToken,
            Map.of(
                "collectionRef", "missing_collection",
                "recordRef", ownerId,
                "method", "password"))
            .statusCode());
    assertEquals(
        400,
        rawRequest(
            "POST",
            "/api/collections/_mfas/records",
            rootToken,
            Map.of(
                "collectionRef", authCollectionId,
                "recordRef", "missing_record",
                "method", "password"))
            .statusCode());

    Map<String, String> persistedIds = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, Object>> entry : payloads.entrySet()) {
      Map<String, Object> persistentPayload = new LinkedHashMap<>(entry.getValue());
      if ("_authOrigins".equals(entry.getKey())) {
        persistentPayload.put("fingerprint", "persistent-fingerprint");
      }
      if ("_externalAuths".equals(entry.getKey())) {
        persistentPayload.put("providerId", "persistent-provider-user");
      }
      JsonNode created =
          request(
              "POST",
              "/api/collections/" + entry.getKey() + "/records",
              rootToken,
              persistentPayload);
      persistedIds.put(entry.getKey(), created.get("id").asText());
      if ("_authOrigins".equals(entry.getKey()) || "_externalAuths".equals(entry.getKey())) {
        assertEquals(
            400,
            rawRequest(
                "POST",
                "/api/collections/" + entry.getKey() + "/records",
                rootToken,
                persistentPayload)
                .statusCode());
      }
    }

    server.close();
    start();
    rootToken = loginToken();
    for (Map.Entry<String, String> entry : persistedIds.entrySet()) {
      assertEquals(
          entry.getValue(),
          request(
              "GET",
              "/api/collections/" + entry.getKey() + "/records/" + entry.getValue(),
              rootToken,
              null)
              .get("id")
              .asText());
    }

    assertEquals(
        204,
        rawRequest(
            "DELETE",
            "/api/collections/system_record_users/records/" + ownerId,
            rootToken,
            null)
            .statusCode());
    for (Map.Entry<String, String> entry : persistedIds.entrySet()) {
      assertEquals(
          404,
          rawRequest(
              "GET",
              "/api/collections/" + entry.getKey() + "/records/" + entry.getValue(),
              rootToken,
              null)
              .statusCode());
    }
  }

  @Test
  void legacySystemCollectionIdsMigrateToOfficialIdsWithoutDataLoss() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();
    JsonNode auth = request("POST", "/api/collections/_superusers/auth-refresh", token, null);
    String rootId = auth.get("record").get("id").asText();

    JsonNode relationCollection =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name",
                "system_id_links",
                "fields",
                List.of(
                    Map.of(
                        "name", "owner",
                        "type", "relation",
                        "collectionId", SystemCollections.SUPERUSERS_ID))));
    JsonNode origin =
        request(
            "POST",
            "/api/collections/_authOrigins/records",
            token,
            Map.of(
                "collectionRef",
                SystemCollections.SUPERUSERS_ID,
                "recordRef",
                rootId,
                "fingerprint",
                "legacy-system-id-fixture"));

    Path officialStorage =
        tempDir.resolve("storage").resolve(SystemCollections.SUPERUSERS_ID).resolve(rootId);
    Files.createDirectories(officialStorage);
    Files.writeString(officialStorage.resolve("legacy.txt"), "preserved", StandardCharsets.UTF_8);

    server.close();
    downgradeSystemCollectionIdsFixture();
    start();
    token = loginToken();

    Map<String, String> officialIds =
        Map.of(
            SystemCollections.SUPERUSERS, SystemCollections.SUPERUSERS_ID,
            SystemCollections.MFAS, SystemCollections.MFAS_ID,
            SystemCollections.OTPS, SystemCollections.OTPS_ID,
            SystemCollections.EXTERNAL_AUTHS, SystemCollections.EXTERNAL_AUTHS_ID,
            SystemCollections.AUTH_ORIGINS, SystemCollections.AUTH_ORIGINS_ID);
    for (Map.Entry<String, String> entry : officialIds.entrySet()) {
      assertEquals(
          entry.getValue(),
          request("GET", "/api/collections/" + entry.getKey(), token, null).get("id").asText());
    }

    JsonNode refreshed = request("POST", "/api/collections/_superusers/auth-refresh", token, null);
    assertEquals(
        SystemCollections.SUPERUSERS_ID, refreshed.get("record").get("collectionId").asText());
    assertEquals(rootId, refreshed.get("record").get("id").asText());
    JsonNode legacyAliasAuth =
        request(
            "POST",
            "/api/collections/" + SystemCollections.LEGACY_SUPERUSERS_ID + "/auth-with-password",
            null,
            Map.of("identity", "root@example.com", "password", "Secret_123"));
    assertEquals(
        SystemCollections.SUPERUSERS_ID,
        legacyAliasAuth.get("record").get("collectionId").asText());
    assertEquals(
        SystemCollections.SUPERUSERS_ID,
        request(
            "POST",
            "/api/collections/" + SystemCollections.LEGACY_SUPERUSERS_ID + "/auth-refresh",
            legacyAliasAuth.get("token").asText(),
            null)
            .get("record")
            .get("collectionId")
            .asText());

    JsonNode migratedOrigin =
        request(
            "GET",
            "/api/collections/_authOrigins/records/" + origin.get("id").asText(),
            token,
            null);
    assertEquals(SystemCollections.SUPERUSERS_ID, migratedOrigin.get("collectionRef").asText());

    JsonNode migratedRelation =
        request("GET", "/api/collections/" + relationCollection.get("id").asText(), token, null);
    JsonNode ownerField = null;
    for (JsonNode field : migratedRelation.get("fields")) {
      if ("owner".equals(field.path("name").asText())) {
        ownerField = field;
        break;
      }
    }
    assertNotNull(ownerField);
    assertEquals(SystemCollections.SUPERUSERS_ID, ownerField.get("collectionId").asText());

    JsonNode superusers = request("GET", "/api/collections/_superusers", token, null);
    assertFalse(fieldNames(superusers).contains("name"));
    assertTrue(fieldNames(superusers).containsAll(List.of("created", "updated")));
    assertEquals(86_400L, superusers.get("authToken").get("duration").asLong());
    assertEquals("text3208210256", superusers.get("fields").get(0).get("id").asText());

    assertEquals(
        "preserved",
        Files.readString(
            tempDir
                .resolve("storage")
                .resolve(SystemCollections.SUPERUSERS_ID)
                .resolve(rootId)
                .resolve("legacy.txt"),
            StandardCharsets.UTF_8));
    assertFalse(
        Files.exists(tempDir.resolve("storage").resolve(SystemCollections.LEGACY_SUPERUSERS_ID)));
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
    assertFieldError(
        badJson,
        400,
        "Failed to read request body.",
        "body",
        "validation_invalid_value",
        "Invalid JSON payload.");

    HttpResponse<String> settingsArray = rawJsonRequest("PATCH", "/api/settings", token, "[]");
    assertEquals(400, settingsArray.statusCode());
    assertFieldError(
        settingsArray,
        400,
        "Settings payload must be a JSON object.",
        "body",
        "validation_invalid_value",
        "Request body must be a JSON object.");

    HttpResponse<String> collectionArray = rawJsonRequest("POST", "/api/collections", token, "[]");
    assertEquals(400, collectionArray.statusCode());
    assertFieldError(
        collectionArray,
        400,
        "Collection payload must be a JSON object.",
        "body",
        "validation_invalid_value",
        "Request body must be a JSON object.");

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name",
            "payload_posts",
            "fields",
            List.of(Map.of("name", "title", "type", "text", "required", true))));
    HttpResponse<String> recordArray =
        rawJsonRequest("POST", "/api/collections/payload_posts/records", token, "[]");
    assertEquals(400, recordArray.statusCode());
    assertFieldError(
        recordArray,
        400,
        "Record payload must be a JSON object.",
        "body",
        "validation_invalid_value",
        "Request body must be a JSON object.");

    request(
        "POST", "/api/collections/payload_posts/records", token, Map.of("title", "filter target"));
    HttpResponse<String> recordFilter =
        rawRequest(
            "GET",
            "/api/collections/payload_posts/records?filter="
                + URLEncoder.encode("title #", StandardCharsets.UTF_8),
            token,
            null);
    assertEquals(400, recordFilter.statusCode());
    assertFieldErrorMessageStartsWith(
        recordFilter,
        400,
        "Invalid filter.",
        "filter",
        "validation_invalid_value",
        "Invalid filter");

    HttpResponse<String> dryRunArray =
        rawJsonRequest("POST", "/api/collections/meta/dry-run-view", token, "[]");
    assertEquals(400, dryRunArray.statusCode());
    assertFieldError(
        dryRunArray,
        400,
        "An error occurred while loading the submitted data.",
        "body",
        "validation_invalid_value",
        "Request body must be a JSON object.");
  }

  @Test
  void healthDiagnosticsAreVisibleOnlyToSuperusers() throws Exception {
    start();

    HttpResponse<String> guestResponse = rawRequest("GET", "/api/health", null, null);
    assertEquals(200, guestResponse.statusCode());
    JsonNode guest = mapper.readTree(guestResponse.body());
    assertEquals(200, guest.get("code").asInt());
    assertEquals("API is healthy.", guest.get("message").asText());
    assertTrue(guest.get("data").isObject());
    assertTrue(guest.get("data").isEmpty());
    assertFalse(guestResponse.body().contains("dataDir"));
    assertFalse(guestResponse.body().contains("superuserReady"));
    HttpResponse<String> head = rawRequest("HEAD", "/api/health", null, null);
    assertEquals(200, head.statusCode());
    assertTrue(head.body().isEmpty());
    assertTrue(request("GET", "/api/bootstrap/superuser", null, null).get("required").asBoolean());

    bootstrapSuperuser();
    assertFalse(request("GET", "/api/bootstrap/superuser", null, null).get("required").asBoolean());
    String rootToken = loginToken();
    request(
        "POST",
        "/api/collections",
        rootToken,
        Map.of(
            "name", "health_users",
            "type", "auth"));
    request(
        "POST",
        "/api/collections/health_users/records",
        rootToken,
        Map.of(
            "email", "health-user@example.com",
            "password", "Secret_456",
            "passwordConfirm", "Secret_456"));
    String userToken =
        request(
            "POST",
            "/api/collections/health_users/auth-with-password",
            null,
            Map.of("identity", "health-user@example.com", "password", "Secret_456"))
            .get("token")
            .asText();

    JsonNode regular = mapper.readTree(rawRequest("GET", "/api/health", userToken, null).body());
    assertTrue(regular.get("data").isObject());
    assertTrue(regular.get("data").isEmpty());

    request(
        "PATCH",
        "/api/settings",
        rootToken,
        Map.of(
            "trustedProxy", Map.of("headers", List.of("X-Health-Real-IP"), "useLeftmostIP", true)));
    JsonNode superuser =
        mapper.readTree(
            rawRequest(
                "GET",
                "/api/health",
                rootToken,
                null,
                Map.of(
                    "X-Health-Real-IP", "invalid, 198.51.100.25",
                    "CF-Connecting-IP", "203.0.113.10"))
                .body());
    assertTrue(superuser.get("data").get("canBackup").asBoolean());
    assertEquals("198.51.100.25", superuser.get("data").get("realIP").asText());
    assertEquals("X-Health-Real-IP", superuser.get("data").get("possibleProxyHeader").asText());
    assertFalse(superuser.get("data").has("dataDir"));
    assertFalse(superuser.get("data").has("superuserReady"));

    request(
        "PATCH",
        "/api/settings",
        rootToken,
        Map.of("trustedProxy", Map.of("headers", List.of(), "useLeftmostIP", false)));
    JsonNode commonProxy =
        mapper.readTree(
            rawRequest(
                "GET",
                "/api/health",
                rootToken,
                null,
                Map.of("CF-Connecting-IP", "203.0.113.11"))
                .body());
    assertEquals("127.0.0.1", commonProxy.get("data").get("realIP").asText());
    assertEquals("CF-Connecting-IP", commonProxy.get("data").get("possibleProxyHeader").asText());
  }

  @Test
  void rateLimitsAndBodyLimitMatchOfficialMiddlewareRules() throws Exception {
    start();
    bootstrapSuperuser();
    String rootToken = loginToken();

    request(
        "POST",
        "/api/collections",
        rootToken,
        Map.of(
            "name", "rate_limit_users",
            "type", "auth"));
    request(
        "POST",
        "/api/collections/rate_limit_users/records",
        rootToken,
        Map.of(
            "email", "limited@example.com",
            "password", "Secret_456",
            "passwordConfirm", "Secret_456"));
    String userToken =
        request(
            "POST",
            "/api/collections/rate_limit_users/auth-with-password",
            null,
            Map.of("identity", "limited@example.com", "password", "Secret_456"))
            .get("token")
            .asText();

    HttpResponse<String> invalid =
        rawRequest(
            "PATCH",
            "/api/settings",
            rootToken,
            Map.of("rateLimits", Map.of("enabled", true, "rules", List.of())));
    assertEquals(400, invalid.statusCode());
    assertFalse(
        request("GET", "/api/settings", rootToken, null)
            .get("rateLimits")
            .get("enabled")
            .asBoolean());

    request(
        "PATCH",
        "/api/settings",
        rootToken,
        Map.of(
            "rateLimits",
            Map.of(
                "enabled", true,
                "rules",
                List.of(
                    Map.of(
                        "label",
                        "/api/health",
                        "audience",
                        "@guest",
                        "duration",
                        1,
                        "maxRequests",
                        1)),
                "excludedIPs", List.of()),
            "trustedProxy", Map.of("headers", List.of(), "useLeftmostIP", false)));
    assertEquals(200, rawRequest("GET", "/api/health", null, null).statusCode());
    HttpResponse<String> guestLimited = rawRequest("GET", "/api/health", null, null);
    assertEquals(429, guestLimited.statusCode());
    assertEquals("1", guestLimited.headers().firstValue("Retry-After").orElse(""));
    Thread.sleep(1100);
    assertEquals(200, rawRequest("GET", "/api/health", null, null).statusCode());
    assertEquals(200, rawRequest("GET", "/api/health", userToken, null).statusCode());
    assertEquals(200, rawRequest("GET", "/api/health", userToken, null).statusCode());

    request(
        "PATCH",
        "/api/settings",
        rootToken,
        Map.of(
            "rateLimits",
            Map.of(
                "enabled", true,
                "rules",
                List.of(
                    Map.of(
                        "label",
                        "/api/health",
                        "audience",
                        "@auth",
                        "duration",
                        60,
                        "maxRequests",
                        1)),
                "excludedIPs", List.of())));
    assertEquals(200, rawRequest("GET", "/api/health", null, null).statusCode());
    assertEquals(200, rawRequest("GET", "/api/health", null, null).statusCode());
    assertEquals(200, rawRequest("GET", "/api/health", userToken, null).statusCode());
    assertEquals(429, rawRequest("GET", "/api/health", userToken, null).statusCode());
    assertEquals(200, rawRequest("GET", "/api/health", rootToken, null).statusCode());
    assertEquals(200, rawRequest("GET", "/api/health", rootToken, null).statusCode());

    request(
        "PATCH",
        "/api/settings",
        rootToken,
        Map.of("rateLimits", Map.of("excludedIPs", List.of("127.0.0.1"))));
    assertEquals(200, rawRequest("GET", "/api/health", userToken, null).statusCode());
    assertEquals(200, rawRequest("GET", "/api/health", userToken, null).statusCode());

    request(
        "PATCH",
        "/api/settings",
        rootToken,
        Map.of(
            "rateLimits", Map.of("excludedIPs", List.of("203.0.113.7")),
            "trustedProxy", Map.of("headers", List.of("X-Test-IP"), "useLeftmostIP", false)));
    Map<String, String> proxyHeaders = Map.of("X-Test-IP", "198.51.100.4, 203.0.113.7");
    assertEquals(200, rawRequest("GET", "/api/health", userToken, null, proxyHeaders).statusCode());
    assertEquals(200, rawRequest("GET", "/api/health", userToken, null, proxyHeaders).statusCode());

    request(
        "PATCH",
        "/api/settings",
        rootToken,
        Map.of(
            "rateLimits",
            Map.of(
                "enabled", true,
                "rules",
                List.of(
                    Map.of(
                        "label",
                        "*:create",
                        "audience",
                        "@guest",
                        "duration",
                        60,
                        "maxRequests",
                        1)),
                "excludedIPs", List.of()),
            "trustedProxy", Map.of("headers", List.of(), "useLeftmostIP", false)));
    request(
        "POST",
        "/api/collections",
        rootToken,
        Map.of(
            "name", "limited_posts",
            "createRule", "",
            "listRule", "",
            "fields", List.of(Map.of("name", "title", "type", "text", "required", true))));
    assertEquals(
        200,
        rawRequest("POST", "/api/collections/limited_posts/records", null, Map.of("title", "first"))
            .statusCode());
    assertEquals(
        429,
        rawRequest(
            "POST", "/api/collections/limited_posts/records", null, Map.of("title", "second"))
            .statusCode());

    request(
        "PATCH",
        "/api/settings",
        rootToken,
        Map.of(
            "rateLimits",
            Map.of(
                "enabled", true,
                "rules",
                List.of(
                    Map.of(
                        "label",
                        "*:auth",
                        "audience",
                        "@guest",
                        "duration",
                        60,
                        "maxRequests",
                        1)),
                "excludedIPs", List.of())));
    assertEquals(
        200,
        rawRequest(
            "POST",
            "/api/collections/rate_limit_users/auth-with-password",
            null,
            Map.of("identity", "limited@example.com", "password", "Secret_456"))
            .statusCode());
    assertEquals(
        429,
        rawRequest(
            "POST",
            "/api/collections/rate_limit_users/auth-with-password",
            null,
            Map.of("identity", "limited@example.com", "password", "Secret_456"))
            .statusCode());

    request("PATCH", "/api/settings", rootToken, Map.of("rateLimits", Map.of("enabled", false)));
    RawHttpResponse tooLarge =
        rawDeclaredContentLengthRequest("POST", "/api/health", (32L << 20) + 1);
    assertEquals(413, tooLarge.status());
    JsonNode tooLargeBody = mapper.readTree(tooLarge.body());
    assertEquals(413, tooLargeBody.get("status").asInt());
    assertEquals("Request entity too large", tooLargeBody.get("message").asText());
  }

  @Test
  void superuserIpWhitelistMatchesOfficialMiddlewareAndAuthResponseRules() throws Exception {
    start();
    bootstrapSuperuser();
    String superuserToken = loginToken();

    request(
        "POST",
        "/api/collections",
        superuserToken,
        Map.of(
            "name", "ip_policy_users",
            "type", "auth",
            "options", Map.of("authRule", "")));
    request(
        "POST",
        "/api/collections/ip_policy_users/records",
        superuserToken,
        Map.of(
            "email", "ip-user@example.com",
            "password", "Secret_123",
            "passwordConfirm", "Secret_123",
            "verified", true));
    String userToken =
        request(
            "POST",
            "/api/collections/ip_policy_users/auth-with-password",
            null,
            Map.of(
                "identity", "ip-user@example.com",
                "password", "Secret_123"))
            .get("token")
            .asText();

    HttpResponse<String> invalidSetting =
        rawRequest(
            "PATCH", "/api/settings", superuserToken, Map.of("superuserIPs", List.of("localhost")));
    assertEquals(400, invalidSetting.statusCode());
    assertTrue(mapper.readTree(invalidSetting.body()).path("data").has("superuserIPs"));
    HttpResponse<String> invalidSettingType =
        rawRequest("PATCH", "/api/settings", superuserToken, Map.of("superuserIPs", "127.0.0.1"));
    assertEquals(400, invalidSettingType.statusCode());
    assertTrue(mapper.readTree(invalidSettingType.body()).path("data").has("superuserIPs"));

    request(
        "PATCH",
        "/api/settings",
        superuserToken,
        Map.of(
            "trustedProxy", Map.of("headers", List.of("X-Test-IP"), "useLeftmostIP", false),
            "superuserIPs", List.of("10.0.0.0/8")));

    assertEquals(
        200,
        rawRequest("GET", "/api/health", null, null, Map.of("X-Test-IP", "127.0.0.1"))
            .statusCode());
    assertEquals(
        200,
        rawRequest(
            "POST",
            "/api/collections/ip_policy_users/auth-refresh",
            userToken,
            null,
            Map.of("X-Test-IP", "127.0.0.1"))
            .statusCode());

    HttpResponse<String> blockedRequest =
        rawRequest("GET", "/api/settings", superuserToken, null, Map.of("X-Test-IP", "127.0.0.1"));
    assertEquals(403, blockedRequest.statusCode());
    assertEquals(0, mapper.readTree(blockedRequest.body()).path("data").size());

    HttpResponse<String> blockedLogin =
        rawRequest(
            "POST",
            "/api/collections/_superusers/auth-with-password",
            null,
            Map.of("identity", "root@example.com", "password", "Secret_123"),
            Map.of("X-Test-IP", "127.0.0.1"));
    assertEquals(403, blockedLogin.statusCode());

    JsonNode allowedLogin =
        requestWithHeaders(
            "POST",
            "/api/collections/_superusers/auth-with-password",
            null,
            Map.of("identity", "root@example.com", "password", "Secret_123"),
            Map.of("X-Test-IP", "invalid, 127.0.0.1, 10.2.3.4, invalid"));
    String allowedToken = allowedLogin.get("token").asText();
    assertEquals(
        200,
        rawRequest(
            "GET",
            "/api/settings",
            allowedToken,
            null,
            Map.of("X-Test-IP", "invalid, 127.0.0.1, 10.2.3.4, invalid"))
            .statusCode());

    requestWithHeaders(
        "POST",
        "/api/backups",
        allowedToken,
        Map.of("name", "ip-policy.zip"),
        Map.of("X-Test-IP", "invalid, 127.0.0.1, 10.2.3.4, invalid"));
    String fileToken =
        requestWithHeaders(
            "POST",
            "/api/files/token",
            allowedToken,
            null,
            Map.of("X-Test-IP", "invalid, 127.0.0.1, 10.2.3.4, invalid"))
            .get("token")
            .asText();
    assertEquals(
        403,
        rawRequest(
            "GET",
            "/api/backups/ip-policy.zip?token=" + fileToken,
            null,
            null,
            Map.of("X-Test-IP", "127.0.0.1"))
            .statusCode());
    assertEquals(
        200,
        rawRequest(
            "GET",
            "/api/backups/ip-policy.zip?token=" + fileToken,
            null,
            null,
            Map.of("X-Test-IP", "invalid, 127.0.0.1, 10.2.3.4, invalid"))
            .statusCode());

    requestWithHeaders(
        "PATCH",
        "/api/settings",
        allowedToken,
        Map.of("trustedProxy", Map.of("useLeftmostIP", true)),
        Map.of("X-Test-IP", "invalid, 127.0.0.1, 10.2.3.4, invalid"));
    assertEquals(
        403,
        rawRequest(
            "GET",
            "/api/settings",
            allowedToken,
            null,
            Map.of("X-Test-IP", "invalid, 127.0.0.1, 10.2.3.4, invalid"))
            .statusCode());
    assertEquals(
        200,
        rawRequest(
            "GET",
            "/api/settings",
            allowedToken,
            null,
            Map.of("X-Test-IP", "10.2.3.4, 127.0.0.1"))
            .statusCode());
  }

  @Test
  void dryRunViewPreviewsSelectQueriesAndRejectsWriteStatements() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    assertEquals(
        401,
        rawRequest("POST", "/api/collections/meta/dry-run-view", null, Map.of("query", "select 1"))
            .statusCode());

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "dry_run_posts", "fields", List.of(Map.of("name", "title", "type", "text"))));
    request("POST", "/api/collections/dry_run_posts/records", token, Map.of("title", "alpha"));
    request("POST", "/api/collections/dry_run_posts/records", token, Map.of("title", "beta"));

    JsonNode preview =
        request(
            "POST",
            "/api/collections/meta/dry-run-view",
            token,
            Map.of("query", "select id, title from dry_run_posts order by title"));
    assertTrue(preview.has("fields"));
    assertTrue(preview.has("sample"));
    assertFalse(preview.has("columns"));
    assertFalse(preview.has("rows"));
    assertEquals("id", preview.get("fields").get(0).get("name").asText());
    assertEquals("text", preview.get("fields").get(0).get("type").asText());
    assertEquals("title", preview.get("fields").get(1).get("name").asText());
    assertEquals("alpha", preview.get("sample").get(0).get("title").asText());
    assertEquals("beta", preview.get("sample").get(1).get("title").asText());
    assertFalse(preview.get("sample").get(0).get("id").asText().isBlank());

    HttpResponse<String> writeQuery =
        rawRequest(
            "POST",
            "/api/collections/meta/dry-run-view",
            token,
            Map.of("query", "insert into t values (1)"));
    assertEquals(400, writeQuery.statusCode());
    assertErrorEnvelope(
        writeQuery, 400, "Invalid view query.");

    HttpResponse<String> multipleStatements =
        rawRequest(
            "POST",
            "/api/collections/meta/dry-run-view",
            token,
            Map.of("query", "select id from dry_run_posts; select id from dry_run_posts"));
    assertEquals(400, multipleStatements.statusCode());
    assertErrorEnvelope(
        multipleStatements,
        400,
        "Invalid view query.");

    HttpResponse<String> wildcard =
        rawRequest(
            "POST",
            "/api/collections/meta/dry-run-view",
            token,
            Map.of("query", "select * from dry_run_posts"));
    assertEquals(400, wildcard.statusCode());
    assertErrorEnvelope(
        wildcard,
        400,
        "Invalid view query.");

    HttpResponse<String> missingId =
        rawRequest(
            "POST",
            "/api/collections/meta/dry-run-view",
            token,
            Map.of("query", "select title from dry_run_posts"));
    assertEquals(400, missingId.statusCode());
    assertErrorEnvelope(
        missingId,
        400,
        "Invalid view query.");

    request("POST", "/api/collections/dry_run_posts/records", token, Map.of("title", "duplicate"));
    request("POST", "/api/collections/dry_run_posts/records", token, Map.of("title", "duplicate"));
    HttpResponse<String> duplicateIds =
        rawRequest(
            "POST",
            "/api/collections/meta/dry-run-view",
            token,
            Map.of("query", "select title as id from dry_run_posts where title = 'duplicate'"));
    assertEquals(400, duplicateIds.statusCode());
    assertErrorEnvelope(
        duplicateIds,
        400,
        "Invalid view query.");

    HttpResponse<String> missingQuery =
        rawRequest("POST", "/api/collections/meta/dry-run-view", token, Map.of("query", ""));
    assertEquals(400, missingQuery.statusCode());
    assertFieldError(
        missingQuery,
        400,
        "An error occurred while validating the submitted data.",
        "query",
        "validation_required",
        "Cannot be blank.");
  }

  @Test
  void viewCollectionsGenerateFieldsExecuteQueriesAndRollbackInvalidUpdates() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "view_sources",
            "listRule", "",
            "viewRule", "",
            "fields",
            List.of(
                Map.of("name", "title", "type", "text"),
                Map.of("name", "status", "type", "text"))));
    request(
        "POST",
        "/api/collections/view_sources/records",
        token,
        Map.of("title", "Published A", "status", "published"));
    request(
        "POST",
        "/api/collections/view_sources/records",
        token,
        Map.of("title", "Draft A", "status", "draft"));

    HttpResponse<String> invalidCreate =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "invalid_view",
                "type", "view",
                "viewQuery", "select title from view_sources"));
    assertEquals(400, invalidCreate.statusCode());
    assertFieldErrorMessageStartsWith(
        invalidCreate,
        400,
        "Failed to create collection.",
        "viewQuery",
        "validation_invalid_view_query",
        "Invalid query.");

    String publishedQuery =
        "select id, title from view_sources where status = 'published' order by title";
    JsonNode view =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "published_posts",
                "type", "view",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(Map.of("name", "ignored!@#$", "type", "text")),
                "viewQuery", publishedQuery));
    assertEquals("view", view.get("type").asText());
    assertEquals(publishedQuery, view.get("viewQuery").asText());
    assertEquals(List.of("id", "title"), fieldNames(view));

    JsonNode published =
        request("GET", "/api/collections/published_posts/records?sort=title", null, null);
    assertEquals(1, published.get("totalItems").asInt());
    assertEquals("Published A", published.get("items").get(0).get("title").asText());

    request(
        "POST",
        "/api/collections/view_sources/records",
        token,
        Map.of("title", "Published B", "status", "published"));
    JsonNode refreshed =
        request("GET", "/api/collections/published_posts/records?sort=title", null, null);
    assertEquals(2, refreshed.get("totalItems").asInt());
    assertEquals("Published B", refreshed.get("items").get(1).get("title").asText());

    String draftQuery =
        "select id, title, status from view_sources where status = 'draft' order by title";
    JsonNode updated =
        request(
            "PATCH",
            "/api/collections/published_posts",
            token,
            Map.of(
                "fields",
                List.of(Map.of("name", "still_ignored!", "type", "text")),
                "viewQuery",
                draftQuery));
    assertEquals("published_posts", updated.get("name").asText());
    assertEquals(draftQuery, updated.get("viewQuery").asText());
    assertEquals(List.of("id", "title", "status"), fieldNames(updated));

    JsonNode drafts = request("GET", "/api/collections/published_posts/records", null, null);
    assertEquals(1, drafts.get("totalItems").asInt());
    assertEquals("Draft A", drafts.get("items").get(0).get("title").asText());

    HttpResponse<String> invalidUpdate =
        rawRequest(
            "PATCH",
            "/api/collections/published_posts",
            token,
            Map.of("viewQuery", "select * from view_sources"));
    assertEquals(400, invalidUpdate.statusCode());
    assertFieldErrorMessageStartsWith(
        invalidUpdate,
        400,
        "Failed to update collection.",
        "viewQuery",
        "validation_invalid_view_query",
        "Invalid query.");

    JsonNode unchanged = request("GET", "/api/collections/published_posts", token, null);
    assertEquals(draftQuery, unchanged.get("viewQuery").asText());
    assertEquals(List.of("id", "title", "status"), fieldNames(unchanged));

    server.close();
    start();
    JsonNode afterRestart = request("GET", "/api/collections/published_posts/records", null, null);
    assertEquals(1, afterRestart.get("totalItems").asInt());
    assertEquals("Draft A", afterRestart.get("items").get(0).get("title").asText());
  }

  @Test
  void collectionImportAndTruncateApisMatchOfficialRoutes() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    assertEquals(
        401,
        rawRequest("PUT", "/api/collections/import", null, Map.of("collections", List.of()))
            .statusCode());

    JsonNode keep =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "import_keep",
                "listRule", "",
                "viewRule", "",
                "fields",
                List.of(
                    Map.of("name", "title", "type", "text", "required", true),
                    Map.of("name", "attachment", "type", "file"))));
    JsonNode obsolete =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "import_obsolete",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(Map.of("name", "title", "type", "text", "required", true))));

    JsonNode keptRecord =
        multipartRequest(
            "POST",
            "/api/collections/import_keep/records",
            token,
            Map.of("title", "kept"),
            Map.of(
                "attachment",
                new MultipartFile(
                    "kept.txt", "text/plain", "kept file".getBytes(StandardCharsets.UTF_8))));
    String keptFilename = keptRecord.get("attachment").asText();
    request("POST", "/api/collections/import_obsolete/records", token, Map.of("title", "obsolete"));

    HttpResponse<String> emptyImport =
        rawRequest("PUT", "/api/collections/import", token, Map.of("collections", List.of()));
    assertEquals(400, emptyImport.statusCode());
    assertFieldError(
        emptyImport,
        400,
        "Failed to import collections.",
        "collections",
        "validation_required",
        "Cannot be blank.");
    JsonNode stillThere =
        request("GET", "/api/collections/" + obsolete.get("id").asText(), token, null);
    assertEquals("import_obsolete", stillThere.get("name").asText());

    HttpResponse<String> imported =
        rawRequest(
            "PUT",
            "/api/collections/import",
            token,
            Map.of(
                "deleteMissing",
                true,
                "collections",
                List.of(
                    Map.of(
                        "id", keep.get("id").asText(),
                        "name", "import_keep_renamed",
                        "type", "base",
                        "listRule", "",
                        "viewRule", "",
                        "fields",
                        List.of(
                            Map.of(
                                "id",
                                keep.get("fields").get(0).get("id").asText(),
                                "name",
                                "id",
                                "type",
                                "text",
                                "required",
                                true,
                                "system",
                                true),
                            Map.of("name", "title", "type", "text", "required", true),
                            Map.of("name", "status", "type", "text"),
                            Map.of("name", "attachment", "type", "file"))),
                    Map.of(
                        "name", "import_auth_users",
                        "type", "auth"))));
    assertEquals(204, imported.statusCode());

    HttpResponse<String> oldName = rawRequest("GET", "/api/collections/import_keep", token, null);
    assertEquals(404, oldName.statusCode());
    JsonNode renamed = request("GET", "/api/collections/import_keep_renamed", token, null);
    assertEquals(keep.get("id").asText(), renamed.get("id").asText());
    assertEquals(4, renamed.get("fields").size());

    JsonNode records = request("GET", "/api/collections/import_keep_renamed/records", null, null);
    assertEquals(1, records.get("totalItems").asInt());
    assertEquals("kept", records.get("items").get(0).get("title").asText());
    HttpResponse<String> keptFile =
        rawRequest(
            "GET",
            "/api/files/import_keep_renamed/" + keptRecord.get("id").asText() + "/" + keptFilename,
            null,
            null);
    assertEquals(200, keptFile.statusCode());

    assertEquals(
        404, rawRequest("GET", "/api/collections/import_obsolete", token, null).statusCode());
    assertFalse(
        Files.exists(tempDir.resolve("records").resolve(obsolete.get("id").asText() + ".json")));

    JsonNode authCollection = request("GET", "/api/collections/import_auth_users", token, null);
    assertEquals("auth", authCollection.get("type").asText());
    assertTrue(fieldNames(authCollection).containsAll(List.of("email", "password", "verified")));
    JsonNode superusers = request("GET", "/api/collections/_superusers", token, null);
    assertEquals("_superusers", superusers.get("name").asText());

    HttpResponse<String> deleteSystem =
        rawRequest("DELETE", "/api/collections/_superusers", token, null);
    assertEquals(400, deleteSystem.statusCode());
    assertErrorEnvelope(deleteSystem, 400, "System collections cannot be deleted.");

    HttpResponse<String> truncateSystem =
        rawRequest("DELETE", "/api/collections/_superusers/truncate", token, null);
    assertEquals(400, truncateSystem.statusCode());
    assertErrorEnvelope(truncateSystem, 400, "System collections cannot be truncated.");

    HttpResponse<String> truncated =
        rawRequest("DELETE", "/api/collections/import_keep_renamed/truncate", token, null);
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

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name",
            "action_base_posts",
            "fields",
            List.of(Map.of("name", "title", "type", "text"))));
    HttpResponse<String> baseAuth =
        rawRequest(
            "POST",
            "/api/collections/action_base_posts/auth-with-password",
            null,
            Map.of(
                "identity", "dev@example.com",
                "password", "Secret_123"));
    assertEquals(400, baseAuth.statusCode());
    assertErrorEnvelope(baseAuth, 400, "The collection is not an auth collection.");

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "readonly_view",
            "type", "view",
            "options", Map.of("query", "select 'view-record' as id")));
    HttpResponse<String> createViewRecord =
        rawRequest(
            "POST",
            "/api/collections/readonly_view/records",
            token,
            Map.of("title", "should fail"));
    assertEquals(400, createViewRecord.statusCode());
    assertErrorEnvelope(createViewRecord, 400, "View collections are read-only.");

    HttpResponse<String> updateViewRecord =
        rawRequest(
            "PATCH",
            "/api/collections/readonly_view/records/view-record",
            token,
            Map.of("title", "should fail"));
    assertEquals(400, updateViewRecord.statusCode());
    assertErrorEnvelope(updateViewRecord, 400, "View collections are read-only.");
  }

  @Test
  void recordsPersistAcrossServerRestart() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "tasks",
            "listRule", "",
            "fields", List.of(Map.of("name", "name", "type", "text", "required", true))));
    request("POST", "/api/collections/tasks/records", token, Map.of("name", "persist me"));
    server.close();

    start();
    JsonNode page = request("GET", "/api/collections/tasks/records", null, null);

    assertEquals(1, page.get("totalItems").asInt());
    assertEquals("persist me", page.get("items").get(0).get("name").asText());
  }

  @Test
  void longAuthCollectionNamesBootstrapAgainstPhysicalTablesOnRestart() throws Exception {
    start();
    bootstrapSuperuser();
    String superuserToken = loginToken();
    String collectionName = "auth_" + "a".repeat(80);

    request(
        "POST", "/api/collections", superuserToken, Map.of("name", collectionName, "type", "auth"));
    request(
        "POST",
        "/api/collections/" + collectionName + "/records",
        superuserToken,
        Map.of(
            "email", "long-name@example.com",
            "password", "Secret_456",
            "passwordConfirm", "Secret_456"));

    server.close();
    start();

    JsonNode authenticated =
        request(
            "POST",
            "/api/collections/" + collectionName + "/auth-with-password",
            null,
            Map.of("identity", "long-name@example.com", "password", "Secret_456"));
    assertTrue(authenticated.hasNonNull("token"));
  }

  @Test
  void backupsCanBeCreatedDownloadedRestoredAndDeleted() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "tasks",
            "listRule", "",
            "viewRule", "",
            "fields", List.of(Map.of("name", "name", "type", "text", "required", true)),
            "indexes",
            List.of("create index idx_tasks_name_not_blank on tasks (name) where name != ''")));
    request("POST", "/api/collections/tasks/records", token, Map.of("name", "before backup"));
    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "backup_tasks_view",
            "type", "view",
            "listRule", "",
            "viewRule", "",
            "viewQuery", "select id, name from tasks"));

    HttpResponse<String> missingUpload =
        rawMultipartRequest("POST", "/api/backups", token, Map.of(), Map.of());
    assertEquals(400, missingUpload.statusCode());
    assertFieldError(
        missingUpload,
        400,
        "Backup file is required.",
        "file",
        "validation_required",
        "Cannot be blank.");

    HttpResponse<String> missingMultipartBoundary =
        rawBodyRequest(
            "POST",
            "/api/backups",
            token,
            "multipart/form-data",
            "not a valid multipart payload".getBytes(StandardCharsets.UTF_8));
    assertEquals(400, missingMultipartBoundary.statusCode());
    assertFieldError(
        missingMultipartBoundary,
        400,
        "Failed to read request body.",
        "body",
        "validation_required",
        "Cannot be blank.");

    HttpResponse<String> invalidMime =
        rawMultipartRequest(
            "POST",
            "/api/backups/upload",
            token,
            Map.of(),
            Map.of(
                "file",
                new MultipartFile(
                    "broken.zip", "text/plain", "not a zip".getBytes(StandardCharsets.UTF_8))));
    assertEquals(400, invalidMime.statusCode());
    assertFieldError(
        invalidMime,
        400,
        "An error occurred while validating the submitted data.",
        "file",
        "validation_invalid_mime_type",
        "Invalid file type.");

    HttpResponse<String> opaqueUpload =
        rawMultipartRequest(
            "POST",
            "/api/backups/upload",
            token,
            Map.of(),
            Map.of(
                "file",
                new MultipartFile(
                    "broken.zip",
                    "application/zip",
                    "not a zip".getBytes(StandardCharsets.UTF_8))));
    assertEquals(204, opaqueUpload.statusCode());
    assertTrue(opaqueUpload.body().isBlank());

    HttpResponse<String> invalidName =
        rawRequest("POST", "/api/backups", token, Map.of("name", "!snap.zip"));
    assertEquals(400, invalidName.statusCode());
    assertFieldError(
        invalidName,
        400,
        "An error occurred while validating the submitted data.",
        "name",
        "validation_match_invalid",
        "Must be in a valid format.");

    HttpResponse<String> createdBackup =
        rawRequest("POST", "/api/backups", token, Map.of("name", "snap.zip"));
    assertEquals(204, createdBackup.statusCode());
    assertTrue(createdBackup.body().isBlank());
    assertTrue(Files.size(tempDir.resolve("backups").resolve("snap.zip")) > 0);
    if (usesRelationalStorage()) {
      JsonNode snapshot = relationalBackupSnapshot(tempDir.resolve("backups").resolve("snap.zip"));
      String configuredStorage =
          System.getProperty("storage", "json").toLowerCase(java.util.Locale.ROOT);
      String expectedEngine =
          switch (configuredStorage) {
            case "mariadb" -> "mysql";
            case "postgresql" -> "postgres";
            default -> configuredStorage;
          };
      assertEquals(expectedEngine, snapshot.get("engine").asText());
      String viewSql = relationalBackupObjectSql(snapshot, "view", "backup_tasks_view");
      assertTrue(viewSql.toUpperCase(java.util.Locale.ROOT).startsWith("CREATE VIEW"));
      String indexSql = relationalBackupObjectSql(snapshot, "index", "idx_tasks_name_not_blank");
      assertTrue(indexSql.toUpperCase(java.util.Locale.ROOT).startsWith("CREATE INDEX"));
      if ("mysql".equalsIgnoreCase(System.getProperty("storage"))
          || "mariadb".equalsIgnoreCase(System.getProperty("storage"))) {
        String normalizedIndexSql = indexSql.toUpperCase(java.util.Locale.ROOT);
        assertTrue(normalizedIndexSql.contains("UNHEX(SHA2"));
        assertTrue(normalizedIndexSql.contains("CASE WHEN"));
      } else {
        assertTrue(indexSql.toUpperCase(java.util.Locale.ROOT).contains(" WHERE "));
      }
    }

    HttpResponse<String> duplicateBackup =
        rawRequest("POST", "/api/backups", token, Map.of("name", "snap.zip"));
    assertEquals(400, duplicateBackup.statusCode());
    assertFieldError(
        duplicateBackup,
        400,
        "Backup already exists.",
        "name",
        "validation_not_unique",
        "Value must be unique.");

    HttpResponse<String> duplicateUpload =
        rawMultipartRequest(
            "POST",
            "/api/backups",
            token,
            Map.of(),
            Map.of(
                "file",
                new MultipartFile(
                    "snap.zip",
                    "application/zip",
                    Files.readAllBytes(tempDir.resolve("backups").resolve("snap.zip")))));
    assertEquals(400, duplicateUpload.statusCode());
    assertFieldError(
        duplicateUpload,
        400,
        "Backup already exists.",
        "file",
        "validation_not_unique",
        "Value must be unique.");

    JsonNode backups = request("GET", "/api/backups", token, null);
    assertTrue(backups.isArray());
    assertEquals(2, backups.size());
    JsonNode snapInfo = findBy(backups, "key", "snap.zip");
    assertEquals("snap.zip", snapInfo.get("key").asText());
    assertTrue(snapInfo.get("size").asLong() > 0);
    assertTrue(snapInfo.get("modified").isTextual());
    assertFalse(snapInfo.has("name"));

    HttpResponse<String> brokenRestore =
        rawRequest("POST", "/api/backups/broken.zip/restore", token, null);
    assertEquals(400, brokenRestore.statusCode());
    assertFieldError(
        brokenRestore,
        400,
        "Invalid backup archive.",
        "file",
        "validation_invalid_value",
        "Invalid backup archive.");
    assertEquals(204, rawRequest("DELETE", "/api/backups/broken.zip", token, null).statusCode());

    String fileToken = request("POST", "/api/files/token", token, null).get("token").asText();
    HttpResponse<String> missingDownload =
        rawRequest("GET", "/api/backups/missing.zip?token=" + fileToken, null, null);
    assertEquals(404, missingDownload.statusCode());
    assertErrorEnvelope(missingDownload, 404, "Backup not found.");

    assertEquals(403, rawRequest("GET", "/api/backups/snap.zip", null, null).statusCode());
    assertEquals(403, rawRequest("GET", "/api/backups/snap.zip", token, null).statusCode());

    HttpResponse<byte[]> download =
        http.send(
            HttpRequest.newBuilder(
                URI.create(server.baseUrl() + "/api/backups/snap.zip?token=" + fileToken))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
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
        "malicious restore entry".getBytes(StandardCharsets.UTF_8));
    HttpResponse<String> evilRestore =
        rawRequest("POST", "/api/backups/evil.zip/restore", token, null);
    assertEquals(400, evilRestore.statusCode());
    assertFieldError(
        evilRestore,
        400,
        "Invalid backup archive.",
        "file",
        "validation_invalid_value",
        "Invalid backup archive.");
    JsonNode stillChanged = request("GET", "/api/collections/tasks/records", null, null);
    assertEquals(2, stillChanged.get("totalItems").asInt());

    HttpResponse<String> restore = rawRequest("POST", "/api/backups/snap.zip/restore", token, null);
    assertEquals(204, restore.statusCode(), restore.body());
    assertTrue(restore.body().isBlank());
    JsonNode restored = request("GET", "/api/collections/tasks/records", null, null);
    assertEquals(1, restored.get("totalItems").asInt());
    assertEquals("before backup", restored.get("items").get(0).get("name").asText());
    JsonNode restoredView =
        request("GET", "/api/collections/backup_tasks_view/records", null, null);
    assertEquals(1, restoredView.get("totalItems").asInt());
    assertEquals("before backup", restoredView.get("items").get(0).get("name").asText());

    HttpResponse<String> deleted = rawRequest("DELETE", "/api/backups/snap.zip", token, null);
    assertEquals(204, deleted.statusCode());

    HttpResponse<String> deleteMissing = rawRequest("DELETE", "/api/backups/snap.zip", token, null);
    assertEquals(400, deleteMissing.statusCode());
    assertErrorEnvelope(deleteMissing, 400, "Invalid or already deleted backup file.");
  }

  @Test
  void legacyEngineLessSnapshotsOnlyRestoreOnSqlite() throws Exception {
    if (!usesRelationalStorage()) {
      return;
    }
    start();
    bootstrapSuperuser();
    String token = loginToken();
    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "legacy_restore_tasks",
            "listRule", "",
            "fields", List.of(Map.of("name", "name", "type", "text", "required", true))));
    JsonNode original =
        request(
            "POST",
            "/api/collections/legacy_restore_tasks/records",
            token,
            Map.of("name", "must survive"));
    assertEquals(
        204, rawRequest("POST", "/api/backups", token, Map.of("name", "legacy.zip")).statusCode());
    request(
        "POST",
        "/api/collections/legacy_restore_tasks/records",
        token,
        Map.of("name", "after backup"));

    removeEngineFromRelationalBackup(tempDir.resolve("backups").resolve("legacy.zip"));
    HttpResponse<String> restore =
        rawRequest("POST", "/api/backups/legacy.zip/restore", token, null);
    if (usesExternalRelationalStorage()) {
      assertEquals(400, restore.statusCode());
      assertErrorEnvelope(
          restore, 400, "Backup storage engine does not match the active storage engine.");
    } else {
      assertEquals(204, restore.statusCode(), restore.body());
    }

    JsonNode remaining =
        request("GET", "/api/collections/legacy_restore_tasks/records", null, null);
    assertEquals(usesExternalRelationalStorage() ? 2 : 1, remaining.get("totalItems").asInt());
    JsonNode preserved =
        request(
            "GET",
            "/api/collections/legacy_restore_tasks/records/" + original.get("id").asText(),
            token,
            null);
    assertEquals("must survive", preserved.get("name").asText());
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

    JsonNode updated =
        request(
            "PATCH",
            "/api/settings",
            token,
            Map.of(
                "meta",
                Map.of(
                    "appName", "Demo Console",
                    "appUrl", "https://example.test"),
                "logs",
                Map.of(
                    "maxDays", 14,
                    "logIp", true,
                    "logAuthId", true),
                "smtp",
                Map.of(
                    "enabled", true,
                    "host", "smtp.example.test",
                    "password", "smtp-secret"),
                "s3",
                Map.of(
                    "accessKey", "access-secret",
                    "secret", "storage-secret")));
    assertEquals("Demo Console", updated.get("meta").get("appName").asText());
    assertEquals("https://example.test", updated.get("meta").get("appURL").asText());
    assertTrue(updated.get("logs").get("logIP").asBoolean());
    assertFalse(updated.get("smtp").has("password"));
    assertEquals("access-secret", updated.get("s3").get("accessKey").asText());
    assertFalse(updated.get("s3").has("secret"));

    request(
        "PATCH",
        "/api/settings",
        token,
        Map.of(
            "smtp", Map.of("password", "******"),
            "s3", Map.of("secret", "******")));
    if (usesRelationalStorage()) {
      try (java.sql.Connection conn = openRelationalConnection();
          java.sql.Statement stmt = conn.createStatement();
          java.sql.ResultSet rs =
              stmt.executeQuery(
                  "SELECT "
                      + databaseIdentifier("value")
                      + " FROM "
                      + databaseIdentifier("_params")
                      + " WHERE "
                      + databaseIdentifier("key")
                      + " = 'settings'")) {
        assertTrue(rs.next());
        String settingsVal = rs.getString(1);
        assertTrue(settingsVal.contains("smtp-secret"));
        assertTrue(settingsVal.contains("storage-secret"));
      }
    } else {
      String settingsFile =
          Files.readString(tempDir.resolve("pb_settings.json"), StandardCharsets.UTF_8);
      assertTrue(settingsFile.contains("smtp-secret"));
      assertTrue(settingsFile.contains("storage-secret"));
    }

    JsonNode filteredLogs =
        request(
            "GET",
            "/api/logs?perPage=50&sort=-created&filter="
                + URLEncoder.encode("data.status = 200", StandardCharsets.UTF_8),
            token,
            null);
    assertTrue(filteredLogs.get("totalItems").asInt() >= 1);
    JsonNode log = filteredLogs.get("items").get(0);
    assertTrue(log.hasNonNull("id"));
    assertEquals(200, log.get("data").get("status").asInt());
    assertTrue(log.get("data").hasNonNull("method"));
    assertTrue(log.get("data").hasNonNull("url"));
    assertTrue(log.get("data").hasNonNull("authId"));
    assertTrue(log.get("data").get("execTime").asDouble() >= 0.0D);

    HttpResponse<String> invalidLogFilter =
        rawRequest(
            "GET",
            "/api/logs?filter=" + URLEncoder.encode("data.status #", StandardCharsets.UTF_8),
            token,
            null);
    assertEquals(400, invalidLogFilter.statusCode());
    assertFieldErrorMessageStartsWith(
        invalidLogFilter,
        400,
        "Invalid filter.",
        "filter",
        "validation_invalid_value",
        "Invalid filter");

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

    assertEquals(
        401,
        rawRequest(
            "POST",
            "/api/settings/test/email",
            null,
            Map.of(
                "email", "dev@example.com",
                "template", "verification"))
            .statusCode());

    HttpResponse<String> invalidS3 =
        rawRequest("POST", "/api/settings/test/s3", token, Map.of("filesystem", "invalid"));
    assertEquals(400, invalidS3.statusCode());
    assertFieldError(
        invalidS3,
        400,
        "Failed to test the S3 filesystem.",
        "filesystem",
        "validation_invalid_value",
        "Must be either storage or backups.");

    HttpResponse<String> missingS3 = rawRequest("POST", "/api/settings/test/s3", token, Map.of());
    assertEquals(400, missingS3.statusCode());
    assertFieldError(
        missingS3,
        400,
        "Failed to test the S3 filesystem.",
        "filesystem",
        "validation_required",
        "Cannot be blank.");

    HttpResponse<String> disabledS3 =
        rawRequest("POST", "/api/settings/test/s3", token, Map.of("filesystem", "storage"));
    assertEquals(400, disabledS3.statusCode());
    assertFieldError(
        disabledS3,
        400,
        "Failed to test the S3 filesystem.",
        "filesystem",
        "validation_invalid_value",
        "S3 storage filesystem is not enabled.");

    HttpResponse<String> missingS3Bucket =
        rawRequest(
            "POST",
            "/api/settings/test/s3",
            token,
            Map.of(
                "filesystem",
                "storage",
                "s3",
                Map.of(
                    "enabled", true,
                    "region", "us-east-1",
                    "accessKey", "access-key",
                    "secret", "secret-key")));
    assertEquals(400, missingS3Bucket.statusCode());
    assertFieldError(
        missingS3Bucket,
        400,
        "Failed to test the S3 filesystem.",
        "bucket",
        "validation_required",
        "Cannot be blank.");

    HttpResponse<String> missingBackupS3Secret =
        rawRequest(
            "POST",
            "/api/settings/test/s3",
            token,
            Map.of(
                "filesystem",
                "backups",
                "backups",
                Map.of(
                    "s3",
                    Map.of(
                        "enabled", true,
                        "bucket", "backup-bucket",
                        "region", "us-east-1",
                        "accessKey", "backup-access-key"))));
    assertEquals(400, missingBackupS3Secret.statusCode());
    assertFieldError(
        missingBackupS3Secret,
        400,
        "Failed to test the S3 filesystem.",
        "secret",
        "validation_required",
        "Cannot be blank.");

    HttpResponse<String> queuedEmail =
        rawRequest(
            "POST",
            "/api/settings/test/email",
            token,
            Map.of(
                "email", "dev@example.com",
                "template", "verification",
                "smtp", Map.of("enabled", false)));
    assertEquals(204, queuedEmail.statusCode());
    JsonNode emailRequests = mapper.readTree(tempDir.resolve("auth_requests.json").toFile());
    JsonNode queued = emailRequests.get(emailRequests.size() - 1);
    assertEquals("testEmail", queued.get("type").asText());
    assertEquals("verification", queued.get("template").asText());
    assertEquals("dev@example.com", queued.get("email").asText());

    HttpResponse<String> invalidEmail =
        rawRequest(
            "POST",
            "/api/settings/test/email",
            token,
            Map.of(
                "email", "dev@example.com",
                "template", "unknown"));
    assertEquals(400, invalidEmail.statusCode());
    assertFieldError(
        invalidEmail,
        400,
        "Failed to send the test email.",
        "template",
        "validation_invalid_value",
        "Invalid email template.");

    HttpResponse<String> missingTemplate =
        rawRequest("POST", "/api/settings/test/email", token, Map.of("email", "dev@example.com"));
    assertEquals(400, missingTemplate.statusCode());
    assertFieldError(
        missingTemplate,
        400,
        "Failed to send the test email.",
        "template",
        "validation_required",
        "Cannot be blank.");

    try (FakeSmtpServer smtp = FakeSmtpServer.start("421 test smtp down")) {
      HttpResponse<String> smtpFailure =
          rawRequest(
              "POST",
              "/api/settings/test/email",
              token,
              Map.of(
                  "email", "dev@example.com",
                  "template", "verification",
                  "smtp", Map.of("enabled", true, "host", "127.0.0.1", "port", smtp.port())));
      assertEquals(400, smtpFailure.statusCode());
      assertFieldError(
          smtpFailure,
          400,
          "Failed to send the test email.",
          "smtp",
          "validation_invalid_value",
          "SMTP delivery failed.");
    }

    HttpResponse<String> missingAppleClientId =
        rawRequest(
            "POST",
            "/api/settings/apple/generate-client-secret",
            token,
            Map.of(
                "teamId",
                "TEAMID1234",
                "keyId",
                "KEYID12345",
                "privateKey",
                ecPrivateKeyPem(),
                "duration",
                3600));
    assertEquals(400, missingAppleClientId.statusCode());
    assertFieldError(
        missingAppleClientId,
        400,
        "Invalid client secret data.",
        "clientId",
        "validation_required",
        "Cannot be blank.");

    HttpResponse<String> invalidAppleDuration =
        rawRequest(
            "POST",
            "/api/settings/apple/generate-client-secret",
            token,
            Map.of(
                "clientId", "com.example.service",
                "teamId", "TEAMID1234",
                "keyId", "KEYID12345",
                "privateKey", ecPrivateKeyPem(),
                "duration", 0));
    assertEquals(400, invalidAppleDuration.statusCode());
    assertFieldError(
        invalidAppleDuration,
        400,
        "Invalid client secret data.",
        "duration",
        "validation_invalid_value",
        "Must be between 1 and 15777000 seconds.");

    HttpResponse<String> invalidApplePrivateKey =
        rawRequest(
            "POST",
            "/api/settings/apple/generate-client-secret",
            token,
            Map.of(
                "clientId", "com.example.service",
                "teamId", "TEAMID1234",
                "keyId", "KEYID12345",
                "privateKey",
                "-----BEGIN PRIVATE KEY-----\nnot-valid-base64\n-----END PRIVATE KEY-----",
                "duration", 3600));
    assertEquals(400, invalidApplePrivateKey.statusCode());
    assertFieldError(
        invalidApplePrivateKey,
        400,
        "Invalid client secret data.",
        "privateKey",
        "validation_invalid_value",
        "Must be a valid PKCS#8 EC private key PEM.");

    JsonNode apple =
        request(
            "POST",
            "/api/settings/apple/generate-client-secret",
            token,
            Map.of(
                "clientId", "com.example.service",
                "teamId", "TEAMID1234",
                "keyId", "KEYID12345",
                "privateKey", ecPrivateKeyPem(),
                "duration", 3600));
    assertAppleClientSecret(apple.get("secret").asText());

    server.close();
    start();
    JsonNode persisted = request("GET", "/api/settings", token, null);
    assertEquals("Demo Console", persisted.get("meta").get("appName").asText());
    assertFalse(persisted.get("smtp").has("password"));
    assertFalse(persisted.get("s3").has("secret"));
  }

  @Test
  void logSettingsAndMaintenanceCronsMatchOfficialBehavior() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "PATCH",
        "/api/settings",
        token,
        Map.of("logs", Map.of("maxDays", 5, "minLevel", 8, "logIP", false, "logAuthId", false)));
    assertEquals(404, rawRequest("GET", "/api/missing-log", token, null).statusCode());

    JsonNode filtered = waitForLogs("data.url = '/api/missing-log'", token, 1);
    assertEquals(1, filtered.get("totalItems").asInt());
    JsonNode filteredLog = filtered.get("items").get(0);
    assertEquals(8, filteredLog.get("level").asInt());
    assertEquals("_superusers", filteredLog.get("data").get("auth").asText());
    assertFalse(filteredLog.get("data").has("authId"));
    assertFalse(filteredLog.get("data").has("remoteIP"));
    assertFalse(filteredLog.get("data").has("userIP"));

    rawRequest(
        "GET",
        "/api/referrer-log",
        token,
        null,
        Map.of(
            "Referer",
            "https://user:password@example.test/reset/secret-token?token=secret-query"));
    JsonNode redactedReferer = waitForLogs("data.url = '/api/referrer-log'", token, 1);
    assertEquals(
        "https://example.test", redactedReferer.get("items").get(0).get("data").get("referer").asText());

    request("PATCH", "/api/settings", token, Map.of("logs", Map.of("maxDays", 0)));
    assertEquals(0, request("GET", "/api/logs", token, null).get("totalItems").asInt());

    request(
        "PATCH",
        "/api/settings",
        token,
        Map.of("logs", Map.of("maxDays", 5, "minLevel", 0, "logIP", true, "logAuthId", true)));
    assertEquals(404, rawRequest("GET", "/api/old-maintenance-log", token, null).statusCode());
    JsonNode oldLogs = waitForLogs("data.url = '/api/old-maintenance-log'", token, 1);
    assertEquals(1, oldLogs.get("totalItems").asInt());
    JsonNode oldLog = oldLogs.get("items").get(0);
    String oldLogId = oldLog.get("id").asText();
    assertTrue(oldLog.get("data").hasNonNull("authId"));
    assertTrue(oldLog.get("data").hasNonNull("remoteIP"));
    assertTrue(oldLog.get("data").hasNonNull("userIP"));

    if (Files.exists(tempDir.resolve("pocketbase.db"))) {
      ageSqliteLogFixture(oldLogId);
      assertEquals(
          1,
          request(
              "GET",
              "/api/logs?filter="
                  + URLEncoder.encode("id = '" + oldLogId + "'", StandardCharsets.UTF_8),
              token,
              null)
              .get("totalItems")
              .asInt());
      assertEquals(
          204, rawRequest("POST", "/api/crons/__pbLogsCleanup__", token, null).statusCode());
      assertTrue(waitForLogMissing(oldLogId, token));
    } else {
      request("PATCH", "/api/settings", token, Map.of("logs", Map.of("maxDays", 0)));
      assertTrue(waitForLogMissing(oldLogId, token));
      assertEquals(
          204, rawRequest("POST", "/api/crons/__pbLogsCleanup__", token, null).statusCode());
    }
    assertEquals(204, rawRequest("POST", "/api/crons/__pbDBOptimize__", token, null).statusCode());
    Thread.sleep(200);
  }

  @Test
  void settingsTestEmailCanSendThroughConfiguredSmtp() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    try (FakeSmtpServer smtp = FakeSmtpServer.start()) {
      request(
          "PATCH",
          "/api/settings",
          token,
          Map.of(
              "meta",
              Map.of(
                  "senderName", "PocketBase Java",
                  "senderAddress", "noreply@example.com"),
              "smtp",
              Map.of("enabled", true, "host", "127.0.0.1", "port", smtp.port(), "tls", false)));

      HttpResponse<String> response =
          rawRequest(
              "POST",
              "/api/settings/test/email",
              token,
              Map.of(
                  "email", "dev@example.com",
                  "template", "password-reset"));

      assertEquals(204, response.statusCode());
      assertTrue(smtp.message().contains("Reset password request"));
      assertTrue(smtp.message().contains("dev@example.com"));
    }
  }

  @Test
  void authRequestsUseCollectionTemplatesAndConfiguredSmtp() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    JsonNode collection =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name",
                "mail_users",
                "type",
                "auth",
                "otp",
                Map.of(
                    "enabled",
                    true,
                    "emailTemplate",
                    Map.of(
                        "subject", "OTP for {APP_NAME}",
                        "body", "<p>OTP {OTP} id {OTP_ID} for {RECORD:email}</p>")),
                "resetPasswordTemplate",
                Map.of(
                    "subject", "Reset {APP_NAME}",
                    "body", "<p>Reset token {TOKEN} for {RECORD:email} at {APP_URL}</p>")));
    assertEquals(180, collection.get("otp").get("duration").asInt());
    assertEquals(8, collection.get("otp").get("length").asInt());
    assertEquals(600, collection.get("mfa").get("duration").asInt());
    assertEquals(432_000, collection.get("authToken").get("duration").asInt());
    assertEquals(86_400, collection.get("verificationToken").get("duration").asInt());
    assertTrue(collection.get("authAlert").get("enabled").asBoolean());

    request(
        "POST",
        "/api/collections/mail_users/records",
        token,
        Map.of(
            "email", "mail@example.com",
            "password", "Secret_456",
            "passwordConfirm", "Secret_456",
            "verified", true));

    try (FakeSmtpServer smtp = FakeSmtpServer.start()) {
      request(
          "PATCH",
          "/api/settings",
          token,
          Map.of(
              "meta",
              Map.of(
                  "appName", "Mail App",
                  "appURL", "https://app.example.com",
                  "senderName", "Mail App",
                  "senderAddress", "noreply@example.com"),
              "smtp",
              Map.of("enabled", true, "host", "127.0.0.1", "port", smtp.port(), "tls", false)));
      assertEquals(
          204,
          rawRequest(
              "POST",
              "/api/collections/mail_users/request-password-reset",
              null,
              Map.of("email", "mail@example.com"))
              .statusCode());
      String message = smtp.message();
      assertTrue(message.contains("Reset token ey"));
      assertTrue(message.contains("mail@example.com"));
      assertTrue(message.contains("https://app.example.com"));
    }

    try (FakeSmtpServer smtp = FakeSmtpServer.start()) {
      request(
          "PATCH",
          "/api/settings",
          token,
          Map.of(
              "smtp",
              Map.of("enabled", true, "host", "127.0.0.1", "port", smtp.port(), "tls", false)));
      JsonNode otp =
          request(
              "POST",
              "/api/collections/mail_users/request-otp",
              null,
              Map.of("email", "mail@example.com"));
      String message = smtp.message();
      assertTrue(message.contains("id " + otp.get("otpId").asText()));
      assertTrue(message.matches("(?s).*OTP [0-9]{8} id .*"));
      assertTrue(message.contains("mail@example.com"));
    }

    Path outbox = tempDir.resolve("auth_requests.json");
    if (Files.exists(outbox)) {
      assertEquals(
          0,
          mapper.readTree(outbox.toFile()).size(),
          "SMTP delivery must not leak action tokens or OTP codes into the development outbox");
    }
  }

  @Test
  void authAlertsTrackOriginsAndNotifyOnlyForNewLocations() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "alert_users",
            "type", "auth",
            "authAlert",
            Map.of(
                "enabled",
                true,
                "emailTemplate",
                Map.of(
                    "subject", "New login for {APP_NAME}",
                    "body", "<p>{ALERT_INFO} for {RECORD:email}</p>"))));
    JsonNode user =
        request(
            "POST",
            "/api/collections/alert_users/records",
            token,
            Map.of(
                "email", "alert@example.com",
                "password", "Secret_456",
                "passwordConfirm", "Secret_456",
                "verified", true));
    String recordId = user.get("id").asText();

    requestWithHeaders(
        "POST",
        "/api/collections/alert_users/auth-with-password",
        null,
        Map.of(
            "identity", "alert@example.com",
            "password", "Secret_456"),
        Map.of("User-Agent", "Device-A"));
    assertEquals(1, authOriginCount("alert_users", recordId));
    assertEquals(0, authOutboxCount("authAlert", "alert@example.com"));

    server.close();
    start();
    token = loginToken();
    requestWithHeaders(
        "POST",
        "/api/collections/alert_users/auth-with-password",
        null,
        Map.of(
            "identity", "alert@example.com",
            "password", "Secret_456"),
        Map.of("User-Agent", "Device-A"));
    assertEquals(1, authOriginCount("alert_users", recordId));

    try (FakeSmtpServer smtp = FakeSmtpServer.start()) {
      request(
          "PATCH",
          "/api/settings",
          token,
          Map.of(
              "meta",
              Map.of(
                  "appName", "Alert App",
                  "senderAddress", "noreply@example.com"),
              "smtp",
              Map.of("enabled", true, "host", "127.0.0.1", "port", smtp.port(), "tls", false)));
      requestWithHeaders(
          "POST",
          "/api/collections/alert_users/auth-with-password",
          null,
          Map.of(
              "identity", "alert@example.com",
              "password", "Secret_456"),
          Map.of("User-Agent", "Device-B"));
      String message = smtp.message();
      assertTrue(message.contains("Device-B"));
      assertTrue(message.contains("alert@example.com"));
    }
    assertEquals(2, authOriginCount("alert_users", recordId));

    request("PATCH", "/api/settings", token, Map.of("smtp", Map.of("enabled", false)));
    for (String device : List.of("Device-C", "Device-D", "Device-E", "Device-F")) {
      requestWithHeaders(
          "POST",
          "/api/collections/alert_users/auth-with-password",
          null,
          Map.of(
              "identity", "alert@example.com",
              "password", "Secret_456"),
          Map.of("User-Agent", device));
    }
    assertEquals(5, authOriginCount("alert_users", recordId));

    request(
        "PATCH",
        "/api/collections/alert_users/records/" + recordId,
        token,
        Map.of(
            "password", "Updated_456",
            "passwordConfirm", "Updated_456"));
    assertEquals(0, authOriginCount("alert_users", recordId));

    requestWithHeaders(
        "POST",
        "/api/collections/alert_users/auth-with-password",
        null,
        Map.of(
            "identity", "alert@example.com",
            "password", "Updated_456"),
        Map.of("User-Agent", "Device-G"));
    assertEquals(1, authOriginCount("alert_users", recordId));
  }

  @Test
  void authAndManageRulesControlLoginAndAuthRecordMutations() throws Exception {
    start();
    bootstrapSuperuser();
    String superuser = loginToken();

    JsonNode managedCollection =
        request(
            "POST",
            "/api/collections",
            superuser,
            Map.of(
                "name", "managed_users",
                "type", "auth",
                "viewRule", "id = @request.auth.id || @request.auth.email = 'manager@example.com'",
                "updateRule",
                "id = @request.auth.id || @request.auth.email = 'manager@example.com'",
                "authRule", "verified = true",
                "manageRule", "@request.auth.email = 'manager@example.com'",
                "fields", List.of(Map.of("name", "displayName", "type", "text"))));
    for (String tokenConfig : List.of(
        "authToken",
        "passwordResetToken",
        "emailChangeToken",
        "verificationToken",
        "fileToken")) {
      assertEquals("", managedCollection.path(tokenConfig).path("secret").asText());
    }
    JsonNode manager =
        request(
            "POST",
            "/api/collections/managed_users/records",
            superuser,
            Map.of(
                "email", "manager@example.com",
                "password", "Manager_456",
                "passwordConfirm", "Manager_456",
                "verified", true,
                "displayName", "Manager"));
    JsonNode user =
        request(
            "POST",
            "/api/collections/managed_users/records",
            superuser,
            Map.of(
                "email", "user@example.com",
                "password", "UserPass_456",
                "passwordConfirm", "UserPass_456",
                "verified", false,
                "displayName", "User"));

    server.close();
    start();
    superuser = loginToken();
    JsonNode persisted = request("GET", "/api/collections/managed_users", superuser, null);
    assertEquals("verified = true", persisted.get("authRule").asText());
    assertEquals(
        "@request.auth.email = 'manager@example.com'", persisted.get("manageRule").asText());

    HttpResponse<String> invalidAuthRule =
        rawRequest(
            "PATCH",
            "/api/collections/managed_users",
            superuser,
            Map.of("authRule", "missing != ''"));
    assertFieldError(
        invalidAuthRule,
        400,
        "Failed to update collection.",
        "authRule",
        "validation_invalid_value",
        "Unknown field `missing`.");
    HttpResponse<String> emptyManageRule =
        rawRequest("PATCH", "/api/collections/managed_users", superuser, Map.of("manageRule", ""));
    assertFieldError(
        emptyManageRule,
        400,
        "Failed to update collection.",
        "manageRule",
        "validation_invalid_value",
        "Rule cannot be empty.");

    HttpResponse<String> blockedLogin =
        rawRequest(
            "POST",
            "/api/collections/managed_users/auth-with-password",
            null,
            Map.of(
                "identity", "user@example.com",
                "password", "UserPass_456"));
    assertEquals(403, blockedLogin.statusCode());
    assertErrorEnvelope(
        blockedLogin,
        403,
        "The request doesn't satisfy the collection requirements to authenticate.");

    JsonNode managerAuth =
        request(
            "POST",
            "/api/collections/managed_users/auth-with-password",
            null,
            Map.of(
                "identity", "manager@example.com",
                "password", "Manager_456"));
    String managerToken = managerAuth.get("token").asText();
    server.close();
    server = null;
    start();
    superuser = loginToken();
    assertTrue(
        request("POST", "/api/collections/managed_users/auth-refresh", managerToken, null)
            .hasNonNull("token"));

    request(
        "PATCH",
        "/api/collections/managed_users",
        superuser,
        Map.of("updateRule", "id = @request.auth.id"));
    HttpResponse<String> managerBlockedByUpdateRule =
        rawRequest(
            "PATCH",
            "/api/collections/managed_users/records/" + user.get("id").asText(),
            managerToken,
            Map.of("displayName", "Not allowed"));
    assertEquals(404, managerBlockedByUpdateRule.statusCode());

    request(
        "PATCH",
        "/api/collections/managed_users",
        superuser,
        Map.of(
            "updateRule", "id = @request.auth.id || @request.auth.email = 'manager@example.com'"));
    request(
        "PATCH",
        "/api/collections/managed_users/records/" + user.get("id").asText(),
        managerToken,
        Map.of(
            "email", "user-next@example.com",
            "verified", true,
            "password", "Managed_456",
            "passwordConfirm", "Managed_456"));

    JsonNode userAuth =
        request(
            "POST",
            "/api/collections/managed_users/auth-with-password",
            null,
            Map.of(
                "identity", "user-next@example.com",
                "password", "Managed_456"));
    String userToken = userAuth.get("token").asText();

    request("PATCH", "/api/collections/managed_users", superuser, Map.of("viewRule", ""));
    assertEquals(
        "user-next@example.com",
        request(
            "GET",
            "/api/collections/managed_users/records/" + user.get("id").asText(),
            managerToken,
            null)
            .get("email")
            .asText());
    assertEquals(
        "user-next@example.com",
        request(
            "GET",
            "/api/collections/managed_users/records/" + user.get("id").asText(),
            userToken,
            null)
            .get("email")
            .asText());
    assertFalse(
        request(
            "GET",
            "/api/collections/managed_users/records/" + manager.get("id").asText(),
            userToken,
            null)
            .has("email"));

    HttpResponse<String> directEmailChange =
        rawRequest(
            "PATCH",
            "/api/collections/managed_users/records/" + user.get("id").asText(),
            userToken,
            Map.of("email", "forbidden@example.com"));
    assertEquals(400, directEmailChange.statusCode());
    assertTrue(mapper.readTree(directEmailChange.body()).get("data").has("email"));

    HttpResponse<String> missingOldPassword =
        rawRequest(
            "PATCH",
            "/api/collections/managed_users/records/" + user.get("id").asText(),
            userToken,
            Map.of("password", "SelfChange_456", "passwordConfirm", "SelfChange_456"));
    assertEquals(400, missingOldPassword.statusCode());
    assertTrue(mapper.readTree(missingOldPassword.body()).get("data").has("oldPassword"));

    request(
        "PATCH",
        "/api/collections/managed_users/records/" + user.get("id").asText(),
        userToken,
        Map.of(
            "password", "SelfChange_456",
            "passwordConfirm", "SelfChange_456",
            "oldPassword", "Managed_456"));
    JsonNode changedAuth =
        request(
            "POST",
            "/api/collections/managed_users/auth-with-password",
            null,
            Map.of(
                "identity", "user-next@example.com",
                "password", "SelfChange_456"));

    ObjectNode denyAllAuth = mapper.createObjectNode();
    denyAllAuth.putNull("authRule");
    request("PATCH", "/api/collections/managed_users", superuser, denyAllAuth);
    assertTrue(
        request("GET", "/api/collections/managed_users", superuser, null).get("authRule").isNull());
    assertEquals(
        401,
        rawRequest(
            "POST",
            "/api/collections/managed_users/auth-refresh",
            changedAuth.get("token").asText(),
            null)
            .statusCode());
    server.close();
    server = null;
    start();
    assertTrue(
        request("GET", "/api/collections/managed_users", superuser, null).get("authRule").isNull());
    assertEquals(
        401,
        rawRequest(
            "POST",
            "/api/collections/managed_users/auth-refresh",
            changedAuth.get("token").asText(),
            null)
            .statusCode());
    assertEquals(
        403,
        rawRequest(
            "POST",
            "/api/collections/managed_users/auth-with-password",
            null,
            Map.of(
                "identity", "manager@example.com",
                "password", "Manager_456"))
            .statusCode());
    request(
        "PATCH",
        "/api/collections/managed_users",
        superuser,
        Map.of("options", Map.of("authRule", "")));
    assertTrue(
        request(
            "POST",
            "/api/collections/managed_users/auth-with-password",
            null,
            Map.of(
                "identity", "manager@example.com",
                "password", "Manager_456"))
            .hasNonNull("token"));
    String freshUserToken =
        request(
            "POST",
            "/api/collections/managed_users/auth-with-password",
            null,
            Map.of(
                "identity", "user-next@example.com",
                "password", "SelfChange_456"))
            .get("token")
            .asText();

    HttpResponse<String> deleteWithoutRule =
        rawRequest(
            "DELETE",
            "/api/collections/managed_users/records/" + user.get("id").asText(),
            freshUserToken,
            null);
    assertEquals(403, deleteWithoutRule.statusCode());
    request(
        "PATCH",
        "/api/collections/managed_users",
        superuser,
        Map.of("deleteRule", "id = @request.auth.id"));
    assertEquals(
        204,
        rawRequest(
            "DELETE",
            "/api/collections/managed_users/records/" + user.get("id").asText(),
            freshUserToken,
            null)
            .statusCode());
    assertFalse(manager.get("id").asText().isBlank());
  }

  @Test
  void requestHeadersReachCrudAuthAndBatchRules() throws Exception {
    start();
    bootstrapSuperuser();
    String superuser = loginToken();
    String headerRule =
        "@request.headers.x-rule-token = 'allow'"
            + " && @request.headers.x-rule-token:isset = true";

    request(
        "POST",
        "/api/collections",
        superuser,
        Map.of(
            "name",
            "header_rule_posts",
            "listRule",
            headerRule,
            "viewRule",
            headerRule,
            "createRule",
            headerRule,
            "updateRule",
            headerRule + " && @request.query.mode = 'edit'",
            "deleteRule",
            headerRule + " && @request.query.mode = 'delete'",
            "fields",
            List.of(Map.of("name", "title", "type", "text", "required", true))));

    assertEquals(
        400,
        rawRequest(
            "POST",
            "/api/collections/header_rule_posts/records",
            null,
            Map.of("title", "blocked"))
            .statusCode());
    JsonNode direct =
        requestWithHeaders(
            "POST",
            "/api/collections/header_rule_posts/records",
            null,
            Map.of("title", "direct"),
            Map.of("X-RULE-TOKEN", "allow"));
    assertEquals(
        0,
        request("GET", "/api/collections/header_rule_posts/records", null, null)
            .get("totalItems")
            .asInt());
    assertEquals(
        1,
        requestWithHeaders(
            "GET",
            "/api/collections/header_rule_posts/records",
            null,
            null,
            Map.of("X-Rule-Token", "allow"))
            .get("totalItems")
            .asInt());
    assertEquals(
        404,
        rawRequest(
            "PATCH",
            "/api/collections/header_rule_posts/records/" + direct.get("id").asText(),
            null,
            Map.of("title", "wrong query"),
            Map.of("x-rule-token", "allow"))
            .statusCode());
    assertEquals(
        "updated",
        requestWithHeaders(
            "PATCH",
            "/api/collections/header_rule_posts/records/"
                + direct.get("id").asText()
                + "?mode=edit",
            null,
            Map.of("title", "updated"),
            Map.of("x-rule-token", "allow"))
            .get("title")
            .asText());
    assertEquals(
        204,
        rawRequest(
            "DELETE",
            "/api/collections/header_rule_posts/records/"
                + direct.get("id").asText()
                + "?mode=delete",
            null,
            null,
            Map.of("X-Rule-Token", "allow"))
            .statusCode());

    JsonNode batch =
        request(
            "POST",
            "/api/batch",
            null,
            Map.of(
                "requests",
                List.of(
                    Map.of(
                        "method",
                        "POST",
                        "url",
                        "/api/collections/header_rule_posts/records",
                        "headers",
                        Map.of("X-Rule-Token", "allow"),
                        "body",
                        Map.of("id", "header_batch", "title", "created")),
                    Map.of(
                        "method",
                        "PATCH",
                        "url",
                        "/api/collections/header_rule_posts/records/header_batch?mode=edit",
                        "headers",
                        Map.of("x-rule-token", "allow"),
                        "body",
                        Map.of("title", "batch updated")),
                    Map.of(
                        "method", "DELETE",
                        "url",
                        "/api/collections/header_rule_posts/records/header_batch?mode=delete",
                        "headers", Map.of("X-RULE-TOKEN", "allow")))));
    assertEquals(3, batch.size());
    assertEquals(204, batch.get(2).get("status").asInt());

    request(
        "POST",
        "/api/collections",
        superuser,
        Map.of(
            "name", "header_auth_users",
            "type", "auth",
            "authRule", "@request.headers.x-auth-token = 'allow'"));
    request(
        "POST",
        "/api/collections/header_auth_users/records",
        superuser,
        Map.of(
            "email", "header-auth@example.com",
            "password", "Secret_456",
            "passwordConfirm", "Secret_456",
            "verified", true));
    Map<String, String> credentials =
        Map.of(
            "identity", "header-auth@example.com",
            "password", "Secret_456");
    assertEquals(
        403,
        rawRequest(
            "POST", "/api/collections/header_auth_users/auth-with-password", null, credentials)
            .statusCode());
    assertTrue(
        requestWithHeaders(
            "POST",
            "/api/collections/header_auth_users/auth-with-password",
            null,
            credentials,
            Map.of("X-Auth-Token", "allow"))
            .hasNonNull("token"));
  }

  @Test
  void requestContextsMatchOfficialRuleExecutionModes() throws Exception {
    start();
    bootstrapSuperuser();
    String superuser = loginToken();

    request(
        "POST",
        "/api/collections",
        superuser,
        Map.of(
            "name", "context_default_records",
            "listRule", "",
            "viewRule", "",
            "createRule", "@request.context = 'default'",
            "fields", List.of(Map.of("name", "title", "type", "text", "required", true))));
    assertEquals(
        "default",
        request(
            "POST",
            "/api/collections/context_default_records/records",
            null,
            Map.of("title", "default"))
            .get("title")
            .asText());

    request(
        "POST",
        "/api/collections",
        superuser,
        Map.of(
            "name", "context_batch_records",
            "listRule", "",
            "viewRule", "",
            "createRule", "@request.context = 'batch'",
            "fields", List.of(Map.of("name", "title", "type", "text", "required", true))));
    assertEquals(
        400,
        rawRequest(
            "POST",
            "/api/collections/context_batch_records/records",
            null,
            Map.of("title", "blocked default"))
            .statusCode());
    JsonNode batch =
        request(
            "POST",
            "/api/batch",
            null,
            Map.of(
                "requests",
                List.of(
                    Map.of(
                        "method", "POST",
                        "url", "/api/collections/context_batch_records/records",
                        "body", Map.of("title", "batch")))));
    assertEquals("batch", batch.get(0).get("body").get("title").asText());

    JsonNode targets =
        request(
            "POST",
            "/api/collections",
            superuser,
            Map.of(
                "name", "context_expand_targets",
                "listRule", "@request.context = 'expand'",
                "viewRule", "@request.context = 'expand'",
                "fields", List.of(Map.of("name", "name", "type", "text", "required", true))));
    JsonNode target =
        request(
            "POST",
            "/api/collections/context_expand_targets/records",
            superuser,
            Map.of("name", "expanded"));
    request(
        "POST",
        "/api/collections",
        superuser,
        Map.of(
            "name", "context_expand_sources",
            "listRule", "",
            "viewRule", "",
            "fields",
            List.of(
                Map.of(
                    "name", "target",
                    "type", "relation",
                    "collectionId", targets.get("id").asText()))));
    JsonNode source =
        request(
            "POST",
            "/api/collections/context_expand_sources/records",
            superuser,
            Map.of("target", target.get("id").asText()));
    assertEquals(
        404,
        rawRequest(
            "GET",
            "/api/collections/context_expand_targets/records/" + target.get("id").asText(),
            null,
            null)
            .statusCode());
    JsonNode expanded =
        request(
            "GET",
            "/api/collections/context_expand_sources/records/"
                + source.get("id").asText()
                + "?expand=target",
            null,
            null);
    assertEquals("expanded", expanded.get("expand").get("target").get("name").asText());

    request(
        "POST",
        "/api/collections",
        superuser,
        Map.of(
            "name", "context_auth_users",
            "type", "auth",
            "authRule", "@request.context = 'password'",
            "otp", Map.of("enabled", true, "duration", 300, "length", 6)));
    request(
        "POST",
        "/api/collections/context_auth_users/records",
        superuser,
        Map.of(
            "email", "context-auth@example.com",
            "password", "Secret_456",
            "passwordConfirm", "Secret_456",
            "verified", true));
    assertTrue(
        request(
            "POST",
            "/api/collections/context_auth_users/auth-with-password",
            null,
            Map.of("identity", "context-auth@example.com", "password", "Secret_456"))
            .hasNonNull("token"));

    request(
        "PATCH",
        "/api/collections/context_auth_users",
        superuser,
        Map.of("authRule", "@request.context = 'otp'"));
    JsonNode otp =
        request(
            "POST",
            "/api/collections/context_auth_users/request-otp",
            null,
            Map.of("email", "context-auth@example.com"));
    String otpId = otp.get("otpId").asText();
    assertTrue(
        request(
            "POST",
            "/api/collections/context_auth_users/auth-with-otp",
            null,
            Map.of(
                "otpId",
                otpId,
                "password",
                otpRequestPassword("context-auth@example.com", otpId)))
            .hasNonNull("token"));
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

    request(
        "PATCH",
        "/api/settings",
        token,
        Map.of("backups", Map.of("cron", "* * * * *", "cronMaxKeep", 1)));
    JsonNode withAutoBackup = request("GET", "/api/crons", token, null);
    assertTrue(cronExists(withAutoBackup, "__pbAutoBackup__", "* * * * *"));

    HttpResponse<String> run = rawRequest("POST", "/api/crons/__pbAutoBackup__", token, null);
    assertEquals(204, run.statusCode());
    assertTrue(waitForAutoBackupCount(1));
  }

  @Test
  void serverCloseWaitsForAcceptedAutoBackupAndPublishesCompleteZip() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "PATCH",
        "/api/settings",
        token,
        Map.of("backups", Map.of("cron", "* * * * *", "cronMaxKeep", 1)));
    assertEquals(204, rawRequest("POST", "/api/crons/__pbAutoBackup__", token, null).statusCode());

    server.close();
    server = null;

    Path backup;
    try (var paths = Files.list(tempDir.resolve("backups"))) {
      backup =
          paths
              .filter(path -> path.getFileName().toString().startsWith("@auto_pb_backup_"))
              .findFirst()
              .orElseThrow();
    }
    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(backup))) {
      assertNotNull(zip.getNextEntry());
    }
    try (var paths = Files.list(tempDir.resolve("backups"))) {
      assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
    }
  }

  @Test
  void sqlApiRunsSuperuserQueriesAndMutatesJsonCollections() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    assertEquals(
        401, rawRequest("POST", "/api/sql", null, Map.of("query", "select 1")).statusCode());

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "sql_users",
            "type", "auth"));
    request(
        "POST",
        "/api/collections/sql_users/records",
        token,
        Map.of(
            "email", "sql-user@example.com",
            "password", "Secret_123",
            "passwordConfirm", "Secret_123",
            "verified", true));
    JsonNode userAuth =
        request(
            "POST",
            "/api/collections/sql_users/auth-with-password",
            null,
            Map.of(
                "identity", "sql-user@example.com",
                "password", "Secret_123"));
    assertEquals(
        403,
        rawRequest("POST", "/api/sql", userAuth.get("token").asText(), Map.of("query", "select 1"))
            .statusCode());

    JsonNode one = request("POST", "/api/sql", token, Map.of("query", "select 1"));
    assertEquals(0, one.get("affectedRows").asInt());
    assertEquals("1", one.get("columns").get(0).get("name").asText());
    assertEquals("1", one.get("rows").get(0).get(0).asText());

    JsonNode second = request("POST", "/api/sql", token, Map.of("query", "select 1; select 2"));
    assertEquals("2", second.get("columns").get(0).get("name").asText());
    assertEquals("2", second.get("rows").get(0).get(0).asText());

    HttpResponse<String> missingQuery = rawRequest("POST", "/api/sql", token, Map.of("query", ""));
    assertEquals(400, missingQuery.statusCode());
    assertFieldError(
        missingQuery,
        400,
        "An error occurred while validating the submitted data.",
        "query",
        "validation_required",
        "Cannot be blank.");

    HttpResponse<String> tooLongQuery =
        rawRequest("POST", "/api/sql", token, Map.of("query", "a".repeat(5001)));
    assertEquals(400, tooLongQuery.statusCode());
    assertFieldError(
        tooLongQuery,
        400,
        "An error occurred while validating the submitted data.",
        "query",
        "validation_invalid_value",
        "query must be at most 5000 characters.");

    HttpResponse<String> arrayPayload = rawJsonRequest("POST", "/api/sql", token, "[]");
    assertEquals(400, arrayPayload.statusCode());
    assertFieldError(
        arrayPayload,
        400,
        "An error occurred while loading the submitted data.",
        "body",
        "validation_invalid_value",
        "Request body must be a JSON object.");

    JsonNode create =
        request(
            "POST",
            "/api/sql",
            token,
            Map.of(
                "query",
                "create table sql_posts(id text primary key, title text not null, published bool, views int)"));
    assertEquals(0, create.get("affectedRows").asInt());
    JsonNode collection = request("GET", "/api/collections/sql_posts", token, null);
    assertEquals("sql_posts", collection.get("name").asText());

    JsonNode inserted =
        request(
            "POST",
            "/api/sql",
            token,
            Map.of(
                "query",
                "insert into sql_posts (id,title,published,views) values ('post_one','Hello SQL',true,7)"));
    assertEquals(1, inserted.get("affectedRows").asInt());

    JsonNode selected =
        request(
            "POST",
            "/api/sql",
            token,
            Map.of(
                "query",
                "select id,title,views from sql_posts where published = true order by created desc limit 1"));
    assertEquals("post_one", selected.get("rows").get(0).get(0).asText());
    assertEquals("Hello SQL", selected.get("rows").get(0).get(1).asText());
    assertEquals("7", selected.get("rows").get(0).get(2).asText());

    JsonNode count =
        request(
            "POST",
            "/api/sql",
            token,
            Map.of("query", "select count(*) as total from sql_posts where title = 'Hello SQL'"));
    assertEquals("total", count.get("columns").get(0).get("name").asText());
    assertEquals("1", count.get("rows").get(0).get(0).asText());

    JsonNode updated =
        request(
            "POST",
            "/api/sql",
            token,
            Map.of(
                "query",
                "update sql_posts set title = 'Updated SQL', views = 8 where id = 'post_one'"));
    assertEquals(1, updated.get("affectedRows").asInt());
    JsonNode afterUpdate =
        request(
            "POST",
            "/api/sql",
            token,
            Map.of("query", "select title,views from sql_posts where id = 'post_one'"));
    assertEquals("Updated SQL", afterUpdate.get("rows").get(0).get(0).asText());
    assertEquals("8", afterUpdate.get("rows").get(0).get(1).asText());

    JsonNode deleted =
        request(
            "POST",
            "/api/sql",
            token,
            Map.of("query", "delete from sql_posts where id = 'post_one'"));
    assertEquals(1, deleted.get("affectedRows").asInt());
    JsonNode empty =
        request(
            "POST", "/api/sql", token, Map.of("query", "select count(*) as total from sql_posts"));
    assertEquals("0", empty.get("rows").get(0).get(0).asText());

    HttpResponse<String> rolledBack =
        rawRequest(
            "POST",
            "/api/sql",
            token,
            Map.of(
                "query",
                "create table sql_tx(id text primary key, title text);"
                    + "insert into sql_tx (id,title) values ('one','ok');"
                    + "invalid"));
    assertEquals(400, rolledBack.statusCode());
    assertMessageAndFieldErrorStartWith(
        rolledBack,
        400,
        "Failed to execute query.",
        "query",
        "validation_invalid_value",
        "The SQL statement could not be executed.");
    assertEquals(404, rawRequest("GET", "/api/collections/sql_tx", token, null).statusCode());
  }

  @Test
  void batchExecutesRecordOperationsAndRollsBackOnFailure() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "batch_posts",
            "listRule", "",
            "viewRule", "",
            "fields", List.of(Map.of("name", "title", "type", "text", "required", true))));

    JsonNode batch =
        request(
            "POST",
            "/api/batch",
            token,
            Map.of(
                "requests",
                List.of(
                    Map.of(
                        "method", "POST",
                        "url", "/api/collections/batch_posts/records",
                        "body", Map.of("id", "batch_one", "title", "created")),
                    Map.of(
                        "method", "PUT",
                        "url", "/api/collections/batch_posts/records/batch_two",
                        "body", Map.of("title", "upserted")),
                    Map.of(
                        "method", "PATCH",
                        "url", "/api/collections/batch_posts/records/batch_one",
                        "body", Map.of("title", "updated")),
                    Map.of(
                        "method", "DELETE",
                        "url", "/api/collections/batch_posts/records/batch_two"))));
    assertTrue(batch.isArray());
    assertEquals(4, batch.size());
    assertEquals(204, batch.get(3).get("status").asInt());

    JsonNode page = request("GET", "/api/collections/batch_posts/records", null, null);
    assertEquals(1, page.get("totalItems").asInt());
    assertEquals("updated", page.get("items").get(0).get("title").asText());

    HttpResponse<String> failed =
        rawRequest(
            "POST",
            "/api/batch",
            token,
            Map.of(
                "requests",
                List.of(
                    Map.of(
                        "method", "POST",
                        "url", "/api/collections/batch_posts/records",
                        "body", Map.of("id", "rollback_me", "title", "rollback")),
                    Map.of(
                        "method", "PATCH",
                        "url", "/api/collections/batch_posts/records/missing",
                        "body", Map.of("title", "fail")))));
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

    JsonNode authCollection =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "batch_auth_users",
                "type", "auth"));
    JsonNode authRecord =
        request(
            "POST",
            "/api/collections/batch_auth_users/records",
            token,
            Map.of(
                "email", "batch-auth@example.com",
                "password", "Secret_456",
                "passwordConfirm", "Secret_456"));
    HttpResponse<String> failedSystemBatch =
        rawRequest(
            "POST",
            "/api/batch",
            token,
            Map.of(
                "requests",
                List.of(
                    Map.of(
                        "method", "POST",
                        "url", "/api/collections/_mfas/records",
                        "body",
                        Map.of(
                            "id",
                            "rollback_mfa",
                            "collectionRef",
                            authCollection.get("id").asText(),
                            "recordRef",
                            authRecord.get("id").asText(),
                            "method",
                            "password")),
                    Map.of(
                        "method", "PATCH",
                        "url", "/api/collections/batch_posts/records/missing",
                        "body", Map.of("title", "fail")))));
    assertEquals(400, failedSystemBatch.statusCode());
    assertEquals(
        404,
        rawRequest("GET", "/api/collections/_mfas/records/rollback_mfa", token, null).statusCode());
  }

  @Test
  void batchValidationErrorsUseOfficialEnvelope() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    HttpResponse<String> missingRequests = rawRequest("POST", "/api/batch", token, Map.of());
    assertEquals(400, missingRequests.statusCode());
    assertFieldError(
        missingRequests,
        400,
        "Failed to process batch.",
        "requests",
        "validation_required",
        "Cannot be blank.");

    HttpResponse<String> invalidMultipartJson =
        rawMultipartRequest("POST", "/api/batch", token, Map.of("@jsonPayload", "{bad"), Map.of());
    assertEquals(400, invalidMultipartJson.statusCode());
    assertFieldError(
        invalidMultipartJson,
        400,
        "Failed to process batch.",
        "@jsonPayload",
        "validation_invalid_value",
        "Invalid JSON payload.");

    HttpResponse<String> missingMethod =
        rawRequest(
            "POST",
            "/api/batch",
            token,
            Map.of("requests", List.of(Map.of("url", "/api/collections/posts/records"))));
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

    HttpResponse<String> unsupportedTarget =
        rawRequest(
            "POST",
            "/api/batch",
            token,
            Map.of("requests", List.of(Map.of("method", "GET", "url", "/api/settings"))));
    assertEquals(400, unsupportedTarget.statusCode());
    JsonNode unsupportedBody = mapper.readTree(unsupportedTarget.body());
    assertEquals(400, unsupportedBody.get("status").asInt());
    assertEquals("Batch request failed.", unsupportedBody.get("message").asText());
    assertEquals(0, unsupportedBody.get("data").get("index").asInt());
    JsonNode unsupportedResponse = unsupportedBody.get("data").get("response");
    assertEquals(400, unsupportedResponse.get("status").asInt());
    assertEquals(
        "Only record batch requests are supported.", unsupportedResponse.get("message").asText());
    assertEquals(
        "validation_invalid_value",
        unsupportedResponse.get("data").get("url").get("code").asText());
    assertEquals(
        "Only record batch requests are supported.",
        unsupportedResponse.get("data").get("url").get("message").asText());

    HttpResponse<String> malformedTarget =
        rawRequest(
            "POST",
            "/api/batch",
            token,
            Map.of("requests", List.of(Map.of("method", "GET", "url", "%"))));
    assertEquals(400, malformedTarget.statusCode());
    JsonNode malformedBody = mapper.readTree(malformedTarget.body());
    assertEquals(400, malformedBody.get("status").asInt());
    assertEquals("Batch request failed.", malformedBody.get("message").asText());
    assertEquals(0, malformedBody.get("data").get("index").asInt());
    JsonNode malformedResponse = malformedBody.get("data").get("response");
    assertEquals(400, malformedResponse.get("status").asInt());
    assertEquals("Batch request failed.", malformedResponse.get("message").asText());
    assertEquals(
        "validation_invalid_value", malformedResponse.get("data").get("url").get("code").asText());
    assertEquals(
        "Invalid batch request URL.",
        malformedResponse.get("data").get("url").get("message").asText());
  }

  @Test
  void multipartBatchUploadsFilesAndRollsBackStorageOnFailure() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "batch_assets",
            "listRule", "",
            "viewRule", "",
            "fields",
            List.of(
                Map.of("name", "title", "type", "text", "required", true),
                Map.of("name", "attachment", "type", "file", "required", true))));

    String payload =
        """
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
    JsonNode batch =
        multipartRequest(
            "POST",
            "/api/batch",
            token,
            Map.of("@jsonPayload", payload),
            Map.of(
                "requests.0.attachment",
                new MultipartFile(
                    "batch upload.txt",
                    "text/plain",
                    "hello from multipart batch".getBytes(StandardCharsets.UTF_8))));

    assertTrue(batch.isArray());
    JsonNode body = batch.get(0).get("body");
    assertEquals(200, batch.get(0).get("status").asInt());
    assertEquals("batch_asset_one", body.get("id").asText());
    String filename = body.get("attachment").asText();
    assertTrue(filename.startsWith("batch_upload_"));

    HttpResponse<String> file =
        rawRequest("GET", "/api/files/batch_assets/batch_asset_one/" + filename, null, null);
    assertEquals(200, file.statusCode());
    assertEquals("hello from multipart batch", file.body());

    String failedPayload =
        """
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
    HttpResponse<String> failed =
        rawMultipartRequest(
            "POST",
            "/api/batch",
            token,
            Map.of("@jsonPayload", failedPayload),
            Map.of(
                "requests[0].attachment",
                new MultipartFile(
                    "rollback file.txt",
                    "text/plain",
                    "this file must be rolled back".getBytes(StandardCharsets.UTF_8))));
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

    HttpResponse<String> rolledBackRecord =
        rawRequest("GET", "/api/collections/batch_assets/records/rollback_asset", token, null);
    assertEquals(404, rolledBackRecord.statusCode());
    assertFalse(storageContainsFilename("rollback_file_"));
  }

  @Test
  void relationExpandResolvesVisibleRecords() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    JsonNode authors =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "authors",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(Map.of("name", "name", "type", "text", "required", true))));
    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "posts",
            "listRule", "",
            "viewRule", "",
            "fields",
            List.of(
                Map.of("name", "title", "type", "text", "required", true),
                Map.of(
                    "name",
                    "author",
                    "type",
                    "relation",
                    "collectionId",
                    authors.get("id").asText()))));
    JsonNode author =
        request("POST", "/api/collections/authors/records", token, Map.of("name", "Ada"));
    JsonNode post =
        request(
            "POST",
            "/api/collections/posts/records",
            token,
            Map.of("title", "Expandable", "author", author.get("id").asText()));

    JsonNode page = request("GET", "/api/collections/posts/records?expand=author", null, null);
    JsonNode expanded = page.get("items").get(0).get("expand").get("author");
    assertEquals(author.get("id").asText(), expanded.get("id").asText());
    assertEquals("Ada", expanded.get("name").asText());

    JsonNode single =
        request(
            "GET",
            "/api/collections/posts/records/" + post.get("id").asText() + "?expand=author",
            null,
            null);
    assertEquals("Ada", single.get("expand").get("author").get("name").asText());
  }

  @Test
  void structuredFieldValuesRoundtripAsOfficialJsonTypes() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    JsonNode people =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "typed_people",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(Map.of("name", "name", "type", "text", "required", true))));
    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "typed_posts",
            "listRule", "",
            "viewRule", "",
            "fields",
            List.of(
                Map.of("name", "title", "type", "text", "required", true),
                Map.of(
                    "name",
                    "labels",
                    "type",
                    "select",
                    "options",
                    Map.of("values", List.of("alpha", "beta", "gamma"), "maxSelect", 3)),
                Map.of(
                    "name",
                    "authors",
                    "type",
                    "relation",
                    "collectionId",
                    people.get("id").asText(),
                    "maxSelect",
                    3),
                Map.of("name", "meta", "type", "json"),
                Map.of("name", "location", "type", "geoPoint"),
                Map.of("name", "published", "type", "bool"))));
    JsonNode ada =
        request("POST", "/api/collections/typed_people/records", token, Map.of("name", "Ada"));
    JsonNode linus =
        request("POST", "/api/collections/typed_people/records", token, Map.of("name", "Linus"));

    JsonNode created =
        request(
            "POST",
            "/api/collections/typed_posts/records",
            token,
            Map.of(
                "title", "Typed payload",
                "labels", List.of("alpha", "beta"),
                "authors", List.of(ada.get("id").asText(), linus.get("id").asText()),
                "meta", Map.of("rating", 5, "tags", List.of("x", "y")),
                "location", Map.of("lat", 12.34, "lon", 56.78),
                "published", true));

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

    JsonNode authors =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "field_authors",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(Map.of("name", "name", "type", "text", "required", true))));
    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "field_posts",
            "listRule", "",
            "viewRule", "",
            "fields",
            List.of(
                Map.of("name", "title", "type", "text", "required", true),
                Map.of("name", "body", "type", "text"),
                Map.of(
                    "name",
                    "author",
                    "type",
                    "relation",
                    "collectionId",
                    authors.get("id").asText()))));
    JsonNode author =
        request("POST", "/api/collections/field_authors/records", token, Map.of("name", "Ada"));
    JsonNode post =
        request(
            "POST",
            "/api/collections/field_posts/records",
            token,
            Map.of(
                "title", "Fields",
                "body", "Hidden by fields",
                "author", author.get("id").asText()));

    JsonNode page =
        request("GET", "/api/collections/field_posts/records?fields=id,title", null, null);
    JsonNode item = page.get("items").get(0);
    assertEquals(post.get("id").asText(), item.get("id").asText());
    assertEquals("Fields", item.get("title").asText());
    assertFalse(item.has("body"));
    assertFalse(item.has("collectionName"));

    JsonNode single =
        request(
            "GET",
            "/api/collections/field_posts/records/"
                + post.get("id").asText()
                + "?expand=author&fields=id,expand.author.name",
            null,
            null);
    assertEquals(post.get("id").asText(), single.get("id").asText());
    assertFalse(single.has("title"));
    assertEquals("Ada", single.get("expand").get("author").get("name").asText());
    assertFalse(single.get("expand").get("author").has("id"));

    JsonNode created =
        request(
            "POST",
            "/api/collections/field_posts/records" + "?expand=author&fields=id,expand.author.name",
            token,
            Map.of(
                "title", "Created fields",
                "body", "Hidden create body",
                "author", author.get("id").asText()));
    assertTrue(created.hasNonNull("id"));
    assertFalse(created.has("title"));
    assertEquals("Ada", created.get("expand").get("author").get("name").asText());
    assertFalse(created.get("expand").get("author").has("id"));

    JsonNode updated =
        request(
            "PATCH",
            "/api/collections/field_posts/records/" + post.get("id").asText() + "?fields=id,title",
            token,
            Map.of(
                "title", "Updated fields",
                "body", "Still hidden by fields"));
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

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "news",
            "listRule", "",
            "viewRule", "",
            "fields", List.of(Map.of("name", "categoryId", "type", "text", "required", true))));
    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "categories",
            "listRule", "@collection.news.categoryId ?= id",
            "viewRule", "@collection.news.categoryId ?= id",
            "fields", List.of(Map.of("name", "name", "type", "text", "required", true))));

    JsonNode visible =
        request(
            "POST",
            "/api/collections/categories/records",
            token,
            Map.of(
                "id", "cat_visible",
                "name", "Visible"));
    JsonNode hidden =
        request(
            "POST",
            "/api/collections/categories/records",
            token,
            Map.of(
                "id", "cat_hidden",
                "name", "Hidden"));
    request(
        "POST",
        "/api/collections/news/records",
        token,
        Map.of("title", "Published", "categoryId", visible.get("id").asText()));

    JsonNode page = request("GET", "/api/collections/categories/records", null, null);
    assertEquals(1, page.get("totalItems").asInt());
    assertEquals(visible.get("id").asText(), page.get("items").get(0).get("id").asText());

    JsonNode single =
        request(
            "GET", "/api/collections/categories/records/" + visible.get("id").asText(), null, null);
    assertEquals("Visible", single.get("name").asText());

    HttpResponse<String> hiddenResponse =
        rawRequest(
            "GET", "/api/collections/categories/records/" + hidden.get("id").asText(), null, null);
    assertEquals(404, hiddenResponse.statusCode());
  }

  @Test
  void authCollectionsHashPasswordsAndRejectDuplicateEmail() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "users",
            "type", "auth",
            "fields", List.of(Map.of("name", "displayName", "type", "text"))));
    request(
        "POST",
        "/api/collections/users/records",
        token,
        Map.of(
            "email", "demo@example.com",
            "password", "Secret_456",
            "passwordConfirm", "Secret_456",
            "displayName", "Demo"));

    JsonNode auth =
        request(
            "POST",
            "/api/collections/users/auth-with-password",
            null,
            Map.of(
                "identity", "demo@example.com",
                "password", "Secret_456"));
    assertTrue(auth.hasNonNull("token"));
    assertFalse(auth.get("record").has("password"));
    HttpResponse<String> idAsIdentity =
        rawRequest(
            "POST",
            "/api/collections/users/auth-with-password",
            null,
            Map.of("identity", auth.get("record").get("id").asText(), "password", "Secret_456"));
    assertEquals(400, idAsIdentity.statusCode());
    assertErrorEnvelope(idAsIdentity, 400, "Failed to authenticate.");
    HttpResponse<String> disabledIdentityField =
        rawRequest(
            "POST",
            "/api/collections/users/auth-with-password",
            null,
            Map.of(
                "identityField", "username",
                "identity", "demo@example.com",
                "password", "Secret_456"));
    assertEquals(400, disabledIdentityField.statusCode());
    assertErrorEnvelope(disabledIdentityField, 400, "Failed to authenticate.");

    HttpResponse<String> duplicate =
        rawRequest(
            "POST",
            "/api/collections/users/records",
            token,
            Map.of(
                "email", "demo@example.com",
                "password", "another-secret",
                "passwordConfirm", "another-secret"));
    assertEquals(400, duplicate.statusCode());
    assertFieldError(
        duplicate,
        400,
        "Failed to create record.",
        "email",
        "validation_not_unique",
        "Value must be unique.");

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

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "otp_users",
            "type", "auth",
            "otp",
            Map.of(
                "enabled", true,
                "duration", 300,
                "length", 6)));
    JsonNode user =
        request(
            "POST",
            "/api/collections/otp_users/records",
            token,
            Map.of(
                "email", "otp@example.com",
                "password", "Secret_456",
                "passwordConfirm", "Secret_456",
                "verified", false));

    JsonNode methods = request("GET", "/api/collections/otp_users/auth-methods", null, null);
    assertTrue(methods.get("password").get("enabled").asBoolean());
    assertTrue(methods.get("otp").get("enabled").asBoolean());
    assertEquals(300, methods.get("otp").get("duration").asInt());

    JsonNode missingUserOtp =
        request(
            "POST",
            "/api/collections/otp_users/request-otp",
            null,
            Map.of("email", "missing@example.com"));
    assertTrue(missingUserOtp.hasNonNull("otpId"));

    JsonNode otpRequest =
        request(
            "POST",
            "/api/collections/otp_users/request-otp",
            null,
            Map.of("email", "otp@example.com"));
    String otpId = otpRequest.get("otpId").asText();
    String otpPassword = otpRequestPassword("otp@example.com", otpId);

    HttpResponse<String> wrongOtpPassword =
        rawRequest(
            "POST",
            "/api/collections/otp_users/auth-with-otp",
            null,
            Map.of("otpId", otpId, "password", "000000"));
    assertEquals(400, wrongOtpPassword.statusCode());
    assertFieldError(
        wrongOtpPassword,
        400,
        "Invalid or expired OTP.",
        "otpId",
        "validation_invalid_value",
        "Invalid or expired OTP.");

    JsonNode auth =
        request(
            "POST",
            "/api/collections/otp_users/auth-with-otp",
            null,
            Map.of(
                "otpId", otpId,
                "password", otpPassword));
    assertTrue(auth.hasNonNull("token"));
    assertEquals(user.get("id").asText(), auth.get("record").get("id").asText());

    JsonNode verified =
        request(
            "GET", "/api/collections/otp_users/records/" + user.get("id").asText(), token, null);
    assertTrue(verified.get("verified").asBoolean());
    HttpResponse<String> passwordAfterOtp =
        rawRequest(
            "POST",
            "/api/collections/otp_users/auth-with-password",
            null,
            Map.of(
                "identity", "otp@example.com",
                "password", "Secret_456"));
    assertEquals(400, passwordAfterOtp.statusCode());
    assertErrorEnvelope(passwordAfterOtp, 400, "Failed to authenticate.");

    HttpResponse<String> reusedOtp =
        rawRequest(
            "POST",
            "/api/collections/otp_users/auth-with-otp",
            null,
            Map.of(
                "otpId", otpId,
                "password", otpPassword));
    assertEquals(400, reusedOtp.statusCode());
    assertFieldError(
        reusedOtp,
        400,
        "Invalid or expired OTP.",
        "otpId",
        "validation_invalid_value",
        "Invalid or expired OTP.");

    JsonNode lockedOtp =
        request(
            "POST",
            "/api/collections/otp_users/request-otp",
            null,
            Map.of("email", "otp@example.com"));
    String lockedOtpId = lockedOtp.get("otpId").asText();
    for (int i = 0; i < 5; i++) {
      HttpResponse<String> failedOtpAttempt =
          rawRequest(
              "POST",
              "/api/collections/otp_users/auth-with-otp",
              null,
              Map.of("otpId", lockedOtpId, "password", "00000" + i));
      assertEquals(400, failedOtpAttempt.statusCode());
      assertFieldError(
          failedOtpAttempt,
          400,
          "Invalid or expired OTP.",
          "otpId",
          "validation_invalid_value",
          "Invalid or expired OTP.");
    }
    HttpResponse<String> lockedOtpAttempt =
        rawRequest(
            "POST",
            "/api/collections/otp_users/auth-with-otp",
            null,
            Map.of("otpId", lockedOtpId, "password", "123456"));
    assertEquals(429, lockedOtpAttempt.statusCode());
    assertFieldError(
        lockedOtpAttempt,
        429,
        "Too many failed OTP attempts.",
        "otpId",
        "validation_invalid_value",
        "Too many failed OTP attempts.");
  }

  @Test
  void authMethodsReflectConfiguredPasswordOtpMfaAndOauth2() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    HttpResponse<String> missingCollection =
        rawRequest("GET", "/api/collections/missing_auth_methods/auth-methods", null, null);
    assertEquals(404, missingCollection.statusCode());
    assertErrorEnvelope(missingCollection, 404, "Collection not found.");

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "auth_methods_base",
            "type", "base",
            "fields", List.of()));
    HttpResponse<String> baseCollection =
        rawRequest("GET", "/api/collections/auth_methods_base/auth-methods", null, null);
    assertEquals(404, baseCollection.statusCode());
    assertErrorEnvelope(baseCollection, 404, "The requested resource wasn't found.");

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "auth_methods_disabled",
            "type", "auth",
            "passwordAuth", Map.of("enabled", false, "identityFields", List.of("email")),
            "otp",
            Map.of(
                "enabled", false,
                "duration", 420,
                "length", 8),
            "mfa", Map.of("enabled", false, "duration", 900),
            "oauth2", Map.of("enabled", false, "providers", List.of(Map.of("name", "github")))));
    JsonNode disabledMethods =
        request("GET", "/api/collections/auth_methods_disabled/auth-methods", null, null);
    assertFalse(disabledMethods.get("password").get("enabled").asBoolean());
    assertTrue(disabledMethods.get("password").get("identityFields").isArray());
    assertEquals(0, disabledMethods.get("password").get("identityFields").size());
    assertFalse(disabledMethods.get("emailPassword").asBoolean());
    assertFalse(disabledMethods.get("usernamePassword").asBoolean());
    assertFalse(disabledMethods.get("otp").get("enabled").asBoolean());
    assertEquals(0, disabledMethods.get("otp").get("duration").asInt());
    assertFalse(disabledMethods.get("otp").has("length"));
    assertFalse(disabledMethods.get("mfa").get("enabled").asBoolean());
    assertEquals(0, disabledMethods.get("mfa").get("duration").asInt());
    assertFalse(disabledMethods.get("oauth2").get("enabled").asBoolean());
    assertEquals(0, disabledMethods.get("oauth2").get("providers").size());
    assertEquals(0, disabledMethods.get("authProviders").size());

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "auth_config_users",
            "type", "auth",
            "fields", List.of(Map.of("name", "username", "type", "text")),
            "indexes",
            List.of(
                "CREATE UNIQUE INDEX idx_auth_config_username ON auth_config_users (username)"),
            "passwordAuth", Map.of("enabled", true, "identityFields", List.of("email", "username")),
            "otp",
            Map.of(
                "enabled", true,
                "duration", 420,
                "length", 8),
            "mfa", Map.of("enabled", true, "duration", 900),
            "oauth2",
            Map.of(
                "enabled",
                true,
                "providers",
                List.of(
                    Map.of(
                        "name", "github",
                        "clientId", "github-client",
                        "clientSecret", "github-secret"),
                    Map.of(
                        "name", "google",
                        "clientId", "google-client",
                        "clientSecret", "google-secret")))));

    JsonNode methods =
        request("GET", "/api/collections/auth_config_users/auth-methods", null, null);
    assertTrue(methods.get("password").get("enabled").asBoolean());
    assertEquals(2, methods.get("password").get("identityFields").size());
    assertTrue(methods.get("usernamePassword").asBoolean());
    assertTrue(methods.get("emailPassword").asBoolean());
    assertTrue(methods.get("otp").get("enabled").asBoolean());
    assertEquals(420, methods.get("otp").get("duration").asInt());
    assertFalse(methods.get("otp").has("length"));
    assertTrue(methods.get("mfa").get("enabled").asBoolean());
    assertEquals(900, methods.get("mfa").get("duration").asInt());
    assertTrue(methods.get("oauth2").get("enabled").asBoolean());
    assertEquals(2, methods.get("oauth2").get("providers").size());
    JsonNode oauthProvider = methods.get("oauth2").get("providers").get(0);
    assertEquals("github", oauthProvider.get("name").asText());
    assertEquals("GitHub", oauthProvider.get("displayName").asText());
    assertTrue(oauthProvider.get("logo").asText().startsWith("<svg"));
    assertFalse(oauthProvider.get("state").asText().isBlank());
    assertTrue(
        oauthProvider
            .get("authURL")
            .asText()
            .startsWith("https://github.com/login/oauth/authorize?"));
    assertTrue(oauthProvider.get("authURL").asText().contains("client_id=github-client"));
    assertTrue(oauthProvider.get("authURL").asText().contains("scope=read%3Auser%20user%3Aemail"));
    assertTrue(oauthProvider.get("authURL").asText().endsWith("&redirect_uri="));
    assertEquals(oauthProvider.get("authURL").asText(), oauthProvider.get("authUrl").asText());
    assertFalse(oauthProvider.get("codeVerifier").asText().isBlank());
    assertFalse(oauthProvider.get("codeChallenge").asText().isBlank());
    assertEquals("S256", oauthProvider.get("codeChallengeMethod").asText());
    assertEquals(2, methods.get("authProviders").size());
    JsonNode legacyProvider = methods.get("authProviders").get(0);
    assertEquals(oauthProvider.get("name").asText(), legacyProvider.get("name").asText());
    assertEquals("", legacyProvider.get("logo").asText());
    assertEquals(oauthProvider.get("state").asText(), legacyProvider.get("state").asText());
    assertEquals(oauthProvider.get("authURL").asText(), legacyProvider.get("authURL").asText());

    JsonNode collections =
        request(
            "GET",
            "/api/collections?filter="
                + URLEncoder.encode("name = 'auth_config_users'", StandardCharsets.UTF_8),
            token,
            null);
    JsonNode listed = collections.get("items").get(0);
    assertTrue(listed.get("oauth2").get("enabled").asBoolean());
    assertEquals("github", listed.get("oauth2").get("providers").get(0).get("name").asText());
  }

  @Test
  void collectionPatchMergesOAuth2ProviderConfigurationByName() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name",
            "oauth2_provider_merge_users",
            "type",
            "auth",
            "oauth2",
            Map.of(
                "enabled",
                true,
                "providers",
                List.of(
                    Map.of(
                        "name",
                        "github",
                        "clientId",
                        "github-client",
                        "clientSecret",
                        "github-secret"),
                    Map.of(
                        "name",
                        "google",
                        "clientId",
                        "google-client",
                        "clientSecret",
                        "google-secret",
                        "tokenURL",
                        "https://example.test/token",
                        "displayName",
                        "Original Google",
                        "scopes",
                        List.of("openid", "email"),
                        "pkce",
                        true,
                        "extra",
                        Map.of("tenant", "before"))))));

    HttpResponse<String> response =
        rawJsonRequest(
            "PATCH",
            "/api/collections/oauth2_provider_merge_users",
            token,
            """
            {
              "oauth2": {
                "providers": [
                  {"name": "apple", "clientId": "apple-client", "clientSecret": "apple-secret"},
                  {"pkce": null, "name": "google", "authURL": "", "displayName": "Updated Google", "extra": {}}
                ]
              }
            }
            """);
    assertEquals(200, response.statusCode(), response.body());

    JsonNode providers = mapper.readTree(response.body()).path("oauth2").path("providers");
    assertEquals(2, providers.size());
    assertEquals("apple", providers.get(0).path("name").asText());
    assertEquals("google", providers.get(1).path("name").asText());
    assertEquals("google-client", providers.get(1).path("clientId").asText());
    assertEquals("https://example.test/token", providers.get(1).path("tokenURL").asText());
    assertEquals("Updated Google", providers.get(1).path("displayName").asText());
    assertEquals(2, providers.get(1).path("scopes").size());
    assertFalse(providers.get(1).path("pkce").asBoolean());
    assertEquals(0, providers.get(1).path("extra").size());

    JsonNode retained =
        request(
            "PATCH",
            "/api/collections/oauth2_provider_merge_users",
            token,
            Map.of("oauth2", Map.of("enabled", false)));
    assertFalse(retained.path("oauth2").path("enabled").asBoolean());
    assertEquals(2, retained.path("oauth2").path("providers").size());

    JsonNode cleared =
        request(
            "PATCH",
            "/api/collections/oauth2_provider_merge_users",
            token,
            Map.of("oauth2", Map.of("providers", List.of())));
    assertEquals(0, cleared.path("oauth2").path("providers").size());
  }

  @Test
  void malformedSurrogateRecordValueIsMangledWithoutTruncatingJsonResponse() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();
    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name",
            "invalid_utf8_records",
            "type",
            "base",
            "fields",
            List.of(Map.of("name", "title", "type", "text"))));

    byte[] prefix = "{\"title\":\"bad \\".getBytes(StandardCharsets.US_ASCII);
    byte[] suffix = "uD800\"}".getBytes(StandardCharsets.US_ASCII);
    byte[] payload = java.util.Arrays.copyOf(prefix, prefix.length + suffix.length);
    System.arraycopy(suffix, 0, payload, prefix.length, suffix.length);
    HttpResponse<String> created =
        rawBodyRequest(
            "POST",
            "/api/collections/invalid_utf8_records/records",
            token,
            "application/json",
            payload);
    assertEquals(200, created.statusCode(), created.body());
    JsonNode createdRecord = mapper.readTree(created.body());
    assertEquals("bad \uFFFD", createdRecord.path("title").asText());

    JsonNode fetched =
        request(
            "GET",
            "/api/collections/invalid_utf8_records/records/" + createdRecord.path("id").asText(),
            token,
            null);
    assertEquals("bad \uFFFD", fetched.path("title").asText());
  }

  @Test
  void authCollectionOptionsValidateIdentityFieldsAndMfaMethods() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    HttpResponse<String> missingIdentity =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "missing_identity_users",
                "type", "auth",
                "passwordAuth", Map.of("enabled", true, "identityFields", List.of("missing"))));
    assertEquals(400, missingIdentity.statusCode());
    assertEquals(
        "validation_missing_field",
        mapper
            .readTree(missingIdentity.body())
            .get("data")
            .get("passwordAuth")
            .get("identityFields")
            .get("code")
            .asText());

    HttpResponse<String> nonUniqueIdentity =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name",
                "nonunique_identity_users",
                "type",
                "auth",
                "fields",
                List.of(Map.of("name", "handle", "type", "text")),
                "passwordAuth",
                Map.of("enabled", true, "identityFields", List.of("handle"))));
    assertEquals(400, nonUniqueIdentity.statusCode());
    assertEquals(
        "validation_missing_unique_constraint",
        mapper
            .readTree(nonUniqueIdentity.body())
            .get("data")
            .get("passwordAuth")
            .get("identityFields")
            .get("code")
            .asText());

    HttpResponse<String> emptyIdentity =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "empty_identity_users",
                "type", "auth",
                "passwordAuth", Map.of("enabled", true, "identityFields", List.of())));
    assertEquals(400, emptyIdentity.statusCode());
    assertEquals(
        "validation_required",
        mapper
            .readTree(emptyIdentity.body())
            .get("data")
            .get("passwordAuth")
            .get("identityFields")
            .get("code")
            .asText());

    HttpResponse<String> insufficientMfa =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "insufficient_mfa_users",
                "type", "auth",
                "mfa", Map.of("enabled", true, "duration", 600)));
    assertEquals(400, insufficientMfa.statusCode());
    assertEquals(
        "validation_mfa_not_enough_auths",
        mapper
            .readTree(insufficientMfa.body())
            .get("data")
            .get("mfa")
            .get("enabled")
            .get("code")
            .asText());

    HttpResponse<String> invalidOtp =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "invalid_otp_options_users",
                "type", "auth",
                "otp",
                Map.of(
                    "enabled",
                    true,
                    "duration",
                    9,
                    "length",
                    3,
                    "emailTemplate",
                    Map.of("subject", "", "body", ""))));
    assertEquals(400, invalidOtp.statusCode());
    JsonNode invalidOtpErrors = mapper.readTree(invalidOtp.body()).get("data").get("otp");
    assertEquals(
        "validation_min_greater_equal_than_required",
        invalidOtpErrors.get("duration").get("code").asText());
    assertEquals(10, invalidOtpErrors.get("duration").get("params").get("threshold").asInt());
    assertEquals(
        "validation_min_greater_equal_than_required",
        invalidOtpErrors.get("length").get("code").asText());
    assertEquals(4, invalidOtpErrors.get("length").get("params").get("threshold").asInt());
    assertEquals(
        "validation_required",
        invalidOtpErrors.get("emailTemplate").get("subject").get("code").asText());
    assertEquals(
        "validation_required",
        invalidOtpErrors.get("emailTemplate").get("body").get("code").asText());

    JsonNode disabledOutOfRangeOtp =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "disabled_out_of_range_otp_users",
                "type", "auth",
                "otp", Map.of("enabled", false, "duration", 9, "length", 3)));
    assertFalse(disabledOutOfRangeOtp.get("otp").get("enabled").asBoolean());
    assertEquals(9, disabledOutOfRangeOtp.get("otp").get("duration").asInt());
    assertEquals(3, disabledOutOfRangeOtp.get("otp").get("length").asInt());

    HttpResponse<String> invalidOtpEnableUpdate =
        rawRequest(
            "PATCH",
            "/api/collections/disabled_out_of_range_otp_users",
            token,
            Map.of("otp", Map.of("enabled", true)));
    assertEquals(400, invalidOtpEnableUpdate.statusCode());
    JsonNode invalidOtpEnableErrors =
        mapper.readTree(invalidOtpEnableUpdate.body()).get("data").get("otp");
    assertEquals(
        "validation_min_greater_equal_than_required",
        invalidOtpEnableErrors.get("duration").get("code").asText());
    assertEquals(
        "validation_min_greater_equal_than_required",
        invalidOtpEnableErrors.get("length").get("code").asText());
    JsonNode otpAfterRejectedUpdate =
        request("GET", "/api/collections/disabled_out_of_range_otp_users", token, null);
    assertFalse(otpAfterRejectedUpdate.get("otp").get("enabled").asBoolean());
    assertEquals(9, otpAfterRejectedUpdate.get("otp").get("duration").asInt());
    assertEquals(3, otpAfterRejectedUpdate.get("otp").get("length").asInt());

    HttpResponse<String> invalidMfaDuration =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name",
                "invalid_mfa_duration_users",
                "type",
                "auth",
                "otp",
                Map.of("enabled", true, "duration", 300, "length", 6),
                "mfa",
                Map.of("enabled", true, "duration", 86_401)));
    assertEquals(400, invalidMfaDuration.statusCode());
    JsonNode invalidMfaDurationError =
        mapper.readTree(invalidMfaDuration.body()).get("data").get("mfa").get("duration");
    assertEquals(
        "validation_max_less_equal_than_required", invalidMfaDurationError.get("code").asText());
    assertEquals(86_400, invalidMfaDurationError.get("params").get("threshold").asInt());

    HttpResponse<String> invalidMfaRule =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name",
                "invalid_mfa_rule_users",
                "type",
                "auth",
                "otp",
                Map.of("enabled", true, "duration", 300, "length", 6),
                "mfa",
                Map.of("enabled", true, "duration", 600, "rule", "(")));
    assertEquals(400, invalidMfaRule.statusCode());
    assertEquals(
        "validation_invalid_value",
        mapper
            .readTree(invalidMfaRule.body())
            .get("data")
            .get("mfa")
            .get("rule")
            .get("code")
            .asText());

    Map<String, String> blankTemplate = Map.of("subject", "", "body", "");
    HttpResponse<String> invalidMailTemplates =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name",
                "invalid_mail_templates_users",
                "type",
                "auth",
                "otp",
                Map.of("enabled", false, "emailTemplate", blankTemplate),
                "authAlert",
                Map.of("enabled", false, "emailTemplate", blankTemplate),
                "verificationTemplate",
                blankTemplate,
                "resetPasswordTemplate",
                blankTemplate,
                "confirmEmailChangeTemplate",
                blankTemplate));
    assertEquals(400, invalidMailTemplates.statusCode());
    JsonNode invalidMailTemplateErrors = mapper.readTree(invalidMailTemplates.body()).get("data");
    assertEquals(
        "validation_required",
        invalidMailTemplateErrors
            .get("otp")
            .get("emailTemplate")
            .get("subject")
            .get("code")
            .asText());
    assertEquals(
        "validation_required",
        invalidMailTemplateErrors
            .get("authAlert")
            .get("emailTemplate")
            .get("body")
            .get("code")
            .asText());
    assertEquals(
        "validation_required",
        invalidMailTemplateErrors.get("verificationTemplate").get("subject").get("code").asText());
    assertEquals(
        "validation_required",
        invalidMailTemplateErrors.get("resetPasswordTemplate").get("body").get("code").asText());
    assertEquals(
        "validation_required",
        invalidMailTemplateErrors
            .get("confirmEmailChangeTemplate")
            .get("subject")
            .get("code")
            .asText());

    String validTokenSecret = "s".repeat(30);
    HttpResponse<String> invalidTokenConfigs =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "invalid_token_config_users",
                "type", "auth",
                "authToken", Map.of("secret", "a".repeat(29), "duration", 9),
                "passwordResetToken", Map.of("secret", validTokenSecret, "duration", 94_670_857),
                "emailChangeToken", Map.of("secret", "e".repeat(256), "duration", 10),
                "verificationToken", Map.of("secret", validTokenSecret, "duration", 0),
                "fileToken", Map.of("secret", validTokenSecret, "duration", 9)));
    assertEquals(400, invalidTokenConfigs.statusCode());
    JsonNode invalidTokenErrors = mapper.readTree(invalidTokenConfigs.body()).get("data");
    assertEquals(
        "validation_length_out_of_range",
        invalidTokenErrors.get("authToken").get("secret").get("code").asText());
    assertEquals(
        30, invalidTokenErrors.get("authToken").get("secret").get("params").get("min").asInt());
    assertEquals(
        255, invalidTokenErrors.get("authToken").get("secret").get("params").get("max").asInt());
    assertEquals(
        "validation_min_greater_equal_than_required",
        invalidTokenErrors.get("authToken").get("duration").get("code").asText());
    assertEquals(
        "validation_max_less_equal_than_required",
        invalidTokenErrors.get("passwordResetToken").get("duration").get("code").asText());
    assertEquals(
        94_670_856,
        invalidTokenErrors
            .get("passwordResetToken")
            .get("duration")
            .get("params")
            .get("threshold")
            .asInt());
    assertEquals(
        "validation_length_out_of_range",
        invalidTokenErrors.get("emailChangeToken").get("secret").get("code").asText());
    assertEquals(
        "validation_required",
        invalidTokenErrors.get("verificationToken").get("duration").get("code").asText());
    assertEquals(
        "validation_min_greater_equal_than_required",
        invalidTokenErrors.get("fileToken").get("duration").get("code").asText());

    JsonNode tokenValidationCollection =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "token_config_validation_users",
                "type", "auth"));
    HttpResponse<String> invalidTokenUpdate =
        rawRequest(
            "PATCH",
            "/api/collections/token_config_validation_users",
            token,
            Map.of("authToken", Map.of("duration", 9)));
    assertEquals(400, invalidTokenUpdate.statusCode());
    assertEquals(
        "validation_min_greater_equal_than_required",
        mapper
            .readTree(invalidTokenUpdate.body())
            .get("data")
            .get("authToken")
            .get("duration")
            .get("code")
            .asText());
    JsonNode tokenAfterRejectedUpdate =
        request("GET", "/api/collections/token_config_validation_users", token, null);
    assertEquals(432_000, tokenAfterRejectedUpdate.get("authToken").get("duration").asInt());

    HttpResponse<String> invalidTokenImport =
        rawRequest(
            "PUT",
            "/api/collections/import",
            token,
            Map.of(
                "collections",
                List.of(
                    Map.of(
                        "id", tokenValidationCollection.get("id").asText(),
                        "name", "token_config_validation_users",
                        "type", "auth",
                        "fields", tokenValidationCollection.get("fields"),
                        "indexes", tokenValidationCollection.get("indexes"),
                        "passwordAuth", tokenValidationCollection.get("passwordAuth"),
                        "fileToken", Map.of("secret", "f".repeat(29), "duration", 180)))));
    assertEquals(400, invalidTokenImport.statusCode());
    assertEquals(
        "validation_length_out_of_range",
        mapper
            .readTree(invalidTokenImport.body())
            .get("data")
            .get("fileToken")
            .get("secret")
            .get("code")
            .asText());
    JsonNode tokenAfterRejectedImport =
        request("GET", "/api/collections/token_config_validation_users", token, null);
    assertEquals(180, tokenAfterRejectedImport.get("fileToken").get("duration").asInt());

    HttpResponse<String> invalidOtpImport =
        rawRequest(
            "PUT",
            "/api/collections/import",
            token,
            Map.of(
                "collections",
                List.of(
                    Map.of(
                        "id", disabledOutOfRangeOtp.get("id").asText(),
                        "name", "disabled_out_of_range_otp_users",
                        "type", "auth",
                        "fields", disabledOutOfRangeOtp.get("fields"),
                        "indexes", disabledOutOfRangeOtp.get("indexes"),
                        "passwordAuth", disabledOutOfRangeOtp.get("passwordAuth"),
                        "otp",
                        Map.of(
                            "enabled",
                            true,
                            "duration",
                            300,
                            "length",
                            6,
                            "emailTemplate",
                            blankTemplate)))));
    assertEquals(400, invalidOtpImport.statusCode());
    assertEquals(
        "validation_required",
        mapper
            .readTree(invalidOtpImport.body())
            .get("data")
            .get("otp")
            .get("emailTemplate")
            .get("body")
            .get("code")
            .asText());
    JsonNode otpAfterRejectedImport =
        request("GET", "/api/collections/disabled_out_of_range_otp_users", token, null);
    assertFalse(otpAfterRejectedImport.get("otp").get("enabled").asBoolean());
    assertEquals(9, otpAfterRejectedImport.get("otp").get("duration").asInt());
    assertEquals(3, otpAfterRejectedImport.get("otp").get("length").asInt());

    JsonNode disabledInvalidOauth =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "disabled_invalid_oauth_users",
                "type", "auth",
                "oauth2",
                Map.of(
                    "enabled",
                    false,
                    "providers",
                    List.of(
                        Map.of(
                            "name", "missing",
                            "authURL", "!invalid!")))));
    assertEquals(
        "missing", disabledInvalidOauth.get("oauth2").get("providers").get(0).get("name").asText());

    HttpResponse<String> missingOauthCredentials =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "missing_oauth_credentials_users",
                "type", "auth",
                "oauth2", Map.of("enabled", true, "providers", List.of(Map.of("name", "github")))));
    assertEquals(400, missingOauthCredentials.statusCode());
    JsonNode missingOauthCredentialErrors =
        mapper
            .readTree(missingOauthCredentials.body())
            .get("data")
            .get("oauth2")
            .get("providers")
            .get("0");
    assertEquals(
        "validation_required", missingOauthCredentialErrors.get("clientId").get("code").asText());
    assertEquals(
        "validation_required",
        missingOauthCredentialErrors.get("clientSecret").get("code").asText());

    HttpResponse<String> missingOauthProvider =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "missing_oauth_provider_users",
                "type", "auth",
                "oauth2",
                Map.of(
                    "enabled",
                    true,
                    "providers",
                    List.of(
                        Map.of(
                            "name", "missing",
                            "clientId", "client-id",
                            "clientSecret", "client-secret")))));
    assertEquals(400, missingOauthProvider.statusCode());
    assertEquals(
        "validation_missing_provider",
        mapper
            .readTree(missingOauthProvider.body())
            .get("data")
            .get("oauth2")
            .get("providers")
            .get("0")
            .get("name")
            .get("code")
            .asText());

    HttpResponse<String> duplicateOauthProvider =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "duplicate_oauth_provider_users",
                "type", "auth",
                "oauth2",
                Map.of(
                    "enabled",
                    true,
                    "providers",
                    List.of(
                        Map.of(
                            "name",
                            "github",
                            "clientId",
                            "first",
                            "clientSecret",
                            "first-secret"),
                        Map.of(
                            "name",
                            "github",
                            "clientId",
                            "second",
                            "clientSecret",
                            "second-secret")))));
    assertEquals(400, duplicateOauthProvider.statusCode());
    assertEquals(
        "validation_duplicated_provider",
        mapper
            .readTree(duplicateOauthProvider.body())
            .get("data")
            .get("oauth2")
            .get("providers")
            .get("1")
            .get("name")
            .get("code")
            .asText());

    HttpResponse<String> invalidOauthUrls =
        rawRequest(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "invalid_oauth_urls_users",
                "type", "auth",
                "oauth2",
                Map.of(
                    "enabled",
                    true,
                    "providers",
                    List.of(
                        Map.of(
                            "name", "github",
                            "clientId", "client-id",
                            "clientSecret", "client-secret",
                            "authURL", "!invalid!",
                            "tokenURL", "relative/path",
                            "userInfoURL", "https:///missing-host")))));
    assertEquals(400, invalidOauthUrls.statusCode());
    JsonNode invalidOauthUrlErrors =
        mapper
            .readTree(invalidOauthUrls.body())
            .get("data")
            .get("oauth2")
            .get("providers")
            .get("0");
    assertEquals("validation_is_url", invalidOauthUrlErrors.get("authURL").get("code").asText());
    assertEquals("validation_is_url", invalidOauthUrlErrors.get("tokenURL").get("code").asText());
    assertEquals(
        "validation_is_url", invalidOauthUrlErrors.get("userInfoURL").get("code").asText());

    JsonNode oauthValidationCollection =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "oauth_validation_users",
                "type", "auth",
                "oauth2",
                Map.of(
                    "enabled",
                    true,
                    "providers",
                    List.of(
                        Map.of(
                            "name", "github",
                            "clientId", "client-id",
                            "clientSecret", "client-secret")))));
    HttpResponse<String> invalidOauthUpdate =
        rawRequest(
            "PATCH",
            "/api/collections/oauth_validation_users",
            token,
            Map.of(
                "oauth2",
                Map.of(
                    "enabled",
                    true,
                    "providers",
                    List.of(
                        Map.of(
                            "name", "github", "clientId", "first", "clientSecret", "first-secret"),
                        Map.of(
                            "name",
                            "github",
                            "clientId",
                            "second",
                            "clientSecret",
                            "second-secret")))));
    assertEquals(400, invalidOauthUpdate.statusCode());
    assertEquals(
        "validation_duplicated_provider",
        mapper
            .readTree(invalidOauthUpdate.body())
            .get("data")
            .get("oauth2")
            .get("providers")
            .get("1")
            .get("name")
            .get("code")
            .asText());
    JsonNode oauthAfterRejectedUpdate =
        request("GET", "/api/collections/oauth_validation_users", token, null);
    assertEquals(1, oauthAfterRejectedUpdate.get("oauth2").get("providers").size());
    assertEquals(
        "github",
        oauthAfterRejectedUpdate.get("oauth2").get("providers").get(0).get("name").asText());

    JsonNode customIdentity =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "custom_identity_users",
                "type", "auth",
                "fields", List.of(Map.of("name", "handle", "type", "text", "required", true)),
                "indexes",
                List.of(
                    "CREATE UNIQUE INDEX idx_custom_identity_handle ON custom_identity_users (handle)"),
                "passwordAuth", Map.of("enabled", true, "identityFields", List.of("handle"))));
    assertEquals(
        "handle", customIdentity.get("passwordAuth").get("identityFields").get(0).asText());

    JsonNode record =
        request(
            "POST",
            "/api/collections/custom_identity_users/records",
            token,
            Map.of(
                "email", "custom-identity@example.com",
                "handle", "custom-login",
                "password", "Secret_456",
                "passwordConfirm", "Secret_456"));
    JsonNode auth =
        request(
            "POST",
            "/api/collections/custom_identity_users/auth-with-password",
            null,
            Map.of(
                "identity", "custom-login",
                "password", "Secret_456"));
    assertEquals(record.get("id").asText(), auth.get("record").get("id").asText());

    HttpResponse<String> invalidUpdate =
        rawRequest(
            "PATCH",
            "/api/collections/custom_identity_users",
            token,
            Map.of("passwordAuth", Map.of("enabled", true, "identityFields", List.of("missing"))));
    assertEquals(400, invalidUpdate.statusCode());
    assertEquals(
        "validation_missing_field",
        mapper
            .readTree(invalidUpdate.body())
            .get("data")
            .get("passwordAuth")
            .get("identityFields")
            .get("code")
            .asText());

    HttpResponse<String> invalidImport =
        rawRequest(
            "PUT",
            "/api/collections/import",
            token,
            Map.of(
                "collections",
                List.of(
                    Map.of(
                        "id", customIdentity.get("id").asText(),
                        "name", "custom_identity_users",
                        "type", "auth",
                        "fields", customIdentity.get("fields"),
                        "indexes", customIdentity.get("indexes"),
                        "passwordAuth",
                        Map.of("enabled", true, "identityFields", List.of("missing"))))));
    assertEquals(400, invalidImport.statusCode());
    assertEquals(
        "validation_missing_field",
        mapper
            .readTree(invalidImport.body())
            .get("data")
            .get("passwordAuth")
            .get("identityFields")
            .get("code")
            .asText());

    HttpResponse<String> invalidOauthImport =
        rawRequest(
            "PUT",
            "/api/collections/import",
            token,
            Map.of(
                "collections",
                List.of(
                    Map.of(
                        "id", oauthValidationCollection.get("id").asText(),
                        "name", "oauth_validation_users",
                        "type", "auth",
                        "fields", oauthValidationCollection.get("fields"),
                        "indexes", oauthValidationCollection.get("indexes"),
                        "passwordAuth", oauthValidationCollection.get("passwordAuth"),
                        "oauth2",
                        Map.of(
                            "enabled",
                            true,
                            "providers",
                            List.of(
                                Map.of(
                                    "name", "missing",
                                    "clientId", "client-id",
                                    "clientSecret", "client-secret")))))));
    assertEquals(400, invalidOauthImport.statusCode());
    assertEquals(
        "validation_missing_provider",
        mapper
            .readTree(invalidOauthImport.body())
            .get("data")
            .get("oauth2")
            .get("providers")
            .get("0")
            .get("name")
            .get("code")
            .asText());
    JsonNode oauthAfterRejectedImport =
        request("GET", "/api/collections/oauth_validation_users", token, null);
    assertEquals(
        "github",
        oauthAfterRejectedImport.get("oauth2").get("providers").get(0).get("name").asText());
  }

  @Test
  void authCollectionTokenDurationsDriveIssuedJwtTtls() throws Exception {
    start();
    bootstrapSuperuser();
    String superuserToken = loginToken();

    request(
        "POST",
        "/api/collections",
        superuserToken,
        Map.of(
            "name", "token_users",
            "type", "auth",
            "authToken", Map.of("duration", 61),
            "passwordResetToken", Map.of("duration", 91),
            "verificationToken", Map.of("duration", 121),
            "emailChangeToken", Map.of("duration", 151),
            "fileToken", Map.of("duration", 181)));
    JsonNode user =
        request(
            "POST",
            "/api/collections/token_users/records",
            superuserToken,
            Map.of(
                "email", "token-user@example.com",
                "password", "Secret_456",
                "passwordConfirm", "Secret_456",
                "verified", false));

    JsonNode auth =
        request(
            "POST",
            "/api/collections/token_users/auth-with-password",
            null,
            Map.of(
                "identity", "token-user@example.com",
                "password", "Secret_456"));
    assertTokenLifetime(auth.get("token").asText(), 61);

    JsonNode refreshed =
        request(
            "POST", "/api/collections/token_users/auth-refresh", auth.get("token").asText(), null);
    assertTokenLifetime(refreshed.get("token").asText(), 61);

    JsonNode fileToken = request("POST", "/api/files/token", auth.get("token").asText(), null);
    assertTokenLifetime(fileToken.get("token").asText(), 181);

    request(
        "POST",
        "/api/collections/token_users/request-password-reset",
        null,
        Map.of("email", "token-user@example.com"));
    assertTokenLifetime(authRequestToken("passwordReset", "token-user@example.com"), 91);

    request(
        "POST",
        "/api/collections/token_users/request-verification",
        null,
        Map.of("email", "token-user@example.com"));
    assertTokenLifetime(authRequestToken("verification", "token-user@example.com"), 121);

    JsonNode verifiedAuth =
        request(
            "POST",
            "/api/collections/token_users/auth-with-password",
            null,
            Map.of(
                "identity", "token-user@example.com",
                "password", "Secret_456"));
    request(
        "POST",
        "/api/collections/token_users/request-email-change",
        verifiedAuth.get("token").asText(),
        Map.of("newEmail", "token-user-next@example.com"));
    assertTokenLifetime(authRequestToken("emailChange", "token-user@example.com"), 151);

    JsonNode impersonated =
        request(
            "POST",
            "/api/collections/token_users/impersonate/" + user.get("id").asText(),
            superuserToken,
            Map.of());
    assertTokenLifetime(impersonated.get("token").asText(), 61);
  }

  @Test
  void rotatingCollectionTokenSecretsInvalidatesIssuedAuthFileAndResetTokens() throws Exception {
    start();
    bootstrapSuperuser();
    String superuserToken = loginToken();
    String authSecretA = "a".repeat(30);
    String authSecretB = "b".repeat(30);
    String resetSecretA = "r".repeat(30);
    String resetSecretB = "s".repeat(30);
    String fileSecretA = "f".repeat(30);
    String fileSecretB = "g".repeat(30);

    request(
        "POST",
        "/api/collections",
        superuserToken,
        Map.of(
            "name", "secret_users",
            "type", "auth",
            "authToken", Map.of("duration", 61, "secret", authSecretA),
            "passwordResetToken", Map.of("duration", 91, "secret", resetSecretA),
            "fileToken", Map.of("duration", 181, "secret", fileSecretA)));
    JsonNode user =
        request(
            "POST",
            "/api/collections/secret_users/records",
            superuserToken,
            Map.of(
                "email", "secret-user@example.com",
                "password", "Secret_456",
                "passwordConfirm", "Secret_456",
                "verified", true));

    JsonNode auth =
        request(
            "POST",
            "/api/collections/secret_users/auth-with-password",
            null,
            Map.of(
                "identity", "secret-user@example.com",
                "password", "Secret_456"));
    String authToken = auth.get("token").asText();

    request(
        "PATCH",
        "/api/collections/secret_users",
        superuserToken,
        Map.of("authToken", Map.of("duration", 61, "secret", "")));
    assertEquals(
        200,
        rawRequest("POST", "/api/collections/secret_users/auth-refresh", authToken, null)
            .statusCode());

    request(
        "PATCH",
        "/api/collections/secret_users",
        superuserToken,
        Map.of(
            "id", auth.get("record").get("collectionId").asText(),
            "name", "secret_users",
            "type", "auth",
            "authToken", Map.of("duration", 61, "secret", authSecretB),
            "passwordResetToken", Map.of("duration", 91, "secret", resetSecretA),
            "fileToken", Map.of("duration", 181, "secret", fileSecretA)));
    assertEquals(
        401,
        rawRequest("POST", "/api/collections/secret_users/auth-refresh", authToken, null)
            .statusCode());

    JsonNode freshAuth =
        request(
            "POST",
            "/api/collections/secret_users/auth-with-password",
            null,
            Map.of(
                "identity", "secret-user@example.com",
                "password", "Secret_456"));
    String freshToken = freshAuth.get("token").asText();

    request(
        "POST",
        "/api/collections",
        superuserToken,
        Map.of(
            "name", "secret_assets",
            "listRule", "owner = @request.auth.id",
            "viewRule", "owner = @request.auth.id",
            "fields",
            List.of(
                Map.of("name", "owner", "type", "text", "required", true),
                Map.of(
                    "name",
                    "attachment",
                    "type",
                    "file",
                    "required",
                    true,
                    "protected",
                    true))));
    JsonNode asset =
        multipartRequest(
            "POST",
            "/api/collections/secret_assets/records",
            superuserToken,
            Map.of("owner", user.get("id").asText()),
            Map.of(
                "attachment",
                new MultipartFile(
                    "secret.txt",
                    "text/plain",
                    "secret payload".getBytes(StandardCharsets.UTF_8))));
    String filename = asset.get("attachment").asText();
    String filePath = "/api/files/secret_assets/" + asset.get("id").asText() + "/" + filename;

    JsonNode fileToken = request("POST", "/api/files/token", freshToken, null);
    assertEquals(
        200,
        rawRequest("GET", filePath + "?token=" + fileToken.get("token").asText(), null, null)
            .statusCode());

    request(
        "PATCH",
        "/api/collections/secret_users",
        superuserToken,
        Map.of(
            "id", freshAuth.get("record").get("collectionId").asText(),
            "name", "secret_users",
            "type", "auth",
            "authToken", Map.of("duration", 61, "secret", authSecretB),
            "passwordResetToken", Map.of("duration", 91, "secret", resetSecretA),
            "fileToken", Map.of("duration", 181, "secret", fileSecretB)));
    assertEquals(
        404,
        rawRequest("GET", filePath + "?token=" + fileToken.get("token").asText(), null, null)
            .statusCode());

    request(
        "POST",
        "/api/collections/secret_users/request-password-reset",
        null,
        Map.of("email", "secret-user@example.com"));
    String resetToken = authRequestToken("passwordReset", "secret-user@example.com");

    request(
        "PATCH",
        "/api/collections/secret_users",
        superuserToken,
        Map.of(
            "id", freshAuth.get("record").get("collectionId").asText(),
            "name", "secret_users",
            "type", "auth",
            "authToken", Map.of("duration", 61, "secret", authSecretB),
            "passwordResetToken", Map.of("duration", 91, "secret", resetSecretB),
            "fileToken", Map.of("duration", 181, "secret", fileSecretB)));
    HttpResponse<String> staleResetToken =
        rawRequest(
            "POST",
            "/api/collections/secret_users/confirm-password-reset",
            null,
            Map.of(
                "token", resetToken,
                "password", "NewSecret_456",
                "passwordConfirm", "NewSecret_456"));
    assertEquals(400, staleResetToken.statusCode());
    assertFieldError(
        staleResetToken,
        400,
        "Invalid or expired token.",
        "token",
        "validation_invalid_value",
        "Invalid or expired token.");
  }

  @Test
  void expiredAndWrongCollectionTokensAreRejected() throws Exception {
    start();
    bootstrapSuperuser();
    String superuserToken = loginToken();

    request(
        "POST",
        "/api/collections",
        superuserToken,
        Map.of(
            "name",
            "timed_users",
            "type",
            "auth",
            "authToken",
            Map.of("duration", 10),
            "passwordResetToken",
            Map.of("duration", 10)));
    request(
        "POST",
        "/api/collections",
        superuserToken,
        Map.of(
            "name", "other_users",
            "type", "auth"));
    request(
        "POST",
        "/api/collections/timed_users/records",
        superuserToken,
        Map.of(
            "email", "timed@example.com",
            "password", "Secret_456",
            "passwordConfirm", "Secret_456",
            "verified", true));
    request(
        "POST",
        "/api/collections/other_users/records",
        superuserToken,
        Map.of(
            "email", "other@example.com",
            "password", "Secret_456",
            "passwordConfirm", "Secret_456",
            "verified", true));

    JsonNode auth =
        request(
            "POST",
            "/api/collections/timed_users/auth-with-password",
            null,
            Map.of(
                "identity", "timed@example.com",
                "password", "Secret_456"));
    String expiredAuthToken = expiredToken(auth.get("token").asText(), "timed_users", "authToken");
    assertEquals(
        401,
        rawRequest("POST", "/api/collections/timed_users/auth-refresh", expiredAuthToken, null)
            .statusCode());

    request(
        "POST",
        "/api/collections/timed_users/request-password-reset",
        null,
        Map.of("email", "timed@example.com"));
    String resetToken = authRequestToken("passwordReset", "timed@example.com");
    HttpResponse<String> wrongCollectionReset =
        rawRequest(
            "POST",
            "/api/collections/other_users/confirm-password-reset",
            null,
            Map.of(
                "token", resetToken,
                "password", "NewSecret_456",
                "passwordConfirm", "NewSecret_456"));
    assertEquals(400, wrongCollectionReset.statusCode());
    assertFieldError(
        wrongCollectionReset,
        400,
        "Invalid or expired token.",
        "token",
        "validation_invalid_value",
        "Invalid or expired token.");
    String expiredResetToken = expiredToken(resetToken, "timed_users", "passwordResetToken");
    HttpResponse<String> expiredReset =
        rawRequest(
            "POST",
            "/api/collections/timed_users/confirm-password-reset",
            null,
            Map.of(
                "token", expiredResetToken,
                "password", "NewSecret_456",
                "passwordConfirm", "NewSecret_456"));
    assertEquals(400, expiredReset.statusCode());
    assertFieldError(
        expiredReset,
        400,
        "Invalid or expired token.",
        "token",
        "validation_invalid_value",
        "Invalid or expired token.");
  }

  @Test
  void mfaRequiresADifferentSecondAuthMethodAcrossPasswordOtpAndOauth2() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name",
            "mfa_users",
            "type",
            "auth",
            "mfa",
            Map.of("enabled", true, "duration", 900, "rule", "true"),
            "otp",
            Map.of(
                "enabled", true,
                "duration", 300,
                "length", 6)));
    request(
        "POST",
        "/api/collections/mfa_users/records",
        token,
        Map.of(
            "email", "mfa@example.com",
            "password", "Secret_456",
            "passwordConfirm", "Secret_456",
            "verified", true));

    HttpResponse<String> passwordFirst =
        rawRequest(
            "POST",
            "/api/collections/mfa_users/auth-with-password",
            null,
            Map.of(
                "identity", "mfa@example.com",
                "password", "Secret_456"));
    assertEquals(401, passwordFirst.statusCode(), passwordFirst.body());
    String passwordMfaId = mapper.readTree(passwordFirst.body()).get("mfaId").asText();

    HttpResponse<String> samePasswordMethod =
        rawRequest(
            "POST",
            "/api/collections/mfa_users/auth-with-password",
            null,
            Map.of(
                "identity", "mfa@example.com",
                "password", "Secret_456",
                "mfaId", passwordMfaId));
    assertEquals(400, samePasswordMethod.statusCode());
    assertFieldError(
        samePasswordMethod,
        400,
        "MFA requires a different auth method.",
        "mfaId",
        "validation_invalid_value",
        "MFA requires a different auth method.");

    JsonNode otpRequest =
        request(
            "POST",
            "/api/collections/mfa_users/request-otp",
            null,
            Map.of("email", "mfa@example.com"));
    String otpId = otpRequest.get("otpId").asText();
    String otpPassword = otpRequestPassword("mfa@example.com", otpId);
    JsonNode passwordCompleted =
        request(
            "POST",
            "/api/collections/mfa_users/auth-with-otp",
            null,
            Map.of(
                "otpId", otpId,
                "password", otpPassword,
                "mfaId", passwordMfaId));
    assertTrue(passwordCompleted.hasNonNull("token"));
    HttpResponse<String> reusedMfaId =
        rawRequest(
            "POST",
            "/api/collections/mfa_users/auth-with-password",
            null,
            Map.of(
                "identity", "mfa@example.com",
                "password", "Secret_456",
                "mfaId", passwordMfaId));
    assertEquals(400, reusedMfaId.statusCode());
    assertFieldError(
        reusedMfaId,
        400,
        "Missing or invalid MFA ID.",
        "mfaId",
        "validation_invalid_value",
        "Missing or invalid MFA ID.");

    JsonNode otpRequest2 =
        request(
            "POST",
            "/api/collections/mfa_users/request-otp",
            null,
            Map.of("email", "mfa@example.com"));
    String otpId2 = otpRequest2.get("otpId").asText();
    String otpPassword2 = otpRequestPassword("mfa@example.com", otpId2);
    HttpResponse<String> otpFirst =
        rawRequest(
            "POST",
            "/api/collections/mfa_users/auth-with-otp",
            null,
            Map.of(
                "otpId", otpId2,
                "password", otpPassword2));
    assertEquals(401, otpFirst.statusCode());
    String otpMfaId = mapper.readTree(otpFirst.body()).get("mfaId").asText();

    JsonNode otpRequest3 =
        request(
            "POST",
            "/api/collections/mfa_users/request-otp",
            null,
            Map.of("email", "mfa@example.com"));
    String otpId3 = otpRequest3.get("otpId").asText();
    String otpPassword3 = otpRequestPassword("mfa@example.com", otpId3);
    HttpResponse<String> sameOtpMethod =
        rawRequest(
            "POST",
            "/api/collections/mfa_users/auth-with-otp",
            null,
            Map.of(
                "otpId", otpId3,
                "password", otpPassword3,
                "mfaId", otpMfaId));
    assertEquals(400, sameOtpMethod.statusCode());
    assertFieldError(
        sameOtpMethod,
        400,
        "MFA requires a different auth method.",
        "mfaId",
        "validation_invalid_value",
        "MFA requires a different auth method.");
    JsonNode otpCompleted =
        request(
            "POST",
            "/api/collections/mfa_users/auth-with-password?mfaId="
                + URLEncoder.encode(otpMfaId, StandardCharsets.UTF_8),
            null,
            Map.of(
                "identity", "mfa@example.com",
                "password", "Secret_456"));
    assertTrue(otpCompleted.hasNonNull("token"));

    try (FakeOAuth2Server oauth = FakeOAuth2Server.start()) {
      JsonNode oauthCollection =
          request(
              "POST",
              "/api/collections",
              token,
              Map.of(
                  "name",
                  "mfa_oauth_users",
                  "type",
                  "auth",
                  "mfa",
                  Map.of("enabled", true, "duration", 900, "rule", "true"),
                  "oauth2",
                  Map.of(
                      "enabled",
                      true,
                      "providers",
                      List.of(
                          Map.of(
                              "name",
                              "oidc",
                              "clientId",
                              "client-123",
                              "clientSecret",
                              "secret-456",
                              "authURL",
                              oauth.baseUrl() + "/authorize",
                              "tokenURL",
                              oauth.baseUrl() + "/token",
                              "userInfoURL",
                              oauth.baseUrl() + "/userinfo",
                              "scopes",
                              List.of("openid", "email", "profile"),
                              "pkce",
                              true)))));
      assertEquals(
          "",
          oauthCollection.path("oauth2").path("providers").get(0).path("clientSecret").asText());
      request(
          "POST",
          "/api/collections/mfa_oauth_users/records",
          token,
          Map.of(
              "email", "oidc@example.com",
              "password", "Secret_456",
              "passwordConfirm", "Secret_456",
              "verified", true));

      JsonNode methods =
          request("GET", "/api/collections/mfa_oauth_users/auth-methods", null, null);
      JsonNode provider = methods.get("oauth2").get("providers").get(0);

      HttpResponse<String> oauthFirst =
          rawRequest(
              "POST",
              "/api/collections/mfa_oauth_users/auth-with-oauth2",
              null,
              Map.of(
                  "provider", "oidc",
                  "code", "first-code",
                  "codeVerifier", provider.get("codeVerifier").asText(),
                  "redirectURL", "http://127.0.0.1/callback"));
      assertEquals(401, oauthFirst.statusCode());
      String oauthMfaId = mapper.readTree(oauthFirst.body()).get("mfaId").asText();

      HttpResponse<String> sameOauthMethod =
          rawRequest(
              "POST",
              "/api/collections/mfa_oauth_users/auth-with-oauth2",
              null,
              Map.of(
                  "provider", "oidc",
                  "code", "second-code",
                  "codeVerifier", provider.get("codeVerifier").asText(),
                  "redirectURL", "http://127.0.0.1/callback",
                  "mfaId", oauthMfaId));
      assertEquals(400, sameOauthMethod.statusCode());
      assertFieldError(
          sameOauthMethod,
          400,
          "MFA requires a different auth method.",
          "mfaId",
          "validation_invalid_value",
          "MFA requires a different auth method.");

      JsonNode oauthCompleted =
          request(
              "POST",
              "/api/collections/mfa_oauth_users/auth-with-password",
              null,
              Map.of(
                  "identity", "oidc@example.com",
                  "password", "Secret_456",
                  "mfaId", oauthMfaId));
      assertTrue(oauthCompleted.hasNonNull("token"));
    }
  }

  @Test
  void superuserMfaCanEscalateFromPasswordToOtp() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    HttpResponse<String> changedSystemCollection =
        rawRequest(
            "PATCH",
            "/api/collections/_superusers",
            token,
            Map.of(
                "name", "admins",
                "type", "base",
                "system", false,
                "listRule", "",
                "viewRule", "",
                "createRule", "",
                "updateRule", "",
                "deleteRule", "",
                "authRule", "id != ''",
                "manageRule", "id != ''"));
    assertEquals(400, changedSystemCollection.statusCode());
    JsonNode systemCollectionErrors = mapper.readTree(changedSystemCollection.body()).get("data");
    assertEquals(
        "validation_collection_system_name_change",
        systemCollectionErrors.get("name").get("code").asText());
    assertEquals(
        "validation_collection_type_change",
        systemCollectionErrors.get("type").get("code").asText());
    assertEquals(
        "validation_collection_system_flag_change",
        systemCollectionErrors.get("system").get("code").asText());
    for (String field : List.of(
        "listRule",
        "viewRule",
        "createRule",
        "updateRule",
        "deleteRule",
        "authRule",
        "manageRule")) {
      assertEquals(
          "validation_collection_system_rule_change",
          systemCollectionErrors.get(field).get("code").asText());
    }

    HttpResponse<String> changedSystemMfaRule =
        rawRequest(
            "PATCH",
            "/api/collections/_superusers",
            token,
            Map.of(
                "options",
                Map.of(
                    "otp", Map.of("enabled", true, "duration", 300, "length", 6),
                    "mfa", Map.of("enabled", true, "duration", 900, "rule", "true"))));
    assertEquals(400, changedSystemMfaRule.statusCode());
    assertEquals(
        "validation_collection_system_rule_change",
        mapper
            .readTree(changedSystemMfaRule.body())
            .get("data")
            .get("mfa")
            .get("rule")
            .get("code")
            .asText());

    JsonNode superuserConfig =
        request(
            "PATCH",
            "/api/collections/_superusers",
            token,
            Map.of(
                "id", "pbc_superusers",
                "name", "_superusers",
                "type", "auth",
                "options",
                Map.of(
                    "passwordAuth",
                    Map.of("enabled", false, "identityFields", List.of("email")),
                    "otp",
                    Map.of(
                        "enabled", true,
                        "duration", 300,
                        "length", 6),
                    "mfa", Map.of("enabled", false, "duration", 900),
                    "oauth2",
                    Map.of(
                        "enabled",
                        true,
                        "providers",
                        List.of(
                            Map.of(
                                "name", "github",
                                "clientId", "client-id",
                                "clientSecret", "client-secret"))))));
    assertTrue(superuserConfig.get("passwordAuth").get("enabled").asBoolean());
    assertTrue(superuserConfig.get("mfa").get("enabled").asBoolean());
    assertFalse(superuserConfig.get("oauth2").get("enabled").asBoolean());
    assertEquals(0, superuserConfig.get("oauth2").get("providers").size());

    HttpResponse<String> passwordFirst =
        rawRequest(
            "POST",
            "/api/collections/_superusers/auth-with-password",
            null,
            Map.of(
                "identity", "root@example.com",
                "password", "Secret_123"));
    assertEquals(401, passwordFirst.statusCode(), passwordFirst.body());
    String passwordMfaId = mapper.readTree(passwordFirst.body()).get("mfaId").asText();

    JsonNode otpRequest =
        request(
            "POST",
            "/api/collections/_superusers/request-otp",
            null,
            Map.of("email", "root@example.com"));
    String otpId = otpRequest.get("otpId").asText();
    String otpPassword = otpRequestPassword("root@example.com", otpId);

    JsonNode completed =
        request(
            "POST",
            "/api/collections/_superusers/auth-with-otp",
            null,
            Map.of(
                "otpId", otpId,
                "password", otpPassword,
                "mfaId", passwordMfaId));
    assertTrue(completed.hasNonNull("token"));

    HttpResponse<String> sameMethod =
        rawRequest(
            "POST",
            "/api/collections/_superusers/auth-with-password",
            null,
            Map.of(
                "identity", "root@example.com",
                "password", "Secret_123",
                "mfaId", passwordMfaId));
    assertEquals(400, sameMethod.statusCode());
    assertFieldError(
        sameMethod,
        400,
        "Missing or invalid MFA ID.",
        "mfaId",
        "validation_invalid_value",
        "Missing or invalid MFA ID.");
  }

  @Test
  void oauth2EndpointsExchangeCodeAndReuseLinkedAuthRecord() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    try (FakeOAuth2Server oauth = FakeOAuth2Server.start()) {
      JsonNode oauthCollection =
          request(
              "POST",
              "/api/collections",
              token,
              Map.of(
                  "name",
                  "oauth_users",
                  "type",
                  "auth",
                  "createRule",
                  "@request.context = 'oauth2'",
                  "fields",
                  List.of(
                      Map.of("name", "providerUid", "type", "text"),
                      Map.of("name", "displayName", "type", "text"),
                      Map.of("name", "loginName", "type", "text"),
                      Map.of("name", "avatarLink", "type", "url")),
                  "indexes",
                  List.of("CREATE UNIQUE INDEX idx_oauth_login_name ON oauth_users (loginName)"),
                  "oauth2",
                  Map.of(
                      "enabled", true,
                      "mappedFields",
                      Map.of(
                          "id", "providerUid",
                          "name", "displayName",
                          "username", "loginName",
                          "avatarURL", "avatarLink"),
                      "providers",
                      List.of(
                          Map.of(
                              "name",
                              "oidc",
                              "clientId",
                              "client-123",
                              "clientSecret",
                              "secret-456",
                              "authURL",
                              oauth.baseUrl() + "/authorize",
                              "tokenURL",
                              oauth.baseUrl() + "/token",
                              "userInfoURL",
                              oauth.baseUrl() + "/userinfo",
                              "scopes",
                              List.of("openid", "email", "profile"),
                              "pkce",
                              true)))));
      assertEquals(
          "",
          oauthCollection.path("oauth2").path("providers").get(0).path("clientSecret").asText());
      assertEquals(
          "providerUid", oauthCollection.path("oauth2").path("mappedFields").path("id").asText());
      request(
          "PATCH",
          "/api/collections/oauth_users",
          token,
          Map.of(
              "oauth2",
              Map.of(
                  "enabled", true,
                  "mappedFields",
                  Map.of(
                      "id", "providerUid",
                      "name", "displayName",
                      "username", "loginName",
                      "avatarURL", "avatarLink"),
                  "providers",
                  List.of(
                      Map.of(
                          "name",
                          "oidc",
                          "clientId",
                          "client-123",
                          "clientSecret",
                          "",
                          "authURL",
                          oauth.baseUrl() + "/authorize",
                          "tokenURL",
                          oauth.baseUrl() + "/token",
                          "userInfoURL",
                          oauth.baseUrl() + "/userinfo",
                          "scopes",
                          List.of("openid", "email", "profile"),
                          "pkce",
                          true)))));

      JsonNode methods = request("GET", "/api/collections/oauth_users/auth-methods", null, null);
      JsonNode provider = methods.get("oauth2").get("providers").get(0);
      assertEquals("oidc", provider.get("name").asText());
      assertTrue(provider.get("authURL").asText().contains("client_id=client-123"));
      assertTrue(provider.get("authURL").asText().contains("scope=openid%20email%20profile"));
      assertTrue(provider.get("codeVerifier").asText().length() >= 10);

      JsonNode firstAuth =
          request(
              "POST",
              "/api/collections/oauth_users/auth-with-oauth2",
              null,
              Map.of(
                  "provider", "oidc",
                  "code", "first-code",
                  "codeVerifier", provider.get("codeVerifier").asText(),
                  "redirectURL", "http://127.0.0.1/callback"));
      String recordId = firstAuth.get("record").get("id").asText();
      assertEquals("oidc@example.com", firstAuth.get("record").get("email").asText());
      assertEquals("oauth-sub-123", firstAuth.get("record").get("providerUid").asText());
      assertEquals("OIDC User", firstAuth.get("record").get("displayName").asText());
      assertEquals("oidc-user", firstAuth.get("record").get("loginName").asText());
      assertEquals(
          "https://cdn.example.com/avatar.png", firstAuth.get("record").get("avatarLink").asText());
      assertTrue(firstAuth.get("record").get("verified").asBoolean());
      assertTrue(firstAuth.get("meta").get("isNew").asBoolean());
      assertEquals("oidc-user", firstAuth.get("meta").get("preferred_username").asText());
      assertTrue(oauth.lastTokenBody().contains("code=first-code"));
      assertTrue(oauth.lastTokenBody().contains("code_verifier="));
      assertTrue(oauth.lastTokenBody().contains("client_secret=secret-456"));

      request(
          "PATCH",
          "/api/collections/oauth_users/records/" + recordId,
          token,
          Map.of("displayName", "Manual Name"));

      JsonNode secondAuth =
          request(
              "POST",
              "/api/collections/oauth_users/auth-with-oauth2",
              null,
              Map.of(
                  "provider", "oidc",
                  "code", "second-code",
                  "codeVerifier", provider.get("codeVerifier").asText(),
                  "redirectURL", "http://127.0.0.1/callback"));
      assertEquals(recordId, secondAuth.get("record").get("id").asText());
      assertEquals("Manual Name", secondAuth.get("record").path("displayName").asText(""));
      assertFalse(secondAuth.get("meta").get("isNew").asBoolean());

      request(
          "POST",
          "/api/collections",
          token,
          Map.of(
              "name", "oauth_blocked_users",
              "type", "auth",
              "createRule", "@request.context != 'oauth2'",
              "oauth2",
              Map.of(
                  "enabled",
                  true,
                  "providers",
                  List.of(
                      Map.of(
                          "name",
                          "oidc",
                          "clientId",
                          "client-123",
                          "clientSecret",
                          "secret-456",
                          "authURL",
                          oauth.baseUrl() + "/authorize",
                          "tokenURL",
                          oauth.baseUrl() + "/token",
                          "userInfoURL",
                          oauth.baseUrl() + "/userinfo",
                          "scopes",
                          List.of("openid", "email", "profile"),
                          "pkce",
                          true)))));
      HttpResponse<String> blockedCreate =
          rawRequest(
              "POST",
              "/api/collections/oauth_blocked_users/auth-with-oauth2",
              null,
              Map.of(
                  "provider", "oidc",
                  "code", "blocked-code",
                  "redirectURL", "http://127.0.0.1/callback"));
      assertEquals(400, blockedCreate.statusCode());
      assertEquals(
          0,
          request("GET", "/api/collections/oauth_blocked_users/records", token, null)
              .get("totalItems")
              .asInt());
    }

    HttpResponse<String> redirect =
        rawRequest("GET", "/api/oauth2-redirect?state=test-state&code=abc123", null, null);
    assertEquals(307, redirect.statusCode());
    assertTrue(
        redirect.headers().firstValue("Location").orElse("").contains("oauth2-redirect-failure"));
  }

  @Test
  void oauth2RedirectUsesOfficialRealtimeContractAndIpChecks() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();
    request(
        "PATCH",
        "/api/settings",
        token,
        Map.of("trustedProxy", Map.of("headers", List.of("X-Test-IP"), "useLeftmostIP", false)));

    String firstIp = "203.0.113.10";
    HttpResponse<InputStream> firstResponse =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/realtime"))
                .header("Accept", "text/event-stream")
                .header("X-Test-IP", firstIp)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, firstResponse.statusCode());
    try (SseReader events = new SseReader(firstResponse.body())) {
      String clientId = mapper.readTree(events.next("PB_CONNECT").data()).get("clientId").asText();
      assertEquals(
          204,
          rawRequest(
              "POST",
              "/api/realtime",
              null,
              Map.of("clientId", clientId, "subscriptions", List.of("custom-topic", "@oauth2")),
              Map.of("X-Test-IP", firstIp))
              .statusCode());

      HttpResponse<String> success =
          rawRequest(
              "GET",
              "/api/oauth2-redirect?state=" + clientId + "&code=abc123",
              null,
              null,
              Map.of("X-Test-IP", firstIp));
      assertEquals(307, success.statusCode());
      assertTrue(
          success.headers().firstValue("Location").orElse("").contains("oauth2-redirect-success"));
      JsonNode payload = mapper.readTree(events.next("@oauth2").data());
      assertEquals(clientId, payload.get("state").asText());
      assertEquals("abc123", payload.get("code").asText());
      assertFalse(payload.has("error"));

      HttpResponse<String> reused =
          rawRequest(
              "GET",
              "/api/oauth2-redirect?state=" + clientId + "&code=reused",
              null,
              null,
              Map.of("X-Test-IP", firstIp));
      assertEquals(307, reused.statusCode());
      assertTrue(
          reused.headers().firstValue("Location").orElse("").contains("oauth2-redirect-failure"));
    }

    String secondIp = "203.0.113.20";
    HttpResponse<InputStream> secondResponse =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/realtime"))
                .header("Accept", "text/event-stream")
                .header("X-Test-IP", secondIp)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    try (SseReader events = new SseReader(secondResponse.body())) {
      String clientId = mapper.readTree(events.next("PB_CONNECT").data()).get("clientId").asText();
      assertEquals(
          400,
          rawRequest(
              "POST",
              "/api/realtime",
              null,
              Map.of("clientId", clientId, "subscriptions", List.of("@oauth2")),
              Map.of("X-Test-IP", "203.0.113.21"))
              .statusCode());
      assertEquals(
          204,
          rawRequest(
              "POST",
              "/api/realtime",
              null,
              Map.of("clientId", clientId, "subscriptions", List.of("@oauth2")),
              Map.of("X-Test-IP", secondIp))
              .statusCode());

      String form =
          "code=post-code&state="
              + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
              + "&user="
              + URLEncoder.encode(
                  "{\"name\":{\"firstName\":\"Ada\",\"lastName\":\"Lovelace\"}}",
                  StandardCharsets.UTF_8);
      HttpResponse<String> post =
          rawBodyRequest(
              "POST",
              "/api/oauth2-redirect",
              null,
              "application/x-www-form-urlencoded",
              form.getBytes(StandardCharsets.UTF_8),
              Map.of("X-Test-IP", secondIp));
      assertEquals(303, post.statusCode());
      assertTrue(
          post.headers().firstValue("Location").orElse("").contains("oauth2-redirect-success"));
      assertEquals(
          "post-code", mapper.readTree(events.next("@oauth2").data()).get("code").asText());
    }

    String thirdIp = "203.0.113.30";
    HttpResponse<InputStream> thirdResponse =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/realtime"))
                .header("Accept", "text/event-stream")
                .header("X-Test-IP", thirdIp)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    try (SseReader events = new SseReader(thirdResponse.body())) {
      String clientId = mapper.readTree(events.next("PB_CONNECT").data()).get("clientId").asText();
      assertEquals(
          204,
          rawRequest(
              "POST",
              "/api/realtime",
              null,
              Map.of("clientId", clientId, "subscriptions", List.of("@oauth2")),
              Map.of("X-Test-IP", thirdIp))
              .statusCode());

      HttpResponse<String> missingCode =
          rawRequest(
              "GET",
              "/api/oauth2-redirect?state=" + clientId,
              null,
              null,
              Map.of("X-Test-IP", thirdIp));
      assertEquals(307, missingCode.statusCode());
      assertTrue(
          missingCode
              .headers()
              .firstValue("Location")
              .orElse("")
              .contains("oauth2-redirect-failure"));
      assertEquals("", mapper.readTree(events.next("@oauth2").data()).get("code").asText());
    }

    String fourthIp = "203.0.113.40";
    HttpResponse<InputStream> fourthResponse =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/realtime"))
                .header("Accept", "text/event-stream")
                .header("X-Test-IP", fourthIp)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    try (SseReader events = new SseReader(fourthResponse.body())) {
      String clientId = mapper.readTree(events.next("PB_CONNECT").data()).get("clientId").asText();
      assertEquals(
          204,
          rawRequest(
              "POST",
              "/api/realtime",
              null,
              Map.of("clientId", clientId, "subscriptions", List.of("@oauth2")),
              Map.of("X-Test-IP", fourthIp))
              .statusCode());
      HttpResponse<String> mismatch =
          rawRequest(
              "GET",
              "/api/oauth2-redirect?state=" + clientId + "&code=blocked",
              null,
              null,
              Map.of("X-Test-IP", "203.0.113.41"));
      assertEquals(307, mismatch.statusCode());
      assertTrue(
          mismatch.headers().firstValue("Location").orElse("").contains("oauth2-redirect-failure"));
      HttpResponse<String> removed =
          rawRequest(
              "GET",
              "/api/oauth2-redirect?state=" + clientId + "&code=after-mismatch",
              null,
              null,
              Map.of("X-Test-IP", fourthIp));
      assertEquals(307, removed.statusCode());
      assertTrue(
          removed.headers().firstValue("Location").orElse("").contains("oauth2-redirect-failure"));
    }
  }

  @Test
  void oauth2ExchangeErrorsUseOfficialEnvelope() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "oauth_missing_token_url_users",
            "type", "auth",
            "oauth2",
            Map.of(
                "enabled",
                true,
                "providers",
                List.of(
                    Map.of(
                        "name", "mailcow",
                        "clientId", "client-123",
                        "clientSecret", "secret-456",
                        "authURL", "http://127.0.0.1/authorize")))));
    HttpResponse<String> missingTokenUrl =
        rawRequest(
            "POST",
            "/api/collections/oauth_missing_token_url_users/auth-with-oauth2",
            null,
            Map.of(
                "provider", "mailcow",
                "code", "bad-code",
                "redirectURL", "http://127.0.0.1/callback"));
    assertEquals(400, missingTokenUrl.statusCode());
    assertFieldError(
        missingTokenUrl,
        400,
        "Failed to authenticate.",
        "provider",
        "validation_invalid_value",
        "OAuth2 provider tokenURL is required.");

    try (FakeOAuth2Server oauth =
        FakeOAuth2Server.start(400, "{\"error\":\"invalid_grant\"}", 200, "{}")) {
      request(
          "POST",
          "/api/collections",
          token,
          Map.of(
              "name", "oauth_error_users",
              "type", "auth",
              "oauth2",
              Map.of(
                  "enabled",
                  true,
                  "providers",
                  List.of(
                      Map.of(
                          "name",
                          "oidc",
                          "clientId",
                          "client-123",
                          "clientSecret",
                          "secret-456",
                          "authURL",
                          oauth.baseUrl() + "/authorize",
                          "tokenURL",
                          oauth.baseUrl() + "/token",
                          "userInfoURL",
                          oauth.baseUrl() + "/userinfo")))));

      HttpResponse<String> missingProvider =
          rawRequest(
              "POST",
              "/api/collections/oauth_error_users/auth-with-oauth2",
              null,
              Map.of(
                  "provider", "missing",
                  "code", "bad-code",
                  "redirectURL", "http://127.0.0.1/callback"));
      assertEquals(400, missingProvider.statusCode());
      assertFieldError(
          missingProvider,
          400,
          "Failed to authenticate.",
          "provider",
          "validation_invalid_value",
          "Provider with name missing is missing or is not enabled.");

      HttpResponse<String> tokenFailure =
          rawRequest(
              "POST",
              "/api/collections/oauth_error_users/auth-with-oauth2",
              null,
              Map.of(
                  "provider", "oidc",
                  "code", "bad-code",
                  "redirectURL", "http://127.0.0.1/callback"));
      assertEquals(400, tokenFailure.statusCode());
      assertFieldError(
          tokenFailure,
          400,
          "Failed to fetch OAuth2 token.",
          "provider",
          "validation_invalid_value",
          "OAuth2 provider request failed.");
    }

    try (FakeOAuth2Server oauth =
        FakeOAuth2Server.start(200, "{\"access_token\":\"token-123\"}", 200, "{}")) {
      request(
          "POST",
          "/api/collections",
          token,
          Map.of(
              "name", "oauth_userinfo_error_users",
              "type", "auth",
              "oauth2",
              Map.of(
                  "enabled",
                  true,
                  "providers",
                  List.of(
                      Map.of(
                          "name",
                          "oidc",
                          "clientId",
                          "client-123",
                          "clientSecret",
                          "secret-456",
                          "authURL",
                          oauth.baseUrl() + "/authorize",
                          "tokenURL",
                          oauth.baseUrl() + "/token",
                          "userInfoURL",
                          oauth.baseUrl() + "/userinfo")))));

      HttpResponse<String> userInfoFailure =
          rawRequest(
              "POST",
              "/api/collections/oauth_userinfo_error_users/auth-with-oauth2",
              null,
              Map.of(
                  "provider", "oidc",
                  "code", "bad-userinfo",
                  "redirectURL", "http://127.0.0.1/callback"));
      assertEquals(400, userInfoFailure.statusCode());
      assertFieldError(
          userInfoFailure,
          400,
          "Failed to fetch OAuth2 user.",
          "provider",
          "validation_invalid_value",
          "OAuth2 user info is empty.");
    }
  }

  @Test
  void authRefreshReissuesTokenForMatchingAuthRecord() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "users",
            "type", "auth",
            "fields", List.of(Map.of("name", "displayName", "type", "text"))));
    JsonNode user =
        request(
            "POST",
            "/api/collections/users/records",
            token,
            Map.of(
                "email", "refresh@example.com",
                "password", "Secret_456",
                "passwordConfirm", "Secret_456",
                "displayName", "Refresh"));

    JsonNode auth =
        request(
            "POST",
            "/api/collections/users/auth-with-password",
            null,
            Map.of(
                "identity", "refresh@example.com",
                "password", "Secret_456"));
    JsonNode refreshed =
        request("POST", "/api/collections/users/auth-refresh", auth.get("token").asText(), null);

    assertTrue(refreshed.hasNonNull("token"));
    assertEquals(user.get("id").asText(), refreshed.get("record").get("id").asText());
    assertFalse(refreshed.get("record").has("password"));

    HttpResponse<String> mismatch =
        rawRequest(
            "POST", "/api/collections/_superusers/auth-refresh", auth.get("token").asText(), null);
    assertEquals(401, mismatch.statusCode());
  }

  @Test
  void authLifecycleEndpointsVerifyResetChangeEmailAndImpersonate() throws Exception {
    start();
    bootstrapSuperuser();
    String superuserToken = loginToken();

    request(
        "POST",
        "/api/collections",
        superuserToken,
        Map.of(
            "name", "auth_lifecycle_users",
            "type", "auth",
            "fields", List.of(Map.of("name", "displayName", "type", "text"))));
    JsonNode user =
        request(
            "POST",
            "/api/collections/auth_lifecycle_users/records",
            superuserToken,
            Map.of(
                "email", "lifecycle@example.com",
                "password", "Secret_456",
                "passwordConfirm", "Secret_456",
                "displayName", "Lifecycle",
                "verified", false));
    String userId = user.get("id").asText();

    JsonNode auth =
        request(
            "POST",
            "/api/collections/auth_lifecycle_users/auth-with-password",
            null,
            Map.of(
                "identity", "lifecycle@example.com",
                "password", "Secret_456"));
    String userToken = auth.get("token").asText();

    HttpResponse<String> verificationRequest =
        rawRequest(
            "POST",
            "/api/collections/auth_lifecycle_users/request-verification",
            null,
            Map.of("email", "lifecycle@example.com"));
    assertEquals(204, verificationRequest.statusCode());
    String verificationToken = authRequestToken("verification", "lifecycle@example.com");
    HttpResponse<String> requestTokenAsBearer =
        rawRequest(
            "GET",
            "/api/collections/auth_lifecycle_users/records/" + userId,
            verificationToken,
            null);
    assertEquals(404, requestTokenAsBearer.statusCode());
    assertErrorEnvelope(requestTokenAsBearer, 404, "Record not found.");

    HttpResponse<String> verified =
        rawRequest(
            "POST",
            "/api/collections/auth_lifecycle_users/confirm-verification",
            null,
            Map.of("token", verificationToken));
    assertEquals(204, verified.statusCode());
    JsonNode verifiedRecord =
        request(
            "GET", "/api/collections/auth_lifecycle_users/records/" + userId, superuserToken, null);
    assertTrue(verifiedRecord.get("verified").asBoolean());

    request(
        "POST",
        "/api/collections/auth_lifecycle_users/request-password-reset",
        null,
        Map.of("email", "lifecycle@example.com"));
    String resetToken = authRequestToken("passwordReset", "lifecycle@example.com");
    HttpResponse<String> mismatch =
        rawRequest(
            "POST",
            "/api/collections/auth_lifecycle_users/confirm-password-reset",
            null,
            Map.of(
                "token", resetToken, "password", "newsecret456", "passwordConfirm", "different"));
    assertEquals(400, mismatch.statusCode());
    assertFieldError(
        mismatch,
        400,
        "passwordConfirm does not match password.",
        "passwordConfirm",
        "validation_invalid_value",
        "Passwords do not match.");

    // A too-short password is rejected with 400 before any DB write runs. Because the
    // validation now happens before token consumption, the same resetToken stays valid and
    // is reused by the successful reset below.
    HttpResponse<String> tooShort =
        rawRequest(
            "POST",
            "/api/collections/auth_lifecycle_users/confirm-password-reset",
            null,
            Map.of("token", resetToken, "password", "short", "passwordConfirm", "short"));
    assertEquals(400, tooShort.statusCode());
    assertFieldError(
        tooShort,
        400,
        "Password must be at least 8 characters.",
        "password",
        "validation_invalid_value",
        "Password must be at least 8 characters.");

    HttpResponse<String> reset =
        rawRequest(
            "POST",
            "/api/collections/auth_lifecycle_users/confirm-password-reset",
            null,
            Map.of(
                "token",
                resetToken,
                "password",
                "newsecret456",
                "passwordConfirm",
                "newsecret456"));
    assertEquals(204, reset.statusCode());
    assertEquals(
        401,
        rawRequest("POST", "/api/collections/auth_lifecycle_users/auth-refresh", userToken, null)
            .statusCode());
    HttpResponse<String> oldPassword =
        rawRequest(
            "POST",
            "/api/collections/auth_lifecycle_users/auth-with-password",
            null,
            Map.of(
                "identity", "lifecycle@example.com",
                "password", "Secret_456"));
    assertEquals(400, oldPassword.statusCode());
    assertErrorEnvelope(oldPassword, 400, "Failed to authenticate.");

    JsonNode newAuth =
        request(
            "POST",
            "/api/collections/auth_lifecycle_users/auth-with-password",
            null,
            Map.of(
                "identity", "lifecycle@example.com",
                "password", "newsecret456"));
    String newUserToken = newAuth.get("token").asText();

    HttpResponse<String> emailChangeRequest =
        rawRequest(
            "POST",
            "/api/collections/auth_lifecycle_users/request-email-change",
            newUserToken,
            Map.of("newEmail", "changed@example.com"));
    assertEquals(204, emailChangeRequest.statusCode());
    String emailChangeToken = authRequestToken("emailChange", "lifecycle@example.com");
    HttpResponse<String> wrongEmailChangePassword =
        rawRequest(
            "POST",
            "/api/collections/auth_lifecycle_users/confirm-email-change",
            null,
            Map.of("token", emailChangeToken, "password", "wrong-password"));
    assertEquals(400, wrongEmailChangePassword.statusCode());
    assertFieldError(
        wrongEmailChangePassword,
        400,
        "Invalid password.",
        "password",
        "validation_invalid_value",
        "Invalid password.");
    assertEquals(
        204,
        rawRequest(
            "POST",
            "/api/collections/auth_lifecycle_users/confirm-email-change",
            null,
            Map.of("token", emailChangeToken, "password", "newsecret456"))
            .statusCode());
    HttpResponse<String> oldEmailAuth =
        rawRequest(
            "POST",
            "/api/collections/auth_lifecycle_users/auth-with-password",
            null,
            Map.of(
                "identity", "lifecycle@example.com",
                "password", "newsecret456"));
    assertEquals(400, oldEmailAuth.statusCode());
    assertErrorEnvelope(oldEmailAuth, 400, "Failed to authenticate.");
    JsonNode changedAuth =
        request(
            "POST",
            "/api/collections/auth_lifecycle_users/auth-with-password",
            null,
            Map.of(
                "identity", "changed@example.com",
                "password", "newsecret456"));

    HttpResponse<String> forbiddenImpersonate =
        rawRequest(
            "POST",
            "/api/collections/auth_lifecycle_users/impersonate/" + userId,
            changedAuth.get("token").asText(),
            Map.of("duration", 120));
    assertEquals(403, forbiddenImpersonate.statusCode());
    JsonNode impersonated =
        request(
            "POST",
            "/api/collections/auth_lifecycle_users/impersonate/" + userId,
            superuserToken,
            Map.of("duration", 120));
    assertEquals(userId, impersonated.get("record").get("id").asText());
    assertTrue(impersonated.hasNonNull("token"));
    assertEquals(
        401,
        rawRequest(
            "POST",
            "/api/collections/auth_lifecycle_users/auth-refresh",
            impersonated.get("token").asText(),
            null)
            .statusCode());
  }

  @Test
  void authResponsesHonorQueryFieldsAndExpand() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    JsonNode teams =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "auth_teams",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(Map.of("name", "name", "type", "text", "required", true))));
    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "auth_query_users",
            "type", "auth",
            "fields",
            List.of(
                Map.of("name", "displayName", "type", "text"),
                Map.of(
                    "name",
                    "team",
                    "type",
                    "relation",
                    "collectionId",
                    teams.get("id").asText()))));
    JsonNode team =
        request("POST", "/api/collections/auth_teams/records", token, Map.of("name", "Core"));
    JsonNode user =
        request(
            "POST",
            "/api/collections/auth_query_users/records",
            token,
            Map.of(
                "email", "query@example.com",
                "password", "Secret_456",
                "passwordConfirm", "Secret_456",
                "displayName", "Query",
                "team", team.get("id").asText()));

    JsonNode auth =
        request(
            "POST",
            "/api/collections/auth_query_users/auth-with-password"
                + "?expand=team&fields=token,record.id,record.expand.team.name",
            null,
            Map.of(
                "identity", "query@example.com",
                "password", "Secret_456"));
    assertTrue(auth.hasNonNull("token"));
    assertEquals(user.get("id").asText(), auth.get("record").get("id").asText());
    assertFalse(auth.get("record").has("email"));
    assertFalse(auth.get("record").has("displayName"));
    assertEquals("Core", auth.get("record").get("expand").get("team").get("name").asText());
    assertFalse(auth.get("record").get("expand").get("team").has("id"));

    JsonNode refreshed =
        request(
            "POST",
            "/api/collections/auth_query_users/auth-refresh"
                + "?expand=team&fields=token,record.expand.team.name",
            auth.get("token").asText(),
            null);
    assertTrue(refreshed.hasNonNull("token"));
    assertFalse(refreshed.get("record").has("id"));
    assertEquals("Core", refreshed.get("record").get("expand").get("team").get("name").asText());

    JsonNode recordOnly =
        request(
            "POST",
            "/api/collections/auth_query_users/auth-refresh" + "?fields=record.*",
            auth.get("token").asText(),
            null);
    assertFalse(recordOnly.has("token"));
    assertEquals("query@example.com", recordOnly.get("record").get("email").asText());
    assertEquals("Query", recordOnly.get("record").get("displayName").asText());
  }

  @Test
  void servesAdminUi() throws Exception {
    start();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(server.baseUrl() + "/_/")).GET().build();
    HttpResponse<String> response =
        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("pocketbase-java"));
    assertTrue(response.body().contains("/_/assets/"));

    Matcher asset = Pattern.compile("src=\"(/_/assets/[^\"]+\\.js)\"").matcher(response.body());
    assertTrue(asset.find());
    HttpRequest assetRequest =
        HttpRequest.newBuilder(URI.create(server.baseUrl() + asset.group(1))).GET().build();
    HttpResponse<String> assetResponse =
        http.send(assetRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, assetResponse.statusCode());
    assertTrue(assetResponse.body().contains("pocketbase-java"));
  }

  @Test
  void collectionRulesFilterAndProtectRecordOperations() throws Exception {
    start();
    bootstrapSuperuser();
    String superuserToken = loginToken();

    request(
        "POST",
        "/api/collections",
        superuserToken,
        Map.of(
            "name", "users",
            "type", "auth",
            "fields", List.of(Map.of("name", "displayName", "type", "text"))));
    request(
        "POST",
        "/api/collections/users/records",
        superuserToken,
        Map.of(
            "email", "alice@example.com",
            "password", "Secret_456",
            "passwordConfirm", "Secret_456",
            "displayName", "Alice"));
    request(
        "POST",
        "/api/collections/users/records",
        superuserToken,
        Map.of(
            "email", "bob@example.com",
            "password", "Secret_456",
            "passwordConfirm", "Secret_456",
            "displayName", "Bob"));

    JsonNode aliceAuth =
        request(
            "POST",
            "/api/collections/users/auth-with-password",
            null,
            Map.of(
                "identity", "alice@example.com",
                "password", "Secret_456"));
    JsonNode bobAuth =
        request(
            "POST",
            "/api/collections/users/auth-with-password",
            null,
            Map.of(
                "identity", "bob@example.com",
                "password", "Secret_456"));
    String aliceToken = aliceAuth.get("token").asText();
    String aliceId = aliceAuth.get("record").get("id").asText();
    String bobToken = bobAuth.get("token").asText();
    String bobId = bobAuth.get("record").get("id").asText();

    String ownerRule = "public = true || owner = @request.auth.id";
    request(
        "POST",
        "/api/collections",
        superuserToken,
        Map.of(
            "name", "documents",
            "listRule", ownerRule,
            "viewRule", ownerRule,
            "createRule", "owner = @request.auth.id",
            "updateRule", "owner = @request.auth.id",
            "deleteRule", "owner = @request.auth.id",
            "fields",
            List.of(
                Map.of("name", "owner", "type", "text", "required", true),
                Map.of("name", "title", "type", "text", "required", true),
                Map.of("name", "public", "type", "bool"))));

    JsonNode aliceDocument =
        request(
            "POST",
            "/api/collections/documents/records",
            aliceToken,
            Map.of("owner", aliceId, "title", "Alice private", "public", false));
    request(
        "POST",
        "/api/collections/documents/records",
        superuserToken,
        Map.of("owner", bobId, "title", "Bob private", "public", false));

    HttpResponse<String> forgedCreate =
        rawRequest(
            "POST",
            "/api/collections/documents/records",
            aliceToken,
            Map.of("owner", bobId, "title", "Forged", "public", false));
    assertEquals(400, forgedCreate.statusCode());
    assertErrorEnvelope(forgedCreate, 400, "The record failed the collection create rule.");

    JsonNode alicePage = request("GET", "/api/collections/documents/records", aliceToken, null);
    assertEquals(1, alicePage.get("totalItems").asInt());
    assertEquals("Alice private", alicePage.get("items").get(0).get("title").asText());

    HttpResponse<String> bobView =
        rawRequest(
            "GET",
            "/api/collections/documents/records/" + aliceDocument.get("id").asText(),
            bobToken,
            null);
    assertEquals(404, bobView.statusCode());

    HttpResponse<String> bobUpdate =
        rawRequest(
            "PATCH",
            "/api/collections/documents/records/" + aliceDocument.get("id").asText(),
            bobToken,
            Map.of("title", "Bob edit"));
    assertEquals(404, bobUpdate.statusCode());

    request(
        "PATCH",
        "/api/collections/documents/records/" + aliceDocument.get("id").asText(),
        aliceToken,
        Map.of("title", "Alice edited"));
    JsonNode updated =
        request(
            "GET",
            "/api/collections/documents/records/" + aliceDocument.get("id").asText(),
            aliceToken,
            null);
    assertEquals("Alice edited", updated.get("title").asText());
  }

  @Test
  void multipartFileUploadsAreStoredAndServedFromApiFiles() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "assets",
            "listRule", "",
            "viewRule", "",
            "fields",
            List.of(
                Map.of("name", "title", "type", "text", "required", true),
                Map.of("name", "attachment", "type", "file", "required", true))));

    JsonNode created =
        multipartRequest(
            "POST",
            "/api/collections/assets/records",
            token,
            Map.of("title", "Uploaded doc"),
            Map.of(
                "attachment",
                new MultipartFile(
                    "hello world.txt",
                    "text/plain",
                    "hello from multipart".getBytes(StandardCharsets.UTF_8))));

    String filename = created.get("attachment").asText();
    assertTrue(filename.startsWith("hello_world_"));
    assertTrue(filename.endsWith(".txt"));

    String filePath = "/api/files/assets/" + created.get("id").asText() + "/" + filename;
    HttpResponse<String> file =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + filePath)).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, file.statusCode());
    assertEquals("text/plain; charset=utf-8", file.headers().firstValue("Content-Type").orElse(""));
    assertEquals("hello from multipart", file.body());

    HttpResponse<String> clampedRange =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + filePath))
                .header("Range", "bytes=0-999999")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(206, clampedRange.statusCode());
    assertEquals("bytes 0-19/20", clampedRange.headers().firstValue("Content-Range").orElse(""));
    assertEquals("20", clampedRange.headers().firstValue("Content-Length").orElse(""));
    assertEquals("hello from multipart", clampedRange.body());

    HttpResponse<String> suffixRange =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + filePath))
                .header("Range", "bytes=-9")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(206, suffixRange.statusCode());
    assertEquals("bytes 11-19/20", suffixRange.headers().firstValue("Content-Range").orElse(""));
    assertEquals("multipart", suffixRange.body());

    HttpResponse<String> unsatisfiableRange =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + filePath))
                .header("Range", "bytes=20-25")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(416, unsatisfiableRange.statusCode());
    assertEquals("bytes */20", unsatisfiableRange.headers().firstValue("Content-Range").orElse(""));

    HttpResponse<String> missingFile =
        rawRequest(
            "GET", "/api/files/assets/" + created.get("id").asText() + "/missing.txt", null, null);
    assertEquals(404, missingFile.statusCode());
    assertErrorEnvelope(missingFile, 404, "The requested resource wasn't found.");
  }

  @Test
  void dartSdkMultipartJsonPayloadIsMergedWithUploadedFiles() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "dart_multipart_assets",
            "listRule", "",
            "viewRule", "",
            "fields",
            List.of(
                Map.of("name", "title", "type", "text", "required", true),
                Map.of("name", "attachment", "type", "file", "required", true))));

    JsonNode created =
        multipartRequest(
            "POST",
            "/api/collections/dart_multipart_assets/records",
            token,
            Map.of("@jsonPayload", "[\"{\\\"title\\\":\\\"Dart upload\\\"}\"]"),
            Map.of(
                "attachment",
                new MultipartFile(
                    "dart upload.txt",
                    "text/plain",
                    "hello from dart".getBytes(StandardCharsets.UTF_8))));

    assertEquals("Dart upload", created.get("title").asText());
    assertTrue(created.get("attachment").asText().startsWith("dart_upload_"));
  }

  @Test
  void imageFileThumbsAreGeneratedOnlyForConfiguredSizes() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "image_assets",
            "listRule", "",
            "viewRule", "",
            "fields",
            List.of(
                Map.of("name", "title", "type", "text", "required", true),
                Map.of(
                    "name",
                    "image",
                    "type",
                    "file",
                    "required",
                    true,
                    "mimeTypes",
                    List.of("image/png"),
                    "thumbs",
                    List.of("8x4", "0x4")))));

    JsonNode created =
        multipartRequest(
            "POST",
            "/api/collections/image_assets/records",
            token,
            Map.of("title", "Image"),
            Map.of("image", new MultipartFile("wide.png", "image/png", pngBytes(16, 8))));

    String filename = created.get("image").asText();
    String filePath = "/api/files/image_assets/" + created.get("id").asText() + "/" + filename;
    HttpResponse<byte[]> thumb =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + filePath + "?thumb=8x4"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, thumb.statusCode());
    assertEquals("image/png", thumb.headers().firstValue("Content-Type").orElse(""));
    assertEquals(List.of(8, 4), imageSize(thumb.body()));

    HttpResponse<byte[]> originalFallback =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + filePath + "?thumb=12x6"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, originalFallback.statusCode());
    assertEquals(List.of(16, 8), imageSize(originalFallback.body()));

    HttpResponse<byte[]> download =
        http.send(
            HttpRequest.newBuilder(
                URI.create(server.baseUrl() + filePath + "?thumb=8x4&download=1"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(
        "attachment; filename=\"" + filename + "\"",
        download.headers().firstValue("Content-Disposition").orElse(""));
  }

  @Test
  void uploadedFilesCannotBeServedAsActiveSameOriginDocuments() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "untrusted_assets",
            "listRule", "",
            "viewRule", "",
            "fields", List.of(Map.of("name", "attachment", "type", "file", "required", true))));

    JsonNode created =
        multipartRequest(
            "POST",
            "/api/collections/untrusted_assets/records",
            token,
            Map.of(),
            Map.of(
                "attachment",
                new MultipartFile(
                    "unsafe.svg",
                    "image/svg+xml",
                    "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                        .getBytes(StandardCharsets.UTF_8))));
    String filename = created.get("attachment").asText();
    String path = "/api/files/untrusted_assets/" + created.get("id").asText() + "/" + filename;

    HttpResponse<byte[]> response =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());

    assertEquals(200, response.statusCode());
    assertEquals("application/octet-stream", response.headers().firstValue("Content-Type").orElse(""));
    assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""));
    assertTrue(
        response
            .headers()
            .firstValue("Content-Security-Policy")
            .orElse("")
            .contains("sandbox"));
    assertEquals(
        "attachment; filename=\"" + filename + "\"",
        response.headers().firstValue("Content-Disposition").orElse(""));

    // The extension alone must not make an active document previewable as a raster image.
    JsonNode disguised =
        multipartRequest(
            "POST",
            "/api/collections/untrusted_assets/records",
            token,
            Map.of(),
            Map.of(
                "attachment",
                new MultipartFile(
                    "looks-like-an-image.png",
                    "image/png",
                    "<!doctype html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8))));
    String disguisedFilename = disguised.get("attachment").asText();
    HttpResponse<byte[]> disguisedResponse =
        http.send(
            HttpRequest.newBuilder(
                URI.create(
                    server.baseUrl()
                        + "/api/files/untrusted_assets/"
                        + disguised.get("id").asText()
                        + "/"
                        + disguisedFilename))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());

    assertEquals(200, disguisedResponse.statusCode());
    assertEquals(
        "application/octet-stream",
        disguisedResponse.headers().firstValue("Content-Type").orElse(""));
    assertEquals(
        "attachment; filename=\"" + disguisedFilename + "\"",
        disguisedResponse.headers().firstValue("Content-Disposition").orElse(""));
  }

  @Test
  void fileFieldsValidateMimeTypesAndMaxSize() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "validated_assets",
            "listRule", "",
            "viewRule", "",
            "fields",
            List.of(
                Map.of("name", "title", "type", "text", "required", true),
                Map.of(
                    "name",
                    "attachment",
                    "type",
                    "file",
                    "required",
                    true,
                    "mimeTypes",
                    List.of("text/plain"),
                    "maxSize",
                    12))));

    JsonNode created =
        multipartRequest(
            "POST",
            "/api/collections/validated_assets/records",
            token,
            Map.of("title", "Valid"),
            Map.of(
                "attachment",
                new MultipartFile(
                    "ok.txt", "text/plain", "small".getBytes(StandardCharsets.UTF_8))));
    assertTrue(created.get("attachment").asText().startsWith("ok_"));

    HttpResponse<String> badMime =
        rawMultipartRequest(
            "POST",
            "/api/collections/validated_assets/records",
            token,
            Map.of("title", "Bad mime"),
            Map.of(
                "attachment",
                new MultipartFile(
                    "bad.png", "image/png", "small".getBytes(StandardCharsets.UTF_8))));
    assertEquals(400, badMime.statusCode());
    assertFieldError(
        badMime,
        400,
        "File `bad.png` MIME type is not allowed for field `attachment`.",
        "attachment",
        "validation_invalid_value",
        "File `bad.png` MIME type is not allowed for field `attachment`.");

    HttpResponse<String> tooLarge =
        rawMultipartRequest(
            "POST",
            "/api/collections/validated_assets/records",
            token,
            Map.of("title", "Too large"),
            Map.of(
                "attachment",
                new MultipartFile(
                    "large.txt",
                    "text/plain",
                    "this payload is too large".getBytes(StandardCharsets.UTF_8))));
    assertEquals(400, tooLarge.statusCode());
    assertFieldError(
        tooLarge,
        400,
        "File `large.txt` exceeds maxSize for field `attachment`.",
        "attachment",
        "validation_invalid_value",
        "File `large.txt` exceeds maxSize for field `attachment`.");
  }

  @Test
  void protectedFilesRequireFileTokenAndViewRuleAccess() throws Exception {
    start();
    bootstrapSuperuser();
    String superuserToken = loginToken();

    request(
        "POST",
        "/api/collections",
        superuserToken,
        Map.of(
            "name", "users",
            "type", "auth",
            "fields", List.of(Map.of("name", "displayName", "type", "text"))));
    request(
        "POST",
        "/api/collections/users/records",
        superuserToken,
        Map.of(
            "email", "alice-file@example.com",
            "password", "Secret_456",
            "passwordConfirm", "Secret_456",
            "displayName", "Alice"));
    request(
        "POST",
        "/api/collections/users/records",
        superuserToken,
        Map.of(
            "email", "bob-file@example.com",
            "password", "Secret_456",
            "passwordConfirm", "Secret_456",
            "displayName", "Bob"));
    JsonNode aliceAuth =
        request(
            "POST",
            "/api/collections/users/auth-with-password",
            null,
            Map.of(
                "identity", "alice-file@example.com",
                "password", "Secret_456"));
    JsonNode bobAuth =
        request(
            "POST",
            "/api/collections/users/auth-with-password",
            null,
            Map.of(
                "identity", "bob-file@example.com",
                "password", "Secret_456"));
    String aliceToken = aliceAuth.get("token").asText();
    String bobToken = bobAuth.get("token").asText();
    String aliceId = aliceAuth.get("record").get("id").asText();

    request(
        "POST",
        "/api/collections",
        superuserToken,
        Map.of(
            "name", "secure_assets",
            "listRule", "owner = @request.auth.id",
            "viewRule", "owner = @request.auth.id && @request.context = 'protectedFile'",
            "fields",
            List.of(
                Map.of("name", "owner", "type", "text", "required", true),
                Map.of(
                    "name",
                    "attachment",
                    "type",
                    "file",
                    "required",
                    true,
                    "protected",
                    true))));
    JsonNode created =
        multipartRequest(
            "POST",
            "/api/collections/secure_assets/records",
            superuserToken,
            Map.of("owner", aliceId),
            Map.of(
                "attachment",
                new MultipartFile(
                    "secret.txt",
                    "text/plain",
                    "protected payload".getBytes(StandardCharsets.UTF_8))));
    String filename = created.get("attachment").asText();
    String filePath = "/api/files/secure_assets/" + created.get("id").asText() + "/" + filename;

    HttpResponse<String> publicFile = rawRequest("GET", filePath, null, null);
    assertEquals(404, publicFile.statusCode());
    assertErrorEnvelope(publicFile, 404, "The requested resource wasn't found.");

    HttpResponse<String> invalidFileToken =
        rawRequest("GET", filePath + "?token=invalid", null, null);
    assertEquals(404, invalidFileToken.statusCode());
    assertErrorEnvelope(invalidFileToken, 404, "The requested resource wasn't found.");

    JsonNode aliceFileToken = request("POST", "/api/files/token", aliceToken, null);
    HttpResponse<String> fileTokenAsBearer =
        rawRequest("GET", filePath, aliceFileToken.get("token").asText(), null);
    assertEquals(404, fileTokenAsBearer.statusCode());
    assertErrorEnvelope(fileTokenAsBearer, 404, "The requested resource wasn't found.");

    HttpResponse<String> aliceFile =
        rawRequest("GET", filePath + "?token=" + aliceFileToken.get("token").asText(), null, null);
    assertEquals(200, aliceFile.statusCode());
    assertEquals("protected payload", aliceFile.body());

    JsonNode bobFileToken = request("POST", "/api/files/token", bobToken, null);
    HttpResponse<String> bobFile =
        rawRequest("GET", filePath + "?token=" + bobFileToken.get("token").asText(), null, null);
    assertEquals(404, bobFile.statusCode());
    assertErrorEnvelope(bobFile, 404, "The requested resource wasn't found.");

    request(
        "PATCH",
        "/api/collections/secure_assets",
        superuserToken,
        Map.of("viewRule", "@request.context = 'protectedFile'"));
    assertEquals(200, rawRequest("GET", filePath, null, null).statusCode());
    assertEquals(200, rawRequest("GET", filePath + "?token=invalid", null, null).statusCode());
  }

  @Test
  void realtimeSendsRecordCreateUpdateAndDeleteEvents() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "updates",
            "listRule", "",
            "viewRule", "",
            "fields", List.of(Map.of("name", "title", "type", "text", "required", true))));

    HttpResponse<InputStream> response =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/realtime"))
                .header("Accept", "text/event-stream")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, response.statusCode());

    try (SseReader events = new SseReader(response.body())) {
      SseEvent connect = events.next("PB_CONNECT");
      String clientId = mapper.readTree(connect.data()).get("clientId").asText();

      request(
          "POST",
          "/api/realtime",
          token,
          Map.of("clientId", clientId, "subscriptions", List.of("updates/*")));

      JsonNode created =
          request("POST", "/api/collections/updates/records", token, Map.of("title", "created"));
      SseEvent createEvent = events.next("updates/*");
      JsonNode createData = mapper.readTree(createEvent.data());
      assertEquals("create", createData.get("action").asText());
      assertEquals("created", createData.get("record").get("title").asText());

      request(
          "PATCH",
          "/api/collections/updates/records/" + created.get("id").asText(),
          token,
          Map.of("title", "updated"));
      SseEvent updateEvent = events.next("updates/*");
      JsonNode updateData = mapper.readTree(updateEvent.data());
      assertEquals("update", updateData.get("action").asText());
      assertEquals("updated", updateData.get("record").get("title").asText());

      request(
          "DELETE", "/api/collections/updates/records/" + created.get("id").asText(), token, null);
      SseEvent deleteEvent = events.next("updates/*");
      JsonNode deleteData = mapper.readTree(deleteEvent.data());
      assertEquals("delete", deleteData.get("action").asText());
      assertEquals(created.get("id").asText(), deleteData.get("record").get("id").asText());
    }
  }

  @Test
  void realtimeAllowsGuestAuthUpgradeButRejectsLaterAuthorizationChanges() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    HttpResponse<InputStream> response =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/realtime"))
                .header("Accept", "text/event-stream")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, response.statusCode());

    try (SseReader events = new SseReader(response.body())) {
      SseEvent connect = events.next("PB_CONNECT");
      String clientId = mapper.readTree(connect.data()).get("clientId").asText();

      HttpResponse<String> initial =
          rawRequest(
              "POST",
              "/api/realtime",
              null,
              Map.of("clientId", clientId, "subscriptions", List.of("updates/*")));
      assertEquals(204, initial.statusCode());

      HttpResponse<String> upgraded =
          rawRequest(
              "POST",
              "/api/realtime",
              token,
              Map.of("clientId", clientId, "subscriptions", List.of("updates/*")));
      assertEquals(204, upgraded.statusCode());

      HttpResponse<String> changedAuth =
          rawRequest(
              "POST",
              "/api/realtime",
              null,
              Map.of("clientId", clientId, "subscriptions", List.of("updates/*")));
      assertEquals(403, changedAuth.statusCode());
    }
  }

  @Test
  void realtimeValidationErrorsUseOfficialEnvelope() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    HttpResponse<String> missingClient =
        rawRequest("POST", "/api/realtime", token, Map.of("subscriptions", List.of("updates/*")));
    assertEquals(400, missingClient.statusCode());
    assertFieldError(
        missingClient,
        400,
        "Failed to subscribe.",
        "clientId",
        "validation_required",
        "Cannot be blank.");

    HttpResponse<String> arrayPayload = rawJsonRequest("POST", "/api/realtime", token, "[]");
    assertEquals(400, arrayPayload.statusCode());
    assertFieldError(
        arrayPayload,
        400,
        "Realtime subscription payload must be an object.",
        "body",
        "validation_invalid_value",
        "Request body must be a JSON object.");

    HttpResponse<String> longClientId =
        rawRequest(
            "POST",
            "/api/realtime",
            token,
            Map.of(
                "clientId", "a".repeat(256),
                "subscriptions", List.of()));
    assertEquals(400, longClientId.statusCode());
    assertFieldError(
        longClientId,
        400,
        "Failed to subscribe.",
        "clientId",
        "validation_length_too_long",
        "The value must be no more than 255 characters.");

    HttpResponse<InputStream> response =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/realtime"))
                .header("Accept", "text/event-stream")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, response.statusCode());
    try (SseReader events = new SseReader(response.body())) {
      SseEvent connect = events.next("PB_CONNECT");
      String clientId = mapper.readTree(connect.data()).get("clientId").asText();

      List<String> maximumSubscriptions = new java.util.ArrayList<>();
      for (int index = 0; index < 1000; index++) {
        maximumSubscriptions.add(String.valueOf(index));
      }
      HttpResponse<String> maximum =
          rawRequest(
              "POST",
              "/api/realtime",
              token,
              Map.of(
                  "clientId", clientId,
                  "subscriptions", maximumSubscriptions));
      assertEquals(204, maximum.statusCode());

      List<String> tooManySubscriptions = new java.util.ArrayList<>();
      for (int index = 0; index < 1001; index++) {
        tooManySubscriptions.add(String.valueOf(index));
      }
      HttpResponse<String> tooMany =
          rawRequest(
              "POST",
              "/api/realtime",
              token,
              Map.of(
                  "clientId", clientId,
                  "subscriptions", tooManySubscriptions));
      assertEquals(400, tooMany.statusCode());
      assertFieldError(
          tooMany,
          400,
          "Failed to subscribe.",
          "subscriptions",
          "validation_length_too_long",
          "The list must contain no more than 1000 items.");

      HttpResponse<String> longTopic =
          rawRequest(
              "POST",
              "/api/realtime",
              token,
              Map.of(
                  "clientId", clientId, "subscriptions", List.of("valid-topic", "a".repeat(2501))));
      assertEquals(400, longTopic.statusCode());
      JsonNode longTopicBody = mapper.readTree(longTopic.body());
      assertEquals("Failed to subscribe.", longTopicBody.get("message").asText());
      assertEquals(
          "validation_length_too_long",
          longTopicBody.get("data").get("subscriptions").get("1").get("code").asText());

      HttpResponse<String> boundaryTopic =
          rawRequest(
              "POST",
              "/api/realtime",
              token,
              Map.of("clientId", clientId, "subscriptions", List.of("a".repeat(2500))));
      assertEquals(204, boundaryTopic.statusCode());

      HttpResponse<String> invalidOptions =
          rawRequest(
              "POST",
              "/api/realtime",
              token,
              Map.of("clientId", clientId, "subscriptions", List.of("updates/*"), "options", "[]"));
      assertEquals(400, invalidOptions.statusCode());
      assertFieldError(
          invalidOptions,
          400,
          "Failed to subscribe.",
          "options",
          "validation_invalid_value",
          "Realtime subscription options must be an object.");
    }
  }

  @Test
  void realtimeSingleRecordSubscriptionsUseViewRuleAndRecordId() throws Exception {
    start();
    bootstrapSuperuser();
    String superuserToken = loginToken();

    request(
        "POST",
        "/api/collections",
        superuserToken,
        Map.of(
            "name", "realtime_users",
            "type", "auth",
            "fields", List.of(Map.of("name", "displayName", "type", "text"))));
    request(
        "POST",
        "/api/collections/realtime_users/records",
        superuserToken,
        Map.of(
            "email", "alice-realtime@example.com",
            "password", "Secret_456",
            "passwordConfirm", "Secret_456",
            "displayName", "Alice"));
    request(
        "POST",
        "/api/collections/realtime_users/records",
        superuserToken,
        Map.of(
            "email", "bob-realtime@example.com",
            "password", "Secret_456",
            "passwordConfirm", "Secret_456",
            "displayName", "Bob"));
    JsonNode aliceAuth =
        request(
            "POST",
            "/api/collections/realtime_users/auth-with-password",
            null,
            Map.of(
                "identity", "alice-realtime@example.com",
                "password", "Secret_456"));
    String aliceToken = aliceAuth.get("token").asText();
    String aliceId = aliceAuth.get("record").get("id").asText();

    request(
        "POST",
        "/api/collections",
        superuserToken,
        Map.of(
            "name", "owned_updates",
            "viewRule", "owner = @request.auth.id",
            "fields",
            List.of(
                Map.of("name", "owner", "type", "text", "required", true),
                Map.of("name", "title", "type", "text", "required", true))));
    JsonNode aliceRecord =
        request(
            "POST",
            "/api/collections/owned_updates/records",
            superuserToken,
            Map.of("owner", aliceId, "title", "Alice private"));
    JsonNode bobRecord =
        request(
            "POST",
            "/api/collections/owned_updates/records",
            superuserToken,
            Map.of("owner", "not-" + aliceId, "title", "Bob private"));

    HttpResponse<InputStream> response =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/realtime"))
                .header("Accept", "text/event-stream")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, response.statusCode());

    try (SseReader events = new SseReader(response.body())) {
      SseEvent connect = events.next("PB_CONNECT");
      String clientId = mapper.readTree(connect.data()).get("clientId").asText();
      String topic = "owned_updates/" + aliceRecord.get("id").asText();

      request(
          "POST",
          "/api/realtime",
          aliceToken,
          Map.of("clientId", clientId, "subscriptions", List.of(topic)));

      request(
          "PATCH",
          "/api/collections/owned_updates/records/" + bobRecord.get("id").asText(),
          superuserToken,
          Map.of("title", "Bob updated"));
      request(
          "PATCH",
          "/api/collections/owned_updates/records/" + aliceRecord.get("id").asText(),
          superuserToken,
          Map.of("title", "Alice updated"));

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

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "updates",
            "listRule", "",
            "viewRule", "",
            "fields",
            List.of(
                Map.of("name", "title", "type", "text", "required", true),
                Map.of("name", "status", "type", "text"))));

    HttpResponse<InputStream> response =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/realtime"))
                .header("Accept", "text/event-stream")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, response.statusCode());

    try (SseReader events = new SseReader(response.body())) {
      SseEvent connect = events.next("PB_CONNECT");
      String clientId = mapper.readTree(connect.data()).get("clientId").asText();
      String options =
          URLEncoder.encode(
              "{\"query\":{\"filter\":\"status = 'active'\"}}", StandardCharsets.UTF_8);

      request(
          "POST",
          "/api/realtime?clientId="
              + clientId
              + "&subscriptions%5B0%5D=updates/*&options="
              + options,
          token,
          null);

      request(
          "POST",
          "/api/collections/updates/records",
          token,
          Map.of(
              "title", "inactive",
              "status", "inactive"));
      JsonNode active =
          request(
              "POST",
              "/api/collections/updates/records",
              token,
              Map.of(
                  "title", "active",
                  "status", "active"));

      SseEvent event = events.next("updates/*");
      JsonNode data = mapper.readTree(event.data());
      assertEquals("create", data.get("action").asText());
      assertEquals(active.get("id").asText(), data.get("record").get("id").asText());
      assertEquals("active", data.get("record").get("title").asText());
    }
  }

  @Test
  void realtimeOptionsHeadersReachCollectionRules() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "header_updates",
            "listRule", "@request.headers.x-rule-token = 'allow' && @request.context = 'realtime'",
            "viewRule", "@request.headers.x-rule-token = 'allow' && @request.context = 'realtime'",
            "fields", List.of(Map.of("name", "title", "type", "text", "required", true))));

    HttpResponse<InputStream> response =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/realtime"))
                .header("Accept", "text/event-stream")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, response.statusCode());

    try (SseReader events = new SseReader(response.body())) {
      SseEvent connect = events.next("PB_CONNECT");
      String clientId = mapper.readTree(connect.data()).get("clientId").asText();
      String options =
          URLEncoder.encode("{\"headers\":{\"X-Rule-Token\":\"allow\"}}", StandardCharsets.UTF_8);
      request(
          "POST",
          "/api/realtime?clientId="
              + clientId
              + "&subscriptions%5B0%5D=header_updates/*&options="
              + options,
          null,
          null);

      JsonNode created =
          request(
              "POST",
              "/api/collections/header_updates/records",
              token,
              Map.of("title", "header event"));
      SseEvent event = events.next("header_updates/*");
      JsonNode data = mapper.readTree(event.data());
      assertEquals("create", data.get("action").asText());
      assertEquals(created.get("id").asText(), data.get("record").get("id").asText());
    }
  }

  @Test
  void realtimeOptionsQueryExpandRelationRecords() throws Exception {
    start();
    bootstrapSuperuser();
    String token = loginToken();

    JsonNode authors =
        request(
            "POST",
            "/api/collections",
            token,
            Map.of(
                "name", "authors",
                "listRule", "",
                "viewRule", "",
                "fields", List.of(Map.of("name", "name", "type", "text", "required", true))));
    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "posts",
            "listRule", "",
            "viewRule", "",
            "fields",
            List.of(
                Map.of("name", "title", "type", "text", "required", true),
                Map.of(
                    "name",
                    "author",
                    "type",
                    "relation",
                    "collectionId",
                    authors.get("id").asText()))));
    JsonNode author =
        request("POST", "/api/collections/authors/records", token, Map.of("name", "Ada"));

    HttpResponse<InputStream> response =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/realtime"))
                .header("Accept", "text/event-stream")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, response.statusCode());

    try (SseReader events = new SseReader(response.body())) {
      SseEvent connect = events.next("PB_CONNECT");
      String clientId = mapper.readTree(connect.data()).get("clientId").asText();
      String options =
          URLEncoder.encode(
              "{\"query\":{\"expand\":\"author\",\"fields\":\"id,expand.author.name\"}}",
              StandardCharsets.UTF_8);

      request(
          "POST",
          "/api/realtime?clientId=" + clientId + "&subscriptions%5B0%5D=posts/*&options=" + options,
          token,
          null);

      JsonNode post =
          request(
              "POST",
              "/api/collections/posts/records",
              token,
              Map.of("title", "Expandable realtime", "author", author.get("id").asText()));

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

    request(
        "POST",
        "/api/collections",
        token,
        Map.of(
            "name", "multipart_updates",
            "listRule", "",
            "viewRule", "",
            "fields", List.of(Map.of("name", "title", "type", "text", "required", true))));

    HttpResponse<InputStream> response =
        http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/realtime"))
                .header("Accept", "text/event-stream")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, response.statusCode());

    try (SseReader events = new SseReader(response.body())) {
      SseEvent connect = events.next("PB_CONNECT");
      String clientId = mapper.readTree(connect.data()).get("clientId").asText();

      HttpResponse<String> subscribe =
          rawMultipartRequest(
              "POST",
              "/api/realtime",
              token,
              Map.of("clientId", clientId, "subscriptions[0]", "multipart_updates/*"),
              Map.of());
      assertEquals(204, subscribe.statusCode());

      JsonNode created =
          request(
              "POST",
              "/api/collections/multipart_updates/records",
              token,
              Map.of("title", "created"));
      SseEvent event = events.next("multipart_updates/*");
      JsonNode data = mapper.readTree(event.data());
      assertEquals("create", data.get("action").asText());
      assertEquals(created.get("id").asText(), data.get("record").get("id").asText());
    }
  }

  private void start() throws IOException {
    server = TestDatabaseFactory.start(new ServerConfig("127.0.0.1", 0, tempDir, null, null, null));
  }

  private void bootstrapSuperuser() throws Exception {
    request(
        "POST",
        "/api/bootstrap/superuser",
        null,
        Map.of(
            "email", "root@example.com",
            "password", "Secret_123"));
  }

  private String loginToken() throws Exception {
    JsonNode auth =
        request(
            "POST",
            "/api/collections/_superusers/auth-with-password",
            null,
            Map.of(
                "identity", "root@example.com",
                "password", "Secret_123"));
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

  private String expiredToken(String token, String collectionName, String tokenConfig)
      throws Exception {
    String baseSecret =
        Files.readString(tempDir.resolve("pb_secret"), StandardCharsets.UTF_8).trim();
    TokenService tokenService = new TokenService(mapper, baseSecret);
    Map<String, Object> claims =
        new LinkedHashMap<>(
            tokenService.peek(token).orElseThrow(() -> new AssertionError("Invalid source token")));
    String tokenKey = String.valueOf(claims.getOrDefault("tokenKey", "")).trim();
    String signingSecret = tokenKey + collectionTokenSecret(collectionName, tokenConfig);
    return tokenService.create(claims, Duration.ofSeconds(-1), signingSecret);
  }

  private String collectionTokenSecret(String collectionName, String tokenConfig) throws Exception {
    if (usesRelationalStorage()) {
      try (var connection = openRelationalConnection();
          var query =
              connection.prepareStatement(
                  "SELECT "
                      + databaseIdentifier("options")
                      + " FROM "
                      + databaseIdentifier("_collections")
                      + " WHERE "
                      + databaseIdentifier("name")
                      + " = ?")) {
        query.setString(1, collectionName);
        try (var result = query.executeQuery()) {
          assertTrue(result.next());
          return mapper.readTree(result.getString(1)).path(tokenConfig).path("secret").asText();
        }
      }
    }
    JsonNode schema = mapper.readTree(tempDir.resolve("pb_schema.json").toFile());
    for (JsonNode collection : schema.withArray("collections")) {
      if (collectionName.equals(collection.path("name").asText())) {
        return collection.path(tokenConfig).path("secret").asText();
      }
    }
    throw new AssertionError(
        "Missing collection token secret for " + collectionName + " / " + tokenConfig);
  }

  private boolean usesRelationalStorage() {
    return switch (System.getProperty("storage", "json")
        .trim()
        .toLowerCase(java.util.Locale.ROOT)) {
      case "sqlite", "mysql", "mariadb", "postgres", "postgresql" -> true;
      default -> false;
    };
  }

  private boolean usesExternalRelationalStorage() {
    return switch (System.getProperty("storage", "json")
        .trim()
        .toLowerCase(java.util.Locale.ROOT)) {
      case "mysql", "mariadb", "postgres", "postgresql" -> true;
      default -> false;
    };
  }

  private java.sql.Connection openRelationalConnection() throws java.sql.SQLException {
    String storage =
        System.getProperty("storage", "json").trim().toLowerCase(java.util.Locale.ROOT);
    if ("sqlite".equals(storage)) {
      return java.sql.DriverManager.getConnection(
          "jdbc:sqlite:" + tempDir.resolve("pocketbase.db").toAbsolutePath());
    }
    String url = System.getProperty("db.url");
    String user = System.getProperty("db.user");
    String password = System.getProperty("db.password");
    if (url == null || url.isBlank()) {
      throw new IllegalStateException("External relational test database URL is unavailable.");
    }
    return user == null || user.isBlank()
        ? java.sql.DriverManager.getConnection(url)
        : java.sql.DriverManager.getConnection(url, user, password == null ? "" : password);
  }

  private String databaseIdentifier(String identifier) {
    boolean mysql =
        "mysql".equalsIgnoreCase(System.getProperty("storage"))
            || "mariadb".equalsIgnoreCase(System.getProperty("storage"));
    String quote = mysql ? "`" : "\"";
    String escaped = identifier.replace(quote, quote + quote);
    return mysql ? "`" + escaped + "`" : "\"" + escaped + "\"";
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

  private int authOutboxCount(String type, String email) throws IOException {
    Path outbox = tempDir.resolve("auth_requests.json");
    if (!Files.exists(outbox)) {
      return 0;
    }
    int count = 0;
    for (JsonNode request : mapper.readTree(outbox.toFile())) {
      if (type.equals(request.path("type").asText())
          && email.equalsIgnoreCase(request.path("email").asText())) {
        count++;
      }
    }
    return count;
  }

  private int authOriginCount(String collectionName, String recordId) throws Exception {
    if (usesRelationalStorage()) {
      String collectionId =
          request("GET", "/api/collections/" + collectionName, loginToken(), null)
              .get("id")
              .asText();
      try (var connection = openRelationalConnection();
          var statement =
              connection.prepareStatement(
                  "SELECT COUNT(*) FROM "
                      + databaseIdentifier("_authOrigins")
                      + " WHERE "
                      + databaseIdentifier("collectionRef")
                      + " = ? AND "
                      + databaseIdentifier("recordRef")
                      + " = ?")) {
        statement.setString(1, collectionId);
        statement.setString(2, recordId);
        try (var result = statement.executeQuery()) {
          return result.next() ? result.getInt(1) : 0;
        }
      }
    }
    Path origins = tempDir.resolve("auth_origins.json");
    if (!Files.exists(origins)) {
      return 0;
    }
    String collectionId =
        request("GET", "/api/collections/" + collectionName, loginToken(), null).get("id").asText();
    int count = 0;
    for (JsonNode origin : mapper.readTree(origins.toFile())) {
      String originCollection =
          origin.has("collectionRef")
              ? origin.path("collectionRef").asText()
              : origin.path("collectionId").asText();
      String originRecord =
          origin.has("recordRef")
              ? origin.path("recordRef").asText()
              : origin.path("recordId").asText();
      if (collectionId.equals(originCollection) && recordId.equals(originRecord)) {
        count++;
      }
    }
    return count;
  }

  private boolean cronExists(JsonNode crons, String id, String expression) {
    for (JsonNode cron : crons) {
      if (id.equals(cron.path("id").asText())
          && expression.equals(cron.path("expression").asText())) {
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
          long count =
              paths
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

  private boolean waitForLogMissing(String id, String token) throws Exception {
    String filter = URLEncoder.encode("id = '" + id + "'", StandardCharsets.UTF_8);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < deadline) {
      JsonNode logs = request("GET", "/api/logs?filter=" + filter, token, null);
      if (logs.get("totalItems").asInt() == 0) {
        return true;
      }
      Thread.sleep(50);
    }
    return false;
  }

  private JsonNode waitForLogs(String filter, String token, int expectedCount) throws Exception {
    String path = "/api/logs?filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    JsonNode last = null;
    while (System.nanoTime() < deadline) {
      last = request("GET", path, token, null);
      if (last.get("totalItems").asInt() >= expectedCount) {
        return last;
      }
      Thread.sleep(50);
    }
    throw new AssertionError(
        "Timed out waiting for "
            + expectedCount
            + " matching activity logs; last response="
            + last);
  }

  private void ageSqliteLogFixture(String id) throws Exception {
    Path database = tempDir.resolve("pocketbase.db");
    try (var connection =
        java.sql.DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        var statement =
            connection.prepareStatement("UPDATE _logs SET created = ?, updated = ? WHERE id = ?")) {
      statement.setString(1, "2000-01-01T00:00:00Z");
      statement.setString(2, "2000-01-01T00:00:00Z");
      statement.setString(3, id);
      assertEquals(1, statement.executeUpdate());
    }
  }

  private List<String> sqliteCustomIndexNames(String table) throws Exception {
    List<String> names = new java.util.ArrayList<>();
    Path database = tempDir.resolve("pocketbase.db");
    try (var connection =
        java.sql.DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        var statement = connection.createStatement();
        var results =
            statement.executeQuery("PRAGMA index_list(\"" + table.replace("\"", "\"\"") + "\")")) {
      while (results.next()) {
        String name = results.getString("name");
        if (name != null && name.startsWith("idx_")) {
          names.add(name);
        }
      }
    }
    names.sort(String::compareTo);
    return names;
  }

  private JsonNode findBy(JsonNode items, String field, String value) {
    for (JsonNode item : items) {
      if (value.equals(item.path(field).asText())) {
        return item;
      }
    }
    throw new AssertionError("Missing item with " + field + "=" + value);
  }

  private JsonNode request(String method, String path, String token, Object body) throws Exception {
    HttpResponse<String> response = rawRequest(method, path, token, body);
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new AssertionError(response.statusCode() + " " + response.body());
    }
    return response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
  }

  private JsonNode requestWithHeaders(
      String method, String path, String token, Object body, Map<String, String> headers)
      throws Exception {
    HttpResponse<String> response = rawRequest(method, path, token, body, headers);
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new AssertionError(response.statusCode() + " " + response.body());
    }
    return response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
  }

  private HttpResponse<String> rawRequest(String method, String path, String token, Object body)
      throws Exception {
    return rawRequest(method, path, token, body, Map.of());
  }

  private HttpResponse<String> rawRequest(
      String method, String path, String token, Object body, Map<String, String> headers)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
            .header("Accept", "application/json");
    headers.forEach(builder::header);
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder.header("Content-Type", "application/json");
      builder.method(
          method,
          HttpRequest.BodyPublishers.ofString(
              mapper.writeValueAsString(body), StandardCharsets.UTF_8));
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private RawHttpResponse rawDeclaredContentLengthRequest(
      String method, String path, long contentLength) throws Exception {
    URI base = URI.create(server.baseUrl());
    try (Socket socket = new Socket(base.getHost(), base.getPort())) {
      String request =
          method
              + " "
              + path
              + " HTTP/1.1\r\n"
              + "Host: "
              + base.getHost()
              + ":"
              + base.getPort()
              + "\r\n"
              + "Content-Type: application/octet-stream\r\n"
              + "Content-Length: "
              + contentLength
              + "\r\n"
              + "Connection: close\r\n\r\n";
      socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      socket.shutdownOutput();
      String raw = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      int headerEnd = raw.indexOf("\r\n\r\n");
      String headers = headerEnd < 0 ? raw : raw.substring(0, headerEnd);
      String body = headerEnd < 0 ? "" : raw.substring(headerEnd + 4);
      String statusLine = headers.lines().findFirst().orElse("");
      String[] statusParts = statusLine.split(" ", 3);
      int status = statusParts.length >= 2 ? Integer.parseInt(statusParts[1]) : 0;
      return new RawHttpResponse(status, body);
    }
  }

  private HttpResponse<String> rawJsonRequest(String method, String path, String token, String body)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> rawBodyRequest(
      String method, String path, String token, String contentType, byte[] body) throws Exception {
    return rawBodyRequest(method, path, token, contentType, body, Map.of());
  }

  private HttpResponse<String> rawBodyRequest(
      String method,
      String path,
      String token,
      String contentType,
      byte[] body,
      Map<String, String> headers)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
            .header("Accept", "application/json")
            .header("Content-Type", contentType)
            .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
    headers.forEach(builder::header);
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
      Map<String, MultipartFile> files)
      throws Exception {
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
      Map<String, MultipartFile> files)
      throws Exception {
    String boundary = "----pocketbase-java-test-boundary";
    byte[] body = multipartBody(boundary, fields, files);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
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
      String fieldMessage)
      throws IOException {
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
      String fieldMessagePrefix)
      throws IOException {
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
      String fieldMessagePrefix)
      throws IOException {
    JsonNode body = mapper.readTree(response.body());
    assertEquals(status, body.get("status").asInt());
    assertFalse(body.has("code"));
    assertTrue(body.get("message").asText().startsWith(messagePrefix));
    JsonNode fieldError = body.get("data").get(field);
    assertNotNull(fieldError, "Missing validation error for " + field);
    assertEquals(code, fieldError.get("code").asText());
    assertTrue(fieldError.get("message").asText().startsWith(fieldMessagePrefix));
  }

  private void assertErrorEnvelope(HttpResponse<String> response, int status, String message)
      throws IOException {
    JsonNode body = mapper.readTree(response.body());
    assertEquals(status, body.get("status").asInt());
    assertFalse(body.has("code"));
    assertEquals(message, body.get("message").asText());
    assertTrue(body.get("data").isObject());
    assertEquals(0, body.get("data").size());
  }

  private void appendZipEntry(Path source, Path target, String entryName, byte[] entryBytes)
      throws IOException {
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

  private JsonNode relationalBackupSnapshot(Path backup) throws IOException {
    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(backup))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if ("relational-backup.json".equals(entry.getName())) {
          ByteArrayOutputStream bytes = new ByteArrayOutputStream();
          zip.transferTo(bytes);
          return mapper.readTree(bytes.toByteArray());
        }
        zip.closeEntry();
      }
    }
    throw new AssertionError("Relational backup snapshot is missing.");
  }

  private String relationalBackupObjectSql(JsonNode snapshot, String type, String name) {
    for (JsonNode object : snapshot.path("objects")) {
      if (type.equals(object.path("type").asText()) && name.equals(object.path("name").asText())) {
        String sql = object.path("sql").asText();
        if (!sql.isBlank()) {
          return sql;
        }
      }
    }
    throw new AssertionError("Missing " + type + " object in relational backup: " + name);
  }

  private void removeEngineFromRelationalBackup(Path backup) throws IOException {
    Path replacement = Files.createTempFile(backup.getParent(), ".legacy-backup-", ".zip");
    boolean snapshotFound = false;
    try {
      try (ZipInputStream zipInput = new ZipInputStream(Files.newInputStream(backup));
          ZipOutputStream zipOutput = new ZipOutputStream(Files.newOutputStream(replacement))) {
        ZipEntry entry;
        while ((entry = zipInput.getNextEntry()) != null) {
          zipOutput.putNextEntry(new ZipEntry(entry.getName()));
          if ("relational-backup.json".equals(entry.getName())) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            zipInput.transferTo(bytes);
            JsonNode parsed = mapper.readTree(bytes.toByteArray());
            if (!(parsed instanceof ObjectNode snapshot)) {
              throw new AssertionError("Relational backup snapshot is not an object.");
            }
            snapshot.remove("engine");
            zipOutput.write(mapper.writeValueAsBytes(snapshot));
            snapshotFound = true;
          } else {
            zipInput.transferTo(zipOutput);
          }
          zipOutput.closeEntry();
          zipInput.closeEntry();
        }
      }
      if (!snapshotFound) {
        throw new AssertionError("Relational backup snapshot is missing.");
      }
      Files.move(replacement, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(replacement);
    }
  }

  private byte[] multipartBody(
      String boundary, Map<String, String> fields, Map<String, MultipartFile> files) {
    List<byte[]> chunks = new java.util.ArrayList<>();
    fields.forEach(
        (name, value) -> {
          String part =
              "--"
                  + boundary
                  + "\r\n"
                  + "Content-Disposition: form-data; name=\""
                  + name
                  + "\"\r\n\r\n"
                  + value
                  + "\r\n";
          chunks.add(part.getBytes(StandardCharsets.UTF_8));
        });
    files.forEach(
        (name, file) -> {
          String partHead =
              "--"
                  + boundary
                  + "\r\n"
                  + "Content-Disposition: form-data; name=\""
                  + name
                  + "\"; filename=\""
                  + file.name()
                  + "\"\r\n"
                  + "Content-Type: "
                  + file.contentType()
                  + "\r\n\r\n";
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
      return paths.anyMatch(
          path -> Files.isRegularFile(path) && path.getFileName().toString().contains(text));
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

  private void assertAuthSystemFields(JsonNode collection) {
    List<String> expected =
        List.of("id", "password", "tokenKey", "email", "emailVisibility", "verified");
    assertEquals(expected, fieldNames(collection).subList(0, expected.size()));
    for (int i = 0; i < expected.size(); i++) {
      assertTrue(collection.get("fields").get(i).get("system").asBoolean());
    }
    assertTrue(collection.get("fields").get(1).get("hidden").asBoolean());
    assertTrue(collection.get("fields").get(2).get("hidden").asBoolean());
  }

  private void downgradeAuthSystemFieldsFixture(String collectionName, String collectionId)
      throws Exception {
    if (usesRelationalStorage()) {
      try (var connection = openRelationalConnection()) {
        String rawSchema;
        try (var query =
            connection.prepareStatement(
                "SELECT "
                    + databaseIdentifier("schema")
                    + " FROM "
                    + databaseIdentifier("_collections")
                    + " WHERE "
                    + databaseIdentifier("name")
                    + " = ?")) {
          query.setString(1, collectionName);
          try (var result = query.executeQuery()) {
            assertTrue(result.next());
            rawSchema = result.getString(1);
          }
        }
        ArrayNode fields = (ArrayNode) mapper.readTree(rawSchema);
        downgradeAuthSystemFields(fields);
        try (var update =
            connection.prepareStatement(
                "UPDATE "
                    + databaseIdentifier("_collections")
                    + " SET "
                    + databaseIdentifier("schema")
                    + " = ? WHERE "
                    + databaseIdentifier("name")
                    + " = ?")) {
          update.setString(1, mapper.writeValueAsString(fields));
          update.setString(2, collectionName);
          update.executeUpdate();
        }
        try (var statement = connection.createStatement()) {
          statement.execute(
              "ALTER TABLE "
                  + databaseIdentifier(collectionName)
                  + " DROP COLUMN "
                  + databaseIdentifier("emailVisibility"));
        }
      }
      return;
    }

    Path schemaFile = tempDir.resolve("pb_schema.json");
    ObjectNode root = (ObjectNode) mapper.readTree(schemaFile.toFile());
    for (JsonNode collection : root.withArray("collections")) {
      if (collectionName.equals(collection.path("name").asText())) {
        downgradeAuthSystemFields((ArrayNode) collection.get("fields"));
      }
    }
    mapper.writerWithDefaultPrettyPrinter().writeValue(schemaFile.toFile(), root);

    Path recordsFile = tempDir.resolve("records").resolve(collectionId + ".jsonl");
    List<String> downgradedRecords = new java.util.ArrayList<>();
    for (String line : Files.readAllLines(recordsFile, StandardCharsets.UTF_8)) {
      ObjectNode record = (ObjectNode) mapper.readTree(line);
      record.remove("emailVisibility");
      downgradedRecords.add(mapper.writeValueAsString(record));
    }
    Files.write(recordsFile, downgradedRecords, StandardCharsets.UTF_8);
  }

  private void downgradeSystemCollectionIdsFixture() throws Exception {
    Map<String, String> replacements =
        Map.of(
            SystemCollections.SUPERUSERS_ID, SystemCollections.LEGACY_SUPERUSERS_ID,
            SystemCollections.MFAS_ID, SystemCollections.LEGACY_MFAS_ID,
            SystemCollections.OTPS_ID, SystemCollections.LEGACY_OTPS_ID,
            SystemCollections.EXTERNAL_AUTHS_ID, SystemCollections.LEGACY_EXTERNAL_AUTHS_ID,
            SystemCollections.AUTH_ORIGINS_ID, SystemCollections.LEGACY_AUTH_ORIGINS_ID);
    if (usesRelationalStorage()) {
      try (var connection = openRelationalConnection()) {
        connection.setAutoCommit(false);
        for (Map.Entry<String, String> replacement : replacements.entrySet()) {
          for (String table : List.of("_authOrigins", "_externalAuths", "_mfas", "_otps")) {
            try (var update =
                connection.prepareStatement(
                    "UPDATE "
                        + databaseIdentifier(table)
                        + " SET "
                        + databaseIdentifier("collectionRef")
                        + " = ? WHERE "
                        + databaseIdentifier("collectionRef")
                        + " = ?")) {
              update.setString(1, replacement.getValue());
              update.setString(2, replacement.getKey());
              update.executeUpdate();
            }
          }
          try (var update =
              connection.prepareStatement(
                  "UPDATE "
                      + databaseIdentifier("_authRequests")
                      + " SET "
                      + databaseIdentifier("collectionId")
                      + " = ? WHERE "
                      + databaseIdentifier("collectionId")
                      + " = ?")) {
            update.setString(1, replacement.getValue());
            update.setString(2, replacement.getKey());
            update.executeUpdate();
          }
        }

        List<Map<String, String>> collections = new java.util.ArrayList<>();
        try (var query =
            connection.prepareStatement(
                "SELECT "
                    + databaseIdentifier("id")
                    + ", "
                    + databaseIdentifier("schema")
                    + " FROM "
                    + databaseIdentifier("_collections"));
            var results = query.executeQuery()) {
          while (results.next()) {
            collections.add(
                Map.of(
                    "id",
                    results.getString(1),
                    "schema",
                    results.getString(2) == null ? "" : results.getString(2)));
          }
        }
        for (Map<String, String> collection : collections) {
          String schema = collection.get("schema");
          if (!schema.isBlank()) {
            JsonNode migrated = replaceJsonStrings(mapper.readTree(schema), replacements);
            try (var update =
                connection.prepareStatement(
                    "UPDATE "
                        + databaseIdentifier("_collections")
                        + " SET "
                        + databaseIdentifier("schema")
                        + " = ? WHERE "
                        + databaseIdentifier("id")
                        + " = ?")) {
              update.setString(1, mapper.writeValueAsString(migrated));
              update.setString(2, collection.get("id"));
              update.executeUpdate();
            }
          }
        }
        for (Map.Entry<String, String> replacement : replacements.entrySet()) {
          try (var update =
              connection.prepareStatement(
                  "UPDATE "
                      + databaseIdentifier("_collections")
                      + " SET "
                      + databaseIdentifier("id")
                      + " = ? WHERE "
                      + databaseIdentifier("id")
                      + " = ?")) {
            update.setString(1, replacement.getValue());
            update.setString(2, replacement.getKey());
            update.executeUpdate();
          }
        }
        connection.commit();
      }
    } else {
      Path schemaFile = tempDir.resolve("pb_schema.json");
      JsonNode schema = replaceJsonStrings(mapper.readTree(schemaFile.toFile()), replacements);
      mapper.writerWithDefaultPrettyPrinter().writeValue(schemaFile.toFile(), schema);

      for (String filename : List.of(
          "auth_origins.json",
          "external_auths.json",
          "mfas.json",
          "otps.json",
          "auth_requests.json")) {
        Path path = tempDir.resolve(filename);
        if (Files.exists(path)) {
          JsonNode records = replaceJsonStrings(mapper.readTree(path.toFile()), replacements);
          mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), records);
        }
      }
      for (Map.Entry<String, String> replacement : replacements.entrySet()) {
        for (String extension : List.of(".jsonl", ".json")) {
          Path source = tempDir.resolve("records").resolve(replacement.getKey() + extension);
          if (!Files.exists(source)) {
            continue;
          }
          JsonNode records;
          if (".jsonl".equals(extension)) {
            ArrayNode lines = mapper.createArrayNode();
            for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
              if (!line.isBlank()) {
                lines.add(replaceJsonStrings(mapper.readTree(line), replacements));
              }
            }
            Path target = source.resolveSibling(replacement.getValue() + extension);
            List<String> migrated = new java.util.ArrayList<>();
            lines.forEach(record -> migrated.add(record.toString()));
            Files.write(target, migrated, StandardCharsets.UTF_8);
            Files.delete(source);
          } else {
            records = replaceJsonStrings(mapper.readTree(source.toFile()), replacements);
            Path target = source.resolveSibling(replacement.getValue() + extension);
            mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), records);
            Files.delete(source);
          }
        }
      }
    }

    for (Map.Entry<String, String> replacement : replacements.entrySet()) {
      Path source = tempDir.resolve("storage").resolve(replacement.getKey());
      if (Files.exists(source)) {
        Files.move(source, source.resolveSibling(replacement.getValue()));
      }
    }
  }

  private JsonNode replaceJsonStrings(JsonNode value, Map<String, String> replacements) {
    if (value == null || value.isNull()) {
      return value;
    }
    if (value.isTextual()) {
      String replacement = replacements.get(value.asText());
      return replacement == null ? value : mapper.getNodeFactory().textNode(replacement);
    }
    if (value.isArray()) {
      ArrayNode copy = value.deepCopy();
      for (int i = 0; i < copy.size(); i++) {
        copy.set(i, replaceJsonStrings(copy.get(i), replacements));
      }
      return copy;
    }
    if (value.isObject()) {
      ObjectNode copy = value.deepCopy();
      List<String> names = new java.util.ArrayList<>();
      copy.fieldNames().forEachRemaining(names::add);
      for (String name : names) {
        copy.set(name, replaceJsonStrings(copy.get(name), replacements));
      }
      return copy;
    }
    return value;
  }

  private void downgradeAuthSystemFields(ArrayNode fields) {
    List<String> removed = List.of("id", "tokenKey", "emailVisibility");
    for (int i = fields.size() - 1; i >= 0; i--) {
      JsonNode field = fields.get(i);
      String name = field.path("name").asText();
      if (removed.contains(name)) {
        fields.remove(i);
      } else if (List.of("password", "email", "verified").contains(name)) {
        ((ObjectNode) field).put("system", false);
      }
    }
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

  private record RawHttpResponse(int status, String body) {
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
      assertTrue(messageReceived.await(15, TimeUnit.SECONDS));
      return message.get();
    }

    private void serveOne() {
      try (Socket socket = serverSocket.accept();
          BufferedReader reader =
              new BufferedReader(
                  new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
          BufferedWriter writer =
              new BufferedWriter(
                  new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
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
      return start(
          200,
          """
              {"access_token":"token-123","token_type":"Bearer"}
              """,
          200,
          """
              {
                "sub":"oauth-sub-123",
                "email":"oidc@example.com",
                "name":"OIDC User",
                "preferred_username":"oidc-user",
                "picture":"https://cdn.example.com/avatar.png"
              }
              """);
    }

    static FakeOAuth2Server start(
        int tokenStatus, String tokenBody, int userInfoStatus, String userInfoBody)
        throws IOException {
      HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      FakeOAuth2Server fake = new FakeOAuth2Server(server);
      server.createContext(
          "/token",
          exchange -> {
            String body =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            fake.lastTokenBody.set(body);
            byte[] bytes = tokenBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(tokenStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.createContext(
          "/userinfo",
          exchange -> {
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
      return executor
          .submit(
              () -> {
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
              })
          .get(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws IOException {
      executor.shutdownNow();
      reader.close();
    }
  }
}
