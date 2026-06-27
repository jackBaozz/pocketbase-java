package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.internal.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.AuthProcessor;
import io.github.jackbaozz.pocketbase.server.internal.IdGenerator;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;
import io.github.jackbaozz.pocketbase.server.internal.PasswordHasher;
import io.github.jackbaozz.pocketbase.server.internal.RecordProcessor;
import io.github.jackbaozz.pocketbase.server.internal.RequestPrincipal;
import io.github.jackbaozz.pocketbase.server.internal.TokenService;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class AuthRepository extends BaseRepository {

    private static final String SUPERUSERS = "_superusers";

    private final TokenService tokenService;
    private final RecordProcessor.StoreContext storeContext;
    private final RecordRepository recordRepository;

    public AuthRepository(JooqDatabase database, ObjectMapper mapper, TokenService tokenService, RecordProcessor.StoreContext storeContext, RecordRepository recordRepository) {
        super(database, mapper);
        this.tokenService = tokenService;
        this.storeContext = storeContext;
        this.recordRepository = recordRepository;
    }

    public Map<String, Object> bootstrapSuperuser(JsonNode body) {
        String email = body.get("email").asText();
        String password = body.get("password").asText();

        if (database.dsl().fetchCount(qt("_superusers")) > 0) {
            throw new ApiException(403, "Superuser already exists.");
        }

        String id = "su_" + IdGenerator.id();
        String passHash = PasswordHasher.hash(password);
        String tokenKey = IdGenerator.id();
        String now = Instant.now().toString();

        database.dsl()
                .insertInto(qt("_superusers"))
                .columns(qfs("id"), qfs("email"), qfs("passwordHash"), qfs("tokenKey"), qfs("created"), qfs("updated"))
                .values(id, email, passHash, tokenKey, now, now)
                .execute();

        Map<String, Object> record = Map.of(
                "id", id,
                "email", email,
                "created", now,
                "updated", now
        );
        return Map.of("record", record);
    }

    public Map<String, Object> authWithPassword(String collection, JsonNode body, Map<String, String> query) {
        String identity = body.get("identity").asText();
        String password = body.get("password").asText();

        if (SUPERUSERS.equals(collection)) {
            var record = database.dsl()
                    .select(qfs("id"), qfs("email"), qfs("passwordHash"), qfs("tokenKey"), qfs("created"), qfs("updated"))
                    .from(qt("_superusers"))
                    .where(qfs("email").eq(identity))
                    .fetchOne();

            if (record != null) {
                String hash = record.getValue(qfs("passwordHash"));
                if (PasswordHasher.verify(password, hash)) {
                    String tokenKey = record.getValue(qfs("tokenKey"));
                    String id = record.getValue(qfs("id"));
                    String email = record.getValue(qfs("email"));
                    String created = record.getValue(qfs("created"));
                    String updated = record.getValue(qfs("updated"));

                    Map<String, Object> claims = Map.of(
                            "sub", id,
                            "email", email,
                            "type", "superuser",
                            "collectionId", "pbc_superusers",
                            "collectionName", SUPERUSERS,
                            "tokenType", "auth",
                            "tokenKey", tokenKey
                    );
                    String token = tokenService.create(claims, Duration.ofDays(7));
                    Map<String, Object> rec = Map.of(
                            "id", id,
                            "email", email,
                            "collectionId", "pbc_superusers",
                            "collectionName", SUPERUSERS,
                            "created", created,
                            "updated", updated
                    );
                    return Map.of("token", token, "record", rec);
                }
            }
            throw new ApiException(400, "Invalid identity or password.");
        }

        throw new ApiException(400, "Only superusers auth is supported in SQLite MVP.");
    }

    public Map<String, Object> authWithOAuth2(String collection, JsonNode body, Map<String, String> query, RequestPrincipal principal) {
        return AuthProcessor.authWithOAuth2(storeContext, mapper, tokenService, collection, body);
    }

    public Map<String, Object> authRefresh(String collection, RequestPrincipal principal, Map<String, String> query) {
        return AuthProcessor.authRefresh(storeContext, tokenService, collection, principal);
    }

    public Map<String, Object> authMethods(String collection) {
        return AuthProcessor.authMethods(storeContext, collection);
    }

    public void requestPasswordReset(String collection, JsonNode body) {
        AuthProcessor.requestPasswordReset(storeContext, tokenService, collection, body);
    }

    public void confirmPasswordReset(String collection, JsonNode body) {
        AuthProcessor.confirmPasswordReset(storeContext, tokenService, collection, body);
    }

    public void requestVerification(String collection, JsonNode body) {
        AuthProcessor.requestVerification(storeContext, tokenService, collection, body);
    }

    public void confirmVerification(String collection, JsonNode body) {
        AuthProcessor.confirmVerification(storeContext, tokenService, collection, body);
    }

    public void requestEmailChange(String collection, JsonNode body, RequestPrincipal principal) {
        AuthProcessor.requestEmailChange(storeContext, tokenService, collection, body, principal);
    }

    public void confirmEmailChange(String collection, JsonNode body) {
        AuthProcessor.confirmEmailChange(storeContext, tokenService, collection, body);
    }

    public Map<String, Object> impersonate(String collection, String id, JsonNode body, Map<String, String> query) {
        Map<String, Object> record = recordRepository.getRecord(collection, id, query, null);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("id", record.get("id"));
        claims.put("type", "authRecord");
        claims.put("collectionId", record.get("collectionId"));
        long seconds = 3600L;
        if (body != null && body.has("duration")) {
            seconds = Math.max(60L, Math.min(604_800L, body.get("duration").asLong(3600L)));
        }
        return Map.of("token", tokenService.create(claims, Duration.ofSeconds(seconds)), "record", record);
    }

    public Map<String, Object> requestOtp(String collection, JsonNode body) {
        CollectionSchema colSchema = storeContext.getCollection(collection);
        if (colSchema == null || !"auth".equals(colSchema.type)) {
            throw new ApiException(400, "The collection is not an auth collection.");
        }
        if (colSchema.otp == null || !colSchema.otp.enabled) {
            throw new ApiException(403, "The collection is not configured to allow OTP authentication.");
        }

        String email = body != null && body.has("email") ? body.get("email").asText().trim().toLowerCase() : "";
        if (email.isEmpty()) {
            throw new ApiException(400, "email is required.",
                    Map.of("email", Map.of("code", "validation_required", "message", "email is required.")));
        }

        Map<String, Object> record = storeContext.findRecordByEmail(colSchema, email);
        if (record == null) {
            // Return a dummy otpId to avoid email enumeration
            return Map.of("otpId", IdGenerator.id());
        }

        // Generate OTP code
        int length = colSchema.otp.length;
        String code = IdGenerator.digits(length);
        String otpId = IdGenerator.id();
        String now = Instant.now().toString();

        // Persist OTP record in _otps table
        try {
            database.dsl()
                    .insertInto(qt("_otps"))
                    .columns(qfs("id"), qfs("created"), qfs("updated"), qfs("recordId"),
                            qfs("collectionId"), qfs("passwordHash"), qfs("sentTo"))
                    .values(otpId, now, now, String.valueOf(record.get("id")),
                            colSchema.id, PasswordHasher.hash(code), email)
                    .execute();
        } catch (Exception e) {
            throw new ApiException(400, "Failed to request OTP.");
        }

        return Map.of("otpId", otpId);
    }

    public Map<String, Object> authWithOtp(String collection, JsonNode body, Map<String, String> query) {
        CollectionSchema colSchema = storeContext.getCollection(collection);
        if (colSchema == null || !"auth".equals(colSchema.type)) {
            throw new ApiException(400, "The collection is not an auth collection.");
        }
        if (colSchema.otp == null || !colSchema.otp.enabled) {
            throw new ApiException(403, "The collection is not configured to allow OTP authentication.");
        }

        String otpId = body != null && body.has("otpId") ? body.get("otpId").asText() : "";
        String password = body != null && body.has("password") ? body.get("password").asText() : "";

        if (otpId.isEmpty()) {
            throw new ApiException(400, "otpId is required.",
                    Map.of("otpId", Map.of("code", "validation_required", "message", "otpId is required.")));
        }
        if (password.isEmpty() || password.length() > 71) {
            throw new ApiException(400, "Invalid OTP password.",
                    Map.of("password", Map.of("code", "validation_invalid_value", "message", "Invalid OTP password.")));
        }

        // Look up OTP record
        var otpRecord = database.dsl()
                .selectFrom(qt("_otps"))
                .where(qfs("id").eq(otpId))
                .fetchOne();

        if (otpRecord == null) {
            throw new ApiException(400, "Invalid or expired OTP.");
        }

        String recordId = otpRecord.getValue(qfs("recordId"), String.class);
        String collectionId = otpRecord.getValue(qfs("collectionId"), String.class);
        String passwordHash = otpRecord.getValue(qfs("passwordHash"), String.class);

        if (!colSchema.id.equals(collectionId) && !colSchema.name.equals(collection)) {
            throw new ApiException(400, "Invalid or expired OTP.");
        }

        // Verify OTP password
        if (!PasswordHasher.verify(password, passwordHash)) {
            // Delete OTP on failed attempt to prevent brute force
            try {
                database.dsl().deleteFrom(qt("_otps")).where(qfs("id").eq(otpId)).execute();
            } catch (Exception ignored) {}
            throw new ApiException(400, "Invalid or expired OTP.");
        }

        // OTP verified - delete it (one-time use)
        try {
            database.dsl().deleteFrom(qt("_otps")).where(qfs("id").eq(otpId)).execute();
        } catch (Exception ignored) {}

        // Get the auth record and issue a token
        Map<String, Object> record = storeContext.getRecord(colSchema, recordId);
        if (record == null) {
            throw new ApiException(400, "Auth record no longer exists.");
        }

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", record.get("id"));
        claims.put("email", record.getOrDefault("email", ""));
        claims.put("type", colSchema.system ? "superuser" : "auth");
        claims.put("collectionId", colSchema.id);
        claims.put("collectionName", colSchema.name);
        claims.put("tokenType", "auth");
        claims.put("tokenKey", record.getOrDefault("tokenKey", ""));

        String token = tokenService.create(claims, Duration.ofDays(7));
        return Map.of("token", token, "record", record);
    }

    public Optional<Map<String, Object>> verifyToken(String token) {
        return tokenService.verify(token);
    }
}
