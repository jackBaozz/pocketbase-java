package io.github.jackbaozz.pocketbase;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Objects;

/**
 * Client facade for PocketBase request logs APIs.
 */
public final class LogsService {
    private final PocketBaseClient client;

    LogsService(PocketBaseClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public RecordList list() {
        return list(ListOptions.defaults());
    }

    public RecordList list(ListOptions options) {
        ListOptions actualOptions = options == null ? ListOptions.defaults() : options;
        return client.send(
                "GET",
                client.apiPath("logs"),
                actualOptions.toQuery(),
                null,
                RecordList.class
        );
    }

    public JsonNode getOne(String id) {
        return client.send(
                "GET",
                client.apiPath("logs", requireText(id, "id")),
                Map.of(),
                null,
                JsonNode.class
        );
    }

    public JsonNode stats() {
        return stats(Map.of());
    }

    public JsonNode stats(Map<String, ?> query) {
        return client.send(
                "GET",
                client.apiPath("logs", "stats"),
                query == null ? Map.of() : query,
                null,
                JsonNode.class
        );
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
