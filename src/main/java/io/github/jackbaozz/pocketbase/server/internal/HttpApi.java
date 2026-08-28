package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** HTTP surface that mirrors the most common PocketBase API and serves the admin UI. */
public final class HttpApi implements HttpHandler {
  private static final long DEFAULT_MAX_BODY_SIZE = 32L << 20;
  private static final long MAX_BACKUP_UPLOAD_SIZE = 256L << 20;
  private static final String BODY_LIMIT_ATTRIBUTE = "pocketbase.bodyLimit";
  private static final String SETTINGS_ATTRIBUTE = "pocketbase.settings";
  private static final int REALTIME_CLIENT_ID_MAX_LENGTH = 255;
  private static final int REALTIME_SUBSCRIPTIONS_MAX_COUNT = 1000;
  private static final int REALTIME_SUBSCRIPTION_MAX_LENGTH = 2500;

  public record Route(String method, String path) {
  }

  public static final List<Route> REGISTERED_ROUTES =
      List.of(
          new Route("GET", "/api/settings"),
          new Route("PATCH", "/api/settings"),
          new Route("POST", "/api/settings/test/s3"),
          new Route("POST", "/api/settings/test/email"),
          new Route("POST", "/api/settings/apple/generate-client-secret"),
          new Route("GET", "/api/collections"),
          new Route("POST", "/api/collections"),
          new Route("GET", "/api/collections/{collection}"),
          new Route("PATCH", "/api/collections/{collection}"),
          new Route("DELETE", "/api/collections/{collection}"),
          new Route("DELETE", "/api/collections/{collection}/truncate"),
          new Route("PUT", "/api/collections/import"),
          new Route("GET", "/api/collections/meta/scaffolds"),
          new Route("GET", "/api/collections/meta/oauth2-providers"),
          new Route("POST", "/api/collections/meta/dry-run-view"),
          new Route("GET", "/api/collections/{collection}/records"),
          new Route("POST", "/api/collections/{collection}/records"),
          new Route("GET", "/api/collections/{collection}/records/{id}"),
          new Route("PATCH", "/api/collections/{collection}/records/{id}"),
          new Route("DELETE", "/api/collections/{collection}/records/{id}"),
          new Route("GET", "/api/oauth2-redirect"),
          new Route("POST", "/api/oauth2-redirect"),
          new Route("GET", "/api/collections/{collection}/auth-methods"),
          new Route("POST", "/api/collections/{collection}/auth-refresh"),
          new Route("POST", "/api/collections/{collection}/auth-with-password"),
          new Route("POST", "/api/collections/{collection}/auth-with-oauth2"),
          new Route("POST", "/api/collections/{collection}/request-otp"),
          new Route("POST", "/api/collections/{collection}/auth-with-otp"),
          new Route("POST", "/api/collections/{collection}/request-password-reset"),
          new Route("POST", "/api/collections/{collection}/confirm-password-reset"),
          new Route("POST", "/api/collections/{collection}/request-verification"),
          new Route("POST", "/api/collections/{collection}/confirm-verification"),
          new Route("POST", "/api/collections/{collection}/request-email-change"),
          new Route("POST", "/api/collections/{collection}/confirm-email-change"),
          new Route("POST", "/api/collections/{collection}/impersonate/{id}"),
          new Route("GET", "/api/logs"),
          new Route("DELETE", "/api/logs"),
          new Route("GET", "/api/logs/stats"),
          new Route("GET", "/api/logs/{id}"),
          new Route("GET", "/api/backups"),
          new Route("POST", "/api/backups"),
          new Route("POST", "/api/backups/upload"),
          new Route("GET", "/api/backups/{key}"),
          new Route("DELETE", "/api/backups/{key}"),
          new Route("POST", "/api/backups/{key}/restore"),
          new Route("GET", "/api/crons"),
          new Route("POST", "/api/crons/{id}"),
          new Route("POST", "/api/files/token"),
          new Route("GET", "/api/files/{collection}/{recordId}/{filename}"),
          new Route("POST", "/api/batch"),
          new Route("GET", "/api/realtime"),
          new Route("POST", "/api/realtime"),
          new Route("GET", "/api/health"),
          new Route("POST", "/api/sql"));

  private final StorageEngine store;
  private final RealtimeHub realtimeHub;
  private final HttpRateLimiter rateLimiter = new HttpRateLimiter();

  public HttpApi(StorageEngine store, RealtimeHub realtimeHub) {
    this.store = store;
    this.realtimeHub = realtimeHub;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    String path = normalizePath(exchange.getRequestURI().getPath());
    int status = 0;
    long started = System.nanoTime();
    try {
      addCommonHeaders(exchange);
      boolean adminRoute = path.equals("/") || path.equals("/_/") || path.startsWith("/_/");
      RequestPrincipal requestPrincipal = principal(exchange).orElse(null);
      Map<String, Object> settings =
          !adminRoute || requestPrincipal != null ? store.getSettings(Map.of()) : Map.of();
      exchange.setAttribute(SETTINGS_ATTRIBUTE, settings);
      if (!HttpIpPolicy.superuserAllowed(exchange, settings, requestPrincipal)) {
        throw new ApiException(403, "You are not allowed to perform this request.");
      }
      if (!adminRoute) {
        RateLimitContext rateContext = rateLimitContext(path, method);
        rateLimiter.check(
            exchange, settings, requestPrincipal, rateContext.labels(), rateContext.limiterId());
        enforceBodyLimit(exchange, requestBodyLimit(path, method));
      }
      if ("OPTIONS".equals(method)) {
        status = 204;
        sendNoContent(exchange);
        return;
      }

      if (path.equals("/") || path.equals("/_/") || path.startsWith("/_/")) {
        serveAdmin(exchange, path);
        status = 200;
        return;
      }
      if (path.startsWith("/api/backups/") && "GET".equals(method)) {
        serveBackup(exchange, path);
        status = 200;
        return;
      }
      if (path.startsWith("/api/files/") && !path.equals("/api/files/token")) {
        serveFile(exchange, path);
        status = 200;
        return;
      }
      if (path.equals("/api/realtime") && "GET".equals(method)) {
        realtimeHub.connect(
            exchange,
            HttpIpPolicy.realIp(
                exchange, HttpIpPolicy.section(requestSettings(exchange), "trustedProxy")));
        return;
      }
      if (path.equals("/ping") && "GET".equals(method)) {
        status = 200;
        sendBytes(
            exchange, 200, "pong".getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
        return;
      }
      if (path.startsWith("/api/")) {
        Object response = routeApi(exchange, path);
        enforceSuperuserAuthResponseIp(exchange, response);
        if (response == NoContent.INSTANCE) {
          status = 204;
          sendNoContent(exchange);
        } else if (response instanceof RedirectResponse redirect) {
          status = redirect.status();
          sendRedirect(exchange, redirect.status(), redirect.location());
        } else {
          status = 200;
          sendJson(exchange, 200, response);
        }
        return;
      }
      throw new ApiException(404, "Not found.");
    } catch (ApiException e) {
      status = e.status();
      sendJson(exchange, e.status(), errorBody(e.status(), e.getMessage(), e.data()));
    } catch (IllegalArgumentException e) {
      status = 400;
      SecuritySupport.logInternalFailure("invalid HTTP request " + method + " " + path, e);
      sendJson(exchange, 400, errorBody(400, "Invalid request.", Map.of()));
    } catch (Exception e) {
      status = 500;
      String errorId = SecuritySupport.logInternalFailure("http " + method + " " + path, e);
      sendJson(
          exchange,
          500,
          errorBody(500, "Internal server error. Reference: " + errorId, Map.of("errorId", errorId)));
    } finally {
      long elapsedMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
      int logStatus = status == 0 ? 200 : status;
      String streamSuffix = status == 0 ? ", stream" : "";
      System.out.printf(
          "%s %s %d (%dms)%s%n",
          method, path, logStatus, elapsedMs, streamSuffix);
      if (shouldLogActivity(path, method, status)) {
        try {
          store.recordActivityLog(
              method,
              activityUrl(exchange),
              status,
              elapsedMs,
              principal(exchange).orElse(null),
              activityHeaders(exchange),
              remoteAddress(exchange));
        } catch (RuntimeException ignored) {
          // Activity logging must never change the API response.
        }
      }
      exchange.close();
    }
  }

  private RateLimitContext rateLimitContext(String path, String method) {
    List<String> defaults = List.of(method + " " + path, path);
    List<String> parts = segments(path);
    String collection = null;
    List<String> actions = List.of();

    if (parts.size() >= 4 && "api".equals(parts.get(0)) && "collections".equals(parts.get(1))) {
      collection = parts.get(2);
      String action = parts.get(3);
      if ("records".equals(action)) {
        if (parts.size() == 4) {
          actions =
              switch (method) {
                case "GET" -> List.of("list");
                case "POST" -> List.of("create");
                default -> List.of();
              };
        } else if (parts.size() == 5) {
          actions =
              switch (method) {
                case "GET" -> List.of("view");
                case "PATCH" -> List.of("update");
                case "DELETE" -> List.of("delete");
                default -> List.of();
              };
        }
      } else {
        actions = authRateLimitActions(action, method);
      }
    } else if (parts.size() >= 5 && "api".equals(parts.get(0)) && "files".equals(parts.get(1))) {
      collection = parts.get(2);
      actions = List.of("file");
    }

    if (collection == null || actions.isEmpty()) {
      return new RateLimitContext(defaults, "default");
    }
    try {
      Map<String, Object> info = store.getCollection(collection, Map.of("fields", "id,name"));
      String collectionName = String.valueOf(info.getOrDefault("name", collection));
      String collectionId = String.valueOf(info.getOrDefault("id", collection));
      List<String> labels = new ArrayList<>();
      actions.forEach(action -> labels.add(collectionName + ":" + action));
      actions.forEach(action -> labels.add("*:" + action));
      labels.addAll(defaults);
      return new RateLimitContext(labels, collectionId + '|' + String.join(",", actions));
    } catch (ApiException ignored) {
      return new RateLimitContext(defaults, "default");
    }
  }

  private List<String> authRateLimitActions(String action, String method) {
    if (!"GET".equals(method) && !"POST".equals(method)) {
      return List.of();
    }
    return switch (action) {
      case "auth-methods" -> List.of("listAuthMethods");
      case "auth-refresh" -> List.of("authRefresh");
      case "auth-with-password" -> List.of("authWithPassword", "auth");
      case "auth-with-oauth2" -> List.of("authWithOAuth2", "auth");
      case "request-otp" -> List.of("requestOTP");
      case "auth-with-otp" -> List.of("authWithOTP", "auth");
      case "request-password-reset" -> List.of("requestPasswordReset");
      case "confirm-password-reset" -> List.of("confirmPasswordReset");
      case "request-verification" -> List.of("requestVerification");
      case "confirm-verification" -> List.of("confirmVerification");
      case "request-email-change" -> List.of("requestEmailChange");
      case "confirm-email-change" -> List.of("confirmEmailChange");
      default -> List.of();
    };
  }

  private long requestBodyLimit(String path, String method) {
    if ("POST".equals(method) && "/api/backups/upload".equals(path)) {
      return MAX_BACKUP_UPLOAD_SIZE;
    }
    if (!"POST".equals(method) && !"PATCH".equals(method)) {
      return DEFAULT_MAX_BODY_SIZE;
    }
    List<String> parts = segments(path);
    if (parts.size() < 4
        || !"api".equals(parts.get(0))
        || !"collections".equals(parts.get(1))
        || !"records".equals(parts.get(3))) {
      return DEFAULT_MAX_BODY_SIZE;
    }
    try {
      Map<String, Object> collection = store.getCollection(parts.get(2), Map.of());
      return collectionBodyLimit(collection);
    } catch (ApiException ignored) {
      return DEFAULT_MAX_BODY_SIZE;
    }
  }

  static boolean unlimitedBodyRoute(String path, String method) {
    return false;
  }

  static long collectionBodyLimit(Map<String, Object> collection) {
    if (collection == null || "view".equals(collection.get("type"))) {
      return DEFAULT_MAX_BODY_SIZE;
    }
    long limit = DEFAULT_MAX_BODY_SIZE;
    Object fields = collection.get("fields");
    if (fields instanceof List<?> list) {
      for (Object raw : list) {
        if (raw instanceof Map<?, ?> field) {
          limit = safeBodyLimitAdd(limit, fieldBodyAllowance(field));
        }
      }
    }
    return limit;
  }

  private static long fieldBodyAllowance(Map<?, ?> field) {
    Object rawType = field.get("type");
    String type = String.valueOf(rawType == null ? "" : rawType).toLowerCase(Locale.ROOT);
    if (!"file".equals(type) && !"json".equals(type) && !"editor".equals(type)) {
      return 0L;
    }
    long fallback = "json".equals(type) ? 1L << 20 : 5L << 20;
    long maxSize = positiveLong(field.get("maxSize"), 0L);
    Object options = field.get("options");
    if (maxSize <= 0 && options instanceof Map<?, ?> map) {
      maxSize = positiveLong(map.get("maxSize"), 0L);
    }
    if (maxSize <= 0) {
      maxSize = fallback;
    }
    if (!"file".equals(type)) {
      return maxSize;
    }
    long maxSelect = positiveLong(field.get("maxSelect"), 0L);
    if (maxSelect <= 0) {
      maxSelect = positiveLong(field.get("maxFiles"), 0L);
    }
    if (maxSelect <= 0 && options instanceof Map<?, ?> map) {
      maxSelect = positiveLong(map.get("maxSelect"), 0L);
      if (maxSelect <= 0) {
        maxSelect = positiveLong(map.get("maxFiles"), 0L);
      }
    }
    return safeBodyLimitMultiply(maxSize, Math.max(1L, maxSelect));
  }

  private void enforceBodyLimit(HttpExchange exchange, long limit) {
    exchange.setAttribute(BODY_LIMIT_ATTRIBUTE, limit);
    String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
    if (contentLength == null || contentLength.isBlank()) {
      return;
    }
    try {
      if (Long.parseLong(contentLength) > limit) {
        throw new ApiException(413, "Request entity too large");
      }
    } catch (NumberFormatException ignored) {
    }
  }

  private static long positiveLong(Object value, long fallback) {
    if (value instanceof Number number) {
      return Math.max(0L, number.longValue());
    }
    try {
      return Math.max(0L, Long.parseLong(String.valueOf(value)));
    } catch (RuntimeException e) {
      return fallback;
    }
  }

  private static long safeBodyLimitAdd(long left, long right) {
    if (right > Long.MAX_VALUE - left) {
      return Long.MAX_VALUE;
    }
    return left + right;
  }

  private static long safeBodyLimitMultiply(long left, long right) {
    if (left > 0 && right > Long.MAX_VALUE / left) {
      return Long.MAX_VALUE;
    }
    return left * right;
  }

  private record RateLimitContext(List<String> labels, String limiterId) {
  }

  private Object routeApi(HttpExchange exchange, String path) throws IOException {
    String method = exchange.getRequestMethod();
    List<String> segments = segments(path);
    Map<String, String> query = query(exchange);
    RuleRequestContext ruleRequest = RuleRequestContext.of(query, requestHeaders(exchange));

    if (segments.size() == 2
        && "health".equals(segments.get(1))
        && ("GET".equals(method) || "HEAD".equals(method))) {
      return healthResponse(exchange, principal(exchange).orElse(null));
    }
    if (segments.size() == 2 && "realtime".equals(segments.get(1)) && "POST".equals(method)) {
      return subscribeRealtime(exchange, query);
    }
    if (segments.size() == 2 && "batch".equals(segments.get(1)) && "POST".equals(method)) {
      return handleBatch(exchange, principal(exchange).orElse(null), requestHeaders(exchange));
    }
    if (segments.size() == 2 && "sql".equals(segments.get(1)) && "POST".equals(method)) {
      RequestPrincipal principal = principal(exchange).orElse(null);
      requireSuperuser(principal);
      return store.runSql(readJson(exchange));
    }
    if (segments.size() == 2
        && "oauth2-redirect".equals(segments.get(1))
        && ("GET".equals(method) || "POST".equals(method))) {
      return oauth2SubscriptionRedirect(exchange);
    }
    if (segments.size() >= 2 && "settings".equals(segments.get(1))) {
      RequestPrincipal principal = principal(exchange).orElse(null);
      requireSuperuser(principal);
      if (segments.size() == 2) {
        return switch (method) {
          case "GET" -> store.getSettings(query);
          case "PATCH" -> store.updateSettings(readRecordInput(exchange).body(), query);
          default -> throw new ApiException(405, "Method not allowed.");
        };
      }
      if (segments.size() == 4
          && "test".equals(segments.get(2))
          && "s3".equals(segments.get(3))
          && "POST".equals(method)) {
        store.testS3(readRecordInput(exchange).body());
        return NoContent.INSTANCE;
      }
      if (segments.size() == 4
          && "test".equals(segments.get(2))
          && "email".equals(segments.get(3))
          && "POST".equals(method)) {
        store.testEmail(readRecordInput(exchange).body());
        return NoContent.INSTANCE;
      }
      if (segments.size() == 4
          && "apple".equals(segments.get(2))
          && "generate-client-secret".equals(segments.get(3))
          && "POST".equals(method)) {
        return store.generateAppleClientSecret(readRecordInput(exchange).body());
      }
      throw new ApiException(404, "Not found.");
    }
    if (segments.size() >= 2 && "logs".equals(segments.get(1))) {
      RequestPrincipal principal = principal(exchange).orElse(null);
      requireSuperuser(principal);
      if (segments.size() == 2 && "DELETE".equals(method)) {
        store.truncateLogs();
        return NoContent.INSTANCE;
      }
      if (segments.size() == 2 && "GET".equals(method)) {
        return store.listLogs(query);
      }
      if (segments.size() == 3 && "stats".equals(segments.get(2)) && "GET".equals(method)) {
        return store.logStats(query);
      }
      if (segments.size() == 3 && "GET".equals(method)) {
        return store.getLog(segments.get(2), query);
      }
      throw new ApiException(404, "Not found.");
    }
    if (segments.size() >= 2 && "crons".equals(segments.get(1))) {
      RequestPrincipal principal = principal(exchange).orElse(null);
      requireSuperuser(principal);
      if (segments.size() == 2 && "GET".equals(method)) {
        return store.listCrons();
      }
      if (segments.size() == 3 && "POST".equals(method)) {
        store.runCron(segments.get(2));
        return NoContent.INSTANCE;
      }
      throw new ApiException(404, "Not found.");
    }
    if (segments.size() == 3
        && "files".equals(segments.get(1))
        && "token".equals(segments.get(2))
        && "POST".equals(method)) {
      return store.fileToken(principal(exchange).orElse(null));
    }
    if (segments.size() >= 2 && "backups".equals(segments.get(1))) {
      RequestPrincipal principal = principal(exchange).orElse(null);
      requireSuperuser(principal);
      if (segments.size() == 2) {
        if ("GET".equals(method)) {
          return store.listBackups();
        }
        if ("POST".equals(method)) {
          createOrUploadBackup(exchange);
          return NoContent.INSTANCE;
        }
        throw new ApiException(405, "Method not allowed.");
      }
      if (segments.size() == 3 && "upload".equals(segments.get(2)) && "POST".equals(method)) {
        uploadBackup(exchange);
        return NoContent.INSTANCE;
      }
      if (segments.size() == 3 && "DELETE".equals(method)) {
        store.deleteBackup(segments.get(2));
        return NoContent.INSTANCE;
      }
      if (segments.size() == 4 && "restore".equals(segments.get(3)) && "POST".equals(method)) {
        requireBackupAvailable();
        store.restoreBackup(segments.get(2));
        return NoContent.INSTANCE;
      }
      throw new ApiException(404, "Not found.");
    }
    if (segments.size() == 3
        && "bootstrap".equals(segments.get(1))
        && "superuser".equals(segments.get(2))
        && "GET".equals(method)) {
      return Map.of("required", !store.hasSuperusers());
    }
    if (segments.size() == 3
        && "bootstrap".equals(segments.get(1))
        && "superuser".equals(segments.get(2))
        && "POST".equals(method)) {
      return store.bootstrapSuperuser(readJson(exchange));
    }
    if (segments.size() == 3
        && "admins".equals(segments.get(1))
        && "auth-with-password".equals(segments.get(2))
        && "POST".equals(method)) {
      requireSuperuserAuthIp(exchange, JsonFileStore.SUPERUSERS);
      return store.authWithPassword(
          JsonFileStore.SUPERUSERS, readJson(exchange), ruleRequest, authOriginContext(exchange));
    }
    if (segments.size() < 2 || !"collections".equals(segments.get(1))) {
      throw new ApiException(404, "Not found.");
    }

    RequestPrincipal principal = principal(exchange).orElse(null);
    if (segments.size() == 2) {
      return switch (method) {
        case "GET" -> {
          requireSuperuser(principal);
          yield store.listCollections(query);
        }
        case "POST" -> {
          requireSuperuser(principal);
          CollectionSchema created = store.createCollection(readJson(exchange));
          yield store.getCollection(created.id, query);
        }
        default -> throw new ApiException(405, "Method not allowed.");
      };
    }

    if (segments.size() == 3 && "import".equals(segments.get(2)) && "PUT".equals(method)) {
      requireSuperuser(principal);
      boolean dryRun = "true".equalsIgnoreCase(query.get("dryRun"));
      Object res = store.importCollections(readJson(exchange), dryRun);
      if (dryRun)
        return res;
      return NoContent.INSTANCE;
    }
    if (segments.size() == 4
        && "meta".equals(segments.get(2))
        && "scaffolds".equals(segments.get(3))
        && "GET".equals(method)) {
      requireSuperuser(principal);
      return store.collectionScaffolds();
    }
    if (segments.size() == 4
        && "meta".equals(segments.get(2))
        && "dry-run-view".equals(segments.get(3))
        && "POST".equals(method)) {
      requireSuperuser(principal);
      return store.dryRunView(readJson(exchange));
    }
    if (segments.size() == 4
        && "meta".equals(segments.get(2))
        && "oauth2-providers".equals(segments.get(3))
        && "GET".equals(method)) {
      requireSuperuser(principal);
      return store.oauth2ProviderMetadata();
    }

    String collection = segments.get(2);
    if (segments.size() == 3) {
      return switch (method) {
        case "GET" -> {
          requireSuperuser(principal);
          yield store.getCollection(collection, query);
        }
        case "PATCH" -> {
          requireSuperuser(principal);
          CollectionSchema updated = store.updateCollection(collection, readJson(exchange));
          yield store.getCollection(updated.id, query);
        }
        case "DELETE" -> {
          requireSuperuser(principal);
          store.deleteCollection(collection);
          yield NoContent.INSTANCE;
        }
        default -> throw new ApiException(405, "Method not allowed.");
      };
    }

    String action = segments.get(3);
    if (segments.size() == 4 && "truncate".equals(action) && "DELETE".equals(method)) {
      requireSuperuser(principal);
      store.truncateCollection(collection);
      return NoContent.INSTANCE;
    }
    if (segments.size() == 4 && "auth-with-password".equals(action) && "POST".equals(method)) {
      requireSuperuserAuthIp(exchange, collection);
      return store.authWithPassword(
          collection, readJson(exchange), ruleRequest, authOriginContext(exchange));
    }
    if (segments.size() == 4 && "request-otp".equals(action) && "POST".equals(method)) {
      return store.requestOtp(collection, readJson(exchange));
    }
    if (segments.size() == 4 && "auth-with-otp".equals(action) && "POST".equals(method)) {
      requireSuperuserAuthIp(exchange, collection);
      return store.authWithOtp(
          collection, readJson(exchange), ruleRequest, authOriginContext(exchange));
    }
    if (segments.size() == 4 && "auth-with-oauth2".equals(action) && "POST".equals(method)) {
      requireSuperuserAuthIp(exchange, collection);
      return store.authWithOAuth2(
          collection, readJson(exchange), ruleRequest, principal, authOriginContext(exchange));
    }
    if (segments.size() == 4 && "auth-refresh".equals(action) && "POST".equals(method)) {
      return store.authRefresh(collection, principal, query);
    }
    if (segments.size() == 4 && "auth-methods".equals(action) && "GET".equals(method)) {
      return store.authMethods(collection);
    }
    if (segments.size() == 4 && "request-password-reset".equals(action) && "POST".equals(method)) {
      store.requestPasswordReset(collection, readJson(exchange));
      return NoContent.INSTANCE;
    }
    if (segments.size() == 4 && "confirm-password-reset".equals(action) && "POST".equals(method)) {
      store.confirmPasswordReset(collection, readJson(exchange));
      return NoContent.INSTANCE;
    }
    if (segments.size() == 4 && "request-verification".equals(action) && "POST".equals(method)) {
      store.requestVerification(collection, readJson(exchange));
      return NoContent.INSTANCE;
    }
    if (segments.size() == 4 && "confirm-verification".equals(action) && "POST".equals(method)) {
      store.confirmVerification(collection, readJson(exchange));
      return NoContent.INSTANCE;
    }
    if (segments.size() == 4 && "request-email-change".equals(action) && "POST".equals(method)) {
      store.requestEmailChange(collection, readJson(exchange), principal);
      return NoContent.INSTANCE;
    }
    if (segments.size() == 4 && "confirm-email-change".equals(action) && "POST".equals(method)) {
      store.confirmEmailChange(collection, readJson(exchange));
      return NoContent.INSTANCE;
    }
    if (segments.size() == 5 && "impersonate".equals(action) && "POST".equals(method)) {
      requireSuperuser(principal);
      return store.impersonate(collection, segments.get(4), readJson(exchange), query);
    }
    if (!"records".equals(action)) {
      throw new ApiException(404, "Not found.");
    }

    if (segments.size() == 4) {
      return switch (method) {
        case "GET" -> store.listRecords(collection, ruleRequest, principal);
        case "POST" -> {
          RecordInput input = readRecordInput(exchange);
          yield store.createRecord(collection, input.body(), input.files(), ruleRequest, principal);
        }
        default -> throw new ApiException(405, "Method not allowed.");
      };
    }

    if (segments.size() == 5) {
      String id = segments.get(4);
      return switch (method) {
        case "GET" -> store.getRecord(collection, id, ruleRequest, principal);
        case "PATCH" -> {
          RecordInput input = readRecordInput(exchange);
          yield store.updateRecord(
              collection, id, input.body(), input.files(), ruleRequest, principal);
        }
        case "DELETE" -> {
          store.deleteRecord(collection, id, ruleRequest, principal);
          yield NoContent.INSTANCE;
        }
        default -> throw new ApiException(405, "Method not allowed.");
      };
    }

    throw new ApiException(404, "Not found.");
  }

  private Object subscribeRealtime(HttpExchange exchange, Map<String, String> query)
      throws IOException {
    RequestPrincipal principal = principal(exchange).orElse(null);
    RealtimeSubscriptionInput input = readRealtimeSubscriptionInput(exchange, query);
    realtimeHub.subscribe(
        input.clientId(),
        input.subscriptions(),
        input.options(),
        principal,
        HttpIpPolicy.realIp(
            exchange, HttpIpPolicy.section(requestSettings(exchange), "trustedProxy")));
    return NoContent.INSTANCE;
  }

  private RealtimeSubscriptionInput readRealtimeSubscriptionInput(
      HttpExchange exchange, Map<String, String> query) throws IOException {
    Map<String, String> values = new LinkedHashMap<>();
    List<String> subscriptions = new ArrayList<>();
    collectRealtimeValues(query, values, subscriptions);

    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    byte[] bytes = readRequestBytes(exchange);
    if (isMultipart(contentType)) {
      MultipartFormData multipart = MultipartFormData.parse(contentType, bytes, store.mapper());
      collectRealtimeJson(multipart.fields(), values, subscriptions);
    } else if (isFormUrlEncoded(contentType)) {
      collectRealtimeValues(
          query(new String(bytes, StandardCharsets.UTF_8)), values, subscriptions);
    } else if (bytes.length > 0) {
      collectRealtimeJson(store.mapper().readTree(bytes), values, subscriptions);
    }

    String clientId = values.get("clientId");
    if (clientId == null || clientId.isBlank()) {
      throw new ApiException(400, "Failed to subscribe.", ApiErrors.requiredField("clientId"));
    }
    validateRealtimeSubscriptionInput(clientId, subscriptions);

    Map<String, String> requestQuery = new LinkedHashMap<>();
    values.forEach(
        (key, value) -> {
          if (!"clientId".equals(key) && !"options".equals(key) && value != null) {
            requestQuery.put(key, value);
          }
        });
    RealtimeHub.SubscriptionOptions parsedOptions =
        realtimeHub.parseSubscriptionOptions(values.get("options"));
    requestQuery.putAll(parsedOptions.query());
    RealtimeHub.SubscriptionOptions options =
        new RealtimeHub.SubscriptionOptions(Map.copyOf(requestQuery), parsedOptions.headers());
    return new RealtimeSubscriptionInput(clientId, List.copyOf(subscriptions), options);
  }

  private void validateRealtimeSubscriptionInput(String clientId, List<String> subscriptions) {
    if (clientId.length() > REALTIME_CLIENT_ID_MAX_LENGTH) {
      throw new ApiException(
          400,
          "Failed to subscribe.",
          ApiErrors.fieldError(
              "clientId",
              "validation_length_too_long",
              "The value must be no more than 255 characters."));
    }
    if (subscriptions.size() > REALTIME_SUBSCRIPTIONS_MAX_COUNT) {
      throw new ApiException(
          400,
          "Failed to subscribe.",
          ApiErrors.fieldError(
              "subscriptions",
              "validation_length_too_long",
              "The list must contain no more than 1000 items."));
    }
    Map<String, Object> itemErrors = new LinkedHashMap<>();
    for (int index = 0; index < subscriptions.size(); index++) {
      if (subscriptions.get(index).length() > REALTIME_SUBSCRIPTION_MAX_LENGTH) {
        itemErrors.put(
            String.valueOf(index),
            ApiErrors.validationError(
                "validation_length_too_long", "The value must be no more than 2500 characters."));
      }
    }
    if (!itemErrors.isEmpty()) {
      throw new ApiException(400, "Failed to subscribe.", Map.of("subscriptions", itemErrors));
    }
  }

  private void collectRealtimeValues(
      Map<String, String> input, Map<String, String> values, List<String> subscriptions) {
    input.forEach(
        (key, value) -> {
          if (isSubscriptionField(key)) {
            addSubscription(value, subscriptions);
          } else {
            values.put(key, value);
          }
        });
  }

  private void collectRealtimeJson(
      JsonNode body, Map<String, String> values, List<String> subscriptions) throws IOException {
    if (body == null || body.isNull()) {
      return;
    }
    if (!body.isObject()) {
      throw new ApiException(
          400,
          "Realtime subscription payload must be an object.",
          ApiErrors.invalidField("body", "Request body must be a JSON object."));
    }
    Iterator<Map.Entry<String, JsonNode>> fields = body.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      String key = entry.getKey();
      JsonNode value = entry.getValue();
      if (isSubscriptionField(key)) {
        addJsonSubscription(value, subscriptions);
      } else if (value != null && !value.isNull()) {
        values.put(key, jsonText(value));
      }
    }
  }

  private void addJsonSubscription(JsonNode value, List<String> subscriptions) {
    if (value == null || value.isNull()) {
      return;
    }
    if (value.isArray()) {
      value.forEach(item -> addSubscription(item.asText(), subscriptions));
      return;
    }
    addSubscription(value.asText(), subscriptions);
  }

  private void addSubscription(String value, List<String> subscriptions) {
    if (value != null) {
      subscriptions.add(value);
    }
  }

  private boolean isSubscriptionField(String key) {
    return "subscriptions".equals(key)
        || "subscriptions[]".equals(key)
        || (key != null && key.startsWith("subscriptions[") && key.endsWith("]"));
  }

  private String jsonText(JsonNode value) throws IOException {
    if (value.isValueNode()) {
      return value.asText("");
    }
    return store.mapper().writeValueAsString(value);
  }

  private Object handleBatch(
      HttpExchange exchange, RequestPrincipal principal, Map<String, String> baseHeaders)
      throws IOException {
    BatchInput input = readBatchInput(exchange);
    JsonNode body = input.body();
    JsonNode requestsNode = body.isArray() ? body : body.get("requests");
    if (requestsNode == null || !requestsNode.isArray()) {
      throw new ApiException(400, "Failed to process batch.", ApiErrors.requiredField("requests"));
    }
    return store.transactional(
        () -> {
          List<Map<String, Object>> responses = new ArrayList<>();
          int index = 0;
          for (JsonNode request : requestsNode) {
            try {
              responses.add(
                  handleBatchRequest(request, input.filesFor(index), principal, baseHeaders));
            } catch (ApiException e) {
              throw new ApiException(
                  400,
                  "Batch request failed.",
                  Map.of(
                      "index", index, "response", errorBody(e.status(), e.getMessage(), e.data())));
            }
            index++;
          }
          return responses;
        });
  }

  private BatchInput readBatchInput(HttpExchange exchange) throws IOException {
    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    byte[] bytes = readRequestBytes(exchange);
    if (isMultipart(contentType)) {
      MultipartFormData multipart = MultipartFormData.parse(contentType, bytes, store.mapper());
      JsonNode payload = multipart.fields().get("@jsonPayload");
      if (payload == null || payload.isNull()) {
        throw new ApiException(
            400, "Failed to process batch.", ApiErrors.requiredField("@jsonPayload"));
      }
      JsonNode body;
      try {
        body = payload.isTextual() ? store.mapper().readTree(payload.asText()) : payload;
      } catch (JsonProcessingException e) {
        throw new ApiException(
            400,
            "Failed to process batch.",
            ApiErrors.invalidField("@jsonPayload", "Invalid JSON payload."));
      }
      return new BatchInput(body, batchFiles(multipart.files()));
    }
    if (bytes.length == 0) {
      throw new ApiException(400, "Failed to process batch.", ApiErrors.requiredField("requests"));
    }
    return new BatchInput(store.mapper().readTree(bytes), Map.of());
  }

  private Map<Integer, Map<String, List<UploadedFile>>> batchFiles(
      Map<String, List<UploadedFile>> files) {
    if (files == null || files.isEmpty()) {
      return Map.of();
    }
    Map<Integer, Map<String, List<UploadedFile>>> out = new LinkedHashMap<>();
    files.forEach(
        (name, uploaded) -> {
          BatchFileField field = batchFileField(name);
          if (field == null) {
            throw new ApiException(
                400,
                "Failed to process batch.",
                ApiErrors.invalidField(
                    "files", "Batch file fields must use requests.N.field or requests[N].field."));
          }
          out.computeIfAbsent(field.index(), ignored -> new LinkedHashMap<>())
              .computeIfAbsent(field.field(), ignored -> new ArrayList<>())
              .addAll(uploaded);
        });
    return out;
  }

  private BatchFileField batchFileField(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    if (name.startsWith("requests.")) {
      int indexStart = "requests.".length();
      int split = name.indexOf('.', indexStart);
      if (split <= indexStart || split == name.length() - 1) {
        return null;
      }
      return batchFileField(name.substring(indexStart, split), name.substring(split + 1));
    }
    if (name.startsWith("requests[")) {
      int indexStart = "requests[".length();
      int indexEnd = name.indexOf(']', indexStart);
      if (indexEnd <= indexStart
          || indexEnd + 1 >= name.length()
          || name.charAt(indexEnd + 1) != '.') {
        return null;
      }
      return batchFileField(name.substring(indexStart, indexEnd), name.substring(indexEnd + 2));
    }
    return null;
  }

  private BatchFileField batchFileField(String index, String field) {
    try {
      int parsedIndex = Integer.parseInt(index);
      if (parsedIndex < 0 || field == null || field.isBlank()) {
        return null;
      }
      return new BatchFileField(parsedIndex, field);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private Map<String, Object> handleBatchRequest(
      JsonNode request,
      Map<String, List<UploadedFile>> files,
      RequestPrincipal principal,
      Map<String, String> baseHeaders) {
    if (request == null || !request.isObject()) {
      throw new ApiException(
          400,
          "Batch request failed.",
          ApiErrors.invalidField("request", "Batch request must be an object."));
    }
    String method = optionalText(request, "method").toUpperCase(Locale.ROOT);
    if (method.isBlank()) {
      throw new ApiException(400, "Batch request failed.", ApiErrors.requiredField("method"));
    }
    String url = optionalText(request, "url");
    if (url.isBlank()) {
      url = optionalText(request, "path");
    }
    if (url.isBlank()) {
      throw new ApiException(400, "Batch request failed.", ApiErrors.requiredField("url"));
    }
    JsonNode body = request.has("body") ? request.get("body") : store.mapper().createObjectNode();
    BatchTarget target;
    try {
      target = batchTarget(url);
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          400,
          "Batch request failed.",
          ApiErrors.invalidField("url", "Invalid batch request URL."));
    }

    List<String> segments = target.segments();
    if (segments.size() < 4
        || !"api".equals(segments.get(0))
        || !"collections".equals(segments.get(1))
        || !"records".equals(segments.get(3))) {
      throw new ApiException(
          400,
          "Only record batch requests are supported.",
          ApiErrors.invalidField("url", "Only record batch requests are supported."));
    }
    String collection = segments.get(2);
    if (JsonFileStore.SUPERUSERS.equals(collection)) {
      requireSuperuser(principal);
    }

    int status;
    Object responseBody;
    Map<String, String> headers = new LinkedHashMap<>(baseHeaders == null ? Map.of() : baseHeaders);
    JsonNode requestHeaders = request.get("headers");
    if (requestHeaders != null && requestHeaders.isObject()) {
      requestHeaders
          .fields()
          .forEachRemaining(
              entry -> {
                if (!"authorization".equalsIgnoreCase(entry.getKey())) {
                  headers.put(
                      entry.getKey(), entry.getValue().isNull() ? "" : entry.getValue().asText(""));
                }
              });
    }
    RuleRequestContext ruleRequest =
        RuleRequestContext.of(target.query(), headers, RuleRequestContext.BATCH);
    if (segments.size() == 4) {
      switch (method) {
        case "POST" -> {
          responseBody = store.createRecord(collection, body, files, ruleRequest, principal);
          status = 200;
        }
        case "PUT" -> {
          responseBody = store.upsertRecord(collection, null, body, files, ruleRequest, principal);
          status = 200;
        }
        default -> throw new ApiException(405, "Method not allowed.");
      }
    } else if (segments.size() == 5) {
      String id = segments.get(4);
      switch (method) {
        case "PATCH" -> {
          responseBody = store.updateRecord(collection, id, body, files, ruleRequest, principal);
          status = 200;
        }
        case "PUT" -> {
          responseBody = store.upsertRecord(collection, id, body, files, ruleRequest, principal);
          status = 200;
        }
        case "DELETE" -> {
          store.deleteRecord(collection, id, ruleRequest, principal);
          responseBody = null;
          status = 204;
        }
        default -> throw new ApiException(405, "Method not allowed.");
      }
    } else {
      throw new ApiException(
          400,
          "Only record batch requests are supported.",
          ApiErrors.invalidField("url", "Only record batch requests are supported."));
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("status", status);
    response.put("body", responseBody);
    return response;
  }

  private BatchTarget batchTarget(String url) {
    URI raw = URI.create(url);
    URI uri = raw.isAbsolute() ? raw : URI.create(url.startsWith("/") ? url : "/" + url);
    return new BatchTarget(segments(normalizePath(uri.getPath())), query(uri.getRawQuery()));
  }

  private void createOrUploadBackup(HttpExchange exchange) throws IOException {
    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    if (isMultipart(contentType)) {
      uploadBackup(exchange);
      return;
    }
    requireBackupAvailable();
    store.createBackup(readJson(exchange));
  }

  private void uploadBackup(HttpExchange exchange) throws IOException {
    RecordInput input = readRecordInput(exchange);
    UploadedFile file =
        input.files().values().stream()
            .flatMap(List::stream)
            .findFirst()
            .orElseThrow(
                () -> new ApiException(
                    400, "Backup file is required.", ApiErrors.requiredField("file")));
    if (!"application/zip".equalsIgnoreCase(file.contentType())) {
      throw new ApiException(
          400,
          "An error occurred while validating the submitted data.",
          ApiErrors.fieldError("file", "validation_invalid_mime_type", "Invalid file type."));
    }
    store.uploadBackup(file.originalFilename(), file.bytes());
  }

  private void requireBackupAvailable() {
    if (!store.canBackup()) {
      throw new ApiException(
          400, "Try again later - another backup/restore process has already been started.");
    }
  }

  private void requireSuperuser(RequestPrincipal principal) {
    if (principal == null) {
      throw new ApiException(401, "Missing or invalid auth token.");
    }
    if (!principal.superuser()) {
      throw new ApiException(403, "Superuser token required.");
    }
  }

  private Map<String, Object> healthResponse(HttpExchange exchange, RequestPrincipal principal) {
    Map<String, Object> data = new LinkedHashMap<>();
    if (principal != null && principal.superuser()) {
      Map<String, Object> settings = requestSettings(exchange);
      Map<String, Object> trustedProxy = HttpIpPolicy.section(settings, "trustedProxy");
      data.put("canBackup", store.canBackup());
      data.put("realIP", HttpIpPolicy.realIp(exchange, trustedProxy));
      data.put("possibleProxyHeader", possibleProxyHeader(exchange, trustedProxy));
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("message", "API is healthy.");
    response.put("code", 200);
    response.put("data", data);
    return response;
  }

  private String possibleProxyHeader(HttpExchange exchange, Map<String, Object> trustedProxy) {
    List<String> headers = new ArrayList<>();
    for (Object value : HttpIpPolicy.list(trustedProxy.get("headers"))) {
      String header = String.valueOf(value == null ? "" : value).trim();
      if (!header.isBlank()) {
        headers.add(header);
      }
    }
    headers.add("CF-Connecting-IP");
    headers.add("Fly-Client-IP");
    headers.add("X\u2011Forwarded-For");

    for (String header : headers) {
      String value = exchange.getRequestHeaders().getFirst(header);
      if (value != null && !value.isBlank()) {
        return header;
      }
    }
    return "";
  }

  private Optional<RequestPrincipal> principal(HttpExchange exchange) {
    String header = exchange.getRequestHeaders().getFirst("Authorization");
    if (header == null || header.isBlank()) {
      return Optional.empty();
    }
    String token =
        header.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())
            ? header.substring("Bearer ".length()).trim()
            : header.trim();
    if (token.isBlank()) {
      return Optional.empty();
    }
    return store
        .verifyToken(token)
        .filter(
            claims -> !"file".equals(claims.get("type")) && !"file".equals(claims.get("tokenType")))
        .map(RequestPrincipal::fromClaims);
  }

  private String optionalText(JsonNode body, String field) {
    JsonNode value = body == null ? null : body.get(field);
    return value == null || value.isNull() ? "" : value.asText("");
  }

  private RedirectResponse oauth2SubscriptionRedirect(HttpExchange exchange) throws IOException {
    Map<String, String> values;
    try {
      values =
          "GET".equals(exchange.getRequestMethod()) ? query(exchange) : formOrJsonValues(exchange);
    } catch (RuntimeException | IOException e) {
      return oauth2Redirect(exchange, false);
    }
    String state = values.getOrDefault("state", "");
    String code = values.getOrDefault("code", "");
    String error = values.getOrDefault("error", "");
    if (state.isBlank()) {
      return oauth2Redirect(exchange, false);
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("state", state);
    payload.put("code", code);
    if (!error.isBlank()) {
      payload.put("error", error);
    }
    String remoteIp =
        HttpIpPolicy.realIp(
            exchange, HttpIpPolicy.section(requestSettings(exchange), "trustedProxy"));
    if (!realtimeHub.sendOAuth2Redirect(state, remoteIp, payload)) {
      return oauth2Redirect(exchange, false);
    }
    if (error.isBlank() && !code.isBlank()) {
      OAuth2Support.storeAppleRedirectName(store.mapper(), code, values.getOrDefault("user", ""));
    }
    return oauth2Redirect(exchange, error.isBlank() && !code.isBlank());
  }

  private RedirectResponse oauth2Redirect(HttpExchange exchange, boolean success) {
    int status = "GET".equals(exchange.getRequestMethod()) ? 307 : 303;
    String location =
        success ? "../_/#/auth/oauth2-redirect-success" : "../_/#/auth/oauth2-redirect-failure";
    return new RedirectResponse(status, location);
  }

  private Map<String, String> formOrJsonValues(HttpExchange exchange) throws IOException {
    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    byte[] bytes = readRequestBytes(exchange);
    if (bytes.length == 0) {
      return Map.of();
    }
    if (isFormUrlEncoded(contentType)) {
      return query(new String(bytes, StandardCharsets.UTF_8));
    }
    JsonNode body = store.mapper().readTree(bytes);
    if (body == null || !body.isObject()) {
      return Map.of();
    }
    Map<String, String> values = new LinkedHashMap<>();
    body.fields()
        .forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText("")));
    return values;
  }

  private RecordInput readRecordInput(HttpExchange exchange) throws IOException {
    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    byte[] bytes = readRequestBytes(exchange);
    if (isMultipart(contentType)) {
      MultipartFormData multipart = MultipartFormData.parse(contentType, bytes, store.mapper());
      JsonNode payload = multipart.fields().get("@jsonPayload");
      if (payload == null || payload.isNull()) {
        return new RecordInput(multipart.fields(), multipart.files());
      }

      JsonNode decoded;
      try {
        decoded = decodeMultipartJsonPayload(payload);
      } catch (JsonProcessingException e) {
        throw new ApiException(
            400,
            "Failed to read request body.",
            ApiErrors.invalidField("@jsonPayload", "Invalid JSON payload."));
      }
      if (decoded == null || !decoded.isObject()) {
        throw new ApiException(
            400,
            "Failed to read request body.",
            ApiErrors.invalidField("@jsonPayload", "Invalid JSON payload."));
      }

      ObjectNode fields = ((ObjectNode) decoded).deepCopy();
      multipart
          .fields()
          .fields()
          .forEachRemaining(
              entry -> {
                if (!"@jsonPayload".equals(entry.getKey())) {
                  fields.set(entry.getKey(), entry.getValue());
                }
              });
      return new RecordInput(JsonResponseSanitizer.sanitize(store.mapper(), fields), multipart.files());
    }
    if (bytes.length == 0) {
      return new RecordInput(store.mapper().createObjectNode(), Map.of());
    }
    return new RecordInput(readJsonBytes(bytes), Map.of());
  }

  private JsonNode decodeMultipartJsonPayload(JsonNode payload) throws JsonProcessingException {
    JsonNode value = payload;
    if (value.isArray() && value.size() == 1 && value.get(0).isTextual()) {
      value = value.get(0);
    }
    return value.isTextual() ? store.mapper().readTree(value.asText()) : value;
  }

  private JsonNode readJson(HttpExchange exchange) throws IOException {
    byte[] bytes = readRequestBytes(exchange);
    if (bytes.length == 0) {
      return store.mapper().createObjectNode();
    }
    return readJsonBytes(bytes);
  }

  private JsonNode readJsonBytes(byte[] bytes) throws IOException {
    try {
      return JsonResponseSanitizer.sanitize(store.mapper(), store.mapper().readTree(bytes));
    } catch (JsonProcessingException e) {
      throw new ApiException(
          400,
          "Failed to read request body.",
          ApiErrors.invalidField("body", "Invalid JSON payload."));
    }
  }

  private void serveFile(HttpExchange exchange, String path) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
      throw new ApiException(405, "Method not allowed.");
    }
    List<String> segments = segments(path);
    if (segments.size() != 5
        || !"api".equals(segments.get(0))
        || !"files".equals(segments.get(1))) {
      throw new ApiException(404, "The requested resource wasn't found.");
    }
    Map<String, String> query = query(exchange);
    String collection = segments.get(2);
    String recordId = segments.get(3);
    String filename = segments.get(4);
    RuleRequestContext request =
        RuleRequestContext.of(query, requestHeaders(exchange), RuleRequestContext.PROTECTED_FILE);
    Path file =
        store.filePath(
            collection, recordId, filename, request, filePrincipal(exchange).orElse(null));
    if (file == null
        || Files.isSymbolicLink(file)
        || !Files.exists(file)
        || !Files.isRegularFile(file)) {
      throw new ApiException(404, "The requested resource wasn't found.");
    }
    ServedFile served = servedFile(file, collection, recordId, filename, query.get("thumb"));
    // Record files are supplied by users. Never allow the browser to infer an active
    // document type (for example an HTML payload named as an image), and sandbox any
    // document that a browser still chooses to render inline.
    exchange.getResponseHeaders().set("Cross-Origin-Opener-Policy", "same-origin");
    exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    exchange
        .getResponseHeaders()
        .set("Content-Security-Policy", "sandbox; default-src 'none'; base-uri 'none'; form-action 'none'");
    HttpFileSupport.serve(
        exchange,
        served.path(),
        served.contentType(),
        truthy(query.get("download")) || !safeInlineFileContentType(served.contentType()),
        headerFilename(filename));
  }

  private ServedFile servedFile(
      Path file, String collection, String recordId, String filename, String thumb)
      throws IOException {
    if (thumb == null
        || thumb.isBlank()
        || !store.fileThumbAllowed(collection, recordId, filename, thumb)) {
      return new ServedFile(file, uploadedFileContentType(file, filename));
    }
    var generated = ThumbnailGenerator.generate(file, filename, thumb);
    if (generated.isPresent()) {
      var thumbnail = generated.get();
      return new ServedFile(
          thumbnail.path(), uploadedFileContentType(thumbnail.path(), thumbnail.contentType()));
    }
    return new ServedFile(file, uploadedFileContentType(file, filename));
  }

  private void serveBackup(HttpExchange exchange, String path) throws IOException {
    store
        .verifyFileToken(query(exchange).get("token"))
        .filter(RequestPrincipal::superuser)
        .filter(
            candidate -> HttpIpPolicy.superuserAllowed(exchange, requestSettings(exchange), candidate))
        .orElseThrow(
            () -> new ApiException(403, "Insufficient permissions to access the resource."));
    List<String> segments = segments(path);
    if (segments.size() != 3
        || !"api".equals(segments.get(0))
        || !"backups".equals(segments.get(1))) {
      throw new ApiException(404, "Backup not found.");
    }
    Path backup = store.backupFile(segments.get(2));
    if (backup == null
        || Files.isSymbolicLink(backup)
        || !Files.exists(backup)
        || !Files.isRegularFile(backup)) {
      throw new ApiException(404, "Backup not found.");
    }
    String filename = backup.getFileName().toString();
    HttpFileSupport.serve(exchange, backup, "application/zip", true, filename);
  }

  private Optional<RequestPrincipal> filePrincipal(HttpExchange exchange) {
    Optional<RequestPrincipal> bearer = principal(exchange);
    if (bearer.isPresent()) {
      return bearer;
    }
    String token = query(exchange).get("token");
    return store
        .verifyFileToken(token)
        .filter(
            candidate -> HttpIpPolicy.superuserAllowed(exchange, requestSettings(exchange), candidate));
  }

  private void serveAdmin(HttpExchange exchange, String path) throws IOException {
    String file =
        path.equals("/") || path.equals("/_/") || path.equals("/_/index.html")
            ? "index.html"
            : path.substring("/_/".length());
    if (file.contains("..") || file.startsWith("/")) {
      throw new ApiException(404, "Not found.");
    }
    String resource = "/pocketbase-admin/" + file;
    try (InputStream input = HttpApi.class.getResourceAsStream(resource)) {
      if (input == null) {
        throw new ApiException(404, "Not found.");
      }
      byte[] bytes = input.readAllBytes();
      exchange.getResponseHeaders().set("Content-Type", contentType(file));
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    }
  }

  private byte[] readRequestBytes(HttpExchange exchange) throws IOException {
    Object configured = exchange.getAttribute(BODY_LIMIT_ATTRIBUTE);
    long limit = configured instanceof Number number ? number.longValue() : DEFAULT_MAX_BODY_SIZE;
    try (InputStream input = exchange.getRequestBody();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      long total = 0L;
      int read;
      while ((read = input.read(buffer)) >= 0) {
        if (read == 0) {
          continue;
        }
        total += read;
        if (limit > 0 && total > limit) {
          throw new ApiException(413, "Request entity too large");
        }
        output.write(buffer, 0, read);
      }
      return output.toByteArray();
    }
  }

  private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
    byte[] bytes = store.mapper().writeValueAsBytes(JsonResponseSanitizer.sanitize(store.mapper(), body));
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    if ("HEAD".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(status, -1);
      return;
    }
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private void sendNoContent(HttpExchange exchange) throws IOException {
    exchange.sendResponseHeaders(204, -1);
  }

  private void sendRedirect(HttpExchange exchange, int status, String location) throws IOException {
    exchange.getResponseHeaders().set("Location", location);
    exchange.sendResponseHeaders(status, -1);
  }

  private void sendBytes(HttpExchange exchange, int status, byte[] body, String contentType)
      throws IOException {
    byte[] bytes = body == null ? new byte[0] : body;
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private void addCommonHeaders(HttpExchange exchange) {
    exchange.getResponseHeaders().set("Cross-Origin-Opener-Policy", "same-origin");
    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    exchange
        .getResponseHeaders()
        .set("Access-Control-Allow-Headers", "Authorization, Content-Type");
    exchange
        .getResponseHeaders()
        .set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
    exchange.getResponseHeaders().set("Content-Security-Policy", "frame-ancestors 'none'");
    exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
    exchange
        .getResponseHeaders()
        .set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), usb=()");
  }

  private boolean shouldLogActivity(String path, String method, int status) {
    if (status <= 0 || "OPTIONS".equals(method) || path == null || !path.startsWith("/api/")) {
      return false;
    }
    boolean logsRoute = path.equals("/api/logs") || path.startsWith("/api/logs/");
    return !path.equals("/api/health")
        && !(path.equals("/api/realtime") && "GET".equals(method))
        && (!logsRoute || status >= 400);
  }

  private String activityUrl(HttpExchange exchange) {
    URI uri = exchange.getRequestURI();
    String rawPath =
        uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
    String rawQuery = redactQuery(uri.getRawQuery());
    return rawQuery == null || rawQuery.isBlank() ? rawPath : rawPath + "?" + rawQuery;
  }

  private String redactQuery(String rawQuery) {
    if (rawQuery == null || rawQuery.isBlank()) {
      return rawQuery;
    }
    StringBuilder redacted = new StringBuilder(rawQuery.length());
    String[] pairs = rawQuery.split("&", -1);
    for (int i = 0; i < pairs.length; i++) {
      if (i > 0) {
        redacted.append('&');
      }
      String pair = pairs[i];
      int equals = pair.indexOf('=');
      String encodedKey = equals < 0 ? pair : pair.substring(0, equals);
      String decodedKey;
      try {
        decodedKey = URLDecoder.decode(encodedKey, StandardCharsets.UTF_8);
      } catch (IllegalArgumentException ignored) {
        decodedKey = encodedKey;
      }
      redacted.append(encodedKey);
      if (equals >= 0) {
        redacted.append('=');
        redacted.append(
            SecuritySupport.isSensitiveQueryKey(decodedKey)
                ? "[REDACTED]"
                : pair.substring(equals + 1));
      }
    }
    return redacted.toString();
  }

  private Map<String, String> requestHeaders(HttpExchange exchange) {
    return collectHeaders(exchange, false);
  }

  private Map<String, String> activityHeaders(HttpExchange exchange) {
    return collectHeaders(exchange, true);
  }

  private Map<String, String> collectHeaders(HttpExchange exchange, boolean redactSensitive) {
    Map<String, String> headers = new LinkedHashMap<>();
    exchange
        .getRequestHeaders()
        .forEach(
            (key, values) -> {
              if (key == null || values == null || values.isEmpty()) {
                return;
              }
              String value = values.get(0);
              if (redactSensitive && SecuritySupport.isSensitiveHeader(key)) {
                value = "[REDACTED]";
              } else if (redactSensitive && "referer".equalsIgnoreCase(key)) {
                value = redactUrl(value);
              }
              headers.put(key, value);
              headers.put(key.toLowerCase(Locale.ROOT), value);
            });
    return headers;
  }

  private String redactUrl(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    try {
      URI uri = URI.create(value);
      // A referrer may contain reset tokens in either its path or query and may even include
      // user-info credentials. Keep only the origin so activity logs cannot become a credential
      // sink while retaining enough context for diagnostics.
      String scheme = uri.getScheme();
      String authority = uri.getRawAuthority();
      if (scheme == null || scheme.isBlank() || authority == null || authority.isBlank()) {
        return "[REDACTED]";
      }
      int userInfoSeparator = authority.lastIndexOf('@');
      if (userInfoSeparator >= 0) {
        authority = authority.substring(userInfoSeparator + 1);
      }
      return scheme + "://" + authority;
    } catch (IllegalArgumentException e) {
      return "[REDACTED]";
    }
  }

  private String remoteAddress(HttpExchange exchange) {
    if (exchange.getRemoteAddress() == null || exchange.getRemoteAddress().getAddress() == null) {
      return "";
    }
    return exchange.getRemoteAddress().getAddress().getHostAddress();
  }

  private AuthOriginContext authOriginContext(HttpExchange exchange) {
    String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
    Map<String, Object> settings = requestSettings(exchange);
    return new AuthOriginContext(
        HttpIpPolicy.realIp(exchange, HttpIpPolicy.section(settings, "trustedProxy")), userAgent);
  }

  private void enforceSuperuserAuthResponseIp(HttpExchange exchange, Object response) {
    if (!(response instanceof Map<?, ?> map)) {
      return;
    }
    boolean superuserResponse =
        map.get("record") instanceof Map<?, ?> record
            && JsonFileStore.SUPERUSERS.equals(String.valueOf(record.get("collectionName")));
    Object token = map.get("token");
    if (token != null) {
      superuserResponse =
          superuserResponse
              || store
                  .verifyToken(String.valueOf(token))
                  .map(RequestPrincipal::fromClaims)
                  .map(RequestPrincipal::superuser)
                  .orElse(false);
    }
    if (superuserResponse
        && !HttpIpPolicy.superuserIpAllowed(exchange, requestSettings(exchange))) {
      throw new ApiException(403, "You are not allowed to perform this request.");
    }
  }

  private void requireSuperuserAuthIp(HttpExchange exchange, String collection) {
    if (SystemCollections.isSuperuserIdentifier(collection)
        && !HttpIpPolicy.superuserIpAllowed(exchange, requestSettings(exchange))) {
      throw new ApiException(403, "You are not allowed to perform this request.");
    }
  }

  private Map<String, Object> requestSettings(HttpExchange exchange) {
    Object value = exchange.getAttribute(SETTINGS_ATTRIBUTE);
    if (value instanceof Map<?, ?> map) {
      return Unsafe.stringObjectMap(map);
    }
    Map<String, Object> settings = store.getSettings(Map.of());
    exchange.setAttribute(SETTINGS_ATTRIBUTE, settings);
    return settings;
  }

  private Map<String, Object> errorBody(int status, String message, Object data) {
    if (status == 401 && data instanceof Map<?, ?> map && map.containsKey("mfaId")) {
      Map<String, Object> raw = new LinkedHashMap<>();
      raw.put("mfaId", map.get("mfaId"));
      return raw;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", status);
    body.put("message", message == null || message.isBlank() ? "Request failed." : message);
    body.put("data", data == null ? Map.of() : data);
    return body;
  }

  private Map<String, String> query(HttpExchange exchange) {
    return query(exchange.getRequestURI().getRawQuery());
  }

  private Map<String, String> query(String query) {
    Map<String, String> values = new LinkedHashMap<>();
    if (query == null || query.isBlank()) {
      return values;
    }
    for (String pair : query.split("&")) {
      if (pair.isBlank()) {
        continue;
      }
      int index = pair.indexOf('=');
      String key = index >= 0 ? pair.substring(0, index) : pair;
      String value = index >= 0 ? pair.substring(index + 1) : "";
      values.put(decode(key), decode(value));
    }
    return values;
  }

  private List<String> segments(String path) {
    List<String> out = new ArrayList<>();
    for (String part : path.split("/")) {
      if (!part.isBlank()) {
        out.add(decode(part));
      }
    }
    return out;
  }

  private String normalizePath(String path) {
    if (path == null || path.isBlank()) {
      return "/";
    }
    return path;
  }

  private String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  private String contentType(String file) {
    String lower = file.toLowerCase();
    if (lower.endsWith(".html") || lower.endsWith(".htm")) {
      return "text/html; charset=utf-8";
    }
    if (lower.endsWith(".png")) {
      return "image/png";
    }
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
      return "image/jpeg";
    }
    if (lower.endsWith(".gif")) {
      return "image/gif";
    }
    if (lower.endsWith(".webp")) {
      return "image/webp";
    }
    if (lower.endsWith(".mp3")) {
      return "audio/mpeg";
    }
    if (lower.endsWith(".ogg")) {
      return "audio/ogg";
    }
    if (lower.endsWith(".wav")) {
      return "audio/wav";
    }
    if (lower.endsWith(".m4a")) {
      return "audio/mp4";
    }
    if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) {
      return "video/mp4";
    }
    if (lower.endsWith(".webm")) {
      return "video/webm";
    }
    if (lower.endsWith(".pdf")) {
      return "application/pdf";
    }
    if (lower.endsWith(".txt")) {
      return "text/plain; charset=utf-8";
    }
    if (lower.endsWith(".css")) {
      return "text/css; charset=utf-8";
    }
    if (lower.endsWith(".js")) {
      return "application/javascript; charset=utf-8";
    }
    if (lower.endsWith(".svg")) {
      return "image/svg+xml";
    }
    return "application/octet-stream";
  }

  private String uploadedFileContentType(Path file, String filename) throws IOException {
    String type = filename.contains("/") ? filename : contentType(filename);
    // SVG, HTML, JavaScript and CSS are active document formats. Files in a record
    // are untrusted, so expose them as downloads rather than executable same-origin
    // documents. Binary media is also checked by signature before it is declared
    // inline-safe, preventing an extension-only MIME spoof.
    if ("image/svg+xml".equals(type)
        || type.startsWith("text/html")
        || type.startsWith("application/javascript")
        || type.startsWith("text/css")) {
      return "application/octet-stream";
    }
    if (requiresSignature(type) && !matchesFileSignature(file, type)) {
      return "application/octet-stream";
    }
    return type;
  }

  private boolean safeInlineFileContentType(String type) {
    return "image/png".equals(type)
        || "image/jpeg".equals(type)
        || "image/gif".equals(type)
        || "image/webp".equals(type)
        || "application/pdf".equals(type)
        || type.startsWith("text/plain")
        || type.startsWith("audio/")
        || type.startsWith("video/");
  }

  private boolean requiresSignature(String type) {
    return "image/png".equals(type)
        || "image/jpeg".equals(type)
        || "image/gif".equals(type)
        || "image/webp".equals(type)
        || "application/pdf".equals(type);
  }

  private boolean matchesFileSignature(Path file, String type) throws IOException {
    byte[] prefix;
    try (InputStream input = Files.newInputStream(file)) {
      prefix = input.readNBytes(12);
    }
    if ("image/png".equals(type)) {
      return hasPrefix(prefix, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a);
    }
    if ("image/jpeg".equals(type)) {
      return hasPrefix(prefix, 0xff, 0xd8, 0xff);
    }
    if ("image/gif".equals(type)) {
      return hasPrefix(prefix, 'G', 'I', 'F', '8', '7', 'a')
          || hasPrefix(prefix, 'G', 'I', 'F', '8', '9', 'a');
    }
    if ("image/webp".equals(type)) {
      return hasPrefix(prefix, 'R', 'I', 'F', 'F')
          && prefix.length >= 12
          && prefix[8] == 'W'
          && prefix[9] == 'E'
          && prefix[10] == 'B'
          && prefix[11] == 'P';
    }
    return !"application/pdf".equals(type) || hasPrefix(prefix, '%', 'P', 'D', 'F', '-');
  }

  private boolean hasPrefix(byte[] bytes, int... expected) {
    if (bytes.length < expected.length) {
      return false;
    }
    for (int index = 0; index < expected.length; index++) {
      if ((bytes[index] & 0xff) != expected[index]) {
        return false;
      }
    }
    return true;
  }

  private boolean truthy(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    return !"0".equals(normalized) && !"false".equals(normalized) && !"no".equals(normalized);
  }

  private String headerFilename(String filename) {
    return filename.replace("\\", "_").replace("\"", "_").replace("\r", "_").replace("\n", "_");
  }

  private boolean isMultipart(String contentType) {
    return contentType != null && contentType.toLowerCase().startsWith("multipart/form-data");
  }

  private boolean isFormUrlEncoded(String contentType) {
    return contentType != null
        && contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded");
  }

  private record RecordInput(JsonNode body, Map<String, List<UploadedFile>> files) {
  }

  private record ServedFile(Path path, String contentType) {
  }

  private record RedirectResponse(int status, String location) {
  }

  private record BatchTarget(List<String> segments, Map<String, String> query) {
  }

  private record BatchInput(JsonNode body, Map<Integer, Map<String, List<UploadedFile>>> files) {
    Map<String, List<UploadedFile>> filesFor(int index) {
      if (files == null || files.isEmpty()) {
        return Map.of();
      }
      return files.getOrDefault(index, Map.of());
    }
  }

  private record BatchFileField(int index, String field) {
  }

  private record RealtimeSubscriptionInput(
      String clientId, List<String> subscriptions, RealtimeHub.SubscriptionOptions options) {
  }

  private enum NoContent {
    INSTANCE
  }
}
