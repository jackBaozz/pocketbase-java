package io.github.jackbaozz.pocketbase.server.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collection metadata persisted by the embedded runtime.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CollectionSchema {
    public static final long DEFAULT_AUTH_TOKEN_DURATION = 432_000L;
    public static final long DEFAULT_PASSWORD_RESET_TOKEN_DURATION = 1_800L;
    public static final long DEFAULT_VERIFICATION_TOKEN_DURATION = 86_400L;
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
    public String authRule = "";
    public String manageRule;
    public String viewQuery;
    public String created;
    public String updated;
    public PasswordAuthConfig passwordAuth = new PasswordAuthConfig();
    public OtpConfig otp = new OtpConfig();
    public MfaConfig mfa = new MfaConfig();
    public OAuth2Config oauth2 = new OAuth2Config();
    public AuthAlertConfig authAlert = new AuthAlertConfig();
    public TokenConfig authToken = new TokenConfig(DEFAULT_AUTH_TOKEN_DURATION);
    public TokenConfig passwordResetToken = new TokenConfig(DEFAULT_PASSWORD_RESET_TOKEN_DURATION);
    public TokenConfig verificationToken = new TokenConfig(DEFAULT_VERIFICATION_TOKEN_DURATION);
    public TokenConfig emailChangeToken = new TokenConfig(DEFAULT_EMAIL_CHANGE_TOKEN_DURATION);
    public TokenConfig fileToken = new TokenConfig(DEFAULT_FILE_TOKEN_DURATION);
    public EmailTemplate verificationTemplate = EmailTemplate.verification();
    public EmailTemplate resetPasswordTemplate = EmailTemplate.passwordReset();
    public EmailTemplate confirmEmailChangeTemplate = EmailTemplate.emailChange();

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
        public long duration = 180;
        public int length = 8;
        public EmailTemplate emailTemplate = EmailTemplate.otp();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MfaConfig {
        public boolean enabled;
        public long duration = 600;
        public String rule;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthAlertConfig {
        public boolean enabled = true;
        public EmailTemplate emailTemplate = EmailTemplate.authAlert();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmailTemplate {
        public String subject;
        public String body;

        public EmailTemplate() {
        }

        public EmailTemplate(String subject, String body) {
            this.subject = subject;
            this.body = body;
        }

        public static EmailTemplate verification() {
            return new EmailTemplate(
                    "Verify your {APP_NAME} email",
                    """
                    <p>Hello,</p>
                    <p>Thank you for joining us at {APP_NAME}.</p>
                    <p>Click on the button below to verify your email address.</p>
                    <p>
                      <a class="btn" href="{APP_URL}/_/#/auth/confirm-verification/{TOKEN}" target="_blank" rel="noopener">Verify</a>
                    </p>
                    <p><i>If you didn't recently register, please ignore this email.</i></p>
                    <p>
                      Thanks,<br/>
                      {APP_NAME} team
                    </p>
                    """
            );
        }

        public static EmailTemplate passwordReset() {
            return new EmailTemplate(
                    "Reset your {APP_NAME} password",
                    """
                    <p>Hello,</p>
                    <p>Click on the button below to reset your password.</p>
                    <p>
                      <a class="btn" href="{APP_URL}/_/#/auth/confirm-password-reset/{TOKEN}" target="_blank" rel="noopener">Reset password</a>
                    </p>
                    <p><i>If you didn't ask to reset your password, please ignore this email.</i></p>
                    <p>
                      Thanks,<br/>
                      {APP_NAME} team
                    </p>
                    """
            );
        }

        public static EmailTemplate emailChange() {
            return new EmailTemplate(
                    "Confirm your {APP_NAME} new email address",
                    """
                    <p>Hello,</p>
                    <p>Click on the button below to confirm your new email address.</p>
                    <p>
                      <a class="btn" href="{APP_URL}/_/#/auth/confirm-email-change/{TOKEN}" target="_blank" rel="noopener">Confirm new email</a>
                    </p>
                    <p><i>If you didn't ask to change your email address, please ignore this email.</i></p>
                    <p>
                      Thanks,<br/>
                      {APP_NAME} team
                    </p>
                    """
            );
        }

        public static EmailTemplate otp() {
            return new EmailTemplate(
                    "OTP for {APP_NAME}",
                    """
                    <p>Hello,</p>
                    <p>Your one-time password is: <strong>{OTP}</strong></p>
                    <p><i>If you didn't ask for the one-time password, you can ignore this email.</i></p>
                    <p>
                      Thanks,<br/>
                      {APP_NAME} team
                    </p>
                    """
            );
        }

        public static EmailTemplate authAlert() {
            return new EmailTemplate(
                    "Login from a new location",
                    """
                    <p>Hello,</p>
                    <p>We noticed a login to your {APP_NAME} account from a new location:</p>
                    <p><em>{ALERT_INFO}</em></p>
                    <p><strong>If this wasn't you, you should immediately change your {APP_NAME} account password to revoke access from all other locations.</strong></p>
                    <p>If this was you, you may disregard this email.</p>
                    <p>
                      Thanks,<br/>
                      {APP_NAME} team
                    </p>
                    """
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OAuth2Config {
        public boolean enabled;
        public List<OAuth2ProviderConfig> providers = new ArrayList<>();
        public OAuth2MappedFields mappedFields = new OAuth2MappedFields();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OAuth2MappedFields {
        public String id = "";
        public String name = "";
        public String username = "";
        public String avatarURL = "";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OAuth2ProviderConfig {
        public String name;
        public String clientId;
        public String clientSecret;
        public String authURL;
        public String tokenURL;
        public String userInfoURL;
        public String displayName;
        public List<String> scopes = new ArrayList<>();
        public Boolean pkce;
        public Map<String, Object> extra = new LinkedHashMap<>();
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
