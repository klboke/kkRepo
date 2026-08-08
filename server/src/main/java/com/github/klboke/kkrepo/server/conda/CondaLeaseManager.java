package com.github.klboke.kkrepo.server.conda;

import com.github.klboke.kkrepo.persistence.jdbc.api.CondaRegistryDao;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Renewable fenced database lease for publication and proxy inventory single-flight. */
@Component
final class CondaLeaseManager {
  private static final Duration TTL = Duration.ofMinutes(5);
  private static final Duration WAIT = Duration.ofSeconds(30);
  private static final long MIN_RETRY_MILLIS = 20;
  private static final long MAX_RETRY_MILLIS = 250;

  private final CondaRegistryDao registry;

  CondaLeaseManager(CondaRegistryDao registry) {
    this.registry = registry;
  }

  Lease acquire(String key) {
    return acquire(key, () -> false);
  }

  Lease acquire(String key, BooleanSupplier completed) {
    return acquireUnlessCompleted(key, completed)
        .orElseThrow(() -> busy(key));
  }

  /**
   * Acquires the lease unless a preceding replica has already made the requested state visible.
   * This lets read-side single-flight waiters consume the winner's result instead of serially
   * repeating the same expensive build.
   */
  Optional<Lease> acquireUnlessCompleted(String key, BooleanSupplier completed) {
    String owner = UUID.randomUUID().toString();
    long deadline = System.nanoTime() + WAIT.toNanos();
    long retryMillis = MIN_RETRY_MILLIS;
    do {
      if (completed != null && completed.getAsBoolean()) {
        return Optional.empty();
      }
      Instant expiresAt = Instant.now().plus(TTL);
      var acquired = registry.tryAcquireLease(key, owner, expiresAt);
      if (acquired.isPresent()) {
        CondaRegistryDao.Lease row = acquired.orElseThrow();
        return Optional.of(
            new Lease(registry, row.leaseKey(), row.owner(), row.fencingToken(), expiresAt));
      }
      if (Thread.currentThread().isInterrupted()) {
        Thread.currentThread().interrupt();
        throw busy(key);
      }
      long jitterMillis = ThreadLocalRandom.current().nextLong(Math.max(1, retryMillis / 4));
      LockSupport.parkNanos(Duration.ofMillis(retryMillis + jitterMillis).toNanos());
      retryMillis = Math.min(MAX_RETRY_MILLIS, retryMillis * 2);
    } while (System.nanoTime() < deadline);
    throw busy(key);
  }

  private static MavenExceptions.WritePolicyDenied busy(String key) {
    return new MavenExceptions.WritePolicyDenied(
        "Another replica is updating this Conda coordinate; retry the request: " + key);
  }

  static final class Lease implements AutoCloseable {
    private final CondaRegistryDao registry;
    private final String key;
    private final String owner;
    private final long fencingToken;
    private final AtomicReference<Instant> expiresAt;
    private final AtomicBoolean releaseRequested = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean lost = new AtomicBoolean();
    private final Thread renewal;

    private Lease(
        CondaRegistryDao registry,
        String key,
        String owner,
        long fencingToken,
        Instant expiresAt) {
      this.registry = registry;
      this.key = key;
      this.owner = owner;
      this.fencingToken = fencingToken;
      this.expiresAt = new AtomicReference<>(expiresAt);
      this.renewal = Thread.ofVirtual().name("conda-registry-lease-renewal").start(this::renew);
    }

    void assertHeld() {
      if (lost.get() || closed.get() || !Instant.now().isBefore(expiresAt.get())) {
        throw busy(key);
      }
      Instant next = Instant.now().plus(TTL);
      try {
        if (!registry.renewLease(key, owner, fencingToken, next)) {
          lost.set(true);
          throw busy(key);
        }
        expiresAt.set(next);
      } catch (MavenExceptions.WritePolicyDenied e) {
        throw e;
      } catch (RuntimeException e) {
        throw busy(key);
      }
    }

    private void renew() {
      while (!closed.get() && !lost.get()) {
        LockSupport.parkNanos(TTL.toNanos() / 3);
        if (closed.get() || lost.get()) return;
        Instant next = Instant.now().plus(TTL);
        try {
          if (registry.renewLease(key, owner, fencingToken, next)) expiresAt.set(next);
          else lost.set(true);
        } catch (RuntimeException ignored) {
          if (!Instant.now().isBefore(expiresAt.get())) lost.set(true);
        }
      }
    }

    @Override
    public void close() {
      if (!releaseRequested.compareAndSet(false, true)) return;
      if (TransactionSynchronizationManager.isActualTransactionActive()
          && TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void afterCompletion(int status) {
                releaseNow();
              }
            });
        return;
      }
      releaseNow();
    }

    private void releaseNow() {
      if (closed.compareAndSet(false, true)) {
        renewal.interrupt();
        registry.releaseLease(key, owner, fencingToken);
      }
    }
  }
}
