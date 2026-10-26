package com.github.klboke.kkrepo.cache;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * One immutable, rebuildable node-local snapshot, replaced explicitly by a background refresher.
 * Reads never load, expire, or wait for refresh. Callers must initialize before serving traffic and
 * schedule refresh on every replica; the durable source remains authoritative. Failed loads retain
 * the last successful snapshot. Suppliers must return complete immutable values, including empty
 * snapshots, and must not mutate previously published values.
 */
public final class RefreshingSnapshotCache<T> {
  private static final String KEY = "snapshot";
  private final LocalCache<String, T> snapshots;
  private final Supplier<T> loader;
  private final ReentrantLock refreshLock = new ReentrantLock();

  public RefreshingSnapshotCache(String name, Supplier<T> loader) {
    this.loader = Objects.requireNonNull(loader, "loader");
    snapshots = LocalCacheFactory.standard().<String, T>builder(name).maximumSize(1).build();
  }

  /** Blocking startup load; errors propagate so startup cannot silently publish an empty policy. */
  public void initialize() {
    refreshLock.lock();
    try {
      publish();
    } finally {
      refreshLock.unlock();
    }
  }

  public T current() {
    T snapshot = snapshots.getIfPresent(KEY);
    if (snapshot == null) throw new IllegalStateException("Snapshot has not been initialized");
    return snapshot;
  }

  /** Skips overlapping refreshes; exceptions propagate without replacing the previous snapshot. */
  public boolean refresh() {
    if (!refreshLock.tryLock()) return false;
    try {
      publish();
      return true;
    } finally {
      refreshLock.unlock();
    }
  }

  private void publish() {
    snapshots.put(KEY, Objects.requireNonNull(loader.get(), "snapshot"));
  }
}
