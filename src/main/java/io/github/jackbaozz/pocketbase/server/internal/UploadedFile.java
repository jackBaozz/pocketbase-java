package io.github.jackbaozz.pocketbase.server.internal;

/**
 * Multipart uploaded file kept in memory for the current request.
 */
public record UploadedFile(String fieldName, String originalFilename, String contentType, byte[] bytes) {
    public UploadedFile {
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName must not be blank");
        }
        originalFilename = originalFilename == null || originalFilename.isBlank() ? "file" : originalFilename;
        contentType = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        bytes = bytes == null ? new byte[0] : bytes;
    }
}
