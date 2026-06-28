package io.github.jackbaozz.pocketbase.server.internal;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class PasswordHasher {
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ParsedHash DUMMY_HASH = createDummyHash();

    private PasswordHasher() {
    }

    public static String hash(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("password must not be blank");
        }
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password.toCharArray(), salt, ITERATIONS);
        return "pbkdf2_sha256$" + ITERATIONS + "$" + encode(salt) + "$" + encode(hash);
    }

    public static boolean verify(String password, String encoded) {
        if (password == null) {
            return false;
        }
        ParsedHash parsed = parse(encoded);
        if (parsed == null) {
            return false;
        }
        return verifyParsed(password, parsed);
    }

    public static boolean verifyOrDummy(String password, String encoded) {
        ParsedHash parsed = parse(encoded);
        boolean matched = verifyParsed(password == null ? "" : password, parsed == null ? DUMMY_HASH : parsed);
        return parsed != null && matched;
    }

    private static boolean verifyParsed(String password, ParsedHash parsed) {
        try {
            byte[] actual = pbkdf2(password.toCharArray(), parsed.salt(), parsed.iterations());
            return MessageDigest.isEqual(parsed.expected(), actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static ParsedHash parse(String encoded) {
        if (encoded == null || !encoded.startsWith("pbkdf2_sha256$")) {
            return null;
        }
        String[] parts = encoded.split("\\$");
        if (parts.length != 4) {
            return null;
        }
        try {
            return new ParsedHash(
                    Integer.parseInt(parts[1]),
                    decode(parts[2]),
                    decode(parts[3])
            );
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ParsedHash createDummyHash() {
        byte[] salt = "pbj_dummy_salt__".getBytes(StandardCharsets.UTF_8);
        return new ParsedHash(
                ITERATIONS,
                salt,
                pbkdf2("pocketbase-java-dummy".toCharArray(), salt, ITERATIONS)
        );
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("password hashing failed", e);
        }
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private record ParsedHash(int iterations, byte[] salt, byte[] expected) {
    }
}
