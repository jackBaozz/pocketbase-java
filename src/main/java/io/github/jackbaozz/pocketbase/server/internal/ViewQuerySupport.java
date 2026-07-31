package io.github.jackbaozz.pocketbase.server.internal;

import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jooq.Asterisk;
import org.jooq.QualifiedAsterisk;
import org.jooq.Query;
import org.jooq.SQLDialect;
import org.jooq.Select;
import org.jooq.impl.DSL;

/** Shared PocketBase-compatible validation and response shaping for view query previews. */
public final class ViewQuerySupport {
  public static final String WILDCARD_ERROR =
      "wildcard columns (*) are not supported - manually type the collection field names you want the view query to have";

  private ViewQuerySupport() {
  }

  public record Column(String name, String type) {
  }

  public static String normalizeSingleSelect(String query) {
    String normalized = query == null ? "" : query.trim();
    if (normalized.isBlank()) {
      throw new IllegalArgumentException("empty query");
    }

    Query[] queries;
    try {
      queries = DSL.using(SQLDialect.SQLITE).parser().parse(normalized).queries();
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
    if (queries.length > 1) {
      throw new IllegalArgumentException("multiple statements are not supported");
    }
    if (queries.length == 0) {
      throw new IllegalArgumentException("empty query");
    }
    if (!(queries[0] instanceof Select<?> select)) {
      throw new IllegalArgumentException("write statements are not allowed");
    }
    if (select.$select().stream()
        .anyMatch(item -> item instanceof Asterisk || item instanceof QualifiedAsterisk)) {
      throw new IllegalArgumentException(WILDCARD_ERROR);
    }

    normalized = normalized.strip();
    while (normalized.endsWith(";")) {
      normalized = normalized.substring(0, normalized.length() - 1).stripTrailing();
    }
    if (normalized.startsWith("(") && normalized.endsWith(")")) {
      normalized = normalized.substring(1, normalized.length() - 1).trim();
    }
    return normalized;
  }

  public static Map<String, Object> result(List<Column> columns, List<List<Object>> rows) {
    int idIndex = -1;
    Set<String> names = new HashSet<>();
    for (int i = 0; i < columns.size(); i++) {
      String name = columns.get(i).name();
      if (!names.add(name)) {
        throw new IllegalArgumentException("duplicate column names are not supported");
      }
      if ("id".equals(name)) {
        idIndex = i;
      }
    }
    if (idIndex < 0) {
      throw new IllegalArgumentException(
          "missing required id column (you can use `(ROW_NUMBER() OVER()) as id` if you don't have one)");
    }

    List<Map<String, Object>> sample = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (List<Object> values : rows) {
      Map<String, Object> record = new LinkedHashMap<>();
      for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
        Object value = columnIndex < values.size() ? values.get(columnIndex) : null;
        if (columnIndex == idIndex) {
          String id = value == null ? "" : String.valueOf(value);
          if (id.isBlank()) {
            throw new IllegalArgumentException(
                "the query could return records with empty or invalid ids");
          }
          if (!ids.add(id)) {
            throw new IllegalArgumentException(
                "the query could return records with non-unique ids");
          }
          value = id;
        }
        record.put(columns.get(columnIndex).name(), value);
      }
      sample.add(record);
    }

    List<Map<String, Object>> fields = new ArrayList<>();
    for (int i = 0; i < columns.size(); i++) {
      Column column = columns.get(i);
      String type = normalizedType(column.name(), column.type(), firstNonNull(rows, i));
      Map<String, Object> field = new LinkedHashMap<>();
      field.put("id", "");
      field.put("name", column.name());
      field.put("type", type);
      field.put("system", "id".equals(column.name()));
      field.put("hidden", false);
      field.put("presentable", false);
      field.put("required", "id".equals(column.name()));
      if ("id".equals(column.name())) {
        field.put("primaryKey", true);
        field.put("pattern", "^[a-z0-9]+$");
      }
      fields.add(field);
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("fields", fields);
    result.put("sample", sample);
    return result;
  }

  public static String typeForJavaClass(String name, Class<?> type) {
    if ("id".equals(name)) {
      return "text";
    }
    if (type == null) {
      return "json";
    }
    if (Boolean.class.isAssignableFrom(type) || type == boolean.class) {
      return "bool";
    }
    if (Number.class.isAssignableFrom(type)
        || type == byte.class
        || type == short.class
        || type == int.class
        || type == long.class
        || type == float.class
        || type == double.class) {
      return "number";
    }
    if (TemporalAccessor.class.isAssignableFrom(type)
        || java.util.Date.class.isAssignableFrom(type)) {
      return "date";
    }
    if (Map.class.isAssignableFrom(type)
        || Iterable.class.isAssignableFrom(type)
        || type.isArray()) {
      return "json";
    }
    return "text";
  }

  private static Object firstNonNull(List<List<Object>> rows, int index) {
    for (List<Object> row : rows) {
      if (index < row.size() && row.get(index) != null) {
        return row.get(index);
      }
    }
    return null;
  }

  private static String normalizedType(String name, String type, Object sampleValue) {
    if ("id".equals(name)) {
      return "text";
    }
    if (type != null && !type.isBlank()) {
      return type.toLowerCase(Locale.ROOT);
    }
    return sampleValue == null ? "json" : typeForJavaClass(name, sampleValue.getClass());
  }
}
