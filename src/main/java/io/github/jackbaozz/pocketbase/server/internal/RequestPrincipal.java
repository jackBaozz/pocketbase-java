package io.github.jackbaozz.pocketbase.server.internal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authenticated request state used by API rules.
 */
public record RequestPrincipal(
        String id,
        String collectionId,
        String collectionName,
        String email,
        boolean superuser,
        Map<String, Object> claims
) {
    public RequestPrincipal {
        claims = claims == null ? Map.of() : Map.copyOf(claims);
    }

    static RequestPrincipal fromClaims(Map<String, Object> claims) {
        Map<String, Object> safeClaims = claims == null ? Map.of() : new LinkedHashMap<>(claims);
        return new RequestPrincipal(
                text(safeClaims.get("sub")),
                text(safeClaims.get("collectionId")),
                text(safeClaims.get("collectionName")),
                text(safeClaims.get("email")),
                "superuser".equals(safeClaims.get("type")),
                safeClaims
        );
    }

    Map<String, Object> asRuleMap() {
        Map<String, Object> data = new LinkedHashMap<>(claims);
        data.put("id", id == null ? "" : id);
        data.put("collectionId", collectionId == null ? "" : collectionId);
        data.put("collectionName", collectionName == null ? "" : collectionName);
        data.put("email", email == null ? "" : email);
        data.put("superuser", superuser);
        return data;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
