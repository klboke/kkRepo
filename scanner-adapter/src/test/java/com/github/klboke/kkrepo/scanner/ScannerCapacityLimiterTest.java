package com.github.klboke.kkrepo.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ScannerCapacityLimiterTest {
  @Test
  void boundsActiveAndQueuedScansAndReportsRejections() throws Exception {
    ScannerAdapterProperties properties = new ScannerAdapterProperties();
    properties.setMaxConcurrentScans(1);
    properties.setMaxQueuedScans(1);
    properties.setAdmissionTimeout(Duration.ofSeconds(2));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ScannerCapacityLimiter limiter = new ScannerCapacityLimiter(properties, registry);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch active = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      Future<String> first = executor.submit(() -> limiter.execute(() -> {
        active.countDown();
        try {
          assertTrue(release.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AssertionError(e);
        }
        return "first";
      }));
      assertTrue(active.await(1, TimeUnit.SECONDS));

      Future<String> second = executor.submit(() -> limiter.execute(() -> "second"));
      awaitGauge(registry, "kkrepo_scanner_queued", 1.0);

      ScannerRequestException rejected = assertThrows(
          ScannerRequestException.class,
          () -> limiter.execute(() -> "overflow"));
      assertEquals(429, rejected.status());
      assertEquals("SCANNER_CAPACITY_EXHAUSTED", rejected.code());
      assertTrue(rejected.retryable());
      assertEquals(
          1.0,
          registry.get("kkrepo_scanner_admission_rejected_total").counter().count());
      assertEquals(1.0, registry.get("kkrepo_scanner_active").gauge().value());

      release.countDown();
      assertEquals("first", first.get(2, TimeUnit.SECONDS));
      assertEquals("second", second.get(2, TimeUnit.SECONDS));
      assertEquals(0.0, registry.get("kkrepo_scanner_active").gauge().value());
      assertEquals(0.0, registry.get("kkrepo_scanner_queued").gauge().value());
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void timesOutQueuedRequestsAndPreservesInterruptStatus() throws Exception {
    ScannerAdapterProperties properties = new ScannerAdapterProperties();
    properties.setMaxConcurrentScans(1);
    properties.setMaxQueuedScans(1);
    properties.setAdmissionTimeout(Duration.ofMillis(20));
    ScannerCapacityLimiter limiter =
        new ScannerCapacityLimiter(properties, new SimpleMeterRegistry());
    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<Void> active = executor.submit(() -> limiter.execute(() -> {
      entered.countDown();
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return null;
    }));
    try {
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      ScannerRequestException timeout = assertThrows(
          ScannerRequestException.class,
          () -> limiter.execute(() -> "late"));
      assertEquals(429, timeout.status());

      Thread.currentThread().interrupt();
      ScannerRequestException interrupted = assertThrows(
          ScannerRequestException.class,
          () -> limiter.execute(() -> "interrupted"));
      assertEquals("SCANNER_ADMISSION_INTERRUPTED", interrupted.code());
      assertTrue(Thread.interrupted());
    } finally {
      release.countDown();
      try {
        active.get(2, TimeUnit.SECONDS);
      } catch (ExecutionException ignored) {
        // The active action is allowed to observe shutdown while the test unwinds.
      }
      executor.shutdownNow();
    }
  }

  private static void awaitGauge(
      SimpleMeterRegistry registry, String name, double expected) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (registry.get(name).gauge().value() != expected
        && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(1);
    }
    assertEquals(expected, registry.get(name).gauge().value());
  }
}
