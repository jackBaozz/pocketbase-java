package io.github.jackbaozz.pocketbase.server.internal;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.zip.CRC32;

public final class IdGenerator {
  private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
  private static final SecureRandom RANDOM = new SecureRandom();

  private IdGenerator() {
  }

  public static String id() {
    return random(15);
  }

  public static String prefixed(String prefix) {
    return prefix + random(12);
  }

  public static String suffix() {
    return random(10);
  }

  public static String digits(int length) {
    int size = Math.max(1, length);
    char[] value = new char[size];
    for (int i = 0; i < value.length; i++) {
      value[i] = (char) ('0' + RANDOM.nextInt(10));
    }
    return new String(value);
  }

  public static String secret() {
    return random(50);
  }

  public static String randomPassword() {
    return random(30);
  }

  public static String collectionId(String type, String name) {
    return "pbc_" + crc32(text(type) + text(name));
  }

  public static String fieldId(String type, String name) {
    return text(type) + crc32(text(name));
  }

  private static long crc32(String value) {
    CRC32 checksum = new CRC32();
    checksum.update(value.getBytes(StandardCharsets.UTF_8));
    return checksum.getValue();
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }

  private static String random(int length) {
    char[] value = new char[length];
    for (int i = 0; i < value.length; i++) {
      value[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
    }
    return new String(value);
  }
}
