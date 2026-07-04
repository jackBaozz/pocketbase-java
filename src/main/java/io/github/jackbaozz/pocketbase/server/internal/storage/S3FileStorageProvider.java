package io.github.jackbaozz.pocketbase.server.internal.storage;

import io.github.jackbaozz.pocketbase.server.spi.FileStorageProvider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

public class S3FileStorageProvider implements FileStorageProvider {
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_SCOPE = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HTTP_DATE = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("EEE, dd MMM yyyy HH:mm:ss zzz")
            .parseDefaulting(ChronoField.NANO_OF_SECOND, 0)
            .toFormatter(Locale.US);

    private final String endpoint;
    private final String region;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private final boolean forcePathStyle;
    private final HttpClient http;

    public S3FileStorageProvider(String endpoint, String region, String bucket, String accessKey, String secretKey) {
        this(endpoint, region, bucket, accessKey, secretKey, false);
    }

    public S3FileStorageProvider(String endpoint, String region, String bucket, String accessKey, String secretKey, boolean forcePathStyle) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.region = region == null || region.isBlank() ? "us-east-1" : region.trim();
        this.bucket = require(bucket, "bucket");
        this.accessKey = require(accessKey, "accessKey");
        this.secretKey = require(secretKey, "secret");
        this.forcePathStyle = forcePathStyle;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public void put(String key, InputStream stream, long length, String contentType) {
        try {
            if (length <= 0) {
                byte[] body = new byte[0];
                send("PUT", safeKey(key), "", body, contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType);
                return;
            }
            String objectKey = safeKey(key);
            Instant now = Instant.now();
            S3Target target = target(objectKey, "");
            String payloadHash = "UNSIGNED-PAYLOAD";

            Map<String, String> signedHeaders = new LinkedHashMap<>();
            signedHeaders.put("host", target.hostHeader());
            signedHeaders.put("x-amz-content-sha256", payloadHash);
            signedHeaders.put("x-amz-date", AMZ_DATE.format(now));

            String authorization = authorization("PUT", target, "", payloadHash, signedHeaders, now);
            HttpRequest.Builder builder = HttpRequest.newBuilder(target.uri())
                    .timeout(Duration.ofSeconds(600))
                    .header("x-amz-content-sha256", payloadHash)
                    .header("x-amz-date", signedHeaders.get("x-amz-date"))
                    .header("Authorization", authorization);
            if (contentType != null && !contentType.isBlank()) {
                builder.header("Content-Type", contentType);
            }
            builder.PUT(ofInputStream(() -> stream, length));

            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            Map<String, String> headers = new LinkedHashMap<>();
            response.headers().map().forEach((name, values) -> {
                if (!values.isEmpty()) {
                    headers.put(name.toLowerCase(Locale.ROOT), values.get(0));
                }
            });
            S3Response s3Response = new S3Response(response.statusCode(), headers, response.body() == null ? new byte[0] : response.body());
            require2xx(s3Response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to write S3 object: " + key, e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write S3 object: " + key, e);
        }
    }

    @Override
    public Optional<InputStream> get(String key) {
        try {
            String objectKey = safeKey(key);
            Instant now = Instant.now();
            S3Target target = target(objectKey, "");
            String payloadHash = sha256Hex(new byte[0]);

            Map<String, String> signedHeaders = new LinkedHashMap<>();
            signedHeaders.put("host", target.hostHeader());
            signedHeaders.put("x-amz-content-sha256", payloadHash);
            signedHeaders.put("x-amz-date", AMZ_DATE.format(now));

            String authorization = authorization("GET", target, "", payloadHash, signedHeaders, now);
            HttpRequest.Builder builder = HttpRequest.newBuilder(target.uri())
                    .timeout(Duration.ofSeconds(30))
                    .header("x-amz-content-sha256", payloadHash)
                    .header("x-amz-date", signedHeaders.get("x-amz-date"))
                    .header("Authorization", authorization)
                    .GET();

            HttpResponse<InputStream> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 404) {
                response.body().close();
                return Optional.empty();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (InputStream err = response.body()) {
                    String body = new String(err.readAllBytes(), StandardCharsets.UTF_8);
                    throw new IOException("S3 returned HTTP " + response.statusCode() + ": " + body);
                }
            }
            return Optional.of(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to read S3 object: " + key, e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read S3 object: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            S3Response response = send("DELETE", safeKey(key), "", new byte[0], null);
            if (response.status() != 404) {
                require2xx(response);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to delete S3 object: " + key, e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete S3 object: " + key, e);
        }
    }

    @Override
    public List<String> list(String prefix) {
        try {
            List<String> keys = new ArrayList<>();
            String continuation = "";
            do {
                String query = "list-type=2&prefix=" + awsEncode(prefix == null ? "" : prefix, false)
                        + (continuation.isBlank() ? "" : "&continuation-token=" + awsEncode(continuation, false));
                S3Response response = send("GET", "", query, new byte[0], null);
                require2xx(response);
                ListResult parsed = parseListResponse(response.body());
                keys.addAll(parsed.keys());
                continuation = parsed.nextContinuationToken();
            } while (!continuation.isBlank());
            return keys;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to list S3 objects: " + prefix, e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to list S3 objects: " + prefix, e);
        }
    }

    @Override
    public Optional<FileStat> stat(String key) {
        try {
            S3Response response = send("HEAD", safeKey(key), "", new byte[0], null);
            if (response.status() == 404) {
                return Optional.empty();
            }
            require2xx(response);
            long size = parseLong(response.headers().get("content-length"), -1);
            long modified = parseHttpDate(response.headers().get("last-modified"));
            String contentType = response.headers().getOrDefault("content-type", "application/octet-stream");
            return Optional.of(new FileStat(size, modified, contentType));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to stat S3 object: " + key, e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to stat S3 object: " + key, e);
        }
    }

    @Override
    public Optional<String> signedUrl(String key, long expirySeconds) {
        String objectKey = safeKey(key);
        long expires = Math.max(1, Math.min(expirySeconds <= 0 ? 300 : expirySeconds, 604_800));
        Instant now = Instant.now();
        S3Target target = target(objectKey, "");
        String date = DATE_SCOPE.format(now);
        String scope = date + "/" + region + "/s3/aws4_request";

        Map<String, String> params = new TreeMap<>();
        params.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        params.put("X-Amz-Credential", accessKey + "/" + scope);
        params.put("X-Amz-Date", AMZ_DATE.format(now));
        params.put("X-Amz-Expires", String.valueOf(expires));
        params.put("X-Amz-SignedHeaders", "host");
        String canonicalQuery = canonicalQuery(params);
        String canonicalRequest = "GET\n"
                + target.canonicalPath() + "\n"
                + canonicalQuery + "\n"
                + "host:" + target.hostHeader() + "\n\n"
                + "host\n"
                + "UNSIGNED-PAYLOAD";
        try {
            String stringToSign = "AWS4-HMAC-SHA256\n"
                    + AMZ_DATE.format(now) + "\n"
                    + scope + "\n"
                    + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            String signature = HexFormat.of().formatHex(hmac(signingKey(secretKey, date, region), stringToSign));
            return Optional.of(target.uri() + "?" + canonicalQuery + "&X-Amz-Signature=" + signature);
        } catch (IOException e) {
            throw new RuntimeException("Failed to sign S3 URL: " + key, e);
        }
    }

    private S3Response send(String method, String key, String canonicalQuery, byte[] body, String contentType)
            throws IOException, InterruptedException {
        byte[] payload = body == null ? new byte[0] : body;
        String payloadHash = sha256Hex(payload);
        Instant now = Instant.now();
        S3Target target = target(key, canonicalQuery);

        Map<String, String> signedHeaders = new LinkedHashMap<>();
        signedHeaders.put("host", target.hostHeader());
        signedHeaders.put("x-amz-content-sha256", payloadHash);
        signedHeaders.put("x-amz-date", AMZ_DATE.format(now));

        String authorization = authorization(method, target, canonicalQuery, payloadHash, signedHeaders, now);
        HttpRequest.Builder builder = HttpRequest.newBuilder(target.uri())
                .timeout(Duration.ofSeconds(30))
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", signedHeaders.get("x-amz-date"))
                .header("Authorization", authorization);
        if (contentType != null && !contentType.isBlank()) {
            builder.header("Content-Type", contentType);
        }
        if ("GET".equals(method) || "HEAD".equals(method) || "DELETE".equals(method)) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofByteArray(payload));
        }
        HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name.toLowerCase(Locale.ROOT), values.get(0));
            }
        });
        return new S3Response(response.statusCode(), headers, response.body() == null ? new byte[0] : response.body());
    }

    private String authorization(
            String method,
            S3Target target,
            String canonicalQuery,
            String payloadHash,
            Map<String, String> headers,
            Instant now
    ) throws IOException {
        String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
        String canonicalHeaders = "host:" + headers.get("host") + "\n"
                + "x-amz-content-sha256:" + headers.get("x-amz-content-sha256") + "\n"
                + "x-amz-date:" + headers.get("x-amz-date") + "\n";
        String canonicalRequest = method + "\n"
                + target.canonicalPath() + "\n"
                + (canonicalQuery == null ? "" : canonicalQuery) + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadHash;
        String date = DATE_SCOPE.format(now);
        String scope = date + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n"
                + AMZ_DATE.format(now) + "\n"
                + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        String signature = HexFormat.of().formatHex(hmac(signingKey(secretKey, date, region), stringToSign));
        return "AWS4-HMAC-SHA256 "
                + "Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;
    }

    private S3Target target(String key, String canonicalQuery) {
        URI endpointUri = endpoint.isBlank()
                ? URI.create("https://" + defaultHost(region))
                : URI.create(endpoint.contains("://") ? endpoint : "https://" + endpoint);
        String scheme = endpointUri.getScheme() == null ? "https" : endpointUri.getScheme();
        String endpointHost = endpointUri.getHost();
        if (endpointHost == null || endpointHost.isBlank()) {
            throw new IllegalArgumentException("Invalid S3 endpoint.");
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
        int port = endpointUri.getPort();
        String authority = host + (port > 0 ? ":" + port : "");
        String querySuffix = canonicalQuery == null || canonicalQuery.isBlank() ? "" : "?" + canonicalQuery;
        return new S3Target(URI.create(scheme + "://" + authority + path + querySuffix), path, authority);
    }

    private static void require2xx(S3Response response) throws IOException {
        if (response.status() < 200 || response.status() >= 300) {
            String body = new String(response.body(), StandardCharsets.UTF_8);
            throw new IOException(body.isBlank() ? "S3 returned HTTP " + response.status() : "S3 returned HTTP " + response.status() + ": " + body);
        }
    }

    private static ListResult parseListResponse(byte[] body) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
            List<String> keys = new ArrayList<>();
            var keyNodes = document.getElementsByTagName("Key");
            for (int i = 0; i < keyNodes.getLength(); i++) {
                keys.add(keyNodes.item(i).getTextContent());
            }
            String next = "";
            var nextNodes = document.getElementsByTagName("NextContinuationToken");
            if (nextNodes.getLength() > 0) {
                next = nextNodes.item(0).getTextContent();
            }
            return new ListResult(keys, next == null ? "" : next);
        } catch (Exception e) {
            throw new IOException("Failed to parse S3 list response.", e);
        }
    }

    private static String safeKey(String key) {
        if (key == null || key.isBlank() || key.startsWith("/") || key.startsWith("\\") || key.contains("..")) {
            throw new IllegalArgumentException("Invalid S3 key: " + key);
        }
        return key.replace('\\', '/');
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("S3 " + field + " is required.");
        }
        return value.trim();
    }

    private static String defaultHost(String region) {
        return "us-east-1".equals(region) ? "s3.amazonaws.com" : "s3." + region + ".amazonaws.com";
    }

    private static String joinPath(String basePath, String first, String second) {
        StringBuilder out = new StringBuilder();
        if (basePath != null && !basePath.isBlank() && !"/".equals(basePath)) {
            String path = basePath.startsWith("/") ? basePath : "/" + basePath;
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            out.append(path);
        }
        if (first != null && !first.isBlank()) {
            out.append('/').append(awsEncode(first, false));
        }
        if (second != null && !second.isBlank()) {
            out.append('/').append(awsEncode(second, true));
        }
        return out.isEmpty() ? "/" : out.toString();
    }

    private static String canonicalQuery(Map<String, String> params) {
        return params.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> awsEncode(entry.getKey(), false) + "=" + awsEncode(entry.getValue(), false))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
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
            throw new IOException("Failed to sign S3 request.", e);
        }
    }

    private static String sha256Hex(byte[] value) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IOException("Failed to hash S3 request.", e);
        }
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseHttpDate(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Instant.from(HTTP_DATE.parse(value)).toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static HttpRequest.BodyPublisher ofInputStream(Supplier<? extends InputStream> streamSupplier, long contentLength) {
        HttpRequest.BodyPublisher delegate = HttpRequest.BodyPublishers.ofInputStream(streamSupplier);
        return new HttpRequest.BodyPublisher() {
            @Override
            public long contentLength() {
                return contentLength;
            }

            @Override
            public void subscribe(java.util.concurrent.Flow.Subscriber<? super java.nio.ByteBuffer> subscriber) {
                delegate.subscribe(subscriber);
            }
        };
    }

    private record S3Target(URI uri, String canonicalPath, String hostHeader) {
    }

    private record S3Response(int status, Map<String, String> headers, byte[] body) {
    }

    private record ListResult(List<String> keys, String nextContinuationToken) {
    }
}
