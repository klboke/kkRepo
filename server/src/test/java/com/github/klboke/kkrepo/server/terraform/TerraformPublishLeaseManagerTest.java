package com.github.klboke.kkrepo.server.terraform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

class TerraformPublishLeaseManagerTest {
  @Test
  void waitsForAnotherReplicaAndReleasesOnlyOnce() {
    AtomicInteger attempts = new AtomicInteger();
    AtomicInteger releases = new AtomicInteger();
    TerraformRegistryDao dao = (TerraformRegistryDao) Proxy.newProxyInstance(
        TerraformRegistryDao.class.getClassLoader(),
        new Class<?>[] {TerraformRegistryDao.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "tryAcquirePublishLease" -> attempts.incrementAndGet() >= 3;
          case "releasePublishLease" -> {
            releases.incrementAndGet();
            yield null;
          }
          default -> throw new UnsupportedOperationException(method.getName());
        });
    TerraformPublishLeaseManager manager = new TerraformPublishLeaseManager(dao);

    TerraformPublishLeaseManager.Lease lease = manager.acquire(
        "provider:1", java.time.Duration.ofMinutes(1), java.time.Duration.ofSeconds(1));
    lease.close();
    lease.close();

    assertEquals(3, attempts.get());
    assertEquals(1, releases.get());
  }

  @Test
  void renewsLeaseUntilPublicationCloses() throws Exception {
    AtomicInteger renewals = new AtomicInteger();
    AtomicInteger releases = new AtomicInteger();
    CountDownLatch renewed = new CountDownLatch(1);
    TerraformRegistryDao dao = (TerraformRegistryDao) Proxy.newProxyInstance(
        TerraformRegistryDao.class.getClassLoader(),
        new Class<?>[] {TerraformRegistryDao.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "tryAcquirePublishLease" -> true;
          case "renewPublishLease" -> {
            renewals.incrementAndGet();
            renewed.countDown();
            yield true;
          }
          case "releasePublishLease" -> {
            releases.incrementAndGet();
            yield null;
          }
          default -> throw new UnsupportedOperationException(method.getName());
        });
    TerraformPublishLeaseManager manager = new TerraformPublishLeaseManager(dao);

    TerraformPublishLeaseManager.Lease lease = manager.acquire(
        "provider:renewed", Duration.ofMillis(90), Duration.ofSeconds(1));
    assertTrue(renewed.await(1, TimeUnit.SECONDS));
    lease.assertHeld();
    lease.close();

    assertTrue(renewals.get() >= 1);
    assertEquals(1, releases.get());
  }

  @Test
  void rejectsPublicationAfterLeaseRenewalIsLost() throws Exception {
    CountDownLatch renewalAttempted = new CountDownLatch(1);
    TerraformRegistryDao dao = (TerraformRegistryDao) Proxy.newProxyInstance(
        TerraformRegistryDao.class.getClassLoader(),
        new Class<?>[] {TerraformRegistryDao.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "tryAcquirePublishLease" -> true;
          case "renewPublishLease" -> {
            renewalAttempted.countDown();
            yield false;
          }
          case "releasePublishLease" -> null;
          default -> throw new UnsupportedOperationException(method.getName());
        });
    TerraformPublishLeaseManager manager = new TerraformPublishLeaseManager(dao);

    try (TerraformPublishLeaseManager.Lease lease = manager.acquire(
        "provider:lost", Duration.ofMillis(90), Duration.ofSeconds(1))) {
      assertTrue(renewalAttempted.await(1, TimeUnit.SECONDS));
      assertLeaseEventuallyRejected(lease, Duration.ofSeconds(1));
    }
  }

  private static void assertLeaseEventuallyRejected(
      TerraformPublishLeaseManager.Lease lease, Duration timeout) {
    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> {
      long deadline = System.nanoTime() + timeout.toNanos();
      do {
        lease.assertHeld();
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
      } while (System.nanoTime() < deadline);
    });
  }
}
