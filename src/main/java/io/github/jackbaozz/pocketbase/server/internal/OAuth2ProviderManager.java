package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema.OAuth2ProviderConfig;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles provider-specific OAuth2 nuances required by SDP-013.
 */
public final class OAuth2ProviderManager {
    private static final TypeReference<List<ProviderMetadata>> PROVIDER_LIST = new TypeReference<>() {
    };

    public record ProviderMetadata(
            String name,
            String displayName,
            String logo,
            String authURL,
            String tokenURL,
            String userInfoURL,
            List<String> scopes,
            boolean pkce,
            int order,
            Map<String, Object> extra
    ) {
    }

    private static final List<ProviderMetadata> PROVIDERS = loadProviders();

    private OAuth2ProviderManager() {
    }

    public static List<ProviderMetadata> providers() {
        return PROVIDERS;
    }

    public static ProviderMetadata providerMetadata(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return PROVIDERS.stream()
                .filter(provider -> provider.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    public static OAuth2ProviderConfig initialize(OAuth2ProviderConfig config) {
        if (config == null) {
            return null;
        }
        ProviderMetadata metadata = providerMetadata(config.name);
        if (metadata == null) {
            throw new ApiException(400, "Invalid or missing OAuth2 provider.",
                    ApiErrors.invalidField("name", "Invalid or missing provider with name " + text(config.name) + "."));
        }

        OAuth2ProviderConfig initialized = new OAuth2ProviderConfig();
        initialized.name = metadata.name();
        initialized.clientId = text(config.clientId);
        initialized.clientSecret = text(config.clientSecret);
        initialized.authURL = firstNonBlank(config.authURL, metadata.authURL());
        initialized.tokenURL = firstNonBlank(config.tokenURL, metadata.tokenURL());
        initialized.userInfoURL = firstNonBlank(config.userInfoURL, metadata.userInfoURL());
        initialized.displayName = firstNonBlank(config.displayName, metadata.displayName());
        initialized.scopes = config.scopes == null || config.scopes.isEmpty()
                ? new ArrayList<>(metadata.scopes() == null ? List.of() : metadata.scopes())
                : config.scopes.stream()
                .filter(scope -> scope != null && !scope.isBlank())
                .map(String::trim)
                .toList();
        initialized.pkce = config.pkce == null ? metadata.pkce() : config.pkce;
        initialized.extra = new LinkedHashMap<>();
        if (metadata.extra() != null) {
            initialized.extra.putAll(metadata.extra());
        }
        if (config.extra != null) {
            initialized.extra.putAll(config.extra);
        }
        return initialized;
    }

    public static Map<String, String> authUrlParameters(OAuth2ProviderConfig config) {
        Map<String, String> params = new LinkedHashMap<>();
        if ("apple".equalsIgnoreCase(config.name)) {
            params.put("response_mode", "form_post");
        }
        return params;
    }

    public static Map<String, Object> parseUserInfo(OAuth2ProviderConfig config, Map<String, Object> userInfo) {
        if (userInfo == null || userInfo.isEmpty()) {
            throw new ApiException(400, "Failed to fetch OAuth2 user.",
                    ApiErrors.invalidField("provider", "OAuth2 user info is empty."));
        }
        return userInfo;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isBlank();
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return isBlank(preferred) ? text(fallback) : preferred.trim();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static List<ProviderMetadata> loadProviders() {
        try (InputStream input = OAuth2ProviderManager.class.getResourceAsStream("/pocketbase-oauth2-providers.json")) {
            if (input == null) {
                throw new IllegalStateException("missing PocketBase OAuth2 provider metadata resource");
            }
            return List.copyOf(new ObjectMapper().readValue(input, PROVIDER_LIST));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
