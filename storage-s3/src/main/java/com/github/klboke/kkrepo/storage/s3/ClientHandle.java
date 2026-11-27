package com.github.klboke.kkrepo.storage.s3;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What a blob storage wrapper holds instead of an SDK client: it resolves the live client on every
 * use, so a client that was closed and replaced is never used after it was closed.
 */
public interface ClientHandle<C> {
  /** The current client, for an operation that finishes within one call. */
  C get();

  /**
   * The current client, kept open until the lease is closed. Use it for anything that outlives a call,
   * such as a response stream that is read later: a client that leaves the cache is not closed while
   * a lease on it is open.
   */
  Lease<C> lease();

  /**
   * Runs one SDK call on the current client and keeps that client open until the call returns, so a
   * request that is still waiting on I/O (an upload, a delete) is not cut short when its client is
   * retired meanwhile.
   */
  default <T> T call(Function<? super C, T> operation) {
    try (Lease<C> lease = lease()) {
      return operation.apply(lease.client());
    }
  }

  /** A handle over a fixed supplier; leases are plain references with nothing to release. */
  static <C> ClientHandle<C> of(Supplier<C> supplier) {
    return new ClientHandle<>() {
      @Override
      public C get() {
        return supplier.get();
      }

      @Override
      public Lease<C> lease() {
        C client = supplier.get();
        return new Lease<>() {
          @Override
          public C client() {
            return client;
          }

          @Override
          public void close() {
          }
        };
      }
    };
  }

  /** Releases the client when closed; closing twice is harmless. */
  interface Lease<C> extends AutoCloseable {
    C client();

    @Override
    void close();
  }
}
