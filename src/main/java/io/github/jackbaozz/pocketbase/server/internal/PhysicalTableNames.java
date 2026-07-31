package io.github.jackbaozz.pocketbase.server.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Maps API-visible collection names to identifiers accepted by every supported relational database.
 * PocketBase allows collection names up to 255 characters, while PostgreSQL and MySQL limit
 * physical identifiers to 63 and 64 bytes.
 */
public final class PhysicalTableNames {
  private static final int EXTERNAL_IDENTIFIER_LIMIT = 63;
  private static final String TABLE_PREFIX = "__pb_";
  private static final String CONSTRAINT_PREFIX = "__pk_";

  private PhysicalTableNames() {
  }

  public static String tableName(JooqDatabase database, String logicalName) {
    if (logicalName == null
        || logicalName.isBlank()
        || database == null
        || database.engine() == JooqDatabase.Engine.SQLITE
        || logicalName.length() <= EXTERNAL_IDENTIFIER_LIMIT) {
      return logicalName;
    }
    return TABLE_PREFIX
        + sha256(logicalName).substring(0, EXTERNAL_IDENTIFIER_LIMIT - TABLE_PREFIX.length());
  }

  public static String primaryKeyName(JooqDatabase database, String tableName) {
    if (database == null || database.engine() == JooqDatabase.Engine.SQLITE) {
      return "pk_" + tableName;
    }
    String candidate = "pk_" + tableName;
    if (candidate.length() <= EXTERNAL_IDENTIFIER_LIMIT) {
      return candidate;
    }
    return CONSTRAINT_PREFIX
        + sha256(candidate).substring(0, EXTERNAL_IDENTIFIER_LIMIT - CONSTRAINT_PREFIX.length());
  }

  private static String sha256(String value) {
    try {
      byte[] bytes =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(bytes.length * 2);
      for (byte b : bytes) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
