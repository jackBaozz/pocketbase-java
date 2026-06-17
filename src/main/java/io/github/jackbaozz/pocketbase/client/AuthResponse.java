package io.github.jackbaozz.pocketbase.client;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Authentication response returned by collection auth endpoints.
 *
 * @param token JWT token
 * @param record authenticated record payload
 * @param meta optional auth provider metadata
 */
public record AuthResponse(String token, JsonNode record, JsonNode meta) {
}
