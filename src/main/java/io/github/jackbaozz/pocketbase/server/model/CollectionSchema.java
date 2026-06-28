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
    public static final long DEFAULT_AUTH_TOKEN_DURATION = 1_209_600L;
    public static final long DEFAULT_PASSWORD_RESET_TOKEN_DURATION = 1_800L;
    public static final long DEFAULT_VERIFICATION_TOKEN_DURATION = 604_800L;
    public static final long DEFAULT_EMAIL_CHANGE_TOKEN_DURATION = 1_800L;
    public static final long DEFAULT_FILE_TOKEN_DURATION = 180L;

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
    public TokenConfig authToken = new TokenConfig(DEFAULT_AUTH_TOKEN_DURATION);
    public TokenConfig passwordResetToken = new TokenConfig(DEFAULT_PASSWORD_RESET_TOKEN_DURATION);
    public TokenConfig verificationToken = new TokenConfig(DEFAULT_VERIFICATION_TOKEN_DURATION);
    public TokenConfig emailChangeToken = new TokenConfig(DEFAULT_EMAIL_CHANGE_TOKEN_DURATION);
    public TokenConfig fileToken = new TokenConfig(DEFAULT_FILE_TOKEN_DURATION);

    public List<String> indexes = new ArrayList<>();

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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TokenConfig {
        public long duration;
        public String secret;

        public TokenConfig() {
        }

        public TokenConfig(long duration) {
            this.duration = duration;
        }
    }
}
