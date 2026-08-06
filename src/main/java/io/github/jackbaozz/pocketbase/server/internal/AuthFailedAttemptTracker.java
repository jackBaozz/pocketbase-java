package io.github.jackbaozz.pocketbase.server.internal;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks failed authentication attempts <strong>per identity (account) only</strong>.
 *
 * <p>Locking one account never blocks a different account, even from the same IP. The {@code
 * remoteIp} parameter is accepted for call-site compatibility and is ignored.
 *
 * <p>Cycle rules (from first failure in the cycle for that identity):
 * <ul>
 *   <li>Within a 10-minute window starting at the first failure, count failures.
 *   <li>If failures reach 10 inside that window, lock that identity for 10 minutes from the 10th
 *       failure.
 *   <li>The 10th failure itself returns 429 (locked), not a generic auth error.
 *   <li>When the window expires without locking, the next failure starts a new cycle.
 *   <li>When the lock expires, the next failure starts a new cycle.
 *   <li>A successful login clears the counter for that identity only.
 * </ul>
 */
public class AuthFailedAttemptTracker {

  static final int MAX_FAILED_ATTEMPTS = 10;
  static final long WINDOW_DURATION_MS = 10 * 60 * 1000L; // 10 minutes
  static final long LOCK_DURATION_MS = 10 * 60 * 1000L; // 10 minutes

  private static final String LOCK_MESSAGE =
      "Too many failed login attempts. Please try again after 10 minutes.";

  private static final ConcurrentHashMap<String, AttemptState> ATTEMPTS =
      new ConcurrentHashMap<>();

  /** Optional fixed clock for unit tests (epoch millis). 0 means use wall clock. */
  private static final AtomicLong CLOCK_OVERRIDE_MS = new AtomicLong(0L);

  public static class AttemptState {
    private final int count;
    /** Epoch millis of the first failure in the current cycle. */
    private final long windowStart;
    /** Epoch millis until which auth is locked; 0 when not locked. */
    private final long lockedUntil;

    public AttemptState(int count, long windowStart, long lockedUntil) {
      this.count = count;
      this.windowStart = windowStart;
      this.lockedUntil = lockedUntil;
    }

    int count() {
      return count;
    }

    long windowStart() {
      return windowStart;
    }

    long lockedUntil() {
      return lockedUntil;
    }
  }

  /**
   * @param remoteIp ignored — counters are per identity only so one locked account cannot block
   *     others on the same IP
   */
  public static void checkLock(String identity, String remoteIp) {
    if (isLocked(identity, remoteIp)) {
      throw lockedException(identity);
    }
  }

  /**
   * Records one failed attempt for the identity, then throws either a lock (429) or a generic
   * invalid-credentials (400) error. The 10th failure in a window throws 429 immediately.
   *
   * @param remoteIp ignored — see {@link #checkLock(String, String)}
   */
  public static void recordFailureAndThrow(String identity, String remoteIp) {
    recordFailure(identity, remoteIp);
    if (isLocked(identity, remoteIp)) {
      throw lockedException(identity);
    }
    throw new ApiException(
        400, "Failed to authenticate.", Map.of("failedAttempts", getFailureCount(identity, remoteIp)));
  }

  /**
   * @param remoteIp ignored — counters are per identity only
   */
  public static void recordFailure(String identity, String remoteIp) {
    String key = identityKey(identity);
    if (key == null) {
      return;
    }
    long now = nowMs();
    ATTEMPTS.compute(key, (k, existing) -> nextFailureState(existing, now));
  }

  /**
   * Computes the next attempt state after one failure at {@code now}.
   *
   * <p>Package-visible for unit tests.
   */
  static AttemptState nextFailureState(AttemptState existing, long now) {
    if (existing == null) {
      return new AttemptState(1, now, 0L);
    }

    // Still locked: keep state (checkLock already rejects auth).
    if (existing.lockedUntil > now) {
      return existing;
    }

    // Lock just expired, or the observation window from the first failure elapsed → new cycle.
    boolean lockExpired = existing.lockedUntil > 0 && existing.lockedUntil <= now;
    boolean windowExpired =
        existing.lockedUntil == 0 && now - existing.windowStart >= WINDOW_DURATION_MS;
    if (lockExpired || windowExpired) {
      return new AttemptState(1, now, 0L);
    }

    int newCount = existing.count + 1;
    long lockedUntil = newCount >= MAX_FAILED_ATTEMPTS ? now + LOCK_DURATION_MS : 0L;
    return new AttemptState(newCount, existing.windowStart, lockedUntil);
  }

  /**
   * Clears the failure counter for this identity only. Does not affect other accounts.
   *
   * @param remoteIp ignored
   */
  public static void recordSuccess(String identity, String remoteIp) {
    if (identity != null && !identity.isBlank()) {
      ATTEMPTS.remove(identityKey(identity));
    }
  }

  /**
   * @param remoteIp ignored — lock state is per identity only
   */
  public static boolean isLocked(String identity, String remoteIp) {
    return isKeyLocked(identityKey(identity), nowMs());
  }

  private static boolean isKeyLocked(String key, long now) {
    if (key == null) {
      return false;
    }
    AttemptState state = ATTEMPTS.get(key);
    return state != null && state.lockedUntil > now;
  }

  private static ApiException lockedException(String identity) {
    return new ApiException(
        429, LOCK_MESSAGE, Map.of("failedAttempts", getFailureCount(identity, null)));
  }

  /**
   * Returns the effective failure count for this identity only.
   *
   * <p>Expired windows and expired locks report {@code 0}. While locked, the pre-lock count is
   * still returned.
   *
   * @param remoteIp ignored
   */
  public static int getFailureCount(String identity, String remoteIp) {
    return effectiveCount(identityKey(identity), nowMs());
  }

  private static int effectiveCount(String key, long now) {
    if (key == null) {
      return 0;
    }
    AttemptState state = ATTEMPTS.get(key);
    if (state == null) {
      return 0;
    }
    // Still locked — report the count that triggered the lock.
    if (state.lockedUntil > now) {
      return state.count;
    }
    // Lock expired → cycle is over until the next failure.
    if (state.lockedUntil > 0) {
      return 0;
    }
    // Observation window from first failure elapsed → cycle is over.
    if (now - state.windowStart >= WINDOW_DURATION_MS) {
      return 0;
    }
    return state.count;
  }

  public static void resetAll() {
    ATTEMPTS.clear();
    clearClockOverride();
  }

  /** Package-visible for unit tests. */
  static AttemptState getState(String identity) {
    return ATTEMPTS.get(identityKey(identity));
  }

  /** Package-visible for unit tests. */
  static void setClockOverride(long epochMs) {
    CLOCK_OVERRIDE_MS.set(epochMs);
  }

  /** Package-visible for unit tests. */
  static void clearClockOverride() {
    CLOCK_OVERRIDE_MS.set(0L);
  }

  private static long nowMs() {
    long override = CLOCK_OVERRIDE_MS.get();
    return override > 0L ? override : Instant.now().toEpochMilli();
  }

  private static String identityKey(String identity) {
    return (identity == null || identity.isBlank()) ? null : "id:" + identity.trim().toLowerCase();
  }
}
