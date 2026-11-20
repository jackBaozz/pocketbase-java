package io.github.jackbaozz.pocketbase.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Service for interacting with the PocketBase Realtime API. */
public final class RealtimeService {
  private static final long INITIAL_RETRY_MILLIS = 200;
  private static final long MAX_RETRY_MILLIS = 5000;

  private final PocketBaseClient client;
  private final Map<String, List<Consumer<JsonNode>>> subscriptions = new ConcurrentHashMap<>();
  private Thread listenerThread;
  private String clientId;
  private CompletableFuture<Void> connectionReady;

  RealtimeService(PocketBaseClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  /**
   * Subscribes to a realtime topic. To subscribe to all records in a collection,
   * use the collection
   * name (e.g. "users"). To subscribe to a specific record, use
   * "collectionName/recordId".
   */
  public void subscribe(String topic, Consumer<JsonNode> callback) {
    Objects.requireNonNull(callback, "callback");
    String normalizedTopic = normalizeTopic(topic);
    CompletableFuture<Void> ready;
    synchronized (this) {
      subscriptions.computeIfAbsent(normalizedTopic, key -> new CopyOnWriteArrayList<>()).add(callback);
      if (clientId != null) {
        submitSubscriptions(clientId);
        return;
      }
      if (listenerThread == null) {
        startListener();
      }
      ready = connectionReady;
    }
    awaitSubscription(ready);
  }

  public synchronized void unsubscribe(String topic) {
    subscriptions.remove(normalizeTopic(topic));
    if (subscriptions.isEmpty()) {
      disconnect();
    } else if (clientId != null) {
      submitSubscriptions(clientId);
    }
  }

  public synchronized void disconnect() {
    if (listenerThread != null) {
      listenerThread.interrupt();
      listenerThread = null;
    }
    if (connectionReady != null) {
      connectionReady.completeExceptionally(new IOException("Realtime subscription was disconnected."));
    }
    connectionReady = null;
    clientId = null;
    subscriptions.clear();
  }

  private void startListener() {
    CompletableFuture<Void> initialConnect = new CompletableFuture<>();
    connectionReady = initialConnect;
    listenerThread = new Thread(() -> listen(initialConnect), "pocketbase-realtime");
    listenerThread.setDaemon(true);
    listenerThread.start();
  }

  private void listen(CompletableFuture<Void> initialConnect) {
    Thread worker = Thread.currentThread();
    long retryMillis = INITIAL_RETRY_MILLIS;
    Exception startupFailure = null;
    try {
      while (!worker.isInterrupted()) {
        try {
          readEvents();
          retryMillis = INITIAL_RETRY_MILLIS;
        } catch (IOException | RuntimeException failure) {
          if (!initialConnect.isDone()) {
            startupFailure = failure;
            break;
          }
        } finally {
          synchronized (this) {
            if (listenerThread == worker && clientId != null) {
              clientId = null;
              // All subscribers waiting through this reconnect share the pending handshake.
              connectionReady = new CompletableFuture<>();
            }
          }
        }
        Thread.sleep(retryMillis);
        retryMillis = Math.min(retryMillis * 2, MAX_RETRY_MILLIS);
      }
    } catch (InterruptedException interrupted) {
      worker.interrupt();
      startupFailure = interrupted;
    } finally {
      synchronized (this) {
        if (listenerThread == worker) {
          listenerThread = null;
          connectionReady.completeExceptionally(toRealtimeException(startupFailure == null
              ? new IOException("Realtime connection closed before subscribing.")
              : startupFailure));
          connectionReady = null;
        }
      }
    }
  }

  private void readEvents() throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(client.baseUri().resolve("api/realtime"))
        .GET()
        .header("Accept", "text/event-stream")
        .build();
    HttpResponse<InputStream> response = client.httpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
      if (response.statusCode() != 200) {
        throw new IOException("Failed to connect to realtime API, status: " + response.statusCode());
      }

      String event = "message";
      String eventId = "";
      boolean registered = false;
      String line;
      while (!Thread.currentThread().isInterrupted() && (line = reader.readLine()) != null) {
        if (line.startsWith("id:")) {
          eventId = line.substring(3).trim();
        } else if (line.startsWith("event:")) {
          event = line.substring(6).trim();
        } else if (line.startsWith("data:")) {
          String data = line.substring(5).trim();
          if ("PB_CONNECT".equals(event) && !registered) {
            String nextId = client.objectMapper().readTree(data).path("clientId").asText(eventId);
            synchronized (this) {
              if (listenerThread != Thread.currentThread()) {
                return;
              }
              if (nextId.isBlank()) {
                throw new IOException("Realtime connection did not return a client id.");
              }
              submitSubscriptions(nextId);
              clientId = nextId;
              connectionReady.complete(null);
              registered = true;
            }
          } else if (registered && !data.isEmpty() && !data.equals("{}")) {
            try {
              dispatch(event, client.objectMapper().readTree(data));
            } catch (IOException | RuntimeException ignored) {
              // Keep listening after an invalid event or a callback failure.
            }
          }
        } else if (line.isEmpty()) {
          event = "message";
          eventId = "";
        }
      }
      if (!registered) {
        throw new IOException("Realtime connection closed before subscribing.");
      }
    }
  }

  private void submitSubscriptions(String subscriptionClientId) {
    Map<String, Object> body = Map.of("clientId", subscriptionClientId, "subscriptions",
        new ArrayList<>(subscriptions.keySet()));
    client.send("POST", client.apiPath("realtime"), null, body, JsonNode.class);
  }

  private void dispatch(String event, JsonNode data) {
    if ("PB_CONNECT".equals(event)) {
      return; // handled via 'id:'
    }
    // In PocketBase, a broadcasted event is usually just the topic or "message".
    // The actual topic comes back in the payload's "action" or "record".
    // But local matching: find matching topics from the event name or data
    // For standard PocketBase data usually has { action: "...", record: {...} }
    subscriptions.forEach(
        (topic, callbacks) -> {
          boolean matches = false;
          if (topic.equals(event)) {
            matches = true;
          } else if ("message".equals(event)) {
            // Try to match based on record content if it's a global collection sub
            if (data.has("record")) {
              JsonNode record = data.get("record");
              String cName = record.has("collectionName") ? record.get("collectionName").asText() : "";
              String rId = record.has("id") ? record.get("id").asText() : "";
              if (topic.equals(cName) || topic.equals(cName + "/" + rId) || topic.equals("*")) {
                matches = true;
              }
            }
          }
          if (matches) {
            callbacks.forEach(cb -> cb.accept(data));
          }
        });
  }

  private static String normalizeTopic(String topic) {
    if (topic == null || topic.isBlank()) {
      throw new IllegalArgumentException("topic must not be blank");
    }
    String trimmed = topic.trim();
    if (trimmed.startsWith("@") || trimmed.contains("/")) {
      return trimmed;
    }
    return trimmed + "/*";
  }

  private static void awaitSubscription(CompletableFuture<Void> future) {
    try {
      future.get(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PocketBaseException("GET", URI.create("/api/realtime"), e);
    } catch (TimeoutException e) {
      throw new PocketBaseException("GET", URI.create("/api/realtime"), e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new PocketBaseException("GET", URI.create("/api/realtime"), cause);
    }
  }

  private static RuntimeException toRealtimeException(Exception e) {
    if (e instanceof RuntimeException runtime) {
      return runtime;
    }
    return new PocketBaseException("GET", URI.create("/api/realtime"), e);
  }
}
