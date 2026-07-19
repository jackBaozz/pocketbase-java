package io.github.jackbaozz.pocketbase.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Response returned by the PocketBase batch endpoint.
 */
public record BatchResponse(List<Item> responses) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public BatchResponse(List<Item> responses) {
        this.responses = responses == null ? List.of() : List.copyOf(responses);
    }

    public record Item(int status, JsonNode body) {
    }
}
