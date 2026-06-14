package io.github.jackbaozz.pocketbase.server.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Native-image friendly PNG thumbnails for PocketBase-style file URLs.
 */
final class ThumbnailGenerator {
    private static final byte[] PNG_SIGNATURE = new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final Pattern THUMB_PATTERN = Pattern.compile("^(\\d+)x(\\d+)([tbf]?)$");
    private static final int MAX_DIMENSION = 8192;

    private ThumbnailGenerator() {
    }

    static Optional<GeneratedThumbnail> generate(Path source, String filename, String spec) throws IOException {
        ThumbSpec thumb = ThumbSpec.parse(spec);
        if (thumb == null || !filename.toLowerCase(Locale.ROOT).endsWith(".png")) {
            return Optional.empty();
        }

        Path cacheDir = source.getParent().resolve("thumbs_" + safeName(filename));
        Path target = cacheDir.resolve(thumb.raw() + "_" + stripExtension(safeName(filename)) + ".png");
        if (Files.exists(target) && Files.getLastModifiedTime(target).compareTo(Files.getLastModifiedTime(source)) >= 0) {
            return Optional.of(new GeneratedThumbnail(target, "image/png"));
        }

        PngImage image = readPng(Files.readAllBytes(source));
        if (image == null) {
            return Optional.empty();
        }
        PngImage thumbnail = resize(image, thumb);
        if (thumbnail == null) {
            return Optional.empty();
        }

        Files.createDirectories(cacheDir);
        Files.write(target, writePng(thumbnail));
        return Optional.of(new GeneratedThumbnail(target, "image/png"));
    }

    private static PngImage readPng(byte[] bytes) throws IOException {
        if (bytes.length < PNG_SIGNATURE.length || !Arrays.equals(PNG_SIGNATURE, Arrays.copyOf(bytes, PNG_SIGNATURE.length))) {
            return null;
        }

        int offset = PNG_SIGNATURE.length;
        int width = 0;
        int height = 0;
        int bitDepth = 0;
        int colorType = 0;
        int interlace = 0;
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        while (offset + 8 <= bytes.length) {
            int length = intAt(bytes, offset);
            String type = new String(bytes, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int dataOffset = offset + 8;
            int next = dataOffset + length + 4;
            if (length < 0 || next > bytes.length) {
                return null;
            }
            switch (type) {
                case "IHDR" -> {
                    width = intAt(bytes, dataOffset);
                    height = intAt(bytes, dataOffset + 4);
                    bitDepth = bytes[dataOffset + 8] & 0xFF;
                    colorType = bytes[dataOffset + 9] & 0xFF;
                    interlace = bytes[dataOffset + 12] & 0xFF;
                }
                case "IDAT" -> idat.write(bytes, dataOffset, length);
                case "IEND" -> offset = bytes.length;
                default -> {
                    // ignore ancillary chunks
                }
            }
            if (offset == bytes.length) {
                break;
            }
            offset = next;
        }

        if (width <= 0 || height <= 0 || bitDepth != 8 || interlace != 0 || (colorType != 2 && colorType != 6)) {
            return null;
        }
        int channels = colorType == 6 ? 4 : 3;
        byte[] inflated = inflate(idat.toByteArray());
        int rowBytes = width * channels;
        int expected = height * (rowBytes + 1);
        if (inflated.length < expected) {
            return null;
        }

        byte[] previous = new byte[rowBytes];
        byte[] current = new byte[rowBytes];
        int[] pixels = new int[width * height];
        int position = 0;
        for (int y = 0; y < height; y++) {
            int filter = inflated[position++] & 0xFF;
            System.arraycopy(inflated, position, current, 0, rowBytes);
            position += rowBytes;
            unfilter(current, previous, channels, filter);
            for (int x = 0; x < width; x++) {
                int base = x * channels;
                int r = current[base] & 0xFF;
                int g = current[base + 1] & 0xFF;
                int b = current[base + 2] & 0xFF;
                int a = channels == 4 ? current[base + 3] & 0xFF : 255;
                pixels[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            byte[] swap = previous;
            previous = current;
            current = swap;
        }
        return new PngImage(width, height, pixels);
    }

    private static void unfilter(byte[] current, byte[] previous, int bytesPerPixel, int filter) {
        for (int i = 0; i < current.length; i++) {
            int left = i >= bytesPerPixel ? current[i - bytesPerPixel] & 0xFF : 0;
            int up = previous[i] & 0xFF;
            int upLeft = i >= bytesPerPixel ? previous[i - bytesPerPixel] & 0xFF : 0;
            int value = current[i] & 0xFF;
            int restored = switch (filter) {
                case 0 -> value;
                case 1 -> value + left;
                case 2 -> value + up;
                case 3 -> value + ((left + up) / 2);
                case 4 -> value + paeth(left, up, upLeft);
                default -> value;
            };
            current[i] = (byte) restored;
        }
    }

    private static int paeth(int left, int up, int upLeft) {
        int p = left + up - upLeft;
        int pa = Math.abs(p - left);
        int pb = Math.abs(p - up);
        int pc = Math.abs(p - upLeft);
        if (pa <= pb && pa <= pc) {
            return left;
        }
        return pb <= pc ? up : upLeft;
    }

    private static PngImage resize(PngImage source, ThumbSpec thumb) {
        int targetWidth = thumb.width();
        int targetHeight = thumb.height();
        if (targetWidth == 0) {
            targetWidth = Math.max(1, (int) Math.round(source.width() * (targetHeight / (double) source.height())));
        }
        if (targetHeight == 0) {
            targetHeight = Math.max(1, (int) Math.round(source.height() * (targetWidth / (double) source.width())));
        }
        if (targetWidth <= 0 || targetHeight <= 0 || targetWidth > MAX_DIMENSION || targetHeight > MAX_DIMENSION) {
            return null;
        }

        boolean fit = thumb.fit() || thumb.width() == 0 || thumb.height() == 0;
        if (fit) {
            double scale = Math.min(targetWidth / (double) source.width(), targetHeight / (double) source.height());
            if (thumb.width() == 0 || thumb.height() == 0) {
                scale = Math.max(targetWidth / (double) source.width(), targetHeight / (double) source.height());
            }
            int fittedWidth = Math.max(1, (int) Math.round(source.width() * scale));
            int fittedHeight = Math.max(1, (int) Math.round(source.height() * scale));
            return sample(source, fittedWidth, fittedHeight, 0, 0, fittedWidth / (double) source.width());
        }

        double scale = Math.max(targetWidth / (double) source.width(), targetHeight / (double) source.height());
        int scaledWidth = Math.max(1, (int) Math.round(source.width() * scale));
        int scaledHeight = Math.max(1, (int) Math.round(source.height() * scale));
        int cropX = Math.max(0, (scaledWidth - targetWidth) / 2);
        int cropY = switch (thumb.anchor()) {
            case "t" -> 0;
            case "b" -> Math.max(0, scaledHeight - targetHeight);
            default -> Math.max(0, (scaledHeight - targetHeight) / 2);
        };
        return sample(source, targetWidth, targetHeight, cropX, cropY, scale);
    }

    private static PngImage sample(PngImage source, int targetWidth, int targetHeight, int cropX, int cropY, double scale) {
        int[] pixels = new int[targetWidth * targetHeight];
        for (int y = 0; y < targetHeight; y++) {
            int sourceY = clamp((int) Math.floor((y + cropY) / scale), 0, source.height() - 1);
            for (int x = 0; x < targetWidth; x++) {
                int sourceX = clamp((int) Math.floor((x + cropX) / scale), 0, source.width() - 1);
                pixels[y * targetWidth + x] = source.pixels()[sourceY * source.width() + sourceX];
            }
        }
        return new PngImage(targetWidth, targetHeight, pixels);
    }

    private static byte[] writePng(PngImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(PNG_SIGNATURE);
        writeChunk(output, "IHDR", ihdr(image.width(), image.height()));
        writeChunk(output, "IDAT", deflate(rawRows(image)));
        writeChunk(output, "IEND", new byte[0]);
        return output.toByteArray();
    }

    private static byte[] ihdr(int width, int height) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(output)) {
            data.writeInt(width);
            data.writeInt(height);
            data.writeByte(8);
            data.writeByte(6);
            data.writeByte(0);
            data.writeByte(0);
            data.writeByte(0);
        }
        return output.toByteArray();
    }

    private static byte[] rawRows(PngImage image) {
        byte[] out = new byte[image.height() * (image.width() * 4 + 1)];
        int position = 0;
        for (int y = 0; y < image.height(); y++) {
            out[position++] = 0;
            for (int x = 0; x < image.width(); x++) {
                int pixel = image.pixels()[y * image.width() + x];
                out[position++] = (byte) ((pixel >> 16) & 0xFF);
                out[position++] = (byte) ((pixel >> 8) & 0xFF);
                out[position++] = (byte) (pixel & 0xFF);
                out[position++] = (byte) ((pixel >> 24) & 0xFF);
            }
        }
        return out;
    }

    private static void writeChunk(ByteArrayOutputStream output, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        try (DataOutputStream stream = new DataOutputStream(output)) {
            stream.writeInt(data.length);
            stream.write(typeBytes);
            stream.write(data);
            stream.writeInt((int) crc.getValue());
        }
    }

    private static byte[] inflate(byte[] bytes) throws IOException {
        try (InflaterInputStream input = new InflaterInputStream(new ByteArrayInputStream(bytes))) {
            return input.readAllBytes();
        }
    }

    private static byte[] deflate(byte[] bytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(output)) {
            deflater.write(bytes);
        }
        return output.toByteArray();
    }

    private static int intAt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String safeName(String filename) {
        return filename.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot <= 0 ? filename : filename.substring(0, dot);
    }

    record GeneratedThumbnail(Path path, String contentType) {
    }

    private record PngImage(int width, int height, int[] pixels) {
    }

    private record ThumbSpec(String raw, int width, int height, String anchor) {
        static ThumbSpec parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            Matcher matcher = THUMB_PATTERN.matcher(value.trim().toLowerCase(Locale.ROOT));
            if (!matcher.matches()) {
                return null;
            }
            int width = Integer.parseInt(matcher.group(1));
            int height = Integer.parseInt(matcher.group(2));
            if (width == 0 && height == 0) {
                return null;
            }
            return new ThumbSpec(matcher.group(0), width, height, matcher.group(3));
        }

        boolean fit() {
            return "f".equals(anchor);
        }
    }
}
