package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;

import static io.github.jackbaozz.pocketbase.server.internal.RelationalStorageEngine.SUPERUSERS;

public class AuthRepository {
    private final RelationalStorageEngine engine;

    public AuthRepository(RelationalStorageEngine engine) {
        this.engine = engine;
    }

    public Map<String, Object> authWithPassword(String collection, JsonNode body, Map<String, String> query) {
        String identity = body.get("identity").asText();
        String password = body.get("password").asText();

        if (SUPERUSERS.equals(collection)) {
            try (Connection conn = engine.connection();
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
                            String token = engine.tokenService.create(claims, Duration.ofDays(7));
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

        CollectionSchema colSchema = engine.getCollectionRepository().getCollectionSchema(collection);
        if (!"auth".equals(colSchema.type)) {
            throw new ApiException(400, "Only auth collections can perform authentication.");
        }

        String id = null;
        String hash = null;
        String tokenKey = null;
        try (Connection conn = engine.connection();
             PreparedStatement select = conn.prepareStatement(
                     "SELECT * FROM " + engine.qt(colSchema.name) + " WHERE email = ? OR username = ?")) {
            select.setString(1, identity);
            select.setString(2, identity);
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    hash = rs.getString("passwordHash");
                    tokenKey = rs.getString("tokenKey");
                    id = rs.getString("id");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        if (id != null && hash != null && PasswordHasher.verify(password, hash)) {
            Map<String, Object> claims = Map.of(
                    "sub", id,
                    "type", "authRecord",
                    "collectionId", colSchema.id,
                    "collectionName", colSchema.name,
                    "tokenType", "auth",
                    "tokenKey", tokenKey == null ? "" : tokenKey
            );
            String token = engine.tokenService.create(claims, Duration.ofDays(7));
            RequestPrincipal loginPrincipal = RequestPrincipal.fromClaims(claims);
            Map<String, Object> rawRecord = engine.getRecord(colSchema, id);
            Map<String, Object> record = io.github.jackbaozz.pocketbase.server.internal.RecordProcessor.process(engine, colSchema, rawRecord, false, Map.of(), loginPrincipal);
            return Map.of("token", token, "record", record);
        }

        throw new ApiException(400, "Invalid identity or password.");
    }

    public Map<String, Object> authWithOAuth2(String collection, JsonNode body, Map<String, String> query, RequestPrincipal principal) {
        return AuthProcessor.authWithOAuth2(engine, engine.mapper, engine.tokenService, collection, body);
    }

    public Map<String, Object> authRefresh(String collection, RequestPrincipal principal, Map<String, String> query) {
        return AuthProcessor.authRefresh(engine, engine.tokenService, collection, principal);
    }

    public Map<String, Object> authMethods(String collection) {
        return AuthProcessor.authMethods(engine, collection);
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
        return Map.of("token", "dummy-impersonate", "record", engine.getRecord(collection, id, query, null));
    }

    public Map<String, Object> authWithOtp(String collection, JsonNode body, Map<String, String> query) {
        throw new UnsupportedOperationException("OTP auth not implemented");
    }
}
