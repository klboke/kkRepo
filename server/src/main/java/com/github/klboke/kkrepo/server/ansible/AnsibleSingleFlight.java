package com.github.klboke.kkrepo.server.ansible;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Collapses same-node proxy and artifact work while the database lease coordinates replicas. */
@Component
final class AnsibleSingleFlight {
  private final ConcurrentHashMap<String, CompletableFuture<Object>> flights =
      new ConcurrentHashMap<>();

  @SuppressWarnings("unchecked")
  <T> T execute(String key, Supplier<T> work) {
    CompletableFuture<Object> created = new CompletableFuture<>();
    CompletableFuture<Object> existing = flights.putIfAbsent(key, created);
    if (existing != null) {
      try {
        return (T) existing.join();
      } catch (CompletionException error) {
        throw propagate(error.getCause());
      }
    }
    try {
      T result = work.get();
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

  private static RuntimeException propagate(Throwable error) {
    if (error instanceof RuntimeException runtime) return runtime;
    if (error instanceof Error fatal) throw fatal;
    return new IllegalStateException(error);
  }
}
