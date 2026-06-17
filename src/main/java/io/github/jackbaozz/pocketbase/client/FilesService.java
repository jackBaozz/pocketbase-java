package io.github.jackbaozz.pocketbase.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Objects;

/**
 * Client facade for PocketBase file helpers.
 */
public final class FilesService {
    private final PocketBaseClient client;

    FilesService(PocketBaseClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public String getToken() {
        JsonNode response = client.send(
                "POST",
                client.apiPath("files", "token"),
                Map.of(),
                null,
                JsonNode.class
        );
        JsonNode token = response == null ? null : response.get("token");
        return token == null || token.isNull() ? "" : token.asText();
    }
}
