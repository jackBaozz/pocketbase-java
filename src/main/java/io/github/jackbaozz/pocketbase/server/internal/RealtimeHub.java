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
import java.util.function.BiFunction;

/**
 * In-memory SSE broker for PocketBase-style realtime record events.
 */
public final class RealtimeHub {
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    private final ObjectMapper mapper;
    private final Map<String, Client> clients = new ConcurrentHashMap<>();

    public RealtimeHub(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void connect(HttpExchange exchange) throws IOException {
        String clientId = IdGenerator.id();
        Client client = new Client(clientId, exchange.getResponseBody());
        clients.put(clientId, client);

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);

        try {
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
            clients.remove(clientId);
            client.close();
        }
    }

    public void subscribe(String clientId, List<String> topics, RequestPrincipal principal) {
        subscribe(clientId, topics, SubscriptionOptions.empty(), principal);
    }

    public void subscribe(String clientId, List<String> topics, SubscriptionOptions options, RequestPrincipal principal) {
        Client client = clients.get(clientId);
        if (client == null) {
            throw new ApiException(404, "Realtime client not found.");
        }
        Set<Subscription> subscriptions = new LinkedHashSet<>();
        for (String topic : topics == null ? List.<String>of() : topics) {
            if (topic == null || topic.isBlank()) {
                continue;
            }
            subscriptions.add(Subscription.parse(topic, mapper).merge(options));
        }
        client.setSubscriptions(subscriptions, principal);
    }

    public SubscriptionOptions parseSubscriptionOptions(String options) {
        return SubscriptionOptions.parse(options, mapper);
    }

    public void publish(
            String collectionName,
            String collectionId,
            String recordId,
            String action,
            BiFunction<Subscription, RequestPrincipal, Map<String, Object>> recordFactory
    ) {
        for (Client client : new ArrayList<>(clients.values())) {
            for (Subscription subscription : client.subscriptions()) {
                if (!subscription.matches(collectionName, collectionId, recordId)) {
                    continue;
                }
                Map<String, Object> record = recordFactory.apply(subscription, client.principal());
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
                throw new ApiException(400, "Failed to subscribe.", ApiErrors.invalidField("options", "Invalid realtime subscription options."));
            }
            if (options == null || !options.isObject()) {
                throw new ApiException(400, "Failed to subscribe.", ApiErrors.invalidField("options", "Realtime subscription options must be an object."));
            }

            Map<String, String> query = new LinkedHashMap<>();
            Map<String, String> headers = new LinkedHashMap<>();
            mergeObject(options.get("query"), mapper, query);
            mergeObject(options.get("headers"), mapper, headers);
            return new SubscriptionOptions(Map.copyOf(query), Map.copyOf(headers));
        }

        private static void mergeObject(JsonNode node, ObjectMapper mapper, Map<String, String> target) {
            if (node == null || node.isNull()) {
                return;
            }
            if (!node.isObject()) {
                throw new ApiException(400, "Failed to subscribe.", ApiErrors.invalidField("options", "Realtime subscription options query and headers must be objects."));
            }
            node.fields().forEachRemaining(entry -> target.put(entry.getKey(), stringify(entry.getValue(), mapper)));
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
                throw new ApiException(400, "Failed to subscribe.", ApiErrors.invalidField("options", "Invalid realtime subscription options."));
            }
        }
    }

    public record Subscription(
            String topic,
            String collection,
            String recordId,
            boolean wildcard,
            Map<String, String> query,
            Map<String, String> headers
    ) {
        static Subscription parse(String topic, ObjectMapper mapper) {
            String[] pieces = topic.split("\\?", 2);
            String target = pieces[0];
            int slash = target.indexOf('/');
            if (slash <= 0 || slash == target.length() - 1) {
                throw new ApiException(400, "Failed to subscribe.", ApiErrors.invalidField("subscriptions", "Invalid realtime subscription topic."));
            }
            String collection = target.substring(0, slash);
            String record = target.substring(slash + 1);
            Map<String, String> query = pieces.length == 2 ? parseQuery(pieces[1]) : new LinkedHashMap<>();
            Map<String, String> headers = new LinkedHashMap<>();
            String options = query.remove("options");
            if (options != null && !options.isBlank()) {
                SubscriptionOptions parsed = SubscriptionOptions.parse(options, mapper);
                query.putAll(parsed.query());
                headers.putAll(parsed.headers());
            }
            return new Subscription(
                    topic,
                    collection,
                    record,
                    "*".equals(record),
                    Map.copyOf(query),
                    Map.copyOf(headers)
            );
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
                    Map.copyOf(mergedHeaders)
            );
        }


        boolean matches(String collectionName, String collectionId, String changedRecordId) {
            boolean collectionMatches = collection.equals(collectionName) || collection.equals(collectionId);
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

    private final class Client {
        private final String id;
        private final OutputStream output;
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile Set<Subscription> subscriptions = Set.of();
        private volatile RequestPrincipal principal;
        private volatile boolean subscribed;

        private Client(String id, OutputStream output) {
            this.id = id;
            this.output = output;
        }

        String id() {
            return id;
        }

        RequestPrincipal principal() {
            return principal;
        }

        Set<Subscription> subscriptions() {
            return subscriptions;
        }

        void setSubscriptions(Set<Subscription> subscriptions, RequestPrincipal principal) {
            if (subscribed && !samePrincipal(this.principal, principal)) {
                throw new ApiException(403, "Realtime subscription authorization must match the initial subscription request.");
            }
            this.subscriptions = Set.copyOf(subscriptions);
            this.principal = principal;
            this.subscribed = true;
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
                return !closed.await(HEARTBEAT_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
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
            output.write(("data: " + mapper.writeValueAsString(data) + "\n\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        synchronized void comment(String message) throws IOException {
            output.write((": " + message + "\n\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        void close() {
            closed.countDown();
            try {
                output.close();
            } catch (IOException ignored) {
                // connection already closed
            }
        }
    }
}
