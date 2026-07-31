package io.github.jackbaozz.pocketbase.server.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

public record AuthOriginContext(String ip, String userAgent) {
  public AuthOriginContext {
    ip = ip == null ? "" : ip.trim();
    userAgent = normalizeUserAgent(userAgent);
  }

  public static AuthOriginContext empty() {
    return new AuthOriginContext("", "");
  }

  public String fingerprint() {
    try {
      MessageDigest digest = MessageDigest.getInstance("MD5");
      return HexFormat.of()
          .formatHex(digest.digest((ip + userAgent).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("MD5 digest is unavailable", e);
    }
  }

  public String alertInfo() {
    return Instant.now() + " - " + ip + (userAgent.isBlank() ? "" : " " + userAgent);
  }

  private static String normalizeUserAgent(String value) {
    String normalized = value == null ? "" : value.trim();
    return normalized.length() > 200 ? normalized.substring(0, 200) + "..." : normalized;
  }
}
