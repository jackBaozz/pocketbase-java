package io.github.jackbaozz.pocketbase.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Service for sending batch requests to PocketBase.
 */
public final class BatchService {
    private final PocketBaseClient client;
    private final List<Object> requests = new ArrayList<>();

    BatchService(PocketBaseClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public BatchService addRequest(Object requestPayload) {
        this.requests.add(requestPayload);
        return this;
    }

    public BatchResponse send() {
        if (requests.isEmpty()) {
            return new BatchResponse(List.of());
        }
        Object body = java.util.Map.of("requests", requests);
        return client.send("POST", client.apiPath("batch"), null, body, BatchResponse.class);
    }
}
