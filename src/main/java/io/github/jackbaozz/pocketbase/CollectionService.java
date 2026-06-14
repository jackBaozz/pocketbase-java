package io.github.jackbaozz.pocketbase;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Objects;

/**
 * Client facade for a single PocketBase collection.
 */
public final class CollectionService {
    private final PocketBaseClient client;
    private final String collection;

    CollectionService(PocketBaseClient client, String collection) {
        this.client = Objects.requireNonNull(client, "client");
        this.collection = requireText(collection, "collection");
    }

    public RecordList list() {
        return list(ListOptions.defaults());
    }

    public RecordList list(ListOptions options) {
        ListOptions actualOptions = options == null ? ListOptions.defaults() : options;
        return client.send(
                "GET",
                client.apiPath("collections", collection, "records"),
                actualOptions.toQuery(),
                null,
                RecordList.class
        );
    }

    public JsonNode getOne(String id) {
        return getOne(id, RecordQuery.defaults());
    }

    public JsonNode getOne(String id, RecordQuery query) {
        RecordQuery actualQuery = query == null ? RecordQuery.defaults() : query;
        return client.send(
                "GET",
                client.apiPath("collections", collection, "records", requireText(id, "id")),
                actualQuery.toQuery(),
                null,
                JsonNode.class
        );
    }

    public JsonNode create(Object body) {
        return create(body, RecordQuery.defaults());
    }

    public JsonNode create(Object body, RecordQuery query) {
        RecordQuery actualQuery = query == null ? RecordQuery.defaults() : query;
        return client.send(
                "POST",
                client.apiPath("collections", collection, "records"),
                actualQuery.toQuery(),
                requireBody(body),
                JsonNode.class
        );
    }

    public JsonNode update(String id, Object body) {
        return update(id, body, RecordQuery.defaults());
    }

    public JsonNode update(String id, Object body, RecordQuery query) {
        RecordQuery actualQuery = query == null ? RecordQuery.defaults() : query;
        return client.send(
                "PATCH",
                client.apiPath("collections", collection, "records", requireText(id, "id")),
                actualQuery.toQuery(),
                requireBody(body),
                JsonNode.class
        );
    }

    public void delete(String id) {
        client.send(
                "DELETE",
                client.apiPath("collections", collection, "records", requireText(id, "id")),
                Map.of(),
                null,
                Void.class
        );
    }

    public AuthResponse authWithPassword(String identity, String password) {
        return authWithPassword(identity, password, RecordQuery.defaults());
    }

    public AuthResponse authWithPassword(String identity, String password, RecordQuery query) {
        RecordQuery actualQuery = query == null ? RecordQuery.defaults() : query;
        AuthResponse response = client.send(
                "POST",
                client.apiPath("collections", collection, "auth-with-password"),
                actualQuery.toQuery(),
                Map.of(
                        "identity", requireText(identity, "identity"),
                        "password", requireText(password, "password")
                ),
                AuthResponse.class
        );
        client.authStore().save(response);
        return response;
    }

    public JsonNode requestOtp(String email) {
        return client.send(
                "POST",
                client.apiPath("collections", collection, "request-otp"),
                Map.of(),
                Map.of("email", requireText(email, "email")),
                JsonNode.class
        );
    }

    public AuthResponse authWithOtp(String otpId, String password) {
        return authWithOtp(otpId, password, RecordQuery.defaults());
    }

    public AuthResponse authWithOtp(String otpId, String password, RecordQuery query) {
        RecordQuery actualQuery = query == null ? RecordQuery.defaults() : query;
        AuthResponse response = client.send(
                "POST",
                client.apiPath("collections", collection, "auth-with-otp"),
                actualQuery.toQuery(),
                Map.of(
                        "otpId", requireText(otpId, "otpId"),
                        "password", requireText(password, "password")
                ),
                AuthResponse.class
        );
        client.authStore().save(response);
        return response;
    }

    public AuthResponse authRefresh() {
        return authRefresh(RecordQuery.defaults());
    }

    public AuthResponse authRefresh(RecordQuery query) {
        RecordQuery actualQuery = query == null ? RecordQuery.defaults() : query;
        AuthResponse response = client.send(
                "POST",
                client.apiPath("collections", collection, "auth-refresh"),
                actualQuery.toQuery(),
                null,
                AuthResponse.class
        );
        client.authStore().save(response);
        return response;
    }

    public JsonNode listAuthMethods() {
        return client.send(
                "GET",
                client.apiPath("collections", collection, "auth-methods"),
                Map.of(),
                null,
                JsonNode.class
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
