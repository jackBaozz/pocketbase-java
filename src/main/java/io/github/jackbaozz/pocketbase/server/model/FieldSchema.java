package io.github.jackbaozz.pocketbase.server.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Field definition compatible with PocketBase collection schema payloads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FieldSchema {
    public String id;
    public String name;
    public String type;
    public boolean required;
    public boolean unique;
    public boolean hidden;
    public boolean system;
    public boolean presentable;
    public String collectionId;
    public List<String> collectionIds;
    public Integer minSelect;
    public Integer maxSelect;
    public Integer maxFiles;
    public Long maxSize;
    public List<String> mimeTypes;
    public List<String> thumbs;
    @JsonProperty("protected")
    public Boolean protectedFile;
    public Map<String, JsonNode> options = new LinkedHashMap<>();

    public FieldSchema() {
    }

    public FieldSchema(String id, String name, String type, boolean required, boolean unique, boolean hidden) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.required = required;
        this.unique = unique;
        this.hidden = hidden;
    }
}
