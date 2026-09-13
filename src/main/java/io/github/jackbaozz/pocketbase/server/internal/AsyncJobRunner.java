package io.github.jackbaozz.pocketbase.server.internal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/** Concurrent daemon executor whose close waits for already accepted maintenance jobs. */
final class AsyncJobRunner implements AutoCloseable {
  private final ExecutorService executor;
  private final AtomicBoolean closed = new AtomicBoolean();

  AsyncJobRunner(String threadName) {
    AtomicInteger counter = new AtomicInteger();
    executor =
        Executors.newCachedThreadPool(
            runnable -> {
              Thread thread = new Thread(runnable, threadName + "-" + counter.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
  }

  void execute(Runnable job) {
    if (closed.get()) {
      throw new IllegalStateException("storage engine is closed");
    }
    try {
      executor.execute(job);
    } catch (RejectedExecutionException e) {
      throw new IllegalStateException("storage engine is closed", e);
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    executor.shutdown();
    try {
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
