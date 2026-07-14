package com.github.klboke.kkrepo.server.terraform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
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
}
