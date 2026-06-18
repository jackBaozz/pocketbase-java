package io.github.jackbaozz.pocketbase.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * Service for PocketBase Batch Requests.
 */
public final class BatchService {
    private final PocketBaseClient client;

    BatchService(PocketBaseClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    // TODO: implement batch logic
}
