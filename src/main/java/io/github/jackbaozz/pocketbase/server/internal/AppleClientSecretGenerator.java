package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AppleClientSecretGenerator {
    private static final int MAX_DURATION_SECONDS = 15_777_000;
    private static final String APPLE_AUDIENCE = "https://appleid.apple.com";

    private AppleClientSecretGenerator() {
    }

    public static Map<String, Object> generate(ObjectMapper mapper, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ApiException(400, "Invalid client secret data.",
                    ApiErrors.invalidField("body", "Request body must be a JSON object."));
        }

        String clientId = requiredText(body, "clientId");
        String teamId = requiredText(body, "teamId");
        String keyId = requiredText(body, "keyId");
        String privateKey = requiredText(body, "privateKey");
        int duration = requiredInt(body, "duration");

        if (teamId.length() != 10) {
            throw invalidField("teamId", "Must be exactly 10 characters.");
        }
        if (keyId.length() != 10) {
            throw invalidField("keyId", "Must be exactly 10 characters.");
        }
        if (duration < 1 || duration > MAX_DURATION_SECONDS) {
            throw invalidField("duration", "Must be between 1 and 15777000 seconds.");
        }
        if (!privateKey.contains("-----BEGIN PRIVATE KEY-----")
                || !privateKey.contains("-----END PRIVATE KEY-----")) {
            throw invalidField("privateKey", "Must be a PKCS#8 EC private key PEM.");
        }

        try {
            ECPrivateKey key = parsePrivateKey(privateKey);
            long now = Instant.now().getEpochSecond();

            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "ES256");
            header.put("kid", keyId);
            header.put("typ", "JWT");

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("iss", teamId);
            payload.put("iat", now);
            payload.put("exp", now + duration);
            payload.put("aud", APPLE_AUDIENCE);
            payload.put("sub", clientId);

            String signingInput = encodeJson(mapper, header) + "." + encodeJson(mapper, payload);
            byte[] signature = sign(key, signingInput.getBytes(StandardCharsets.UTF_8));
            String token = signingInput + "." + base64Url(signature);
            return Map.of("secret", token);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw invalidField("privateKey", "Must be a valid PKCS#8 EC private key PEM.");
        }
    }

    private static String requiredText(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull() || value.asText("").isBlank()) {
            throw requiredField(field);
        }
        return value.asText().trim();
    }

    private static int requiredInt(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            throw requiredField(field);
        }
        if (value.canConvertToInt()) {
            return value.asInt();
        }
        try {
            return Integer.parseInt(value.asText("").trim());
        } catch (NumberFormatException e) {
            throw invalidField(field, "Must be a valid number.");
        }
    }

    private static ApiException requiredField(String field) {
        return new ApiException(400, "Invalid client secret data.", ApiErrors.requiredField(field));
    }

    private static ApiException invalidField(String field, String message) {
        return new ApiException(400, "Invalid client secret data.", ApiErrors.invalidField(field, message));
    }

    private static ECPrivateKey parsePrivateKey(String pem) throws Exception {
        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(base64);
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        if (!(key instanceof ECPrivateKey ecPrivateKey)) {
            throw new IllegalArgumentException("private key is not an EC key");
        }
        return ecPrivateKey;
    }

    private static byte[] sign(ECPrivateKey key, byte[] payload) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(key);
        signature.update(payload);
        return derToJose(signature.sign());
    }

    private static String encodeJson(ObjectMapper mapper, Object value) throws Exception {
        return base64Url(mapper.writeValueAsBytes(value));
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] derToJose(byte[] der) {
        int[] pos = {0};
        if (readByte(der, pos) != 0x30) {
            throw new IllegalArgumentException("invalid ECDSA signature");
        }
        readLength(der, pos);
        byte[] r = readInteger(der, pos);
        byte[] s = readInteger(der, pos);

        byte[] out = new byte[64];
        copyInteger(r, out, 0);
        copyInteger(s, out, 32);
        return out;
    }

    private static byte[] readInteger(byte[] der, int[] pos) {
        if (readByte(der, pos) != 0x02) {
            throw new IllegalArgumentException("invalid ECDSA signature integer");
        }
        int length = readLength(der, pos);
        if (length < 1 || pos[0] + length > der.length) {
            throw new IllegalArgumentException("invalid ECDSA signature length");
        }
        byte[] value = new byte[length];
        System.arraycopy(der, pos[0], value, 0, length);
        pos[0] += length;
        return value;
    }

    private static void copyInteger(byte[] value, byte[] out, int offset) {
        int start = 0;
        while (start < value.length - 1 && value[start] == 0) {
            start++;
        }
        int length = value.length - start;
        if (length > 32) {
            throw new IllegalArgumentException("ECDSA signature integer is too large");
        }
        System.arraycopy(value, start, out, offset + 32 - length, length);
    }

    private static int readByte(byte[] bytes, int[] pos) {
        if (pos[0] >= bytes.length) {
            throw new IllegalArgumentException("unexpected end of ECDSA signature");
        }
        return bytes[pos[0]++] & 0xff;
    }

    private static int readLength(byte[] bytes, int[] pos) {
        int first = readByte(bytes, pos);
        if (first < 128) {
            return first;
        }
        int count = first & 0x7f;
        if (count < 1 || count > 4) {
            throw new IllegalArgumentException("invalid DER length");
        }
        int length = 0;
        for (int i = 0; i < count; i++) {
            length = (length << 8) | readByte(bytes, pos);
        }
        return length;
    }
}
