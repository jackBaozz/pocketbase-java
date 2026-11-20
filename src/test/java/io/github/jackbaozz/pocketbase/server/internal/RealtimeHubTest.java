package io.github.jackbaozz.pocketbase.server.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RealtimeHubTest {
  @Test
  void updatesSubscriptionsWhileAnOAuthEventWriteIsBlocked() throws Exception {
    var hub = new RealtimeHub(new ObjectMapper());
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var workers = Executors.newCachedThreadPool();
    var pauseWrites = new AtomicBoolean();
    var writeBlocked = new CountDownLatch(1);
    var releaseWrite = new CountDownLatch(1);
    server.setExecutor(workers);
    server.createContext("/api/realtime", exchange -> {
      exchange.setStreams(null, new FilterOutputStream(exchange.getResponseBody()) {
        @Override
        public void flush() throws IOException {
          if (pauseWrites.get()) {
            writeBlocked.countDown();
            try {
              if (!releaseWrite.await(10, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting to finish the test event write.");
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IOException(e);
            }
          }
          super.flush();
        }
      });
      hub.connect(exchange, "127.0.0.1");
    });
    server.start();

    try {
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/realtime"))
              .timeout(Duration.ofSeconds(5)).GET().build();
      var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
        String id = reader.readLine();
        assertTrue(id != null && id.startsWith("id: "));
        String clientId = id.substring("id: ".length());
        while (!reader.readLine().isEmpty()) {
          // Drain the PB_CONNECT event before pausing the next flush.
        }
        hub.subscribe(clientId, List.of("@oauth2"), null);

        pauseWrites.set(true);
        var send =
            workers.submit(() -> hub.sendOAuth2Redirect(clientId, "127.0.0.1", Map.of("code", "local-test-value")));
        try {
          assertTrue(writeBlocked.await(5, TimeUnit.SECONDS));
          workers.submit(() -> hub.subscribe(clientId, List.of("records/*"), null)).get(4, TimeUnit.SECONDS);
          assertFalse(send.isDone(), "Subscription updates must finish before the stream unblocks");
          assertFalse(hub.sendOAuth2Redirect(clientId, "127.0.0.1", Map.of()));
        } finally {
          releaseWrite.countDown();
        }
        assertTrue(send.get(5, TimeUnit.SECONDS));
      }
    } finally {
      releaseWrite.countDown();
      hub.close();
      server.stop(0);
      workers.shutdownNow();
    }
  }
}
