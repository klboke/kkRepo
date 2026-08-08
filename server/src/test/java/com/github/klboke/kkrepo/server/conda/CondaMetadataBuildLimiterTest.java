package com.github.klboke.kkrepo.server.conda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CondaMetadataBuildLimiterTest {
  @Test
  void collapsesSameCoordinateBeforeConsumingAnotherBuildPermit() throws Exception {
    CondaMetadataBuildLimiter limiter = new CondaMetadataBuildLimiter(2, 2_000);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch secondCalling = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger executions = new AtomicInteger();
    AtomicReference<Thread> secondThread = new AtomicReference<>();
    try {
      Future<String> first = executor.submit(() -> limiter.execute("repo/main/linux-64", () -> {
        executions.incrementAndGet();
        entered.countDown();
        await(release);
        return "metadata";
      }));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      Future<String> second = executor.submit(() -> {
        secondThread.set(Thread.currentThread());
        secondCalling.countDown();
        return limiter.execute("repo/main/linux-64", () -> {
          executions.incrementAndGet();
          return "duplicate";
        });
      });

      assertTrue(secondCalling.await(2, TimeUnit.SECONDS));
      assertTrue(awaitWaiting(secondThread));

      release.countDown();

      assertEquals("metadata", first.get(2, TimeUnit.SECONDS));
      assertEquals("metadata", second.get(2, TimeUnit.SECONDS));
      assertEquals(1, executions.get());
      assertEquals(0, limiter.inFlightCount());
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void boundsWaitForAnExistingCoordinate() throws Exception {
    CondaMetadataBuildLimiter limiter = new CondaMetadataBuildLimiter(1, 25);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      Future<String> leader = executor.submit(() -> limiter.execute("repo/main/noarch", () -> {
        entered.countDown();
        await(release);
        return "metadata";
      }));
      assertTrue(entered.await(2, TimeUnit.SECONDS));

      assertThrows(
          MavenExceptions.BadUpstreamException.class,
          () -> limiter.execute("repo/main/noarch", () -> "duplicate"));

      Thread.currentThread().interrupt();
      try {
        assertThrows(MavenExceptions.BadUpstreamException.class,
            () -> limiter.execute("repo/main/noarch", () -> "interrupted-duplicate"));
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }

      release.countDown();
      assertEquals("metadata", leader.get(2, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void blankCoordinatesUsePermitAndCapacityTimeoutsRemainBounded() throws Exception {
    CondaMetadataBuildLimiter limiter = new CondaMetadataBuildLimiter(1, 25);
    assertEquals("direct", limiter.execute(" ", () -> "direct"));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      Future<String> holder = executor.submit(() -> limiter.execute(() -> {
        entered.countDown();
        await(release);
        return "holder";
      }));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertThrows(MavenExceptions.BadUpstreamException.class,
          () -> limiter.execute(() -> "blocked"));
      release.countDown();
      assertEquals("holder", holder.get(2, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void interruptedCapacityWaitPreservesInterruptAndLeaderFailuresReachWaiters() throws Exception {
    CondaMetadataBuildLimiter interrupted = new CondaMetadataBuildLimiter(1, 25);
    Thread.currentThread().interrupt();
    try {
      assertThrows(MavenExceptions.BadUpstreamException.class,
          () -> interrupted.execute(() -> "never"));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }

    CondaMetadataBuildLimiter limiter = new CondaMetadataBuildLimiter(2, 2_000);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch followerStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Thread> followerThread = new AtomicReference<>();
    try {
      Future<?> leader = executor.submit(() -> assertThrows(
          IllegalArgumentException.class,
          () -> limiter.execute("failure", () -> {
            entered.countDown();
            await(release);
            throw new IllegalArgumentException("boom");
          })));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      Future<?> follower = executor.submit(() -> {
        followerThread.set(Thread.currentThread());
        followerStarted.countDown();
        assertThrows(IllegalArgumentException.class,
            () -> limiter.execute("failure", () -> "never"));
      });
      assertTrue(followerStarted.await(2, TimeUnit.SECONDS));
      assertTrue(awaitWaiting(followerThread));
      release.countDown();
      leader.get(2, TimeUnit.SECONDS);
      follower.get(2, TimeUnit.SECONDS);
      assertEquals(0, limiter.inFlightCount());
    } finally {
      release.countDown();
      executor.shutdownNow();
    }

    assertThrows(AssertionError.class,
        () -> limiter.execute("fatal", () -> { throw new AssertionError("fatal"); }));
  }

  private static boolean awaitWaiting(AtomicReference<Thread> thread) throws InterruptedException {
    for (int attempt = 0; attempt < 200; attempt++) {
      Thread.State state = thread.get().getState();
      if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) return true;
      Thread.sleep(5);
    }
    return false;
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for test release");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
    }
  }
}
