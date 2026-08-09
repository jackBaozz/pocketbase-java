package io.github.jackbaozz.pocketbase.server.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThumbnailGeneratorTest {

  private static final byte[] PNG_SIGNATURE =
      new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

  @TempDir
  Path tempDir;

  @Test
  void rejectsPngDimensionsThatCouldExhaustMemory() throws Exception {
    Path source = tempDir.resolve("oversized.png");
    Files.write(source, pngHeader(Integer.MAX_VALUE, 1, 6));

    Optional<ThumbnailGenerator.GeneratedThumbnail> result =
        assertDoesNotThrow(() -> ThumbnailGenerator.generate(source, "oversized.png", "10x10"));
    org.junit.jupiter.api.Assertions.assertTrue(result.isEmpty());
  }

  @Test
  void rejectsSymlinkedThumbnailCache() throws Exception {
    Path source = tempDir.resolve("image.png");
    Files.write(source, pngHeader(1, 1, 6));
    Path cache = tempDir.resolve("thumbs_image.png");
    Path outside = tempDir.resolve("outside");
    Files.createDirectories(outside);
    try {
      Files.createSymbolicLink(cache, outside);
    } catch (UnsupportedOperationException | IOException e) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links are not available");
    }

    assertThrows(
        IOException.class, () -> ThumbnailGenerator.generate(source, "image.png", "10x10"));
  }

  @Test
  void createsPrivateThumbnailCacheOnPosixFilesystems() throws Exception {
    Assumptions.assumeTrue(
        Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) != null);
    Path source = tempDir.resolve("image.png");
    Files.write(source, pngImage(1, 1));

    Optional<ThumbnailGenerator.GeneratedThumbnail> result =
        ThumbnailGenerator.generate(source, "image.png", "1x1");
    org.junit.jupiter.api.Assertions.assertTrue(result.isPresent());
    assertEquals(
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE),
        Files.getPosixFilePermissions(source.resolveSibling("thumbs_image.png")));
    assertEquals(
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        Files.getPosixFilePermissions(result.orElseThrow().path()));
  }

  private static byte[] pngHeader(int width, int height, int colorType) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.write(PNG_SIGNATURE);
    byte[] data = new byte[13];
    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(data);
    buffer.putInt(width).putInt(height).put((byte) 8).put((byte) colorType);
    buffer.put((byte) 0).put((byte) 0).put((byte) 0);
    writeChunk(output, "IHDR", data);
    return output.toByteArray();
  }

  private static byte[] pngImage(int width, int height) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.write(PNG_SIGNATURE);
    byte[] header = new byte[13];
    java.nio.ByteBuffer.wrap(header)
        .putInt(width)
        .putInt(height)
        .put((byte) 8)
        .put((byte) 6)
        .put((byte) 0)
        .put((byte) 0)
        .put((byte) 0);
    writeChunk(output, "IHDR", header);
    ByteArrayOutputStream raw = new ByteArrayOutputStream();
    raw.write(0);
    raw.write(new byte[] {(byte) 0x22, (byte) 0x44, (byte) 0x66, (byte) 0xff});
    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
      deflater.write(raw.toByteArray());
    }
    writeChunk(output, "IDAT", compressed.toByteArray());
    writeChunk(output, "IEND", new byte[0]);
    return output.toByteArray();
  }

  private static void writeChunk(ByteArrayOutputStream output, String type, byte[] data)
      throws IOException {
    byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    try (DataOutputStream writer = new DataOutputStream(output)) {
      writer.writeInt(data.length);
      writer.write(typeBytes);
      writer.write(data);
      CRC32 crc = new CRC32();
      crc.update(typeBytes);
      crc.update(data);
      writer.writeInt((int) crc.getValue());
    }
  }
}
