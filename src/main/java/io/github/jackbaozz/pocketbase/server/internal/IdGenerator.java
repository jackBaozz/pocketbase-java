package io.github.jackbaozz.pocketbase.server.internal;

import java.security.SecureRandom;

final class IdGenerator {
    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private IdGenerator() {
    }

    static String id() {
        return random(15);
    }

    static String prefixed(String prefix) {
        return prefix + random(12);
    }

    static String suffix() {
        return random(10);
    }

    private static String random(int length) {
        char[] value = new char[length];
        for (int i = 0; i < value.length; i++) {
            value[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(value);
    }
}
