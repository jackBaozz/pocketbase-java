package io.github.jackbaozz.pocketbase.server.internal;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class HttpFileSupport {

    private static final String HEAD = "HEAD";

    private HttpFileSupport() {
    }

    public static void serve(HttpExchange exchange, Path file, String contentType, boolean download, String filename) throws IOException {
        long size = Files.size(file);
        long lastModified = Files.getLastModifiedTime(file).toMillis();
        String etag = "\"" + Long.toHexString(lastModified) + "-" + Long.toHexString(size) + "\"";

        String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
        if (etag.equals(ifNoneMatch)) {
            exchange.sendResponseHeaders(304, -1);
            return;
        }

        exchange.getResponseHeaders().set("ETag", etag);
        exchange.getResponseHeaders().set("Last-Modified", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(lastModified), java.time.ZoneId.of("GMT"))));
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        exchange.getResponseHeaders().set("Content-Type", contentType);

        if (download) {
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        } else {
            exchange.getResponseHeaders().set("Content-Disposition", "inline; filename=\"" + filename + "\"");
        }

        String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            Range range = parseSingleRange(rangeHeader, size);
            if (range == null) {
                exchange.getResponseHeaders().set("Content-Range", "bytes */" + size);
                exchange.sendResponseHeaders(416, -1);
                return;
            }

            long length = range.length();
            exchange.getResponseHeaders().set("Content-Range", "bytes " + range.start() + "-" + range.end() + "/" + size);
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(length));
            exchange.sendResponseHeaders(206, HEAD.equals(exchange.getRequestMethod()) ? -1 : length);

            if (!HEAD.equals(exchange.getRequestMethod())) {
                try (FileChannel fc = FileChannel.open(file, StandardOpenOption.READ);
                     OutputStream os = exchange.getResponseBody();
                     WritableByteChannel out = Channels.newChannel(os)) {
                    long position = range.start();
                    long remaining = length;
                    while (remaining > 0) {
                        long count = fc.transferTo(position, remaining, out);
                        if (count <= 0) break;
                        position += count;
                        remaining -= count;
                    }
                }
            }
            return;
        }

        exchange.getResponseHeaders().set("Content-Length", String.valueOf(size));
        exchange.sendResponseHeaders(200, HEAD.equals(exchange.getRequestMethod()) ? -1 : size);
        if (HEAD.equals(exchange.getRequestMethod())) {
            return;
        }
        try (OutputStream output = exchange.getResponseBody()) {
            Files.copy(file, output);
        }
    }

    private static Range parseSingleRange(String rangeHeader, long size) {
        if (size <= 0) {
            return null;
        }
        String value = rangeHeader.substring("bytes=".length()).trim();
        if (value.isEmpty() || value.contains(",")) {
            return null;
        }

        int separator = value.indexOf('-');
        if (separator < 0) {
            return null;
        }

        String startPart = value.substring(0, separator).trim();
        String endPart = value.substring(separator + 1).trim();
        if (startPart.isEmpty()) {
            Long suffixLength = parseUnsignedLong(endPart);
            if (suffixLength == null || suffixLength <= 0) {
                return null;
            }
            long length = Math.min(suffixLength, size);
            return new Range(size - length, size - 1);
        }

        Long start = parseUnsignedLong(startPart);
        Long requestedEnd = endPart.isEmpty() ? size - 1 : parseUnsignedLong(endPart);
        if (start == null || requestedEnd == null || start > requestedEnd || start >= size) {
            return null;
        }
        return new Range(start, Math.min(requestedEnd, size - 1));
    }

    private static Long parseUnsignedLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return null;
            }
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Range(long start, long end) {
        private long length() {
            return end - start + 1;
        }
    }
}
