package io.github.jackbaozz.pocketbase.server.spi;

import io.github.jackbaozz.pocketbase.server.internal.storage.LocalFileStorageProvider;
import io.github.jackbaozz.pocketbase.server.internal.storage.S3FileStorageProvider;

import java.nio.file.Path;
import java.util.Map;

public class StorageProviderFactory {
    
    public static FileStorageProvider createLocalProvider(Path baseDir) {
        return new LocalFileStorageProvider(baseDir);
    }
    
    public static FileStorageProvider createS3Provider(Map<String, Object> config) {
        String endpoint = (String) config.get("endpoint");
        String region = (String) config.get("region");
        String bucket = (String) config.get("bucket");
        String accessKey = (String) config.get("accessKey");
        String secretKey = config.get("secretKey") == null ? (String) config.get("secret") : (String) config.get("secretKey");
        boolean forcePathStyle = Boolean.TRUE.equals(config.get("forcePathStyle"))
                || "true".equalsIgnoreCase(String.valueOf(config.get("forcePathStyle")));
        
        return new S3FileStorageProvider(endpoint, region, bucket, accessKey, secretKey, forcePathStyle);
    }
}
