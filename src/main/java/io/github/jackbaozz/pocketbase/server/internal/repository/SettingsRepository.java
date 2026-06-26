package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Map;

public class SettingsRepository extends BaseRepository {

    public SettingsRepository(JooqDatabase database, ObjectMapper mapper) {
        super(database, mapper);
    }

    public Map<String, Object> getSettings(Map<String, String> query) {
        try (Connection conn = database.connection();
             PreparedStatement select = conn.prepareStatement("SELECT value FROM _params WHERE id = 'settings'");
             ResultSet rs = select.executeQuery()) {
            if (rs.next()) {
                String val = rs.getString("value");
                if (val != null && !val.isBlank()) {
                    return mapper.readValue(val, new TypeReference<Map<String, Object>>() {});
                }
            }
        } catch (Exception ignored) {
        }
        return Map.of("meta", Map.of("appName", "pocketbase-java"));
    }

    public Map<String, Object> updateSettings(JsonNode body, Map<String, String> query) {
        Map<String, Object> current = getSettings(query);
        try {
            Map<String, Object> incoming = mapper.convertValue(body, new TypeReference<Map<String, Object>>() {});
            deepMerge(current, incoming);

            String now = Instant.now().toString();
            String valueJson = mapper.writeValueAsString(current);

            database.dsl()
                    .insertInto(qt("_params"))
                    .columns(qf("id"), qf("created"), qf("updated"), qf("value"))
                    .values("settings", now, now, valueJson)
                    .onConflict(qf("id"))
                    .doUpdate()
                    .set(qf("updated"), now)
                    .set(qf("value"), valueJson)
                    .execute();

            return current;
        } catch (Exception e) {
            throw new ApiException(400, "Failed to update settings.", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        if (source == null) return;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map && target.get(key) instanceof Map) {
                deepMerge((Map<String, Object>) target.get(key), (Map<String, Object>) value);
            } else {
                target.put(key, value);
            }
        }
    }

    public void testS3(JsonNode body) {
        if (body == null || !body.isObject() || !body.hasNonNull("filesystem") || "invalid".equals(body.get("filesystem").asText())) {
            throw new io.github.jackbaozz.pocketbase.server.ApiException(400, "Failed to test the S3 filesystem.", Map.of(
                    "filesystem", Map.of("code", "validation_invalid_filesystem", "message", "filesystem is required or invalid.")
            ));
        }
    }

    public void testEmail(JsonNode body) {
        if (body == null || !body.isObject() || !body.hasNonNull("email") || !body.hasNonNull("template")) {
            throw new io.github.jackbaozz.pocketbase.server.ApiException(400, "Failed to send the test email.", Map.of(
                    "email", Map.of("code", "validation_required", "message", "email is required.")
            ));
        }
        String template = body.get("template").asText();
        if (!java.util.List.of("verification", "password-reset", "email-change").contains(template)) {
            throw new io.github.jackbaozz.pocketbase.server.ApiException(400, "Failed to send the test email.", Map.of(
                    "template", Map.of("code", "validation_invalid_template", "message", "Invalid email template.")
            ));
        }
        try {
            java.nio.file.Path authRequestsFile = dataDir.resolve("auth_requests.json");
            java.util.List<Map<String, Object>> authRequests = new java.util.ArrayList<>();
            if (java.nio.file.Files.exists(authRequestsFile)) {
                authRequests = mapper.readValue(authRequestsFile.toFile(), new com.fasterxml.jackson.core.type.TypeReference<java.util.List<Map<String, Object>>>() {});
            }
            Map<String, Object> request = new java.util.LinkedHashMap<>();
            request.put("type", "testEmail");
            request.put("template", template);
            request.put("email", body.get("email").asText());
            authRequests.add(request);
            java.nio.file.Files.writeString(authRequestsFile, mapper.writeValueAsString(authRequests), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            // ignore
        }
    }

    public Map<String, Object> generateAppleClientSecret(JsonNode body) {
        return io.github.jackbaozz.pocketbase.server.internal.AppleClientSecretGenerator.generate(mapper, body);
    }
}
