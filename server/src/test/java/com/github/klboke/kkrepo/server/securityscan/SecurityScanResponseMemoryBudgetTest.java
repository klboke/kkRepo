package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SecurityScanResponseMemoryBudgetTest {
  @Test
  void derivesSharedConcurrencyFromTheEnvelopeExpansionAndBudget() {
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.setMaxResponseBytes(1024);
    properties.setResponseMemoryBudgetBytes(4096);
    properties.getWorker().setBatchSize(4);

    SecurityScanResponseMemoryBudget budget =
        new SecurityScanResponseMemoryBudget(properties);

    assertEquals(2, budget.maxConcurrentTasks());
  }

  @Test
  void neverAdmitsMoreTasksThanTheWorkerCanClaim() {
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.setMaxResponseBytes(1024);
    properties.setResponseMemoryBudgetBytes(8192);
    properties.getWorker().setBatchSize(3);

    SecurityScanResponseMemoryBudget budget =
        new SecurityScanResponseMemoryBudget(properties);

    assertEquals(3, budget.maxConcurrentTasks());
  }

  @Test
  void rejectsAConfigurationThatCannotAdmitOneBoundedResponse() {
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.setMaxResponseBytes(4096);
    properties.setResponseMemoryBudgetBytes(4096);

    IllegalStateException failure = assertThrows(
        IllegalStateException.class,
        () -> new SecurityScanResponseMemoryBudget(properties));

    assertTrue(failure.getMessage().contains("at least twice"));
  }

  @Test
  void rejectsAnOverflowingResponseReservation() {
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.setMaxResponseBytes(Long.MAX_VALUE);

    IllegalStateException failure = assertThrows(
        IllegalStateException.class,
        () -> new SecurityScanResponseMemoryBudget(properties));

    assertTrue(failure.getMessage().contains("at least twice"));
  }

  @Test
  void rejectsABudgetLargerThanHalfTheJvmHeap() {
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.setMaxResponseBytes(1024);
    properties.setResponseMemoryBudgetBytes(Long.MAX_VALUE);

    IllegalStateException failure = assertThrows(
        IllegalStateException.class,
        () -> new SecurityScanResponseMemoryBudget(properties));

    assertTrue(failure.getMessage().contains("half"));
  }
}
