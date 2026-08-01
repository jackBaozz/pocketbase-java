package io.github.jackbaozz.pocketbase.server;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class S3BackupRepositoryTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient http = HttpClient.newHttpClient();
  private final Map<String, byte[]> objects = new LinkedHashMap<>();
  private LocalPocketBase server;
  private HttpServer s3;
  private String previousStorage;
  private volatile CountDownLatch putStarted;
  private volatile CountDownLatch releasePut;

  @TempDir
  Path dataDir;

  @AfterEach
  void tearDown() {
    if (releasePut != null) {
      releasePut.countDown();
    }
    if (server != null) {
      server.close();
    }
    if (s3 != null) {
      s3.stop(0);
    }
    if (previousStorage == null) {
      System.clearProperty("storage");
    } else {
      System.setProperty("storage", previousStorage);
    }
  }

  @Test
  void relationalBackupsCanUseS3ProviderForCreateListDownloadAndDelete() throws Exception {
    s3 = fakeS3();
    s3.start();
    previousStorage = System.getProperty("storage");
    System.setProperty("storage", "sqlite");
    server = LocalPocketBase.start(new ServerConfig("127.0.0.1", 0, dataDir, null, null, null));
    bootstrapSuperuser();
    String token = loginSuperuser();

    request(
        "PATCH",
        "/api/settings",
        token,
        Map.of(
            "backups",
            Map.of(
                "s3",
                Map.of(
                    "enabled", true,
                    "endpoint", "http://127.0.0.1:" + s3.getAddress().getPort(),
                    "bucket", "bucket",
                    "region", "us-east-1",
                    "accessKey", "access",
                    "secret", "secret",
                    "forcePathStyle", true))));

    putStarted = new CountDownLatch(1);
    releasePut = new CountDownLatch(1);
    var create =
        http.sendAsync(
            jsonRequest("POST", "/api/backups", token, Map.of("name", "s3snap.zip")),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertTrue(putStarted.await(5, TimeUnit.SECONDS));

    JsonNode activeHealth = request("GET", "/api/health", token, null);
    assertFalse(activeHealth.get("data").get("canBackup").asBoolean());
    assertEquals(
        400, rawRequest("POST", "/api/backups", token, Map.of("name", "second.zip")).statusCode());
    assertEquals(400, rawRequest("DELETE", "/api/backups/s3snap.zip", token, null).statusCode());

    releasePut.countDown();
    HttpResponse<String> created = create.get(10, TimeUnit.SECONDS);
    assertEquals(204, created.statusCode());
    assertTrue(created.body().isBlank());
    assertTrue(objects.containsKey("s3snap.zip"));
    assertTrue(request("GET", "/api/health", token, null).get("data").get("canBackup").asBoolean());

    JsonNode list = request("GET", "/api/backups", token, null);
    assertTrue(list.isArray());
    assertEquals(1, list.size());
    assertEquals("s3snap.zip", list.get(0).get("key").asText());
    assertTrue(list.get(0).get("modified").isTextual());

    String fileToken = request("POST", "/api/files/token", token, null).get("token").asText();
    HttpResponse<byte[]> download =
        rawBytes("GET", "/api/backups/s3snap.zip?token=" + fileToken, null);
    assertEquals(200, download.statusCode());
    assertTrue(download.body().length > 0);

    HttpResponse<String> deleted = rawRequest("DELETE", "/api/backups/s3snap.zip", token, null);
    assertEquals(204, deleted.statusCode());
    assertFalse(objects.containsKey("s3snap.zip"));
  }

  private HttpServer fakeS3() throws Exception {
    HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    fake.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          String key = path.startsWith("/bucket/") ? path.substring("/bucket/".length()) : "";
          switch (exchange.getRequestMethod()) {
            case "PUT" -> {
              CountDownLatch started = putStarted;
              CountDownLatch release = releasePut;
              if (started != null && release != null) {
                started.countDown();
                try {
                  if (!release.await(10, TimeUnit.SECONDS)) {
                    exchange.sendResponseHeaders(503, -1);
                    break;
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  exchange.sendResponseHeaders(503, -1);
                  break;
                }
              }
              objects.put(key, exchange.getRequestBody().readAllBytes());
              exchange.sendResponseHeaders(200, -1);
            }
            case "GET" -> {
              if (exchange.getRequestURI().getRawQuery() != null
                  && exchange.getRequestURI().getRawQuery().contains("list-type=2")) {
                StringBuilder body = new StringBuilder("<ListBucketResult>");
                objects
                    .keySet()
                    .forEach(
                        name -> body.append("<Contents><Key>")
                            .append(name)
                            .append("</Key></Contents>"));
                body.append("</ListBucketResult>");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
              } else {
                byte[] bytes = objects.get(key);
                if (bytes == null) {
                  exchange.sendResponseHeaders(404, -1);
                } else {
                  exchange.getResponseHeaders().set("Content-Type", "application/zip");
                  exchange.sendResponseHeaders(200, bytes.length);
                  exchange.getResponseBody().write(bytes);
                }
              }
            }
            case "HEAD" -> {
              byte[] bytes = objects.get(key);
              if (bytes == null) {
                exchange.sendResponseHeaders(404, -1);
              } else {
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(bytes.length));
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.sendResponseHeaders(200, -1);
              }
            }
            case "DELETE" -> {
              objects.remove(key);
              exchange.sendResponseHeaders(204, -1);
            }
            default -> exchange.sendResponseHeaders(405, -1);
          }
          exchange.close();
        });
    return fake;
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

  private String loginSuperuser() throws Exception {
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

  private JsonNode request(String method, String path, String token, Object body) throws Exception {
    HttpResponse<String> response = rawRequest(method, path, token, body);
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new AssertionError(response.statusCode() + " " + response.body());
    }
    return response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
  }

  private HttpResponse<String> rawRequest(String method, String path, String token, Object body)
      throws Exception {
    return http.send(
        jsonRequest(method, path, token, body),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpRequest jsonRequest(String method, String path, String token, Object body)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
            .header("Accept", "application/json");
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
    return builder.build();
  }

  private HttpResponse<byte[]> rawBytes(String method, String path, String token) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
            .header("Accept", "application/octet-stream")
            .method(method, HttpRequest.BodyPublishers.noBody());
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
  }
}
