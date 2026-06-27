package io.github.jackbaozz.pocketbase.server.internal;

import java.security.SecureRandom;

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

    private static String random(int length) {
        char[] value = new char[length];
        for (int i = 0; i < value.length; i++) {
            value[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(value);
    }
}
