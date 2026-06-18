package io.github.jackbaozz.pocketbase.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Service for PocketBase SSE Realtime Subscriptions.
 */
public final class RealtimeService {
    private final PocketBaseClient client;

    RealtimeService(PocketBaseClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    // TODO: implement SSE connection and Realtime subscribe logic
}
