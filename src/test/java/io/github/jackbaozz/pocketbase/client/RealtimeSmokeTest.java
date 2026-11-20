package io.github.jackbaozz.pocketbase.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RealtimeSmokeTest {
  @Test
  void waitsForTheNewServerSubscriptionBeforeReturningToCallersDuringReconnect() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var workers = Executors.newCachedThreadPool();
    var closeFirstStream = new CountDownLatch(1);
    var secondStreamOpened = new CountDownLatch(1);
    var sendSecondId = new CountDownLatch(1);
    var acknowledgeSecondPost = new CountDownLatch(1);
    var secondSubscribed = new CountDownLatch(1);
    var stopStreams = new CountDownLatch(1);
    var connectionCount = new AtomicInteger();
    var subscriptionRequests = new LinkedBlockingQueue<JsonNode>();
    var events = new LinkedBlockingQueue<JsonNode>();
    var mapper = new ObjectMapper();
    server.setExecutor(workers);
    server.createContext("/api/realtime", exchange -> {
      try {
        if ("POST".equals(exchange.getRequestMethod())) {
          JsonNode body = mapper.readTree(exchange.getRequestBody());
          subscriptionRequests.add(body);
          if ("connection-2".equals(body.path("clientId").asText())) {
            if (!acknowledgeSecondPost.await(10, TimeUnit.SECONDS)) {
              return;
            }
            exchange.sendResponseHeaders(204, -1);
            secondSubscribed.countDown();
          } else {
            exchange.sendResponseHeaders(204, -1);
          }
          return;
        }
        int sequence = connectionCount.incrementAndGet();
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        if (sequence == 2) {
          secondStreamOpened.countDown();
          if (!sendSecondId.await(10, TimeUnit.SECONDS)) {
            return;
          }
        }
        var output = exchange.getResponseBody();
        output.write(("event: PB_CONNECT\ndata: {\"clientId\":\"connection-" + sequence + "\"}\n\n")
            .getBytes(StandardCharsets.UTF_8));
        output.flush();
        if (sequence == 1) {
          closeFirstStream.await(10, TimeUnit.SECONDS);
          return;
        }
        if (secondSubscribed.await(10, TimeUnit.SECONDS)) {
          output.write("event: records/*\ndata: {\"action\":\"create\"}\n\n".getBytes(StandardCharsets.UTF_8));
          output.flush();
        }
        stopStreams.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (IOException ignored) {
        // The test may close the client while the fixture is finishing its stream.
      } finally {
        exchange.close();
      }
    });
    server.start();

    var realtime = PocketBaseClient.builder("http://127.0.0.1:" + server.getAddress().getPort())
        .build().realtime();
    try {
      realtime.subscribe("records", events::add);
      assertSubscription(subscriptionRequests.poll(5, TimeUnit.SECONDS), "connection-1", Set.of("records/*"));
      closeFirstStream.countDown();
      assertTrue(secondStreamOpened.await(5, TimeUnit.SECONDS));

      var callerStarted = new CountDownLatch(1);
      var subscribing = workers.submit(() -> {
        callerStarted.countDown();
        realtime.subscribe("new_topic", events::add);
      });
      assertTrue(callerStarted.await(5, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> subscribing.get(250, TimeUnit.MILLISECONDS));
      sendSecondId.countDown();
      assertSubscription(subscriptionRequests.poll(5, TimeUnit.SECONDS), "connection-2",
          Set.of("records/*", "new_topic/*"));
      assertThrows(TimeoutException.class, () -> subscribing.get(250, TimeUnit.MILLISECONDS));
      acknowledgeSecondPost.countDown();
      subscribing.get(5, TimeUnit.SECONDS);
      JsonNode event = events.poll(5, TimeUnit.SECONDS);
      assertNotNull(event, "The existing callback should receive events after reconnecting");
      assertEquals("create", event.path("action").asText());
    } finally {
      closeFirstStream.countDown();
      sendSecondId.countDown();
      acknowledgeSecondPost.countDown();
      stopStreams.countDown();
      realtime.disconnect();
      server.stop(0);
      workers.shutdownNow();
    }
  }

  private static void assertSubscription(JsonNode body, String clientId, Set<String> topics) {
    assertNotNull(body, "The SDK should submit subscriptions when the stream connects");
    assertEquals(clientId, body.path("clientId").asText());
    Set<String> actualTopics = new HashSet<>();
    body.path("subscriptions").forEach(topic -> actualTopics.add(topic.asText()));
    assertEquals(topics, actualTopics);
  }
}
