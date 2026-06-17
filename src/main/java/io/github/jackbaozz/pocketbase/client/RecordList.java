package io.github.jackbaozz.pocketbase.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Paginated record list returned by PocketBase collection record APIs.
 *
 * @param page current page
 * @param perPage page size
 * @param totalItems total item count when requested
 * @param totalPages total page count when requested
 * @param items record payloads
 */
public record RecordList(int page, int perPage, int totalItems, int totalPages, List<JsonNode> items) {
}
