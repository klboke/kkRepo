package com.github.klboke.kkrepo.server.conda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CondaProxyInventorySchedulerTest {
  @Test
  void collapsesTheSamePendingCoordinate() throws Exception {
    CondaProxyInventoryScheduler scheduler = new CondaProxyInventoryScheduler(1, 0, 2);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(1);
    AtomicInteger executions = new AtomicInteger();
    try {
      scheduler.schedule("repo:main:noarch", () -> {
        executions.incrementAndGet();
        entered.countDown();
        await(release);
        finished.countDown();
      });
      assertTrue(entered.await(2, TimeUnit.SECONDS));

      scheduler.schedule("repo:main:noarch", executions::incrementAndGet);
      release.countDown();

      assertTrue(finished.await(2, TimeUnit.SECONDS));
      assertEquals(1, executions.get());
    } finally {
      release.countDown();
      scheduler.close();
    }
  }

  @Test
  void boundsPendingCoordinates() throws Exception {
    CondaProxyInventoryScheduler scheduler = new CondaProxyInventoryScheduler(1, 0, 1);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(1);
    AtomicBoolean overflowRan = new AtomicBoolean();
    try {
      scheduler.schedule("repo:main:noarch", () -> {
        entered.countDown();
        await(release);
        finished.countDown();
      });
      assertTrue(entered.await(2, TimeUnit.SECONDS));

      scheduler.schedule("repo:main:linux-64", () -> overflowRan.set(true));
      assertEquals(1, scheduler.scheduledCount());
      release.countDown();

      assertTrue(finished.await(2, TimeUnit.SECONDS));
      assertFalse(overflowRan.get());
    } finally {
      release.countDown();
      scheduler.close();
    }
  }

  @Test
  void failedWorkCanBeRetriedAndRejectedSchedulingReleasesBookkeeping() throws Exception {
    CondaProxyInventoryScheduler scheduler = new CondaProxyInventoryScheduler(1, 0, 1);
    CountDownLatch attempted = new CountDownLatch(1);
    CountDownLatch retried = new CountDownLatch(1);
    try {
      scheduler.schedule("repo:main:noarch", () -> {
        attempted.countDown();
        throw new IllegalStateException("projection failed");
      });
      assertTrue(attempted.await(2, TimeUnit.SECONDS));
      for (int attempt = 0; attempt < 200 && scheduler.scheduledCount() != 0; attempt++) {
        Thread.sleep(5);
      }
      assertEquals(0, scheduler.scheduledCount());
      scheduler.schedule("repo:main:noarch", retried::countDown);
      assertTrue(retried.await(2, TimeUnit.SECONDS));
      for (int attempt = 0; attempt < 200 && scheduler.scheduledCount() != 0; attempt++) {
        Thread.sleep(5);
      }
      assertEquals(0, scheduler.scheduledCount());
    } finally {
      scheduler.close();
    }

    assertThrows(RejectedExecutionException.class,
        () -> scheduler.schedule("repo:closed", () -> { }));
    assertEquals(0, scheduler.scheduledCount());
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
