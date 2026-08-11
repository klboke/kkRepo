package com.github.klboke.kkrepo.server.conan;

import com.github.klboke.kkrepo.persistence.jdbc.api.ConanRegistryDao;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Renewable database lease with a monotonically increasing fencing token. */
@Component
final class ConanLeaseManager {
  private static final Duration TTL = Duration.ofMinutes(5);
  private static final Duration WAIT = Duration.ofSeconds(30);
  private final ConanRegistryDao registry;

  ConanLeaseManager(ConanRegistryDao registry) {
    this.registry = registry;
  }

  Lease acquire(long repositoryId, String coordinate) {
    String owner = UUID.randomUUID().toString();
    long deadline = System.nanoTime() + WAIT.toNanos();
    long delay = 20;
    do {
      Instant expiresAt = Instant.now().plus(TTL);
      Optional<ConanRegistryDao.Lease> acquired = registry.tryAcquireLease(
          repositoryId, coordinate, owner, expiresAt);
      if (acquired.isPresent()) {
        ConanRegistryDao.Lease row = acquired.orElseThrow();
        return new Lease(
            registry, repositoryId, coordinate, owner, row.fencingToken(), expiresAt);
      }
      if (Thread.currentThread().isInterrupted()) {
        Thread.currentThread().interrupt();
        throw busy(coordinate);
      }
      long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, delay / 4));
      LockSupport.parkNanos(Duration.ofMillis(delay + jitter).toNanos());
      delay = Math.min(250, delay * 2);
    } while (System.nanoTime() < deadline);
    throw busy(coordinate);
  }

  private static ConanExceptions.Busy busy(String coordinate) {
    return new ConanExceptions.Busy(
        "Another replica is updating this Conan coordinate; retry: " + coordinate);
  }

  static final class Lease implements AutoCloseable {
    private final ConanRegistryDao registry;
    private final long repositoryId;
    private final String coordinate;
    private final String owner;
    private final long fencingToken;
    private final AtomicReference<Instant> expiresAt;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread renewal;

    private Lease(
        ConanRegistryDao registry,
        long repositoryId,
        String coordinate,
        String owner,
        long fencingToken,
        Instant expiresAt) {
      this.registry = registry;
      this.repositoryId = repositoryId;
      this.coordinate = coordinate;
      this.owner = owner;
      this.fencingToken = fencingToken;
      this.expiresAt = new AtomicReference<>(expiresAt);
      renewal = Thread.ofVirtual().name("conan-coordinate-lease-renewal").start(this::renew);
    }

    long fencingToken() {
      return fencingToken;
    }

    Instant expiresAt() {
      return expiresAt.get();
    }

    void assertHeld() {
      if (closed.get() || !Instant.now().isBefore(expiresAt.get())) throw busy(coordinate);
      Instant next = Instant.now().plus(TTL);
      if (!registry.renewLease(
          repositoryId, coordinate, owner, fencingToken, next)) {
        throw busy(coordinate);
      }
      expiresAt.set(next);
    }

    private void renew() {
      while (!closed.get()) {
        LockSupport.parkNanos(TTL.toNanos() / 3);
        if (closed.get()) return;
        Instant next = Instant.now().plus(TTL);
        try {
          if (!registry.renewLease(
              repositoryId, coordinate, owner, fencingToken, next)) return;
          expiresAt.set(next);
        } catch (RuntimeException ignored) {
          if (!Instant.now().isBefore(expiresAt.get())) return;
        }
      }
    }

    @Override
    public void close() {
      if (TransactionSynchronizationManager.isActualTransactionActive()
          && TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            release();
          }
        });
      } else {
        release();
      }
    }

    private void release() {
      if (!closed.compareAndSet(false, true)) return;
      renewal.interrupt();
      registry.releaseLease(repositoryId, coordinate, owner, fencingToken);
    }
  }
}
