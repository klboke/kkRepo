package com.github.klboke.kkrepo.server.apt;

import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.springframework.stereotype.Component;

/** Renewable database lease with fencing tokens for cross-replica APT publication. */
@Component
final class AptLeaseManager {
  private static final Duration TTL = Duration.ofMinutes(5);
  private static final Duration WAIT = Duration.ofSeconds(30);
  private final AptRegistryDao registry;

  AptLeaseManager(AptRegistryDao registry) {
    this.registry = registry;
  }

  Lease acquire(String key) {
    String owner = UUID.randomUUID().toString();
    long deadline = System.nanoTime() + WAIT.toNanos();
    long retryMillis = 20;
    do {
      Instant now = Instant.now();
      Instant expiresAt = now.plus(TTL);
      var acquired = registry.tryAcquireLease(key, owner, now, expiresAt);
      if (acquired.isPresent()) {
        AptRegistryDao.Lease row = acquired.orElseThrow();
        return new Lease(registry, row.leaseKey(), row.owner(), row.fencingToken(), expiresAt);
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

    private Lease(
        AptRegistryDao registry,
        String key,
        String owner,
        long fencingToken,
        Instant expiresAt) {
      this.registry = registry;
      this.key = key;
      this.owner = owner;
      this.fencingToken = fencingToken;
      this.expiresAt = new AtomicReference<>(expiresAt);
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
      Instant next = now.plus(TTL);
      if (!registry.renewLease(key, owner, fencingToken, now, next)) {
        lost.set(true);
        throw busy(key);
      }
      expiresAt.set(next);
    }

    private void renew() {
      while (!closed.get() && !lost.get()) {
        LockSupport.parkNanos(TTL.toNanos() / 3);
        if (closed.get() || lost.get()) return;
        Instant now = Instant.now();
        Instant next = now.plus(TTL);
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
