package io.github.jackbaozz.pocketbase.server.internal;

import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;
import java.util.List;
import java.util.function.Function;

public final class CollectionRuleSupport {
  private CollectionRuleSupport() {
  }

  public static void validate(CollectionSchema collection, String message) {
    validate(
        collection,
        message,
        identifier -> collectionMatches(collection, identifier) ? collection : null);
  }

  public static void validate(
      CollectionSchema collection,
      String message,
      Function<String, CollectionSchema> collectionLookup) {
    Function<String, CollectionSchema> lookup =
        identifier -> collectionMatches(collection, identifier)
            ? collection
            : collectionLookup.apply(identifier);
    validateRule(collection, "listRule", collection.listRule, true, message, lookup);
    validateRule(collection, "viewRule", collection.viewRule, true, message, lookup);
    validateRule(collection, "createRule", collection.createRule, true, message, lookup);
    validateRule(collection, "updateRule", collection.updateRule, true, message, lookup);
    validateRule(collection, "deleteRule", collection.deleteRule, true, message, lookup);
    if ("auth".equals(collection.type)) {
      validateRule(collection, "authRule", collection.authRule, true, message, lookup);
      validateRule(collection, "manageRule", collection.manageRule, false, message, lookup);
      if (collection.mfa != null
          && collection.mfa.enabled
          && collection.mfa.rule != null
          && !collection.mfa.rule.isBlank()) {
        validateNestedRule(collection, "mfa", "rule", collection.mfa.rule, message, lookup);
      }
    }
  }

  private static void validateNestedRule(
      CollectionSchema collection,
      String parent,
      String field,
      String rule,
      String message,
      Function<String, CollectionSchema> collectionLookup) {
    try {
      validateRule(collection, field, rule, true, message, collectionLookup);
    } catch (ApiException e) {
      Object nested = e.data();
      if (nested instanceof java.util.Map<?, ?> map && map.containsKey(field)) {
        throw new ApiException(
            400, message, java.util.Map.of(parent, java.util.Map.of(field, map.get(field))));
      }
      throw e;
    }
  }

  private static void validateRule(
      CollectionSchema collection,
      String field,
      String rule,
      boolean allowBlank,
      String message,
      Function<String, CollectionSchema> collectionLookup) {
    if (rule == null) {
      return;
    }
    if (rule.isBlank()) {
      if (allowBlank) {
        return;
      }
      throw invalidRule(message, field, "Rule cannot be empty.");
    }
    try {
      RuleEvaluator.validate(rule);
    } catch (ApiException e) {
      throw invalidRule(message, field, "Invalid rule syntax.");
    }

    for (String identifier : RuleEvaluator.identifiers(rule)) {
      if (List.of("@now", "@todayStart", "@todayEnd").contains(withoutModifier(identifier))) {
        continue;
      }
      if (identifier.startsWith("@request.body.")) {
        validateRequestBodyIdentifier(collection, identifier, message, field, collectionLookup);
        continue;
      }
      if (identifier.startsWith("@collection.")) {
        validateCollectionIdentifier(identifier, message, field, collectionLookup);
        continue;
      }
      if (identifier.startsWith("@request.")) {
        continue;
      }
      if (!RecordFieldResolverSupport.validPath(collectionLookup, collection, identifier, true)) {
        String path = withoutModifier(identifier);
        int dot = path.indexOf('.');
        String detail =
            dot < 0
                ? "Unknown field `" + path + "`."
                : "Unknown or invalid field `" + identifier + "`.";
        throw invalidRule(message, field, detail);
      }
    }
  }

  private static void validateRequestBodyIdentifier(
      CollectionSchema collection,
      String identifier,
      String message,
      String ruleField,
      Function<String, CollectionSchema> collectionLookup) {
    String rawPath = identifier.substring("@request.body.".length());
    String path = withoutModifier(rawPath);
    String modifier = modifier(rawPath);
    int dot = path.indexOf('.');
    String root = dot < 0 ? path : path.substring(0, dot);
    FieldSchema bodyField = RecordFieldResolverSupport.field(collection, root);
    if (bodyField == null) {
      if (!RecordFieldResolverSupport.requestBodyModifierAllowed(null, modifier)) {
        throw invalidRule(
            message, ruleField, "Unknown or invalid request body field `" + rawPath + "`.");
      }
      return;
    }
    if (dot < 0) {
      if (!RecordFieldResolverSupport.requestBodyModifierAllowed(bodyField, modifier)) {
        throw invalidRule(
            message, ruleField, "Invalid request body field modifier `" + rawPath + "`.");
      }
      return;
    }
    if (!RecordFieldResolverSupport.validPath(collectionLookup, collection, rawPath, true)) {
      throw invalidRule(
          message, ruleField, "Unknown or invalid request body field `" + rawPath + "`.");
    }
  }

  private static void validateCollectionIdentifier(
      String identifier,
      String message,
      String ruleField,
      Function<String, CollectionSchema> collectionLookup) {
    String path = identifier.substring("@collection.".length());
    int dot = path.indexOf('.');
    if (dot <= 0 || dot == path.length() - 1) {
      throw invalidRule(message, ruleField, "Invalid collection field `" + identifier + "`.");
    }
    String collectionToken = path.substring(0, dot);
    int alias = collectionToken.indexOf(':');
    String collectionName = alias < 0 ? collectionToken : collectionToken.substring(0, alias);
    CollectionSchema target = collectionLookup.apply(collectionName);
    String targetPath = path.substring(dot + 1);
    if (target == null
        || !RecordFieldResolverSupport.validPath(collectionLookup, target, targetPath, true)) {
      throw invalidRule(
          message, ruleField, "Unknown or invalid collection field `" + identifier + "`.");
    }
  }

  private static boolean collectionMatches(CollectionSchema collection, String identifier) {
    return collection != null
        && (identifier != null)
        && (identifier.equals(collection.id) || identifier.equals(collection.name));
  }

  private static String withoutModifier(String raw) {
    if (raw == null) {
      return "";
    }
    int index = raw.lastIndexOf(':');
    return index > raw.lastIndexOf('.') ? raw.substring(0, index) : raw;
  }

  private static String modifier(String raw) {
    if (raw == null) {
      return "";
    }
    int index = raw.lastIndexOf(':');
    return index > raw.lastIndexOf('.') ? raw.substring(index + 1) : "";
  }

  private static ApiException invalidRule(String message, String field, String detail) {
    return new ApiException(400, message, ApiErrors.invalidField(field, detail));
  }
}
