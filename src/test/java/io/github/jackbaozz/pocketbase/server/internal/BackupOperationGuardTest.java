package io.github.jackbaozz.pocketbase.server.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BackupOperationGuardTest {

  @Test
  void rejectsOverlappingOperationsAndReleasesTheActiveKey() {
    BackupOperationGuard guard = new BackupOperationGuard();

    String result =
        guard.run(
            "first.zip",
            () -> {
              assertFalse(guard.available());
              assertTrue(guard.active("first.zip"));
              ApiException error =
                  assertThrows(ApiException.class, () -> guard.run("second.zip", () -> "no"));
              assertEquals(400, error.status());
              return "ok";
            });

    assertEquals("ok", result);
    assertTrue(guard.available());
    assertFalse(guard.active("first.zip"));
  }

  @Test
  void releasesTheOperationAfterFailure() {
    BackupOperationGuard guard = new BackupOperationGuard();

    assertThrows(
        IllegalStateException.class,
        () -> guard.run(
            "broken.zip",
            () -> {
              throw new IllegalStateException("failed");
            }));
    assertTrue(guard.available());
  }
}
