package com.github.klboke.kkrepo.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ScanDeadlineTest {
  @Test
  void expiresFromOneMonotonicStartTime() {
    AtomicLong clock = new AtomicLong(100);
    ScanDeadline deadline = new ScanDeadline(Duration.ofNanos(5), clock::get);

    clock.set(104);
    assertEquals(Duration.ofNanos(1), deadline.remaining());
    clock.set(105);

    ScannerRequestException failure =
        assertThrows(ScannerRequestException.class, deadline::check);
    assertEquals("SCANNER_TIMEOUT", failure.code());
    assertEquals(504, failure.status());
    assertTrue(failure.retryable());
  }

  @Test
  void preservesAndClassifiesCancellationInterrupts() {
    ScanDeadline deadline = new ScanDeadline(30);
    Thread.currentThread().interrupt();
    try {
      ScannerRequestException failure =
          assertThrows(ScannerRequestException.class, deadline::check);
      assertEquals("SCANNER_INTERRUPTED", failure.code());
      assertTrue(failure.retryable());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }
}
