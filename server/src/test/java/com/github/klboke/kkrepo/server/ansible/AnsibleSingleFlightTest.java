package com.github.klboke.kkrepo.server.ansible;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AnsibleSingleFlightTest {

  @Test
  void collapsesConcurrentWorkForTheSameKeyAndClearsTheFlight() throws Exception {
    AnsibleSingleFlight flights = new AnsibleSingleFlight();
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch loaderStarted = new CountDownLatch(1);
    CountDownLatch releaseLoader = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var first = executor.submit(() -> flights.execute("coordinate", () -> {
        calls.incrementAndGet();
        loaderStarted.countDown();
        await(releaseLoader);
        return "ready";
      }));
      assertTrue(loaderStarted.await(1, TimeUnit.SECONDS));
      AtomicReference<String> secondResult = new AtomicReference<>();
      AtomicReference<Throwable> secondFailure = new AtomicReference<>();
      Thread second = Thread.ofPlatform().start(() -> {
        try {
          secondResult.set(flights.execute("coordinate", () -> {
            calls.incrementAndGet();
            return "unexpected";
          }));
        } catch (Throwable failure) {
          secondFailure.set(failure);
        }
      });
      awaitWaiting(second);
      releaseLoader.countDown();

      assertEquals("ready", first.get(1, TimeUnit.SECONDS));
      second.join(1000);
      assertNull(secondFailure.get());
      assertEquals("ready", secondResult.get());
      assertEquals(1, calls.get());
      assertEquals(0, flights.inFlightCount());
    }
  }

  @Test
  void propagatesFailuresToFollowersAndAllowsARetry() {
    AnsibleSingleFlight flights = new AnsibleSingleFlight();
    IllegalStateException failure = assertThrows(
        IllegalStateException.class,
        () -> flights.execute("coordinate", () -> {
          throw new IllegalStateException("failed");
        }));

    assertEquals("failed", failure.getMessage());
    assertEquals("recovered", flights.execute("coordinate", () -> "recovered"));
    assertEquals(0, flights.inFlightCount());
  }

  @Test
  void doesNotWrapFatalErrors() {
    AnsibleSingleFlight flights = new AnsibleSingleFlight();

    AssertionError failure = assertThrows(
        AssertionError.class,
        () -> flights.execute("coordinate", () -> {
          throw new AssertionError("fatal");
        }));

    assertEquals("fatal", failure.getMessage());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(1, TimeUnit.SECONDS)) throw new AssertionError("timed out");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError(error);
    }
  }

  private static void awaitWaiting(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (thread.getState() != Thread.State.WAITING
        && thread.getState() != Thread.State.TIMED_WAITING) {
      if (!thread.isAlive() || System.nanoTime() >= deadline) {
        throw new AssertionError("single-flight follower did not wait");
      }
      Thread.sleep(1);
    }
  }
}
