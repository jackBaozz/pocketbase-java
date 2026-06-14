package io.github.jackbaozz.pocketbase;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Query options for collection record list APIs.
 */
public final class ListOptions {
    private final Integer page;
    private final Integer perPage;
    private final String sort;
    private final String filter;
    private final String expand;
    private final String fields;
    private final Boolean skipTotal;

    private ListOptions(Builder builder) {
        this.page = builder.page;
        this.perPage = builder.perPage;
        this.sort = builder.sort;
        this.filter = builder.filter;
        this.expand = builder.expand;
        this.fields = builder.fields;
        this.skipTotal = builder.skipTotal;
    }

    public static ListOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    Map<String, Object> toQuery() {
        Map<String, Object> query = new LinkedHashMap<>();
        putIfPresent(query, "page", page);
        putIfPresent(query, "perPage", perPage);
        putIfPresent(query, "sort", sort);
        putIfPresent(query, "filter", filter);
        putIfPresent(query, "expand", expand);
        putIfPresent(query, "fields", fields);
        putIfPresent(query, "skipTotal", skipTotal);
        return query;
    }

    private static void putIfPresent(Map<String, Object> query, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        query.put(key, value);
    }

    public static final class Builder {
        private Integer page;
        private Integer perPage;
        private String sort;
        private String filter;
        private String expand;
        private String fields;
        private Boolean skipTotal;

        private Builder() {
        }

        public Builder page(int page) {
            if (page < 1) {
                throw new IllegalArgumentException("page must be greater than or equal to 1");
            }
            this.page = page;
            return this;
        }

        public Builder perPage(int perPage) {
            if (perPage < 1) {
                throw new IllegalArgumentException("perPage must be greater than or equal to 1");
            }
            this.perPage = perPage;
            return this;
        }

        public Builder sort(String sort) {
            this.sort = sort;
            return this;
        }

        public Builder filter(String filter) {
            this.filter = filter;
            return this;
        }

        public Builder expand(String expand) {
            this.expand = expand;
            return this;
        }

        public Builder fields(String fields) {
            this.fields = fields;
            return this;
        }

        public Builder skipTotal(boolean skipTotal) {
            this.skipTotal = skipTotal;
            return this;
        }

        public ListOptions build() {
            return new ListOptions(this);
        }

        @Override
        public String toString() {
            return "ListOptions" + Objects.toString(build().toQuery());
        }
    }
}
