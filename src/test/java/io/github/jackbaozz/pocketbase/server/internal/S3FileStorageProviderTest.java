package io.github.jackbaozz.pocketbase.server.internal;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.github.jackbaozz.pocketbase.server.internal.storage.S3FileStorageProvider;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class S3FileStorageProviderTest {
  private HttpServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void providerCanPutGetListStatDeleteAndSignUrlsAgainstS3CompatibleEndpoint() throws Exception {
    Map<String, byte[]> objects = new LinkedHashMap<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          String key = path.startsWith("/bucket/") ? path.substring("/bucket/".length()) : "";
          switch (exchange.getRequestMethod()) {
            case "PUT" -> {
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
                exchange.close();
                return;
              }
              byte[] bytes = objects.get(key);
              if (bytes == null) {
                exchange.sendResponseHeaders(404, -1);
              } else {
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
              }
            }
            case "HEAD" -> {
              byte[] bytes = objects.get(key);
              if (bytes == null) {
                exchange.sendResponseHeaders(404, -1);
              } else {
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(bytes.length));
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
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
    server.start();

    String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    S3FileStorageProvider provider =
        new S3FileStorageProvider(endpoint, "us-east-1", "bucket", "access", "secret", true);

    provider.put(
        "folder/test.txt",
        new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)),
        5,
        "text/plain");
    assertEquals(
        "hello",
        new String(
            provider.get("folder/test.txt").orElseThrow().readAllBytes(), StandardCharsets.UTF_8));
    assertTrue(provider.list("folder/").contains("folder/test.txt"));
    assertEquals(5, provider.stat("folder/test.txt").orElseThrow().size());
    assertTrue(
        provider.signedUrl("folder/test.txt", 60).orElseThrow().contains("X-Amz-Signature="));
    provider.delete("folder/test.txt");
    assertTrue(provider.get("folder/test.txt").isEmpty());
  }
}
