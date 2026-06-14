package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class TokenService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper mapper;
    private final byte[] secret;

    TokenService(ObjectMapper mapper, String secret) {
        this.mapper = mapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    String create(Map<String, Object> claims, Duration ttl) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> payload = new LinkedHashMap<>(claims);
        payload.put("iat", now);
        payload.put("exp", now + ttl.toSeconds());

        String head = encodeJson(header);
        String body = encodeJson(payload);
        String signature = sign(head + "." + body);
        return head + "." + body + "." + signature;
    }

    Optional<Map<String, Object>> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        String signingInput = parts[0] + "." + parts[1];
        if (!MessageDigestSupport.constantTimeEquals(sign(signingInput), parts[2])) {
            return Optional.empty();
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            Map<String, Object> claims = mapper.readValue(payload, MAP_TYPE);
            Object exp = claims.get("exp");
            if (exp instanceof Number number && number.longValue() < Instant.now().getEpochSecond()) {
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String encodeJson(Object value) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mapper.writeValueAsBytes(value));
        } catch (Exception e) {
            throw new IllegalStateException("token serialization failed", e);
        }
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("token signing failed", e);
        }
    }

    private static final class MessageDigestSupport {
        private MessageDigestSupport() {
        }

        static boolean constantTimeEquals(String expected, String actual) {
            if (expected == null || actual == null) {
                return false;
            }
            byte[] left = expected.getBytes(StandardCharsets.UTF_8);
            byte[] right = actual.getBytes(StandardCharsets.UTF_8);
            if (left.length != right.length) {
                return false;
            }
            int result = 0;
            for (int i = 0; i < left.length; i++) {
                result |= left[i] ^ right[i];
            }
            return result == 0;
        }
    }
}
