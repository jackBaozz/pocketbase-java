package io.github.jackbaozz.pocketbase.server.internal;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jackbaozz.pocketbase.server.internal.storage.LocalFileStorageProvider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileStorageProviderTest {
  @TempDir
  Path tempDir;

  @Test
  void providerPreservesNestedKeysAndFilenamesContainingDots() throws Exception {
    Path bucket = tempDir.resolve("bucket");
    var provider = new LocalFileStorageProvider(bucket);
    String key = "collection/record/report..v2.txt";
    provider.put(key, content("hello"), 5, "text/plain");

    try (var stream = provider.get(key).orElseThrow()) {
      assertEquals("hello", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }
    assertEquals(5, provider.stat(key).orElseThrow().size());
    assertEquals(bucket.resolve(key), provider.getLocalPath(key).orElseThrow());
    assertEquals(List.of(key), provider.list("collection/"));

    provider.put(key, content("updated"), 7, "text/plain");
    assertEquals("updated", Files.readString(bucket.resolve(key)));
    provider.delete(key);
    assertTrue(provider.get(key).isEmpty());
    assertTrue(provider.stat(key).isEmpty());
    assertTrue(provider.list("collection/").isEmpty());
  }

  @Test
  void objectKeysCannotIdentifyTheBucketItself() {
    Path bucket = tempDir.resolve("bucket");
    var provider = new LocalFileStorageProvider(bucket);

    for (String key : Arrays.asList(null, "", " ", ".", "./", ".//", "././")) {
      assertRejectedObjectKey(provider, key);
      assertTrue(Files.isDirectory(bucket), "invalid object key must not remove the bucket");
    }
  }

  @Test
  void listPrefixesCanIdentifyTheBucketItself() {
    var provider = new LocalFileStorageProvider(tempDir.resolve("bucket"));
    provider.put("nested/file.txt", content("nested"), 6, "text/plain");
    provider.put("root.txt", content("root"), 4, "text/plain");

    for (String prefix : Arrays.asList(null, "", " ", ".", "./", ".//", "././")) {
      assertEquals(Set.of("nested/file.txt", "root.txt"), Set.copyOf(provider.list(prefix)));
    }
  }

  @Test
  void pathsCannotEscapeThroughKeysOrListPrefixesOnAnyPlatform() throws Exception {
    var provider = new LocalFileStorageProvider(tempDir.resolve("bucket"));
    Path outside = tempDir.resolve("outside.txt");
    Files.writeString(outside, "outside");
    for (String key : List.of(
        "../outside.txt",
        "nested/../../outside.txt",
        "nested/../file.txt",
        "../bucket-sibling/file.txt",
        outside.toAbsolutePath().toString(),
        "/etc/passwd",
        "\\outside.txt",
        "nested\\..\\outside.txt",
        "C:/outside.txt",
        "C:\\outside.txt",
        "C:outside.txt",
        "\\\\server\\share\\outside.txt")) {
      assertRejectedObjectKey(provider, key);
      assertThrows(IllegalArgumentException.class, () -> provider.list(key), key);
    }
    assertEquals("outside", Files.readString(outside));
  }

  @Test
  void symbolicLinksCannotExposeObjectsOrListContentsOutsideTheBucket() throws Exception {
    Path bucket = tempDir.resolve("bucket");
    Path outside = Files.createDirectory(tempDir.resolve("outside"));
    Path outsideFile = Files.writeString(outside.resolve("secret.txt"), "secret");
    var provider = new LocalFileStorageProvider(bucket);
    createSymbolicLink(bucket.resolve("linked-directory"), outside);
    createSymbolicLink(bucket.resolve("linked-file"), outsideFile);
    createSymbolicLink(bucket.resolve("broken-link"), outside.resolve("missing.txt"));

    for (String key : List.of("linked-directory/secret.txt", "linked-file", "broken-link")) {
      assertRejectedObjectKey(provider, key);
      assertThrows(IllegalArgumentException.class, () -> provider.list(key), key);
    }
    assertTrue(provider.list("").isEmpty(), "recursive listing must not follow symbolic links");
    assertEquals("secret", Files.readString(outsideFile));
    assertFalse(Files.exists(outside.resolve("missing.txt")));
  }

  @Test
  void replacingTheBucketWithASymbolicLinkCannotBypassRootPrefixChecks() throws Exception {
    Path bucket = tempDir.resolve("bucket");
    var provider = new LocalFileStorageProvider(bucket);
    Path outside = Files.createDirectory(tempDir.resolve("outside"));
    Path outsideFile = Files.writeString(outside.resolve("secret.txt"), "secret");
    Files.delete(bucket);
    createSymbolicLink(bucket, outside);

    assertRejectedObjectKey(provider, "secret.txt");
    assertThrows(IllegalArgumentException.class, () -> provider.list(""));
    assertThrows(IllegalArgumentException.class, () -> provider.list(null));
    assertEquals("secret", Files.readString(outsideFile));
  }

  private static void assertRejectedObjectKey(LocalFileStorageProvider provider, String key) {
    assertAll(
        String.valueOf(key),
        () -> assertThrows(IllegalArgumentException.class, () -> provider.put(key, content("x"), 1, "text/plain")),
        () -> assertThrows(IllegalArgumentException.class, () -> provider.get(key)),
        () -> assertThrows(IllegalArgumentException.class, () -> provider.stat(key)),
        () -> assertThrows(IllegalArgumentException.class, () -> provider.delete(key)),
        () -> assertThrows(IllegalArgumentException.class, () -> provider.getLocalPath(key)));
  }

  private static ByteArrayInputStream content(String value) {
    return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
  }

  private static void createSymbolicLink(Path link, Path target) throws IOException {
    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException | IOException | SecurityException e) {
      Assumptions.assumeTrue(false, "symbolic links are not available: " + e.getClass().getSimpleName());
    }
  }
}
