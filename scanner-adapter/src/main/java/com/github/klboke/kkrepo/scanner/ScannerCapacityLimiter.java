package com.github.klboke.kkrepo.scanner;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** Fair, bounded admission control for memory- and disk-intensive scanner processes. */
@Component
public class ScannerCapacityLimiter {
  private final ScannerAdapterProperties properties;
  private final Semaphore capacity;
  private final Semaphore admission;
  private final AtomicInteger active = new AtomicInteger();
  private final AtomicInteger queued = new AtomicInteger();
  private final Counter rejected;

  public ScannerCapacityLimiter(
      ScannerAdapterProperties properties, MeterRegistry registry) {
    this.properties = properties;
    this.capacity = new Semaphore(properties.getMaxConcurrentScans(), true);
    this.admission = new Semaphore(
        properties.getMaxConcurrentScans() + properties.getMaxQueuedScans(), true);
    this.rejected = Counter.builder("kkrepo_scanner_admission_rejected_total")
        .description("Scanner requests rejected by bounded admission control")
        .register(registry);
    Gauge.builder("kkrepo_scanner_active", active, AtomicInteger::get)
        .description("Scanner requests actively executing")
        .register(registry);
    Gauge.builder("kkrepo_scanner_queued", queued, AtomicInteger::get)
        .description("Scanner requests waiting for execution capacity")
        .register(registry);
    Gauge.builder(
            "kkrepo_scanner_capacity_limit",
            properties,
            value -> value.getMaxConcurrentScans())
        .description("Configured scanner execution concurrency")
        .register(registry);
  }

  public <T> T execute(CheckedSupplier<T> action) throws IOException {
    if (!admission.tryAcquire()) {
      throw busy();
    }
    boolean acquired = false;
    queued.incrementAndGet();
    try {
      Duration timeout = properties.getAdmissionTimeout();
      long nanos = timeoutNanos(timeout);
      try {
        acquired = capacity.tryAcquire(Math.max(0, nanos), TimeUnit.NANOSECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ScannerRequestException(
            "SCANNER_ADMISSION_INTERRUPTED",
            "Scanner admission was interrupted",
            503,
            true,
            e);
      }
      if (!acquired) {
        throw busy();
      }
      queued.decrementAndGet();
      active.incrementAndGet();
      return action.get();
    } finally {
      if (acquired) {
        active.decrementAndGet();
        capacity.release();
      } else {
        queued.decrementAndGet();
      }
      admission.release();
    }
  }

  private ScannerRequestException busy() {
    rejected.increment();
    return new ScannerRequestException(
        "SCANNER_CAPACITY_EXHAUSTED",
        "Scanner execution capacity is temporarily exhausted",
        429,
        true);
  }

  private static long timeoutNanos(Duration timeout) {
    if (timeout == null || timeout.isNegative() || timeout.isZero()) return 0;
    try {
      return timeout.toNanos();
    } catch (ArithmeticException e) {
      return Long.MAX_VALUE;
    }
  }

  @FunctionalInterface
  public interface CheckedSupplier<T> {
    T get() throws IOException;
  }
}
