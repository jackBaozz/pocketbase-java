package io.github.jackbaozz.pocketbase.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Service for interacting with the PocketBase Realtime API.
 */
public final class RealtimeService {
    private final PocketBaseClient client;
    private final Map<String, List<Consumer<JsonNode>>> subscriptions = new ConcurrentHashMap<>();
    private Thread listenerThread;
    private String clientId;

    RealtimeService(PocketBaseClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * Subscribes to a realtime topic.
     * To subscribe to all records in a collection, use the collection name (e.g. "users").
     * To subscribe to a specific record, use "collectionName/recordId".
     */
    public synchronized void subscribe(String topic, Consumer<JsonNode> callback) {
        subscriptions.computeIfAbsent(topic, k -> new ArrayList<>()).add(callback);
        if (clientId != null && !clientId.isBlank()) {
            submitSubscriptions();
        } else if (listenerThread == null) {
            connect();
        }
    }

    public synchronized void unsubscribe(String topic) {
        subscriptions.remove(topic);
        if (subscriptions.isEmpty()) {
            disconnect();
        } else if (clientId != null) {
            submitSubscriptions();
        }
    }

    public synchronized void disconnect() {
        if (listenerThread != null) {
            listenerThread.interrupt();
            listenerThread = null;
        }
        clientId = null;
        subscriptions.clear();
    }

    private void connect() {
        listenerThread = new Thread(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(client.baseUri().toString() + "/api/realtime"))
                        .GET()
                        .header("Accept", "text/event-stream")
                        .build();

                // Uses the global HttpClient since PocketBaseClient does not expose it
                java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    throw new PocketBaseException("GET", URI.create("/api/realtime"), new RuntimeException("Failed to connect to realtime API, status: " + response.statusCode()));
                }

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    String currentEvent = "message";
                    while (!Thread.currentThread().isInterrupted() && (line = reader.readLine()) != null) {
                        if (line.isBlank()) {
                            continue;
                        }
                        if (line.startsWith("id:")) {
                            clientId = line.substring(3).trim();
                            submitSubscriptions();
                        } else if (line.startsWith("event:")) {
                            currentEvent = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            String dataStr = line.substring(5).trim();
                            if (!dataStr.isBlank() && !dataStr.equals("{}")) {
                                try {
                                    JsonNode dataNode = client.objectMapper().readTree(dataStr);
                                    dispatch(currentEvent, dataNode);
                                } catch (Exception e) {
                                    // Ignore parse errors on raw messages
                                }
                            }
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // Reconnect or handle failure logic can be added here
            } finally {
                clientId = null;
            }
        });
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void submitSubscriptions() {
        if (clientId == null || clientId.isBlank() || subscriptions.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> body = Map.of(
                    "clientId", clientId,
                    "subscriptions", new ArrayList<>(subscriptions.keySet())
            );
            client.send("POST", "realtime", null, body, JsonNode.class);
        } catch (Exception e) {
            // Error submitting subscriptions
        }
    }

    private void dispatch(String event, JsonNode data) {
        if ("PB_CONNECT".equals(event)) {
            return; // handled via 'id:'
        }
        // In PocketBase, a broadcasted event is usually just the topic or "message".
        // The actual topic comes back in the payload's "action" or "record".
        // But local matching: find matching topics from the event name or data
        // For standard PocketBase data usually has { action: "...", record: {...} }
        subscriptions.forEach((topic, callbacks) -> {
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
}
