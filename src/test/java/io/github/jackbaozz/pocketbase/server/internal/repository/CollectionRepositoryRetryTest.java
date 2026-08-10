package io.github.jackbaozz.pocketbase.server.internal.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jackbaozz.pocketbase.server.internal.ApiException;
import java.util.Map;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;

class CollectionRepositoryRetryTest {

  @Test
  void recognizesSqliteLockHiddenByThePublicApiException() {
    ApiException publicError =
        new ApiException(
            400,
            "Failed to create collection.",
            Map.of(),
            new DataAccessException("[SQLITE_BUSY] The database file is locked"));

    assertTrue(CollectionRepository.isTransientSqliteLock(publicError));
  }

  @Test
  void doesNotRetryOrdinaryCollectionValidationErrors() {
    ApiException validationError =
        new ApiException(400, "Failed to create collection.", Map.of("name", "invalid"));

    assertFalse(CollectionRepository.isTransientSqliteLock(validationError));
  }
}
