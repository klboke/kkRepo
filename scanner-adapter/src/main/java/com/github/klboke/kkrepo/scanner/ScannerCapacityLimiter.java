package com.github.klboke.kkrepo.scanner;

import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
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
  private static final long SCRATCH_PERMIT_BYTES = 1024L * 1024;

  private final ScannerAdapterProperties properties;
  private final Semaphore capacity;
  private final Semaphore scratchCapacity;
  private final Semaphore admission;
  private final int scratchLimitPermits;
  private final AtomicInteger active = new AtomicInteger();
  private final AtomicInteger activeScratchPermits = new AtomicInteger();
  private final AtomicInteger queued = new AtomicInteger();
  private final Counter rejected;

  public ScannerCapacityLimiter(
      ScannerAdapterProperties properties, MeterRegistry registry) {
    this.properties = properties;
    this.capacity = new Semaphore(properties.getMaxConcurrentScans(), true);
    this.scratchLimitPermits = (int) Math.max(
        1,
        Math.min(
            Integer.MAX_VALUE,
            properties.getMaxScratchBytes() / SCRATCH_PERMIT_BYTES));
    this.scratchCapacity = new Semaphore(scratchLimitPermits, true);
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
    Gauge.builder(
            "kkrepo_scanner_scratch_reserved_bytes",
            activeScratchPermits,
            value -> value.get() * (double) SCRATCH_PERMIT_BYTES)
        .description("Scratch bytes reserved by admitted scanner requests")
        .register(registry);
    Gauge.builder(
            "kkrepo_scanner_scratch_limit_bytes",
            properties,
            value -> value.getMaxScratchBytes())
        .description("Configured scanner scratch-space admission budget")
        .register(registry);
  }

  public <T> T execute(CheckedSupplier<T> action) throws IOException {
    return execute(0, action);
  }

  public <T> T execute(ResourceLimits limits, CheckedSupplier<T> action) throws IOException {
    return execute(scratchPermits(limits), action);
  }

  private <T> T execute(int scratchPermits, CheckedSupplier<T> action) throws IOException {
    if (!admission.tryAcquire()) {
      throw busy();
    }
    boolean capacityAcquired = false;
    boolean scratchAcquired = false;
    boolean queuedCounted = true;
    queued.incrementAndGet();
    try {
      Duration timeout = properties.getAdmissionTimeout();
      long nanos = timeoutNanos(timeout);
      long started = System.nanoTime();
      try {
        capacityAcquired =
            capacity.tryAcquire(Math.max(0, nanos), TimeUnit.NANOSECONDS);
        if (capacityAcquired && scratchPermits > 0) {
          long remaining = remainingNanos(nanos, started);
          scratchAcquired = scratchCapacity.tryAcquire(
              scratchPermits, remaining, TimeUnit.NANOSECONDS);
        } else {
          scratchAcquired = capacityAcquired;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ScannerRequestException(
            "SCANNER_ADMISSION_INTERRUPTED",
            "Scanner admission was interrupted",
            503,
            true,
            e);
      }
      if (!capacityAcquired || !scratchAcquired) {
        throw busy();
      }
      queued.decrementAndGet();
      queuedCounted = false;
      active.incrementAndGet();
      activeScratchPermits.addAndGet(scratchPermits);
      return action.get();
    } finally {
      if (capacityAcquired && scratchAcquired) {
        active.decrementAndGet();
        activeScratchPermits.addAndGet(-scratchPermits);
      }
      if (scratchAcquired && scratchPermits > 0) {
        scratchCapacity.release(scratchPermits);
      }
      if (capacityAcquired) capacity.release();
      if (queuedCounted) {
        queued.decrementAndGet();
      }
      admission.release();
    }
  }

  private int scratchPermits(ResourceLimits requested) {
    if (requested == null) {
      throw new ScannerRequestException(
          "RESOURCE_LIMITS_REQUIRED", "Scanner resource limits are required", 400, false);
    }
    long input = Math.min(requested.maxInputBytes(), properties.getMaxInputBytes());
    long single = Math.min(requested.maxSingleFileBytes(), properties.getMaxInputBytes());
    long expanded = Math.min(
        requested.maxUncompressedBytes(),
        saturatedMultiply(properties.getMaxInputBytes(), 4));
    int depth = Math.min(requested.maxNestedDepth(), 10);
    long retainedNested = Math.min(expanded, saturatedMultiply(single, depth));
    long processOutput = saturatedAdd(
        saturatedMultiply(properties.getMaxOutputBytes(), 3),
        properties.getMaxStderrBytes());
    long estimated = saturatedAdd(saturatedAdd(input, retainedNested), processOutput);
    long permits = ceilDiv(estimated, SCRATCH_PERMIT_BYTES);
    if (estimated > properties.getMaxScratchBytes()
        || permits > scratchLimitPermits
        || permits > Integer.MAX_VALUE) {
      rejected.increment();
      throw new ScannerRequestException(
          "SCANNER_SCRATCH_REQUEST_TOO_LARGE",
          "Scanner request exceeds the configured scratch-space budget",
          413,
          false);
    }
    return (int) Math.max(1, permits);
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

  private static long remainingNanos(long timeoutNanos, long startedNanos) {
    if (timeoutNanos == Long.MAX_VALUE) return Long.MAX_VALUE;
    return Math.max(0, timeoutNanos - (System.nanoTime() - startedNanos));
  }

  private static long saturatedAdd(long left, long right) {
    if (left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
    return left + right;
  }

  private static long saturatedMultiply(long value, long multiplier) {
    if (value == 0 || multiplier == 0) return 0;
    if (value > Long.MAX_VALUE / multiplier) return Long.MAX_VALUE;
    return value * multiplier;
  }

  private static long ceilDiv(long value, long divisor) {
    if (value <= 0) return 0;
    return 1 + (value - 1) / divisor;
  }

  @FunctionalInterface
  public interface CheckedSupplier<T> {
    T get() throws IOException;
  }
}
