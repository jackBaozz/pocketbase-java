package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RecordInputProtection {
    private RecordInputProtection() {
    }

    public static JsonNode writableBody(CollectionSchema collection, JsonNode body, RequestPrincipal principal) {
        if (body == null || !body.isObject() || isSuperuser(principal)) {
            return body;
        }
        ObjectNode copy = body.deepCopy();
        for (FieldSchema field : collection.fields) {
            if (!writableField(collection, field, principal)) {
                copy.remove(field.name);
                copy.remove(field.name + "+");
                copy.remove(field.name + "-");
            }
        }
        return copy;
    }

    public static Map<String, List<UploadedFile>> writableFiles(
            CollectionSchema collection,
            Map<String, List<UploadedFile>> files,
            RequestPrincipal principal
    ) {
        if (files == null || files.isEmpty() || isSuperuser(principal)) {
            return files == null ? Map.of() : files;
        }
        Map<String, List<UploadedFile>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<UploadedFile>> entry : files.entrySet()) {
            FieldSchema field = findField(collection, baseFieldName(entry.getKey()));
            if (field == null || writableField(collection, field, principal)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    public static boolean writableField(CollectionSchema collection, FieldSchema field, RequestPrincipal principal) {
        if (field == null || !field.hidden || isSuperuser(principal)) {
            return true;
        }
        return "auth".equals(collection.type) && "password".equals(field.name);
    }

    private static FieldSchema findField(CollectionSchema collection, String name) {
        if (collection == null || collection.fields == null) {
            return null;
        }
        for (FieldSchema field : collection.fields) {
            if (field != null && name.equals(field.name)) {
                return field;
            }
        }
        return null;
    }

    private static String baseFieldName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        if (name.endsWith("+") || name.endsWith("-")) {
            return name.substring(0, name.length() - 1);
        }
        return name;
    }

    private static boolean isSuperuser(RequestPrincipal principal) {
        return principal != null && principal.superuser();
    }
}
