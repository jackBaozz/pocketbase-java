package io.github.jackbaozz.pocketbase;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Error payload returned by PocketBase HTTP APIs.
 *
 * @param status PocketBase status code
 * @param message human-readable message
 * @param data field-level error details
 */
public record PocketBaseError(int status, String message, JsonNode data) {
}
