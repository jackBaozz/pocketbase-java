package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Validates the shared PocketBase collection model fields before persistence.
 */
public final class CollectionModelValidation {
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z0-9_]+$");
    private static final Set<String> COLLECTION_TYPES = Set.of("base", "auth", "view");
    private static final Set<String> RESERVED_FIELD_NAMES = Set.of(
            "null",
            "true",
            "false",
            "_rowid_",
            "collectionId",
            "collectionName",
            "expand"
    );
    private static final Set<String> RESERVED_AUTH_FIELD_NAMES = Set.of("passwordConfirm", "oldPassword");

    private CollectionModelValidation() {
    }

    public static void validate(
            CollectionSchema current,
            CollectionSchema candidate,
            JsonNode body,
            Iterable<CollectionSchema> existingCollections,
            Predicate<String> tableExists,
            String message
    ) {
        Map<String, Object> errors = new LinkedHashMap<>();
        List<CollectionSchema> existing = snapshot(existingCollections);

        collectIdError(current, candidate, existing, errors);
        collectTypeError(candidate, body, errors);
        collectNameError(current, candidate, existing, tableExists, errors);
        collectFieldsError(candidate, errors);
        CollectionUpdateProtection.collectErrors(current, candidate, body, errors);

        if (!errors.isEmpty()) {
            throw new ApiException(400, message, errors);
        }
    }

    private static void collectIdError(
            CollectionSchema current,
            CollectionSchema candidate,
            List<CollectionSchema> existing,
            Map<String, Object> errors
    ) {
        String id = candidate == null ? null : candidate.id;
        if (id == null || id.isBlank()) {
            errors.put("id", ApiErrors.validationError("validation_required", ApiErrors.MESSAGE_CANNOT_BE_BLANK));
            return;
        }
        if (id.length() > 100) {
            errors.put("id", lengthError(1, 100));
            return;
        }
        if (!IDENTIFIER_PATTERN.matcher(id).matches()) {
            errors.put("id", ApiErrors.validationError("validation_match_invalid", "Must be in a valid format."));
            return;
        }
        if (current == null && existing.stream().anyMatch(collection -> Objects.equals(collection.id, id))) {
            errors.put("id", ApiErrors.validationError(
                    "validation_invalid_or_existing_id",
                    "The model id is invalid or already exists."
            ));
        }
    }

    private static void collectTypeError(CollectionSchema candidate, JsonNode body, Map<String, Object> errors) {
        String type = candidate == null ? null : candidate.type;
        if (body != null && body.isObject() && body.has("type") && !body.path("type").asText("").isBlank()) {
            type = body.path("type").asText();
        }
        if (type == null || type.isBlank()) {
            errors.put("type", ApiErrors.validationError("validation_required", ApiErrors.MESSAGE_CANNOT_BE_BLANK));
        } else if (!COLLECTION_TYPES.contains(type)) {
            errors.put("type", ApiErrors.validationError("validation_in_invalid", "Must be a valid value."));
        }
    }

    private static void collectNameError(
            CollectionSchema current,
            CollectionSchema candidate,
            List<CollectionSchema> existing,
            Predicate<String> tableExists,
            Map<String, Object> errors
    ) {
        String name = candidate == null ? null : candidate.name;
        if (name == null || name.isBlank()) {
            errors.put("name", ApiErrors.validationError("validation_required", ApiErrors.MESSAGE_CANNOT_BE_BLANK));
            return;
        }
        if (name.length() > 255) {
            errors.put("name", lengthError(1, 255));
            return;
        }
        if (name.toLowerCase(Locale.ROOT).contains("_via_")) {
            errors.put("name", ApiErrors.validationError(
                    "validation_found_via",
                    "The value cannot contain \"_via_\"."
            ));
            return;
        }
        if (!IDENTIFIER_PATTERN.matcher(name).matches()) {
            errors.put("name", ApiErrors.validationError("validation_match_invalid", "Must be in a valid format."));
            return;
        }
        if (current != null && current.system && !Objects.equals(current.name, name)) {
            errors.put("name", ApiErrors.validationError(
                    "validation_collection_system_name_change",
                    "System collection name cannot be changed."
            ));
            return;
        }

        String currentId = current == null ? null : current.id;
        boolean duplicateName = existing.stream()
                .filter(collection -> collection != null && !Objects.equals(collection.id, currentId))
                .anyMatch(collection -> collection.name != null && collection.name.equalsIgnoreCase(name));
        if (duplicateName) {
            errors.put("name", ApiErrors.validationError(
                    "validation_collection_name_exists",
                    "Collection name must be unique (case insensitive)."
            ));
            return;
        }
        if (existing.stream().anyMatch(collection -> collection != null && Objects.equals(collection.id, name))) {
            errors.put("name", ApiErrors.validationError(
                    "validation_collection_name_id_duplicate",
                    "The name must not match an existing collection id."
            ));
            return;
        }
        if ((current == null || current.name == null || !current.name.equalsIgnoreCase(name))
                && tableExists != null && tableExists.test(name)) {
            errors.put("name", ApiErrors.validationError(
                    "validation_collection_name_invalid",
                    "The name shouldn't match with an existing internal table."
            ));
        }
    }

    private static void collectFieldsError(CollectionSchema candidate, Map<String, Object> errors) {
        List<FieldSchema> fields = candidate == null || candidate.fields == null ? List.of() : candidate.fields;
        if (fields.isEmpty()) {
            errors.put("fields", ApiErrors.validationError("validation_required", ApiErrors.MESSAGE_CANNOT_BE_BLANK));
            return;
        }

        Map<String, Object> duplicate = firstDuplicateError(fields);
        if (!duplicate.isEmpty()) {
            errors.put("fields", duplicate);
            return;
        }

        boolean hasId = fields.stream().anyMatch(field -> field != null && "id".equals(field.name));
        if (!hasId) {
            errors.put("fields", ApiErrors.validationError(
                    "validation_missing_primary_key",
                    "Missing or invalid \"id\" PK field."
            ));
            return;
        }

        Map<String, Object> fieldErrors = new LinkedHashMap<>();
        for (int index = 0; index < fields.size(); index++) {
            FieldSchema field = fields.get(index);
            Map<String, Object> settingsError = fieldSettingsError(candidate.type, field);
            if (!settingsError.isEmpty()) {
                fieldErrors.put(String.valueOf(index), settingsError);
            }
        }
        if (!fieldErrors.isEmpty()) {
            errors.put("fields", fieldErrors);
        }
    }

    private static Map<String, Object> firstDuplicateError(List<FieldSchema> fields) {
        Set<String> ids = new java.util.LinkedHashSet<>();
        Set<String> names = new java.util.LinkedHashSet<>();
        for (int index = 0; index < fields.size(); index++) {
            FieldSchema field = fields.get(index);
            if (field == null) {
                continue;
            }
            if (!ids.add(field.id)) {
                return Map.of(String.valueOf(index), Map.of(
                        "id",
                        ApiErrors.validationError(
                                "validation_duplicated_field_id",
                                "Duplicated or invalid field id \"" + field.id + "\""
                        )
                ));
            }
            String normalizedName = field.name == null ? null : field.name.toLowerCase(Locale.ROOT);
            if (!names.add(normalizedName)) {
                return Map.of(String.valueOf(index), Map.of(
                        "name",
                        ApiErrors.validationError(
                                "validation_duplicated_field_name",
                                "Duplicated or invalid field name " + field.name + ".",
                                Map.of("fieldName", field.name == null ? "" : field.name)
                        )
                ));
            }
        }
        return Map.of();
    }

    private static Map<String, Object> fieldSettingsError(String collectionType, FieldSchema field) {
        Map<String, Object> errors = new LinkedHashMap<>();
        if (field == null) {
            errors.put("name", ApiErrors.validationError("validation_required", ApiErrors.MESSAGE_CANNOT_BE_BLANK));
            return errors;
        }
        if (field.id == null || field.id.isBlank()) {
            errors.put("id", ApiErrors.validationError("validation_required", ApiErrors.MESSAGE_CANNOT_BE_BLANK));
        } else if (field.id.length() > 100) {
            errors.put("id", lengthError(1, 100));
        }

        String name = field.name;
        if (name == null || name.isBlank()) {
            errors.put("name", ApiErrors.validationError("validation_required", ApiErrors.MESSAGE_CANNOT_BE_BLANK));
        } else if (name.length() > 100) {
            errors.put("name", lengthError(1, 100));
        } else if (!IDENTIFIER_PATTERN.matcher(name).matches()) {
            errors.put("name", ApiErrors.validationError("validation_match_invalid", "Must be in a valid format."));
        } else if (RESERVED_FIELD_NAMES.contains(name)) {
            errors.put("name", ApiErrors.validationError("validation_not_in_invalid", "Must not be in list."));
        } else if (name.toLowerCase(Locale.ROOT).contains("_via_")) {
            errors.put("name", ApiErrors.validationError(
                    "validation_found_via",
                    "The value cannot contain \"_via_\"."
            ));
        } else if ("auth".equals(collectionType) && RESERVED_AUTH_FIELD_NAMES.contains(name)) {
            errors.put("name", ApiErrors.validationError(
                    "validation_reserved_field_name",
                    "The field name is reserved and cannot be used."
            ));
        }
        return errors;
    }

    private static Map<String, Object> lengthError(int min, int max) {
        return ApiErrors.validationError(
                "validation_length_out_of_range",
                "The length must be between " + min + " and " + max + ".",
                Map.of("min", min, "max", max)
        );
    }

    private static List<CollectionSchema> snapshot(Iterable<CollectionSchema> collections) {
        List<CollectionSchema> result = new ArrayList<>();
        if (collections != null) {
            collections.forEach(result::add);
        }
        return result;
    }
}
