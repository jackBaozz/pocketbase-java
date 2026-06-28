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
        String secretKey = (String) config.get("secretKey");
        
        return new S3FileStorageProvider(endpoint, region, bucket, accessKey, secretKey);
    }
}
