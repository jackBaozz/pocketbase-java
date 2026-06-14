package io.github.jackbaozz.pocketbase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Root PocketBase HTTP client.
 */
public final class PocketBaseClient {
    private final URI baseUri;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AuthStore authStore;

    private PocketBaseClient(Builder builder) {
        this.baseUri = normalizeBaseUri(builder.baseUrl);
        this.httpClient = builder.httpClient != null ? builder.httpClient : defaultHttpClient();
        this.objectMapper = builder.objectMapper != null ? builder.objectMapper : defaultObjectMapper();
        this.authStore = builder.authStore != null ? builder.authStore : new AuthStore();
    }

    public static Builder builder(String baseUrl) {
        return new Builder(baseUrl);
    }

    public CollectionService collection(String collection) {
        return new CollectionService(this, collection);
    }

    public CollectionsService collections() {
        return new CollectionsService(this);
    }

    public FilesService files() {
        return new FilesService(this);
    }

    public AuthStore authStore() {
        return authStore;
    }

    public URI baseUri() {
        return baseUri;
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    String apiPath(String... segments) {
        StringJoiner joiner = new StringJoiner("/", "/api/", "");
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                throw new IllegalArgumentException("path segment must not be blank");
            }
            joiner.add(encodePathSegment(segment));
        }
        return joiner.toString();
    }

    <T> T send(String method, String path, Map<String, ?> query, Object body, Class<T> responseType) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(responseType, "responseType");

        URI uri = buildUri(path, query == null ? Map.of() : query);
        HttpRequest request = buildRequest(method, uri, body);
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw toException(method, uri, response.statusCode(), response.body());
            }
            if (responseType == Void.class || response.statusCode() == 204 || response.body() == null || response.body().isBlank()) {
                return null;
            }
            return objectMapper.readValue(response.body(), responseType);
        } catch (IOException e) {
            throw new PocketBaseException(method, uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PocketBaseException(method, uri, e);
        }
    }

    private HttpRequest buildRequest(String method, URI uri, Object body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", "pocketbase-java/0.1.0");

        authStore.token().ifPresent(token -> builder.header("Authorization", "Bearer " + token));

        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
            return builder.build();
        }

        builder.header("Content-Type", "application/json");
        builder.method(method, HttpRequest.BodyPublishers.ofString(writeJson(body), StandardCharsets.UTF_8));
        return builder.build();
    }

    private PocketBaseException toException(String method, URI uri, int statusCode, String responseBody) {
        PocketBaseError error = null;
        if (responseBody != null && !responseBody.isBlank()) {
            try {
                error = objectMapper.readValue(responseBody, PocketBaseError.class);
            } catch (JsonProcessingException ignored) {
                error = null;
            }
        }
        return new PocketBaseException(statusCode, method, uri, responseBody == null ? "" : responseBody, error);
    }

    private URI buildUri(String path, Map<String, ?> query) {
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        String queryString = encodeQuery(query);
        String suffix = queryString.isBlank() ? normalizedPath : normalizedPath + "?" + queryString;
        return baseUri.resolve(suffix);
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("body cannot be serialized to JSON", e);
        }
    }

    private static String encodeQuery(Map<String, ?> query) {
        Map<String, ?> safeQuery = query == null ? Map.of() : query;
        Map<String, String> flat = new LinkedHashMap<>();
        safeQuery.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                return;
            }
            String text = String.valueOf(value);
            if (!text.isBlank()) {
                flat.put(key, text);
            }
        });
        if (flat.isEmpty()) {
            return "";
        }

        StringJoiner joiner = new StringJoiner("&");
        flat.forEach((key, value) -> joiner.add(encodeQueryPart(key) + "=" + encodeQueryPart(value)));
        return joiner.toString();
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeQueryPart(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static URI normalizeBaseUri(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        String value = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        return URI.create(value);
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private static ObjectMapper defaultObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public static final class Builder {
        private final String baseUrl;
        private HttpClient httpClient;
        private ObjectMapper objectMapper;
        private AuthStore authStore;

        private Builder(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public Builder authStore(AuthStore authStore) {
            this.authStore = authStore;
            return this;
        }

        public Builder bearerToken(String token) {
            AuthStore store = this.authStore == null ? new AuthStore() : this.authStore;
            store.save(token, null);
            this.authStore = store;
            return this;
        }

        public PocketBaseClient build() {
            return new PocketBaseClient(this);
        }
    }
}
