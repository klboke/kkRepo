package com.github.klboke.kkrepo.server.apt;

import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Renewable database lease with fencing tokens for cross-replica APT publication. */
@Component
final class AptLeaseManager {
  private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
  private static final Duration DEFAULT_WAIT = Duration.ofSeconds(30);
  private final AptRegistryDao registry;
  private final Duration ttl;
  private final Duration wait;

  @Autowired
  AptLeaseManager(AptRegistryDao registry) {
    this(registry, DEFAULT_TTL, DEFAULT_WAIT);
  }

  AptLeaseManager(AptRegistryDao registry, Duration ttl, Duration wait) {
    this.registry = registry;
    this.ttl = ttl;
    this.wait = wait;
  }

  Lease acquire(String key) {
    String owner = UUID.randomUUID().toString();
    long deadline = System.nanoTime() + wait.toNanos();
    long retryMillis = 20;
    do {
      Instant now = Instant.now();
      Instant expiresAt = now.plus(ttl);
      var acquired = registry.tryAcquireLease(key, owner, now, expiresAt);
      if (acquired.isPresent()) {
        AptRegistryDao.Lease row = acquired.orElseThrow();
        return new Lease(registry, row.leaseKey(), row.owner(), row.fencingToken(), expiresAt, ttl);
      }
      if (Thread.currentThread().isInterrupted()) {
        Thread.currentThread().interrupt();
        throw busy(key);
      }
      long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, retryMillis / 4));
      LockSupport.parkNanos(Duration.ofMillis(retryMillis + jitter).toNanos());
      retryMillis = Math.min(250, retryMillis * 2);
    } while (System.nanoTime() < deadline);
    throw busy(key);
  }

  /** Attempts once so scheduled workers never tie up a thread behind another replica. */
  Optional<Lease> tryAcquire(String key) {
    String owner = UUID.randomUUID().toString();
    Instant now = Instant.now();
    Instant expiresAt = now.plus(ttl);
    return registry.tryAcquireLease(key, owner, now, expiresAt)
        .map(row -> new Lease(
            registry,
            row.leaseKey(),
            row.owner(),
            row.fencingToken(),
            row.expiresAt() == null ? expiresAt : row.expiresAt(),
            ttl));
  }

  private static MavenExceptions.WritePolicyDenied busy(String key) {
    return new MavenExceptions.WritePolicyDenied(
        "Another replica is publishing this APT suite; retry the request: " + key);
  }

  static final class Lease implements AutoCloseable {
    private final AptRegistryDao registry;
    private final String key;
    private final String owner;
    private final long fencingToken;
    private final AtomicReference<Instant> expiresAt;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean lost = new AtomicBoolean();
    private final Thread renewal;
    private final Duration ttl;

    private Lease(
        AptRegistryDao registry,
        String key,
        String owner,
        long fencingToken,
        Instant expiresAt,
        Duration ttl) {
      this.registry = registry;
      this.key = key;
      this.owner = owner;
      this.fencingToken = fencingToken;
      this.expiresAt = new AtomicReference<>(expiresAt);
      this.ttl = ttl;
      this.renewal = Thread.ofVirtual().name("apt-publish-lease-renewal").start(this::renew);
    }

    String owner() {
      return owner;
    }

    long fencingToken() {
      return fencingToken;
    }

    void assertHeld() {
      if (closed.get() || lost.get() || !Instant.now().isBefore(expiresAt.get())) {
        throw busy(key);
      }
      Instant now = Instant.now();
      Instant next = now.plus(ttl);
      if (!registry.renewLease(key, owner, fencingToken, now, next)) {
        lost.set(true);
        throw busy(key);
      }
      expiresAt.set(next);
    }

    private void renew() {
      while (!closed.get() && !lost.get()) {
        LockSupport.parkNanos(ttl.toNanos() / 3);
        if (closed.get() || lost.get()) return;
        Instant now = Instant.now();
        Instant next = now.plus(ttl);
        try {
          if (registry.renewLease(key, owner, fencingToken, now, next)) expiresAt.set(next);
          else lost.set(true);
        } catch (RuntimeException ignored) {
          if (!Instant.now().isBefore(expiresAt.get())) lost.set(true);
        }
      }
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        renewal.interrupt();
        registry.releaseLease(key, owner, fencingToken);
      }
    }
  }
}
