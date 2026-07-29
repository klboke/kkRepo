package com.github.klboke.kkrepo.scanner;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** One monotonic, interruption-aware deadline shared by every stage of a scanner request. */
final class ScanDeadline {
  private final LongSupplier nanoTime;
  private final long startedNanos;
  private final long timeoutNanos;

  ScanDeadline(int timeoutSeconds) {
    this(Duration.ofSeconds(Math.max(1, timeoutSeconds)), System::nanoTime);
  }

  ScanDeadline(Duration timeout, LongSupplier nanoTime) {
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    this.startedNanos = nanoTime.getAsLong();
    this.timeoutNanos = positiveNanos(timeout);
  }

  void check() {
    remainingNanos();
  }

  Duration remaining() {
    return Duration.ofNanos(remainingNanos());
  }

  private long remainingNanos() {
    if (Thread.currentThread().isInterrupted()) {
      throw new ScannerRequestException(
          "SCANNER_INTERRUPTED",
          "Scanner request was interrupted",
          503,
          true);
    }
    long elapsed = nanoTime.getAsLong() - startedNanos;
    long remaining = timeoutNanos - elapsed;
    if (remaining <= 0) {
      throw new ScannerRequestException(
          "SCANNER_TIMEOUT",
          "Scanner request exceeded its end-to-end time limit",
          504,
          true);
    }
    return remaining;
  }

  private static long positiveNanos(Duration timeout) {
    if (timeout == null || timeout.isNegative() || timeout.isZero()) {
      return 1;
    }
    try {
      return Math.max(1, timeout.toNanos());
    } catch (ArithmeticException e) {
      return Long.MAX_VALUE;
    }
  }
}
