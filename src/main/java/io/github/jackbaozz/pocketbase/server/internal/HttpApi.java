package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP surface that mirrors the most common PocketBase API and serves the admin UI.
 */
public final class HttpApi implements HttpHandler {
    private final JsonFileStore store;
    private final RealtimeHub realtimeHub;

    public HttpApi(JsonFileStore store, RealtimeHub realtimeHub) {
        this.store = store;
        this.realtimeHub = realtimeHub;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = normalizePath(exchange.getRequestURI().getPath());
        int status = 0;
        long started = System.nanoTime();
        try {
            addCommonHeaders(exchange);
            if ("OPTIONS".equals(method)) {
                status = 204;
                sendNoContent(exchange);
                return;
            }

            if (path.equals("/") || path.equals("/_/") || path.startsWith("/_/")) {
                serveAdmin(exchange, path);
                status = 200;
                return;
            }
            if (path.startsWith("/api/backups/") && "GET".equals(method)) {
                serveBackup(exchange, path);
                status = 200;
                return;
            }
            if (path.startsWith("/api/files/") && !path.equals("/api/files/token")) {
                serveFile(exchange, path);
                status = 200;
                return;
            }
            if (path.equals("/api/realtime") && "GET".equals(method)) {
                realtimeHub.connect(exchange);
                return;
            }
            if (path.equals("/ping") && "GET".equals(method)) {
                status = 200;
                sendBytes(exchange, 200, "pong".getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
                return;
            }
            if (path.startsWith("/api/")) {
                Object response = routeApi(exchange, path);
                if (response == NoContent.INSTANCE) {
                    status = 204;
                    sendNoContent(exchange);
                } else if (response instanceof HtmlResponse html) {
                    status = html.status();
                    sendBytes(exchange, html.status(), html.body(), html.contentType());
                } else {
                    status = 200;
                    sendJson(exchange, 200, response);
                }
                return;
            }
            throw new ApiException(404, "Not found.");
        } catch (ApiException e) {
            status = e.status();
            sendJson(exchange, e.status(), errorBody(e.status(), e.getMessage(), e.data()));
        } catch (IllegalArgumentException e) {
            status = 400;
            sendJson(exchange, 400, errorBody(400, e.getMessage(), Map.of()));
        } catch (Exception e) {
            status = 500;
            sendJson(exchange, 500, errorBody(500, "Internal server error.", Map.of("error", e.getMessage())));
        } finally {
            if (shouldLogActivity(path, method, status)) {
                try {
                    store.recordActivityLog(
                            method,
                            activityUrl(exchange),
                            status,
                            Math.max(0L, (System.nanoTime() - started) / 1_000_000L),
                            principal(exchange).orElse(null),
                            requestHeaders(exchange),
                            remoteAddress(exchange)
                    );
                } catch (RuntimeException ignored) {
                    // Activity logging must never change the API response.
                }
            }
            exchange.close();
        }
    }

    private Object routeApi(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod();
        List<String> segments = segments(path);
        Map<String, String> query = query(exchange);

        if (segments.size() == 2 && "health".equals(segments.get(1)) && "GET".equals(method)) {
            return store.health();
        }
        if (segments.size() == 2 && "realtime".equals(segments.get(1)) && "POST".equals(method)) {
            return subscribeRealtime(exchange, query);
        }
        if (segments.size() == 2 && "batch".equals(segments.get(1)) && "POST".equals(method)) {
            return handleBatch(exchange, principal(exchange).orElse(null));
        }
        if (segments.size() == 2 && "sql".equals(segments.get(1)) && "POST".equals(method)) {
            RequestPrincipal principal = principal(exchange).orElse(null);
            requireSuperuser(principal);
            return store.runSql(readJson(exchange));
        }
        if (segments.size() == 2 && "oauth2-redirect".equals(segments.get(1))
                && ("GET".equals(method) || "POST".equals(method))) {
            return oauth2RedirectPage(exchange);
        }
        if (segments.size() >= 2 && "settings".equals(segments.get(1))) {
            RequestPrincipal principal = principal(exchange).orElse(null);
            requireSuperuser(principal);
            if (segments.size() == 2) {
                return switch (method) {
                    case "GET" -> store.getSettings(query);
                    case "PATCH" -> store.updateSettings(readRecordInput(exchange).body(), query);
                    default -> throw new ApiException(405, "Method not allowed.");
                };
            }
            if (segments.size() == 4 && "test".equals(segments.get(2)) && "s3".equals(segments.get(3))
                    && "POST".equals(method)) {
                store.testS3(readRecordInput(exchange).body());
                return NoContent.INSTANCE;
            }
            if (segments.size() == 4 && "test".equals(segments.get(2)) && "email".equals(segments.get(3))
                    && "POST".equals(method)) {
                store.testEmail(readRecordInput(exchange).body());
                return NoContent.INSTANCE;
            }
            if (segments.size() == 4 && "apple".equals(segments.get(2)) && "generate-client-secret".equals(segments.get(3))
                    && "POST".equals(method)) {
                return store.generateAppleClientSecret(readRecordInput(exchange).body());
            }
            throw new ApiException(404, "Not found.");
        }
        if (segments.size() >= 2 && "logs".equals(segments.get(1))) {
            RequestPrincipal principal = principal(exchange).orElse(null);
            requireSuperuser(principal);
            if (segments.size() == 2 && "GET".equals(method)) {
                return store.listLogs(query);
            }
            if (segments.size() == 3 && "stats".equals(segments.get(2)) && "GET".equals(method)) {
                return store.logStats(query);
            }
            if (segments.size() == 3 && "GET".equals(method)) {
                return store.getLog(segments.get(2), query);
            }
            throw new ApiException(404, "Not found.");
        }
        if (segments.size() >= 2 && "crons".equals(segments.get(1))) {
            RequestPrincipal principal = principal(exchange).orElse(null);
            requireSuperuser(principal);
            if (segments.size() == 2 && "GET".equals(method)) {
                return store.listCrons();
            }
            if (segments.size() == 3 && "POST".equals(method)) {
                store.runCron(segments.get(2));
                return NoContent.INSTANCE;
            }
            throw new ApiException(404, "Not found.");
        }
        if (segments.size() == 3 && "files".equals(segments.get(1)) && "token".equals(segments.get(2))
                && "POST".equals(method)) {
            return store.fileToken(principal(exchange).orElse(null));
        }
        if (segments.size() >= 2 && "backups".equals(segments.get(1))) {
            RequestPrincipal principal = principal(exchange).orElse(null);
            requireSuperuser(principal);
            if (segments.size() == 2) {
                return switch (method) {
                    case "GET" -> store.listBackups(parsePositive(query.get("page"), 1), parsePositive(query.get("perPage"), 100));
                    case "POST" -> createOrUploadBackup(exchange);
                    default -> throw new ApiException(405, "Method not allowed.");
                };
            }
            if (segments.size() == 3 && "upload".equals(segments.get(2)) && "POST".equals(method)) {
                return uploadBackup(exchange);
            }
            if (segments.size() == 3 && "DELETE".equals(method)) {
                store.deleteBackup(segments.get(2));
                return NoContent.INSTANCE;
            }
            if (segments.size() == 4 && "restore".equals(segments.get(3)) && "POST".equals(method)) {
                return store.restoreBackup(segments.get(2));
            }
            throw new ApiException(404, "Not found.");
        }
        if (segments.size() == 3 && "bootstrap".equals(segments.get(1)) && "superuser".equals(segments.get(2))
                && "POST".equals(method)) {
            return store.bootstrapSuperuser(readJson(exchange));
        }
        if (segments.size() == 3 && "admins".equals(segments.get(1)) && "auth-with-password".equals(segments.get(2))
                && "POST".equals(method)) {
            return store.authWithPassword(JsonFileStore.SUPERUSERS, readJson(exchange), query);
        }
        if (segments.size() < 2 || !"collections".equals(segments.get(1))) {
            throw new ApiException(404, "Not found.");
        }

        RequestPrincipal principal = principal(exchange).orElse(null);
        if (segments.size() == 2) {
            return switch (method) {
                case "GET" -> {
                    requireSuperuser(principal);
                    yield store.listCollections(query);
                }
                case "POST" -> {
                    requireSuperuser(principal);
                    yield store.createCollection(readJson(exchange));
                }
                default -> throw new ApiException(405, "Method not allowed.");
            };
        }

        if (segments.size() == 3 && "import".equals(segments.get(2)) && "PUT".equals(method)) {
            requireSuperuser(principal);
            store.importCollections(readJson(exchange));
            return NoContent.INSTANCE;
        }
        if (segments.size() == 4 && "meta".equals(segments.get(2)) && "scaffolds".equals(segments.get(3))
                && "GET".equals(method)) {
            requireSuperuser(principal);
            return store.collectionScaffolds();
        }
        if (segments.size() == 4 && "meta".equals(segments.get(2)) && "dry-run-view".equals(segments.get(3))
                && "POST".equals(method)) {
            requireSuperuser(principal);
            return store.dryRunView(readJson(exchange));
        }
        if (segments.size() == 4 && "meta".equals(segments.get(2)) && "oauth2-providers".equals(segments.get(3))
                && "GET".equals(method)) {
            requireSuperuser(principal);
            return store.oauth2ProviderMetadata();
        }

        String collection = segments.get(2);
        if (segments.size() == 3) {
            return switch (method) {
                case "GET" -> {
                    requireSuperuser(principal);
                    yield store.getCollection(collection, query);
                }
                case "PATCH" -> {
                    requireSuperuser(principal);
                    yield store.updateCollection(collection, readJson(exchange));
                }
                case "DELETE" -> {
                    requireSuperuser(principal);
                    store.deleteCollection(collection);
                    yield NoContent.INSTANCE;
                }
                default -> throw new ApiException(405, "Method not allowed.");
            };
        }

        String action = segments.get(3);
        if (segments.size() == 4 && "truncate".equals(action) && "DELETE".equals(method)) {
            requireSuperuser(principal);
            store.truncateCollection(collection);
            return NoContent.INSTANCE;
        }
        if (segments.size() == 4 && "auth-with-password".equals(action) && "POST".equals(method)) {
            return store.authWithPassword(collection, readJson(exchange), query);
        }
        if (segments.size() == 4 && "request-otp".equals(action) && "POST".equals(method)) {
            return store.requestOtp(collection, readJson(exchange));
        }
        if (segments.size() == 4 && "auth-with-otp".equals(action) && "POST".equals(method)) {
            return store.authWithOtp(collection, readJson(exchange), query);
        }
        if (segments.size() == 4 && "auth-with-oauth2".equals(action) && "POST".equals(method)) {
            return store.authWithOAuth2(collection, readJson(exchange), query, principal);
        }
        if (segments.size() == 4 && "auth-refresh".equals(action) && "POST".equals(method)) {
            return store.authRefresh(collection, principal, query);
        }
        if (segments.size() == 4 && "auth-methods".equals(action) && "GET".equals(method)) {
            return store.authMethods(collection);
        }
        if (segments.size() == 4 && "request-password-reset".equals(action) && "POST".equals(method)) {
            store.requestPasswordReset(collection, readJson(exchange));
            return NoContent.INSTANCE;
        }
        if (segments.size() == 4 && "confirm-password-reset".equals(action) && "POST".equals(method)) {
            store.confirmPasswordReset(collection, readJson(exchange));
            return NoContent.INSTANCE;
        }
        if (segments.size() == 4 && "request-verification".equals(action) && "POST".equals(method)) {
            store.requestVerification(collection, readJson(exchange));
            return NoContent.INSTANCE;
        }
        if (segments.size() == 4 && "confirm-verification".equals(action) && "POST".equals(method)) {
            store.confirmVerification(collection, readJson(exchange));
            return NoContent.INSTANCE;
        }
        if (segments.size() == 4 && "request-email-change".equals(action) && "POST".equals(method)) {
            store.requestEmailChange(collection, readJson(exchange), principal);
            return NoContent.INSTANCE;
        }
        if (segments.size() == 4 && "confirm-email-change".equals(action) && "POST".equals(method)) {
            store.confirmEmailChange(collection, readJson(exchange));
            return NoContent.INSTANCE;
        }
        if (segments.size() == 5 && "impersonate".equals(action) && "POST".equals(method)) {
            requireSuperuser(principal);
            return store.impersonate(collection, segments.get(4), readJson(exchange), query);
        }
        if (!"records".equals(action)) {
            throw new ApiException(404, "Not found.");
        }

        if (JsonFileStore.SUPERUSERS.equals(collection) && !"POST".equals(method)) {
            requireSuperuser(principal);
        }
        if (JsonFileStore.SUPERUSERS.equals(collection) && "POST".equals(method)) {
            requireSuperuser(principal);
        }

        if (segments.size() == 4) {
            return switch (method) {
                case "GET" -> store.listRecords(collection, query, principal);
                case "POST" -> {
                    RecordInput input = readRecordInput(exchange);
                    yield store.createRecord(collection, input.body(), input.files(), query, principal);
                }
                default -> throw new ApiException(405, "Method not allowed.");
            };
        }

        if (segments.size() == 5) {
            String id = segments.get(4);
            return switch (method) {
                case "GET" -> store.getRecord(collection, id, query, principal);
                case "PATCH" -> {
                    RecordInput input = readRecordInput(exchange);
                    yield store.updateRecord(collection, id, input.body(), input.files(), query, principal);
                }
                case "DELETE" -> {
                    store.deleteRecord(collection, id, principal);
                    yield NoContent.INSTANCE;
                }
                default -> throw new ApiException(405, "Method not allowed.");
            };
        }

        throw new ApiException(404, "Not found.");
    }

    private Object subscribeRealtime(HttpExchange exchange, Map<String, String> query) throws IOException {
        RequestPrincipal principal = principal(exchange).orElse(null);
        RealtimeSubscriptionInput input = readRealtimeSubscriptionInput(exchange, query);
        realtimeHub.subscribe(input.clientId(), input.subscriptions(), input.options(), principal);
        return NoContent.INSTANCE;
    }

    private RealtimeSubscriptionInput readRealtimeSubscriptionInput(
            HttpExchange exchange,
            Map<String, String> query
    ) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> subscriptions = new ArrayList<>();
        collectRealtimeValues(query, values, subscriptions);

        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        try (InputStream input = exchange.getRequestBody()) {
            byte[] bytes = input.readAllBytes();
            if (isMultipart(contentType)) {
                MultipartFormData multipart = MultipartFormData.parse(contentType, bytes, store.mapper());
                collectRealtimeJson(multipart.fields(), values, subscriptions);
            } else if (isFormUrlEncoded(contentType)) {
                collectRealtimeValues(query(new String(bytes, StandardCharsets.UTF_8)), values, subscriptions);
            } else if (bytes.length > 0) {
                collectRealtimeJson(store.mapper().readTree(bytes), values, subscriptions);
            }
        }

        String clientId = values.get("clientId");
        if (clientId == null || clientId.isBlank()) {
            throw new ApiException(400, "clientId is required.");
        }

        Map<String, String> requestQuery = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!"clientId".equals(key) && !"options".equals(key) && value != null) {
                requestQuery.put(key, value);
            }
        });
        RealtimeHub.SubscriptionOptions parsedOptions = realtimeHub.parseSubscriptionOptions(values.get("options"));
        requestQuery.putAll(parsedOptions.query());
        RealtimeHub.SubscriptionOptions options = new RealtimeHub.SubscriptionOptions(
                Map.copyOf(requestQuery),
                parsedOptions.headers()
        );
        return new RealtimeSubscriptionInput(clientId, List.copyOf(subscriptions), options);
    }

    private void collectRealtimeValues(
            Map<String, String> input,
            Map<String, String> values,
            List<String> subscriptions
    ) {
        input.forEach((key, value) -> {
            if (isSubscriptionField(key)) {
                addSubscription(value, subscriptions);
            } else {
                values.put(key, value);
            }
        });
    }

    private void collectRealtimeJson(
            JsonNode body,
            Map<String, String> values,
            List<String> subscriptions
    ) throws IOException {
        if (body == null || body.isNull()) {
            return;
        }
        if (!body.isObject()) {
            throw new ApiException(400, "Realtime subscription payload must be an object.");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = body.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (isSubscriptionField(key)) {
                addJsonSubscription(value, subscriptions);
            } else if (value != null && !value.isNull()) {
                values.put(key, jsonText(value));
            }
        }
    }

    private void addJsonSubscription(JsonNode value, List<String> subscriptions) {
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isArray()) {
            value.forEach(item -> addSubscription(item.asText(), subscriptions));
            return;
        }
        addSubscription(value.asText(), subscriptions);
    }

    private void addSubscription(String value, List<String> subscriptions) {
        if (value != null && !value.isBlank()) {
            subscriptions.add(value);
        }
    }

    private boolean isSubscriptionField(String key) {
        return "subscriptions".equals(key) || "subscriptions[]".equals(key)
                || (key != null && key.startsWith("subscriptions[") && key.endsWith("]"));
    }

    private String jsonText(JsonNode value) throws IOException {
        if (value.isValueNode()) {
            return value.asText("");
        }
        return store.mapper().writeValueAsString(value);
    }

    private Object handleBatch(HttpExchange exchange, RequestPrincipal principal) throws IOException {
        BatchInput input = readBatchInput(exchange);
        JsonNode body = input.body();
        JsonNode requestsNode = body.isArray() ? body : body.get("requests");
        if (requestsNode == null || !requestsNode.isArray()) {
            throw new ApiException(400, "Batch payload must contain a requests array.");
        }
        return store.transactional(() -> {
            List<Map<String, Object>> responses = new ArrayList<>();
            int index = 0;
            for (JsonNode request : requestsNode) {
                try {
                    responses.add(handleBatchRequest(request, input.filesFor(index), principal));
                } catch (ApiException e) {
                    throw new ApiException(400, "Batch request failed.", Map.of(
                            "index", index,
                            "status", e.status(),
                            "message", e.getMessage(),
                            "data", e.data()
                    ));
                }
                index++;
            }
            return Map.of("responses", responses);
        });
    }

    private BatchInput readBatchInput(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        try (InputStream input = exchange.getRequestBody()) {
            byte[] bytes = input.readAllBytes();
            if (isMultipart(contentType)) {
                MultipartFormData multipart = MultipartFormData.parse(contentType, bytes, store.mapper());
                JsonNode payload = multipart.fields().get("@jsonPayload");
                if (payload == null || payload.isNull()) {
                    throw new ApiException(400, "Batch multipart payload requires @jsonPayload.");
                }
                JsonNode body = payload.isTextual() ? store.mapper().readTree(payload.asText()) : payload;
                return new BatchInput(body, batchFiles(multipart.files()));
            }
            if (bytes.length == 0) {
                throw new ApiException(400, "Batch payload must contain a requests array.");
            }
            return new BatchInput(store.mapper().readTree(bytes), Map.of());
        }
    }

    private Map<Integer, Map<String, List<UploadedFile>>> batchFiles(Map<String, List<UploadedFile>> files) {
        if (files == null || files.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Map<String, List<UploadedFile>>> out = new LinkedHashMap<>();
        files.forEach((name, uploaded) -> {
            BatchFileField field = batchFileField(name);
            if (field == null) {
                throw new ApiException(400, "Batch file fields must use requests.N.field or requests[N].field.");
            }
            out.computeIfAbsent(field.index(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(field.field(), ignored -> new ArrayList<>())
                    .addAll(uploaded);
        });
        return out;
    }

    private BatchFileField batchFileField(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (name.startsWith("requests.")) {
            int indexStart = "requests.".length();
            int split = name.indexOf('.', indexStart);
            if (split <= indexStart || split == name.length() - 1) {
                return null;
            }
            return batchFileField(name.substring(indexStart, split), name.substring(split + 1));
        }
        if (name.startsWith("requests[")) {
            int indexStart = "requests[".length();
            int indexEnd = name.indexOf(']', indexStart);
            if (indexEnd <= indexStart || indexEnd + 1 >= name.length() || name.charAt(indexEnd + 1) != '.') {
                return null;
            }
            return batchFileField(name.substring(indexStart, indexEnd), name.substring(indexEnd + 2));
        }
        return null;
    }

    private BatchFileField batchFileField(String index, String field) {
        try {
            int parsedIndex = Integer.parseInt(index);
            if (parsedIndex < 0 || field == null || field.isBlank()) {
                return null;
            }
            return new BatchFileField(parsedIndex, field);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> handleBatchRequest(
            JsonNode request,
            Map<String, List<UploadedFile>> files,
            RequestPrincipal principal
    ) {
        if (request == null || !request.isObject()) {
            throw new ApiException(400, "Batch request must be an object.");
        }
        String method = optionalText(request, "method").toUpperCase(Locale.ROOT);
        if (method.isBlank()) {
            throw new ApiException(400, "Batch request method is required.");
        }
        String url = optionalText(request, "url");
        if (url.isBlank()) {
            url = optionalText(request, "path");
        }
        if (url.isBlank()) {
            throw new ApiException(400, "Batch request url is required.");
        }
        JsonNode body = request.has("body") ? request.get("body") : store.mapper().createObjectNode();
        BatchTarget target = batchTarget(url);

        List<String> segments = target.segments();
        if (segments.size() < 4 || !"api".equals(segments.get(0)) || !"collections".equals(segments.get(1))
                || !"records".equals(segments.get(3))) {
            throw new ApiException(400, "Only record batch requests are supported.");
        }
        String collection = segments.get(2);
        if (JsonFileStore.SUPERUSERS.equals(collection)) {
            requireSuperuser(principal);
        }

        int status;
        Object responseBody;
        if (segments.size() == 4) {
            switch (method) {
                case "POST" -> {
                    responseBody = store.createRecord(collection, body, files, target.query(), principal);
                    status = 200;
                }
                case "PUT" -> {
                    responseBody = store.upsertRecord(collection, null, body, files, target.query(), principal);
                    status = 200;
                }
                default -> throw new ApiException(405, "Method not allowed.");
            }
        } else if (segments.size() == 5) {
            String id = segments.get(4);
            switch (method) {
                case "PATCH" -> {
                    responseBody = store.updateRecord(collection, id, body, files, target.query(), principal);
                    status = 200;
                }
                case "PUT" -> {
                    responseBody = store.upsertRecord(collection, id, body, files, target.query(), principal);
                    status = 200;
                }
                case "DELETE" -> {
                    store.deleteRecord(collection, id, principal);
                    responseBody = null;
                    status = 204;
                }
                default -> throw new ApiException(405, "Method not allowed.");
            }
        } else {
            throw new ApiException(400, "Only record batch requests are supported.");
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status);
        response.put("body", responseBody);
        return response;
    }

    private BatchTarget batchTarget(String url) {
        URI raw = URI.create(url);
        URI uri = raw.isAbsolute() ? raw : URI.create(url.startsWith("/") ? url : "/" + url);
        return new BatchTarget(segments(normalizePath(uri.getPath())), query(uri.getRawQuery()));
    }

    private Object createOrUploadBackup(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (isMultipart(contentType)) {
            return uploadBackup(exchange);
        }
        return store.createBackup(readJson(exchange));
    }

    private Object uploadBackup(HttpExchange exchange) throws IOException {
        RecordInput input = readRecordInput(exchange);
        UploadedFile file = input.files().values().stream()
                .flatMap(List::stream)
                .findFirst()
                .orElseThrow(() -> new ApiException(400, "Backup file is required."));
        return store.uploadBackup(file.originalFilename(), file.bytes());
    }

    private void requireSuperuser(RequestPrincipal principal) {
        if (principal == null) {
            throw new ApiException(401, "Missing or invalid auth token.");
        }
        if (!principal.superuser()) {
            throw new ApiException(403, "Superuser token required.");
        }
    }

    private Optional<RequestPrincipal> principal(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        String token = header.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())
                ? header.substring("Bearer ".length()).trim()
                : header.trim();
        if (token.isBlank()) {
            return Optional.empty();
        }
        return store.verifyToken(token)
                .map(RequestPrincipal::fromClaims);
    }

    private String optionalText(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private HtmlResponse oauth2RedirectPage(HttpExchange exchange) throws IOException {
        Map<String, String> values = "GET".equals(exchange.getRequestMethod())
                ? query(exchange)
                : formOrJsonValues(exchange);
        String state = values.getOrDefault("state", "");
        String code = values.getOrDefault("code", "");
        String error = values.getOrDefault("error", "");
        String payload = store.mapper().writeValueAsString(Map.of(
                "source", "pocketbase-java-oauth2",
                "state", state,
                "code", code,
                "error", error
        ));
        String message = error.isBlank() && !code.isBlank()
                ? "OAuth2 authentication completed. You can close this window."
                : "OAuth2 authentication failed. You can close this window.";
        String html = "<!doctype html><html><head><meta charset=\"utf-8\"><title>OAuth2 Redirect</title></head><body>"
                + "<script>"
                + "const payload=" + payload + ";"
                + "try{window.opener&&window.opener.postMessage(payload, window.location.origin);}catch(e){}"
                + "try{sessionStorage.setItem('pbj-oauth2-result', JSON.stringify(payload));}catch(e){}"
                + "document.body.textContent=" + store.mapper().writeValueAsString(message) + ";"
                + "setTimeout(()=>window.close(),300);"
                + "</script></body></html>";
        return new HtmlResponse(200, html.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
    }

    private Map<String, String> formOrJsonValues(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        try (InputStream input = exchange.getRequestBody()) {
            byte[] bytes = input.readAllBytes();
            if (bytes.length == 0) {
                return Map.of();
            }
            if (isFormUrlEncoded(contentType)) {
                return query(new String(bytes, StandardCharsets.UTF_8));
            }
            JsonNode body = store.mapper().readTree(bytes);
            if (body == null || !body.isObject()) {
                return Map.of();
            }
            Map<String, String> values = new LinkedHashMap<>();
            body.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText("")));
            return values;
        }
    }

    private RecordInput readRecordInput(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        try (InputStream input = exchange.getRequestBody()) {
            byte[] bytes = input.readAllBytes();
            if (isMultipart(contentType)) {
                MultipartFormData multipart = MultipartFormData.parse(contentType, bytes, store.mapper());
                return new RecordInput(multipart.fields(), multipart.files());
            }
            if (bytes.length == 0) {
                return new RecordInput(store.mapper().createObjectNode(), Map.of());
            }
            return new RecordInput(store.mapper().readTree(bytes), Map.of());
        }
    }

    private JsonNode readJson(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            byte[] bytes = input.readAllBytes();
            if (bytes.length == 0) {
                return store.mapper().createObjectNode();
            }
            return store.mapper().readTree(bytes);
        }
    }

    private void serveFile(HttpExchange exchange, String path) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
            throw new ApiException(405, "Method not allowed.");
        }
        List<String> segments = segments(path);
        if (segments.size() != 5 || !"api".equals(segments.get(0)) || !"files".equals(segments.get(1))) {
            throw new ApiException(404, "File not found.");
        }
        Map<String, String> query = query(exchange);
        String collection = segments.get(2);
        String recordId = segments.get(3);
        String filename = segments.get(4);
        Path file = store.filePath(collection, recordId, filename, filePrincipal(exchange).orElse(null));
        if (file == null || !Files.exists(file) || !Files.isRegularFile(file)) {
            throw new ApiException(404, "File not found.");
        }
        ServedFile served = servedFile(file, collection, recordId, filename, query.get("thumb"));
        long size = Files.size(served.path());
        exchange.getResponseHeaders().set("Content-Type", served.contentType());
        exchange.getResponseHeaders().set("Content-Length", String.valueOf(size));
        if (truthy(query.get("download"))) {
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + headerFilename(filename) + "\"");
        }
        exchange.sendResponseHeaders(200, "HEAD".equals(exchange.getRequestMethod()) ? -1 : size);
        if ("HEAD".equals(exchange.getRequestMethod())) {
            return;
        }
        try (OutputStream output = exchange.getResponseBody()) {
            Files.copy(served.path(), output);
        }
    }

    private ServedFile servedFile(Path file, String collection, String recordId, String filename, String thumb) throws IOException {
        if (thumb == null || thumb.isBlank() || !store.fileThumbAllowed(collection, recordId, filename, thumb)) {
            return new ServedFile(file, contentType(filename));
        }
        return ThumbnailGenerator.generate(file, filename, thumb)
                .<ServedFile>map(generated -> new ServedFile(generated.path(), generated.contentType()))
                .orElseGet(() -> new ServedFile(file, contentType(filename)));
    }

    private void serveBackup(HttpExchange exchange, String path) throws IOException {
        requireSuperuser(principal(exchange).orElse(null));
        List<String> segments = segments(path);
        if (segments.size() != 3 || !"api".equals(segments.get(0)) || !"backups".equals(segments.get(1))) {
            throw new ApiException(404, "Backup not found.");
        }
        Path backup = store.backupFile(segments.get(2));
        if (backup == null || !Files.exists(backup) || !Files.isRegularFile(backup)) {
            throw new ApiException(404, "Backup not found.");
        }
        long size = Files.size(backup);
        String filename = backup.getFileName().toString();
        exchange.getResponseHeaders().set("Content-Type", "application/zip");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        exchange.getResponseHeaders().set("Content-Length", String.valueOf(size));
        exchange.sendResponseHeaders(200, size);
        try (OutputStream output = exchange.getResponseBody()) {
            Files.copy(backup, output);
        }
    }

    private Optional<RequestPrincipal> filePrincipal(HttpExchange exchange) {
        Optional<RequestPrincipal> bearer = principal(exchange);
        if (bearer.isPresent()) {
            return bearer;
        }
        String token = query(exchange).get("token");
        return store.verifyFileToken(token);
    }

    private void serveAdmin(HttpExchange exchange, String path) throws IOException {
        String file = path.equals("/") || path.equals("/_/") || path.equals("/_/index.html")
                ? "index.html"
                : path.substring("/_/".length());
        if (file.contains("..") || file.startsWith("/")) {
            throw new ApiException(404, "Not found.");
        }
        String resource = "/pocketbase-admin/" + file;
        try (InputStream input = HttpApi.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new ApiException(404, "Not found.");
            }
            byte[] bytes = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType(file));
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = store.mapper().writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void sendNoContent(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(204, -1);
    }

    private void sendBytes(HttpExchange exchange, int status, byte[] body, String contentType) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body;
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void addCommonHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
    }

    private boolean shouldLogActivity(String path, String method, int status) {
        if (status <= 0 || "OPTIONS".equals(method) || path == null || !path.startsWith("/api/")) {
            return false;
        }
        boolean logsRoute = path.equals("/api/logs") || path.startsWith("/api/logs/");
        return !path.equals("/api/health")
                && !(path.equals("/api/realtime") && "GET".equals(method))
                && (!logsRoute || status >= 400);
    }

    private String activityUrl(HttpExchange exchange) {
        URI uri = exchange.getRequestURI();
        String rawPath = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
        String rawQuery = uri.getRawQuery();
        return rawQuery == null || rawQuery.isBlank() ? rawPath : rawPath + "?" + rawQuery;
    }

    private Map<String, String> requestHeaders(HttpExchange exchange) {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((key, values) -> {
            if (key == null || values == null || values.isEmpty()) {
                return;
            }
            String value = values.get(0);
            headers.put(key, value);
            headers.put(key.toLowerCase(Locale.ROOT), value);
        });
        return headers;
    }

    private String remoteAddress(HttpExchange exchange) {
        if (exchange.getRemoteAddress() == null || exchange.getRemoteAddress().getAddress() == null) {
            return "";
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    private Map<String, Object> errorBody(int status, String message, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status);
        body.put("status", status);
        body.put("message", message == null || message.isBlank() ? "Request failed." : message);
        body.put("data", data == null ? Map.of() : data);
        return body;
    }

    private Map<String, String> query(HttpExchange exchange) {
        return query(exchange.getRequestURI().getRawQuery());
    }

    private Map<String, String> query(String query) {
        Map<String, String> values = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return values;
        }
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int index = pair.indexOf('=');
            String key = index >= 0 ? pair.substring(0, index) : pair;
            String value = index >= 0 ? pair.substring(index + 1) : "";
            values.put(decode(key), decode(value));
        }
        return values;
    }

    private List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isBlank()) {
                out.add(decode(part));
            }
        }
        return out;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private int parsePositive(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String contentType(String file) {
        String lower = file.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain; charset=utf-8";
        }
        if (file.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (file.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (file.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "text/html; charset=utf-8";
    }

    private boolean truthy(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return !"0".equals(normalized) && !"false".equals(normalized) && !"no".equals(normalized);
    }

    private String headerFilename(String filename) {
        return filename.replace("\\", "_")
                .replace("\"", "_")
                .replace("\r", "_")
                .replace("\n", "_");
    }

    private boolean isMultipart(String contentType) {
        return contentType != null && contentType.toLowerCase().startsWith("multipart/form-data");
    }

    private boolean isFormUrlEncoded(String contentType) {
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded");
    }

    private record RecordInput(JsonNode body, Map<String, List<UploadedFile>> files) {
    }

    private record ServedFile(Path path, String contentType) {
    }

    private record HtmlResponse(int status, byte[] body, String contentType) {
    }

    private record BatchTarget(List<String> segments, Map<String, String> query) {
    }

    private record BatchInput(JsonNode body, Map<Integer, Map<String, List<UploadedFile>>> files) {
        Map<String, List<UploadedFile>> filesFor(int index) {
            if (files == null || files.isEmpty()) {
                return Map.of();
            }
            return files.getOrDefault(index, Map.of());
        }
    }

    private record BatchFileField(int index, String field) {
    }

    private record RealtimeSubscriptionInput(
            String clientId,
            List<String> subscriptions,
            RealtimeHub.SubscriptionOptions options
    ) {
    }

    private enum NoContent {
        INSTANCE
    }
}
