package com.github.klboke.kkrepo.server.swift;

import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Database lease with a fencing token for cross-replica publish/cache miss coalescing. */
@Component
final class SwiftPublishLeaseManager {
  private static final Duration LEASE_TIME = Duration.ofMinutes(5);
  private static final Duration ACQUIRE_TIMEOUT = Duration.ofSeconds(30);
  private final SwiftRegistryDao registry;

  SwiftPublishLeaseManager(SwiftRegistryDao registry) {
    this.registry = registry;
  }

  Lease acquire(String key) {
    String owner = UUID.randomUUID().toString();
    Instant deadline = Instant.now().plus(ACQUIRE_TIMEOUT);
    do {
      OptionalLease acquired = tryAcquire(key, owner);
      if (acquired.lease() != null) {
        return acquired.lease();
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new SwiftExceptions.Conflict("Interrupted while waiting for Swift publication lease");
      }
    } while (Instant.now().isBefore(deadline));
    throw new SwiftExceptions.Conflict("Another replica is publishing this Swift release");
  }

  private OptionalLease tryAcquire(String key, String owner) {
    return new OptionalLease(registry.tryAcquireLease(key, owner, Instant.now().plus(LEASE_TIME))
        .map(row -> new Lease(row.leaseKey(), row.owner(), row.fencingToken()))
        .orElse(null));
  }

  final class Lease implements AutoCloseable {
    private final String key;
    private final String owner;
    private final long fencingToken;
    private boolean closed;

    private Lease(String key, String owner, long fencingToken) {
      this.key = key;
      this.owner = owner;
      this.fencingToken = fencingToken;
    }

    String key() { return key; }

    String owner() { return owner; }

    long fencingToken() { return fencingToken; }

    void assertHeld() {
      if (closed || !registry.renewLease(
          key, owner, fencingToken, Instant.now().plus(LEASE_TIME))) {
        throw new SwiftExceptions.Conflict("Swift publication lease was lost");
      }
    }

    @Override
    public void close() {
      if (!closed) {
        closed = true;
        registry.releaseLease(key, owner, fencingToken);
      }
    }
  }

  private record OptionalLease(Lease lease) {}
}
