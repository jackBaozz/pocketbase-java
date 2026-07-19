package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared in-memory counterpart of PocketBase's RecordFieldResolver. */
public final class RecordFieldResolverSupport {
    private static final int MAX_RELATION_DEPTH = 6;
    private static final Set<String> SYSTEM_FIELDS = Set.of("id", "created", "updated");
    private static final Pattern BACK_RELATION = Pattern.compile("^(\\w+)_via_(\\w+)$");

    private RecordFieldResolverSupport() {
    }

    public static RuleEvaluator.Context context(
            RecordProcessor.StoreContext store,
            CollectionSchema collection,
            Map<String, Object> record,
            Map<String, Object> body,
            Map<String, String> query,
            String method,
            RequestPrincipal principal,
            boolean allowHiddenFields,
            boolean enforceRelatedListRules
    ) {
        return context(
                store,
                collection,
                record,
                body,
                RuleRequestContext.of(query, Map.of()),
                method,
                principal,
                allowHiddenFields,
                enforceRelatedListRules
        );
    }

    public static RuleEvaluator.Context context(
            RecordProcessor.StoreContext store,
            CollectionSchema collection,
            Map<String, Object> record,
            Map<String, Object> body,
            RuleRequestContext request,
            String method,
            RequestPrincipal principal,
            boolean allowHiddenFields,
            boolean enforceRelatedListRules
    ) {
        Map<String, Object> safeRecord = record == null ? Map.of() : record;
        Map<String, Object> safeBody = body == null ? Map.of() : body;
        RuleRequestContext safeRequest = request == null ? RuleRequestContext.empty() : request;
        return RuleEvaluator.context(
                safeRecord,
                safeBody,
                safeRequest.query(),
                safeRequest.headers(),
                safeRequest.context(),
                method,
                principal,
                store::recordsForRule,
                identifier -> resolveIdentifier(
                        store,
                        collection,
                        safeRecord,
                        safeBody,
                        safeRequest,
                        principal,
                        allowHiddenFields,
                        enforceRelatedListRules,
                        identifier
                )
        );
    }

    public static Object resolveSortValue(
            RecordProcessor.StoreContext store,
            CollectionSchema collection,
            Map<String, Object> record,
            String path,
            Map<String, String> query,
            RequestPrincipal principal,
            boolean allowHiddenFields,
            boolean enforceRelatedListRules
    ) {
        return resolveSortValue(
                store,
                collection,
                record,
                path,
                RuleRequestContext.of(query, Map.of()),
                principal,
                allowHiddenFields,
                enforceRelatedListRules
        );
    }

    public static Object resolveSortValue(
            RecordProcessor.StoreContext store,
            CollectionSchema collection,
            Map<String, Object> record,
            String path,
            RuleRequestContext request,
            RequestPrincipal principal,
            boolean allowHiddenFields,
            boolean enforceRelatedListRules
    ) {
        String modifier = modifier(path);
        String fieldPath = withoutModifier(path);
        RuleEvaluator.Resolution resolution = resolveRecordPath(
                store,
                collection,
                record,
                fieldPath,
                request == null ? RuleRequestContext.empty() : request,
                principal,
                allowHiddenFields,
                enforceRelatedListRules
        );
        if (!resolution.resolved()) {
            return null;
        }
        Object value = RuleEvaluator.firstSortValue(resolution.value());
        return switch (modifier) {
            case "" -> value;
            case "lower" -> value == null ? null : String.valueOf(value).toLowerCase(java.util.Locale.ROOT);
            case "length" -> valueLength(value);
            case "each" -> firstValue(value);
            case "isset" -> value != null;
            case "changed" -> false;
            default -> throw new ApiException(400, "Invalid search parameters.");
        };
    }

    public static boolean validPath(
            RecordProcessor.StoreContext store,
            CollectionSchema collection,
            String rawPath,
            boolean allowHiddenFields
    ) {
        return validPath(store::getCollection, collection, rawPath, allowHiddenFields);
    }

    public static boolean validPath(
            Function<String, CollectionSchema> collectionLookup,
            CollectionSchema collection,
            String rawPath,
            boolean allowHiddenFields
    ) {
        String path = withoutModifier(rawPath);
        String modifier = modifier(rawPath);
        if (path.isBlank() || path.startsWith("@")) {
            return false;
        }
        String[] parts = path.split("\\.");
        CollectionSchema current = collection;
        int relationDepth = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isBlank() || current == null) {
                return false;
            }
            if (SYSTEM_FIELDS.contains(part)) {
                return i == parts.length - 1 && regularModifierAllowed(null, modifier);
            }
            FieldSchema field = field(current, part);
            if (field == null) {
                BackRelation back = backRelation(collectionLookup, current, part);
                if (back == null || (!allowHiddenFields && back.field().hidden)) {
                    return false;
                }
                current = back.collection();
                relationDepth++;
                if (relationDepth > MAX_RELATION_DEPTH) {
                    return false;
                }
                if (i == parts.length - 1) {
                    return regularModifierAllowed(back.field(), modifier);
                }
                continue;
            }
            if (!allowHiddenFields && field.hidden) {
                return false;
            }
            if (!"relation".equalsIgnoreCase(field.type)) {
                return regularModifierAllowed(i == parts.length - 1 ? field : null, modifier);
            }
            if (i == parts.length - 1) {
                return regularModifierAllowed(field, modifier);
            }
            current = relationCollection(collectionLookup, field);
            relationDepth++;
            if (current == null || relationDepth > MAX_RELATION_DEPTH) {
                return false;
            }
        }
        return true;
    }

    public static FieldSchema field(CollectionSchema collection, String name) {
        if (collection == null || collection.fields == null) {
            return null;
        }
        for (FieldSchema field : collection.fields) {
            if (field != null && Objects.equals(field.name, name)) {
                return field;
            }
        }
        return null;
    }

    public static boolean requestBodyModifierAllowed(FieldSchema field, String modifier) {
        if (modifier == null || modifier.isBlank() || "lower".equals(modifier) || "isset".equals(modifier)) {
            return true;
        }
        if (field == null) {
            return false;
        }
        if ("changed".equals(modifier)) {
            return true;
        }
        return ("length".equals(modifier) || "each".equals(modifier)) && arrayable(field);
    }

    private static RuleEvaluator.Resolution resolveIdentifier(
            RecordProcessor.StoreContext store,
            CollectionSchema collection,
            Map<String, Object> record,
            Map<String, Object> body,
            RuleRequestContext request,
            RequestPrincipal principal,
            boolean allowHiddenFields,
            boolean enforceRelatedListRules,
            String identifier
    ) {
        if (identifier.startsWith("@request.body.")) {
            return resolveRecordPath(
                    store,
                    collection,
                    body,
                    identifier.substring("@request.body.".length()),
                    request,
                    principal,
                    allowHiddenFields,
                    false
            );
        }
        if (identifier.startsWith("@request.auth.")) {
            if (principal == null) {
                return RuleEvaluator.Resolution.resolved(null);
            }
            String path = identifier.substring("@request.auth.".length());
            if ("collectionId".equals(path)) {
                return RuleEvaluator.Resolution.resolved(principal.collectionId());
            }
            if ("collectionName".equals(path)) {
                return RuleEvaluator.Resolution.resolved(principal.collectionName());
            }
            CollectionSchema authCollection = store.getCollection(principal.collectionId());
            Map<String, Object> authRecord = authCollection == null ? null : store.getRecord(authCollection, principal.id());
            if (authCollection == null || authRecord == null) {
                return RuleEvaluator.Resolution.resolved("id".equals(path) ? principal.id() : null);
            }
            return resolveRecordPath(
                    store,
                    authCollection,
                    authRecord,
                    path,
                    request,
                    principal,
                    allowHiddenFields,
                    false
            );
        }
        if (identifier.startsWith("@")) {
            return RuleEvaluator.Resolution.unresolved();
        }
        return resolveRecordPath(
                store,
                collection,
                record,
                identifier,
                request,
                principal,
                allowHiddenFields,
                enforceRelatedListRules
        );
    }

    private static RuleEvaluator.Resolution resolveRecordPath(
            RecordProcessor.StoreContext store,
            CollectionSchema collection,
            Map<String, Object> record,
            String path,
            RuleRequestContext request,
            RequestPrincipal principal,
            boolean allowHiddenFields,
            boolean enforceRelatedListRules
    ) {
        if (store == null || collection == null || record == null || path == null || path.isBlank()) {
            return RuleEvaluator.Resolution.unresolved();
        }
        PathResult result = resolve(
                store,
                collection,
                List.of(record),
                path.split("\\."),
                0,
                request,
                principal,
                allowHiddenFields,
                enforceRelatedListRules,
                0,
                false
        );
        if (!result.resolved()) {
            return RuleEvaluator.Resolution.unresolved();
        }
        if (result.multiMatch()) {
            return RuleEvaluator.Resolution.resolved(RuleEvaluator.multiMatch(result.values()));
        }
        return RuleEvaluator.Resolution.resolved(result.values().isEmpty() ? null : result.values().get(0));
    }

    private static PathResult resolve(
            RecordProcessor.StoreContext store,
            CollectionSchema collection,
            List<Map<String, Object>> records,
            String[] parts,
            int index,
            RuleRequestContext request,
            RequestPrincipal principal,
            boolean allowHiddenFields,
            boolean enforceRelatedListRules,
            int relationDepth,
            boolean multiMatch
    ) {
        if (index >= parts.length) {
            return new PathResult(true, new ArrayList<>(records), multiMatch);
        }
        String part = parts[index];
        if (part.isBlank()) {
            return PathResult.unresolved();
        }

        FieldSchema field = field(collection, part);
        if (field == null && !SYSTEM_FIELDS.contains(part)) {
            BackRelation back = backRelation(store, collection, part);
            if (back == null || (!allowHiddenFields && back.field().hidden) || relationDepth >= MAX_RELATION_DEPTH) {
                return PathResult.unresolved();
            }
            List<Map<String, Object>> related = new ArrayList<>();
            for (Map<String, Object> current : records) {
                String currentId = text(current.get("id"));
                for (Map<String, Object> candidate : store.recordsForRule(back.collection().name)) {
                    if (!relationIds(candidate.get(back.field().name)).contains(currentId)) {
                        continue;
                    }
                    if (canUseRelatedRecord(
                            store,
                            back.collection(),
                            candidate,
                            request,
                            principal,
                            allowHiddenFields,
                            enforceRelatedListRules
                    )) {
                        related.add(candidate);
                    }
                }
            }
            if (index == parts.length - 1) {
                return new PathResult(
                        true,
                        related.stream().map(item -> item.get("id")).toList(),
                        multiMatch || backIsMultiple(back.field())
                );
            }
            return resolve(
                    store,
                    back.collection(),
                    related,
                    parts,
                    index + 1,
                    request,
                    principal,
                    allowHiddenFields,
                    enforceRelatedListRules,
                    relationDepth + 1,
                    multiMatch || backIsMultiple(back.field())
            );
        }

        if (field == null || !"relation".equalsIgnoreCase(field.type) || index == parts.length - 1) {
            List<Object> values = new ArrayList<>();
            String remainder = String.join(".", java.util.Arrays.copyOfRange(parts, index, parts.length));
            for (Map<String, Object> current : records) {
                values.add(readPath(current, remainder));
            }
            return new PathResult(true, values, multiMatch);
        }
        if (!allowHiddenFields && field.hidden) {
            return PathResult.unresolved();
        }
        if (relationDepth >= MAX_RELATION_DEPTH) {
            return PathResult.unresolved();
        }

        CollectionSchema target = relationCollection(store, field);
        if (target == null) {
            return PathResult.unresolved();
        }
        boolean multiple = isMultiple(field);

        // PocketBase treats a single relation's `.id` as the stored relation value.
        if (!multiple && index == parts.length - 2 && "id".equals(parts[index + 1])) {
            List<Object> values = records.stream().map(item -> item.get(field.name)).toList();
            return new PathResult(true, values, multiMatch);
        }

        List<Map<String, Object>> related = new ArrayList<>();
        for (Map<String, Object> current : records) {
            for (String id : relationIds(current.get(field.name))) {
                Map<String, Object> candidate = store.getRecord(target, id);
                if (candidate != null && canUseRelatedRecord(
                        store,
                        target,
                        candidate,
                        request,
                        principal,
                        allowHiddenFields,
                        enforceRelatedListRules
                )) {
                    related.add(candidate);
                }
            }
        }
        return resolve(
                store,
                target,
                related,
                parts,
                index + 1,
                request,
                principal,
                allowHiddenFields,
                enforceRelatedListRules,
                relationDepth + 1,
                multiMatch || multiple
        );
    }

    private static boolean canUseRelatedRecord(
            RecordProcessor.StoreContext store,
            CollectionSchema collection,
            Map<String, Object> record,
            RuleRequestContext request,
            RequestPrincipal principal,
            boolean allowHiddenFields,
            boolean enforceRelatedListRules
    ) {
        if (!enforceRelatedListRules || principal != null && principal.superuser()) {
            return true;
        }
        if (collection.listRule == null) {
            return false;
        }
        if (collection.listRule.isBlank()) {
            return true;
        }
        return RuleEvaluator.matches(
                collection.listRule,
                context(store, collection, record, null, request, "GET", principal, true, false)
        );
    }

    private static BackRelation backRelation(
            RecordProcessor.StoreContext store,
            CollectionSchema current,
            String property
    ) {
        Matcher matcher = BACK_RELATION.matcher(property);
        if (!matcher.matches()) {
            return null;
        }
        CollectionSchema backCollection = store.getCollection(matcher.group(1));
        FieldSchema backField = backCollection == null ? null : field(backCollection, matcher.group(2));
        if (backField == null || !"relation".equalsIgnoreCase(backField.type)) {
            return null;
        }
        CollectionSchema target = relationCollection(store, backField);
        if (target == null || current == null || !Objects.equals(target.id, current.id)) {
            return null;
        }
        return new BackRelation(backCollection, backField);
    }

    private static BackRelation backRelation(
            Function<String, CollectionSchema> collectionLookup,
            CollectionSchema current,
            String property
    ) {
        Matcher matcher = BACK_RELATION.matcher(property);
        if (!matcher.matches()) {
            return null;
        }
        CollectionSchema backCollection = collectionLookup.apply(matcher.group(1));
        FieldSchema backField = backCollection == null ? null : field(backCollection, matcher.group(2));
        if (backField == null || !"relation".equalsIgnoreCase(backField.type)) {
            return null;
        }
        CollectionSchema target = relationCollection(collectionLookup, backField);
        if (target == null || current == null || !Objects.equals(target.id, current.id)) {
            return null;
        }
        return new BackRelation(backCollection, backField);
    }

    private static CollectionSchema relationCollection(RecordProcessor.StoreContext store, FieldSchema field) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (field.collectionId != null && !field.collectionId.isBlank()) {
            candidates.add(field.collectionId);
        }
        if (field.collectionIds != null) {
            candidates.addAll(field.collectionIds);
        }
        JsonNode option = field.options == null ? null : field.options.get("collectionId");
        if (option != null && !option.asText().isBlank()) {
            candidates.add(option.asText());
        }
        for (String candidate : candidates) {
            CollectionSchema collection = store.getCollection(candidate);
            if (collection != null) {
                return collection;
            }
        }
        return null;
    }

    private static CollectionSchema relationCollection(
            Function<String, CollectionSchema> collectionLookup,
            FieldSchema field
    ) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (field.collectionId != null && !field.collectionId.isBlank()) {
            candidates.add(field.collectionId);
        }
        if (field.collectionIds != null) {
            candidates.addAll(field.collectionIds);
        }
        JsonNode option = field.options == null ? null : field.options.get("collectionId");
        if (option != null && !option.asText().isBlank()) {
            candidates.add(option.asText());
        }
        for (String candidate : candidates) {
            CollectionSchema collection = collectionLookup.apply(candidate);
            if (collection != null) {
                return collection;
            }
        }
        return null;
    }

    private static boolean regularModifierAllowed(FieldSchema field, String modifier) {
        if (modifier == null || modifier.isBlank() || "lower".equals(modifier)) {
            return true;
        }
        return ("length".equals(modifier) || "each".equals(modifier))
                && field != null
                && arrayable(field);
    }

    private static boolean arrayable(FieldSchema field) {
        return field != null && List.of("select", "file", "relation").contains(
                field.type == null ? "" : field.type.toLowerCase(java.util.Locale.ROOT)
        );
    }

    private static boolean isMultiple(FieldSchema field) {
        if (field.maxSelect != null) {
            return field.maxSelect > 1;
        }
        JsonNode option = field.options == null ? null : field.options.get("maxSelect");
        return option != null && option.asInt(1) > 1;
    }

    private static boolean backIsMultiple(FieldSchema field) {
        return isMultiple(field) || !field.unique;
    }

    private static List<String> relationIds(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(RecordFieldResolverSupport::text).filter(item -> !item.isBlank()).toList();
        }
        String single = text(value);
        return single.isBlank() ? List.of() : List.of(single);
    }

    @SuppressWarnings("unchecked")
    private static Object readPath(Object source, String path) {
        Object current = source;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || part.isBlank()) {
                return null;
            }
            current = ((Map<String, Object>) map).get(part);
        }
        return current;
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

    private static int valueLength(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Collection<?> collection) {
            return collection.size();
        }
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        return String.valueOf(value).isBlank() ? 0 : 1;
    }

    private static Object firstValue(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream().findFirst().orElse(null);
        }
        return value;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record BackRelation(CollectionSchema collection, FieldSchema field) {
    }

    private record PathResult(boolean resolved, List<Object> values, boolean multiMatch) {
        static PathResult unresolved() {
            return new PathResult(false, List.of(), false);
        }
    }
}
