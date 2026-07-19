package io.github.jackbaozz.pocketbase.server.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

public final class SearchQuerySupport {
    public static final int DEFAULT_PER_PAGE = 30;
    public static final int MAX_PER_PAGE = 1000;
    public static final int MAX_FILTER_LENGTH = 3500;
    public static final int MAX_SORT_EXPRESSIONS = 8;
    public static final int MAX_SORT_FIELD_LENGTH = 255;

    private SearchQuerySupport() {
    }

    public static Parameters parse(Map<String, String> query) {
        return parse(query, DEFAULT_PER_PAGE);
    }

    public static Parameters parse(Map<String, String> query, int defaultPerPage) {
        Map<String, String> safeQuery = query == null ? Map.of() : query;
        int normalizedDefault = defaultPerPage <= 0 ? DEFAULT_PER_PAGE : Math.min(defaultPerPage, MAX_PER_PAGE);

        int page = parseInteger(safeQuery.get("page"), 1);
        if (page <= 0) {
            page = 1;
        }

        int perPage = parseInteger(safeQuery.get("perPage"), normalizedDefault);
        if (perPage <= 0) {
            perPage = normalizedDefault;
        } else if (perPage > MAX_PER_PAGE) {
            perPage = MAX_PER_PAGE;
        }

        boolean skipTotal = parseBoolean(safeQuery.get("skipTotal"));
        String filter = safeQuery.getOrDefault("filter", "");
        if (filter.length() > MAX_FILTER_LENGTH) {
            throw invalidSearchParameters();
        }

        List<SortTerm> sort = parseSort(safeQuery.get("sort"));
        return new Parameters(page, perPage, skipTotal, filter, sort);
    }

    public static void rejectSuperuserOnlyRuleFields(Map<String, String> query, RequestPrincipal principal) {
        if (principal != null && principal.superuser()) {
            return;
        }
        Map<String, String> safeQuery = query == null ? Map.of() : query;
        for (String parameter : List.of("filter", "sort")) {
            String value = safeQuery.getOrDefault(parameter, "");
            for (String field : List.of("@collection.", "@request.")) {
                if (value.contains(field)) {
                    throw new ApiException(403, "Only superusers can filter by " + field);
                }
            }
        }
    }

    public static Map<String, Object> result(Parameters parameters, int total, List<?> items) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("page", parameters.page());
        response.put("perPage", parameters.perPage());
        response.put("totalItems", parameters.skipTotal() ? -1 : total);
        response.put("totalPages", parameters.skipTotal() ? -1 : totalPages(total, parameters.perPage()));
        response.put("items", items);
        return response;
    }

    public static void sortMaps(List<Map<String, Object>> items, List<SortTerm> requested, String defaultSort) {
        sortMaps(items, requested, defaultSort, SearchQuerySupport::readPath);
    }

    public static void sortMaps(
            List<Map<String, Object>> items,
            List<SortTerm> requested,
            String defaultSort,
            BiFunction<Map<String, Object>, String, Object> pathReader
    ) {
        List<SortTerm> terms = requested;
        if ((terms == null || terms.isEmpty()) && defaultSort != null && !defaultSort.isBlank()) {
            terms = parseSort(defaultSort);
        }
        if (terms == null || terms.isEmpty()) {
            return;
        }
        if (terms.stream().anyMatch(SortTerm::random)) {
            Collections.shuffle(items);
            return;
        }

        Comparator<Map<String, Object>> comparator = null;
        for (SortTerm term : terms) {
            Comparator<Map<String, Object>> next = (left, right) -> compareValues(
                    pathReader.apply(left, term.name()),
                    pathReader.apply(right, term.name())
            );
            if (term.descending()) {
                next = next.reversed();
            }
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        if (comparator != null) {
            items.sort(comparator);
        }
    }

    @SuppressWarnings("unchecked")
    public static Object readPath(Object source, String path) {
        if (source == null || path == null || path.isBlank()) {
            return null;
        }
        String modifier = "";
        int modifierIndex = path.lastIndexOf(':');
        if (modifierIndex > path.lastIndexOf('.')) {
            modifier = path.substring(modifierIndex + 1);
            path = path.substring(0, modifierIndex);
        }
        Object current = source;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || part.isBlank()) {
                return null;
            }
            current = ((Map<String, Object>) map).get(part);
        }
        return switch (modifier) {
            case "" -> current;
            case "lower" -> current == null ? null : String.valueOf(current).toLowerCase(java.util.Locale.ROOT);
            case "length" -> valueLength(current);
            case "each" -> current;
            case "isset" -> current != null;
            case "changed" -> false;
            default -> throw new ApiException(400, "Invalid search parameters.");
        };
    }

    private static int parseInteger(String raw, int fallback) {
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw invalidSearchParameters();
        }
    }

    private static boolean parseBoolean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        return switch (raw) {
            case "1", "t", "T", "true", "TRUE", "True" -> true;
            case "0", "f", "F", "false", "FALSE", "False" -> false;
            default -> throw invalidSearchParameters();
        };
    }

    private static List<SortTerm> parseSort(String raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        String[] parts = raw.split(",", -1);
        if (parts.length > MAX_SORT_EXPRESSIONS) {
            throw invalidSearchParameters();
        }
        List<SortTerm> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            String value = part.trim();
            boolean descending = value.startsWith("-");
            if (descending || value.startsWith("+")) {
                value = value.substring(1);
            }
            if (value.isEmpty() || value.length() > MAX_SORT_FIELD_LENGTH) {
                throw invalidSearchParameters();
            }
            result.add(new SortTerm(value, descending));
        }
        return List.copyOf(result);
    }

    private static int totalPages(int total, int perPage) {
        return total == 0 ? 0 : (int) Math.ceil((double) total / perPage);
    }

    private static int compareValues(Object left, Object right) {
        if (left == right) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return new BigDecimal(leftNumber.toString()).compareTo(new BigDecimal(rightNumber.toString()));
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private static int valueLength(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof java.util.Collection<?> collection) {
            return collection.size();
        }
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        return String.valueOf(value).isBlank() ? 0 : 1;
    }

    private static ApiException invalidSearchParameters() {
        return new ApiException(400, "Invalid search parameters.");
    }

    public record Parameters(
            int page,
            int perPage,
            boolean skipTotal,
            String filter,
            List<SortTerm> sort
    ) {
        public int fromIndex(int total) {
            long offset = (long) (page - 1) * perPage;
            return (int) Math.min(total, Math.max(0L, offset));
        }
    }

    public record SortTerm(String name, boolean descending) {
        public boolean random() {
            return "@random".equals(name);
        }

        public boolean rowId() {
            return "@rowid".equals(name);
        }
    }
}
