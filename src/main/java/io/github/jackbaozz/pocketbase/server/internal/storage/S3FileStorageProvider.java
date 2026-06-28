package io.github.jackbaozz.pocketbase.server.internal.storage;

import io.github.jackbaozz.pocketbase.server.spi.FileStorageProvider;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

public class S3FileStorageProvider implements FileStorageProvider {
    
    private final String endpoint;
    private final String region;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    
    public S3FileStorageProvider(String endpoint, String region, String bucket, String accessKey, String secretKey) {
        this.endpoint = endpoint;
        this.region = region;
        this.bucket = bucket;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    @Override
    public void put(String key, InputStream stream, long length, String contentType) {
        // Implementation stubbed for S3 without bringing in full SDK 
        // Or if required, would use basic HTTP client with AWS V4 signatures.
        throw new UnsupportedOperationException("S3 Storage not fully implemented yet");
    }

    @Override
    public Optional<InputStream> get(String key) {
        throw new UnsupportedOperationException("S3 Storage not fully implemented yet");
    }

    @Override
    public void delete(String key) {
        throw new UnsupportedOperationException("S3 Storage not fully implemented yet");
    }

    @Override
    public List<String> list(String prefix) {
        throw new UnsupportedOperationException("S3 Storage not fully implemented yet");
    }

    @Override
    public Optional<FileStat> stat(String key) {
        throw new UnsupportedOperationException("S3 Storage not fully implemented yet");
    }

    @Override
    public Optional<String> signedUrl(String key, long expirySeconds) {
        throw new UnsupportedOperationException("S3 Storage not fully implemented yet");
    }
}
