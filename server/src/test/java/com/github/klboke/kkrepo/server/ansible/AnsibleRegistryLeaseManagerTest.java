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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AnsibleRegistryLeaseManagerTest {

  @Test
  void renewsTheFencedCoordinateLeaseUntilClosed() throws Exception {
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    CountDownLatch renewed = new CountDownLatch(1);
    when(registry.renewLease(eq("lease"), eq("owner"), eq(7L), any()))
        .thenAnswer(invocation -> {
          renewed.countDown();
          return true;
        });
    AnsibleRegistryLeaseManager manager = new AnsibleRegistryLeaseManager(registry);

    AnsibleRegistryLeaseManager.MonitoredLease monitored = manager.monitor(
        lease(Instant.now().plusSeconds(1)), Duration.ofMillis(90));
    assertTrue(renewed.await(1, TimeUnit.SECONDS));
    monitored.assertHeld();
    monitored.close();
    monitored.close();

    verify(registry, atLeastOnce()).renewLease(eq("lease"), eq("owner"), eq(7L), any());
  }

  @Test
  void detectsARejectedRenewal() throws Exception {
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    CountDownLatch attempted = new CountDownLatch(1);
    when(registry.renewLease(eq("lease"), eq("owner"), eq(7L), any()))
        .thenAnswer(invocation -> {
          attempted.countDown();
          return false;
        });

    try (AnsibleRegistryLeaseManager.MonitoredLease monitored =
             new AnsibleRegistryLeaseManager(registry).monitor(
                 lease(Instant.now().plusSeconds(1)), Duration.ofMillis(90))) {
      assertTrue(attempted.await(1, TimeUnit.SECONDS));
      assertThrows(AnsibleGalaxyExceptions.ServiceUnavailable.class, monitored::assertHeld);
    }
  }

  @Test
  void retriesTransientRenewalFailuresBeforeTheCurrentLeaseExpires() throws Exception {
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    AtomicInteger attempts = new AtomicInteger();
    CountDownLatch renewed = new CountDownLatch(1);
    when(registry.renewLease(eq("lease"), eq("owner"), eq(7L), any()))
        .thenAnswer(invocation -> {
          if (attempts.incrementAndGet() == 1) throw new IllegalStateException("temporary");
          renewed.countDown();
          return true;
        });

    try (AnsibleRegistryLeaseManager.MonitoredLease monitored =
             new AnsibleRegistryLeaseManager(registry).monitor(
                 lease(Instant.now().plusSeconds(1)), Duration.ofMillis(90))) {
      assertTrue(renewed.await(1, TimeUnit.SECONDS));
      monitored.assertHeld();
    }
    assertTrue(attempts.get() >= 2);
  }

  @Test
  void requiresACurrentPositiveLease() {
    AnsibleRegistryLeaseManager manager =
        new AnsibleRegistryLeaseManager(mock(AnsibleGalaxyRegistryDao.class));

    assertThrows(IllegalArgumentException.class, () -> manager.monitor(null, Duration.ofMinutes(1)));
    assertThrows(IllegalArgumentException.class,
        () -> manager.monitor(lease(null), Duration.ofMinutes(1)));
    assertThrows(IllegalArgumentException.class,
        () -> manager.monitor(lease(Instant.now()), Duration.ZERO));
  }

  private static AnsibleGalaxyRegistryDao.Lease lease(Instant expiresAt) {
    return new AnsibleGalaxyRegistryDao.Lease("lease", "owner", 7L, expiresAt, Instant.now());
  }
}
