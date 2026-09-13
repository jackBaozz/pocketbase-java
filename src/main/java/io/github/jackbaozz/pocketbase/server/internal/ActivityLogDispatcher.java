package io.github.jackbaozz.pocketbase.server.internal;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, single-consumer activity-log writer.
 *
 * <p>
 * HTTP workers only enqueue immutable events. Persistence failures and queue saturation are
 * deliberately isolated from the completed API response; the owning server drains accepted events
 * before closing the storage engine.
 */
public final class ActivityLogDispatcher implements AutoCloseable {
  private static final int DEFAULT_CAPACITY = 4096;
  private static final int MIN_CAPACITY = 64;
  private static final int MAX_CAPACITY = 100_000;
  private static final long DEFAULT_FLUSH_TIMEOUT_MILLIS = 5000L;
  private static final long MIN_FLUSH_TIMEOUT_MILLIS = 100L;
  private static final long MAX_FLUSH_TIMEOUT_MILLIS = 30_000L;

  private final StorageEngine store;
  private final ArrayBlockingQueue<ActivityLogEvent> queue;
  private final Object stateMonitor = new Object();
  private final AtomicBoolean accepting = new AtomicBoolean(true);
  private final AtomicLong accepted = new AtomicLong();
  private final AtomicLong completed = new AtomicLong();
  private final AtomicLong dropped = new AtomicLong();
  private final Thread worker;

  private ActivityLogDispatcher(StorageEngine store, int capacity) {
    this.store = store;
    this.queue = new ArrayBlockingQueue<>(capacity);
    this.worker = new Thread(this::run, "pocketbase-java-activity-log");
    this.worker.setDaemon(true);
    this.worker.start();
  }

  public static ActivityLogDispatcher create(StorageEngine store) {
    return new ActivityLogDispatcher(store, configuredCapacity());
  }

  static ActivityLogDispatcher forTesting(StorageEngine store, int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("activity log queue capacity must be positive");
    }
    return new ActivityLogDispatcher(store, capacity);
  }

  public boolean submit(
      String method,
      String url,
      int status,
      long duration,
      RequestPrincipal principal,
      Map<String, String> headers,
      String remoteIp) {
    ActivityLogEvent event =
        new ActivityLogEvent(
            method,
            url,
            status,
            duration,
            principal,
            headers == null ? Map.of() : Map.copyOf(headers),
            remoteIp);
    long droppedTotal = 0L;
    synchronized (stateMonitor) {
      if (!accepting.get() || !queue.offer(event)) {
        droppedTotal = dropped.incrementAndGet();
      } else {
        accepted.incrementAndGet();
        stateMonitor.notifyAll();
        return true;
      }
    }
    reportDropped(droppedTotal, "queue saturated");
    return false;
  }

  long acceptedCount() {
    return accepted.get();
  }

  long droppedCount() {
    return dropped.get();
  }

  public boolean flush(Duration timeout) {
    long timeoutNanos = Math.max(0L, timeout == null ? 0L : timeout.toNanos());
    long deadline = System.nanoTime() + timeoutNanos;
    synchronized (stateMonitor) {
      while (completed.get() < accepted.get()) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) {
          return false;
        }
        try {
          TimeUnit.NANOSECONDS.timedWait(stateMonitor, remaining);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
      return true;
    }
  }

  public boolean closeAndFlush(Duration timeout) {
    synchronized (stateMonitor) {
      if (accepting.getAndSet(false)) {
        stateMonitor.notifyAll();
      }
    }

    boolean drained = flush(timeout);
    if (!drained) {
      long abandoned;
      long droppedTotal = 0L;
      synchronized (stateMonitor) {
        abandoned = queue.size();
        queue.clear();
        if (abandoned > 0) {
          droppedTotal = dropped.addAndGet(abandoned);
          stateMonitor.notifyAll();
        }
      }
      reportDropped(droppedTotal, "flush timeout");
      worker.interrupt();
    }
    long joinMillis = Math.max(1L, Math.min(1000L, timeoutMillis(timeout)));
    try {
      worker.join(joinMillis);
    } catch (InterruptedException e) {
      worker.interrupt();
      Thread.currentThread().interrupt();
      return false;
    }
    return drained && !worker.isAlive();
  }

  @Override
  public void close() {
    closeAndFlush(Duration.ofMillis(configuredFlushTimeoutMillis()));
  }

  private void run() {
    try {
      while (true) {
        ActivityLogEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
        if (event != null) {
          try {
            store.recordActivityLog(
                event.method(),
                event.url(),
                event.status(),
                event.duration(),
                event.principal(),
                event.headers(),
                event.remoteIp());
          } catch (RuntimeException ignored) {
            // Activity logging must never alter the API response or prevent later events.
          } finally {
            synchronized (stateMonitor) {
              completed.incrementAndGet();
              stateMonitor.notifyAll();
            }
          }
          continue;
        }
        synchronized (stateMonitor) {
          if (!accepting.get() && queue.isEmpty()) {
            return;
          }
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      // A close timeout is allowed to abandon the remaining queue so storage can be released.
    }
  }

  private static int configuredCapacity() {
    return configuredInt(
        "PB_ACTIVITY_LOG_QUEUE_CAPACITY", DEFAULT_CAPACITY, MIN_CAPACITY, MAX_CAPACITY);
  }

  public static long configuredFlushTimeoutMillis() {
    return configuredInt(
        "PB_ACTIVITY_LOG_FLUSH_TIMEOUT_MS",
        (int) DEFAULT_FLUSH_TIMEOUT_MILLIS,
        (int) MIN_FLUSH_TIMEOUT_MILLIS,
        (int) MAX_FLUSH_TIMEOUT_MILLIS);
  }

  private static int configuredInt(String name, int fallback, int min, int max) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      value = System.getenv(name);
    }
    try {
      return Math.max(min, Math.min(max, Integer.parseInt(value)));
    } catch (RuntimeException ignored) {
      return fallback;
    }
  }

  private static long timeoutMillis(Duration timeout) {
    if (timeout == null) {
      return 0L;
    }
    return Math.max(0L, timeout.toMillis());
  }

  private void reportDropped(long droppedCount, String reason) {
    if (droppedCount > 0 && (droppedCount == 1 || (droppedCount & (droppedCount - 1)) == 0)) {
      System.err.println(
          "[pocketbase-java] activity log " + reason + "; dropped=" + droppedCount);
    }
  }

  private record ActivityLogEvent(
      String method,
      String url,
      int status,
      long duration,
      RequestPrincipal principal,
      Map<String, String> headers,
      String remoteIp) {
  }
}
