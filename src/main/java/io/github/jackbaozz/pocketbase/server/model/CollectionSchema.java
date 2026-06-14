package io.github.jackbaozz.pocketbase.server.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Collection metadata persisted by the embedded runtime.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CollectionSchema {
    public String id;
    public String name;
    public String type = "base";
    public boolean system;
    public String listRule;
    public String viewRule;
    public String createRule;
    public String updateRule;
    public String deleteRule;
    public String created;
    public String updated;

    @JsonAlias("schema")
    public List<FieldSchema> fields = new ArrayList<>();

    public CollectionSchema() {
    }
}
