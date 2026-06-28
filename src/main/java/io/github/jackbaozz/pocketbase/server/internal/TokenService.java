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
import java.util.function.Function;

public final class TokenService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper mapper;
    private final byte[] secret;

    public TokenService(ObjectMapper mapper, String secret) {
        this.mapper = mapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String create(Map<String, Object> claims, Duration ttl) {
        return create(claims, ttl, "");
    }

    public String create(Map<String, Object> claims, Duration ttl, String extraSecret) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> payload = new LinkedHashMap<>(claims);
        payload.put("iat", now);
        payload.put("exp", now + ttl.toSeconds());

        String head = encodeJson(header);
        String body = encodeJson(payload);
        String signature = sign(head + "." + body, extraSecret);
        return head + "." + body + "." + signature;
    }

    public Optional<Map<String, Object>> verify(String token) {
        return verify(token, claims -> "");
    }

    public Optional<Map<String, Object>> verify(String token, Function<Map<String, Object>, String> extraSecretResolver) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            Map<String, Object> claims = decodeClaims(parts[1]);
            String signingInput = parts[0] + "." + parts[1];
            String extraSecret = extraSecretResolver == null ? "" : String.valueOf(extraSecretResolver.apply(claims));
            if (!SecuritySupport.constantTimeEquals(sign(signingInput, extraSecret), parts[2])) {
                return Optional.empty();
            }
            Object exp = claims.get("exp");
            if (exp instanceof Number number && number.longValue() < Instant.now().getEpochSecond()) {
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Optional<Map<String, Object>> peek(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            return Optional.of(decodeClaims(parts[1]));
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

    private Map<String, Object> decodeClaims(String payloadPart) throws Exception {
        byte[] payload = Base64.getUrlDecoder().decode(payloadPart);
        return mapper.readValue(payload, MAP_TYPE);
    }

    private String sign(String value, String extraSecret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey(extraSecret), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("token signing failed", e);
        }
    }

    private byte[] signingKey(String extraSecret) {
        if (extraSecret == null || extraSecret.isBlank()) {
            return secret;
        }
        byte[] extra = extraSecret.getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[secret.length + 1 + extra.length];
        System.arraycopy(secret, 0, combined, 0, secret.length);
        combined[secret.length] = (byte) ':';
        System.arraycopy(extra, 0, combined, secret.length + 1, extra.length);
        return combined;
    }

}
