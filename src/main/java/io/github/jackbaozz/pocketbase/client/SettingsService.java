package io.github.jackbaozz.pocketbase.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Client facade for PocketBase settings APIs.
 */
public final class SettingsService {
    private final PocketBaseClient client;

    SettingsService(PocketBaseClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public JsonNode get() {
        return get(null);
    }

    public JsonNode get(String fields) {
        return client.send(
                "GET",
                client.apiPath("settings"),
                fields == null || fields.isBlank() ? Map.of() : Map.of("fields", fields),
                null,
                JsonNode.class
        );
    }

    public JsonNode update(Object body) {
        return client.send(
                "PATCH",
                client.apiPath("settings"),
                Map.of(),
                requireBody(body),
                JsonNode.class
        );
    }

    public void testS3(String filesystem) {
        testS3(Map.of("filesystem", requireText(filesystem, "filesystem")));
    }

    public void testS3(Object body) {
        client.send(
                "POST",
                client.apiPath("settings", "test", "s3"),
                Map.of(),
                requireBody(body),
                Void.class
        );
    }

    public void testEmail(String email, String template) {
        testEmail(email, template, "");
    }

    public void testEmail(String email, String template, String collection) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", requireText(email, "email"));
        body.put("template", requireText(template, "template"));
        if (collection != null && !collection.isBlank()) {
            body.put("collection", collection);
        }
        testEmail(body);
    }

    public void testEmail(Object body) {
        client.send(
                "POST",
                client.apiPath("settings", "test", "email"),
                Map.of(),
                requireBody(body),
                Void.class
        );
    }

    public String generateAppleClientSecret(Object body) {
        JsonNode response = client.send(
                "POST",
                client.apiPath("settings", "apple", "generate-client-secret"),
                Map.of(),
                requireBody(body),
                JsonNode.class
        );
        JsonNode secret = response == null ? null : response.get("secret");
        return secret == null || secret.isNull() ? "" : secret.asText();
    }

    private static Object requireBody(Object body) {
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        return body;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
