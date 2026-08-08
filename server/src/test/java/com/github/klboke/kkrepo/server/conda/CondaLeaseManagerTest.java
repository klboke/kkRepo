package com.github.klboke.kkrepo.server.conda;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.CondaRegistryDao;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CondaLeaseManagerTest {
  @AfterEach
  void clearTransactionState() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void releasesImmediatelyOutsideATransaction() {
    CondaRegistryDao registry = registry();
    CondaLeaseManager.Lease lease = new CondaLeaseManager(registry).acquire("package:1");

    lease.close();

    verify(registry).releaseLease(anyString(), anyString(), anyLong());
    assertThrows(MavenExceptions.WritePolicyDenied.class, lease::assertHeld);
  }

  @Test
  void holdsLeaseUntilTheOuterTransactionCompletes() {
    CondaRegistryDao registry = registry();
    CondaLeaseManager.Lease lease = new CondaLeaseManager(registry).acquire("package:1");
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.initSynchronization();

    lease.close();

    verify(registry, never()).releaseLease(anyString(), anyString(), anyLong());
    TransactionSynchronizationManager.getSynchronizations().forEach(
        synchronization -> synchronization.afterCompletion(
            TransactionSynchronization.STATUS_COMMITTED));
    verify(registry).releaseLease(anyString(), anyString(), anyLong());
  }

  @Test
  void waiterConsumesTheCompletedWinnerResultWithoutTakingAnotherLease() {
    CondaRegistryDao registry = mock(CondaRegistryDao.class);
    when(registry.tryAcquireLease(anyString(), anyString(), any())).thenReturn(Optional.empty());

    Optional<CondaLeaseManager.Lease> acquired = new CondaLeaseManager(registry)
        .acquireUnlessCompleted("metadata:1", () -> true);

    assertTrue(acquired.isEmpty());
    verify(registry, never()).renewLease(anyString(), anyString(), anyLong(), any());
  }

  @Test
  void acquisitionAndRenewalFailuresFailClosedAndPreserveInterrupt() {
    CondaRegistryDao unavailable = mock(CondaRegistryDao.class);
    when(unavailable.tryAcquireLease(anyString(), anyString(), any())).thenReturn(Optional.empty());
    Thread.currentThread().interrupt();
    try {
      assertThrows(MavenExceptions.WritePolicyDenied.class,
          () -> new CondaLeaseManager(unavailable).acquire("busy"));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }

    CondaRegistryDao lost = registry();
    when(lost.renewLease(anyString(), anyString(), anyLong(), any())).thenReturn(false);
    CondaLeaseManager.Lease lease = new CondaLeaseManager(lost).acquire("lost");
    assertThrows(MavenExceptions.WritePolicyDenied.class, lease::assertHeld);
    lease.close();
    lease.close();
    verify(lost).releaseLease(anyString(), anyString(), anyLong());

    CondaRegistryDao failed = registry();
    when(failed.renewLease(anyString(), anyString(), anyLong(), any()))
        .thenThrow(new IllegalStateException("database unavailable"));
    try (CondaLeaseManager.Lease failing = new CondaLeaseManager(failed).acquire("failed")) {
      assertThrows(MavenExceptions.WritePolicyDenied.class, failing::assertHeld);
    }
  }

  private static CondaRegistryDao registry() {
    CondaRegistryDao registry = mock(CondaRegistryDao.class);
    when(registry.tryAcquireLease(anyString(), anyString(), any())).thenAnswer(invocation -> {
      String key = invocation.getArgument(0);
      String owner = invocation.getArgument(1);
      Instant expiresAt = invocation.getArgument(2);
      return Optional.of(new CondaRegistryDao.Lease(
          key, owner, 1, expiresAt, Instant.now()));
    });
    return registry;
  }
}
