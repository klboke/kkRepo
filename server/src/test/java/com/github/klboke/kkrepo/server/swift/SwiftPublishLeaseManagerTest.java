package com.github.klboke.kkrepo.server.swift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SwiftPublishLeaseManagerTest {

  @Test
  void duplicatePublisherStopsWaitingAsSoonAsTheWinningReleaseIsVisible() {
    SwiftRegistryDao registry = mock(SwiftRegistryDao.class);
    when(registry.tryAcquireLease(anyString(), anyString(), any()))
        .thenReturn(Optional.empty());
    SwiftPublishLeaseManager leases = new SwiftPublishLeaseManager(registry);

    assertThrows(
        SwiftExceptions.Conflict.class,
        () -> leases.acquire("swift:1:acme:demo:1.0.0", () -> true));

    verify(registry).tryAcquireLease(anyString(), anyString(), any());
  }

  @Test
  void coalescedProxyReaderWaitsForTheCurrentMaterializerInsteadOfReturningConflict() {
    SwiftRegistryDao registry = mock(SwiftRegistryDao.class);
    SwiftRegistryDao.Lease acquired = new SwiftRegistryDao.Lease(
        "swift:1:acme:demo:1.0.0", "reader", 7L, Instant.MAX, Instant.EPOCH);
    when(registry.tryAcquireLease(anyString(), anyString(), any()))
        .thenReturn(Optional.empty(), Optional.of(acquired));
    SwiftPublishLeaseManager leases = new SwiftPublishLeaseManager(registry);

    SwiftPublishLeaseManager.Lease lease =
        leases.acquireForCoalescedRead("swift:1:acme:demo:1.0.0");

    assertEquals(7L, lease.fencingToken());
    verify(registry, times(2)).tryAcquireLease(anyString(), anyString(), any());
    lease.close();
  }
}
