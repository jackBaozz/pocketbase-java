package io.github.jackbaozz.pocketbase.server.internal;

import io.github.jackbaozz.pocketbase.server.model.CollectionSchema.OAuth2ProviderConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handles provider-specific OAuth2 nuances required by SDP-013.
 */
final class OAuth2ProviderManager {
    private OAuth2ProviderManager() {
    }

    static void validateConfig(OAuth2ProviderConfig config) {
        if (config == null || config.name == null || config.name.isBlank()) {
            throw new ApiException(400, "OAuth2 provider name is required.");
        }
        if ("oidc".equalsIgnoreCase(config.name) && (isBlank(config.authURL) || isBlank(config.tokenURL))) {
            throw new ApiException(400, "OIDC requires authURL and tokenURL.");
        }
    }

    static Map<String, String> authUrlParameters(OAuth2ProviderConfig config) {
        Map<String, String> params = new LinkedHashMap<>();
        if ("apple".equalsIgnoreCase(config.name)) {
            params.put("response_mode", "form_post");
        }
        return params;
    }

    static Map<String, Object> parseUserInfo(OAuth2ProviderConfig config, Map<String, Object> userInfo) {
        if (userInfo == null || userInfo.isEmpty()) {
            throw new ApiException(400, "Failed to fetch OAuth2 user.", Map.of("provider", Map.of("message", "OAuth2 user info is empty.")));
        }
        return userInfo;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isBlank();
    }
}
