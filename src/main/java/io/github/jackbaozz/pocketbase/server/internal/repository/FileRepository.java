package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.internal.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.ApiErrors;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;
import io.github.jackbaozz.pocketbase.server.internal.RecordProcessor;
import io.github.jackbaozz.pocketbase.server.internal.RequestPrincipal;
import io.github.jackbaozz.pocketbase.server.internal.TokenService;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class FileRepository extends BaseRepository {

    private final Path dataDir;
    private final TokenService tokenService;
    private final CollectionRepository collectionRepository;
    private final RecordRepository recordRepository;
    private final RecordProcessor.StoreContext storeContext;

    public FileRepository(
            JooqDatabase database,
            ObjectMapper mapper,
            Path dataDir,
            TokenService tokenService,
            CollectionRepository collectionRepository,
            RecordRepository recordRepository,
            RecordProcessor.StoreContext storeContext
    ) {
        super(database, mapper);
        this.dataDir = dataDir;
        this.tokenService = tokenService;
        this.collectionRepository = collectionRepository;
        this.recordRepository = recordRepository;
        this.storeContext = storeContext;
    }

    public Path filePath(String collectionIdOrName, String recordId, String filename, RequestPrincipal principal) {
        FileLookup lookup = lookupFile(collectionIdOrName, recordId, filename);
        if (lookup == null) {
            return null;
        }
        if (lookup.fields().stream().allMatch(this::protectedFileField)) {
            requireProtectedFileAccess(lookup.collection(), lookup.record(), principal);
        }
        Path recordDir = dataDir.resolve("storage").resolve(lookup.collection().id).resolve(recordId).normalize();
        Path file = recordDir.resolve(filename).normalize();
        return file.startsWith(recordDir) ? file : null;
    }

    public boolean fileThumbAllowed(String collection, String recordId, String filename, String thumb) {
        if (thumb == null || thumb.isBlank()) {
            return false;
        }
        FileLookup lookup = lookupFile(collection, recordId, filename);
        if (lookup == null) {
            return false;
        }
        return lookup.fields().stream().anyMatch(field -> thumbs(field).contains(thumb));
    }

    public Map<String, Object> fileToken(RequestPrincipal principal) {
        if (principal == null || principal.id().isBlank()) {
            throw new ApiException(401, "Missing or invalid auth token.");
        }
        Map<String, Object> claims = new LinkedHashMap<>(principal.claims());
        claims.remove("iat");
        claims.remove("exp");
        claims.put("tokenType", "file");
        CollectionSchema collection = collectionForPrincipal(principal);
        Duration ttl = tokenDuration(collection == null ? null : collection.fileToken, CollectionSchema.DEFAULT_FILE_TOKEN_DURATION);
        return Map.of("token", tokenService.create(claims, ttl, tokenSigningSecret(collection == null ? null : collection.fileToken, claims.get("tokenKey"))));
    }

    public Optional<RequestPrincipal> verifyFileToken(String token) {
        var claims = tokenService.verify(token, this::fileTokenSigningSecret);
        if (claims.isEmpty()) return Optional.empty();
        Object tokenType = claims.get().get("tokenType");
        if (!"file".equals(tokenType) && !"file".equals(claims.get().get("type"))) {
            return Optional.empty();
        }
        return Optional.of(RequestPrincipal.fromClaims(claims.get()));
    }

    private FileLookup lookupFile(String collectionIdOrName, String recordId, String filename) {
        if (collectionIdOrName == null || collectionIdOrName.isBlank()
                || recordId == null || recordId.isBlank()
                || filename == null || filename.isBlank()) {
            return null;
        }
        CollectionSchema collection;
        try {
            collection = collectionRepository.getCollectionSchema(collectionIdOrName);
        } catch (ApiException e) {
            return null;
        }
        Map<String, Object> record = recordRepository.getRawRecord(collection, recordId);
        if (record == null) {
            return null;
        }
        List<FieldSchema> fields = fileFieldsReferencing(collection, record, filename);
        return fields.isEmpty() ? null : new FileLookup(collection, record, fields);
    }

    private List<FieldSchema> fileFieldsReferencing(CollectionSchema collection, Map<String, Object> record, String filename) {
        List<FieldSchema> fields = new ArrayList<>();
        for (FieldSchema field : collection.fields) {
            if (!"file".equalsIgnoreCase(field.type)) {
                continue;
            }
            if (fileList(record.get(field.name)).contains(filename)) {
                fields.add(field);
            }
        }
        return fields;
    }

    private List<String> fileList(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .map(String::valueOf)
                    .filter(item -> !item.isBlank())
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return new ArrayList<>();
        }
        if (text.startsWith("[") && text.endsWith("]")) {
            try {
                List<?> parsed = mapper.readValue(text, List.class);
                return parsed.stream()
                        .map(String::valueOf)
                        .filter(item -> !item.isBlank())
                        .collect(Collectors.toCollection(ArrayList::new));
            } catch (Exception ignored) {
            }
        }
        return new ArrayList<>(List.of(text));
    }

    private boolean protectedFileField(FieldSchema field) {
        if (Boolean.TRUE.equals(field.protectedFile)) {
            return true;
        }
        JsonNode value = field.options == null ? null : field.options.get("protected");
        return value != null && value.asBoolean(false);
    }

    private void requireProtectedFileAccess(CollectionSchema collection, Map<String, Object> record, RequestPrincipal principal) {
        if (principal == null) {
            throw new ApiException(403, "Protected file token required.", ApiErrors.requiredField("token"));
        }
        if (!storeContext.canView(collection, record, Map.of(), principal)) {
            throw new ApiException(403, "Protected file is not accessible.",
                    ApiErrors.invalidField("token", "Protected file is not accessible."));
        }
    }

    private List<String> thumbs(FieldSchema field) {
        if (field.thumbs != null && !field.thumbs.isEmpty()) {
            return field.thumbs.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        JsonNode value = field.options == null ? null : field.options.get("thumbs");
        if (value == null || value.isNull()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (value.isArray()) {
            value.forEach(item -> {
                String text = item.asText("").trim();
                if (!text.isBlank()) {
                    out.add(text);
                }
            });
        } else {
            String text = value.asText("").trim();
            if (!text.isBlank()) {
                out.add(text);
            }
        }
        return out;
    }

    private record FileLookup(CollectionSchema collection, Map<String, Object> record, List<FieldSchema> fields) {
    }

    private CollectionSchema collectionForPrincipal(RequestPrincipal principal) {
        if (principal == null) {
            return null;
        }
        CollectionSchema collection = null;
        if (principal.collectionId() != null && !principal.collectionId().isBlank()) {
            try {
                collection = collectionRepository.getCollectionSchema(principal.collectionId());
            } catch (ApiException ignored) {
            }
        }
        if (collection == null && principal.collectionName() != null && !principal.collectionName().isBlank()) {
            try {
                collection = collectionRepository.getCollectionSchema(principal.collectionName());
            } catch (ApiException ignored) {
            }
        }
        return collection;
    }

    private Duration tokenDuration(CollectionSchema.TokenConfig config, long fallbackSeconds) {
        long seconds = config == null || config.duration <= 0 ? fallbackSeconds : config.duration;
        return Duration.ofSeconds(seconds);
    }

    private String fileTokenSigningSecret(Map<String, Object> claims) {
        CollectionSchema collection = collectionForPrincipal(RequestPrincipal.fromClaims(claims));
        if (collection == null) {
            return "";
        }
        return tokenSigningSecret(collection.fileToken, claims.get("tokenKey"));
    }

    private String tokenSigningSecret(CollectionSchema.TokenConfig config, Object tokenKeyValue) {
        String tokenKey = tokenKeyValue == null ? "" : String.valueOf(tokenKeyValue).trim();
        String secret = config == null || config.secret == null ? "" : config.secret.trim();
        return tokenKey + secret;
    }
}
