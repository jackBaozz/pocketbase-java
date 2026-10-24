package io.github.jackbaozz.pocketbase.server.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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
  void fallsBackToOfficialPixelLimitForMissingInvalidOrNonpositiveValues() {
    for (String configured : new String[] {
        null, "", " ", "0", "-1", "invalid", "9223372036854775808", " 25 ", "25\n", "25e0",
        "09", "0x19.0", "1__0", "_25", "25_", "0x__19", "++25", "+ 25", "-.9", "-.",
        ".9", ".", "+.", "0b102", "２５"
    }) {
      assertEquals(262144000L, ThumbnailGenerator.sourcePixelLimit(configured), configured);
    }
  }

  @Test
  void parsesOfficialDecimalBasePrefixesAndUnderscoreFormats() {
    for (String configured : new String[] {
        "25", "25.", "25.0", "25.9", "+25", "+25.9", "0o31", "0O31", "0b11001", "0B11001",
        "0x19", "0X19", "+0x19", "0x_19", "0_31", "2_5"
    }) {
      assertEquals(25L, ThumbnailGenerator.sourcePixelLimit(configured), configured);
    }
    for (String configured : new String[] {"025", "025.9", "+025.9", "00025"}) {
      assertEquals(21L, ThumbnailGenerator.sourcePixelLimit(configured), configured);
    }
    assertEquals(1000000L, ThumbnailGenerator.sourcePixelLimit("1_000_000"));
    for (String configured : new String[] {
        Long.toString(Long.MAX_VALUE), Long.MAX_VALUE + ".9", "0x7fff_ffff_ffff_ffff"
    }) {
      assertEquals(Long.MAX_VALUE, ThumbnailGenerator.sourcePixelLimit(configured), configured);
    }
  }

  @Test
  void honorsEnvironmentPixelLimitAndAllowsExactBoundary() throws Exception {
    for (int limit : new int[] {24, 25, 26}) {
      Path source = tempDir.resolve("image_" + limit + ".png");
      Files.write(source, pngImage(5, 5));
      assertGenerationWithEnvironment(source, Integer.toString(limit), limit >= 25);
    }
  }

  @Test
  void numericEnvironmentFormatsCannotSilentlyRelaxThePixelLimit() throws Exception {
    for (String configured : new String[] {"24.9", "0x18", "025", "0o30", "0b11000", "2_4"}) {
      Path source = tempDir.resolve("image_" + configured.replace('.', '_') + ".png");
      Files.write(source, pngImage(5, 5));
      assertGenerationWithEnvironment(source, configured, false);
    }
    Path source = tempDir.resolve("exact_decimal.png");
    Files.write(source, pngImage(5, 5));
    assertGenerationWithEnvironment(source, "25.9", true);
  }

  @Test
  void invalidEnvironmentValueUsesDefaultWithoutRelaxingExistingDecoderLimits() throws Exception {
    Path source = tempDir.resolve("image.png");
    Files.write(source, pngImage(5, 5));
    assertGenerationWithEnvironment(source, "invalid", true);

    Path oversized = tempDir.resolve("oversized.png");
    Files.write(oversized, pngHeader(8192, 8192, 6));
    assertGenerationWithEnvironment(oversized, Long.toString(Long.MAX_VALUE), false);
  }

  @Test
  void rejectsOversizedHeaderBeforeReadingEntireSourceFile() throws Exception {
    Path source = tempDir.resolve("oversized.png");
    try (var channel =
        Files.newByteChannel(
            source,
            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE))) {
      channel.write(ByteBuffer.wrap(pngHeader(8192, 8192, 6)));
      channel.position((long) Integer.MAX_VALUE + 1);
      channel.write(ByteBuffer.wrap(new byte[] {0}));
    }
    // Files.readAllBytes cannot create an array this large. A safe rejection must inspect the
    // fixed header first, without allocating storage proportional to the encoded file size.
    assertTrue(Files.size(source) > Integer.MAX_VALUE);
    assertTrue(
        assertDoesNotThrow(() -> ThumbnailGenerator.generate(source, "oversized.png", "10x10"))
            .isEmpty());
  }

  @Test
  void secondHeaderCannotBypassConfiguredPixelLimit() throws Exception {
    Path source = tempDir.resolve("repeated_header.png");
    ByteArrayOutputStream png = new ByteArrayOutputStream();
    png.write(pngHeader(5, 5, 6));
    byte[] secondHeader = pngHeader(6, 6, 6);
    png.write(secondHeader, PNG_SIGNATURE.length, secondHeader.length - PNG_SIGNATURE.length);
    writeChunk(png, "IDAT", new byte[] {0});
    writeChunk(png, "IEND", new byte[0]);
    Files.write(source, png.toByteArray());
    assertGenerationWithEnvironment(source, "25", false);
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
    for (int y = 0; y < height; y++) {
      raw.write(0);
      for (int x = 0; x < width; x++) {
        raw.write(new byte[] {(byte) 0x22, (byte) 0x44, (byte) 0x66, (byte) 0xff});
      }
    }
    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
      deflater.write(raw.toByteArray());
    }
    writeChunk(output, "IDAT", compressed.toByteArray());
    writeChunk(output, "IEND", new byte[0]);
    return output.toByteArray();
  }

  private static void assertGenerationWithEnvironment(
      Path source, String configuredLimit, boolean expected) throws Exception {
    ProcessBuilder builder =
        new ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            "-cp",
            System.getProperty("java.class.path"),
            EnvironmentProbe.class.getName(),
            source.toString());
    builder.environment().put("PB_THUMBS_MAX_SOURCE_PIXELS", configuredLimit);
    Process process = builder.redirectErrorStream(true).start();
    try {
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "thumbnail probe should terminate");
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(0, process.exitValue(), output);
      assertEquals(Boolean.toString(expected), output.trim());
    } finally {
      process.destroyForcibly();
    }
  }

  public static final class EnvironmentProbe {
    public static void main(String[] args) throws IOException {
      Path source = Path.of(args[0]);
      System.out.println(
          ThumbnailGenerator.generate(source, source.getFileName().toString(), "1x1").isPresent());
    }
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
