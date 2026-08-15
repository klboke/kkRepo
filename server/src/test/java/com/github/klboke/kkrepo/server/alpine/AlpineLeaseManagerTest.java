package com.github.klboke.kkrepo.server.alpine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AlpineLeaseManagerTest {
  @Test
  void acquiresRenewsAndReleasesLeaseExactlyOnce() {
    AlpineRegistryDao registry = registry();
    when(registry.renewLease(anyString(), anyString(), anyLong(), any(), any()))
        .thenReturn(true);
    AlpineLeaseManager manager =
        new AlpineLeaseManager(registry, Duration.ofHours(1), Duration.ofMillis(5));

    AlpineLeaseManager.Lease lease = manager.acquire("alpine:test");
    assertEquals(7L, lease.fencingToken());
    assertFalse(lease.owner().isBlank());
    lease.assertHeld();
    lease.close();
    lease.close();

    verify(registry).renewLease(
        org.mockito.ArgumentMatchers.eq("alpine:test"),
        org.mockito.ArgumentMatchers.eq(lease.owner()),
        org.mockito.ArgumentMatchers.eq(7L), any(), any());
    verify(registry).releaseLease("alpine:test", lease.owner(), 7L);
    assertThrows(MavenExceptions.WritePolicyDenied.class, lease::assertHeld);
  }

  @Test
  void tryAcquireReturnsEmptyOrUsesDatabaseExpiry() {
    AlpineRegistryDao registry = mock(AlpineRegistryDao.class);
    AlpineLeaseManager manager =
        new AlpineLeaseManager(registry, Duration.ofHours(1), Duration.ofMillis(1));
    when(registry.tryAcquireLease(anyString(), anyString(), any(), any()))
        .thenReturn(Optional.empty());
    assertTrue(manager.tryAcquire("busy").isEmpty());

    Instant databaseExpiry = Instant.now().plusSeconds(120);
    when(registry.tryAcquireLease(anyString(), anyString(), any(), any()))
        .thenAnswer(invocation -> Optional.of(new AlpineRegistryDao.Lease(
            invocation.getArgument(0), invocation.getArgument(1), 9L, 1L,
            databaseExpiry, Instant.now())));
    try (AlpineLeaseManager.Lease lease = manager.tryAcquire("free").orElseThrow()) {
      assertEquals(9L, lease.fencingToken());
    }
  }

  @Test
  void acquisitionTimeoutAndInterruptFailClosed() {
    AlpineRegistryDao registry = mock(AlpineRegistryDao.class);
    when(registry.tryAcquireLease(anyString(), anyString(), any(), any()))
        .thenReturn(Optional.empty());
    AlpineLeaseManager manager =
        new AlpineLeaseManager(registry, Duration.ofSeconds(1), Duration.ZERO);
    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> manager.acquire("busy"));

    Thread.currentThread().interrupt();
    try {
      assertThrows(MavenExceptions.WritePolicyDenied.class,
          () -> new AlpineLeaseManager(registry, Duration.ofSeconds(1), Duration.ofSeconds(1))
              .acquire("interrupted"));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void lostRenewalFailsClosed() {
    AlpineRegistryDao registry = registry();
    when(registry.renewLease(anyString(), anyString(), anyLong(), any(), any()))
        .thenReturn(false);
    try (AlpineLeaseManager.Lease lease =
        new AlpineLeaseManager(registry, Duration.ofHours(1), Duration.ZERO).acquire("lost")) {
      assertThrows(MavenExceptions.WritePolicyDenied.class, lease::assertHeld);
    }
    verify(registry, times(1)).releaseLease(anyString(), anyString(), anyLong());
  }

  private static AlpineRegistryDao registry() {
    AlpineRegistryDao registry = mock(AlpineRegistryDao.class);
    when(registry.tryAcquireLease(anyString(), anyString(), any(), any()))
        .thenAnswer(invocation -> Optional.of(new AlpineRegistryDao.Lease(
            invocation.getArgument(0), invocation.getArgument(1), 7L, 1L,
            invocation.getArgument(3), Instant.now())));
    return registry;
  }
}
