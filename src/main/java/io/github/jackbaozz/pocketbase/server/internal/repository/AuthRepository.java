package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jackbaozz.pocketbase.server.internal.ApiErrors;
import io.github.jackbaozz.pocketbase.server.internal.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.AuthMailSupport;
import io.github.jackbaozz.pocketbase.server.internal.AuthOriginContext;
import io.github.jackbaozz.pocketbase.server.internal.AuthProcessor;
import io.github.jackbaozz.pocketbase.server.internal.IdGenerator;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;
import io.github.jackbaozz.pocketbase.server.internal.OAuth2FieldMappingSupport;
import io.github.jackbaozz.pocketbase.server.internal.OAuth2ProviderManager;
import io.github.jackbaozz.pocketbase.server.internal.OAuth2Support;
import io.github.jackbaozz.pocketbase.server.internal.PasswordHasher;
import io.github.jackbaozz.pocketbase.server.internal.RecordFieldResolverSupport;
import io.github.jackbaozz.pocketbase.server.internal.RecordProcessor;
import io.github.jackbaozz.pocketbase.server.internal.RequestPrincipal;
import io.github.jackbaozz.pocketbase.server.internal.RuleRequestContext;
import io.github.jackbaozz.pocketbase.server.internal.SecuritySupport;
import io.github.jackbaozz.pocketbase.server.internal.SystemCollections;
import io.github.jackbaozz.pocketbase.server.internal.TokenService;
import io.github.jackbaozz.pocketbase.server.internal.UploadedFile;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public class AuthRepository extends BaseRepository {

  private static final String SUPERUSERS = SystemCollections.SUPERUSERS;

  private final TokenService tokenService;
  private final RecordProcessor.StoreContext storeContext;
  private final RecordRepository recordRepository;
  private final SettingsRepository settingsRepository;
  private final Path dataDir;

  public AuthRepository(
      JooqDatabase database,
      ObjectMapper mapper,
      TokenService tokenService,
      RecordProcessor.StoreContext storeContext,
      RecordRepository recordRepository,
      SettingsRepository settingsRepository,
      Path dataDir) {
    super(database, mapper);
    this.tokenService = tokenService;
    this.storeContext = storeContext;
    this.recordRepository = recordRepository;
    this.settingsRepository = settingsRepository;
    this.dataDir = dataDir;
  }

  public Map<String, Object> bootstrapSuperuser(JsonNode body) {
    String email = body.get("email").asText();
    String password = body.get("password").asText();

    if (database.dsl().fetchCount(qt("_superusers")) > 0) {
      throw new ApiException(403, "Superuser already exists.");
    }

    String id = IdGenerator.id();
    String passHash = PasswordHasher.hash(password);
    String tokenKey = IdGenerator.secret();
    String now = Instant.now().toString();

    database
        .dsl()
        .insertInto(qt("_superusers"))
        .columns(
            qfs("id"),
            qfs("email"),
            qfs("passwordHash"),
            qfs("tokenKey"),
            qfb("emailVisibility"),
            qfb("verified"),
            qfs("created"),
            qfs("updated"))
        .values(id, email, passHash, tokenKey, false, true, now, now)
        .execute();

    Map<String, Object> record = new LinkedHashMap<>();
    record.put("id", id);
    record.put("email", email);
    record.put("emailVisibility", false);
    record.put("verified", true);
    record.put("created", now);
    record.put("updated", now);
    return Map.of("record", record);
  }

  public Map<String, Object> authWithPassword(
      String collection, JsonNode body, Map<String, String> query) {
    return authWithPassword(collection, body, query, AuthOriginContext.empty());
  }

  public Map<String, Object> authWithPassword(
      String collection, JsonNode body, Map<String, String> query, AuthOriginContext origin) {
    return authWithPassword(collection, body, RuleRequestContext.of(query, Map.of()), origin);
  }

  public Map<String, Object> authWithPassword(
      String collection, JsonNode body, RuleRequestContext request, AuthOriginContext origin) {
    request = request.withContext(RuleRequestContext.PASSWORD);
    Map<String, String> query = request.query();
    String identity = body != null && body.has("identity") ? body.get("identity").asText() : "";
    String password = body != null && body.has("password") ? body.get("password").asText() : "";
    if (identity.isBlank()) {
      throw new ApiException(400, "Failed to authenticate.", ApiErrors.requiredField("identity"));
    }
    if (password.isBlank()) {
      throw new ApiException(400, "Failed to authenticate.", ApiErrors.requiredField("password"));
    }

    String remoteIp = origin != null ? origin.ip() : null;
    io.github.jackbaozz.pocketbase.server.internal.AuthFailedAttemptTracker.checkLock(identity, remoteIp);

    if (SystemCollections.isSuperuserIdentifier(collection)) {
      CollectionSchema superuserSchema = storeContext.getCollection(SUPERUSERS);
      var record =
          database
              .dsl()
              .select(
                  qfs("id"),
                  qfs("email"),
                  qfs("passwordHash"),
                  qfs("tokenKey"),
                  qfb("emailVisibility"),
                  qfb("verified"),
                  qfs("created"),
                  qfs("updated"))
              .from(qt("_superusers"))
              .where(qfs("email").eq(identity))
              .fetchOne();

      String hash = record == null ? null : record.getValue(qfs("passwordHash"));
      if (PasswordHasher.verifyOrDummy(password, hash)) {
        if (record != null) {
          String tokenKey = record.getValue(qfs("tokenKey"));
          String id = record.getValue(qfs("id"));
          String email = record.getValue(qfs("email"));
          Boolean emailVisibility = record.getValue(qfb("emailVisibility"));
          Boolean verified = record.getValue(qfb("verified"));
          String created = record.getValue(qfs("created"));
          String updated = record.getValue(qfs("updated"));

          Map<String, Object> claims =
              Map.of(
                  "sub", id,
                  "email", email,
                  "type", "auth",
                  "collectionId", SystemCollections.SUPERUSERS_ID,
                  "collectionName", SUPERUSERS,
                  "tokenType", "auth",
                  "tokenKey", tokenKey);
          if (superuserSchema != null && "auth".equals(superuserSchema.type)) {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("id", id);
            rec.put("email", email);
            rec.put("collectionId", SystemCollections.SUPERUSERS_ID);
            rec.put("collectionName", SUPERUSERS);
            rec.put("emailVisibility", Boolean.TRUE.equals(emailVisibility));
            rec.put("verified", !Boolean.FALSE.equals(verified));
            rec.put("created", created);
            rec.put("updated", updated);
            rec.put("tokenKey", tokenKey);
            Map<String, Object> res = handleAuthWithMfa(
                superuserSchema,
                rec,
                request,
                mfaId(body, query),
                "password",
                Map.of(),
                origin,
                body,
                null);
            io.github.jackbaozz.pocketbase.server.internal.AuthFailedAttemptTracker.recordSuccess(identity, remoteIp);
            return res;
          }
          Duration ttl =
              tokenDuration(
                  superuserSchema == null ? null : superuserSchema.authToken,
                  CollectionSchema.DEFAULT_AUTH_TOKEN_DURATION);
          String token =
              tokenService.create(
                  claims,
                  ttl,
                  tokenSigningSecret(
                      superuserSchema == null ? null : superuserSchema.authToken, tokenKey));
          Map<String, Object> rec = new LinkedHashMap<>();
          rec.put("id", id);
          rec.put("email", email);
          rec.put("emailVisibility", Boolean.TRUE.equals(emailVisibility));
          rec.put("verified", !Boolean.FALSE.equals(verified));
          rec.put("collectionId", SystemCollections.SUPERUSERS_ID);
          rec.put("collectionName", SUPERUSERS);
          rec.put("created", created);
          rec.put("updated", updated);
          io.github.jackbaozz.pocketbase.server.internal.AuthFailedAttemptTracker.recordSuccess(identity, remoteIp);
          return Map.of("token", token, "record", rec);
        }
      }
      io.github.jackbaozz.pocketbase.server.internal.AuthFailedAttemptTracker.recordFailureAndThrow(
          identity, remoteIp);
    }

    CollectionSchema colSchema = storeContext.getCollection(collection);
    if (colSchema == null || !"auth".equals(colSchema.type)) {
      throw new ApiException(400, "The collection is not an auth collection.");
    }
    if (colSchema.passwordAuth == null || !colSchema.passwordAuth.enabled) {
      throw new ApiException(
          403, "The collection is not configured to allow password authentication.");
    }

    String identityField =
        body != null && body.hasNonNull("identityField") ? body.get("identityField").asText() : "";
    List<String> identityFields = passwordIdentityFields(colSchema, identityField);
    Map<String, Object> record = findAuthRecordByIdentity(colSchema, identityFields, identity);
    Object passwordHash = record == null ? null : record.get("password");
    if (passwordHash == null && record != null) {
      passwordHash = record.get("passwordHash");
    }
    if (PasswordHasher.verifyOrDummy(
        password, passwordHash == null ? null : String.valueOf(passwordHash))) {
      if (record != null) {
        Map<String, Object> res = handleAuthWithMfa(
            colSchema,
            record,
            request,
            mfaId(body, query),
            "password",
            Map.of(),
            origin,
            body,
            null);
        io.github.jackbaozz.pocketbase.server.internal.AuthFailedAttemptTracker.recordSuccess(identity, remoteIp);
        return res;
      }
    }
    io.github.jackbaozz.pocketbase.server.internal.AuthFailedAttemptTracker.recordFailureAndThrow(
        identity, remoteIp);
    // Unreachable: recordFailureAndThrow always throws. Satisfies the compiler's
    // flow analysis since it cannot see across the helper method boundary.
    throw invalidAuthCredentials();
  }

  private ApiException invalidAuthCredentials() {
    return new ApiException(400, "Failed to authenticate.");
  }

  public Map<String, Object> authWithOAuth2(
      String collection, JsonNode body, Map<String, String> query, RequestPrincipal principal) {
    return authWithOAuth2(collection, body, query, principal, AuthOriginContext.empty());
  }

  public Map<String, Object> authWithOAuth2(
      String collection,
      JsonNode body,
      Map<String, String> query,
      RequestPrincipal principal,
      AuthOriginContext origin) {
    return authWithOAuth2(
        collection, body, RuleRequestContext.of(query, Map.of()), principal, origin);
  }

  public Map<String, Object> authWithOAuth2(
      String collection,
      JsonNode body,
      RuleRequestContext request,
      RequestPrincipal principal,
      AuthOriginContext origin) {
    request = request.withContext(RuleRequestContext.OAUTH2);
    Map<String, String> query = request.query();
    CollectionSchema colSchema = storeContext.getCollection(collection);
    if (colSchema == null || !"auth".equals(colSchema.type)) {
      throw new ApiException(400, "The collection is not an auth collection.");
    }
    if (colSchema.oauth2 == null || !colSchema.oauth2.enabled) {
      throw new ApiException(
          403, "The collection is not configured to allow OAuth2 authentication.");
    }

    String providerName = requiredText(body, "provider");
    String code = requiredText(body, "code");
    String redirectURL = requiredText(body, "redirectURL");
    String codeVerifier = optionalText(body, "codeVerifier");
    CollectionSchema.OAuth2ProviderConfig provider =
        colSchema.oauth2.providers.stream()
            .filter(item -> providerName.equalsIgnoreCase(item.name))
            .findFirst()
            .orElseThrow(
                () -> new ApiException(
                    400,
                    "Failed to authenticate.",
                    ApiErrors.invalidField(
                        "provider",
                        "Provider with name "
                            + providerName
                            + " is missing or is not enabled.")));

    OAuth2Support.OAuth2User oauthUser =
        OAuth2Support.authenticate(mapper, provider, code, redirectURL, codeVerifier);
    Map<String, Object> record =
        findOAuth2LinkedRecord(colSchema, provider.name, oauthUser.providerId());
    boolean isNew = false;

    if (record == null && principal != null && sameCollection(principal, colSchema)) {
      record = recordRepository.getRawRecord(colSchema, principal.id());
    }
    if (record == null && !oauthUser.email().isBlank()) {
      record = recordRepository.findRecordByEmail(colSchema.name, oauthUser.email());
    }
    if (record == null) {
      ObjectNode payload = mapper.createObjectNode();
      JsonNode createData = body == null ? null : body.get("createData");
      if (createData != null && createData.isObject()) {
        payload.setAll((ObjectNode) createData);
      }
      if (!payload.hasNonNull("email") && !oauthUser.email().isBlank()) {
        payload.put("email", oauthUser.email());
      }
      if (!payload.hasNonNull("password")) {
        String generatedPassword = IdGenerator.randomPassword();
        payload.put("password", generatedPassword);
        payload.put("passwordConfirm", generatedPassword);
      }
      Map<String, List<UploadedFile>> mappedFiles =
          OAuth2FieldMappingSupport.apply(
              colSchema,
              payload,
              oauthUser,
              (field, value) -> recordRepository.getRawRecordByField(colSchema, field, value) == null);
      Map<String, Object> created =
          recordRepository.createRecord(
              colSchema.name,
              payload,
              mappedFiles,
              RuleRequestContext.of(Map.of(), request.headers(), RuleRequestContext.OAUTH2),
              principal);
      record = recordRepository.getRawRecord(colSchema, String.valueOf(created.get("id")));
      if (!oauthUser.email().isBlank()
          && oauthUser.email().equalsIgnoreCase(String.valueOf(record.getOrDefault("email", "")))
          && !truthy(record.get("verified"))) {
        Map<String, Object> updates = Map.of("verified", true, "updated", Instant.now().toString());
        recordRepository.updateFields(colSchema.name, String.valueOf(record.get("id")), updates);
        record.putAll(updates);
      }
      isNew = true;
    } else {
      Map<String, Object> updates = new LinkedHashMap<>();
      if (!oauthUser.email().isBlank()
          && oauthUser.email().equalsIgnoreCase(String.valueOf(record.getOrDefault("email", "")))
          && !truthy(record.get("verified"))) {
        updates.put("verified", true);
      }
      if (!updates.isEmpty()) {
        updates.put("updated", Instant.now().toString());
        recordRepository.updateFields(colSchema.name, String.valueOf(record.get("id")), updates);
        record.putAll(updates);
      }
    }

    ensureTokenKey(colSchema, record);
    upsertExternalAuth(colSchema, record, provider.name, oauthUser.providerId());
    Map<String, Object> meta = new LinkedHashMap<>(oauthUser.raw());
    meta.put("isNew", isNew);
    return handleAuthWithMfa(
        colSchema, record, request, mfaId(body, query), "oauth2", meta, origin, body, principal);
  }

  public Map<String, Object> authRefresh(
      String collection, RequestPrincipal principal, Map<String, String> query) {
    if (principal == null
        || !Objects.equals(principal.collectionName(), collection)
            && !Objects.equals(
                SystemCollections.canonicalIdentifier(principal.collectionId()),
                SystemCollections.canonicalIdentifier(collection))) {
      throw new ApiException(401, "Missing or invalid auth token.");
    }
    CollectionSchema requestedCollection = storeContext.getCollection(collection);
    if (requestedCollection == null) {
      throw new ApiException(401, "Missing or invalid auth token.");
    }
    if (!"auth".equals(principal.claims().getOrDefault("tokenType", "auth"))) {
      throw new ApiException(401, "Missing or invalid auth token.");
    }

    if (SUPERUSERS.equals(requestedCollection.name)) {
      CollectionSchema superuserSchema = storeContext.getCollection(SUPERUSERS);
      var record =
          database
              .dsl()
              .select(
                  qfs("id"),
                  qfs("email"),
                  qfs("tokenKey"),
                  qfb("emailVisibility"),
                  qfb("verified"),
                  qfs("created"),
                  qfs("updated"))
              .from(qt("_superusers"))
              .where(qfs("id").eq(principal.id()))
              .fetchOne();
      if (record == null
          || !SecuritySupport.constantTimeEquals(
              String.valueOf(record.get(qfs("tokenKey"))),
              String.valueOf(principal.claims().get("tokenKey")))) {
        throw new ApiException(401, "Auth record no longer exists.");
      }
      Map<String, Object> claims =
          Map.of(
              "sub",
              record.get(qfs("id")),
              "email",
              record.get(qfs("email")),
              "type",
              "auth",
              "collectionId",
              SystemCollections.SUPERUSERS_ID,
              "collectionName",
              SUPERUSERS,
              "tokenType",
              "auth",
              "tokenKey",
              record.get(qfs("tokenKey")));
      Duration ttl =
          tokenDuration(
              superuserSchema == null ? null : superuserSchema.authToken,
              CollectionSchema.DEFAULT_AUTH_TOKEN_DURATION);
      String token =
          tokenService.create(
              claims,
              ttl,
              tokenSigningSecret(
                  superuserSchema == null ? null : superuserSchema.authToken,
                  record.get(qfs("tokenKey"))));
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("token", token);
      Map<String, Object> responseRecord = new LinkedHashMap<>();
      responseRecord.put("id", record.get(qfs("id")));
      responseRecord.put("email", record.get(qfs("email")));
      responseRecord.put(
          "emailVisibility", Boolean.TRUE.equals(record.get(qfb("emailVisibility"))));
      responseRecord.put("verified", !Boolean.FALSE.equals(record.get(qfb("verified"))));
      responseRecord.put("collectionId", SystemCollections.SUPERUSERS_ID);
      responseRecord.put("collectionName", SUPERUSERS);
      responseRecord.put("created", record.get(qfs("created")));
      responseRecord.put("updated", record.get(qfs("updated")));
      response.put("record", responseRecord);
      return RecordProcessor.selectFields(response, query == null ? null : query.get("fields"));
    }

    CollectionSchema colSchema = requestedCollection;
    if (colSchema == null || !"auth".equals(colSchema.type)) {
      throw new ApiException(401, "Auth collection not found.");
    }
    Map<String, Object> record = recordRepository.getRawRecord(colSchema, principal.id());
    if (record == null) {
      throw new ApiException(401, "Auth record no longer exists.");
    }
    if (!SecuritySupport.constantTimeEquals(
        String.valueOf(record.getOrDefault("tokenKey", "")),
        String.valueOf(principal.claims().getOrDefault("tokenKey", "")))) {
      throw new ApiException(401, "Auth record token is no longer valid.");
    }
    return authResponse(
        colSchema,
        record,
        query,
        tokenDuration(colSchema.authToken, CollectionSchema.DEFAULT_AUTH_TOKEN_DURATION),
        "auth");
  }

  public Map<String, Object> authMethods(String collection) {
    CollectionSchema colSchema = storeContext.getCollection(collection);
    if (colSchema == null) {
      throw new ApiException(404, "Collection not found.");
    }
    if (!"auth".equals(colSchema.type)) {
      throw new ApiException(404, "The requested resource wasn't found.");
    }

    boolean passwordEnabled = colSchema.passwordAuth != null && colSchema.passwordAuth.enabled;
    List<String> identityFields =
        passwordEnabled && colSchema.passwordAuth.identityFields != null
            ? new ArrayList<>(colSchema.passwordAuth.identityFields)
            : new ArrayList<>();
    List<Map<String, Object>> providers =
        colSchema.oauth2 != null && colSchema.oauth2.enabled
            ? colSchema.oauth2.providers.stream()
                .map(
                    config -> {
                      OAuth2ProviderManager.ProviderMetadata metadata =
                          OAuth2ProviderManager.providerMetadata(config.name);
                      if (metadata == null) {
                        return null;
                      }
                      OAuth2Support.AuthMethodProviderInfo info =
                          OAuth2Support.authMethodInfo(
                              config, metadata.displayName(), metadata.logo());
                      return orderedMap(
                          "name", info.name(),
                          "displayName", info.displayName(),
                          "logo", info.logo(),
                          "state", info.state(),
                          "authURL", info.authURL(),
                          "authUrl", info.authUrl(),
                          "codeVerifier", info.codeVerifier(),
                          "codeChallenge", info.codeChallenge(),
                          "codeChallengeMethod", info.codeChallengeMethod());
                    })
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new))
            : new ArrayList<>();

    List<Map<String, Object>> legacyProviders =
        providers.stream()
            .map(
                provider -> orderedMap(
                    "name", provider.get("name"),
                    "displayName", provider.get("displayName"),
                    "logo", "",
                    "state", provider.get("state"),
                    "authURL", provider.get("authURL"),
                    "authUrl", provider.get("authUrl"),
                    "codeVerifier", provider.get("codeVerifier"),
                    "codeChallenge", provider.get("codeChallenge"),
                    "codeChallengeMethod", provider.get("codeChallengeMethod")))
            .collect(Collectors.toCollection(ArrayList::new));

    boolean oauthEnabled = colSchema.oauth2 != null && colSchema.oauth2.enabled;
    return Map.of(
        "password", Map.of("enabled", passwordEnabled, "identityFields", identityFields),
        "emailPassword", passwordEnabled && identityFields.contains("email"),
        "usernamePassword", passwordEnabled && identityFields.contains("username"),
        "otp",
        Map.of(
            "enabled",
            colSchema.otp != null && colSchema.otp.enabled,
            "duration",
            colSchema.otp != null && colSchema.otp.enabled ? colSchema.otp.duration : 0),
        "mfa",
        Map.of(
            "enabled",
            colSchema.mfa != null && colSchema.mfa.enabled,
            "duration",
            colSchema.mfa != null && colSchema.mfa.enabled ? colSchema.mfa.duration : 0),
        "oauth2",
        Map.of(
            "enabled", oauthEnabled,
            "providers", providers),
        "authProviders", oauthEnabled ? legacyProviders : List.of());
  }

  public void requestPasswordReset(String collection, JsonNode body) {
    String token = AuthProcessor.requestPasswordReset(storeContext, tokenService, collection, body);
    CollectionSchema colSchema = storeContext.getCollection(collection);
    appendAuthRequestFromToken(
        token,
        "passwordReset",
        tokenDuration(
            colSchema == null ? null : colSchema.passwordResetToken,
            CollectionSchema.DEFAULT_PASSWORD_RESET_TOKEN_DURATION));
  }

  public void confirmPasswordReset(String collection, JsonNode body) {
    String token = actionToken(body);
    database.transactional(
        () -> {
          consumeAuthRequestToken(collection, token, "passwordReset");
          AuthProcessor.confirmPasswordReset(storeContext, tokenService, collection, body);
          return null;
        });
  }

  public void requestVerification(String collection, JsonNode body) {
    String token = AuthProcessor.requestVerification(storeContext, tokenService, collection, body);
    CollectionSchema colSchema = storeContext.getCollection(collection);
    appendAuthRequestFromToken(
        token,
        "verification",
        tokenDuration(
            colSchema == null ? null : colSchema.verificationToken,
            CollectionSchema.DEFAULT_VERIFICATION_TOKEN_DURATION));
  }

  public void confirmVerification(String collection, JsonNode body) {
    String token = actionToken(body);
    database.transactional(
        () -> {
          consumeAuthRequestToken(collection, token, "verification");
          AuthProcessor.confirmVerification(storeContext, tokenService, collection, body);
          return null;
        });
  }

  public void requestEmailChange(String collection, JsonNode body, RequestPrincipal principal) {
    String token =
        AuthProcessor.requestEmailChange(storeContext, tokenService, collection, body, principal);
    CollectionSchema colSchema = storeContext.getCollection(collection);
    appendAuthRequestFromToken(
        token,
        "emailChange",
        tokenDuration(
            colSchema == null ? null : colSchema.emailChangeToken,
            CollectionSchema.DEFAULT_EMAIL_CHANGE_TOKEN_DURATION));
  }

  public void confirmEmailChange(String collection, JsonNode body) {
    String token = actionToken(body);
    database.transactional(
        () -> {
          consumeAuthRequestToken(collection, token, "emailChange");
          AuthProcessor.confirmEmailChange(storeContext, tokenService, collection, body);
          return null;
        });
  }

  public Map<String, Object> impersonate(
      String collection, String id, JsonNode body, Map<String, String> query) {
    CollectionSchema colSchema = storeContext.getCollection(collection);
    if (colSchema == null || !"auth".equals(colSchema.type)) {
      throw new ApiException(400, "The collection is not an auth collection.");
    }
    Map<String, Object> record = recordRepository.getRawRecord(colSchema, id);
    if (record == null) {
      throw new ApiException(404, "Record not found.");
    }
    long defaultSeconds =
        tokenDurationSeconds(colSchema.authToken, CollectionSchema.DEFAULT_AUTH_TOKEN_DURATION);
    long seconds = defaultSeconds;
    if (body != null && body.has("duration")) {
      seconds = Math.max(60L, Math.min(604_800L, body.get("duration").asLong(defaultSeconds)));
    }
    return authResponse(colSchema, record, query, Duration.ofSeconds(seconds), "impersonate");
  }

  public Map<String, Object> requestOtp(String collection, JsonNode body) {
    CollectionSchema colSchema = storeContext.getCollection(collection);
    if (colSchema == null || !"auth".equals(colSchema.type)) {
      throw new ApiException(400, "The collection is not an auth collection.");
    }
    if (colSchema.otp == null || !colSchema.otp.enabled) {
      throw new ApiException(403, "The collection is not configured to allow OTP authentication.");
    }

    String email =
        body != null && body.has("email") ? body.get("email").asText().trim().toLowerCase() : "";
    if (email.isEmpty()) {
      throw new ApiException(400, "Failed to request OTP.", ApiErrors.requiredField("email"));
    }

    Map<String, Object> record = storeContext.findRecordByEmail(colSchema, email);
    if (record == null) {
      // Return a dummy otpId to avoid email enumeration
      return Map.of("otpId", IdGenerator.id());
    }

    // Generate OTP code
    int length = colSchema.otp.length;
    String code = IdGenerator.digits(length);
    String otpId = IdGenerator.id();
    String now = Instant.now().toString();

    // Persist OTP record in _otps table
    try {
      database
          .dsl()
          .insertInto(qt("_otps"))
          .columns(
              qfs("id"),
              qfs("created"),
              qfs("updated"),
              qfs("recordRef"),
              qfs("collectionRef"),
              qfs("password"),
              qfs("sentTo"),
              qfi("failedAttempts"))
          .values(
              otpId,
              now,
              now,
              String.valueOf(record.get("id")),
              colSchema.id,
              PasswordHasher.hash(code),
              email,
              0)
          .execute();
    } catch (Exception e) {
      throw new ApiException(400, "Failed to request OTP.");
    }

    Map<String, Object> request = new LinkedHashMap<>();
    request.put("id", IdGenerator.id());
    request.put("type", "otp");
    request.put("collectionId", colSchema.id);
    request.put("collectionName", colSchema.name);
    request.put("recordId", record.get("id"));
    request.put("email", email);
    request.put("otpId", otpId);
    request.put("password", code);
    request.put("created", now);
    request.put("expires", Instant.now().plusSeconds(colSchema.otp.duration).toString());
    appendAuthRequest(request);
    return Map.of("otpId", otpId);
  }

  public Map<String, Object> authWithOtp(
      String collection, JsonNode body, Map<String, String> query) {
    return authWithOtp(collection, body, query, AuthOriginContext.empty());
  }

  public Map<String, Object> authWithOtp(
      String collection, JsonNode body, Map<String, String> query, AuthOriginContext origin) {
    return authWithOtp(collection, body, RuleRequestContext.of(query, Map.of()), origin);
  }

  public Map<String, Object> authWithOtp(
      String collection, JsonNode body, RuleRequestContext request, AuthOriginContext origin) {
    request = request.withContext(RuleRequestContext.OTP);
    Map<String, String> query = request.query();
    CollectionSchema colSchema = storeContext.getCollection(collection);
    if (colSchema == null || !"auth".equals(colSchema.type)) {
      throw new ApiException(400, "The collection is not an auth collection.");
    }
    if (colSchema.otp == null || !colSchema.otp.enabled) {
      throw new ApiException(403, "The collection is not configured to allow OTP authentication.");
    }

    String otpId = body != null && body.has("otpId") ? body.get("otpId").asText() : "";
    String password = body != null && body.has("password") ? body.get("password").asText() : "";

    if (otpId.isEmpty()) {
      throw new ApiException(400, "Failed to authenticate.", ApiErrors.requiredField("otpId"));
    }
    if (password.isEmpty()) {
      throw new ApiException(400, "Failed to authenticate.", ApiErrors.requiredField("password"));
    }
    if (password.length() > 71) {
      throw new ApiException(
          400,
          "Invalid OTP password.",
          ApiErrors.invalidField("password", "Invalid OTP password."));
    }

    // Look up OTP record
    var otpRecord = database.dsl().selectFrom(qt("_otps")).where(qfs("id").eq(otpId)).fetchOne();

    if (otpRecord == null) {
      throw invalidOtp();
    }

    String recordId = otpRecord.getValue(qfs("recordRef"), String.class);
    String collectionId = otpRecord.getValue(qfs("collectionRef"), String.class);
    String passwordHash = otpRecord.getValue(qfs("password"), String.class);
    String created = otpRecord.getValue(qfs("created"), String.class);
    int failedAttempts =
        otpRecord.getValue(qfi("failedAttempts"), Integer.class) == null
            ? 0
            : otpRecord.getValue(qfi("failedAttempts"), Integer.class);

    if (!colSchema.id.equals(collectionId) && !colSchema.name.equals(collection)) {
      throw invalidOtp();
    }
    if (otpExpired(created, colSchema)) {
      database.dsl().deleteFrom(qt("_otps")).where(qfs("id").eq(otpId)).execute();
      throw invalidOtp();
    }
    Map<String, Object> record = storeContext.getRecord(colSchema, recordId);
    if (record == null) {
      database.dsl().deleteFrom(qt("_otps")).where(qfs("id").eq(otpId)).execute();
      throw invalidOtp();
    }
    if (failedAttempts >= 5) {
      throw tooManyOtpAttempts();
    }

    // Verify OTP password
    if (!PasswordHasher.verifyOrDummy(password, passwordHash)) {
      database
          .dsl()
          .update(qt("_otps"))
          .set(qfi("failedAttempts"), failedAttempts + 1)
          .where(qfs("id").eq(otpId))
          .execute();
      throw invalidOtp();
    }

    int consumed = database.dsl().deleteFrom(qt("_otps")).where(qfs("id").eq(otpId)).execute();
    if (consumed != 1) {
      throw invalidOtp();
    }

    String sentTo = otpRecord.getValue(qfs("sentTo"), String.class);
    if (!SUPERUSERS.equals(colSchema.name)
        && sentTo != null
        && sentTo.equalsIgnoreCase(String.valueOf(record.getOrDefault("email", "")))) {
      Map<String, Object> updates = new LinkedHashMap<>();
      updates.put("verified", true);
      if (colSchema.mfa == null || !colSchema.mfa.enabled) {
        updates.put(
            "password", PasswordHasher.hash(IdGenerator.prefixed("otp_") + IdGenerator.id()));
        updates.put("tokenKey", IdGenerator.secret());
      }
      recordRepository.updateFields(colSchema.name, recordId, updates);
      record.putAll(updates);
    }

    return handleAuthWithMfa(
        colSchema, record, request, mfaId(body, query), "otp", Map.of(), origin, body, null);
  }

  private boolean otpExpired(String created, CollectionSchema collection) {
    long durationSeconds =
        collection != null && collection.otp != null && collection.otp.duration > 0
            ? collection.otp.duration
            : 180L;
    try {
      return created == null
          || created.isBlank()
          || !Instant.parse(created).plusSeconds(durationSeconds).isAfter(Instant.now());
    } catch (RuntimeException e) {
      return true;
    }
  }

  private ApiException invalidOtp() {
    return new ApiException(
        400, "Invalid or expired OTP.", ApiErrors.invalidField("otpId", "Invalid or expired OTP."));
  }

  private ApiException tooManyOtpAttempts() {
    return new ApiException(
        429,
        "Too many failed OTP attempts.",
        ApiErrors.invalidField("otpId", "Too many failed OTP attempts."));
  }

  public Optional<Map<String, Object>> verifyToken(String token) {
    return tokenService
        .verify(token, this::bearerTokenSigningSecret)
        .filter(
            claims -> {
              Object tokenType = claims.get("tokenType");
              return tokenType == null
                  || "auth".equals(tokenType)
                  || "impersonate".equals(tokenType);
            });
  }

  public void pruneExpiredMfas() {
    List<String> expiredIds =
        database
            .dsl()
            .select(qfs("id"), qfs("created"), qfs("collectionRef"))
            .from(qt("_mfas"))
            .fetch(
                record -> {
                  String collectionId = record.get(qfs("collectionRef"), String.class);
                  CollectionSchema collection =
                      collectionId == null ? null : storeContext.getCollection(collectionId);
                  if (collection == null || collection.mfa == null) {
                    return record.get(qfs("id"), String.class);
                  }
                  return mfaExpired(collection, record.get(qfs("created"), String.class))
                      ? record.get(qfs("id"), String.class)
                      : null;
                })
            .stream()
            .filter(Objects::nonNull)
            .toList();
    if (!expiredIds.isEmpty()) {
      database.dsl().deleteFrom(qt("_mfas")).where(qfs("id").in(expiredIds)).execute();
    }
  }

  public void pruneExpiredOtps() {
    List<String> expiredIds =
        database
            .dsl()
            .select(qfs("id"), qfs("created"), qfs("collectionRef"))
            .from(qt("_otps"))
            .fetch(
                record -> {
                  String collectionId = record.get(qfs("collectionRef"), String.class);
                  CollectionSchema collection =
                      collectionId == null ? null : storeContext.getCollection(collectionId);
                  if (collection == null || collection.otp == null) {
                    return record.get(qfs("id"), String.class);
                  }
                  long durationSeconds =
                      collection.otp.duration > 0 ? collection.otp.duration : 300;
                  try {
                    Instant cutoff = Instant.now().minusSeconds(durationSeconds);
                    return Instant.parse(record.get(qfs("created"), String.class)).isBefore(cutoff)
                        ? record.get(qfs("id"), String.class)
                        : null;
                  } catch (Exception e) {
                    return record.get(qfs("id"), String.class);
                  }
                })
            .stream()
            .filter(Objects::nonNull)
            .toList();
    if (!expiredIds.isEmpty()) {
      database.dsl().deleteFrom(qt("_otps")).where(qfs("id").in(expiredIds)).execute();
    }
  }

  private List<String> passwordIdentityFields(CollectionSchema colSchema, String requestedField) {
    List<String> configured =
        colSchema.passwordAuth == null || colSchema.passwordAuth.identityFields == null
            ? List.of("email")
            : colSchema.passwordAuth.identityFields;
    if (requestedField != null && !requestedField.isBlank()) {
      if (!configured.contains(requestedField)) {
        throw invalidAuthCredentials();
      }
      return List.of(requestedField);
    }
    return configured.isEmpty() ? List.of("email") : configured;
  }

  private Map<String, Object> findAuthRecordByIdentity(
      CollectionSchema colSchema, List<String> identityFields, String identity) {
    if (identity == null || identity.isBlank()) {
      return null;
    }
    for (String field : new ArrayList<>(identityFields)) {
      String lookup = "email".equals(field) ? identity.trim().toLowerCase() : identity;
      try {
        Map<String, Object> record = recordRepository.getRawRecordByField(colSchema, field, lookup);
        if (record != null) {
          return record;
        }
      } catch (Exception ignored) {
      }
    }
    return null;
  }

  private Map<String, Object> authResponse(
      CollectionSchema colSchema,
      Map<String, Object> record,
      Map<String, String> query,
      Duration ttl,
      String tokenType) {
    return authResponse(colSchema, record, query, ttl, tokenType, null);
  }

  private Map<String, Object> authResponse(
      CollectionSchema colSchema,
      Map<String, Object> record,
      Map<String, String> query,
      Duration ttl,
      String tokenType,
      Map<String, Object> meta) {
    return authResponse(
        colSchema, record, RuleRequestContext.of(query, Map.of()), ttl, tokenType, meta);
  }

  private Map<String, Object> authResponse(
      CollectionSchema colSchema,
      Map<String, Object> record,
      RuleRequestContext request,
      Duration ttl,
      String tokenType,
      Map<String, Object> meta) {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("sub", record.get("id"));
    claims.put("email", record.getOrDefault("email", ""));
    claims.put("type", "auth");
    claims.put("collectionId", colSchema.id);
    claims.put("collectionName", colSchema.name);
    claims.put("tokenType", tokenType);
    claims.put("tokenKey", record.getOrDefault("tokenKey", ""));

    String token =
        tokenService.create(
            claims,
            ttl == null
                ? tokenDuration(colSchema.authToken, CollectionSchema.DEFAULT_AUTH_TOKEN_DURATION)
                : ttl,
            tokenSigningSecret(colSchema.authToken, record.get("tokenKey")));
    RuleRequestContext safeRequest = request == null ? RuleRequestContext.empty() : request;
    Map<String, String> safeQuery = safeRequest.query();
    Map<String, String> recordQuery = new LinkedHashMap<>(safeQuery);
    recordQuery.remove("fields");
    RuleRequestContext recordRequest = RuleRequestContext.of(recordQuery, safeRequest.headers());
    RequestPrincipal authPrincipal = RequestPrincipal.fromClaims(claims);
    Map<String, Object> normalizedRecord =
        recordRepository.normalizeStoredRecord(colSchema, record);
    Map<String, Object> processed =
        RecordProcessor.process(
            storeContext, colSchema, normalizedRecord, false, recordRequest, authPrincipal);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("token", token);
    response.put("record", processed);
    if (meta != null && !meta.isEmpty()) {
      response.put("meta", meta);
    }
    return RecordProcessor.selectFields(response, safeQuery.get("fields"));
  }

  private Map<String, Object> handleAuthWithMfa(
      CollectionSchema collection,
      Map<String, Object> record,
      RuleRequestContext request,
      String mfaIdParam,
      String method,
      Map<String, Object> meta,
      AuthOriginContext origin,
      JsonNode body,
      RequestPrincipal requestPrincipal) {
    if (collection.mfa == null || !collection.mfa.enabled) {
      return authenticatedResponse(
          collection, record, request, meta, origin, body, requestPrincipal);
    }
    if (mfaIdParam != null && !mfaIdParam.isBlank()) {
      var mfa =
          database
              .dsl()
              .selectFrom(qt("_mfas"))
              .where(qfs("id").eq(mfaIdParam))
              .and(qfs("recordRef").eq(String.valueOf(record.get("id"))))
              .and(qfs("collectionRef").eq(collection.id))
              .fetchOne();
      if (mfa == null || mfaExpired(collection, mfa.get(qfs("created"), String.class))) {
        if (mfa != null) {
          database.dsl().deleteFrom(qt("_mfas")).where(qfs("id").eq(mfaIdParam)).execute();
        }
        throw invalidMfaId("Missing or invalid MFA ID.");
      }
      if (Objects.equals(mfa.get(qfs("method"), String.class), method)) {
        throw invalidMfaId("MFA requires a different auth method.");
      }
      database.dsl().deleteFrom(qt("_mfas")).where(qfs("id").eq(mfaIdParam)).execute();
      return authenticatedResponse(
          collection, record, request, meta, origin, body, requestPrincipal);
    }
    boolean requireMfa =
        collection.mfa.rule == null
            || collection.mfa.rule.isBlank()
            || io.github.jackbaozz.pocketbase.server.internal.RuleEvaluator.matches(
                collection.mfa.rule,
                RecordFieldResolverSupport.context(
                    storeContext, collection, record, null, request, "POST", null, true, false));
    if (!requireMfa) {
      return authenticatedResponse(
          collection, record, request, meta, origin, body, requestPrincipal);
    }
    String now = Instant.now().toString();
    String id = IdGenerator.id();
    database
        .dsl()
        .insertInto(qt("_mfas"))
        .columns(
            qfs("id"),
            qfs("created"),
            qfs("updated"),
            qfs("recordRef"),
            qfs("collectionRef"),
            qfs("method"))
        .values(id, now, now, String.valueOf(record.get("id")), collection.id, method)
        .execute();
    throw new ApiException(401, "MFA required.", Map.of("mfaId", id));
  }

  private Map<String, Object> authenticatedResponse(
      CollectionSchema collection,
      Map<String, Object> record,
      RuleRequestContext request,
      Map<String, Object> meta,
      AuthOriginContext origin,
      JsonNode body,
      RequestPrincipal requestPrincipal) {
    requireAuthRule(collection, record, body, request, requestPrincipal);
    Map<String, Object> response =
        authResponse(
            collection,
            record,
            request,
            tokenDuration(collection.authToken, CollectionSchema.DEFAULT_AUTH_TOKEN_DURATION),
            "auth",
            meta);
    recordAuthOrigin(collection, record, origin);
    return response;
  }

  private void requireAuthRule(
      CollectionSchema collection,
      Map<String, Object> record,
      JsonNode body,
      RuleRequestContext request,
      RequestPrincipal requestPrincipal) {
    Map<String, Object> requestBody =
        body == null || !body.isObject()
            ? Map.of()
            : mapper.convertValue(body, new TypeReference<Map<String, Object>>() {
            });
    if (collection.authRule == null
        || !collection.authRule.isBlank()
            && !io.github.jackbaozz.pocketbase.server.internal.RuleEvaluator.matches(
                collection.authRule,
                RecordFieldResolverSupport.context(
                    storeContext,
                    collection,
                    record,
                    requestBody,
                    request,
                    "POST",
                    requestPrincipal,
                    true,
                    false))) {
      throw new ApiException(
          403, "The request doesn't satisfy the collection requirements to authenticate.");
    }
  }

  private void recordAuthOrigin(
      CollectionSchema collection, Map<String, Object> record, AuthOriginContext origin) {
    if (collection == null
        || collection.authAlert == null
        || !collection.authAlert.enabled
        || record == null
        || origin == null
        || origin.ip().isBlank() && origin.userAgent().isBlank()) {
      return;
    }

    String recordId = String.valueOf(record.getOrDefault("id", ""));
    String fingerprint = origin.fingerprint();
    var origins =
        database
            .dsl()
            .selectFrom(qt("_authOrigins"))
            .where(qfs("collectionRef").eq(collection.id))
            .and(qfs("recordRef").eq(recordId))
            .orderBy(qfs("updated").desc())
            .fetch();
    var current =
        origins.stream()
            .filter(item -> fingerprint.equals(item.get(qfs("fingerprint"), String.class)))
            .findFirst()
            .orElse(null);
    String timestamp = Instant.now().toString();
    if (current != null) {
      database
          .dsl()
          .update(qt("_authOrigins"))
          .set(qfs("updated"), timestamp)
          .where(qfs("id").eq(current.get(qfs("id"), String.class)))
          .execute();
      return;
    }

    if (!origins.isEmpty() && !textSetting(record.get("email")).isBlank()) {
      Map<String, Object> request = new LinkedHashMap<>();
      request.put("id", IdGenerator.id());
      request.put("type", "authAlert");
      request.put("collectionId", collection.id);
      request.put("collectionName", collection.name);
      request.put("recordId", recordId);
      request.put("email", record.get("email"));
      request.put("alertInfo", origin.alertInfo());
      request.put("created", timestamp);
      request.put("expires", Instant.now().plus(Duration.ofHours(2)).toString());
      if (!AuthMailSupport.sendAsync(
          collection, record, request, settingsRepository.loadRawSettings(), null)) {
        appendAuthOutboxRequest(request);
      }
    }

    if (origins.size() >= 5) {
      List<String> staleIds =
          origins.subList(4, origins.size()).stream()
              .map(item -> item.get(qfs("id"), String.class))
              .filter(Objects::nonNull)
              .toList();
      if (!staleIds.isEmpty()) {
        database.dsl().deleteFrom(qt("_authOrigins")).where(qfs("id").in(staleIds)).execute();
      }
    }

    try {
      database
          .dsl()
          .insertInto(qt("_authOrigins"))
          .columns(
              qfs("id"),
              qfs("created"),
              qfs("updated"),
              qfs("collectionRef"),
              qfs("recordRef"),
              qfs("fingerprint"))
          .values(IdGenerator.id(), timestamp, timestamp, collection.id, recordId, fingerprint)
          .execute();
    } catch (org.jooq.exception.DataAccessException ignored) {
      database
          .dsl()
          .update(qt("_authOrigins"))
          .set(qfs("updated"), timestamp)
          .where(qfs("collectionRef").eq(collection.id))
          .and(qfs("recordRef").eq(recordId))
          .and(qfs("fingerprint").eq(fingerprint))
          .execute();
    }
  }

  private ApiException invalidMfaId(String message) {
    return new ApiException(400, message, ApiErrors.invalidField("mfaId", message));
  }

  private Map<String, Object> findOAuth2LinkedRecord(
      CollectionSchema collection, String provider, String providerId) {
    try {
      var externalAuth =
          database
              .dsl()
              .selectFrom(qt("_externalAuths"))
              .where(qfs("collectionRef").eq(collection.id))
              .and(qfs("provider").eq(provider))
              .and(qfs("providerId").eq(providerId))
              .fetchOne();
      if (externalAuth == null) {
        return null;
      }
      return recordRepository.getRawRecord(
          collection, externalAuth.get(qfs("recordRef"), String.class));
    } catch (Exception e) {
      return null;
    }
  }

  private void upsertExternalAuth(
      CollectionSchema collection, Map<String, Object> record, String provider, String providerId) {
    String now = Instant.now().toString();
    var existing =
        database
            .dsl()
            .selectFrom(qt("_externalAuths"))
            .where(qfs("collectionRef").eq(collection.id))
            .and(qfs("provider").eq(provider))
            .and(qfs("providerId").eq(providerId))
            .fetchOne();
    if (existing == null) {
      database
          .dsl()
          .insertInto(qt("_externalAuths"))
          .columns(
              qfs("id"),
              qfs("created"),
              qfs("updated"),
              qfs("recordRef"),
              qfs("collectionRef"),
              qfs("provider"),
              qfs("providerId"))
          .values(
              IdGenerator.id(),
              now,
              now,
              String.valueOf(record.get("id")),
              collection.id,
              provider,
              providerId)
          .execute();
      return;
    }
    database
        .dsl()
        .update(qt("_externalAuths"))
        .set(qfs("updated"), now)
        .set(qfs("recordRef"), String.valueOf(record.get("id")))
        .where(qfs("id").eq(existing.get(qfs("id"), String.class)))
        .execute();
  }

  private void ensureTokenKey(CollectionSchema collection, Map<String, Object> record) {
    if (!textSetting(record.get("tokenKey")).isBlank()) {
      return;
    }
    String tokenKey = IdGenerator.secret();
    recordRepository.updateFields(
        collection.name, String.valueOf(record.get("id")), Map.of("tokenKey", tokenKey));
    record.put("tokenKey", tokenKey);
  }

  private boolean sameCollection(RequestPrincipal principal, CollectionSchema collection) {
    if (principal == null || collection == null) {
      return false;
    }
    return collection.name.equals(principal.collectionName())
        || collection.id.equals(principal.collectionId());
  }

  private boolean truthy(Object value) {
    if (value == null)
      return false;
    if (value instanceof Boolean bool)
      return bool;
    if (value instanceof Number number)
      return number.intValue() != 0;
    return Boolean.parseBoolean(String.valueOf(value));
  }

  private String requiredText(JsonNode body, String field) {
    if (body == null || !body.has(field) || body.get(field).isNull()) {
      throw new ApiException(400, "Failed to authenticate.", ApiErrors.requiredField(field));
    }
    String value = body.get(field).asText().trim();
    if (value.isBlank()) {
      throw new ApiException(400, "Failed to authenticate.", ApiErrors.requiredField(field));
    }
    return value;
  }

  private String optionalText(JsonNode body, String field) {
    if (body == null || !body.has(field) || body.get(field).isNull()) {
      return "";
    }
    return body.get(field).asText().trim();
  }

  private Map<String, Object> orderedMap(Object... values) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i + 1 < values.length; i += 2) {
      map.put(String.valueOf(values[i]), values[i + 1]);
    }
    return map;
  }

  private String textSetting(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private String mfaId(JsonNode body, Map<String, String> query) {
    String value = optionalText(body, "mfaId");
    if (!value.isBlank()) {
      return value;
    }
    if (query == null) {
      return "";
    }
    String queryValue = query.get("mfaId");
    return queryValue == null ? "" : queryValue.trim();
  }

  private String bearerTokenSigningSecret(Map<String, Object> claims) {
    CollectionSchema collection =
        storeContext.getCollection(String.valueOf(claims.getOrDefault("collectionId", "")));
    if (collection == null) {
      collection =
          storeContext.getCollection(String.valueOf(claims.getOrDefault("collectionName", "")));
    }
    if (collection == null) {
      return "";
    }
    return tokenSigningSecret(collection.authToken, claims.get("tokenKey"));
  }

  private boolean mfaExpired(CollectionSchema collection, String created) {
    try {
      long durationSeconds = collection.mfa.duration > 0 ? collection.mfa.duration : 600;
      Instant cutoff = Instant.now().minusSeconds(durationSeconds);
      return Instant.parse(created).isBefore(cutoff);
    } catch (Exception e) {
      return true;
    }
  }

  private void appendAuthRequestFromToken(String token, String type, Duration ttl) {
    if (token == null || token.isBlank()) {
      return;
    }
    Map<String, Object> claims = tokenService.peek(token).orElse(Map.of());
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("id", IdGenerator.id());
    request.put("type", type);
    request.put("collectionId", claims.get("collectionId"));
    request.put("collectionName", claims.get("collectionName"));
    request.put("recordId", claims.get("sub"));
    request.put("email", claims.get("email"));
    if (claims.containsKey("newEmail")) {
      request.put("newEmail", claims.get("newEmail"));
    }
    request.put("token", token);
    request.put("created", Instant.now().toString());
    request.put("expires", Instant.now().plus(ttl == null ? Duration.ofHours(2) : ttl).toString());
    appendAuthRequest(request);
  }

  private void appendAuthRequest(Map<String, Object> request) {
    database
        .dsl()
        .insertInto(qt("_authRequests"))
        .columns(
            qfs("id"),
            qfs("type"),
            qfs("collectionId"),
            qfs("collectionName"),
            qfs("recordId"),
            qfs("email"),
            qfs("newEmail"),
            qfs("token"),
            qfs("created"),
            qfs("expires"))
        .values(
            String.valueOf(request.get("id")),
            String.valueOf(request.get("type")),
            String.valueOf(request.get("collectionId")),
            String.valueOf(request.get("collectionName")),
            String.valueOf(request.get("recordId")),
            String.valueOf(request.getOrDefault("email", "")),
            request.containsKey("newEmail") ? String.valueOf(request.get("newEmail")) : null,
            String.valueOf(request.get("token")),
            String.valueOf(request.get("created")),
            String.valueOf(request.get("expires")))
        .execute();
    CollectionSchema collection =
        storeContext.getCollection(String.valueOf(request.getOrDefault("collectionId", "")));
    if (collection == null) {
      collection =
          storeContext.getCollection(String.valueOf(request.getOrDefault("collectionName", "")));
    }
    Map<String, Object> record =
        collection == null
            ? Map.of()
            : Optional.ofNullable(
                storeContext.getRecord(
                    collection, String.valueOf(request.getOrDefault("recordId", ""))))
                .orElse(Map.of());
    Runnable onFailure =
        "otp".equals(request.get("type"))
            ? () -> database
                .dsl()
                .deleteFrom(qt("_otps"))
                .where(qfs("id").eq(String.valueOf(request.get("otpId"))))
                .execute()
            : null;
    if (!AuthMailSupport.sendAsync(
        collection, record, request, settingsRepository.loadRawSettings(), onFailure)) {
      appendAuthOutboxRequest(request);
    }
  }

  private synchronized void appendAuthOutboxRequest(Map<String, Object> request) {
    try {
      Files.createDirectories(dataDir);
      Path outbox = dataDir.resolve("auth_requests.json");
      List<Map<String, Object>> requests = new ArrayList<>();
      if (Files.exists(outbox)) {
        requests = mapper.readValue(outbox.toFile(), new TypeReference<>() {
        });
      }
      Instant now = Instant.now();
      requests.removeIf(item -> expiredOutboxRequest(item, now));
      requests.add(new LinkedHashMap<>(request));
      Files.writeString(
          outbox,
          mapper.writerWithDefaultPrettyPrinter().writeValueAsString(requests),
          StandardCharsets.UTF_8);
    } catch (Exception ignored) {
      // The database record remains authoritative if the development outbox cannot be written.
    }
  }

  private boolean expiredOutboxRequest(Map<String, Object> request, Instant now) {
    Object expires = request == null ? null : request.get("expires");
    if (expires == null || String.valueOf(expires).isBlank()) {
      return false;
    }
    try {
      return Instant.parse(String.valueOf(expires)).isBefore(now);
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private String actionToken(JsonNode body) {
    if (body == null || !body.hasNonNull("token") || body.get("token").asText().isBlank()) {
      throw invalidAuthRequestToken();
    }
    return body.get("token").asText();
  }

  private void consumeAuthRequestToken(String collection, String token, String type) {
    CollectionSchema schema = storeContext.getCollection(collection);
    if (schema == null) {
      throw invalidAuthRequestToken();
    }
    int consumed =
        database
            .dsl()
            .deleteFrom(qt("_authRequests"))
            .where(qfs("token").eq(token))
            .and(qfs("type").eq(type))
            .and(qfs("expires").gt(Instant.now().toString()))
            .and(qfs("collectionId").eq(schema.id).or(qfs("collectionName").eq(schema.name)))
            .execute();
    if (consumed != 1) {
      throw invalidAuthRequestToken();
    }
  }

  private ApiException invalidAuthRequestToken() {
    return new ApiException(
        400,
        "Invalid or expired token.",
        ApiErrors.invalidField("token", "Invalid or expired token."));
  }

  private Duration tokenDuration(CollectionSchema.TokenConfig config, long fallbackSeconds) {
    return Duration.ofSeconds(tokenDurationSeconds(config, fallbackSeconds));
  }

  private long tokenDurationSeconds(CollectionSchema.TokenConfig config, long fallbackSeconds) {
    if (config == null || config.duration <= 0) {
      return fallbackSeconds;
    }
    return config.duration;
  }

  private String tokenSigningSecret(CollectionSchema.TokenConfig config, Object tokenKeyValue) {
    String tokenKey = tokenKeyValue == null ? "" : String.valueOf(tokenKeyValue).trim();
    String secret = config == null || config.secret == null ? "" : config.secret.trim();
    return tokenKey + secret;
  }
}
