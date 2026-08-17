package com.github.klboke.kkrepo.server.huggingface;

import com.github.klboke.kkrepo.persistence.jdbc.api.HuggingFaceRegistryDao;
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

/** Renewable fenced database lease for cross-replica model-file cache population. */
@Component
final class HuggingFaceLeaseManager {
  private static final Duration TTL = Duration.ofMinutes(5);
  private static final Duration WAIT = Duration.ofMinutes(2);
  private static final long MIN_RETRY_MILLIS = 25;
  private static final long MAX_RETRY_MILLIS = 500;
  private final HuggingFaceRegistryDao registry;

  HuggingFaceLeaseManager(HuggingFaceRegistryDao registry) {
    this.registry = registry;
  }

  Optional<Lease> acquireUnlessCompleted(
      long repositoryId, String key, BooleanSupplier completed) {
    String owner = UUID.randomUUID().toString();
    long deadline = System.nanoTime() + WAIT.toNanos();
    long retryMillis = MIN_RETRY_MILLIS;
    do {
      if (completed != null && completed.getAsBoolean()) return Optional.empty();
      Instant expiresAt = Instant.now().plus(TTL);
      Optional<HuggingFaceRegistryDao.FetchLease> acquired =
          registry.tryAcquireLease(repositoryId, key, owner, expiresAt);
      if (acquired.isPresent()) {
        var row = acquired.orElseThrow();
        return Optional.of(new Lease(
            registry, repositoryId, key, row.owner(), row.fencingToken(), expiresAt));
      }
      if (Thread.currentThread().isInterrupted()) {
        Thread.currentThread().interrupt();
        throw busy(key);
      }
      long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, retryMillis / 4));
      LockSupport.parkNanos(Duration.ofMillis(retryMillis + jitter).toNanos());
      retryMillis = Math.min(MAX_RETRY_MILLIS, retryMillis * 2);
    } while (System.nanoTime() < deadline);
    throw busy(key);
  }

  private static MavenExceptions.BadUpstreamException busy(String key) {
    return new MavenExceptions.BadUpstreamException(
        "Another replica is still caching this Hugging Face model file; retry: " + key);
  }

  static final class Lease implements AutoCloseable {
    private final HuggingFaceRegistryDao registry;
    private final long repositoryId;
    private final String key;
    private final String owner;
    private final long fencingToken;
    private final AtomicReference<Instant> expiresAt;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean lost = new AtomicBoolean();
    private final Thread renewal;

    private Lease(
        HuggingFaceRegistryDao registry,
        long repositoryId,
        String key,
        String owner,
        long fencingToken,
        Instant expiresAt) {
      this.registry = registry;
      this.repositoryId = repositoryId;
      this.key = key;
      this.owner = owner;
      this.fencingToken = fencingToken;
      this.expiresAt = new AtomicReference<>(expiresAt);
      this.renewal = Thread.ofVirtual().name("huggingface-fetch-lease-renewal").start(this::renew);
    }

    long fencingToken() {
      return fencingToken;
    }

    void assertHeld() {
      if (lost.get() || closed.get() || !Instant.now().isBefore(expiresAt.get())) throw busy(key);
      // The dedicated renewal thread owns database heartbeats. Calling the database here made
      // every bounded checkpoint add a network round trip, even immediately after acquisition.
      // Correctness still comes from the durable expiry plus the fencing token checked by every
      // state transition; this method is only the fast local fail-closed checkpoint.
    }

    private void renew() {
      while (!closed.get() && !lost.get()) {
        LockSupport.parkNanos(TTL.toNanos() / 3);
        if (closed.get() || lost.get()) return;
        Instant next = Instant.now().plus(TTL);
        try {
          if (registry.renewLease(repositoryId, key, owner, fencingToken, next)) {
            expiresAt.set(next);
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
      if (!closed.compareAndSet(false, true)) return;
      renewal.interrupt();
      registry.releaseLease(repositoryId, key, owner, fencingToken);
    }
  }
}
