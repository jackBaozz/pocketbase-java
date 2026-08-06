package io.github.jackbaozz.pocketbase.server.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AuthFailedAttemptTrackerTest {

  private static final long T0 = 1_700_000_000_000L;

  @BeforeEach
  public void setUp() {
    AuthFailedAttemptTracker.resetAll();
  }

  @Test
  public void testLockAfterTenFailuresInWindow() {
    String identity = "testuser@example.com";
    String ip = "127.0.0.1";
    AuthFailedAttemptTracker.setClockOverride(T0);

    for (int i = 0; i < 9; i++) {
      AuthFailedAttemptTracker.checkLock(identity, ip);
      AuthFailedAttemptTracker.recordFailure(identity, ip);
    }

    // 9 failures: still allowed
    assertDoesNotThrow(() -> AuthFailedAttemptTracker.checkLock(identity, ip));

    // 10th failure locks immediately (429 on the same attempt)
    ApiException onTenth =
        assertThrows(
            ApiException.class,
            () -> AuthFailedAttemptTracker.recordFailureAndThrow(identity, ip));
    assertEquals(429, onTenth.status());
    assertTrue(AuthFailedAttemptTracker.isLocked(identity, ip));

    ApiException exception =
        assertThrows(
            ApiException.class, () -> AuthFailedAttemptTracker.checkLock(identity, ip));
    assertEquals(429, exception.status());

    AuthFailedAttemptTracker.AttemptState state = AuthFailedAttemptTracker.getState(identity);
    assertNotNull(state);
    assertEquals(10, state.count());
    assertEquals(T0, state.windowStart());
    assertEquals(T0 + AuthFailedAttemptTracker.LOCK_DURATION_MS, state.lockedUntil());
  }

  @Test
  public void testLockDoesNotAffectOtherIdentitiesOnSameIp() {
    String ip = "203.0.113.50";
    String lockedUser = "locked@ex.com";
    String otherUser = "other@ex.com";
    AuthFailedAttemptTracker.setClockOverride(T0);

    // Lock one account (10 failures).
    for (int i = 0; i < 10; i++) {
      try {
        AuthFailedAttemptTracker.recordFailureAndThrow(lockedUser, ip);
      } catch (ApiException ignored) {
        // expected 400 then 429
      }
    }
    assertTrue(AuthFailedAttemptTracker.isLocked(lockedUser, ip));
    assertEquals(10, AuthFailedAttemptTracker.getFailureCount(lockedUser, ip));

    // A different account on the same IP must remain usable with a fresh counter.
    assertDoesNotThrow(() -> AuthFailedAttemptTracker.checkLock(otherUser, ip));
    assertEquals(0, AuthFailedAttemptTracker.getFailureCount(otherUser, ip));
    assertTrue(!AuthFailedAttemptTracker.isLocked(otherUser, ip));

    ApiException firstOtherFail =
        assertThrows(
            ApiException.class,
            () -> AuthFailedAttemptTracker.recordFailureAndThrow(otherUser, ip));
    assertEquals(400, firstOtherFail.status());
    assertEquals(1, AuthFailedAttemptTracker.getFailureCount(otherUser, ip));
    // Original account stays locked independently.
    assertTrue(AuthFailedAttemptTracker.isLocked(lockedUser, ip));
  }

  @Test
  public void testWindowStartsAtFirstFailure() {
    String identity = "window@example.com";
    String ip = "10.0.0.1";

    // First failure at T0
    AuthFailedAttemptTracker.setClockOverride(T0);
    AuthFailedAttemptTracker.recordFailure(identity, ip);

    // 8 more near end of window (still inside)
    AuthFailedAttemptTracker.setClockOverride(T0 + AuthFailedAttemptTracker.WINDOW_DURATION_MS - 1);
    for (int i = 0; i < 8; i++) {
      AuthFailedAttemptTracker.recordFailure(identity, ip);
    }
    assertEquals(9, AuthFailedAttemptTracker.getState(identity).count());
    assertEquals(T0, AuthFailedAttemptTracker.getState(identity).windowStart());

    // 10th still in window → lock on that attempt
    ApiException locked =
        assertThrows(
            ApiException.class,
            () -> AuthFailedAttemptTracker.recordFailureAndThrow(identity, ip));
    assertEquals(429, locked.status());
    assertThrows(ApiException.class, () -> AuthFailedAttemptTracker.checkLock(identity, ip));
  }

  @Test
  public void testWindowExpiryResetsCycleWithoutLock() {
    String identity = "expire@example.com";
    String ip = "10.0.0.2";

    AuthFailedAttemptTracker.setClockOverride(T0);
    for (int i = 0; i < 9; i++) {
      AuthFailedAttemptTracker.recordFailure(identity, ip);
    }
    assertEquals(9, AuthFailedAttemptTracker.getState(identity).count());

    // After window from first failure: next failure starts a new cycle (count = 1)
    long nextCycleStart = T0 + AuthFailedAttemptTracker.WINDOW_DURATION_MS;
    AuthFailedAttemptTracker.setClockOverride(nextCycleStart);
    AuthFailedAttemptTracker.recordFailure(identity, ip);

    AuthFailedAttemptTracker.AttemptState state = AuthFailedAttemptTracker.getState(identity);
    assertNotNull(state);
    assertEquals(1, state.count());
    assertEquals(nextCycleStart, state.windowStart());
    assertEquals(0L, state.lockedUntil());
    assertDoesNotThrow(() -> AuthFailedAttemptTracker.checkLock(identity, ip));
  }

  @Test
  public void testUnlockAfterLockDurationStartsNewCycle() {
    String identity = "unlock@example.com";
    String ip = "10.0.0.3";

    AuthFailedAttemptTracker.setClockOverride(T0);
    for (int i = 0; i < 10; i++) {
      AuthFailedAttemptTracker.recordFailure(identity, ip);
    }
    assertThrows(ApiException.class, () -> AuthFailedAttemptTracker.checkLock(identity, ip));

    long lockEnd = T0 + AuthFailedAttemptTracker.LOCK_DURATION_MS;
    // Still locked just before unlock
    AuthFailedAttemptTracker.setClockOverride(lockEnd - 1);
    assertThrows(ApiException.class, () -> AuthFailedAttemptTracker.checkLock(identity, ip));

    // At unlock time, check passes; next failure starts a fresh cycle
    AuthFailedAttemptTracker.setClockOverride(lockEnd);
    assertDoesNotThrow(() -> AuthFailedAttemptTracker.checkLock(identity, ip));
    AuthFailedAttemptTracker.recordFailure(identity, ip);

    AuthFailedAttemptTracker.AttemptState state = AuthFailedAttemptTracker.getState(identity);
    assertNotNull(state);
    assertEquals(1, state.count());
    assertEquals(lockEnd, state.windowStart());
    assertEquals(0L, state.lockedUntil());
  }

  @Test
  public void testSuccessClearsCounter() {
    String identity = "testuser2@example.com";
    String ip = "127.0.0.2";
    AuthFailedAttemptTracker.setClockOverride(T0);

    for (int i = 0; i < 5; i++) {
      AuthFailedAttemptTracker.recordFailure(identity, ip);
    }

    AuthFailedAttemptTracker.recordSuccess(identity, ip);
    assertNull(AuthFailedAttemptTracker.getState(identity));

    // After success, another 9 failures shouldn't lock
    for (int i = 0; i < 9; i++) {
      AuthFailedAttemptTracker.recordFailure(identity, ip);
    }
    assertDoesNotThrow(() -> AuthFailedAttemptTracker.checkLock(identity, ip));
  }

  @Test
  public void testGetFailureCountSurvivesAcrossClients() {
    String identity = "count@example.com";
    String ip = "10.0.0.9";
    AuthFailedAttemptTracker.setClockOverride(T0);

    assertEquals(0, AuthFailedAttemptTracker.getFailureCount(identity, ip));

    for (int i = 1; i <= 3; i++) {
      AuthFailedAttemptTracker.recordFailure(identity, ip);
      // Browser refresh must not clear server-side cycle — count stays available.
      assertEquals(i, AuthFailedAttemptTracker.getFailureCount(identity, ip));
    }

    // After window expiry, effective count is 0 until next failure.
    AuthFailedAttemptTracker.setClockOverride(T0 + AuthFailedAttemptTracker.WINDOW_DURATION_MS);
    assertEquals(0, AuthFailedAttemptTracker.getFailureCount(identity, ip));
  }

  @Test
  public void testNextFailureStatePureLogic() {
    // first failure
    AuthFailedAttemptTracker.AttemptState s1 =
        AuthFailedAttemptTracker.nextFailureState(null, T0);
    assertEquals(1, s1.count());
    assertEquals(T0, s1.windowStart());
    assertEquals(0L, s1.lockedUntil());

    // accumulate to 10 within window
    AuthFailedAttemptTracker.AttemptState s = s1;
    for (int i = 2; i <= 10; i++) {
      s = AuthFailedAttemptTracker.nextFailureState(s, T0 + i);
    }
    assertEquals(10, s.count());
    assertTrue(s.lockedUntil() > 0);

    // during lock, state unchanged
    AuthFailedAttemptTracker.AttemptState duringLock =
        AuthFailedAttemptTracker.nextFailureState(s, s.lockedUntil() - 1);
    assertEquals(s.count(), duringLock.count());
    assertEquals(s.lockedUntil(), duringLock.lockedUntil());

    // after lock, new cycle
    AuthFailedAttemptTracker.AttemptState after =
        AuthFailedAttemptTracker.nextFailureState(s, s.lockedUntil());
    assertEquals(1, after.count());
    assertEquals(s.lockedUntil(), after.windowStart());
  }
}
