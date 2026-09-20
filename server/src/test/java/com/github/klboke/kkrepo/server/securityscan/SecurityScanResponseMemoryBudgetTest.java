package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SecurityScanResponseMemoryBudgetTest {
  @Test
  void conservativeDefaultsAdmitOneLargeDecodedResponse() {
    SecurityScanResponseMemoryBudget budget =
        new SecurityScanResponseMemoryBudget(enabledProperties());

    assertEquals(1, budget.maxConcurrentTasks());
  }

  @Test
  void derivesSharedConcurrencyFromTheBoundedWireAndTokenEnvelope() {
    SecurityScanningProperties properties = enabledProperties();
    properties.setMaxResponseBytes(1024);
    properties.setMaxResponseTokens(1024);
    properties.setResponseMemoryBudgetBytes(530_432);
    properties.getWorker().setBatchSize(4);

    SecurityScanResponseMemoryBudget budget =
        new SecurityScanResponseMemoryBudget(properties);

    assertEquals(2, budget.maxConcurrentTasks());
  }

  @Test
  void neverAdmitsMoreTasksThanTheWorkerCanClaim() {
    SecurityScanningProperties properties = enabledProperties();
    properties.setMaxResponseBytes(1024);
    properties.setMaxResponseTokens(1024);
    properties.setResponseMemoryBudgetBytes(795_648);
    properties.getWorker().setBatchSize(3);

    SecurityScanResponseMemoryBudget budget =
        new SecurityScanResponseMemoryBudget(properties);

    assertEquals(3, budget.maxConcurrentTasks());
  }

  @Test
  void rejectsAConfigurationThatCannotAdmitOneBoundedResponse() {
    SecurityScanningProperties properties = enabledProperties();
    properties.setMaxResponseBytes(1024);
    properties.setMaxResponseTokens(1024);
    properties.setResponseMemoryBudgetBytes(265_215);

    IllegalStateException failure = assertThrows(
        IllegalStateException.class,
        () -> new SecurityScanResponseMemoryBudget(properties));

    assertTrue(failure.getMessage().contains("cannot admit"));
  }

  @Test
  void rejectsAnOverflowingResponseReservation() {
    SecurityScanningProperties properties = enabledProperties();
    properties.setMaxResponseBytes(Long.MAX_VALUE);
    properties.setMaxResponseTokens(Integer.MAX_VALUE);

    IllegalStateException failure = assertThrows(
        IllegalStateException.class,
        () -> new SecurityScanResponseMemoryBudget(properties));

    assertTrue(failure.getMessage().contains("cannot admit"));
  }

  @Test
  void rejectsABudgetLargerThanHalfTheJvmHeap() {
    SecurityScanningProperties properties = enabledProperties();
    properties.setMaxResponseBytes(1024);
    properties.setMaxResponseTokens(1024);
    properties.setResponseMemoryBudgetBytes(Long.MAX_VALUE);

    IllegalStateException failure = assertThrows(
        IllegalStateException.class,
        () -> new SecurityScanResponseMemoryBudget(properties));

    assertTrue(failure.getMessage().contains("half"));
  }

  @Test
  void disabledDeploymentDoesNotValidateOrReserveScannerResponseMemory() {
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.setResponseMemoryBudgetBytes(Long.MAX_VALUE);

    SecurityScanResponseMemoryBudget budget =
        new SecurityScanResponseMemoryBudget(properties);

    assertEquals(0, budget.maxConcurrentTasks());
    assertThrows(IllegalStateException.class, budget::acquire);
  }

  private static SecurityScanningProperties enabledProperties() {
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.setEnabled(true);
    return properties;
  }
}
