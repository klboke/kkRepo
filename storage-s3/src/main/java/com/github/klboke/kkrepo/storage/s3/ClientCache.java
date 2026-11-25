package com.github.klboke.kkrepo.storage.s3;

import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;

/**
 * Node-local cache of SDK clients (one connection pool each) with the lifetime rules that a closed
 * client can never be reopened.
 *
 * <ul>
 *   <li>Clients are keyed by store ID <em>and</em> configuration signature. Callers that disagree
 *       about a store's signature each get their own client; none closes another's.
 *   <li>A client leaves the cache only when it has been idle for the idle expiry, or when its store is
 *       invalidated. It is then closed after the close grace, so calls that already hold it can finish,
 *       and not while a {@linkplain ClientHandle#lease() lease} on it is open (a response stream that is
 *       still being read).
 *   <li>Anything that outlives a call holds a {@link ClientHandle}, which resolves the current client on
 *       every use, so an invalidated client is rebuilt by the next call.
 * </ul>
 */
final class ClientCache<C> {
  private static final Duration LEASE_RECHECK = Duration.ofMinutes(1);

  private final Logger log;
  private final String label;
  private final Function<S3BlobStoreConfig, C> builder;
  private final Consumer<C> closer;
  private final Duration closeGrace;
  private final Duration leaseRecheck;
  private final LocalCache<Key, Entry<C>> cache;
  /** Entries that left the cache and are not closed yet, so shutdown can still reach them. */
  private final Set<Entry<C>> retired = ConcurrentHashMap.newKeySet();

  /** {@code closeGrace} zero closes unleased clients right away (tests). */
  ClientCache(
      String name,
      String label,
      Logger log,
      Function<S3BlobStoreConfig, C> builder,
      Consumer<C> closer,
      Duration idleExpiry,
      Duration closeGrace) {
    this.log = log;
    this.label = label;
    this.builder = builder;
    this.closer = closer;
    this.closeGrace = closeGrace;
    this.leaseRecheck = closeGrace.isZero() ? Duration.ofMillis(50) : LEASE_RECHECK;
    this.cache = LocalCacheFactory.standard()
        .<Key, Entry<C>>builder(name)
        .expireAfterAccess(idleExpiry)
        .scheduledExpiration()
        .removalListener((key, entry, cause) -> retire(entry))
        .build();
  }

  /** The client for this exact configuration, built on first use. */
  C client(S3BlobStoreConfig config) {
    return entry(new Key(config.id(), config.signature()), config).client;
  }

  /** A reference that always resolves the live client for this configuration. */
  ClientHandle<C> handle(S3BlobStoreConfig config) {
    Key key = new Key(config.id(), config.signature());
    return new ClientHandle<>() {
      @Override
      public C get() {
        return entry(key, config).client;
      }

      @Override
      public Lease<C> lease() {
        Entry<C> entry = entry(key, config);
        entry.leases.incrementAndGet();
        AtomicBoolean released = new AtomicBoolean();
        return new Lease<>() {
          @Override
          public C client() {
            return entry.client;
          }

          @Override
          public void close() {
            if (released.compareAndSet(false, true)) {
              entry.leases.decrementAndGet();
            }
          }
        };
      }
    };
  }

  private Entry<C> entry(Key key, S3BlobStoreConfig config) {
    Entry<C> cached = cache.getIfPresent(key);
    if (cached != null) {
      return cached;
    }
    return cache.get(key, ignored -> create(config));
  }

  private Entry<C> create(S3BlobStoreConfig config) {
    for (Entry<C> other : cache.asMap().values()) {
      if (other.config.id() == config.id()) {
        // Two callers disagree about this store's configuration. Each now owns its own client, so
        // neither closes the other's, but the disagreement itself is worth knowing about.
        log.warn("{} blob store {} (id={}) is now used with a second configuration; callers disagree about: {}",
            label, config.name(), config.id(), other.config.differingFields(config));
        break;
      }
    }
    return new Entry<>(config, builder.apply(config));
  }

  /** Drops every client of this store (deleted or reconfigured); the next use builds a fresh one. */
  void invalidate(long id) {
    int removed = 0;
    for (Key key : Set.copyOf(cache.asMap().keySet())) {
      if (key.storeId() == id) {
        cache.invalidate(key);
        removed++;
      }
    }
    if (removed > 0) {
      log.info("Invalidated {} {} client(s) of blob store id={}; they close after {} once no lease is open",
          removed, label, id, closeGrace);
    }
  }

  Set<Long> cachedStoreIds() {
    Set<Long> ids = new HashSet<>();
    for (Key key : cache.asMap().keySet()) {
      ids.add(key.storeId());
    }
    return Set.copyOf(ids);
  }

  /** Closes every client immediately, leased or not, retired or not: the process is going away. */
  void shutdown() {
    Set<Entry<C>> open = new HashSet<>(cache.asMap().values());
    cache.invalidateAll();
    open.addAll(retired);
    open.forEach(this::close);
  }

  private void retire(Entry<C> entry) {
    if (entry != null && !entry.closed.get()) {
      retired.add(entry);
      later(closeGrace, () -> closeWhenUnleased(entry));
    }
  }

  private void closeWhenUnleased(Entry<C> entry) {
    if (entry.closed.get()) {
      return;
    }
    if (entry.leases.get() > 0) {
      later(leaseRecheck, () -> closeWhenUnleased(entry));
      return;
    }
    close(entry);
  }

  /** The only place a client is closed, so scheduled retirement and shutdown never close it twice. */
  private void close(Entry<C> entry) {
    if (entry.closed.compareAndSet(false, true)) {
      retired.remove(entry);
      closeQuietly(entry.client);
    }
  }

  private void later(Duration delay, Runnable task) {
    if (delay.isZero()) {
      task.run();
      return;
    }
    CompletableFuture.runAsync(
        task, CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS));
  }

  private void closeQuietly(C client) {
    try {
      closer.accept(client);
    } catch (RuntimeException ignored) {
      // best-effort close on eviction / shutdown
    }
  }

  private record Key(long storeId, String signature) {
  }

  private static final class Entry<C> {
    private final S3BlobStoreConfig config;
    private final C client;
    private final AtomicInteger leases = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    private Entry(S3BlobStoreConfig config, C client) {
      this.config = config;
      this.client = client;
    }
  }
}
