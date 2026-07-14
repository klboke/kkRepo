package com.github.klboke.kkrepo.server.terraform;

import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.springframework.stereotype.Component;

/** Blocking, database-backed lease acquisition used to serialize publication across replicas. */
@Component
final class TerraformPublishLeaseManager {
  private static final long RETRY_NANOS = Duration.ofMillis(25).toNanos();

  private final TerraformRegistryDao registry;

  TerraformPublishLeaseManager(TerraformRegistryDao registry) {
    this.registry = registry;
  }

  Lease acquire(String key, Duration ttl, Duration wait) {
    String owner = UUID.randomUUID().toString();
    long deadline = System.nanoTime() + wait.toNanos();
    do {
      if (registry.tryAcquirePublishLease(key, owner, Instant.now().plus(ttl))) {
        return new Lease(registry, key, owner);
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
    private final AtomicBoolean closed = new AtomicBoolean();

    private Lease(TerraformRegistryDao registry, String key, String owner) {
      this.registry = registry;
      this.key = key;
      this.owner = owner;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        registry.releasePublishLease(key, owner);
      }
    }
  }
}
