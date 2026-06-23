package io.github.jackbaozz.pocketbase.server.internal;

import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class RecordProcessor {
    private static final int MAX_EXPAND_DEPTH = 6;

    private RecordProcessor() {}

    public interface StoreContext {
        CollectionSchema getCollection(String nameOrId);
        Map<String, Object> getRecord(CollectionSchema collection, String id);
        boolean canView(CollectionSchema collection, Map<String, Object> record, Map<String, String> query, RequestPrincipal principal);
    }

    public static Map<String, Object> process(
            StoreContext ctx,
            CollectionSchema collection,
            Map<String, Object> record,
            boolean includeHidden,
            Map<String, String> query,
            RequestPrincipal principal
    ) {
        Map<String, Object> out = publicRecord(collection, record, includeHidden);
        Map<String, String> safeQuery = query == null ? Map.of() : query;
        applyExpand(ctx, collection, record, out, expandPaths(safeQuery.get("expand")), safeQuery, principal, includeHidden, 0);
        return selectFields(out, safeQuery.get("fields"));
    }

    public static Map<String, Object> publicRecord(CollectionSchema collection, Map<String, Object> record, boolean includeHidden) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", record.get("id"));
        out.put("collectionId", collection.id);
        out.put("collectionName", collection.name);
        out.put("created", record.get("created"));
        out.put("updated", record.get("updated"));
        for (FieldSchema field : collection.fields) {
            if ("password".equalsIgnoreCase(field.type)) continue;
            if (field.hidden && !includeHidden) continue;
            if (record.containsKey(field.name)) {
                out.put(field.name, record.get(field.name));
            }
        }
        return out;
    }

    private static void applyExpand(
            StoreContext ctx,
            CollectionSchema collection,
            Map<String, Object> source,
            Map<String, Object> output,
            List<List<String>> paths,
            Map<String, String> query,
            RequestPrincipal principal,
            boolean includeHidden,
            int depth
    ) {
        if (paths.isEmpty() || depth >= MAX_EXPAND_DEPTH) return;

        Map<String, List<List<String>>> grouped = new LinkedHashMap<>();
        for (List<String> path : paths) {
            if (path.isEmpty()) continue;
            List<String> tail = path.size() == 1 ? List.of() : path.subList(1, path.size());
            grouped.computeIfAbsent(path.get(0), ignored -> new ArrayList<>()).add(tail);
        }

        Map<String, Object> expanded = new LinkedHashMap<>();
        for (Map.Entry<String, List<List<String>>> entry : grouped.entrySet()) {
            FieldSchema field = relationField(collection, entry.getKey());
            if (field == null) continue;

            List<String> targetIds = new ArrayList<>();
            if (field.collectionId != null && !field.collectionId.isBlank()) {
                targetIds.add(field.collectionId);
            }
            if (field.collectionIds != null) {
                targetIds.addAll(field.collectionIds);
            }
            if (field.options != null && field.options.containsKey("collectionId")) {
                targetIds.add(field.options.get("collectionId").asText());
            }

            if (targetIds.isEmpty()) continue;

            CollectionSchema target = null;
            for (String tid : targetIds) {
                target = ctx.getCollection(tid);
                if (target != null) break;
            }
            if (target == null) continue;

            Object rawValue = source.get(field.name);
            List<Map<String, Object>> related = new ArrayList<>();
            for (String id : relationIds(rawValue)) {
                Map<String, Object> relatedRecord = ctx.getRecord(target, id);
                if (relatedRecord == null || !ctx.canView(target, relatedRecord, query, principal)) continue;
                Map<String, Object> relatedOutput = publicRecord(target, relatedRecord, includeHidden);
                applyExpand(ctx, target, relatedRecord, relatedOutput, entry.getValue(), query, principal, includeHidden, depth + 1);
                related.add(relatedOutput);
            }

            if (!related.isEmpty()) {
                int maxSelect = field.options != null && field.options.containsKey("maxSelect") ? field.options.get("maxSelect").asInt(1) : 1;
                expanded.put(entry.getKey(), maxSelect == 1 ? related.get(0) : related);
            }
        }

        if (!expanded.isEmpty()) {
            output.put("expand", expanded);
        }
    }

    private static FieldSchema relationField(CollectionSchema collection, String name) {
        for (FieldSchema field : collection.fields) {
            if (field.name.equals(name) && "relation".equalsIgnoreCase(field.type)) {
                return field;
            }
        }
        return null;
    }

    private static List<String> relationIds(Object value) {
        if (value instanceof String str) return List.of(str);
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    private static List<List<String>> expandPaths(String expandParam) {
        if (expandParam == null || expandParam.isBlank()) return List.of();
        return Arrays.stream(expandParam.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> Arrays.asList(s.split("\\.")))
                .toList();
    }

    public static Map<String, Object> selectFields(Map<String, Object> source, String fields) {
        if (fields == null || fields.isBlank() || "*".equals(fields.trim())) return source;

        List<List<String>> paths = Arrays.stream(fields.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.equals("*"))
                .map(s -> Arrays.asList(s.split("\\.")))
                .toList();

        if (paths.isEmpty()) return source;

        Map<String, Object> out = new LinkedHashMap<>();
        for (List<String> path : paths) {
            copySelectedField(source, out, path, 0);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void copySelectedField(Object source, Object target, List<String> path, int index) {
        if (index >= path.size() || source == null || target == null) return;
        String key = path.get(index);
        boolean isLast = index == path.size() - 1;

        if (source instanceof Map<?, ?> sourceMap && target instanceof Map targetMap) {
            if (!sourceMap.containsKey(key)) return;
            Object sourceVal = sourceMap.get(key);

            if (isLast) {
                targetMap.put(key, sourceVal);
                return;
            }

            if (sourceVal instanceof Map<?, ?> valMap) {
                Map<String, Object> nextTarget = (Map<String, Object>) targetMap.computeIfAbsent(key, k -> new LinkedHashMap<>());
                copySelectedField(valMap, nextTarget, path, index + 1);
            } else if (sourceVal instanceof List<?> valList) {
                List<Object> nextTargetList = (List<Object>) targetMap.computeIfAbsent(key, k -> new ArrayList<>());
                for (int i = 0; i < valList.size(); i++) {
                    if (i >= nextTargetList.size()) nextTargetList.add(new LinkedHashMap<>());
                    copySelectedField(valList.get(i), nextTargetList.get(i), path, index + 1);
                }
            }
        }
    }
}
