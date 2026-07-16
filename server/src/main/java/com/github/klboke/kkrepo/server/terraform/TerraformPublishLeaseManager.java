package com.github.klboke.kkrepo.server.terraform;

import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.springframework.stereotype.Component;

/** Blocking, database-backed lease acquisition used to serialize publication across replicas. */
@Component
final class TerraformPublishLeaseManager {
  private static final long RETRY_NANOS = Duration.ofMillis(25).toNanos();
  private static final long MIN_RENEWAL_NANOS = Duration.ofMillis(10).toNanos();

  private final TerraformRegistryDao registry;

  TerraformPublishLeaseManager(TerraformRegistryDao registry) {
    this.registry = registry;
  }

  Lease acquire(String key, Duration ttl, Duration wait) {
    String owner = UUID.randomUUID().toString();
    long deadline = System.nanoTime() + wait.toNanos();
    do {
      Instant expiresAt = Instant.now().plus(ttl);
      if (registry.tryAcquirePublishLease(key, owner, expiresAt)) {
        return new Lease(registry, key, owner, ttl, expiresAt);
      }
      if (Thread.currentThread().isInterrupted()) {
        Thread.currentThread().interrupt();
        throw unavailable(key);
      }
      LockSupport.parkNanos(RETRY_NANOS);
    } while (System.nanoTime() < deadline);
    throw unavailable(key);
  }

  private static MavenExceptions.WritePolicyDenied unavailable(String key) {
    return new MavenExceptions.WritePolicyDenied(
        "Terraform publication lease is busy; retry the request: " + key);
  }

  static final class Lease implements AutoCloseable {
    private final TerraformRegistryDao registry;
    private final String key;
    private final String owner;
    private final Duration ttl;
    private final AtomicReference<Instant> expiresAt;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean lost = new AtomicBoolean();
    private final long renewalNanos;
    private final Thread renewal;

    private Lease(
        TerraformRegistryDao registry,
        String key,
        String owner,
        Duration ttl,
        Instant expiresAt) {
      this.registry = registry;
      this.key = key;
      this.owner = owner;
      this.ttl = ttl;
      this.expiresAt = new AtomicReference<>(expiresAt);
      this.renewalNanos = Math.max(MIN_RENEWAL_NANOS, ttl.toNanos() / 3);
      this.renewal = Thread.ofVirtual()
          .name("terraform-publish-lease-renewal")
          .start(this::renewUntilClosed);
    }

    void assertHeld() {
      if (lost.get() || !Instant.now().isBefore(expiresAt.get())) {
        throw unavailable(key);
      }
    }

    private void renewUntilClosed() {
      while (!closed.get() && !lost.get()) {
        LockSupport.parkNanos(renewalNanos);
        if (closed.get() || lost.get()) {
          return;
        }
        Instant renewedUntil = Instant.now().plus(ttl);
        try {
          if (registry.renewPublishLease(key, owner, renewedUntil)) {
            expiresAt.set(renewedUntil);
          } else {
            lost.set(true);
          }
        } catch (RuntimeException ignored) {
          if (!Instant.now().isBefore(expiresAt.get())) {
            lost.set(true);
          }
        }
      }
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        renewal.interrupt();
        registry.releasePublishLease(key, owner);
      }
    }
  }
}
