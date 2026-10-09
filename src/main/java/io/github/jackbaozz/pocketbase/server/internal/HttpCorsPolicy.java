package io.github.jackbaozz.pocketbase.server.internal;

import com.sun.net.httpserver.HttpExchange;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Origin matching and preflight headers compatible with PocketBase v0.40.5. */
public final class HttpCorsPolicy {
  private static final int MAX_AUTHORITY_BYTES = 253;
  private static final int MAX_PATTERN_ORIGIN_BYTES = MAX_AUTHORITY_BYTES + 3 + 5;
  private final List<String> allowedOrigins;

  public HttpCorsPolicy(List<String> allowedOrigins) {
    this.allowedOrigins =
        allowedOrigins == null || allowedOrigins.isEmpty()
            ? List.of("*")
            : List.copyOf(allowedOrigins);
  }

  public void apply(HttpExchange exchange) {
    var headers = exchange.getResponseHeaders();
    headers.add("Vary", "Origin");
    String allowed = allowOrigin(exchange.getRequestHeaders().getFirst("Origin"));
    if (allowed == null) {
      return;
    }
    headers.set("Access-Control-Allow-Origin", allowed);
    if ("OPTIONS".equals(exchange.getRequestMethod())) {
      headers.add("Vary", "Access-Control-Request-Method");
      headers.add("Vary", "Access-Control-Request-Headers");
      headers.set("Access-Control-Allow-Methods", "GET,HEAD,PUT,PATCH,POST,DELETE");
      String requestedHeaders =
          exchange.getRequestHeaders().getFirst("Access-Control-Request-Headers");
      if (requestedHeaders != null && !requestedHeaders.isEmpty()) {
        headers.set("Access-Control-Allow-Headers", requestedHeaders);
      }
    }
  }

  String allowOrigin(String origin) {
    if (origin == null || origin.isEmpty()) {
      return null;
    }
    for (String allowed : allowedOrigins) {
      if ("*".equals(allowed) || allowed.equals(origin)) {
        return allowed;
      }
      if (exactWildcardMatch(origin, allowed)) {
        return origin;
      }
    }
    // Upstream retains a bounded glob fallback. A '*' may span several labels here;
    // the strict fast path must not accept a mismatching prefix before this check.
    if (origin.getBytes(StandardCharsets.UTF_8).length <= MAX_PATTERN_ORIGIN_BYTES
        && origin.contains("://")) {
      for (String allowed : allowedOrigins) {
        if (wildcardMatch(origin, allowed)) {
          return origin;
        }
      }
    }
    return null;
  }

  static boolean exactWildcardMatch(String origin, String pattern) {
    int originScheme = origin.indexOf("://");
    int patternScheme = pattern.indexOf("://");
    if (originScheme < 0
        || patternScheme < 0
        || !origin.substring(0, originScheme).equals(pattern.substring(0, patternScheme))) {
      return false;
    }
    String authority = origin.substring(originScheme + 3);
    if (authority.getBytes(StandardCharsets.UTF_8).length > MAX_AUTHORITY_BYTES) {
      return false;
    }
    String[] parts = authority.split("\\.", -1);
    String[] expected = pattern.substring(patternScheme + 3).split("\\.", -1);
    if (parts.length != expected.length) {
      return false;
    }
    for (int i = parts.length - 1; i >= 0; i--) {
      if (!"*".equals(expected[i]) && !expected[i].equals(parts[i])) {
        return false;
      }
    }
    return true;
  }

  /** Equivalent to upstream's anchored '*'/'?' regex without regex backtracking. */
  private static boolean wildcardMatch(String origin, String pattern) {
    int[] text = origin.codePoints().toArray();
    int[] glob = pattern.codePoints().toArray();
    int at = 0;
    int next = 0;
    int star = -1;
    int retry = 0;
    while (at < text.length) {
      if (text[at] == '\n') {
        return false;
      }
      if (next < glob.length && glob[next] == '*') {
        star = next++;
        retry = at;
      } else if (next < glob.length && (glob[next] == '?' || glob[next] == text[at])) {
        at++;
        next++;
      } else if (star >= 0) {
        next = star + 1;
        at = ++retry;
      } else {
        return false;
      }
    }
    while (next < glob.length && glob[next] == '*') {
      next++;
    }
    return next == glob.length;
  }
}
