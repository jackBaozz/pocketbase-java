package io.github.jackbaozz.pocketbase.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Objects;

/**
 * Client facade for PocketBase SQL APIs.
 */
public final class SqlService {
    private final PocketBaseClient client;

    SqlService(PocketBaseClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public JsonNode run(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        return client.send(
                "POST",
                client.apiPath("sql"),
                Map.of(),
                Map.of("query", query),
                JsonNode.class
        );
    }
}
