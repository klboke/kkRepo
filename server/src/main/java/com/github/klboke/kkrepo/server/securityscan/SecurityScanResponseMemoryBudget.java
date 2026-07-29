package com.github.klboke.kkrepo.server.securityscan;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Process-local admission budget for scanner responses and their decoded projections.
 *
 * <p>Task ownership remains database-backed across replicas, but heap pressure is local to one
 * kkRepo process. Successful HTTP responses are parsed directly from a bounded stream; reserving
 * twice the configured wire envelope covers the simultaneously live decoded byte arrays and
 * projection graph. The lease is held for the whole task so a parsed result cannot overlap another
 * large response while it is being validated and persisted.
 */
@Component
@ConditionalOnProperty(
    prefix = "kkrepo.security-scanning", name = "enabled", havingValue = "true")
public class SecurityScanResponseMemoryBudget {
  static final int RESPONSE_EXPANSION_FACTOR = 2;

  private final Semaphore permits;
  private final int maxConcurrentTasks;

  public SecurityScanResponseMemoryBudget(SecurityScanningProperties properties) {
    long configuredBudget = properties.getResponseMemoryBudgetBytes();
    long maximumHeapShare = Runtime.getRuntime().maxMemory() / 2;
    if (configuredBudget > maximumHeapShare) {
      throw new IllegalStateException(
          "kkrepo.security-scanning.response-memory-budget-bytes must not exceed half "
              + "of the configured JVM max heap (configured " + configuredBudget
              + " > " + maximumHeapShare + ")");
    }
    long reservation;
    try {
      reservation = Math.multiplyExact(
          properties.getMaxResponseBytes(), (long) RESPONSE_EXPANSION_FACTOR);
    } catch (ArithmeticException overflow) {
      throw invalidBudget(properties);
    }
    long admitted = configuredBudget / reservation;
    if (admitted < 1) {
      throw invalidBudget(properties);
    }
    maxConcurrentTasks = (int) Math.min(
        Math.min(admitted, Integer.MAX_VALUE),
        properties.getWorker().getBatchSize());
    permits = new Semaphore(maxConcurrentTasks, true);
  }

  public Lease acquire() throws InterruptedException {
    permits.acquire();
    return new Lease(permits);
  }

  int maxConcurrentTasks() {
    return maxConcurrentTasks;
  }

  private static IllegalStateException invalidBudget(SecurityScanningProperties properties) {
    return new IllegalStateException(
        "kkrepo.security-scanning.response-memory-budget-bytes must be at least twice "
            + "kkrepo.security-scanning.max-response-bytes (configured "
            + properties.getResponseMemoryBudgetBytes() + " < "
            + properties.getMaxResponseBytes() + " * " + RESPONSE_EXPANSION_FACTOR + ")");
  }

  public static final class Lease implements AutoCloseable {
    private final Semaphore permits;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Lease(Semaphore permits) {
      this.permits = permits;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        permits.release();
      }
    }
  }
}
