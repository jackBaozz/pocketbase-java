package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.internal.ApiErrors;
import io.github.jackbaozz.pocketbase.server.internal.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.AppleClientSecretGenerator;
import io.github.jackbaozz.pocketbase.server.internal.IdGenerator;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;
import io.github.jackbaozz.pocketbase.server.internal.RecordProcessor;
import io.github.jackbaozz.pocketbase.server.internal.S3Probe;
import io.github.jackbaozz.pocketbase.server.internal.SmtpMailer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class SettingsRepository extends BaseRepository {
    private static final String REDACTED_SECRET = "******";
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final List<String> TEST_EMAIL_TEMPLATES = List.of("verification", "password-reset", "email-change", "otp", "login-alert");

    private final Path dataDir;

    public SettingsRepository(JooqDatabase database, ObjectMapper mapper, Path dataDir) {
        super(database, mapper);
        this.dataDir = dataDir;
    }

    public Map<String, Object> getSettings(Map<String, String> query) {
        Map<String, String> safeQuery = query == null ? Map.of() : query;
        return RecordProcessor.selectFields(redactedSettings(loadRawSettings()), safeQuery.get("fields"));
    }

    public Map<String, Object> updateSettings(JsonNode body, Map<String, String> query) {
        if (body == null || !body.isObject()) {
            throw new ApiException(400, "Settings payload must be a JSON object.",
                    ApiErrors.invalidField("body", "Request body must be a JSON object."));
        }
        Map<String, Object> current = loadRawSettings();
        try {
            Map<String, Object> incoming = mapper.convertValue(body, new TypeReference<Map<String, Object>>() {});
            deepMerge(current, incoming);
            normalizeSettings(current);

            String now = Instant.now().toString();
            String valueJson = mapper.writeValueAsString(current);

            database.dsl()
                    .insertInto(qt("_params"))
                    .columns(qf("id"), qf("key"), qf("created"), qf("updated"), qf("value"))
                    .values("settings", "settings", now, now, valueJson)
                    .onConflict(qf("id"))
                    .doUpdate()
                    .set(qf("key"), "settings")
                    .set(qf("updated"), now)
                    .set(qf("value"), valueJson)
                    .execute();

            return RecordProcessor.selectFields(redactedSettings(current), query == null ? null : query.get("fields"));
        } catch (Exception e) {
            throw new ApiException(400, "Failed to update settings.", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        if (source == null) return;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = normalizeSettingKey(entry.getKey());
            Object value = entry.getValue();
            if (REDACTED_SECRET.equals(value) && target.containsKey(key)) {
                continue;
            }
            if (value instanceof Map && target.get(key) instanceof Map) {
                deepMerge((Map<String, Object>) target.get(key), (Map<String, Object>) value);
            } else {
                target.put(key, mapper.convertValue(value, Object.class));
            }
        }
    }

    public void testS3(JsonNode body) {
        if (body == null || !body.isObject() || !body.hasNonNull("filesystem") || body.get("filesystem").asText().isBlank()) {
            throw new ApiException(400, "Failed to test the S3 filesystem.", ApiErrors.requiredField("filesystem"));
        }
        String filesystem = body.get("filesystem").asText().trim();
        S3Probe.test(filesystem, s3SettingsFor(loadRawSettings(), filesystem, body));
    }

    public void testEmail(JsonNode body) {
        if (body == null || !body.isObject() || !body.hasNonNull("email") || body.get("email").asText().isBlank()) {
            throw new ApiException(400, "Failed to send the test email.", ApiErrors.requiredField("email"));
        }
        if (!body.hasNonNull("template") || body.get("template").asText().isBlank()) {
            throw new ApiException(400, "Failed to send the test email.", ApiErrors.requiredField("template"));
        }
        Map<String, Object> settings = loadRawSettings();
        String email = body.get("email").asText().trim().toLowerCase(Locale.ROOT);
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new ApiException(400, "Failed to send the test email.", ApiErrors.invalidField("email", "Invalid email address."));
        }
        String template = body.get("template").asText();
        if (!TEST_EMAIL_TEMPLATES.contains(template)) {
            throw new ApiException(400, "Failed to send the test email.", ApiErrors.invalidField("template", "Invalid email template."));
        }

        EmailContent content = testEmailContent(template, settings);
        Map<String, Object> smtp = mergedSettingsSection(settings, "smtp", body);
        if (truthySetting(smtp.get("enabled"), false) && !textSetting(smtp.get("host")).isBlank()) {
            SmtpMailer.send(smtpSettings(smtp), new SmtpMailer.Message(
                    textSetting(section(settings, "meta").get("senderName")),
                    senderAddress(settings),
                    email,
                    content.subject(),
                    content.html(),
                    content.text()
            ));
            return;
        }

        try {
            Path authRequestsFile = dataDir.resolve("auth_requests.json");
            List<Map<String, Object>> authRequests = new ArrayList<>();
            if (Files.exists(authRequestsFile)) {
                authRequests = mapper.readValue(authRequestsFile.toFile(), new TypeReference<List<Map<String, Object>>>() {});
            }
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("id", IdGenerator.id());
            request.put("type", "testEmail");
            request.put("template", template);
            request.put("email", email);
            request.put("subject", content.subject());
            request.put("text", content.text());
            request.put("html", content.html());
            request.put("created", Instant.now().toString());
            authRequests.add(request);
            Files.writeString(authRequestsFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(authRequests), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            // ignore
        }
    }

    public Map<String, Object> generateAppleClientSecret(JsonNode body) {
        return AppleClientSecretGenerator.generate(mapper, body);
    }

    private Map<String, Object> loadRawSettings() {
        Map<String, Object> settings = defaultSettings();
        try {
            var result = database.dsl()
                    .select(qfs("value"))
                    .from(qt("_params"))
                    .where(qfs("id").eq("settings"))
                    .fetchOne();
            if (result != null) {
                String val = result.get(qfs("value"));
                if (val != null && !val.isBlank()) {
                    deepMerge(settings, mapper.readValue(val, new TypeReference<Map<String, Object>>() {}));
                }
            }
        } catch (Exception ignored) {
        }
        normalizeSettings(settings);
        return settings;
    }

    private Map<String, Object> redactedSettings(Map<String, Object> raw) {
        Map<String, Object> copy = mapper.convertValue(raw, new TypeReference<Map<String, Object>>() {});
        hideSensitiveSettings(copy);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private void hideSensitiveSettings(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> target = (Map<String, Object>) map;
            for (Map.Entry<String, Object> entry : new ArrayList<>(target.entrySet())) {
                if (hiddenSettingKey(entry.getKey())) {
                    target.put(entry.getKey(), REDACTED_SECRET);
                } else {
                    hideSensitiveSettings(entry.getValue());
                }
            }
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(this::hideSensitiveSettings);
        }
    }

    @SuppressWarnings("unchecked")
    private void applySettingDefaults(Map<String, Object> target, Map<String, Object> defaults) {
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            Object existing = target.get(entry.getKey());
            if (existing == null) {
                target.put(entry.getKey(), mapper.convertValue(entry.getValue(), Object.class));
            } else if (existing instanceof Map<?, ?> existingMap && entry.getValue() instanceof Map<?, ?> defaultMap) {
                applySettingDefaults((Map<String, Object>) existingMap, (Map<String, Object>) defaultMap);
            }
        }
    }

    private void normalizeSettings(Map<String, Object> settings) {
        applySettingDefaults(settings, defaultSettings());
        Map<String, Object> meta = section(settings, "meta");
        if (meta.containsKey("appUrl") && !meta.containsKey("appURL")) {
            meta.put("appURL", meta.remove("appUrl"));
        }
        if (textSetting(meta.get("appName")).isBlank()) {
            meta.put("appName", "pocketbase-java");
        }
        if (textSetting(meta.get("appURL")).isBlank()) {
            meta.put("appURL", "http://127.0.0.1:8090");
        }
        Map<String, Object> logs = section(settings, "logs");
        if (logs.containsKey("logIp") && !logs.containsKey("logIP")) {
            logs.put("logIP", logs.remove("logIp"));
        }
        normalizeInt(logs, "maxDays", 5, 0, 3650);
        normalizeInt(logs, "minLevel", 0, 0, 16);
        normalizeBool(logs, "logIP", true);
        normalizeBool(logs, "logAuthId", true);
        Map<String, Object> batch = section(settings, "batch");
        normalizeInt(batch, "maxRequests", 50, 1, 500);
        normalizeInt(batch, "timeout", 3, 1, 3600);
        normalizeInt(batch, "maxBodySize", 33_554_432, 1, Integer.MAX_VALUE);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(Map<String, Object> settings, String name) {
        Object value = settings.get(name);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        settings.put(name, created);
        return created;
    }

    private Map<String, Object> mergedSettingsSection(Map<String, Object> settings, String name, JsonNode body) {
        Map<String, Object> merged = mapper.convertValue(section(settings, name), new TypeReference<Map<String, Object>>() {});
        JsonNode override = body == null ? null : body.get(name);
        if (override != null && override.isObject()) {
            deepMerge(merged, mapper.convertValue(override, new TypeReference<Map<String, Object>>() {}));
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> s3SettingsFor(Map<String, Object> settings, String filesystem, JsonNode body) {
        String target = filesystem == null || filesystem.isBlank() ? "storage" : filesystem.trim();
        Map<String, Object> base;
        if ("backups".equals(target)) {
            Object nested = section(settings, "backups").get("s3");
            base = nested instanceof Map<?, ?> map
                    ? mapper.convertValue(map, new TypeReference<Map<String, Object>>() {})
                    : new LinkedHashMap<>();
            JsonNode backupsOverride = body == null ? null : body.get("backups");
            if (backupsOverride != null && backupsOverride.isObject() && backupsOverride.get("s3") != null) {
                deepMerge(base, mapper.convertValue(backupsOverride.get("s3"), new TypeReference<Map<String, Object>>() {}));
            }
        } else {
            base = mapper.convertValue(section(settings, "s3"), new TypeReference<Map<String, Object>>() {});
        }
        JsonNode directOverride = body == null ? null : body.get("s3");
        if (directOverride != null && directOverride.isObject()) {
            deepMerge(base, mapper.convertValue(directOverride, new TypeReference<Map<String, Object>>() {}));
        }
        return base;
    }

    private SmtpMailer.Settings smtpSettings(Map<String, Object> smtp) {
        String host = textSetting(smtp.get("host"));
        if (host.isBlank()) {
            throw new ApiException(400, "Failed to send the test email.", ApiErrors.requiredField("host"));
        }
        return new SmtpMailer.Settings(
                host,
                Math.max(1, Math.min(65535, intSetting(smtp.get("port"), 587))),
                textSetting(smtp.get("username")),
                textSetting(smtp.get("password")),
                textSetting(smtp.get("authMethod")).isBlank() ? "PLAIN" : textSetting(smtp.get("authMethod")),
                truthySetting(smtp.get("tls"), false),
                textSetting(smtp.get("localName"))
        );
    }

    private String senderAddress(Map<String, Object> settings) {
        String senderAddress = textSetting(section(settings, "meta").get("senderAddress"));
        return senderAddress.isBlank() ? "noreply@example.com" : senderAddress;
    }

    private EmailContent testEmailContent(String template, Map<String, Object> settings) {
        String appName = textSetting(section(settings, "meta").get("appName"));
        if (appName.isBlank()) {
            appName = "PocketBase Java";
        }
        String actionUrl = textSetting(section(settings, "meta").get("appURL"));

        return switch (template) {
            case "verification" -> new EmailContent("Verify your email",
                "Verify your email address for this " + appName + " instance.\n\n" + actionUrl,
                "<p>Verify your email address for this " + appName + " instance.</p><p><a href=\"" + actionUrl + "\">Verify</a></p>");
            case "password-reset" -> new EmailContent("Reset password",
                "Reset password request for this " + appName + " instance.\n\n" + actionUrl,
                "<p>Reset password request for this " + appName + " instance.</p><p><a href=\"" + actionUrl + "\">Reset</a></p>");
            case "email-change" -> new EmailContent("Confirm new email",
                "Confirm new email request for this " + appName + " instance.\n\n" + actionUrl,
                "<p>Confirm new email request for this " + appName + " instance.</p><p><a href=\"" + actionUrl + "\">Confirm</a></p>");
            case "otp" -> new EmailContent("Your one-time password",
                "Your test one-time password is 123456.",
                "<p>Your test one-time password is <strong>123456</strong>.</p>");
            case "login-alert" -> new EmailContent("New login alert",
                "This is a test login alert from a new location.",
                "<p>This is a test login alert from a new location.</p>");
            default -> throw new ApiException(400, "Failed to send the test email.", ApiErrors.invalidField("template", "Invalid email template."));
        };
    }

    private Map<String, Object> defaultSettings() {
        return orderedMap(
                "meta", orderedMap(
                        "accentColor", "#1055c9",
                        "appName", "pocketbase-java",
                        "appURL", "http://127.0.0.1:8090",
                        "senderName", "PocketBase Java",
                        "senderAddress", "noreply@example.com",
                        "hideControls", false
                ),
                "logs", orderedMap("maxDays", 5, "minLevel", 0, "logIP", true, "logAuthId", true),
                "smtp", orderedMap("enabled", false, "host", "", "port", 587, "username", "", "password", "", "authMethod", "PLAIN", "tls", false, "localName", ""),
                "s3", orderedMap("enabled", false, "bucket", "", "region", "", "endpoint", "", "accessKey", "", "secret", "", "forcePathStyle", false),
                "backups", orderedMap(
                        "cron", "",
                        "cronMaxKeep", 3,
                        "s3", orderedMap("enabled", false, "bucket", "", "region", "", "endpoint", "", "accessKey", "", "secret", "", "forcePathStyle", false)
                ),
                "rateLimits", orderedMap(
                        "enabled", false,
                        "rules", List.of(
                                orderedMap("label", "*:auth", "audience", "", "duration", 3, "maxRequests", 2),
                                orderedMap("label", "*:create", "audience", "", "duration", 5, "maxRequests", 20),
                                orderedMap("label", "/api/batch", "audience", "", "duration", 1, "maxRequests", 3),
                                orderedMap("label", "/api/", "audience", "", "duration", 10, "maxRequests", 300)
                        ),
                        "excludedIPs", List.of()
                ),
                "trustedProxy", orderedMap("headers", List.of(), "useLeftmostIP", false),
                "batch", orderedMap("enabled", true, "maxRequests", 50, "timeout", 3, "maxBodySize", 33_554_432),
                "superuserIPs", List.of()
        );
    }

    private Map<String, Object> orderedMap(Object... entries) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            out.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return out;
    }

    private String normalizeSettingKey(String key) {
        if ("appUrl".equals(key)) return "appURL";
        if ("logIp".equals(key)) return "logIP";
        return key;
    }

    private boolean hiddenSettingKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return "password".equals(normalized) || "secret".equals(normalized) || "privatekey".equals(normalized);
    }

    private void normalizeInt(Map<String, Object> section, String key, int fallback, int min, int max) {
        section.put(key, Math.max(min, Math.min(max, intSetting(section.get(key), fallback))));
    }

    private void normalizeBool(Map<String, Object> section, String key, boolean fallback) {
        section.put(key, truthySetting(section.get(key), fallback));
    }

    private int intSetting(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private boolean truthySetting(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return fallback;
        return List.of("1", "true", "yes", "on").contains(normalized);
    }

    private String textSetting(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record EmailContent(String subject, String text, String html) {}
}
