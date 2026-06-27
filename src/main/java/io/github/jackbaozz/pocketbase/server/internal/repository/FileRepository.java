package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.internal.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;
import io.github.jackbaozz.pocketbase.server.internal.RequestPrincipal;
import io.github.jackbaozz.pocketbase.server.internal.TokenService;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class FileRepository extends BaseRepository {

    private final Path dataDir;
    private final TokenService tokenService;

    public FileRepository(JooqDatabase database, ObjectMapper mapper, Path dataDir, TokenService tokenService) {
        super(database, mapper);
        this.dataDir = dataDir;
        this.tokenService = tokenService;
    }

    public Path filePath(String collectionIdOrName, String recordId, String filename, RequestPrincipal principal) {
        if (collectionIdOrName == null || recordId == null || filename == null) return null;
        return dataDir.resolve("storage").resolve(collectionIdOrName).resolve(recordId).resolve(filename);
    }

    public boolean fileThumbAllowed(String collection, String recordId, String filename, String thumb) {
        return true;
    }

    public Map<String, Object> fileToken(RequestPrincipal principal) {
        if (principal == null || principal.id().isBlank()) {
            throw new ApiException(401, "Missing or invalid auth token.");
        }
        Map<String, Object> claims = new LinkedHashMap<>(principal.claims());
        claims.remove("iat");
        claims.remove("exp");
        claims.put("tokenType", "file");
        return Map.of("token", tokenService.create(claims, Duration.ofMinutes(2)));
    }

    public Optional<RequestPrincipal> verifyFileToken(String token) {
        var claims = tokenService.verify(token);
        if (claims.isEmpty()) return Optional.empty();
        return Optional.of(RequestPrincipal.fromClaims(claims.get()));
    }
}
