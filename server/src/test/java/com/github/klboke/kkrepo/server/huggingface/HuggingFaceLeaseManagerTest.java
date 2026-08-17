package com.github.klboke.kkrepo.server.huggingface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.HuggingFaceRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.HuggingFaceRegistryDao.FetchLease;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

class HuggingFaceLeaseManagerTest {
  @Test
  void checkpointsUseTheRenewalThreadInsteadOfSynchronousDatabaseHeartbeats() {
    HuggingFaceRegistryDao registry = mock(HuggingFaceRegistryDao.class);
    when(registry.tryAcquireLease(eq(7L), eq("file"), any(), any()))
        .thenAnswer(invocation -> Optional.of(new FetchLease(
            7L, "file", invocation.getArgument(2), 11L, Instant.now().plusSeconds(300),
            Instant.now())));

    HuggingFaceLeaseManager.Lease lease = new HuggingFaceLeaseManager(registry)
        .acquireUnlessCompleted(7L, "file", () -> false)
        .orElseThrow();
    assertEquals(11L, lease.fencingToken());
    lease.assertHeld();
    lease.assertHeld();

    verify(registry, never()).renewLease(eq(7L), eq("file"), any(), eq(11L), any());
    lease.close();
    lease.close();
    verify(registry).releaseLease(eq(7L), eq("file"), any(), eq(11L));
  }

  @Test
  void returnsWithoutAcquiringWhenAnotherReplicaCompletesDuringBackoff() {
    HuggingFaceRegistryDao registry = mock(HuggingFaceRegistryDao.class);
    when(registry.tryAcquireLease(eq(7L), eq("file"), any(), any()))
        .thenReturn(Optional.empty());
    AtomicInteger checks = new AtomicInteger();

    Optional<HuggingFaceLeaseManager.Lease> lease = new HuggingFaceLeaseManager(registry)
        .acquireUnlessCompleted(7L, "file", () -> checks.incrementAndGet() > 1);

    assertEquals(Optional.empty(), lease);
    verify(registry).tryAcquireLease(eq(7L), eq("file"), any(), any());
  }

  @Test
  void failsFastWhenWaitingThreadIsInterrupted() {
    HuggingFaceRegistryDao registry = mock(HuggingFaceRegistryDao.class);
    when(registry.tryAcquireLease(eq(7L), eq("file"), any(), any()))
        .thenReturn(Optional.empty());

    Thread.currentThread().interrupt();
    try {
      assertThrows(
          MavenExceptions.BadUpstreamException.class,
          () -> new HuggingFaceLeaseManager(registry)
              .acquireUnlessCompleted(7L, "file", () -> false));
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void renewalExtendsTheLeaseExpiry() throws Exception {
    HuggingFaceRegistryDao registry = renewableRegistry();
    when(registry.renewLease(eq(7L), eq("file"), any(), eq(11L), any()))
        .thenReturn(true);
    HuggingFaceLeaseManager.Lease lease = acquire(registry);

    try {
      LockSupport.unpark(renewalThread(lease));
      verify(registry, timeout(1000)).renewLease(eq(7L), eq("file"), any(), eq(11L), any());
      lease.assertHeld();
    } finally {
      lease.close();
    }
  }

  @Test
  void rejectedRenewalMarksTheLeaseLost() throws Exception {
    HuggingFaceRegistryDao registry = renewableRegistry();
    when(registry.renewLease(eq(7L), eq("file"), any(), eq(11L), any()))
        .thenReturn(false);
    HuggingFaceLeaseManager.Lease lease = acquire(registry);

    try {
      LockSupport.unpark(renewalThread(lease));
      verify(registry, timeout(1000)).renewLease(eq(7L), eq("file"), any(), eq(11L), any());
      assertEventuallyLost(lease);
    } finally {
      lease.close();
    }
  }

  @Test
  void renewalFailureMarksAnAlreadyExpiredLeaseLost() throws Exception {
    HuggingFaceRegistryDao registry = renewableRegistry();
    when(registry.renewLease(eq(7L), eq("file"), any(), eq(11L), any()))
        .thenThrow(new IllegalStateException("database unavailable"));
    HuggingFaceLeaseManager.Lease lease = acquire(registry);
    expiresAt(lease).set(Instant.EPOCH);

    try {
      LockSupport.unpark(renewalThread(lease));
      verify(registry, timeout(1000)).renewLease(eq(7L), eq("file"), any(), eq(11L), any());
      assertThrows(MavenExceptions.BadUpstreamException.class, lease::assertHeld);
    } finally {
      lease.close();
    }
  }

  private static HuggingFaceRegistryDao renewableRegistry() {
    HuggingFaceRegistryDao registry = mock(HuggingFaceRegistryDao.class);
    when(registry.tryAcquireLease(eq(7L), eq("file"), any(), any()))
        .thenAnswer(invocation -> Optional.of(new FetchLease(
            7L, "file", invocation.getArgument(2), 11L, Instant.now().plusSeconds(300),
            Instant.now())));
    return registry;
  }

  private static HuggingFaceLeaseManager.Lease acquire(HuggingFaceRegistryDao registry) {
    return new HuggingFaceLeaseManager(registry)
        .acquireUnlessCompleted(7L, "file", () -> false)
        .orElseThrow();
  }

  private static void assertEventuallyLost(HuggingFaceLeaseManager.Lease lease) {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(1).toNanos();
    while (System.nanoTime() < deadline) {
      try {
        lease.assertHeld();
      } catch (MavenExceptions.BadUpstreamException expected) {
        return;
      }
      LockSupport.parkNanos(java.time.Duration.ofMillis(1).toNanos());
    }
    assertThrows(MavenExceptions.BadUpstreamException.class, lease::assertHeld);
  }

  private static Thread renewalThread(HuggingFaceLeaseManager.Lease lease) throws Exception {
    Field field = HuggingFaceLeaseManager.Lease.class.getDeclaredField("renewal");
    field.setAccessible(true);
    return (Thread) field.get(lease);
  }

  @SuppressWarnings("unchecked")
  private static AtomicReference<Instant> expiresAt(HuggingFaceLeaseManager.Lease lease)
      throws Exception {
    Field field = HuggingFaceLeaseManager.Lease.class.getDeclaredField("expiresAt");
    field.setAccessible(true);
    return (AtomicReference<Instant>) field.get(lease);
  }
}
