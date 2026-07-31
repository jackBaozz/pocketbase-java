package io.github.jackbaozz.pocketbase.server.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AsyncJobRunnerTest {

  @Test
  void runsJobsConcurrentlyAndWaitsForAcceptedWorkOnClose() throws Exception {
    AsyncJobRunner runner = new AsyncJobRunner("cron-test");
    CountDownLatch started = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger completed = new AtomicInteger();
    Runnable job =
        () -> {
          started.countDown();
          try {
            release.await();
            completed.incrementAndGet();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        };

    runner.execute(job);
    runner.execute(job);
    assertTrue(started.await(2, TimeUnit.SECONDS));
    release.countDown();
    runner.close();

    assertEquals(2, completed.get());
    assertThrows(IllegalStateException.class, () -> runner.execute(() -> {
    }));
  }
}
