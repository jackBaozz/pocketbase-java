package io.github.jackbaozz.pocketbase.server.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Collection metadata persisted by the embedded runtime.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CollectionSchema {
    public String id;
    public String name;
    public String type = "base";
    public boolean system;
    public String listRule;
    public String viewRule;
    public String createRule;
    public String updateRule;
    public String deleteRule;
    public String created;
    public String updated;
    public PasswordAuthConfig passwordAuth = new PasswordAuthConfig();
    public OtpConfig otp = new OtpConfig();
    public MfaConfig mfa = new MfaConfig();
    public OAuth2Config oauth2 = new OAuth2Config();

    @JsonAlias("schema")
    public List<FieldSchema> fields = new ArrayList<>();

    public CollectionSchema() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PasswordAuthConfig {
        public boolean enabled = true;
        public List<String> identityFields = new ArrayList<>(List.of("email"));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OtpConfig {
        public boolean enabled;
        public long duration = 300;
        public int length = 6;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MfaConfig {
        public boolean enabled;
        public long duration = 1800;
        public String rule;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OAuth2Config {
        public boolean enabled;
        public List<OAuth2ProviderConfig> providers = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OAuth2ProviderConfig {
        public String name;
        public String clientId;
        public String clientSecret;
        public String authURL;
        public String tokenURL;
        public String userInfoURL;
        public List<String> scopes = new ArrayList<>();
        public boolean pkce = true;
    }
}
