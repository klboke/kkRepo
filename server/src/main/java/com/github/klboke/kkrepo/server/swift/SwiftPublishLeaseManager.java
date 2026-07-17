package com.github.klboke.kkrepo.server.swift;

import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

/** Database lease with a fencing token for cross-replica publish/cache miss coalescing. */
@Component
final class SwiftPublishLeaseManager {
  private static final Duration LEASE_TIME = Duration.ofMinutes(5);
  private static final Duration ACQUIRE_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration COALESCED_READ_ACQUIRE_TIMEOUT =
      LEASE_TIME.plus(ACQUIRE_TIMEOUT);
  private static final long INITIAL_BACKOFF_MILLIS = 50L;
  private static final long MAX_BACKOFF_MILLIS = 1000L;
  private final SwiftRegistryDao registry;

  SwiftPublishLeaseManager(SwiftRegistryDao registry) {
    this.registry = registry;
  }

  Lease acquire(String key) {
    return acquire(key, () -> false);
  }

  Lease acquire(String key, BooleanSupplier completedByAnotherReplica) {
    return acquire(key, completedByAnotherReplica, ACQUIRE_TIMEOUT);
  }

  /**
   * Acquires the lease used to coalesce a proxy cache miss. A proxy reader must be willing to wait
   * for the current lease lifetime: the owner can legitimately spend longer than the hosted
   * publish acquire timeout downloading and inspecting an upstream archive. Once this lease is
   * acquired, the caller re-reads the durable release before doing any upstream work.
   */
  Lease acquireForCoalescedRead(String key) {
    return acquire(key, () -> false, COALESCED_READ_ACQUIRE_TIMEOUT);
  }

  Lease acquireForCoalescedRead(String key, BooleanSupplier completedByAnotherReplica) {
    return acquire(key, completedByAnotherReplica, COALESCED_READ_ACQUIRE_TIMEOUT);
  }

  private Lease acquire(
      String key, BooleanSupplier completedByAnotherReplica, Duration acquireTimeout) {
    String owner = UUID.randomUUID().toString();
    Instant deadline = Instant.now().plus(acquireTimeout);
    long backoffMillis = INITIAL_BACKOFF_MILLIS;
    do {
      OptionalLease acquired = tryAcquire(key, owner);
      if (acquired.lease() != null) {
        return acquired.lease();
      }
      if (completedByAnotherReplica != null && completedByAnotherReplica.getAsBoolean()) {
        throw new SwiftExceptions.Conflict("Swift release already exists");
      }
      try {
        long jitter = ThreadLocalRandom.current().nextLong(
            Math.max(1L, backoffMillis / 2L), backoffMillis + 1L);
        Thread.sleep(jitter);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new SwiftExceptions.Conflict("Interrupted while waiting for Swift publication lease");
      }
      backoffMillis = Math.min(MAX_BACKOFF_MILLIS, backoffMillis * 2L);
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
