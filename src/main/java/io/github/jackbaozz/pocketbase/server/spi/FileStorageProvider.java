package io.github.jackbaozz.pocketbase.server.spi;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface FileStorageProvider {
    
    void put(String key, InputStream stream, long length, String contentType);
    
    Optional<InputStream> get(String key);
    
    void delete(String key);
    
    List<String> list(String prefix);
    
    Optional<FileStat> stat(String key);
    
    Optional<String> signedUrl(String key, long expirySeconds);
    
    // Support for local files (temp staging)
    default Optional<Path> getLocalPath(String key) {
        return Optional.empty();
    }
    
    public record FileStat(long size, long lastModifiedMillis, String contentType) {}
}
