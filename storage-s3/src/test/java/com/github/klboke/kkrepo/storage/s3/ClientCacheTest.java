package com.github.klboke.kkrepo.storage.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The lease rules of {@link ClientCache}, with timings short enough to observe expiry. */
class ClientCacheTest {
  private static final Logger LOG = LoggerFactory.getLogger(ClientCacheTest.class);

  private static final class Client {
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger closes = new AtomicInteger();

    private void close() {
      closed.set(true);
      closes.incrementAndGet();
    }
  }

  private static ClientCache<Client> cache(Duration idleExpiry, Duration closeGrace) {
    return new ClientCache<>(
        "client-cache-test", "Test", LOG, config -> new Client(), Client::close,
        idleExpiry, closeGrace);
  }

  private static S3BlobStoreConfig config(long id) {
    return S3BlobStoreConfig.of(id, "store-" + id, "http://localhost:9000", "us-east-1", "bucket", "", Map.of());
  }

  @Test
  void anIdleExpiredClientIsNotClosedWhileAStreamStillHoldsALease() throws Exception {
    ClientCache<Client> cache = cache(Duration.ofMillis(100), Duration.ZERO);
    try {
      ClientHandle<Client> handle = cache.handle(config(1));
      ClientHandle.Lease<Client> lease = handle.lease();
      Client streaming = lease.client();

      // Nothing touches the store for longer than the idle expiry, then another lookup replaces it.
      Thread.sleep(400);
      Client replacement = handle.get();
      assertNotSame(streaming, replacement);

      Thread.sleep(400);
      assertFalse(streaming.closed.get(), "a client with an open lease must stay open");

      lease.close();
      awaitClosed(streaming);
    } finally {
      cache.shutdown();
    }
  }

  @Test
  void aClientWithoutLeasesIsClosedAfterTheGrace() throws Exception {
    ClientCache<Client> cache = cache(Duration.ofHours(1), Duration.ZERO);
    try {
      ClientHandle<Client> handle = cache.handle(config(1));
      Client client = handle.get();

      cache.invalidate(1);

      awaitClosed(client);
    } finally {
      cache.shutdown();
    }
  }

  @Test
  void closingALeaseTwiceReleasesItOnlyOnce() throws Exception {
    ClientCache<Client> cache = cache(Duration.ofHours(1), Duration.ZERO);
    try {
      ClientHandle<Client> handle = cache.handle(config(1));
      ClientHandle.Lease<Client> first = handle.lease();
      ClientHandle.Lease<Client> second = handle.lease();
      Client client = first.client();

      first.close();
      first.close();
      cache.invalidate(1);
      Thread.sleep(300);
      assertFalse(client.closed.get(), "the second lease is still open");

      second.close();
      awaitClosed(client);
    } finally {
      cache.shutdown();
    }
  }

  @Test
  void shutdownClosesEvenLeasedClients() {
    ClientCache<Client> cache = cache(Duration.ofHours(1), Duration.ofHours(1));
    ClientHandle.Lease<Client> lease = cache.handle(config(1)).lease();
    Client client = lease.client();

    cache.shutdown();

    assertTrue(client.closed.get());
    assertEquals(0, cache.cachedStoreIds().size());
  }

  @Test
  void shutdownReachesAnInvalidatedClientThatStillHoldsALease() {
    ClientCache<Client> cache = cache(Duration.ofHours(1), Duration.ZERO);
    ClientHandle.Lease<Client> lease = cache.handle(config(1)).lease();
    Client client = lease.client();
    cache.invalidate(1);
    assertFalse(client.closed.get(), "the open lease keeps it alive after invalidation");

    cache.shutdown();

    assertEquals(1, client.closes.get());
    lease.close();
  }

  @Test
  void aClientRetiredByShutdownIsNotClosedAgainByItsScheduledRetirement() throws Exception {
    ClientCache<Client> cache = cache(Duration.ofHours(1), Duration.ofMillis(100));
    Client client = cache.handle(config(1)).get();

    cache.shutdown();
    Thread.sleep(500);

    assertEquals(1, client.closes.get());
  }

  @Test
  void anIdleClientIsClosedEvenWhenNothingTouchesTheCacheAgain() throws Exception {
    ClientCache<Client> cache = cache(Duration.ofMillis(100), Duration.ZERO);
    try {
      Client client = cache.handle(config(1)).get();

      awaitClosed(client);
    } finally {
      cache.shutdown();
    }
  }

  @Test
  void aCloserThatFailsNeverBreaksEvictionOrShutdown() {
    ClientCache<Client> cache = new ClientCache<>(
        "client-cache-failing-closer", "Test", LOG, config -> new Client(),
        client -> { throw new IllegalStateException("close failed"); },
        Duration.ofHours(1), Duration.ZERO);
    cache.handle(config(1)).get();

    cache.invalidate(1);
    cache.handle(config(2)).get();
    cache.shutdown();

    assertEquals(0, cache.cachedStoreIds().size());
  }

  private static void awaitClosed(Client client) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
    while (!client.closed.get() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(client.closed.get(), "the client should have been closed");
  }
}
