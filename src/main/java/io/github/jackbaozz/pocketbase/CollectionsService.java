package io.github.jackbaozz.pocketbase;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Objects;

/**
 * Client facade for PocketBase collection management APIs.
 */
public final class CollectionsService {
    private final PocketBaseClient client;

    CollectionsService(PocketBaseClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public JsonNode list() {
        return client.send("GET", client.apiPath("collections"), Map.of(), null, JsonNode.class);
    }

    public JsonNode list(ListOptions options) {
        return client.send(
                "GET",
                client.apiPath("collections"),
                options == null ? Map.of() : options.toQuery(),
                null,
                JsonNode.class
        );
    }

    public JsonNode getOne(String idOrName) {
        return client.send(
                "GET",
                client.apiPath("collections", requireText(idOrName, "idOrName")),
                Map.of(),
                null,
                JsonNode.class
        );
    }

    public JsonNode getOne(String idOrName, ListOptions options) {
        return client.send(
                "GET",
                client.apiPath("collections", requireText(idOrName, "idOrName")),
                options == null ? Map.of() : options.toQuery(),
                null,
                JsonNode.class
        );
    }

    public JsonNode create(Object body) {
        return client.send("POST", client.apiPath("collections"), Map.of(), requireBody(body), JsonNode.class);
    }

    public JsonNode update(String idOrName, Object body) {
        return client.send(
                "PATCH",
                client.apiPath("collections", requireText(idOrName, "idOrName")),
                Map.of(),
                requireBody(body),
                JsonNode.class
        );
    }

    public void delete(String idOrName) {
        client.send(
                "DELETE",
                client.apiPath("collections", requireText(idOrName, "idOrName")),
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

    private static Object requireBody(Object body) {
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        return body;
    }
}
