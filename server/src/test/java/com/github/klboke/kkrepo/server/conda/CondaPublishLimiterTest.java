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
import org.junit.jupiter.api.Test;

class CondaPublishLimiterTest {
  @Test
  void boundsPublicationCapacityAndReleasesPermit() throws Exception {
    CondaPublishLimiter limiter = new CondaPublishLimiter(1, 25);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      Future<String> holder = executor.submit(() -> limiter.execute(() -> {
        entered.countDown();
        await(release);
        return "published";
      }));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertThrows(MavenExceptions.WritePolicyDenied.class,
          () -> limiter.execute(() -> "blocked"));
      release.countDown();
      assertEquals("published", holder.get(2, TimeUnit.SECONDS));
      assertEquals("next", limiter.execute(() -> "next"));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void interruptedWaitPreservesInterruptStatus() {
    CondaPublishLimiter limiter = new CondaPublishLimiter(1, 25);
    Thread.currentThread().interrupt();
    try {
      assertThrows(MavenExceptions.WritePolicyDenied.class,
          () -> limiter.execute(() -> "never"));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
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
