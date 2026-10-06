package com.github.klboke.kkrepo.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Rebuildable node-local snapshots invalidated by a durable version watermark.
 *
 * <p>The loader remains the source of truth. A failed version read bypasses the cache, and failed
 * publication invalidation drops local state. Sibling replicas observe publications according to
 * the version provider's polling policy; the snapshot TTL bounds local retention independently.
 * Values must be immutable after copying. Call {@link #published} only after durable publication.
 */
public final class VersionedSnapshotCache<K, V> {
  private final LocalCache<K, V> snapshots;
  private final LocalCache<K, Long> observedVersions;
  private final Versions<K> versions;
  private final Function<K, Optional<V>> loader;
  private final UnaryOperator<V> copy;
  private final BiConsumer<K, RuntimeException> onVersionFailure;

  public VersionedSnapshotCache(
      String name, Duration ttl, long maximumSize, Versions<K> versions,
      Function<K, Optional<V>> loader, UnaryOperator<V> copy,
      BiConsumer<K, RuntimeException> onVersionFailure) {
    this.snapshots = LocalCacheFactory.standard().<K, V>builder(name)
        .expireAfterWrite(ttl).maximumSize(maximumSize).build();
    this.observedVersions = LocalCacheFactory.standard().<K, Long>builder(name + "-versions")
        .maximumSize(maximumSize).build();
    this.versions = versions;
    this.loader = loader;
    this.copy = copy;
    this.onVersionFailure = onVersionFailure;
  }

  public interface Versions<K> {
    long current(K key);

    long bump(K key);
  }

  public Optional<V> find(K key) {
    if (!synchronizeVersion(key)) return loader.apply(key);
    V cached = snapshots.getIfPresent(key);
    if (cached != null) return Optional.of(cached);
    Optional<V> loaded = loader.apply(key).map(copy);
    loaded.ifPresent(snapshot -> snapshots.put(key, snapshot));
    return loaded;
  }

  public void published(K key, V snapshot) {
    snapshots.invalidate(key);
    try {
      long version = versions.bump(key);
      observedVersions.put(key, version);
      snapshots.put(key, copy.apply(snapshot));
    } catch (RuntimeException error) {
      observedVersions.invalidate(key);
      onVersionFailure.accept(key, error);
    }
  }

  private boolean synchronizeVersion(K key) {
    try {
      long current = versions.current(key);
      Long observed = observedVersions.getIfPresent(key);
      if (observed != null && observed.longValue() != current) snapshots.invalidate(key);
      observedVersions.put(key, current);
      return true;
    } catch (RuntimeException error) {
      snapshots.invalidate(key);
      observedVersions.invalidate(key);
      onVersionFailure.accept(key, error);
      return false;
    }
  }
}
