package io.github.jackbaozz.pocketbase.server.internal.storage;

import io.github.jackbaozz.pocketbase.server.internal.FilePermissionSupport;
import io.github.jackbaozz.pocketbase.server.spi.FileStorageProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class LocalFileStorageProvider implements FileStorageProvider {

  private final Path baseDir;

  public LocalFileStorageProvider(Path baseDir) {
    this.baseDir = baseDir.toAbsolutePath().normalize();
    try {
      FilePermissionSupport.secureDirectory(this.baseDir);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to prepare local storage.", e);
    }
  }

  @Override
  public void put(String key, InputStream stream, long length, String contentType) {
    Path target = resolveKey(key);
    try {
      FilePermissionSupport.secureDirectory(target.getParent());
      Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
      FilePermissionSupport.secureFile(target);
    } catch (IOException e) {
      throw new RuntimeException("Failed to write file to local storage: " + key, e);
    }
  }

  @Override
  public Optional<InputStream> get(String key) {
    Path target = resolveKey(key);
    if (!Files.exists(target)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.newInputStream(target));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  @Override
  public void delete(String key) {
    Path target = resolveKey(key);
    try {
      Files.deleteIfExists(target);
    } catch (IOException ignored) {
    }
  }

  @Override
  public List<String> list(String prefix) {
    Path dir = prefix == null || prefix.isBlank() ? baseDir : resolveKey(prefix);
    if (!Files.exists(dir) || !Files.isDirectory(dir)) {
      return List.of();
    }
    try (var stream = Files.walk(dir)) {
      return stream
          .filter(Files::isRegularFile)
          .filter(path -> !Files.isSymbolicLink(path))
          .map(p -> baseDir.relativize(p).toString().replace('\\', '/'))
          .collect(Collectors.toList());
    } catch (IOException e) {
      return List.of();
    }
  }

  @Override
  public Optional<FileStat> stat(String key) {
    Path target = resolveKey(key);
    if (!Files.exists(target)) {
      return Optional.empty();
    }
    try {
      long size = Files.size(target);
      long modified = Files.getLastModifiedTime(target).toMillis();
      String contentType = Files.probeContentType(target);
      if (contentType == null)
        contentType = "application/octet-stream";
      return Optional.of(new FileStat(size, modified, contentType));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<String> signedUrl(String key, long expirySeconds) {
    return Optional.empty();
  }

  @Override
  public Optional<Path> getLocalPath(String key) {
    return Optional.of(resolveKey(key));
  }

  private Path resolveKey(String key) {
    if (key == null || key.isBlank() || key.contains("..") || key.startsWith("/") || key.startsWith("\\")) {
      throw new IllegalArgumentException("Invalid key for local storage.");
    }
    Path target = baseDir.resolve(key).normalize();
    if (!target.startsWith(baseDir)) {
      throw new IllegalArgumentException("Invalid key for local storage.");
    }
    Path current = baseDir;
    for (Path part : baseDir.relativize(target)) {
      current = current.resolve(part);
      if (Files.isSymbolicLink(current)) {
        throw new IllegalArgumentException("Invalid key for local storage.");
      }
    }
    return target;
  }
}
