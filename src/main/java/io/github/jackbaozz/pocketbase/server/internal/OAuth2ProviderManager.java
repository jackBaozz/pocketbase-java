package io.github.jackbaozz.pocketbase.server.internal;

import io.github.jackbaozz.pocketbase.server.model.CollectionSchema.OAuth2ProviderConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles provider-specific OAuth2 nuances required by SDP-013.
 */
public final class OAuth2ProviderManager {
    public record ProviderMetadata(String name, String displayName, String logo) {
    }

    private static final List<ProviderMetadata> PROVIDERS = List.of(
            new ProviderMetadata("apple", "Apple", ""),
            new ProviderMetadata("bitbucket", "Bitbucket", ""),
            new ProviderMetadata("box", "Box", ""),
            new ProviderMetadata("discord", "Discord", ""),
            new ProviderMetadata("facebook", "Facebook", ""),
            new ProviderMetadata("gitea", "Gitea", ""),
            new ProviderMetadata("gitee", "Gitee", ""),
            new ProviderMetadata("github", "GitHub", ""),
            new ProviderMetadata("gitlab", "GitLab", ""),
            new ProviderMetadata("google", "Google", ""),
            new ProviderMetadata("instagram", "Instagram", ""),
            new ProviderMetadata("kakao", "Kakao", ""),
            new ProviderMetadata("lark", "Lark", ""),
            new ProviderMetadata("linear", "Linear", ""),
            new ProviderMetadata("livechat", "LiveChat", ""),
            new ProviderMetadata("mailcow", "mailcow", ""),
            new ProviderMetadata("microsoft", "Microsoft", ""),
            new ProviderMetadata("monday", "monday.com", ""),
            new ProviderMetadata("notion", "Notion", ""),
            new ProviderMetadata("oidc", "OIDC", ""),
            new ProviderMetadata("patreon", "Patreon", ""),
            new ProviderMetadata("planningcenter", "Planning Center", ""),
            new ProviderMetadata("spotify", "Spotify", ""),
            new ProviderMetadata("strava", "Strava", ""),
            new ProviderMetadata("trakt", "Trakt", ""),
            new ProviderMetadata("twitch", "Twitch", ""),
            new ProviderMetadata("twitter", "Twitter", ""),
            new ProviderMetadata("vk", "VK", ""),
            new ProviderMetadata("wakatime", "WakaTime", ""),
            new ProviderMetadata("yandex", "Yandex", "")
    );

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
                .filter(provider -> provider.name().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    public static void validateConfig(OAuth2ProviderConfig config) {
        if (config == null || config.name == null || config.name.isBlank()) {
            throw new ApiException(400, "OAuth2 provider name is required.",
                    ApiErrors.requiredField("name"));
        }
        if ("oidc".equalsIgnoreCase(config.name) && (isBlank(config.authURL) || isBlank(config.tokenURL))) {
            Map<String, Object> errors = new LinkedHashMap<>();
            if (isBlank(config.authURL)) {
                errors.putAll(ApiErrors.requiredField("authURL"));
            }
            if (isBlank(config.tokenURL)) {
                errors.putAll(ApiErrors.requiredField("tokenURL"));
            }
            throw new ApiException(400, "OIDC requires authURL and tokenURL.", errors);
        }
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
}
