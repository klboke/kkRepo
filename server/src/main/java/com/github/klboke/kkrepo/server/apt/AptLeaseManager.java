package com.github.klboke.kkrepo.server.apt;

import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.server.coordination.FencedLeaseManager;
import com.github.klboke.kkrepo.server.coordination.FencedLeaseManager.Lease;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Protocol binding for the shared renewable, fenced database lease. */
@Component
final class AptLeaseManager {
  private final FencedLeaseManager leases;

  @Autowired
  AptLeaseManager(AptRegistryDao registry) {
    this(registry, Duration.ofMinutes(5), Duration.ofSeconds(30));
  }

  AptLeaseManager(AptRegistryDao registry, Duration ttl, Duration wait) {
    leases = new FencedLeaseManager(new FencedLeaseManager.Store() {
      @Override
      public Optional<FencedLeaseManager.Row> tryAcquireLease(
          String key, String owner, Instant now, Instant expiresAt) {
        return registry.tryAcquireLease(key, owner, now, expiresAt)
            .map(row -> new FencedLeaseManager.Row(
                row.leaseKey(), row.owner(), row.fencingToken(), row.expiresAt()));
      }

      @Override
      public boolean renewLease(
          String key, String owner, long token, Instant now, Instant expiresAt) {
        return registry.renewLease(key, owner, token, now, expiresAt);
      }

      @Override
      public void releaseLease(String key, String owner, long token) {
        registry.releaseLease(key, owner, token);
      }
    }, ttl, wait, "apt-publish-lease-renewal", key -> new MavenExceptions.WritePolicyDenied(
        "Another replica is publishing this APT suite; retry the request: " + key));
  }

  Lease acquire(String key) {
    return leases.acquire(key);
  }

  Optional<Lease> tryAcquire(String key) {
    return leases.tryAcquire(key);
  }
}
