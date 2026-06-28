package io.github.jackbaozz.pocketbase.server.internal;

import java.nio.charset.StandardCharsets;

public final class SecuritySupport {
    private SecuritySupport() {
    }

    public static boolean constantTimeEquals(String left, String right) {
        byte[] expected = left == null ? new byte[0] : left.getBytes(StandardCharsets.UTF_8);
        byte[] actual = right == null ? new byte[0] : right.getBytes(StandardCharsets.UTF_8);
        int max = Math.max(expected.length, actual.length);
        int result = expected.length ^ actual.length;
        for (int i = 0; i < max; i++) {
            byte leftByte = i < expected.length ? expected[i] : 0;
            byte rightByte = i < actual.length ? actual[i] : 0;
            result |= leftByte ^ rightByte;
        }
        return result == 0;
    }
}
