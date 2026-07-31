package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/** Applies PocketBase OAuth2 known-field mappings when creating auth records. */
public final class OAuth2FieldMappingSupport {
  private OAuth2FieldMappingSupport() {
  }

  public static void normalize(CollectionSchema collection) {
    if (collection == null || collection.oauth2 == null) {
      return;
    }
    if (collection.oauth2.mappedFields == null) {
      collection.oauth2.mappedFields = new CollectionSchema.OAuth2MappedFields();
    }
    CollectionSchema.OAuth2MappedFields mapped = collection.oauth2.mappedFields;
    mapped.id = existingFieldOrEmpty(collection, mapped.id);
    mapped.name = existingFieldOrEmpty(collection, mapped.name);
    mapped.username = existingFieldOrEmpty(collection, mapped.username);
    mapped.avatarURL = existingFieldOrEmpty(collection, mapped.avatarURL);
  }

  public static Map<String, List<UploadedFile>> apply(
      CollectionSchema collection,
      ObjectNode payload,
      OAuth2Support.OAuth2User user,
      BiPredicate<String, String> canAssignUsername) {
    Map<String, List<UploadedFile>> files = new LinkedHashMap<>();
    if (collection == null || payload == null || user == null || collection.oauth2 == null) {
      return files;
    }
    normalize(collection);
    CollectionSchema.OAuth2MappedFields mapped = collection.oauth2.mappedFields;
    putIfMissing(payload, mapped.id, user.providerId());
    putIfMissing(payload, mapped.name, user.name());
    if (!isBlank(mapped.username)
        && !payload.has(mapped.username)
        && !isBlank(user.username())
        && (canAssignUsername == null
            || canAssignUsername.test(mapped.username, user.username()))) {
      payload.put(mapped.username, user.username());
    }
    if (!isBlank(mapped.avatarURL)
        && !payload.has(mapped.avatarURL)
        && !isBlank(user.avatarURL())) {
      FieldSchema field = field(collection, mapped.avatarURL);
      if (field != null && "file".equals(field.type)) {
        long maxSize = field.maxSize == null || field.maxSize <= 0 ? 5L << 20 : field.maxSize;
        OAuth2Support.downloadFile(user.avatarURL(), maxSize)
            .ifPresent(
                download -> files.put(
                    mapped.avatarURL,
                    List.of(
                        new UploadedFile(
                            mapped.avatarURL,
                            download.filename(),
                            download.contentType(),
                            download.bytes()))));
      } else {
        payload.put(mapped.avatarURL, user.avatarURL());
      }
    }
    return files;
  }

  private static void putIfMissing(ObjectNode payload, String field, String value) {
    if (!isBlank(field) && !payload.has(field) && !isBlank(value)) {
      payload.put(field, value);
    }
  }

  private static String existingFieldOrEmpty(CollectionSchema collection, String name) {
    return isBlank(name) || field(collection, name) == null ? "" : name.trim();
  }

  private static FieldSchema field(CollectionSchema collection, String name) {
    if (collection.fields == null || isBlank(name)) {
      return null;
    }
    return collection.fields.stream()
        .filter(candidate -> candidate != null && name.equals(candidate.name))
        .findFirst()
        .orElse(null);
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isBlank();
  }
}
