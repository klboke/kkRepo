package com.github.klboke.kkrepo.server.ansible;

import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.springframework.stereotype.Component;

/** Renews a fenced import-task lease while a replica persists a collection archive. */
@Component
final class AnsibleImportTaskLeaseManager {
  private static final long MIN_RENEWAL_NANOS = Duration.ofMillis(10).toNanos();

  private final AnsibleGalaxyRegistryDao registry;

  AnsibleImportTaskLeaseManager(AnsibleGalaxyRegistryDao registry) {
    this.registry = registry;
  }

  Lease monitor(AnsibleGalaxyRegistryDao.ImportTask task, Duration ttl) {
    if (task.leaseOwner() == null || task.leaseExpiresAt() == null) {
      throw new IllegalArgumentException("A claimed import task lease is required");
    }
    return new Lease(registry, task, ttl);
  }

  static final class Lease implements AutoCloseable {
    private final AnsibleGalaxyRegistryDao registry;
    private final String taskId;
    private final String owner;
    private final long fencingToken;
    private final Duration ttl;
    private final AtomicReference<Instant> expiresAt;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean lost = new AtomicBoolean();
    private final long renewalNanos;
    private final Thread renewal;

    private Lease(
        AnsibleGalaxyRegistryDao registry,
        AnsibleGalaxyRegistryDao.ImportTask task,
        Duration ttl) {
      this.registry = registry;
      this.taskId = task.taskId();
      this.owner = task.leaseOwner();
      this.fencingToken = task.fencingToken();
      this.ttl = ttl;
      this.expiresAt = new AtomicReference<>(task.leaseExpiresAt());
      this.renewalNanos = Math.max(MIN_RENEWAL_NANOS, ttl.toNanos() / 3);
      this.renewal = Thread.ofVirtual()
          .name("ansible-import-task-lease-renewal")
          .start(this::renewUntilClosed);
    }

    void assertHeld() {
      if (lost.get() || !Instant.now().isBefore(expiresAt.get())) {
        throw new AnsibleGalaxyExceptions.ServiceUnavailable(
            "Ansible collection import task lease was lost");
      }
    }

    private void renewUntilClosed() {
      while (!closed.get() && !lost.get()) {
        LockSupport.parkNanos(renewalNanos);
        if (closed.get() || lost.get()) return;
        Instant renewedUntil = Instant.now().plus(ttl);
        try {
          if (registry.renewTaskLease(taskId, owner, fencingToken, renewedUntil)) {
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
