package io.github.jackbaozz.pocketbase.server.internal;

import io.github.jackbaozz.pocketbase.server.model.FieldSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validates PocketBase collection fields that overlap with record response metadata. */
public final class CollectionFieldProtection {
  private static final Set<String> RESERVED_RECORD_FIELDS =
      Set.of("expand", "collectionId", "collectionName");

  private CollectionFieldProtection() {
  }

  public static void validateReservedNames(List<FieldSchema> fields, String message) {
    Map<String, Object> errors = new LinkedHashMap<>();
    List<FieldSchema> safeFields = fields == null ? List.of() : fields;
    for (int index = 0; index < safeFields.size(); index++) {
      FieldSchema field = safeFields.get(index);
      if (field == null || !RESERVED_RECORD_FIELDS.contains(field.name)) {
        continue;
      }
      errors.put(
          String.valueOf(index),
          Map.of(
              "name",
              ApiErrors.validationError(
                  "validation_not_in_invalid", "The field name is reserved and cannot be used.")));
    }
    if (!errors.isEmpty()) {
      throw new ApiException(400, message, Map.of("fields", errors));
    }
  }

  public static void validateSystemFieldUpdate(
      List<FieldSchema> currentFields, List<FieldSchema> submittedFields, String message) {
    List<FieldSchema> safeSubmitted = submittedFields == null ? List.of() : submittedFields;
    for (FieldSchema current : currentFields == null ? List.<FieldSchema>of() : currentFields) {
      if (current == null || !current.system) {
        continue;
      }
      FieldSchema submitted =
          safeSubmitted.stream()
              .filter(candidate -> candidate != null && Objects.equals(current.id, candidate.id))
              .findFirst()
              .orElse(null);
      if (submitted == null || !Objects.equals(current.name, submitted.name)) {
        throw new ApiException(
            400,
            message,
            ApiErrors.fieldError(
                "fields",
                "validation_system_field_change",
                "System fields cannot be deleted or renamed."));
      }
    }
  }
}
