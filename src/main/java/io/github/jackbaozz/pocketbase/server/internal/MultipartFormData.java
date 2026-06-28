package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal multipart/form-data parser for native-friendly record uploads.
 */
final class MultipartFormData {
    private final ObjectNode fields;
    private final Map<String, List<UploadedFile>> files;

    private MultipartFormData(ObjectNode fields, Map<String, List<UploadedFile>> files) {
        this.fields = fields;
        this.files = files;
    }

    static MultipartFormData parse(String contentType, byte[] body, ObjectMapper mapper) {
        String boundary = boundary(contentType);
        if (boundary == null || boundary.isBlank()) {
            throw new ApiException(400, "Failed to read request body.",
                    ApiErrors.requiredField("body"));
        }

        ObjectNode fields = mapper.createObjectNode();
        Map<String, List<UploadedFile>> files = new LinkedHashMap<>();
        byte[] marker = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int position = indexOf(body, marker, 0);
        while (position >= 0) {
            int partStart = position + marker.length;
            if (partStart + 1 < body.length && body[partStart] == '-' && body[partStart + 1] == '-') {
                break;
            }
            if (partStart + 1 < body.length && body[partStart] == '\r' && body[partStart + 1] == '\n') {
                partStart += 2;
            }
            int next = indexOf(body, marker, partStart);
            if (next < 0) {
                break;
            }

            int partEnd = next;
            if (partEnd >= 2 && body[partEnd - 2] == '\r' && body[partEnd - 1] == '\n') {
                partEnd -= 2;
            }
            parsePart(Arrays.copyOfRange(body, partStart, partEnd), mapper, fields, files);
            position = next;
        }
        return new MultipartFormData(fields, files);
    }

    JsonNode fields() {
        return fields;
    }

    Map<String, List<UploadedFile>> files() {
        return files;
    }

    private static void parsePart(
            byte[] part,
            ObjectMapper mapper,
            ObjectNode fields,
            Map<String, List<UploadedFile>> files
    ) {
        int headerEnd = indexOf(part, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), 0);
        if (headerEnd < 0) {
            return;
        }
        String rawHeaders = new String(part, 0, headerEnd, StandardCharsets.ISO_8859_1);
        byte[] content = Arrays.copyOfRange(part, headerEnd + 4, part.length);
        Map<String, String> headers = headers(rawHeaders);
        String disposition = headers.getOrDefault("content-disposition", "");
        String name = parameter(disposition, "name");
        if (name == null || name.isBlank()) {
            return;
        }
        String filename = parameter(disposition, "filename");
        if (filename != null && !filename.isBlank()) {
            files.computeIfAbsent(name, ignored -> new ArrayList<>())
                    .add(new UploadedFile(name, filename, headers.get("content-type"), content));
            return;
        }

        String value = new String(content, StandardCharsets.UTF_8);
        JsonNode parsed = parseFieldValue(value, mapper);
        fields.set(name, parsed);
    }

    private static JsonNode parseFieldValue(String value, ObjectMapper mapper) {
        String text = value == null ? "" : value;
        String trimmed = text.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[") || "true".equals(trimmed)
                || "false".equals(trimmed) || "null".equals(trimmed) || looksNumeric(trimmed)) {
            try {
                return mapper.readTree(trimmed);
            } catch (Exception ignored) {
                return mapper.getNodeFactory().textNode(text);
            }
        }
        return mapper.getNodeFactory().textNode(text);
    }

    private static boolean looksNumeric(String value) {
        if (value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!Character.isDigit(ch) && ch != '-' && ch != '.') {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> headers(String rawHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line : rawHeaders.split("\\r\\n")) {
            int split = line.indexOf(':');
            if (split <= 0) {
                continue;
            }
            headers.put(
                    line.substring(0, split).trim().toLowerCase(Locale.ROOT),
                    line.substring(split + 1).trim()
            );
        }
        return headers;
    }

    private static String boundary(String contentType) {
        if (contentType == null) {
            return null;
        }
        for (String part : contentType.split(";")) {
            String value = part.trim();
            if (value.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                String boundary = value.substring("boundary=".length()).trim();
                if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() >= 2) {
                    return boundary.substring(1, boundary.length() - 1);
                }
                return boundary;
            }
        }
        return null;
    }

    private static String parameter(String header, String name) {
        for (String part : header.split(";")) {
            String value = part.trim();
            if (!value.toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT) + "=")) {
                continue;
            }
            String result = value.substring(name.length() + 1).trim();
            if (result.startsWith("\"") && result.endsWith("\"") && result.length() >= 2) {
                return result.substring(1, result.length() - 1);
            }
            return result;
        }
        return null;
    }

    private static int indexOf(byte[] source, byte[] target, int from) {
        outer:
        for (int i = Math.max(0, from); i <= source.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (source[i + j] != target[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
