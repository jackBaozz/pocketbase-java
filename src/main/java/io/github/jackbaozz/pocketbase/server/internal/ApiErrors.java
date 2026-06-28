package io.github.jackbaozz.pocketbase.server.internal;

import java.util.Map;

public final class ApiErrors {
    public static final String MESSAGE_CANNOT_BE_BLANK = "Cannot be blank.";
    public static final String MESSAGE_VALUE_MUST_BE_UNIQUE = "Value must be unique.";

    private ApiErrors() {
    }

    public static Map<String, Object> validationError(String code, String message) {
        return Map.of("code", code, "message", message);
    }

    public static Map<String, Object> fieldError(String field, String code, String message) {
        return Map.of(field, validationError(code, message));
    }

    public static Map<String, Object> requiredField(String field) {
        return fieldError(field, "validation_required", MESSAGE_CANNOT_BE_BLANK);
    }

    public static Map<String, Object> notUniqueField(String field) {
        return fieldError(field, "validation_not_unique", MESSAGE_VALUE_MUST_BE_UNIQUE);
    }
}
