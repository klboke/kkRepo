package com.github.klboke.kkrepo.server.conan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.ConanRegistryDao;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ConanLeaseManagerTest {
  private final ConanRegistryDao registry = mock(ConanRegistryDao.class);
  private final ConanLeaseManager manager = new ConanLeaseManager(registry);

  @AfterEach
  void clearTransactionStateAndInterrupt() {
    Thread.interrupted();
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void acquiresRenewsAndReleasesOneFencedLeaseIdempotently() {
    when(registry.tryAcquireLease(eq(7L), eq("demo/1.0"), anyString(), any(Instant.class)))
        .thenAnswer(call -> Optional.of(new ConanRegistryDao.Lease(
            7L, "demo/1.0", call.getArgument(2), 19L, call.getArgument(3), Instant.now())));
    when(registry.renewLease(
        eq(7L), eq("demo/1.0"), anyString(), eq(19L), any(Instant.class)))
        .thenReturn(true);

    ConanLeaseManager.Lease lease = manager.acquire(7L, "demo/1.0");
    assertEquals(19L, lease.fencingToken());
    Instant initialExpiry = lease.expiresAt();
    lease.assertHeld();
    assertTrue(!lease.expiresAt().isBefore(initialExpiry));
    lease.close();
    lease.close();

    verify(registry).renewLease(
        eq(7L), eq("demo/1.0"), anyString(), eq(19L), any(Instant.class));
    verify(registry, times(1)).releaseLease(
        eq(7L), eq("demo/1.0"), anyString(), eq(19L));
  }

  @Test
  void reportsBusyWhenRenewalLosesTheFence() {
    when(registry.tryAcquireLease(anyLong(), anyString(), anyString(), any(Instant.class)))
        .thenAnswer(call -> Optional.of(new ConanRegistryDao.Lease(
            call.getArgument(0), call.getArgument(1), call.getArgument(2), 3L,
            call.getArgument(3), Instant.now())));
    when(registry.renewLease(anyLong(), anyString(), anyString(), eq(3L), any(Instant.class)))
        .thenReturn(false);

    try (ConanLeaseManager.Lease lease = manager.acquire(1L, "coordinate")) {
      ConanExceptions.Busy failure = assertThrows(ConanExceptions.Busy.class, lease::assertHeld);
      assertTrue(failure.getMessage().contains("coordinate"));
    }
  }

  @Test
  void interruptedWaitFailsFastWithoutLeakingALease() {
    when(registry.tryAcquireLease(anyLong(), anyString(), anyString(), any(Instant.class)))
        .thenReturn(Optional.empty());
    Thread.currentThread().interrupt();

    assertThrows(ConanExceptions.Busy.class, () -> manager.acquire(1L, "busy"));
    assertTrue(Thread.currentThread().isInterrupted());
    verify(registry, never()).releaseLease(anyLong(), anyString(), anyString(), anyLong());
  }

  @Test
  void defersReleaseUntilTheSurroundingTransactionCompletes() {
    when(registry.tryAcquireLease(anyLong(), anyString(), anyString(), any(Instant.class)))
        .thenAnswer(call -> Optional.of(new ConanRegistryDao.Lease(
            call.getArgument(0), call.getArgument(1), call.getArgument(2), 5L,
            call.getArgument(3), Instant.now())));
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.initSynchronization();
    ConanLeaseManager.Lease lease = manager.acquire(1L, "transactional");

    lease.close();
    verify(registry, never()).releaseLease(anyLong(), anyString(), anyString(), anyLong());
    for (TransactionSynchronization synchronization
        : TransactionSynchronizationManager.getSynchronizations()) {
      synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
    }

    verify(registry).releaseLease(eq(1L), eq("transactional"), anyString(), eq(5L));
  }

  @Test
  void retriesWithBoundedBackoffBeforeAcquiringTheLease() {
    when(registry.tryAcquireLease(anyLong(), anyString(), anyString(), any(Instant.class)))
        .thenReturn(Optional.empty())
        .thenAnswer(call -> Optional.of(new ConanRegistryDao.Lease(
            call.getArgument(0), call.getArgument(1), call.getArgument(2), 7L,
            call.getArgument(3), Instant.now())));

    try (ConanLeaseManager.Lease lease = manager.acquire(1L, "contended")) {
      assertEquals(7L, lease.fencingToken());
    }

    verify(registry, times(2))
        .tryAcquireLease(eq(1L), eq("contended"), anyString(), any(Instant.class));
  }

  @Test
  void renewalThreadRefreshesThenStopsWhenTheFenceIsLost() throws Exception {
    when(registry.tryAcquireLease(anyLong(), anyString(), anyString(), any(Instant.class)))
        .thenAnswer(call -> Optional.of(new ConanRegistryDao.Lease(
            call.getArgument(0), call.getArgument(1), call.getArgument(2), 8L,
            call.getArgument(3), Instant.now())));
    when(registry.renewLease(anyLong(), anyString(), anyString(), eq(8L), any(Instant.class)))
        .thenReturn(true, false);

    ConanLeaseManager.Lease lease = manager.acquire(1L, "renewed");
    renewalThread(lease).interrupt();
    verify(registry, timeout(1_000).times(2)).renewLease(
        eq(1L), eq("renewed"), anyString(), eq(8L), any(Instant.class));
    assertTrue(lease.expiresAt().isAfter(Instant.now()));
    lease.close();
  }

  @Test
  void renewalThreadToleratesATransientDatabaseFailureWhileTheLeaseIsFresh()
      throws Exception {
    when(registry.tryAcquireLease(anyLong(), anyString(), anyString(), any(Instant.class)))
        .thenAnswer(call -> Optional.of(new ConanRegistryDao.Lease(
            call.getArgument(0), call.getArgument(1), call.getArgument(2), 9L,
            call.getArgument(3), Instant.now())));
    when(registry.renewLease(anyLong(), anyString(), anyString(), eq(9L), any(Instant.class)))
        .thenThrow(new IllegalStateException("database unavailable"))
        .thenReturn(false);

    ConanLeaseManager.Lease lease = manager.acquire(1L, "transient");
    renewalThread(lease).interrupt();
    verify(registry, timeout(1_000).times(2)).renewLease(
        eq(1L), eq("transient"), anyString(), eq(9L), any(Instant.class));
    lease.close();
  }

  private static Thread renewalThread(ConanLeaseManager.Lease lease) throws Exception {
    Field field = ConanLeaseManager.Lease.class.getDeclaredField("renewal");
    field.setAccessible(true);
    return (Thread) field.get(lease);
  }
}
