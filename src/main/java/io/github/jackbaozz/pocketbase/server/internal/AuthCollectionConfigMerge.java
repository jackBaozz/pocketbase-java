package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Restores PocketBase's nested patch semantics after collection normalization. */
public final class AuthCollectionConfigMerge {
  private AuthCollectionConfigMerge() {
  }

  public static void mergeSubmitted(
      ObjectMapper mapper, CollectionSchema target, CollectionSchema base, JsonNode body) {
    if (target == null || body == null || !body.isObject() || !"auth".equals(target.type)) {
      return;
    }
    CollectionSchema defaults = base == null ? new CollectionSchema() : base;
    JsonNode passwordAuth = option(body, "passwordAuth");
    if (passwordAuth != null) {
      target.passwordAuth =
          merge(
              mapper,
              defaults.passwordAuth,
              passwordAuth,
              CollectionSchema.PasswordAuthConfig.class);
    }
    JsonNode otp = option(body, "otp");
    if (otp != null) {
      target.otp = merge(mapper, defaults.otp, otp, CollectionSchema.OtpConfig.class);
    }
    JsonNode mfa = option(body, "mfa");
    if (mfa != null) {
      target.mfa = merge(mapper, defaults.mfa, mfa, CollectionSchema.MfaConfig.class);
    }
    JsonNode oauth2 = option(body, "oauth2");
    if (oauth2 != null) {
      target.oauth2 = mergeOAuth2(mapper, defaults.oauth2, oauth2);
    }
    JsonNode authAlert = option(body, "authAlert");
    if (authAlert != null) {
      target.authAlert =
          merge(mapper, defaults.authAlert, authAlert, CollectionSchema.AuthAlertConfig.class);
    }
    target.authToken =
        mergeToken(mapper, target.authToken, defaults.authToken, option(body, "authToken"));
    target.passwordResetToken =
        mergeToken(
            mapper,
            target.passwordResetToken,
            defaults.passwordResetToken,
            option(body, "passwordResetToken"));
    target.verificationToken =
        mergeToken(
            mapper,
            target.verificationToken,
            defaults.verificationToken,
            option(body, "verificationToken"));
    target.emailChangeToken =
        mergeToken(
            mapper,
            target.emailChangeToken,
            defaults.emailChangeToken,
            option(body, "emailChangeToken"));
    target.fileToken =
        mergeToken(mapper, target.fileToken, defaults.fileToken, option(body, "fileToken"));
    JsonNode verificationTemplate = option(body, "verificationTemplate");
    if (verificationTemplate != null) {
      target.verificationTemplate =
          merge(
              mapper,
              defaults.verificationTemplate,
              verificationTemplate,
              CollectionSchema.EmailTemplate.class);
    }
    JsonNode resetPasswordTemplate = option(body, "resetPasswordTemplate");
    if (resetPasswordTemplate != null) {
      target.resetPasswordTemplate =
          merge(
              mapper,
              defaults.resetPasswordTemplate,
              resetPasswordTemplate,
              CollectionSchema.EmailTemplate.class);
    }
    JsonNode confirmEmailChangeTemplate = option(body, "confirmEmailChangeTemplate");
    if (confirmEmailChangeTemplate != null) {
      target.confirmEmailChangeTemplate =
          merge(
              mapper,
              defaults.confirmEmailChangeTemplate,
              confirmEmailChangeTemplate,
              CollectionSchema.EmailTemplate.class);
    }
  }

  private static JsonNode option(JsonNode body, String name) {
    if (body.has(name)) {
      return body.get(name);
    }
    JsonNode options = body.path("options");
    return options.isObject() && options.has(name) ? options.get(name) : null;
  }

  private static CollectionSchema.TokenConfig mergeToken(
      ObjectMapper mapper,
      CollectionSchema.TokenConfig normalized,
      CollectionSchema.TokenConfig base,
      JsonNode patch) {
    if (patch == null) {
      return normalized;
    }
    CollectionSchema.TokenConfig merged =
        merge(mapper, base, patch, CollectionSchema.TokenConfig.class);
    if (merged.secret == null || merged.secret.isBlank()) {
      if (base != null && base.secret != null && !base.secret.isBlank()) {
        merged.secret = base.secret;
      } else if (normalized != null) {
        merged.secret = normalized.secret;
      }
    }
    return merged;
  }

  /**
   * Merges an OAuth2 collection patch while treating each submitted provider as a partial update
   * keyed by its name.
   *
   * <p>The dashboard deliberately omits provider secrets (and can omit other unchanged fields)
   * when a collection is edited. Replacing the providers array wholesale would therefore discard
   * the retained configuration of a provider that is still present in the request.
   */
  private static CollectionSchema.OAuth2Config mergeOAuth2(
      ObjectMapper mapper, CollectionSchema.OAuth2Config base, JsonNode patch) {
    CollectionSchema.OAuth2Config current =
        base == null ? new CollectionSchema.OAuth2Config() : base;
    CollectionSchema.OAuth2Config merged =
        merge(mapper, current, patch, CollectionSchema.OAuth2Config.class);

    if (!patch.isObject() || !patch.has("providers") || !patch.get("providers").isArray()) {
      return merged;
    }

    List<CollectionSchema.OAuth2ProviderConfig> providers = new ArrayList<>();
    for (JsonNode submitted : patch.get("providers")) {
      if (submitted == null || submitted.isNull() || !submitted.isObject()) {
        providers.add(mapper.convertValue(submitted, CollectionSchema.OAuth2ProviderConfig.class));
        continue;
      }

      CollectionSchema.OAuth2ProviderConfig original =
          findProviderByName(current.providers, submitted.path("name").asText(null));
      CollectionSchema.OAuth2ProviderConfig mergedProvider =
          original == null
              ? mapper.convertValue(submitted, CollectionSchema.OAuth2ProviderConfig.class)
              : merge(mapper, original, submitted, CollectionSchema.OAuth2ProviderConfig.class);
      // The Admin UI sends an empty clientSecret as a redacted-secret sentinel. Retain the
      // persisted value for an existing provider, as the pre-v0.40.1 update path already did.
      if (original != null
          && original.clientSecret != null
          && !original.clientSecret.isBlank()
          && submitted.has("clientSecret")
          && (submitted.path("clientSecret").isNull()
              || submitted.path("clientSecret").asText().isBlank())) {
        mergedProvider.clientSecret = original.clientSecret;
      }
      providers.add(mergedProvider);
    }
    merged.providers = providers;
    return merged;
  }

  private static CollectionSchema.OAuth2ProviderConfig findProviderByName(
      List<CollectionSchema.OAuth2ProviderConfig> providers, String name) {
    if (providers == null || name == null) {
      return null;
    }
    for (CollectionSchema.OAuth2ProviderConfig provider : providers) {
      if (provider != null && name.equals(provider.name)) {
        return provider;
      }
    }
    return null;
  }

  private static <T> T merge(ObjectMapper mapper, T base, JsonNode patch, Class<T> type) {
    T copy = mapper.convertValue(base, type);
    if (patch == null || patch.isNull()) {
      return copy;
    }
    try {
      return mapper.readerForUpdating(copy).readValue(patch.traverse(mapper));
    } catch (IOException | IllegalArgumentException e) {
      throw new ApiException(
          400,
          "Invalid auth collection configuration.",
          ApiErrors.invalidField("options", "Invalid auth collection configuration."));
    }
  }
}
