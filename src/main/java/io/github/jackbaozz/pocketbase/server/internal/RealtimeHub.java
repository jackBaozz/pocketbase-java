package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

/** In-memory SSE broker for PocketBase-style realtime record events. */
public final class RealtimeHub {
  private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
  private static final int MAX_CLIENTS = 1000;
  private static final int MAX_CLIENTS_PER_IP = 100;
  private static final long MAX_CONNECTION_NANOS = Duration.ofMinutes(30).toNanos();
  private static final String OAUTH2_TOPIC = "@oauth2";

  private final ObjectMapper mapper;
  private final Map<String, Client> clients = new ConcurrentHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();

  public RealtimeHub(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public void connect(HttpExchange exchange) throws IOException {
    connect(exchange, "");
  }

  public void connect(HttpExchange exchange, String remoteIp) throws IOException {
    if (closed.get()) {
      throw new ApiException(503, "Realtime service is shutting down.");
    }
    String clientId = IdGenerator.id();
    Client client = new Client(clientId, exchange.getResponseBody(), remoteIp);
    synchronized (clients) {
      if (closed.get()) {
        throw new ApiException(503, "Realtime service is shutting down.");
      }
      if (clients.size() >= MAX_CLIENTS) {
        throw new ApiException(503, "Realtime service is temporarily at capacity.");
      }
      if (!client.remoteIp.isBlank()
          && clients.values().stream()
              .filter(existing -> client.remoteIp.equals(existing.remoteIp))
              .count() >= MAX_CLIENTS_PER_IP) {
        throw new ApiException(429, "Too many realtime connections from this address.");
      }
      clients.put(clientId, client);
    }

    try {
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
      exchange.getResponseHeaders().set("Cache-Control", "no-cache");
      exchange.getResponseHeaders().set("Connection", "keep-alive");
      exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
      exchange.sendResponseHeaders(200, 0);
      try {
        client.send("PB_CONNECT", Map.of("clientId", clientId), clientId);
      } catch (IOException ignored) {
        return;
      }
      while (!Thread.currentThread().isInterrupted() && client.awaitHeartbeat()) {
        try {
          client.comment("keepalive");
        } catch (IOException ignored) {
          break;
        }
      }
    } finally {
      synchronized (clients) {
        clients.remove(clientId);
      }
      client.close();
    }
  }

  public void subscribe(String clientId, List<String> topics, RequestPrincipal principal) {
    subscribe(clientId, topics, SubscriptionOptions.empty(), principal);
  }

  public void subscribe(
      String clientId,
      List<String> topics,
      SubscriptionOptions options,
      RequestPrincipal principal) {
    subscribe(clientId, topics, options, principal, "");
  }

  public void subscribe(
      String clientId,
      List<String> topics,
      SubscriptionOptions options,
      RequestPrincipal principal,
      String remoteIp) {
    Client client = clients.get(clientId);
    if (client == null) {
      throw new ApiException(404, "Missing or invalid client id.");
    }
    List<String> requested = topics == null ? List.of() : topics;
    Set<Subscription> subscriptions = new LinkedHashSet<>();
    for (String topic : requested) {
      if (topic == null || topic.isBlank()) {
        continue;
      }
      subscriptions.add(Subscription.parse(topic, mapper).merge(options));
    }
    boolean onlyOAuth2 = requested.size() == 1 && OAUTH2_TOPIC.equals(requested.get(0));
    client.setSubscriptions(subscriptions, principal, onlyOAuth2, remoteIp);
  }

  public boolean sendOAuth2Redirect(String clientId, String remoteIp, Map<String, Object> payload) {
    Client client = clients.get(clientId);
    if (client == null) {
      return false;
    }
    try {
      return client.sendOAuth2Redirect(remoteIp, payload);
    } catch (IOException e) {
      client.close();
      clients.remove(client.id());
      return false;
    }
  }

  public SubscriptionOptions parseSubscriptionOptions(String options) {
    return SubscriptionOptions.parse(options, mapper);
  }

  public void publish(
      String collectionName,
      String collectionId,
      String recordId,
      String action,
      BiFunction<Subscription, RequestPrincipal, Map<String, Object>> recordFactory) {
    if (closed.get()) {
      return;
    }
    for (Client client : new ArrayList<>(clients.values())) {
      ClientSubscriptions state = client.state;
      for (Subscription subscription : state.topics()) {
        if (!subscription.matches(collectionName, collectionId, recordId)) {
          continue;
        }
        Map<String, Object> record = recordFactory.apply(subscription, state.principal());
        if (record == null) {
          continue;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("record", record);
        try {
          client.send(subscription.topic(), payload, null);
        } catch (IOException e) {
          client.close();
          clients.remove(client.id());
          break;
        }
      }
    }
  }

  /** Closes every active SSE stream and wakes heartbeat waiters. */
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    List<Client> active;
    synchronized (clients) {
      active = new ArrayList<>(clients.values());
      clients.clear();
    }
    for (Client client : active) {
      client.close();
    }
  }

  public record SubscriptionOptions(Map<String, String> query, Map<String, String> headers) {
    static SubscriptionOptions empty() {
      return new SubscriptionOptions(Map.of(), Map.of());
    }

    static SubscriptionOptions parse(String rawOptions, ObjectMapper mapper) {
      if (rawOptions == null || rawOptions.isBlank()) {
        return empty();
      }
      JsonNode options;
      try {
        options = mapper.readTree(rawOptions);
      } catch (IOException e) {
        throw new ApiException(
            400,
            "Failed to subscribe.",
            ApiErrors.invalidField("options", "Invalid realtime subscription options."));
      }
      if (options == null || !options.isObject()) {
        throw new ApiException(
            400,
            "Failed to subscribe.",
            ApiErrors.invalidField("options", "Realtime subscription options must be an object."));
      }

      Map<String, String> query = new LinkedHashMap<>();
      Map<String, String> headers = new LinkedHashMap<>();
      mergeObject(options.get("query"), mapper, query);
      mergeObject(options.get("headers"), mapper, headers);
      return new SubscriptionOptions(Map.copyOf(query), Map.copyOf(headers));
    }

    private static void mergeObject(
        JsonNode node, ObjectMapper mapper, Map<String, String> target) {
      if (node == null || node.isNull()) {
        return;
      }
      if (!node.isObject()) {
        throw new ApiException(
            400,
            "Failed to subscribe.",
            ApiErrors.invalidField(
                "options", "Realtime subscription options query and headers must be objects."));
      }
      node.fields()
          .forEachRemaining(
              entry -> target.put(entry.getKey(), stringify(entry.getValue(), mapper)));
    }

    private static String stringify(JsonNode value, ObjectMapper mapper) {
      if (value == null || value.isNull()) {
        return "";
      }
      if (value.isValueNode()) {
        return value.asText("");
      }
      try {
        return mapper.writeValueAsString(value);
      } catch (IOException e) {
        throw new ApiException(
            400,
            "Failed to subscribe.",
            ApiErrors.invalidField("options", "Invalid realtime subscription options."));
      }
    }
  }

  public record Subscription(
      String topic,
      String collection,
      String recordId,
      boolean wildcard,
      Map<String, String> query,
      Map<String, String> headers) {
    static Subscription parse(String topic, ObjectMapper mapper) {
      String[] pieces = topic.split("\\?", 2);
      String target = pieces[0];
      int slash = target.indexOf('/');
      if (slash <= 0 || slash == target.length() - 1) {
        return new Subscription(topic, target, "", false, Map.of(), Map.of());
      }
      String collection = target.substring(0, slash);
      String record = target.substring(slash + 1);
      Map<String, String> query =
          pieces.length == 2 ? parseQuery(pieces[1]) : new LinkedHashMap<>();
      Map<String, String> headers = new LinkedHashMap<>();
      String options = query.remove("options");
      if (options != null && !options.isBlank()) {
        SubscriptionOptions parsed = SubscriptionOptions.parse(options, mapper);
        query.putAll(parsed.query());
        headers.putAll(parsed.headers());
      }
      return new Subscription(
          topic, collection, record, "*".equals(record), Map.copyOf(query), Map.copyOf(headers));
    }

    Subscription merge(SubscriptionOptions inherited) {
      if (inherited == null || (inherited.query().isEmpty() && inherited.headers().isEmpty())) {
        return this;
      }
      Map<String, String> mergedQuery = new LinkedHashMap<>(inherited.query());
      mergedQuery.putAll(query);
      Map<String, String> mergedHeaders = new LinkedHashMap<>(inherited.headers());
      mergedHeaders.putAll(headers);
      return new Subscription(
          topic,
          collection,
          recordId,
          wildcard,
          Map.copyOf(mergedQuery),
          Map.copyOf(mergedHeaders));
    }

    boolean matches(String collectionName, String collectionId, String changedRecordId) {
      boolean collectionMatches =
          collection.equals(collectionName) || collection.equals(collectionId);
      if (!collectionMatches) {
        return false;
      }
      return wildcard || recordId.equals(changedRecordId);
    }

    public String filter() {
      return query.get("filter");
    }

    private static Map<String, String> parseQuery(String query) {
      Map<String, String> values = new LinkedHashMap<>();
      if (query == null || query.isBlank()) {
        return values;
      }
      for (String pair : query.split("&")) {
        if (pair.isBlank()) {
          continue;
        }
        int split = pair.indexOf('=');
        String key = split >= 0 ? pair.substring(0, split) : pair;
        String value = split >= 0 ? pair.substring(split + 1) : "";
        values.put(decode(key), decode(value));
      }
      return values;
    }

    private static String decode(String value) {
      return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
  }

  private record ClientSubscriptions(Set<Subscription> topics, RequestPrincipal principal) {
  }

  private final class Client {
    private final String id;
    private final OutputStream output;
    private final String remoteIp;
    private final long startedAtNanos = System.nanoTime();
    private final CountDownLatch closed = new CountDownLatch(1);
    private final AtomicBoolean closedOnce = new AtomicBoolean();
    private final Object subscriptionLock = new Object();
    private volatile ClientSubscriptions state = new ClientSubscriptions(Set.of(), null);
    private String oauth2SubscriptionIp = "";

    private Client(String id, OutputStream output, String remoteIp) {
      this.id = id;
      this.output = output;
      this.remoteIp = remoteIp == null ? "" : remoteIp;
    }

    String id() {
      return id;
    }

    void setSubscriptions(
        Set<Subscription> subscriptions,
        RequestPrincipal principal,
        boolean onlyOAuth2,
        String subscriptionIp) {
      synchronized (subscriptionLock) {
        if (state.principal() != null && !samePrincipal(state.principal(), principal)) {
          throw new ApiException(
              403,
              "The current and the previous request authorization don't match.");
        }
        if (onlyOAuth2 && state.topics().isEmpty()) {
          oauth2SubscriptionIp = subscriptionIp == null ? "" : subscriptionIp;
        } else if (subscriptions.stream().noneMatch(topic -> OAUTH2_TOPIC.equals(topic.topic()))) {
          oauth2SubscriptionIp = "";
        }
        state = new ClientSubscriptions(Set.copyOf(subscriptions), principal);
      }
    }

    boolean sendOAuth2Redirect(String candidateIp, Map<String, Object> payload)
        throws IOException {
      synchronized (subscriptionLock) {
        Set<Subscription> remaining = new LinkedHashSet<>(state.topics());
        if (!remaining.removeIf(subscription -> OAUTH2_TOPIC.equals(subscription.topic()))) {
          return false;
        }
        state = new ClientSubscriptions(Set.copyOf(remaining), state.principal());
        String subscriptionIp = oauth2SubscriptionIp;
        oauth2SubscriptionIp = "";
        if (!remoteIp.isBlank()
            && !remoteIp.equals(candidateIp)
            && (subscriptionIp.isBlank() || !subscriptionIp.equals(candidateIp))) {
          return false;
        }
      }
      // The callback is consumed before writing so a slow stream cannot stall subscription updates.
      send(OAUTH2_TOPIC, payload, null);
      return true;
    }

    private boolean samePrincipal(RequestPrincipal left, RequestPrincipal right) {
      if (left == null || right == null) {
        return left == right;
      }
      return left.superuser() == right.superuser()
          && Objects.equals(left.id(), right.id())
          && Objects.equals(left.collectionId(), right.collectionId())
          && Objects.equals(left.collectionName(), right.collectionName());
    }

    boolean awaitHeartbeat() {
      try {
        long remaining = MAX_CONNECTION_NANOS - (System.nanoTime() - startedAtNanos);
        if (remaining <= 0) {
          return false;
        }
        return !closed.await(Math.min(remaining, HEARTBEAT_INTERVAL.toNanos()), TimeUnit.NANOSECONDS)
            && System.nanoTime() - startedAtNanos < MAX_CONNECTION_NANOS;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    synchronized void send(String event, Object data, String id) throws IOException {
      if (id != null && !id.isBlank()) {
        output.write(("id: " + id + "\n").getBytes(StandardCharsets.UTF_8));
      }
      output.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
      output.write(
          ("data: " + mapper.writeValueAsString(data) + "\n\n").getBytes(StandardCharsets.UTF_8));
      output.flush();
    }

    synchronized void comment(String message) throws IOException {
      output.write((": " + message + "\n\n").getBytes(StandardCharsets.UTF_8));
      output.flush();
    }

    void close() {
      if (!closedOnce.compareAndSet(false, true)) {
        return;
      }
      closed.countDown();
      try {
        output.close();
      } catch (IOException ignored) {
        // connection already closed
      }
    }
  }
}
