package io.github.jackbaozz.pocketbase;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Query options for single-record and auth APIs.
 */
public final class RecordQuery {
    private final String expand;
    private final String fields;

    private RecordQuery(Builder builder) {
        this.expand = builder.expand;
        this.fields = builder.fields;
    }

    public static RecordQuery defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    Map<String, Object> toQuery() {
        Map<String, Object> query = new LinkedHashMap<>();
        if (expand != null && !expand.isBlank()) {
            query.put("expand", expand);
        }
        if (fields != null && !fields.isBlank()) {
            query.put("fields", fields);
        }
        return query;
    }

    public static final class Builder {
        private String expand;
        private String fields;

        private Builder() {
        }

        public Builder expand(String expand) {
            this.expand = expand;
            return this;
        }

        public Builder fields(String fields) {
            this.fields = fields;
            return this;
        }

        public RecordQuery build() {
            return new RecordQuery(this);
        }
    }
}
