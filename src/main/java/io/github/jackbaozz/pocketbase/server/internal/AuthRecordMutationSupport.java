package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class AuthRecordMutationSupport {
    private AuthRecordMutationSupport() {
    }

    public static boolean hasManageAccess(
            CollectionSchema collection,
            Map<String, Object> record,
            Map<String, Object> body,
            Map<String, String> query,
            String method,
            RequestPrincipal principal,
            RecordProcessor.StoreContext store
    ) {
        return hasManageAccess(
                collection,
                record,
                body,
                RuleRequestContext.of(query, Map.of()),
                method,
                principal,
                store
        );
    }

    public static boolean hasManageAccess(
            CollectionSchema collection,
            Map<String, Object> record,
            Map<String, Object> body,
            RuleRequestContext request,
            String method,
            RequestPrincipal principal,
            RecordProcessor.StoreContext store
    ) {
        if (principal != null && principal.superuser()) {
            return true;
        }
        if (collection == null
                || !"auth".equals(collection.type)
                || principal == null
                || collection.manageRule == null
                || collection.manageRule.isBlank()) {
            return false;
        }
        return RuleEvaluator.matches(
                collection.manageRule,
                RecordFieldResolverSupport.context(
                        store,
                        collection,
                        record,
                        body == null ? Map.of() : body,
                        request == null ? RuleRequestContext.empty() : request,
                        method == null ? "GET" : method,
                        principal,
                        true,
                        false
                )
        );
    }

    public static void validate(
            CollectionSchema collection,
            Map<String, Object> existing,
            Map<String, Object> candidate,
            JsonNode body,
            boolean manageAccess,
            String passwordField
    ) {
        if (collection == null || !"auth".equals(collection.type) || body == null || !body.isObject()) {
            return;
        }

        boolean create = existing == null;
        String message = create ? "Failed to create record." : "Failed to update record.";
        Map<String, Object> errors = new LinkedHashMap<>();

        if (create && !manageAccess) {
            candidate.put("verified", false);
        }

        if (!create && !manageAccess) {
            if (body.has("email") && !Objects.equals(text(existing.get("email")), body.path("email").asText())) {
                errors.put("email", ApiErrors.validationError("validation_values_mismatch", "The email address can be changed only by a manager."));
            }
            if (body.has("verified") && truthy(existing.get("verified")) != body.path("verified").asBoolean()) {
                errors.put("verified", ApiErrors.validationError("validation_values_mismatch", "The verified state can be changed only by a manager."));
            }
        }

        boolean passwordMutation = body.has(passwordField) || body.has("passwordConfirm") || body.has("oldPassword");
        if (passwordMutation) {
            String password = body.path(passwordField).asText("");
            String passwordConfirm = body.path("passwordConfirm").asText("");
            if (password.isBlank()) {
                errors.put(passwordField, ApiErrors.validationError("validation_required", "Cannot be blank."));
            }
            if (passwordConfirm.isBlank()) {
                errors.put("passwordConfirm", ApiErrors.validationError("validation_required", "Cannot be blank."));
            } else if (!password.equals(passwordConfirm)) {
                errors.put("passwordConfirm", ApiErrors.validationError("validation_values_mismatch", "Passwords do not match."));
            }
            if (!create && !manageAccess) {
                String oldPassword = body.path("oldPassword").asText("");
                if (oldPassword.isBlank()
                        || !PasswordHasher.verifyOrDummy(oldPassword, text(existing.get(passwordField)))) {
                    errors.put("oldPassword", ApiErrors.validationError("validation_invalid_old_password", "Missing or invalid old password."));
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new ApiException(400, message, errors);
        }
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
