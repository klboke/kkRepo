package com.github.klboke.kkrepo.server.r;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RLeaseManagerTest {
  @Test
  void acquiresRenewsAndReleasesLeaseExactlyOnce() {
    RRegistryDao registry = registry();
    when(registry.renewLease(anyString(), anyString(), anyLong(), any(), any()))
        .thenReturn(true);
    RLeaseManager manager =
        new RLeaseManager(registry, Duration.ofHours(1), Duration.ofMillis(5));

    RLeaseManager.Lease lease = manager.acquire("r:test");
    assertEquals(7L, lease.fencingToken());
    assertFalse(lease.owner().isBlank());
    lease.assertHeld();
    lease.close();
    lease.close();

    verify(registry).renewLease(
        org.mockito.ArgumentMatchers.eq("r:test"),
        org.mockito.ArgumentMatchers.eq(lease.owner()),
        org.mockito.ArgumentMatchers.eq(7L), any(), any());
    verify(registry).releaseLease("r:test", lease.owner(), 7L);
    assertThrows(MavenExceptions.WritePolicyDenied.class, lease::assertHeld);
  }

  @Test
  void tryAcquireReturnsEmptyAndHonorsDatabaseExpiry() {
    RRegistryDao registry = mock(RRegistryDao.class);
    RLeaseManager manager =
        new RLeaseManager(registry, Duration.ofHours(1), Duration.ofMillis(1));
    when(registry.tryAcquireLease(anyString(), anyString(), any(), any()))
        .thenReturn(Optional.empty());
    assertTrue(manager.tryAcquire("busy").isEmpty());

    Instant databaseExpiry = Instant.now().plusSeconds(120);
    when(registry.tryAcquireLease(anyString(), anyString(), any(), any()))
        .thenAnswer(invocation -> Optional.of(new RRegistryDao.Lease(
            invocation.getArgument(0), invocation.getArgument(1), 9L, 1L,
            databaseExpiry, Instant.now())));
    try (RLeaseManager.Lease lease = manager.tryAcquire("free").orElseThrow()) {
      assertEquals(9L, lease.fencingToken());
    }

    try (RLeaseManager.Lease ignored = new RLeaseManager(registry).tryAcquire("default")
        .orElseThrow()) {
      assertEquals(9L, ignored.fencingToken());
    }
  }

  @Test
  void acquisitionTimeoutAndInterruptFailClosed() {
    RRegistryDao registry = mock(RRegistryDao.class);
    when(registry.tryAcquireLease(anyString(), anyString(), any(), any()))
        .thenReturn(Optional.empty());
    RLeaseManager manager =
        new RLeaseManager(registry, Duration.ofSeconds(1), Duration.ZERO);
    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> manager.acquire("busy"));

    Thread.currentThread().interrupt();
    try {
      assertThrows(MavenExceptions.WritePolicyDenied.class,
          () -> new RLeaseManager(registry, Duration.ofSeconds(1), Duration.ofSeconds(1))
              .acquire("interrupted"));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void lostRenewalFailsClosed() {
    RRegistryDao registry = registry();
    when(registry.renewLease(anyString(), anyString(), anyLong(), any(), any()))
        .thenReturn(false);
    try (RLeaseManager.Lease lease =
        new RLeaseManager(registry, Duration.ofHours(1), Duration.ZERO).acquire("lost")) {
      assertThrows(MavenExceptions.WritePolicyDenied.class, lease::assertHeld);
    }
    verify(registry, times(1)).releaseLease(anyString(), anyString(), anyLong());
  }

  @Test
  void backgroundRenewalExtendsOrLosesTheLease() throws Exception {
    RRegistryDao registry = registry();
    CountDownLatch renewed = new CountDownLatch(1);
    when(registry.renewLease(anyString(), anyString(), anyLong(), any(), any()))
        .thenAnswer(invocation -> {
          renewed.countDown();
          return true;
        });
    try (RLeaseManager.Lease lease = new RLeaseManager(
        registry, Duration.ofMillis(60), Duration.ZERO).acquire("background")) {
      assertTrue(renewed.await(1, TimeUnit.SECONDS));
      lease.assertHeld();
    }
  }

  private static RRegistryDao registry() {
    RRegistryDao registry = mock(RRegistryDao.class);
    when(registry.tryAcquireLease(anyString(), anyString(), any(), any()))
        .thenAnswer(invocation -> Optional.of(new RRegistryDao.Lease(
            invocation.getArgument(0), invocation.getArgument(1), 7L, 1L,
            invocation.getArgument(3), Instant.now())));
    return registry;
  }
}
