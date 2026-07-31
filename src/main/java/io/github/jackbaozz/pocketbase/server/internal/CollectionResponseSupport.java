package io.github.jackbaozz.pocketbase.server.internal;

import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CollectionResponseSupport {
  private static final List<String> TOKEN_CONFIGS =
      List.of(
          "authToken", "passwordResetToken", "emailChangeToken", "verificationToken", "fileToken");

  private CollectionResponseSupport() {
  }

  public static Map<String, Object> redactSecrets(Map<String, Object> collection) {
    if (collection == null || !"auth".equals(collection.get("type"))) {
      return collection;
    }
    redactAuthOptions(collection);
    Object options = collection.get("options");
    if (options instanceof Map<?, ?> map) {
      redactAuthOptions(castMap(map));
    }
    return collection;
  }

  public static void preserveOAuth2ClientSecrets(
      CollectionSchema.OAuth2Config current, CollectionSchema.OAuth2Config updated) {
    if (current == null
        || current.providers == null
        || updated == null
        || updated.providers == null) {
      return;
    }
    Map<String, String> secrets = new LinkedHashMap<>();
    for (CollectionSchema.OAuth2ProviderConfig provider : current.providers) {
      if (provider != null
          && provider.name != null
          && provider.clientSecret != null
          && !provider.clientSecret.isBlank()) {
        secrets.put(provider.name.toLowerCase(java.util.Locale.ROOT), provider.clientSecret);
      }
    }
    for (CollectionSchema.OAuth2ProviderConfig provider : updated.providers) {
      if (provider == null
          || provider.name == null
          || provider.clientSecret != null && !provider.clientSecret.isBlank()) {
        continue;
      }
      String secret = secrets.get(provider.name.toLowerCase(java.util.Locale.ROOT));
      if (secret != null) {
        provider.clientSecret = secret;
      }
    }
  }

  private static void redactAuthOptions(Map<String, Object> options) {
    for (String name : TOKEN_CONFIGS) {
      Object raw = options.get(name);
      if (raw instanceof Map<?, ?> map) {
        castMap(map).put("secret", "");
      }
    }
    Object rawOauth2 = options.get("oauth2");
    if (!(rawOauth2 instanceof Map<?, ?> oauth2)) {
      return;
    }
    Object rawProviders = oauth2.get("providers");
    if (!(rawProviders instanceof List<?> providers)) {
      return;
    }
    for (Object rawProvider : providers) {
      if (rawProvider instanceof Map<?, ?> provider) {
        castMap(provider).put("clientSecret", "");
      }
    }
  }

  private static Map<String, Object> castMap(Map<?, ?> source) {
    return Unsafe.stringObjectMap(source);
  }
}
