package io.github.jackbaozz.pocketbase;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Objects;

/**
 * Client facade for PocketBase cron APIs.
 */
public final class CronsService {
    private final PocketBaseClient client;

    CronsService(PocketBaseClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public JsonNode list() {
        return client.send(
                "GET",
                client.apiPath("crons"),
                Map.of(),
                null,
                JsonNode.class
        );
    }

    public void run(String id) {
        client.send(
                "POST",
                client.apiPath("crons", requireText(id, "id")),
                Map.of(),
                null,
                Void.class
        );
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
