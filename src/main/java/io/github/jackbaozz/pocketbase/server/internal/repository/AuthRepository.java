package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.AuthProcessor;
import io.github.jackbaozz.pocketbase.server.internal.IdGenerator;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;
import io.github.jackbaozz.pocketbase.server.internal.PasswordHasher;
import io.github.jackbaozz.pocketbase.server.internal.RecordProcessor;
import io.github.jackbaozz.pocketbase.server.internal.RequestPrincipal;
import io.github.jackbaozz.pocketbase.server.internal.TokenService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

        try (Connection conn = database.connection();
             PreparedStatement check = conn.prepareStatement("SELECT count(*) FROM _superusers");
             ResultSet rs = check.executeQuery()) {
            if (rs.next() && rs.getInt(1) > 0) {
                throw new ApiException(403, "Superuser already exists.");
            }
        } catch (SQLException e) {
            throw new RuntimeException("superuser bootstrap check failed", e);
        }

        String id = "su_" + IdGenerator.id();
        String passHash = PasswordHasher.hash(password);
        String tokenKey = IdGenerator.id();
        String now = Instant.now().toString();

        try (Connection conn = database.connection();
             PreparedStatement insert = conn.prepareStatement(
                     "INSERT INTO _superusers(id, email, passwordHash, tokenKey, created, updated) VALUES(?,?,?,?,?,?)")) {
            insert.setString(1, id);
            insert.setString(2, email);
            insert.setString(3, passHash);
            insert.setString(4, tokenKey);
            insert.setString(5, now);
            insert.setString(6, now);
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new ApiException(400, "Failed to bootstrap superuser: " + e.getMessage());
        }

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
            try (Connection conn = database.connection();
                 PreparedStatement select = conn.prepareStatement(
                         "SELECT id, email, passwordHash, tokenKey, created, updated FROM _superusers WHERE email = ?")) {
                select.setString(1, identity);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        String hash = rs.getString("passwordHash");
                        if (PasswordHasher.verify(password, hash)) {
                            String tokenKey = rs.getString("tokenKey");
                            String id = rs.getString("id");
                            String email = rs.getString("email");
                            String created = rs.getString("created");
                            String updated = rs.getString("updated");

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
                            Map<String, Object> record = Map.of(
                                    "id", id,
                                    "email", email,
                                    "collectionId", "pbc_superusers",
                                    "collectionName", SUPERUSERS,
                                    "created", created,
                                    "updated", updated
                            );
                            return Map.of("token", token, "record", record);
                        }
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
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
        AuthProcessor.requestPasswordReset(collection);
    }

    public void confirmPasswordReset(String collection, JsonNode body) {
        AuthProcessor.confirmPasswordReset(collection);
    }

    public void requestVerification(String collection, JsonNode body) {
        AuthProcessor.requestVerification(collection);
    }

    public void confirmVerification(String collection, JsonNode body) {
        AuthProcessor.confirmVerification(collection);
    }

    public void requestEmailChange(String collection, JsonNode body, RequestPrincipal principal) {
        AuthProcessor.requestEmailChange(collection);
    }

    public void confirmEmailChange(String collection, JsonNode body) {
        AuthProcessor.confirmEmailChange(collection);
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
        throw new ApiException(400, "Failed to request OTP.", Map.of("email", "OTP login is not configured."));
    }

    public Map<String, Object> authWithOtp(String collection, JsonNode body, Map<String, String> query) {
        throw new ApiException(400, "Failed to authenticate with OTP.");
    }

    public Optional<Map<String, Object>> verifyToken(String token) {
        return tokenService.verify(token);
    }
}
