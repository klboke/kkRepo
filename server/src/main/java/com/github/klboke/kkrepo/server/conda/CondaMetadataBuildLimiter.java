package com.github.klboke.kkrepo.server.conda;

import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Node-local admission control for memory/CPU-heavy inventory refreshes and metadata builds. */
@Component
final class CondaMetadataBuildLimiter {
  private final Semaphore permits;
  private final long waitMillis;
  private final ConcurrentHashMap<String, CompletableFuture<Object>> flights =
      new ConcurrentHashMap<>();

  @Autowired
  CondaMetadataBuildLimiter(
      @Value("${kkrepo.conda.metadata.max-concurrent-builds:2}") int maxConcurrentBuilds,
      @Value("${kkrepo.conda.metadata.build-permit-wait-ms:60000}") long waitMillis) {
    this.permits = new Semaphore(Math.max(1, maxConcurrentBuilds), true);
    this.waitMillis = Math.max(1, waitMillis);
  }

  CondaMetadataBuildLimiter() {
    this(2, 60_000);
  }

  <T> T execute(Supplier<T> action) {
    return executeWithPermit(action);
  }

  /** Collapses same-node work by coordinate before consuming one of the bounded build permits. */
  @SuppressWarnings("unchecked")
  <T> T execute(String key, Supplier<T> action) {
    if (key == null || key.isBlank()) return executeWithPermit(action);
    CompletableFuture<Object> created = new CompletableFuture<>();
    CompletableFuture<Object> existing = flights.putIfAbsent(key, created);
    if (existing != null) {
      try {
        return (T) existing.get(waitMillis, TimeUnit.MILLISECONDS);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new MavenExceptions.BadUpstreamException(
            "Interrupted while waiting for Conda metadata build capacity");
      } catch (TimeoutException error) {
        throw new MavenExceptions.BadUpstreamException(
            "Conda metadata build capacity is busy; retry the request");
      } catch (ExecutionException error) {
        throw propagate(error.getCause());
      }
    }
    try {
      T result = executeWithPermit(action);
      created.complete(result);
      return result;
    } catch (Throwable error) {
      created.completeExceptionally(error);
      throw propagate(error);
    } finally {
      flights.remove(key, created);
    }
  }

  int inFlightCount() {
    return flights.size();
  }

  private <T> T executeWithPermit(Supplier<T> action) {
    boolean acquired = false;
    try {
      acquired = permits.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
      if (!acquired) {
        throw new MavenExceptions.BadUpstreamException(
            "Conda metadata build capacity is busy; retry the request");
      }
      return action.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MavenExceptions.BadUpstreamException(
          "Interrupted while waiting for Conda metadata build capacity");
    } finally {
      if (acquired) {
        permits.release();
      }
    }
  }

  private static RuntimeException propagate(Throwable error) {
    if (error instanceof RuntimeException runtime) return runtime;
    if (error instanceof Error fatal) throw fatal;
    return new IllegalStateException(error);
  }
}
