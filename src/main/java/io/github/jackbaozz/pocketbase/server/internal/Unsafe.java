package io.github.jackbaozz.pocketbase.server.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class Unsafe {
  private Unsafe() {
  }

  @SuppressWarnings("unchecked")
  private static <K, V> Map<K, V> map(Map<?, ?> source) {
    return (Map<K, V>) source;
  }

  @SuppressWarnings("unchecked")
  private static <T> List<T> list(List<?> source) {
    return (List<T>) source;
  }

  public static Map<String, Object> stringObjectMap(Map<?, ?> source) {
    return map(source);
  }

  public static List<Object> objectList(List<?> source) {
    return list(source);
  }

  public static List<Map<String, Object>> stringObjectMapList(List<?> source) {
    return list(source);
  }

  public static Map<String, Object> stringObjectMap(Object source) {
    if (source instanceof Map map) {
      return stringObjectMap(map);
    }
    throw new ClassCastException("Expected Map but got " + source);
  }

  public static List<Object> objectList(Object source) {
    if (source instanceof List list) {
      return objectList(list);
    }
    throw new ClassCastException("Expected List but got " + source);
  }

  public static List<Map<String, Object>> stringObjectMapList(Object source) {
    if (source instanceof List list) {
      return stringObjectMapList(list);
    }
    throw new ClassCastException("Expected List but got " + source);
  }

  public static void deepMerge(Map<String, Object> target, Map<String, Object> source) {
    if (source == null || source.isEmpty()) {
      return;
    }
    source.forEach(
        (rawKey, value) -> {
          String key = rawKey instanceof String ? (String) rawKey : String.valueOf(rawKey);
          if (target.containsKey(key)) {
            Object existing = target.get(key);
            if (existing instanceof Map existingMap && value instanceof Map sourceMap) {
              deepMerge(stringObjectMap(existingMap), stringObjectMap(sourceMap));
              target.put(key, existingMap);
            } else {
              target.put(key, value);
            }
            return;
          }
          target.put(key, value);
        });
  }

  public static void hideSensitiveSettings(Object value, Predicate<String> hiddenSettingKey) {
    if (value instanceof Map map) {
      Map<String, Object> target = stringObjectMap(map);
      for (Map.Entry<String, Object> entry : new ArrayList<>(target.entrySet())) {
        Object child = entry.getValue();
        if (hiddenSettingKey != null && hiddenSettingKey.test(entry.getKey())) {
          target.remove(entry.getKey());
        } else {
          hideSensitiveSettings(child, hiddenSettingKey);
        }
      }
      return;
    }
    if (value instanceof List list) {
      for (Object item : list) {
        hideSensitiveSettings(item, hiddenSettingKey);
      }
    }
  }

  public static void applySettingDefaults(
      Map<String, Object> target, Map<String, Object> defaults) {
    for (Map.Entry<String, Object> entry : defaults.entrySet()) {
      Object existing = target.get(entry.getKey());
      if (existing == null) {
        target.put(entry.getKey(), entry.getValue());
      } else if (existing instanceof Map existingMap
          && entry.getValue() instanceof Map defaultMap) {
        applySettingDefaults(stringObjectMap(existingMap), stringObjectMap(defaultMap));
      }
    }
  }

  public static Object readPath(Object source, String path) {
    if (source == null || path == null || path.isBlank()) {
      return null;
    }
    Object current = source;
    for (String part : path.split("\\.")) {
      if (part.isBlank()) {
        return null;
      }
      if (current instanceof Map map) {
        current = stringObjectMap(map).get(part);
      } else {
        return null;
      }
    }
    return current;
  }

  public static void copySelectedField(Object source, Object target, List<String> path, int index) {
    if (index >= path.size() || source == null || target == null) {
      return;
    }
    String key = path.get(index);
    boolean isLast = index == path.size() - 1;

    if (source instanceof Map<?, ?> sourceMap && target instanceof Map<?, ?> targetMap) {
      if ("*".equals(key)) {
        if (isLast) {
          Map<String, Object> typedTarget = stringObjectMap(targetMap);
          sourceMap.forEach(
              (sourceKey, value) -> typedTarget.put(String.valueOf(sourceKey), value));
        }
        return;
      }
      if (!sourceMap.containsKey(key)) {
        return;
      }
      Object sourceVal = sourceMap.get(key);

      if (isLast) {
        Map<String, Object> typedTarget = stringObjectMap(targetMap);
        typedTarget.put(key, sourceVal);
        return;
      }

      if (sourceVal instanceof Map<?, ?> valMap) {
        Map<String, Object> typedTarget = stringObjectMap(targetMap);
        Map<String, Object> nextTarget =
            stringObjectMap(typedTarget.computeIfAbsent(key, k -> new LinkedHashMap<>()));
        copySelectedField(valMap, nextTarget, path, index + 1);
      } else if (sourceVal instanceof List<?> valList) {
        Map<String, Object> typedTarget = stringObjectMap(targetMap);
        List<Object> nextTargetList =
            objectList(typedTarget.computeIfAbsent(key, k -> new ArrayList<>()));
        for (int i = 0; i < valList.size(); i++) {
          if (i >= nextTargetList.size()) {
            nextTargetList.add(new LinkedHashMap<>());
          }
          copySelectedField(valList.get(i), nextTargetList.get(i), path, index + 1);
        }
      }
    }
  }
}
