package io.github.jackbaozz.pocketbase.server.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ActivityLogDispatcherTest {

  @Test
  void preservesAcceptedOrderAndFlushesBeforeClose() {
    List<String> received = new ArrayList<>();
    StorageEngine store = recordingStore(received, null, null);
    ActivityLogDispatcher dispatcher = ActivityLogDispatcher.forTesting(store, 8);

    assertTrue(dispatcher.submit("GET", "/one", 200, 1, null, Map.of(), ""));
    assertTrue(dispatcher.submit("GET", "/two", 200, 2, null, Map.of(), ""));
    assertTrue(dispatcher.flush(Duration.ofSeconds(2)));
    assertTrue(dispatcher.closeAndFlush(Duration.ofSeconds(2)));

    assertEquals(List.of("/one", "/two"), received);
    assertEquals(2, dispatcher.acceptedCount());
    assertEquals(0, dispatcher.droppedCount());
  }

  @Test
  void queueSaturationDropsWithoutBlockingTheCaller() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    StorageEngine store = recordingStore(new ArrayList<>(), started, release);
    ActivityLogDispatcher dispatcher = ActivityLogDispatcher.forTesting(store, 1);

    assertTrue(dispatcher.submit("GET", "/first", 200, 1, null, Map.of(), ""));
    assertTrue(started.await(2, TimeUnit.SECONDS));
    assertTrue(dispatcher.submit("GET", "/second", 200, 1, null, Map.of(), ""));
    long start = System.nanoTime();
    assertFalse(dispatcher.submit("GET", "/third", 200, 1, null, Map.of(), ""));
    assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 100);

    release.countDown();
    assertTrue(dispatcher.closeAndFlush(Duration.ofSeconds(2)));
    assertEquals(1, dispatcher.droppedCount());
  }

  @Test
  void persistenceFailureDoesNotStopLaterEvents() {
    List<String> received = new ArrayList<>();
    AtomicBoolean failFirst = new AtomicBoolean(true);
    StorageEngine store = recordingStore(received, null, null, failFirst);
    ActivityLogDispatcher dispatcher = ActivityLogDispatcher.forTesting(store, 8);

    assertTrue(dispatcher.submit("GET", "/failed", 500, 1, null, Map.of(), ""));
    assertTrue(dispatcher.submit("GET", "/kept", 200, 1, null, Map.of(), ""));
    assertTrue(dispatcher.closeAndFlush(Duration.ofSeconds(2)));

    assertEquals(List.of("/failed", "/kept"), received);
    assertEquals(2, dispatcher.acceptedCount());
  }

  @Test
  void timeoutAbandonsQueuedEventsAndReturnsPromptly() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    StorageEngine store = recordingStore(new ArrayList<>(), started, new CountDownLatch(1));
    ActivityLogDispatcher dispatcher = ActivityLogDispatcher.forTesting(store, 4);

    assertTrue(dispatcher.submit("GET", "/blocked", 200, 1, null, Map.of(), ""));
    assertTrue(started.await(2, TimeUnit.SECONDS));
    assertTrue(dispatcher.submit("GET", "/abandoned", 200, 1, null, Map.of(), ""));

    long start = System.nanoTime();
    assertFalse(dispatcher.closeAndFlush(Duration.ofMillis(100)));
    assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1000);
    assertTrue(dispatcher.droppedCount() >= 1);
  }

  private StorageEngine recordingStore(
      List<String> received, CountDownLatch started, CountDownLatch release) {
    return recordingStore(received, started, release, new AtomicBoolean());
  }

  private StorageEngine recordingStore(
      List<String> received,
      CountDownLatch started,
      CountDownLatch release,
      AtomicBoolean failFirst) {
    AtomicInteger calls = new AtomicInteger();
    return (StorageEngine) Proxy.newProxyInstance(
        StorageEngine.class.getClassLoader(),
        new Class<?>[] {StorageEngine.class},
        (proxy, method, args) -> {
          if ("recordActivityLog".equals(method.getName())) {
            String url = String.valueOf(args[1]);
            synchronized (received) {
              received.add(url);
            }
            if (started != null) {
              started.countDown();
            }
            if (release != null) {
              release.await(2, TimeUnit.SECONDS);
            }
            if (failFirst.compareAndSet(true, false)) {
              throw new IllegalStateException("simulated log failure");
            }
            return null;
          }
          if (method.getName().equals("mapper")) {
            return new ObjectMapper();
          }
          if (method.getReturnType() == boolean.class) {
            return false;
          }
          if (method.getReturnType() == int.class || method.getReturnType() == long.class) {
            return 0;
          }
          return null;
        });
  }
}
