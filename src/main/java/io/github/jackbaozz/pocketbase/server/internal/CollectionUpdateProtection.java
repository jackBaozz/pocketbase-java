package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Enforces PocketBase collection properties that cannot change after creation.
 */
public final class CollectionUpdateProtection {
    private CollectionUpdateProtection() {
    }

    public static void validate(
            CollectionSchema current,
            CollectionSchema updated,
            JsonNode body,
            String message
    ) {
        if (current == null || updated == null || body == null || !body.isObject()) {
            return;
        }
        Map<String, Object> errors = new LinkedHashMap<>();
        if (current.system && body.has("name") && !Objects.equals(current.name, updated.name)) {
            errors.put("name", ApiErrors.validationError(
                    "validation_collection_system_name_change",
                    "System collection name cannot be changed."
            ));
        }
        collectErrors(current, updated, body, errors);
        if (!errors.isEmpty()) {
            throw new ApiException(400, message, errors);
        }
    }

    static void collectErrors(
            CollectionSchema current,
            CollectionSchema updated,
            JsonNode body,
            Map<String, Object> errors
    ) {
        if (current == null || updated == null || body == null || !body.isObject()) {
            return;
        }
        String requestedType = body.path("type").asText(updated.type);
        if (!errors.containsKey("type") && body.has("type") && !Objects.equals(current.type, requestedType)) {
            errors.put("type", ApiErrors.validationError(
                    "validation_collection_type_change",
                    "Collection type cannot be changed."
            ));
        }
        if (!errors.containsKey("system") && body.has("system") && body.path("system").asBoolean() != current.system) {
            errors.put("system", ApiErrors.validationError(
                    "validation_collection_system_flag_change",
                    "System collection state cannot be changed."
            ));
        }
        if (current.system) {
            collectSystemCollectionErrors(current, updated, body, errors);
        }
        collectFieldTypeErrors(current, updated, body, errors);
    }

    private static void collectSystemCollectionErrors(
            CollectionSchema current,
            CollectionSchema updated,
            JsonNode body,
            Map<String, Object> errors
    ) {
        compareRule(body, errors, "listRule", current.listRule, updated.listRule);
        compareRule(body, errors, "viewRule", current.viewRule, updated.viewRule);
        compareRule(body, errors, "createRule", current.createRule, updated.createRule);
        compareRule(body, errors, "updateRule", current.updateRule, updated.updateRule);
        compareRule(body, errors, "deleteRule", current.deleteRule, updated.deleteRule);
        if ("auth".equals(current.type)) {
            compareAuthRule(body, errors, "authRule", current.authRule, updated.authRule);
            compareAuthRule(body, errors, "manageRule", current.manageRule, updated.manageRule);
            JsonNode mfa = option(body, "mfa");
            if (mfa != null && mfa.isObject() && mfa.has("rule")
                    && updated.mfa != null && updated.mfa.enabled
                    && !Objects.equals(current.mfa == null ? null : current.mfa.rule, updated.mfa.rule)) {
                errors.put("mfa", Map.of("rule", systemRuleError()));
            }
        }
    }

    private static void compareRule(
            JsonNode body,
            Map<String, Object> errors,
            String field,
            String current,
            String updated
    ) {
        if (body.has(field) && !Objects.equals(current, updated)) {
            errors.put(field, systemRuleError());
        }
    }

    private static void compareAuthRule(
            JsonNode body,
            Map<String, Object> errors,
            String field,
            String current,
            String updated
    ) {
        if ((body.has(field) || option(body, field) != null) && !Objects.equals(current, updated)) {
            errors.put(field, systemRuleError());
        }
    }

    private static Map<String, Object> systemRuleError() {
        return ApiErrors.validationError(
                "validation_collection_system_rule_change",
                "System collection API rule cannot be changed."
        );
    }

    private static void collectFieldTypeErrors(
            CollectionSchema current,
            CollectionSchema updated,
            JsonNode body,
            Map<String, Object> errors
    ) {
        if (!body.has("fields") && !body.has("schema")) {
            return;
        }
        Map<String, Object> fieldErrors = new LinkedHashMap<>();
        for (int i = 0; i < updated.fields.size(); i++) {
            FieldSchema next = updated.fields.get(i);
            if (next == null || next.id == null || next.id.isBlank()) {
                continue;
            }
            FieldSchema previous = current.fields.stream()
                    .filter(field -> field != null && Objects.equals(field.id, next.id))
                    .findFirst()
                    .orElse(null);
            if (previous != null && !Objects.equals(previous.type, next.type)) {
                fieldErrors.put(String.valueOf(i), ApiErrors.validationError(
                        "validation_field_type_change",
                        "Field type cannot be changed."
                ));
            }
        }
        if (!fieldErrors.isEmpty() && !errors.containsKey("fields")) {
            errors.put("fields", fieldErrors);
        }
    }

    private static JsonNode option(JsonNode body, String name) {
        if (body.has(name)) {
            return body.get(name);
        }
        JsonNode options = body.path("options");
        return options.isObject() && options.has(name) ? options.get(name) : null;
    }
}
