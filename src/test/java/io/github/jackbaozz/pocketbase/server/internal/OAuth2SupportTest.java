package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OAuth2SupportTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void appleRedirectNameIsBoundedAndConsumedOnce() {
        String code = "a".repeat(985);
        String lastName = "b".repeat(200);

        assertTrue(OAuth2Support.storeAppleRedirectName(
                mapper,
                code,
                "{\"name\":{\"firstName\":\"Ada\",\"lastName\":\"" + lastName + "\"}}"
        ));
        assertEquals("Ada " + "b".repeat(146), OAuth2Support.consumeAppleRedirectName(code));
        assertEquals("", OAuth2Support.consumeAppleRedirectName(code));

        assertFalse(OAuth2Support.storeAppleRedirectName(
                mapper,
                "a".repeat(986),
                "{\"name\":{\"firstName\":\"Too\",\"lastName\":\"Long\"}}"
        ));
        assertFalse(OAuth2Support.storeAppleRedirectName(mapper, "invalid-json", "{"));
    }

    @Test
    void avatarDownloadRejectsLoopbackAndPrivateTargets() {
        assertTrue(OAuth2Support.downloadFile("http://127.0.0.1/avatar.png", 1024).isEmpty());
        assertTrue(OAuth2Support.downloadFile("http://localhost/avatar.png", 1024).isEmpty());
        assertTrue(OAuth2Support.downloadFile("file:///tmp/avatar.png", 1024).isEmpty());
    }

    @Test
    void standardProviderDefaultsAndOverridesMatchOfficialMetadata() {
        assertEquals(32, OAuth2ProviderManager.providers().size());
        assertEquals("apple", OAuth2ProviderManager.providers().get(0).name());

        CollectionSchema.OAuth2ProviderConfig config = new CollectionSchema.OAuth2ProviderConfig();
        config.name = "bitbucket";
        config.clientId = "client-id";
        config.clientSecret = "client-secret";

        CollectionSchema.OAuth2ProviderConfig initialized = OAuth2ProviderManager.initialize(config);
        assertEquals("Bitbucket", initialized.displayName);
        assertEquals("https://bitbucket.org/site/oauth2/authorize", initialized.authURL);
        assertEquals("https://bitbucket.org/site/oauth2/access_token", initialized.tokenURL);
        assertEquals("https://api.bitbucket.org/2.0/user", initialized.userInfoURL);
        assertEquals(List.of("account"), initialized.scopes);
        assertFalse(initialized.pkce);

        OAuth2Support.AuthMethodProviderInfo defaults = OAuth2Support.authMethodInfo(
                config,
                initialized.displayName,
                OAuth2ProviderManager.providerMetadata(config.name).logo()
        );
        assertTrue(defaults.authURL().contains("client_id=client-id"));
        assertTrue(defaults.authURL().contains("scope=account"));
        assertTrue(defaults.authURL().endsWith("&redirect_uri="));
        assertEquals("", defaults.codeVerifier());
        assertEquals("", defaults.codeChallenge());
        assertEquals("", defaults.codeChallengeMethod());

        config.authURL = "https://example.com/authorize";
        config.displayName = "Custom Bitbucket";
        config.scopes = List.of("custom");
        config.pkce = true;
        CollectionSchema.OAuth2ProviderConfig overridden = OAuth2ProviderManager.initialize(config);
        assertEquals("https://example.com/authorize", overridden.authURL);
        assertEquals("Custom Bitbucket", overridden.displayName);
        assertEquals(List.of("custom"), overridden.scopes);
        assertTrue(overridden.pkce);
    }

    @Test
    void mappedFieldsRespectCreateDataAndMissingMappingsAreCleared() {
        CollectionSchema collection = new CollectionSchema();
        collection.type = "auth";
        collection.fields = List.of(
                new FieldSchema("provider_id", "providerUid", "text", false, false, false),
                new FieldSchema("display_name", "displayName", "text", false, false, false),
                new FieldSchema("login_name", "loginName", "text", false, true, false),
                new FieldSchema("avatar_link", "avatarLink", "url", false, false, false)
        );
        collection.oauth2.mappedFields.id = "providerUid";
        collection.oauth2.mappedFields.name = "displayName";
        collection.oauth2.mappedFields.username = "loginName";
        collection.oauth2.mappedFields.avatarURL = "avatarLink";

        ObjectNode payload = mapper.createObjectNode();
        payload.put("displayName", "Submitted Name");
        OAuth2Support.OAuth2User user = new OAuth2Support.OAuth2User(
                "provider-123",
                "mapped@example.com",
                "Provider Name",
                "provider-user",
                "https://cdn.example.com/avatar.png",
                new LinkedHashMap<>()
        );
        Map<String, List<UploadedFile>> files = OAuth2FieldMappingSupport.apply(
                collection,
                payload,
                user,
                (field, value) -> true
        );

        assertEquals("provider-123", payload.get("providerUid").asText());
        assertEquals("Submitted Name", payload.get("displayName").asText());
        assertEquals("provider-user", payload.get("loginName").asText());
        assertEquals("https://cdn.example.com/avatar.png", payload.get("avatarLink").asText());
        assertTrue(files.isEmpty());

        collection.oauth2.mappedFields.name = "missing";
        OAuth2FieldMappingSupport.normalize(collection);
        assertEquals("", collection.oauth2.mappedFields.name);
    }
}
