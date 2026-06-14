package io.github.jackbaozz.pocketbase.server.internal;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class S3Probe {
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_SCOPE = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private S3Probe() {
    }

    static void test(String filesystem, Map<String, Object> config) {
        String target = filesystem == null || filesystem.isBlank() ? "storage" : filesystem;
        if (!"storage".equals(target) && !"backups".equals(target)) {
            throw validationError("filesystem", "Must be either storage or backups.");
        }
        if (!truthy(config.get("enabled"))) {
            throw rawError("S3 " + target + " filesystem is not enabled");
        }

        S3Config s3 = S3Config.from(config);
        String prefix = "pb_settings_test_" + IdGenerator.suffix();
        String key = prefix + "/test.txt";
        byte[] body = "test".getBytes(StandardCharsets.UTF_8);

        try {
            send("PUT", s3, key, "", body);
            send("GET", s3, "", "list-type=2&max-keys=1&prefix=" + awsEncode(prefix + "/", false), new byte[0]);
            send("DELETE", s3, key, "", new byte[0]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw rawError("interrupted while testing S3 filesystem");
        } catch (Exception e) {
            try {
                send("DELETE", s3, key, "", new byte[0]);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
            throw rawError(e.getMessage());
        }
    }

    private static void send(String method, S3Config s3, String key, String canonicalQuery, byte[] body)
            throws IOException, InterruptedException {
        byte[] payload = body == null ? new byte[0] : body;
        String payloadHash = sha256Hex(payload);
        Instant now = Instant.now();
        S3Target target = s3.target(key, canonicalQuery);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("host", target.hostHeader());
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", AMZ_DATE.format(now));

        String authorization = authorization(method, target, canonicalQuery, payloadHash, headers, s3, now);
        HttpRequest.Builder builder = HttpRequest.newBuilder(target.uri())
                .timeout(Duration.ofSeconds(15))
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", headers.get("x-amz-date"))
                .header("Authorization", authorization)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(payload));
        if (payload.length > 0) {
            builder.header("Content-Type", "text/plain; charset=utf-8");
        }

        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = response.body() == null || response.body().isBlank()
                    ? "S3 returned HTTP " + response.statusCode()
                    : "S3 returned HTTP " + response.statusCode() + ": " + response.body();
            throw new IOException(message);
        }
    }

    private static String authorization(
            String method,
            S3Target target,
            String canonicalQuery,
            String payloadHash,
            Map<String, String> headers,
            S3Config s3,
            Instant now
    ) throws IOException {
        String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
        String canonicalHeaders = "host:" + headers.get("host") + "\n"
                + "x-amz-content-sha256:" + headers.get("x-amz-content-sha256") + "\n"
                + "x-amz-date:" + headers.get("x-amz-date") + "\n";
        String canonicalRequest = method + "\n"
                + target.canonicalPath() + "\n"
                + canonicalQuery + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadHash;

        String date = DATE_SCOPE.format(now);
        String scope = date + "/" + s3.region() + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n"
                + AMZ_DATE.format(now) + "\n"
                + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] signature = hmac(signingKey(s3.secret(), date, s3.region()), stringToSign);
        return "AWS4-HMAC-SHA256 "
                + "Credential=" + s3.accessKey() + "/" + scope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + HexFormat.of().formatHex(signature);
    }

    private static byte[] signingKey(String secret, String date, String region) throws IOException {
        byte[] kDate = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, "s3");
        return hmac(kService, "aws4_request");
    }

    private static byte[] hmac(byte[] key, String value) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IOException("failed to sign S3 request", e);
        }
    }

    private static String sha256Hex(byte[] value) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IOException("failed to hash S3 request", e);
        }
    }

    private static String awsEncode(String value, boolean slashAllowed) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append((char) c);
            } else if (slashAllowed && c == '/') {
                out.append('/');
            } else {
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit((c >>> 4) & 0xf, 16)));
                out.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && switch (String.valueOf(value).trim().toLowerCase(Locale.ROOT)) {
            case "", "0", "false", "no" -> false;
            default -> true;
        };
    }

    private static String text(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int integer(Map<String, Object> source, String key, int fallback) {
        Object value = source.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static ApiException validationError(String field, String message) {
        return new ApiException(400, "Failed to test the S3 filesystem.", Map.of(field, Map.of("message", message)));
    }

    private static ApiException rawError(String message) {
        return new ApiException(400, "Failed to test the S3 filesystem. Raw error: \n" + message);
    }

    private record S3Config(
            String bucket,
            String region,
            String endpoint,
            String accessKey,
            String secret,
            boolean forcePathStyle
    ) {
        static S3Config from(Map<String, Object> config) {
            String bucket = text(config, "bucket");
            String region = text(config, "region");
            String accessKey = text(config, "accessKey");
            String secret = text(config, "secret");
            if (bucket.isBlank()) {
                throw validationError("bucket", "Bucket is required.");
            }
            if (region.isBlank()) {
                throw validationError("region", "Region is required.");
            }
            if (accessKey.isBlank()) {
                throw validationError("accessKey", "Access key is required.");
            }
            if (secret.isBlank()) {
                throw validationError("secret", "Secret is required.");
            }
            return new S3Config(
                    bucket,
                    region,
                    text(config, "endpoint"),
                    accessKey,
                    secret,
                    truthy(config.get("forcePathStyle"))
            );
        }

        S3Target target(String key, String canonicalQuery) {
            URI endpointUri = endpoint.isBlank()
                    ? URI.create("https://" + defaultHost(region))
                    : URI.create(endpoint.contains("://") ? endpoint : "https://" + endpoint);
            String scheme = endpointUri.getScheme() == null ? "https" : endpointUri.getScheme();
            String endpointHost = endpointUri.getHost();
            if (endpointHost == null || endpointHost.isBlank()) {
                throw rawError("invalid S3 endpoint");
            }

            String host = endpointHost;
            String basePath = endpointUri.getRawPath() == null ? "" : endpointUri.getRawPath();
            String path;
            if (forcePathStyle) {
                path = joinPath(basePath, bucket, key);
            } else {
                host = bucket + "." + endpointHost;
                path = joinPath(basePath, "", key);
            }
            int port = integer(Map.of("port", endpointUri.getPort()), "port", -1);
            String authority = host + (port > 0 ? ":" + port : "");
            String querySuffix = canonicalQuery == null || canonicalQuery.isBlank() ? "" : "?" + canonicalQuery;
            return new S3Target(
                    URI.create(scheme + "://" + authority + path + querySuffix),
                    path,
                    authority
            );
        }

        private static String defaultHost(String region) {
            return "us-east-1".equals(region) ? "s3.amazonaws.com" : "s3." + region + ".amazonaws.com";
        }

        private static String joinPath(String basePath, String first, String second) {
            StringBuilder out = new StringBuilder();
            if (basePath != null && !basePath.isBlank() && !"/".equals(basePath)) {
                out.append(basePath.startsWith("/") ? basePath : "/" + basePath);
            }
            if (first != null && !first.isBlank()) {
                out.append('/').append(awsEncode(first, false));
            }
            if (second != null && !second.isBlank()) {
                out.append('/').append(awsEncode(second, true));
            }
            return out.isEmpty() ? "/" : out.toString();
        }
    }

    private record S3Target(URI uri, String canonicalPath, String hostHeader) {
    }
}
