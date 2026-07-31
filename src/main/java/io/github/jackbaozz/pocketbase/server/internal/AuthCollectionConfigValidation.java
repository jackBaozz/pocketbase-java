package io.github.jackbaozz.pocketbase.server.internal;

import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates auth options that reference collection fields or other auth methods. */
public final class AuthCollectionConfigValidation {
  private AuthCollectionConfigValidation() {
  }

  public static void validate(CollectionSchema collection, String message) {
    if (collection == null || !"auth".equals(collection.type)) {
      return;
    }
    Map<String, Object> errors = new LinkedHashMap<>();
    collectPasswordAuthErrors(collection, errors);
    collectOAuth2Errors(collection, errors);
    collectOtpErrors(collection, errors);
    collectMfaErrors(collection, errors);
    collectTokenErrors(collection, errors);
    collectEmailTemplateErrors(collection, errors);
    if (!errors.isEmpty()) {
      throw new ApiException(400, message, errors);
    }
    validateMfaMethods(collection, message);
    validatePasswordAuthFields(collection, message);
  }

  private static void collectPasswordAuthErrors(
      CollectionSchema collection, Map<String, Object> errors) {
    CollectionSchema.PasswordAuthConfig passwordAuth = collection.passwordAuth;
    if (passwordAuth == null || !passwordAuth.enabled) {
      return;
    }
    List<String> identityFields =
        passwordAuth.identityFields == null ? List.of() : passwordAuth.identityFields;
    if (identityFields.isEmpty()) {
      errors.put(
          "passwordAuth",
          Map.of(
              "identityFields",
              ApiErrors.validationError("validation_required", ApiErrors.MESSAGE_CANNOT_BE_BLANK)));
    }
  }

  private static void validatePasswordAuthFields(CollectionSchema collection, String message) {
    CollectionSchema.PasswordAuthConfig passwordAuth = collection.passwordAuth;
    if (passwordAuth == null || !passwordAuth.enabled) {
      return;
    }
    List<String> identityFields =
        passwordAuth.identityFields == null ? List.of() : passwordAuth.identityFields;
    for (String identityField : identityFields) {
      FieldSchema field =
          collection.fields.stream()
              .filter(candidate -> candidate != null && identityField.equals(candidate.name))
              .findFirst()
              .orElse(null);
      if (field == null) {
        throw passwordAuthError(
            message, "validation_missing_field", "Invalid or missing field " + identityField + ".");
      }
      boolean unique =
          field.unique
              || collection.indexes.stream()
                  .anyMatch(
                      index -> CollectionIndexSupport.isSingleColumnUnique(index, identityField));
      if (!unique) {
        throw passwordAuthError(
            message,
            "validation_missing_unique_constraint",
            "The field " + identityField + " doesn't have a UNIQUE constraint.");
      }
    }
  }

  private static ApiException passwordAuthError(String message, String code, String detail) {
    return new ApiException(
        400,
        message,
        Map.of("passwordAuth", Map.of("identityFields", ApiErrors.validationError(code, detail))));
  }

  private static void collectOAuth2Errors(CollectionSchema collection, Map<String, Object> errors) {
    CollectionSchema.OAuth2Config oauth2 = collection.oauth2;
    if (oauth2 == null || !oauth2.enabled) {
      return;
    }
    List<CollectionSchema.OAuth2ProviderConfig> providers =
        oauth2.providers == null ? List.of() : oauth2.providers;
    Map<String, Object> providerErrors = new LinkedHashMap<>();
    Set<String> names = new LinkedHashSet<>();
    for (int i = 0; i < providers.size(); i++) {
      CollectionSchema.OAuth2ProviderConfig provider = providers.get(i);
      Map<String, Object> fields = new LinkedHashMap<>();
      if (provider == null) {
        fields.put(
            "name",
            ApiErrors.validationError("validation_required", ApiErrors.MESSAGE_CANNOT_BE_BLANK));
        fields.put(
            "clientId",
            ApiErrors.validationError("validation_required", ApiErrors.MESSAGE_CANNOT_BE_BLANK));
        fields.put(
            "clientSecret",
            ApiErrors.validationError("validation_required", ApiErrors.MESSAGE_CANNOT_BE_BLANK));
      } else {
        String name = provider.name == null ? "" : provider.name;
        if (name.isBlank()) {
          fields.put(
              "name",
              ApiErrors.validationError("validation_required", ApiErrors.MESSAGE_CANNOT_BE_BLANK));
        } else if (!names.add(name)) {
          fields.put(
              "name",
              ApiErrors.validationError(
                  "validation_duplicated_provider",
                  "The provider " + name + " is already registered."));
        } else if (OAuth2ProviderManager.providerMetadata(name) == null) {
          fields.put(
              "name",
              ApiErrors.validationError(
                  "validation_missing_provider",
                  "Invalid or missing provider with name " + name + "."));
        }
        if (provider.clientId == null || provider.clientId.isBlank()) {
          fields.put(
              "clientId",
              ApiErrors.validationError("validation_required", ApiErrors.MESSAGE_CANNOT_BE_BLANK));
        }
        if (provider.clientSecret == null || provider.clientSecret.isBlank()) {
          fields.put(
              "clientSecret",
              ApiErrors.validationError("validation_required", ApiErrors.MESSAGE_CANNOT_BE_BLANK));
        }
        validateUrl(fields, "authURL", provider.authURL);
        validateUrl(fields, "tokenURL", provider.tokenURL);
        validateUrl(fields, "userInfoURL", provider.userInfoURL);
      }
      if (!fields.isEmpty()) {
        providerErrors.put(String.valueOf(i), fields);
      }
    }
    if (!providerErrors.isEmpty()) {
      errors.put("oauth2", Map.of("providers", providerErrors));
    }
  }

  private static void collectOtpErrors(CollectionSchema collection, Map<String, Object> errors) {
    CollectionSchema.OtpConfig otp = collection.otp;
    if (otp == null) {
      return;
    }
    Map<String, Object> otpErrors = new LinkedHashMap<>();
    if (otp.enabled) {
      validateRequiredRange(otpErrors, "duration", otp.duration, 10, 86_400);
      validateRequiredMinimum(otpErrors, "length", otp.length, 4);
    }
    Map<String, Object> templateErrors = emailTemplateErrors(otp.emailTemplate);
    if (!templateErrors.isEmpty()) {
      otpErrors.put("emailTemplate", templateErrors);
    }
    if (!otpErrors.isEmpty()) {
      errors.put("otp", otpErrors);
    }
  }

  private static void collectMfaErrors(CollectionSchema collection, Map<String, Object> errors) {
    CollectionSchema.MfaConfig mfa = collection.mfa;
    if (mfa == null || !mfa.enabled) {
      return;
    }
    Map<String, Object> mfaErrors = new LinkedHashMap<>();
    validateRequiredRange(mfaErrors, "duration", mfa.duration, 10, 86_400);
    if (!mfaErrors.isEmpty()) {
      errors.put("mfa", mfaErrors);
    }
  }

  private static void collectTokenErrors(CollectionSchema collection, Map<String, Object> errors) {
    putTokenErrors(errors, "authToken", collection.authToken);
    putTokenErrors(errors, "passwordResetToken", collection.passwordResetToken);
    putTokenErrors(errors, "emailChangeToken", collection.emailChangeToken);
    putTokenErrors(errors, "verificationToken", collection.verificationToken);
    putTokenErrors(errors, "fileToken", collection.fileToken);
  }

  private static void putTokenErrors(
      Map<String, Object> errors, String field, CollectionSchema.TokenConfig token) {
    Map<String, Object> tokenErrors = new LinkedHashMap<>();
    String secret = token == null ? null : token.secret;
    if (secret == null || secret.isEmpty()) {
      tokenErrors.put(
          "secret",
          ApiErrors.validationError("validation_required", ApiErrors.MESSAGE_CANNOT_BE_BLANK));
    } else if (secret.length() < 30 || secret.length() > 255) {
      tokenErrors.put(
          "secret",
          ApiErrors.validationError(
              "validation_length_out_of_range",
              "The length must be between 30 and 255.",
              Map.of("min", 30, "max", 255)));
    }
    validateRequiredRange(
        tokenErrors, "duration", token == null ? 0 : token.duration, 10, 94_670_856);
    if (!tokenErrors.isEmpty()) {
      errors.put(field, tokenErrors);
    }
  }

  private static void collectEmailTemplateErrors(
      CollectionSchema collection, Map<String, Object> errors) {
    if (collection.authAlert != null) {
      Map<String, Object> templateErrors = emailTemplateErrors(collection.authAlert.emailTemplate);
      if (!templateErrors.isEmpty()) {
        errors.put("authAlert", Map.of("emailTemplate", templateErrors));
      }
    }
    putTemplateErrors(errors, "verificationTemplate", collection.verificationTemplate);
    putTemplateErrors(errors, "resetPasswordTemplate", collection.resetPasswordTemplate);
    putTemplateErrors(errors, "confirmEmailChangeTemplate", collection.confirmEmailChangeTemplate);
  }

  private static void putTemplateErrors(
      Map<String, Object> errors, String field, CollectionSchema.EmailTemplate template) {
    Map<String, Object> templateErrors = emailTemplateErrors(template);
    if (!templateErrors.isEmpty()) {
      errors.put(field, templateErrors);
    }
  }

  private static Map<String, Object> emailTemplateErrors(CollectionSchema.EmailTemplate template) {
    Map<String, Object> errors = new LinkedHashMap<>();
    if (template == null || template.subject == null || template.subject.isEmpty()) {
      errors.put(
          "subject",
          ApiErrors.validationError("validation_required", ApiErrors.MESSAGE_CANNOT_BE_BLANK));
    }
    if (template == null || template.body == null || template.body.isEmpty()) {
      errors.put(
          "body",
          ApiErrors.validationError("validation_required", ApiErrors.MESSAGE_CANNOT_BE_BLANK));
    }
    return errors;
  }

  private static void validateRequiredRange(
      Map<String, Object> errors, String field, long value, long minimum, long maximum) {
    if (value == 0) {
      errors.put(
          field,
          ApiErrors.validationError("validation_required", ApiErrors.MESSAGE_CANNOT_BE_BLANK));
    } else if (value < minimum) {
      errors.put(field, minimumError(minimum));
    } else if (value > maximum) {
      errors.put(field, maximumError(maximum));
    }
  }

  private static void validateRequiredMinimum(
      Map<String, Object> errors, String field, long value, long minimum) {
    if (value == 0) {
      errors.put(
          field,
          ApiErrors.validationError("validation_required", ApiErrors.MESSAGE_CANNOT_BE_BLANK));
    } else if (value < minimum) {
      errors.put(field, minimumError(minimum));
    }
  }

  private static Map<String, Object> minimumError(long threshold) {
    return ApiErrors.validationError(
        "validation_min_greater_equal_than_required",
        "Must be no less than " + threshold + ".",
        Map.of("threshold", threshold));
  }

  private static Map<String, Object> maximumError(long threshold) {
    return ApiErrors.validationError(
        "validation_max_less_equal_than_required",
        "Must be no greater than " + threshold + ".",
        Map.of("threshold", threshold));
  }

  private static void validateUrl(Map<String, Object> errors, String field, String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    try {
      URI uri = URI.create(value);
      if (!uri.isAbsolute() || uri.getScheme() == null || uri.getScheme().isBlank()) {
        throw new IllegalArgumentException("relative URL");
      }
      if (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
          && (uri.getHost() == null || uri.getHost().isBlank())) {
        throw new IllegalArgumentException("missing host");
      }
    } catch (IllegalArgumentException e) {
      errors.put(field, ApiErrors.validationError("validation_is_url", "Must be a valid URL."));
    }
  }

  private static void validateMfaMethods(CollectionSchema collection, String message) {
    if (collection.mfa == null || !collection.mfa.enabled) {
      return;
    }
    int enabledMethods = 0;
    if (collection.passwordAuth != null && collection.passwordAuth.enabled) {
      enabledMethods++;
    }
    if (collection.otp != null && collection.otp.enabled) {
      enabledMethods++;
    }
    if (collection.oauth2 != null && collection.oauth2.enabled) {
      enabledMethods++;
    }
    if (enabledMethods < 2) {
      throw new ApiException(
          400,
          message,
          Map.of(
              "mfa",
              Map.of(
                  "enabled",
                  ApiErrors.validationError(
                      "validation_mfa_not_enough_auths",
                      "MFA requires at least 2 auth methods to be enabled."))));
    }
  }
}
