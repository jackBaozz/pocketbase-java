package io.github.jackbaozz.pocketbase.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class BackupConsistencyTest {

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
    ServerConfig config = new ServerConfig("127.0.0.1", 0, dataDir, null, null, null);
    server = TestDatabaseFactory.start(config);
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
    HttpRequest bootstrap =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/bootstrap/superuser"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"email\":\"admin@example.com\",\"password\":\"Password_123\"}"))
            .build();
    httpClient.send(bootstrap, HttpResponse.BodyHandlers.ofString());
  }

  private String getSuperuserToken() throws Exception {
    HttpRequest login =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/collections/_superusers/auth-with-password"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"identity\":\"admin@example.com\",\"password\":\"Password_123\"}"))
            .build();
    HttpResponse<String> response = httpClient.send(login, HttpResponse.BodyHandlers.ofString());
    return mapper.readTree(response.body()).get("token").asText();
  }

  @Test
  void testConcurrentBackupAndRestoreIntegrity() throws Exception {
    String token = getSuperuserToken();

    // 1. Create a posts collection
    String collectionJson =
        "{"
            + "\"name\":\"backup_posts\","
            + "\"type\":\"base\","
            + "\"schema\":[{\"name\":\"title\",\"type\":\"text\",\"required\":true}]"
            + "}";
    HttpRequest createCollReq =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/collections"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(collectionJson))
            .build();
    assertEquals(200, httpClient.send(createCollReq, HttpResponse.BodyHandlers.ofString()).statusCode());

    // 2. Insert initial records
    for (int i = 0; i < 5; i++) {
      HttpRequest insertReq =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl + "/api/collections/backup_posts/records"))
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"Post " + i + "\"}"))
              .build();
      assertEquals(200, httpClient.send(insertReq, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    // 3. Start a concurrent writer thread creating more records while backup is requested
    ExecutorService executor = Executors.newFixedThreadPool(2);
    AtomicBoolean running = new AtomicBoolean(true);
    CountDownLatch startLatch = new CountDownLatch(1);

    executor.submit(() -> {
      startLatch.countDown();
      int counter = 100;
      while (running.get()) {
        try {
          HttpRequest insertReq =
              HttpRequest.newBuilder()
                  .uri(URI.create(baseUrl + "/api/collections/backup_posts/records"))
                  .header("Authorization", "Bearer " + token)
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"Concurrent " + (counter++) + "\"}"))
                  .build();
          httpClient.send(insertReq, HttpResponse.BodyHandlers.ofString());
          Thread.sleep(10);
        } catch (Exception ignored) {
        }
      }
    });

    assertTrue(startLatch.await(5, TimeUnit.SECONDS));

    // 4. Create backup
    HttpRequest createBackupReq =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/backups"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"consistency_test.zip\"}"))
            .build();
    HttpResponse<String> backupRes = httpClient.send(createBackupReq, HttpResponse.BodyHandlers.ofString());
    assertEquals(204, backupRes.statusCode());

    running.set(false);
    executor.shutdown();
    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

    // 5. Verify backup file exists and validate ZIP CRC
    Path backupZip = dataDir.resolve("backups").resolve("consistency_test.zip");
    assertTrue(Files.exists(backupZip));
    try (ZipFile zip = new ZipFile(backupZip.toFile())) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        assertNotNull(entry.getName());
        try (InputStream is = zip.getInputStream(entry)) {
          byte[] buffer = new byte[8192];
          while (is.read(buffer) > 0) {
            // Drain to verify CRC
          }
        }
      }
    }

    // 6. Restore the backup
    HttpRequest restoreReq =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/backups/consistency_test.zip/restore"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    HttpResponse<String> restoreRes = httpClient.send(restoreReq, HttpResponse.BodyHandlers.ofString());
    assertEquals(204, restoreRes.statusCode());

    // 7. Verify collection records exist after restore
    HttpRequest listReq =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/collections/backup_posts/records"))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
    HttpResponse<String> listRes = httpClient.send(listReq, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, listRes.statusCode());
    JsonNode listBody = mapper.readTree(listRes.body());
    assertTrue(listBody.get("totalItems").asInt() >= 5);
  }
}
