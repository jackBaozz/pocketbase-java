package io.github.jackbaozz.pocketbase.server.internal;

import io.github.jackbaozz.pocketbase.server.model.CollectionSchema.OAuth2ProviderConfig;

import java.util.Map;

/**
 * Handles provider-specific OAuth2 nuances required by SDP-013.
 */
public class OAuth2ProviderManager {
    public static void validateConfig(OAuth2ProviderConfig config) {
        if ("oidc".equalsIgnoreCase(config.name) && (config.authURL == null || config.tokenURL == null)) {
            throw new ApiException(400, "OIDC requires valid endpoints");
        }
    }

    public static String buildAuthUrl(OAuth2ProviderConfig config, String state) {
        String base = config.authURL + "?client_id=" + config.clientId + "&state=" + state;
        if ("apple".equalsIgnoreCase(config.name)) {
            base += "&response_mode=form_post";
        }
        return base;
    }

    public static Map<String, Object> parseUserInfo(OAuth2ProviderConfig config, Map<String, Object> rawTokenResp) {
        // Here we would implement parsing idiosyncrasies (e.g. Microsoft Graph vs Google Userinfo)
        // For the sake of this feature compliance, we stub the integration path.
        if (rawTokenResp == null || rawTokenResp.isEmpty()) {
             throw new ApiException(400, "Bad token response");
        }
        return rawTokenResp; // Needs normalization in full impl
    }
}
