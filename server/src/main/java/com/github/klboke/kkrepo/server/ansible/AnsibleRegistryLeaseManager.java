package com.github.klboke.kkrepo.server.ansible;

import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.springframework.stereotype.Component;

/** Renews coordinate leases during slow downloads, inspection, and blob persistence. */
@Component
final class AnsibleRegistryLeaseManager {
  private static final long MIN_RENEWAL_NANOS = Duration.ofMillis(10).toNanos();

  private final AnsibleGalaxyRegistryDao registry;

  AnsibleRegistryLeaseManager(AnsibleGalaxyRegistryDao registry) {
    this.registry = registry;
  }

  MonitoredLease monitor(AnsibleGalaxyRegistryDao.Lease lease, Duration ttl) {
    return new MonitoredLease(registry, lease, ttl);
  }

  static final class MonitoredLease implements AutoCloseable {
    private final AnsibleGalaxyRegistryDao registry;
    private final AnsibleGalaxyRegistryDao.Lease lease;
    private final Duration ttl;
    private final AtomicReference<Instant> expiresAt;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean lost = new AtomicBoolean();
    private final Thread renewal;
    private final long renewalNanos;

    private MonitoredLease(
        AnsibleGalaxyRegistryDao registry,
        AnsibleGalaxyRegistryDao.Lease lease,
        Duration ttl) {
      if (lease == null || lease.expiresAt() == null || ttl == null || ttl.isNegative()
          || ttl.isZero()) {
        throw new IllegalArgumentException("A current Ansible registry lease and TTL are required");
      }
      this.registry = registry;
      this.lease = lease;
      this.ttl = ttl;
      this.expiresAt = new AtomicReference<>(lease.expiresAt());
      this.renewalNanos = Math.max(MIN_RENEWAL_NANOS, ttl.toNanos() / 3);
      this.renewal = Thread.ofVirtual()
          .name("ansible-registry-lease-renewal")
          .start(this::renewUntilClosed);
    }

    void assertHeld() {
      if (lost.get() || !Instant.now().isBefore(expiresAt.get())) {
        throw new AnsibleGalaxyExceptions.ServiceUnavailable(
            "Ansible registry coordination lease was lost");
      }
    }

    private void renewUntilClosed() {
      while (!closed.get() && !lost.get()) {
        LockSupport.parkNanos(renewalNanos);
        if (closed.get() || lost.get()) return;
        Instant renewedUntil = Instant.now().plus(ttl);
        try {
          if (registry.renewLease(
              lease.leaseKey(), lease.owner(), lease.fencingToken(), renewedUntil)) {
            expiresAt.set(renewedUntil);
          } else {
            lost.set(true);
          }
        } catch (RuntimeException ignored) {
          if (!Instant.now().isBefore(expiresAt.get())) lost.set(true);
        }
      }
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) renewal.interrupt();
    }
  }
}
