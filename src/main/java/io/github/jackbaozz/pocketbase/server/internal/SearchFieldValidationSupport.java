package io.github.jackbaozz.pocketbase.server.internal;

import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import java.util.Set;

public final class SearchFieldValidationSupport {
  private static final Set<String> COLLECTION_FIELDS =
      Set.of("id", "created", "updated", "name", "system", "type");
  private static final Set<String> STATIC_FILTER_VALUES =
      Set.of("@now", "@todayStart", "@todayEnd");

  private SearchFieldValidationSupport() {
  }

  public static void validateCollections(SearchQuerySupport.Parameters search) {
    validateFilter(search.filter(), SearchFieldValidationSupport::validCollectionField);
    for (SearchQuerySupport.SortTerm term : search.sort()) {
      if (!term.random() && !validCollectionField(term.name())) {
        throw invalidSearchParameters();
      }
    }
  }

  public static void validateLogs(SearchQuerySupport.Parameters search) {
    validateLogFilter(search.filter());
    for (SearchQuerySupport.SortTerm term : search.sort()) {
      if (!term.random() && !term.rowId() && !validLogField(term.name())) {
        throw invalidSearchParameters();
      }
    }
  }

  public static void validateLogFilter(String filter) {
    if (filter != null && filter.length() > SearchQuerySupport.MAX_FILTER_LENGTH) {
      throw invalidSearchParameters();
    }
    validateFilter(filter, SearchFieldValidationSupport::validLogField);
  }

  public static void validateRecords(
      RecordProcessor.StoreContext store,
      CollectionSchema collection,
      SearchQuerySupport.Parameters search,
      RequestPrincipal principal) {
    boolean allowHidden = principal != null && principal.superuser();
    validateFilter(
        search.filter(), field -> validRecordField(store, collection, field, allowHidden));
    for (SearchQuerySupport.SortTerm term : search.sort()) {
      if (!term.random()
          && !term.rowId()
          && !validRecordField(store, collection, term.name(), allowHidden)) {
        throw invalidSearchParameters();
      }
    }
  }

  private static void validateFilter(
      String filter, java.util.function.Predicate<String> validator) {
    RuleEvaluator.validate(filter);
    for (String identifier : RuleEvaluator.identifiers(filter)) {
      if (STATIC_FILTER_VALUES.contains(withoutModifier(identifier)) || specialField(identifier)) {
        continue;
      }
      if (!validator.test(identifier)) {
        throw invalidSearchParameters();
      }
    }
  }

  private static boolean validCollectionField(String raw) {
    String field = rootField(raw);
    return COLLECTION_FIELDS.contains(field);
  }

  private static boolean validLogField(String raw) {
    String field = withoutModifier(raw);
    return "id".equals(field)
        || "created".equals(field)
        || "level".equals(field)
        || "message".equals(field)
        || "data".equals(field)
        || field.startsWith("data.");
  }

  private static boolean validRecordField(
      RecordProcessor.StoreContext store,
      CollectionSchema collection,
      String raw,
      boolean allowHidden) {
    return RecordFieldResolverSupport.validPath(store, collection, raw, allowHidden);
  }

  private static String rootField(String raw) {
    String field = withoutModifier(raw);
    int dot = field.indexOf('.');
    return dot < 0 ? field : field.substring(0, dot);
  }

  private static String withoutModifier(String raw) {
    if (raw == null) {
      return "";
    }
    int modifier = raw.indexOf(':');
    return modifier < 0 ? raw : raw.substring(0, modifier);
  }

  private static boolean specialField(String identifier) {
    return identifier.startsWith("@request.") || identifier.startsWith("@collection.");
  }

  private static ApiException invalidSearchParameters() {
    return new ApiException(400, "Invalid search parameters.");
  }
}
