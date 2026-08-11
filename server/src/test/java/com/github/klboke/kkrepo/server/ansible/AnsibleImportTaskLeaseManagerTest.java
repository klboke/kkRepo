package com.github.klboke.kkrepo.server.ansible;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

class AnsibleImportTaskLeaseManagerTest {
  private static final Duration ASYNC_ASSERTION_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration ASYNC_ASSERTION_POLL_INTERVAL = Duration.ofMillis(1);

  @Test
  void renewsTheFencedTaskLeaseUntilClosed() throws Exception {
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    CountDownLatch renewed = new CountDownLatch(1);
    when(registry.renewTaskLease(eq("task"), eq("owner"), eq(7L), any()))
        .thenAnswer(invocation -> {
          renewed.countDown();
          return true;
        });
    AnsibleImportTaskLeaseManager manager = new AnsibleImportTaskLeaseManager(registry);

    AnsibleImportTaskLeaseManager.Lease lease = manager.monitor(
        task(Instant.now().plusSeconds(1)), Duration.ofMillis(300));
    assertTrue(renewed.await(1, TimeUnit.SECONDS));
    lease.assertHeld();
    lease.close();
    lease.close();

    verify(registry, atLeastOnce()).renewTaskLease(
        eq("task"), eq("owner"), eq(7L), any());
  }

  @Test
  void rejectsCompletionAfterTheFencingLeaseIsLost() {
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    when(registry.renewTaskLease(eq("task"), eq("owner"), eq(7L), any()))
        .thenReturn(false);
    AnsibleImportTaskLeaseManager manager = new AnsibleImportTaskLeaseManager(registry);

    try (AnsibleImportTaskLeaseManager.Lease lease = manager.monitor(
        task(Instant.now().plus(Duration.ofMinutes(1))), Duration.ofMillis(90))) {
      assertEventuallyLeaseLost(lease);
    }
  }

  @Test
  void retriesATransientRenewalFailureWhileTheExistingLeaseIsValid() throws Exception {
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    AtomicInteger attempts = new AtomicInteger();
    CountDownLatch renewed = new CountDownLatch(1);
    when(registry.renewTaskLease(eq("task"), eq("owner"), eq(7L), any()))
        .thenAnswer(invocation -> {
          if (attempts.incrementAndGet() == 1) throw new IllegalStateException("temporary");
          renewed.countDown();
          return true;
        });
    AnsibleImportTaskLeaseManager manager = new AnsibleImportTaskLeaseManager(registry);

    try (AnsibleImportTaskLeaseManager.Lease lease = manager.monitor(
        task(Instant.now().plusSeconds(1)), Duration.ofMillis(180))) {
      assertTrue(renewed.await(1, TimeUnit.SECONDS));
      lease.assertHeld();
    }
    assertTrue(attempts.get() >= 2);
  }

  @Test
  void requiresAClaimedTask() {
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    AnsibleImportTaskLeaseManager manager = new AnsibleImportTaskLeaseManager(registry);
    AnsibleGalaxyRegistryDao.ImportTask unclaimed = new AnsibleGalaxyRegistryDao.ImportTask(
        "task", 1L, "alice", "WAITING", List.of(), null, null, null, null, null, null,
        null, null, null, 0, null, null, 0L, Instant.now(), null, null, Instant.now());

    assertThrows(IllegalArgumentException.class,
        () -> manager.monitor(unclaimed, Duration.ofMinutes(5)));
  }

  private static void assertEventuallyLeaseLost(AnsibleImportTaskLeaseManager.Lease lease) {
    long deadline = System.nanoTime() + ASYNC_ASSERTION_TIMEOUT.toNanos();
    do {
      try {
        lease.assertHeld();
      } catch (AnsibleGalaxyExceptions.ServiceUnavailable expected) {
        return;
      }
      LockSupport.parkNanos(ASYNC_ASSERTION_POLL_INTERVAL.toNanos());
    } while (System.nanoTime() < deadline);

    assertThrows(AnsibleGalaxyExceptions.ServiceUnavailable.class, lease::assertHeld);
  }

  private static AnsibleGalaxyRegistryDao.ImportTask task(Instant expiresAt) {
    Instant now = Instant.now();
    return new AnsibleGalaxyRegistryDao.ImportTask(
        "task", 1L, "alice", "RUNNING", List.of(), null, null, "acme", "tools", "1.2.3",
        "acme-tools-1.2.3.tar.gz", "a".repeat(64), "a".repeat(64), 2L, 1, "owner",
        expiresAt, 7L, now, now, null, now);
  }
}
