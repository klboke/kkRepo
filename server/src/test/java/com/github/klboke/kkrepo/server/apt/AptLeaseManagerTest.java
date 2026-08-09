package com.github.klboke.kkrepo.server.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.timeout;

import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.time.Instant;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AptLeaseManagerTest {

  @Test
  void acquiresRenewsAndReleasesFencedLeaseOnce() {
    AptRegistryDao registry = availableRegistry();
    AptLeaseManager.Lease lease = new AptLeaseManager(registry).acquire("suite:1");
    assertEquals("suite:1", leaseKey(registry));
    assertEquals(9L, lease.fencingToken());
    assertTrue(!lease.owner().isBlank());
    lease.assertHeld();
    lease.close();
    lease.close();
    verify(registry, times(1)).releaseLease("suite:1", lease.owner(), 9L);
    assertThrows(MavenExceptions.WritePolicyDenied.class, lease::assertHeld);
  }

  @Test
  void nonBlockingAcquisitionAttemptsOnlyOnce() {
    AptRegistryDao available = availableRegistry();
    try (AptLeaseManager.Lease lease =
        new AptLeaseManager(available).tryAcquire("worker").orElseThrow()) {
      assertEquals(9L, lease.fencingToken());
    }
    verify(available, times(1)).tryAcquireLease(anyString(), anyString(), any(), any());

    AptRegistryDao busy = mock(AptRegistryDao.class);
    when(busy.tryAcquireLease(anyString(), anyString(), any(), any()))
        .thenReturn(Optional.empty());
    assertTrue(new AptLeaseManager(busy).tryAcquire("worker").isEmpty());
    verify(busy, times(1)).tryAcquireLease(anyString(), anyString(), any(), any());
  }

  @Test
  void failsClosedWhenRenewalIsLostOrAcquisitionIsInterrupted() {
    AptRegistryDao lost = availableRegistry();
    when(lost.renewLease(anyString(), anyString(), anyLong(), any(), any())).thenReturn(false);
    try (AptLeaseManager.Lease lease = new AptLeaseManager(lost).acquire("lost")) {
      assertThrows(MavenExceptions.WritePolicyDenied.class, lease::assertHeld);
      assertThrows(MavenExceptions.WritePolicyDenied.class, lease::assertHeld);
    }

    AptRegistryDao busy = mock(AptRegistryDao.class);
    when(busy.tryAcquireLease(anyString(), anyString(), any(), any()))
        .thenReturn(Optional.empty());
    Thread.currentThread().interrupt();
    try {
      assertThrows(MavenExceptions.WritePolicyDenied.class,
          () -> new AptLeaseManager(busy).acquire("busy"));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void retriesUntilBoundedDeadlineAndRenewsInBackground() throws Exception {
    AptRegistryDao busy = mock(AptRegistryDao.class);
    when(busy.tryAcquireLease(anyString(), anyString(), any(), any()))
        .thenReturn(Optional.empty());
    AptLeaseManager fast = new AptLeaseManager(
        busy, Duration.ofMillis(60), Duration.ofMillis(5));
    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> fast.acquire("busy"));
    verify(busy, org.mockito.Mockito.atLeastOnce())
        .tryAcquireLease(anyString(), anyString(), any(), any());

    AptRegistryDao renewable = availableRegistry();
    try (AptLeaseManager.Lease ignored = new AptLeaseManager(
        renewable, Duration.ofMillis(90), Duration.ofMillis(10)).acquire("renew")) {
      verify(renewable, timeout(500).atLeastOnce())
          .renewLease(anyString(), anyString(), anyLong(), any(), any());
    }
  }

  @Test
  void backgroundRenewalMarksLeaseLostAfterFalseOrExpiredFailure() throws Exception {
    AptRegistryDao rejected = availableRegistry();
    when(rejected.renewLease(anyString(), anyString(), anyLong(), any(), any())).thenReturn(false);
    try (AptLeaseManager.Lease lease = new AptLeaseManager(
        rejected, Duration.ofMillis(45), Duration.ofMillis(10)).acquire("rejected")) {
      verify(rejected, timeout(500)).renewLease(anyString(), anyString(), anyLong(), any(), any());
      assertThrows(MavenExceptions.WritePolicyDenied.class, lease::assertHeld);
    }

    AptRegistryDao failing = availableRegistry();
    when(failing.renewLease(anyString(), anyString(), anyLong(), any(), any()))
        .thenThrow(new IllegalStateException("database unavailable"));
    try (AptLeaseManager.Lease lease = new AptLeaseManager(
        failing, Duration.ofMillis(30), Duration.ofMillis(10)).acquire("failing")) {
      verify(failing, timeout(500).atLeast(2))
          .renewLease(anyString(), anyString(), anyLong(), any(), any());
      Thread.sleep(40);
      assertThrows(MavenExceptions.WritePolicyDenied.class, lease::assertHeld);
    }
  }

  private static AptRegistryDao availableRegistry() {
    AptRegistryDao registry = mock(AptRegistryDao.class);
    when(registry.tryAcquireLease(anyString(), anyString(), any(), any())).thenAnswer(invocation ->
        Optional.of(new AptRegistryDao.Lease(
            invocation.getArgument(0), invocation.getArgument(1), 9L, 1L,
            invocation.getArgument(3), invocation.getArgument(2))));
    when(registry.renewLease(anyString(), anyString(), anyLong(), any(), any())).thenReturn(true);
    return registry;
  }

  private static String leaseKey(AptRegistryDao registry) {
    verify(registry).tryAcquireLease(anyString(), anyString(), any(), any());
    return "suite:1";
  }
}
