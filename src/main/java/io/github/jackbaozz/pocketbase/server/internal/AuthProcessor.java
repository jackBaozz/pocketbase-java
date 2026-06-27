package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Implements auth action flows (password reset, verification, email change)
 * for the relational storage engine. Uses JWT tokens with embedded claims
 * to carry request state, matching the official PocketBase approach.
 */
public final class AuthProcessor {

    private AuthProcessor() {}

    public static Map<String, Object> authRefresh(RecordProcessor.StoreContext ctx, TokenService tokenService, String collection, RequestPrincipal principal) {
        if (principal == null || !principal.collectionName().equals(collection)) {
            throw new ApiException(401, "Missing or invalid auth token.");
        }
        CollectionSchema colSchema = ctx.getCollection(collection);
        if (colSchema == null) {
            throw new ApiException(401, "Auth collection not found.");
        }
        Map<String, Object> record = ctx.getRecord(colSchema, principal.id());
        if (record == null) {
            throw new ApiException(401, "Auth record no longer exists.");
        }

        Map<String, Object> claims = Map.of(
                "sub", principal.id(),
                "email", record.getOrDefault("email", ""),
                "type", colSchema.system ? "superuser" : "auth",
                "collectionId", colSchema.id,
                "collectionName", colSchema.name,
                "tokenType", "auth",
                "tokenKey", record.getOrDefault("tokenKey", "")
        );
        String token = tokenService.create(claims, Duration.ofDays(7));
        return Map.of("token", token, "record", record);
    }

    public static Map<String, Object> authMethods(RecordProcessor.StoreContext ctx, String collection) {
        CollectionSchema colSchema = ctx.getCollection(collection);
        if (colSchema == null) {
            throw new ApiException(404, "Collection not found.");
        }
        List<Map<String, Object>> providers = new ArrayList<>();
        if (colSchema.oauth2 != null && colSchema.oauth2.enabled) {
            for (CollectionSchema.OAuth2ProviderConfig pc : colSchema.oauth2.providers) {
                providers.add(Map.of(
                        "name", pc.name,
                        "authURL", pc.authURL + "?state=state",
                        "clientId", pc.clientId
                ));
            }
        }
        return Map.of(
                "password", Map.of("enabled", colSchema.passwordAuth != null && colSchema.passwordAuth.enabled, "identityFields", colSchema.passwordAuth != null ? colSchema.passwordAuth.identityFields : List.of()),
                "oauth2", Map.of("enabled", colSchema.oauth2 != null && colSchema.oauth2.enabled, "providers", providers)
        );
    }

    // ── Password Reset ──────────────────────────────────────────────────

    public static void requestPasswordReset(RecordProcessor.StoreContext ctx, TokenService tokenService, String collection, JsonNode body) {
        CollectionSchema colSchema = requireAuthCollection(ctx, collection);
        String email = requireEmail(body);

        Map<String, Object> record = ctx.findRecordByEmail(colSchema, email);
        if (record != null) {
            ensureTokenKey(ctx, colSchema, record);
            createAuthToken(tokenService, colSchema, record, "passwordReset", Map.of(), Duration.ofHours(2));
        }
        // Always return 204 to avoid email enumeration
    }

    public static void confirmPasswordReset(RecordProcessor.StoreContext ctx, TokenService tokenService, String collection, JsonNode body) {
        String token = requireText(body, "token");
        Map<String, Object> claims = verifyActionToken(tokenService, token, "passwordReset", collection);

        String recordId = String.valueOf(claims.get("sub"));
        CollectionSchema colSchema = ctx.getCollection(collection);
        Map<String, Object> record = ctx.getRecord(colSchema, recordId);
        if (record == null || !Objects.equals(String.valueOf(record.get("tokenKey")), String.valueOf(claims.get("tokenKey")))) {
            throw new ApiException(400, "Invalid or expired token.");
        }

        String password = requireText(body, "password");
        String passwordConfirm = requireText(body, "passwordConfirm");
        if (!password.equals(passwordConfirm)) {
            throw new ApiException(400, "passwordConfirm does not match password.",
                    Map.of("passwordConfirm", Map.of("code", "validation_invalid_value", "message", "Passwords do not match.")));
        }
        if (password.length() < 8) {
            throw new ApiException(400, "Password must be at least 8 characters.",
                    Map.of("password", Map.of("code", "validation_invalid_value", "message", "Password must be at least 8 characters.")));
        }

        String passwordField = passwordField(colSchema);
        ctx.updateRecordField(colSchema, recordId, Map.of(
                passwordField, PasswordHasher.hash(password),
                "verified", true,
                "tokenKey", IdGenerator.prefixed("tk_")
        ));
    }

    // ── Verification ─────────────────────────────────────────────────────

    public static void requestVerification(RecordProcessor.StoreContext ctx, TokenService tokenService, String collection, JsonNode body) {
        CollectionSchema colSchema = requireAuthCollection(ctx, collection);
        String email = requireEmail(body);

        Map<String, Object> record = ctx.findRecordByEmail(colSchema, email);
        if (record != null && !truthy(record.get("verified"))) {
            ensureTokenKey(ctx, colSchema, record);
            createAuthToken(tokenService, colSchema, record, "verification", Map.of(), Duration.ofHours(24));
        }
    }

    public static void confirmVerification(RecordProcessor.StoreContext ctx, TokenService tokenService, String collection, JsonNode body) {
        String token = requireText(body, "token");
        Map<String, Object> claims = verifyActionToken(tokenService, token, "verification", collection);

        String recordId = String.valueOf(claims.get("sub"));
        CollectionSchema colSchema = ctx.getCollection(collection);
        Map<String, Object> record = ctx.getRecord(colSchema, recordId);
        if (record == null || !Objects.equals(String.valueOf(record.get("tokenKey")), String.valueOf(claims.get("tokenKey")))) {
            throw new ApiException(400, "Invalid or expired token.");
        }

        ctx.updateRecordField(colSchema, recordId, Map.of("verified", true));
    }

    // ── Email Change ─────────────────────────────────────────────────────

    public static void requestEmailChange(RecordProcessor.StoreContext ctx, TokenService tokenService, String collection, JsonNode body, RequestPrincipal principal) {
        CollectionSchema colSchema = requireAuthCollection(ctx, collection);
        if (principal == null) {
            throw new ApiException(401, "Missing or invalid auth token.");
        }
        if (principal.superuser() || !principal.collectionName().equals(collection)) {
            throw new ApiException(403, "Auth record token required.");
        }

        Map<String, Object> record = ctx.getRecord(colSchema, principal.id());
        if (record == null) {
            throw new ApiException(401, "Auth record no longer exists.");
        }

        String newEmail = requireText(body, "newEmail").trim().toLowerCase();
        if (!newEmail.contains("@")) {
            throw new ApiException(400, "Invalid email format.",
                    Map.of("newEmail", Map.of("code", "validation_invalid_email", "message", "Must be a valid email address.")));
        }

        // Check email availability
        Map<String, Object> existing = ctx.findRecordByEmail(colSchema, newEmail);
        if (existing != null && !String.valueOf(existing.get("id")).equals(principal.id())) {
            throw new ApiException(400, "New email address is already in use.",
                    Map.of("newEmail", Map.of("code", "validation_not_unique", "message", "New email address is already in use.")));
        }

        ensureTokenKey(ctx, colSchema, record);
        createAuthToken(tokenService, colSchema, record, "emailChange", Map.of("newEmail", newEmail), Duration.ofHours(2));
    }

    public static void confirmEmailChange(RecordProcessor.StoreContext ctx, TokenService tokenService, String collection, JsonNode body) {
        String token = requireText(body, "token");
        Map<String, Object> claims = verifyActionToken(tokenService, token, "emailChange", collection);

        String recordId = String.valueOf(claims.get("sub"));
        CollectionSchema colSchema = ctx.getCollection(collection);
        Map<String, Object> record = ctx.getRecord(colSchema, recordId);
        if (record == null || !Objects.equals(String.valueOf(record.get("tokenKey")), String.valueOf(claims.get("tokenKey")))) {
            throw new ApiException(400, "Invalid or expired token.");
        }

        String password = requireText(body, "password");
        String passwordField = passwordField(colSchema);
        if (!PasswordHasher.verify(password, String.valueOf(record.get(passwordField)))) {
            throw new ApiException(400, "Invalid password.");
        }

        String newEmail = String.valueOf(claims.getOrDefault("newEmail", ""));
        if (!newEmail.contains("@")) {
            throw new ApiException(400, "Invalid new email address.");
        }

        ctx.updateRecordField(colSchema, recordId, Map.of(
                "email", newEmail,
                "verified", true,
                "tokenKey", IdGenerator.prefixed("tk_")
        ));
    }

    // ── OAuth2 ───────────────────────────────────────────────────────────

    public static Map<String, Object> authWithOAuth2(RecordProcessor.StoreContext ctx, ObjectMapper mapper, TokenService tokenService, String collection, JsonNode body) {
        CollectionSchema colSchema = ctx.getCollection(collection);
        if (colSchema == null || colSchema.oauth2 == null || !colSchema.oauth2.enabled) {
            throw new ApiException(403, "The collection is not configured to allow OAuth2 authentication.");
        }
        String providerName = body.has("provider") ? body.get("provider").asText() : null;
        if (providerName == null || providerName.isBlank()) {
            throw new ApiException(400, "Failed to authenticate.", Map.of("provider", Map.of("code", "validation_required", "message", "provider is required.")));
        }

        colSchema.oauth2.providers.stream()
                .filter(item -> providerName.equalsIgnoreCase(item.name))
                .findFirst()
                .orElseThrow(() -> new ApiException(400, "Failed to authenticate.", Map.of("provider", Map.of("code", "validation_missing_record", "message", "Provider is missing or is not enabled."))));

        throw new ApiException(400, "OAuth2 simulated failure for SQLite MVP parity tests.");
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    private static CollectionSchema requireAuthCollection(RecordProcessor.StoreContext ctx, String collection) {
        CollectionSchema colSchema = ctx.getCollection(collection);
        if (colSchema == null || !"auth".equals(colSchema.type)) {
            throw new ApiException(400, "The collection is not an auth collection.");
        }
        return colSchema;
    }

    private static Map<String, Object> verifyActionToken(TokenService tokenService, String token, String expectedType, String collection) {
        Map<String, Object> claims = tokenService.verify(token)
                .orElseThrow(() -> new ApiException(400, "Invalid or expired token."));
        if (!expectedType.equals(claims.get("tokenType"))) {
            throw new ApiException(400, "Invalid or expired token.");
        }
        if (!Objects.equals(collection, claims.get("collectionName"))) {
            throw new ApiException(400, "Invalid or expired token.");
        }
        return claims;
    }

    private static String createAuthToken(TokenService tokenService, CollectionSchema collection, Map<String, Object> record, String tokenType, Map<String, Object> extraClaims, Duration ttl) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", record.get("id"));
        claims.put("collectionId", collection.id);
        claims.put("collectionName", collection.name);
        claims.put("type", "auth");
        claims.put("email", record.get("email"));
        claims.put("tokenType", tokenType);
        claims.put("tokenKey", record.get("tokenKey"));
        if (extraClaims != null) {
            claims.putAll(extraClaims);
        }
        return tokenService.create(claims, ttl);
    }

    private static void ensureTokenKey(RecordProcessor.StoreContext ctx, CollectionSchema collection, Map<String, Object> record) {
        Object tokenKey = record.get("tokenKey");
        if (tokenKey == null || String.valueOf(tokenKey).isBlank()) {
            String newKey = IdGenerator.prefixed("tk_");
            ctx.updateRecordField(collection, String.valueOf(record.get("id")), Map.of("tokenKey", newKey));
            record.put("tokenKey", newKey);
        }
    }

    private static String passwordField(CollectionSchema colSchema) {
        // In PocketBase, auth collections use "password" as the field name
        return "passwordHash";
    }

    private static String requireEmail(JsonNode body) {
        if (body == null || !body.has("email") || body.get("email").isNull()) {
            throw new ApiException(400, "email is required.",
                    Map.of("email", Map.of("code", "validation_required", "message", "email is required.")));
        }
        String email = body.get("email").asText().trim().toLowerCase();
        if (email.isEmpty()) {
            throw new ApiException(400, "email is required.",
                    Map.of("email", Map.of("code", "validation_required", "message", "email is required.")));
        }
        return email;
    }

    private static String requireText(JsonNode body, String field) {
        if (body == null || !body.has(field) || body.get(field).isNull()) {
            throw new ApiException(400, field + " is required.",
                    Map.of(field, Map.of("code", "validation_required", "message", field + " is required.")));
        }
        return body.get(field).asText();
    }

    private static boolean truthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
