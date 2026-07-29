package com.github.klboke.kkrepo.server.securityscan;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Process-local admission budget for scanner responses and their decoded projections.
 *
 * <p>Task ownership remains database-backed across replicas, but heap pressure is local to one
 * kkRepo process. Successful HTTP responses are parsed directly from a bounded stream with explicit
 * byte, token, nesting, string, projection-count, and list-count limits. Admission is derived from
 * those two independently enforced allocation drivers: three bytes of transient heap per accepted
 * wire byte covers UTF-8/text buffers plus the Base64 document and its defensive copy, and 256
 * bytes per accepted JSON token covers record, collection, reference, and scalar overhead. The
 * lease is held for the whole task so a parsed result cannot overlap another large response while
 * it is being validated and persisted.
 */
@Component
@ConditionalOnProperty(
    prefix = "kkrepo.security-scanning", name = "enabled", havingValue = "true")
public class SecurityScanResponseMemoryBudget {
  static final int TRANSIENT_BYTES_PER_WIRE_BYTE = 3;
  static final int TRANSIENT_BYTES_PER_JSON_TOKEN = 256;

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
      long wireReservation = Math.multiplyExact(
          properties.getMaxResponseBytes(), (long) TRANSIENT_BYTES_PER_WIRE_BYTE);
      long tokenReservation = Math.multiplyExact(
          properties.getMaxResponseTokens(), (long) TRANSIENT_BYTES_PER_JSON_TOKEN);
      reservation = Math.addExact(wireReservation, tokenReservation);
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
        "kkrepo.security-scanning.response-memory-budget-bytes cannot admit one bounded "
            + "response (configured " + properties.getResponseMemoryBudgetBytes()
            + " < max-response-bytes " + properties.getMaxResponseBytes() + " * "
            + TRANSIENT_BYTES_PER_WIRE_BYTE + " + max-response-tokens "
            + properties.getMaxResponseTokens() + " * "
            + TRANSIENT_BYTES_PER_JSON_TOKEN + ")");
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
