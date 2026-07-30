package com.github.klboke.kkrepo.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ScannerExecutionRegistryTest {
  @Test
  void interruptsAnActiveRunAndRemovesItsRegistration() throws Exception {
    ScannerExecutionRegistry registry = new ScannerExecutionRegistry();
    CountDownLatch started = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread thread = Thread.ofPlatform().start(() -> {
      try {
        registry.execute("run-1", () -> {
          started.countDown();
          try {
            new CountDownLatch(1).await();
            return "completed";
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
          }
        });
      } catch (Throwable error) {
        failure.set(error);
      }
    });

    assertTrue(started.await(2, TimeUnit.SECONDS));
    ScannerRequestException duplicate = assertThrows(
        ScannerRequestException.class,
        () -> registry.execute("run-1", () -> "duplicate"));
    assertEquals("SCANNER_RUN_ALREADY_ACTIVE", duplicate.code());

    assertTrue(registry.cancel("run-1"));
    thread.join(2000);

    assertFalse(thread.isAlive());
    assertInstanceOf(IOException.class, failure.get());
    assertFalse(registry.cancel("run-1"));
  }

  @Test
  void rejectsUnboundedOrMissingRunIdentifiers() {
    ScannerExecutionRegistry registry = new ScannerExecutionRegistry();

    for (String runId : new String[] {null, "", "has/slash", "a".repeat(129)}) {
      ScannerRequestException failure = assertThrows(
          ScannerRequestException.class,
          () -> registry.cancel(runId));
      assertEquals("SCANNER_RUN_ID_INVALID", failure.code());
    }
  }

  @Test
  void completedExecutionCannotInterruptAReusedThread() {
    ScannerExecutionRegistry.ActiveExecution execution =
        new ScannerExecutionRegistry.ActiveExecution(Thread.currentThread());

    execution.complete();

    assertFalse(execution.cancel());
    assertFalse(Thread.currentThread().isInterrupted());
  }
}
