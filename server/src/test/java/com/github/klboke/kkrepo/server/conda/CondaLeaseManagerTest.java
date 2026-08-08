package com.github.klboke.kkrepo.server.conda;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.CondaRegistryDao;
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
