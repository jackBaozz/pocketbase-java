package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class OAuth2Support {
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
  };
  private static final String APPLE_REDIRECT_NAME_PREFIX = "@redirect_name_";
  private static final long APPLE_REDIRECT_NAME_TTL_MILLIS = Duration.ofMinutes(1).toMillis();
  private static final ConcurrentMap<String, AppleRedirectName> APPLE_REDIRECT_NAMES =
      new ConcurrentHashMap<>();

  private OAuth2Support() {
  }

  public static AuthMethodProviderInfo authMethodInfo(
      CollectionSchema.OAuth2ProviderConfig config, String displayName, String logo) {
    config = OAuth2ProviderManager.initialize(config);
    String state = IdGenerator.prefixed("oauth_");
    boolean pkce = Boolean.TRUE.equals(config.pkce);
    String codeVerifier = pkce ? randomVerifier() : "";
    String codeChallenge = pkce ? s256Challenge(codeVerifier) : "";
    String codeChallengeMethod = pkce ? "S256" : "";
    String authUrl = buildAuthUrl(config, state, codeChallenge, codeChallengeMethod);
    return new AuthMethodProviderInfo(
        config.name,
        firstNonBlank(config.displayName, displayName),
        logo,
        state,
        authUrl,
        authUrl,
        codeVerifier,
        codeChallenge,
        codeChallengeMethod);
  }

  public static OAuth2User authenticate(
      ObjectMapper mapper,
      CollectionSchema.OAuth2ProviderConfig config,
      String code,
      String redirectURL,
      String codeVerifier) {
    config = OAuth2ProviderManager.initialize(config);
    if (isBlank(config.tokenURL)) {
      throw new ApiException(
          400,
          "Failed to authenticate.",
          ApiErrors.invalidField("provider", "OAuth2 provider tokenURL is required."));
    }
    Map<String, Object> token = fetchToken(mapper, config, code, redirectURL, codeVerifier);
    Map<String, Object> userInfo =
        OAuth2ProviderManager.parseUserInfo(config, fetchUserInfo(mapper, config, token));
    String providerId = text(userInfo.get("sub"));
    if (providerId.isBlank()) {
      providerId = text(userInfo.get("id"));
    }
    if (providerId.isBlank()) {
      throw new ApiException(
          400,
          "Failed to authenticate.",
          ApiErrors.invalidField("provider", "OAuth2 user id is missing."));
    }
    String email = text(userInfo.get("email"));
    String name = text(userInfo.get("name"));
    if (name.isBlank() && "apple".equalsIgnoreCase(config.name)) {
      name = consumeAppleRedirectName(code);
    }
    String username = text(userInfo.get("preferred_username"));
    if (username.isBlank()) {
      username = text(userInfo.get("login"));
    }
    if (username.isBlank()) {
      username = text(userInfo.get("username"));
    }
    String avatarURL = text(userInfo.get("picture"));
    if (avatarURL.isBlank()) {
      avatarURL = text(userInfo.get("avatar_url"));
    }
    return new OAuth2User(providerId, email, name, username, avatarURL, userInfo);
  }

  static boolean storeAppleRedirectName(ObjectMapper mapper, String code, String serializedUser) {
    if (mapper == null || isBlank(code) || isBlank(serializedUser)) {
      return false;
    }
    String key = APPLE_REDIRECT_NAME_PREFIX + code;
    if (key.length() > 1000) {
      return false;
    }
    try {
      JsonNode name = mapper.readTree(serializedUser).path("name");
      String fullName =
          (name.path("firstName").asText("") + " " + name.path("lastName").asText("")).trim();
      if (fullName.length() > 150) {
        fullName = fullName.substring(0, 150).trim();
      }
      if (fullName.isBlank()) {
        return false;
      }
      long now = System.currentTimeMillis();
      APPLE_REDIRECT_NAMES.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
      APPLE_REDIRECT_NAMES.put(
          key, new AppleRedirectName(fullName, now + APPLE_REDIRECT_NAME_TTL_MILLIS));
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  static String consumeAppleRedirectName(String code) {
    if (isBlank(code)) {
      return "";
    }
    AppleRedirectName stored = APPLE_REDIRECT_NAMES.remove(APPLE_REDIRECT_NAME_PREFIX + code);
    if (stored == null || stored.expiresAtMillis() <= System.currentTimeMillis()) {
      return "";
    }
    return stored.name();
  }

  static Optional<DownloadedFile> downloadFile(String rawUrl, long maxBytes) {
    URI uri;
    try {
      uri = URI.create(rawUrl);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null
        || !publicHost(uri.getHost())) {
      return Optional.empty();
    }
    long limit = maxBytes <= 0 ? 5L << 20 : maxBytes;
    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "*/*")
            .GET()
            .build();
    try {
      HttpResponse<InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        response.body().close();
        return Optional.empty();
      }
      long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
      if (declaredLength > limit) {
        response.body().close();
        return Optional.empty();
      }
      byte[] bytes;
      try (InputStream input = response.body();
          ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
          total += read;
          if (total > limit) {
            return Optional.empty();
          }
          output.write(buffer, 0, read);
        }
        bytes = output.toByteArray();
      }
      String contentType =
          response
              .headers()
              .firstValue("Content-Type")
              .map(value -> value.split(";", 2)[0].trim())
              .filter(value -> !value.isBlank())
              .orElse("application/octet-stream");
      return Optional.of(new DownloadedFile(downloadFilename(uri), contentType, bytes));
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return Optional.empty();
    }
  }

  private static boolean publicHost(String host) {
    try {
      for (InetAddress address : InetAddress.getAllByName(host)) {
        if (address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()
            || uniqueLocalIpv6(address.getAddress())) {
          return false;
        }
      }
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private static boolean uniqueLocalIpv6(byte[] address) {
    return address != null && address.length == 16 && (address[0] & 0xFE) == 0xFC;
  }

  private static String downloadFilename(URI uri) {
    String path = uri.getPath();
    String name =
        path == null || path.isBlank() ? "avatar" : path.substring(path.lastIndexOf('/') + 1);
    name = name.replaceAll("[^A-Za-z0-9._-]", "_");
    return name.isBlank() ? "avatar" : name;
  }

  private static Map<String, Object> fetchToken(
      ObjectMapper mapper,
      CollectionSchema.OAuth2ProviderConfig config,
      String code,
      String redirectURL,
      String codeVerifier) {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    String body =
        form(
            Map.of(
                "grant_type",
                "authorization_code",
                "code",
                code,
                "client_id",
                text(config.clientId),
                "client_secret",
                text(config.clientSecret),
                "redirect_uri",
                redirectURL == null ? "" : redirectURL,
                "code_verifier",
                Boolean.TRUE.equals(config.pkce) ? codeVerifier : ""));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(config.tokenURL))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    try {
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new ApiException(
            400,
            "Failed to fetch OAuth2 token.",
            ApiErrors.invalidField("provider", response.body()));
      }
      return mapper.readValue(response.body(), MAP_TYPE);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new ApiException(
          400, "Failed to fetch OAuth2 token.", ApiErrors.invalidField("provider", e.getMessage()));
    }
  }

  private static Map<String, Object> fetchUserInfo(
      ObjectMapper mapper,
      CollectionSchema.OAuth2ProviderConfig config,
      Map<String, Object> token) {
    String accessToken = text(token.get("access_token"));
    if (isBlank(config.userInfoURL)) {
      String idToken = text(token.get("id_token"));
      if (idToken.isBlank()) {
        throw new ApiException(
            400,
            "Failed to fetch OAuth2 user.",
            ApiErrors.invalidField("provider", "userInfoURL or id_token is required."));
      }
      return parseJwtClaims(mapper, idToken);
    }
    if (accessToken.isBlank()) {
      throw new ApiException(
          400,
          "Failed to fetch OAuth2 user.",
          ApiErrors.invalidField("provider", "access_token is missing."));
    }
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(config.userInfoURL))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build();
    try {
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new ApiException(
            400,
            "Failed to fetch OAuth2 user.",
            ApiErrors.invalidField("provider", response.body()));
      }
      return mapper.readValue(response.body(), MAP_TYPE);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new ApiException(
          400, "Failed to fetch OAuth2 user.", ApiErrors.invalidField("provider", e.getMessage()));
    }
  }

  private static Map<String, Object> parseJwtClaims(ObjectMapper mapper, String jwt) {
    String[] parts = jwt.split("\\.");
    if (parts.length < 2) {
      throw new ApiException(
          400,
          "Failed to fetch OAuth2 user.",
          ApiErrors.invalidField("provider", "Invalid id_token."));
    }
    try {
      byte[] bytes = Base64.getUrlDecoder().decode(parts[1]);
      return mapper.readValue(bytes, MAP_TYPE);
    } catch (IOException | IllegalArgumentException e) {
      throw new ApiException(
          400,
          "Failed to fetch OAuth2 user.",
          ApiErrors.invalidField("provider", "Invalid id_token."));
    }
  }

  private static String buildAuthUrl(
      CollectionSchema.OAuth2ProviderConfig config,
      String state,
      String codeChallenge,
      String codeChallengeMethod) {
    if (isBlank(config.authURL) || isBlank(config.clientId)) {
      return "";
    }
    List<String> scopes = new ArrayList<>();
    if (config.scopes != null) {
      for (String scope : config.scopes) {
        if (!isBlank(scope)) {
          scopes.add(scope.trim());
        }
      }
    }
    List<String> parts = new ArrayList<>();
    parts.add(pair("response_type", "code"));
    parts.add(pair("client_id", config.clientId));
    parts.add(pair("state", state));
    if (!scopes.isEmpty()) {
      parts.add(pair("scope", String.join(" ", scopes)));
    }
    if (!codeChallenge.isBlank()) {
      parts.add(pair("code_challenge", codeChallenge));
      parts.add(pair("code_challenge_method", codeChallengeMethod));
    }
    OAuth2ProviderManager.authUrlParameters(config)
        .forEach((key, value) -> parts.add(pair(key, value)));
    parts.add("redirect_uri=");
    return appendQuery(config.authURL, String.join("&", parts));
  }

  private static String appendQuery(String base, String query) {
    return base + (base.contains("?") ? "&" : "?") + query;
  }

  private static String pair(String key, String value) {
    return encode(key) + "=" + encode(value == null ? "" : value);
  }

  private static String form(Map<String, String> values) {
    List<String> pairs = new ArrayList<>();
    values.forEach(
        (key, value) -> {
          if (value != null && !value.isBlank()) {
            pairs.add(pair(key, value));
          }
        });
    return String.join("&", pairs);
  }

  private static String randomVerifier() {
    return IdGenerator.prefixed("pkce_") + IdGenerator.id();
  }

  private static String s256Challenge(String verifier) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    } catch (Exception e) {
      throw new IllegalStateException("failed to create PKCE challenge", e);
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static String firstNonBlank(String preferred, String fallback) {
    return isBlank(preferred) ? text(fallback) : preferred;
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isBlank();
  }

  public record AuthMethodProviderInfo(
      String name,
      String displayName,
      String logo,
      String state,
      String authURL,
      String authUrl,
      String codeVerifier,
      String codeChallenge,
      String codeChallengeMethod) {
  }

  public record OAuth2User(
      String providerId,
      String email,
      String name,
      String username,
      String avatarURL,
      Map<String, Object> raw) {
  }

  private record AppleRedirectName(String name, long expiresAtMillis) {
  }

  record DownloadedFile(String filename, String contentType, byte[] bytes) {
  }
}
