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
import org.junit.jupiter.api.Test;

class AnsibleImportTaskLeaseManagerTest {
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
  void rejectsCompletionAfterTheFencingLeaseIsLost() throws Exception {
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    CountDownLatch attempted = new CountDownLatch(1);
    when(registry.renewTaskLease(eq("task"), eq("owner"), eq(7L), any()))
        .thenAnswer(invocation -> {
          attempted.countDown();
          return false;
        });
    AnsibleImportTaskLeaseManager manager = new AnsibleImportTaskLeaseManager(registry);

    try (AnsibleImportTaskLeaseManager.Lease lease = manager.monitor(
        task(Instant.now().plusSeconds(1)), Duration.ofMillis(90))) {
      assertTrue(attempted.await(1, TimeUnit.SECONDS));
      assertThrows(AnsibleGalaxyExceptions.ServiceUnavailable.class, lease::assertHeld);
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

  private static AnsibleGalaxyRegistryDao.ImportTask task(Instant expiresAt) {
    Instant now = Instant.now();
    return new AnsibleGalaxyRegistryDao.ImportTask(
        "task", 1L, "alice", "RUNNING", List.of(), null, null, "acme", "tools", "1.2.3",
        "acme-tools-1.2.3.tar.gz", "a".repeat(64), "a".repeat(64), 2L, 1, "owner",
        expiresAt, 7L, now, now, null, now);
  }
}
