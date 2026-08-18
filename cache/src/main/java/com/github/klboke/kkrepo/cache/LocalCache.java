package com.github.klboke.kkrepo.cache;

import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * Strongly typed, node-local cache owned by the {@code cache} module.
 *
 * <p>Values stored here must be disposable and reconstructable from durable state. This interface
 * deliberately exposes only backend-neutral operations so business modules do not depend on a
 * specific cache implementation or its configuration-specific runtime types.
 */
public interface LocalCache<K, V> {
  V getIfPresent(K key);

  V get(K key, Function<? super K, ? extends V> mappingFunction);

  void put(K key, V value);

  void invalidate(K key);

  void invalidateAll();

  long estimatedSize();

  ConcurrentMap<K, V> asMap();

  void cleanUp();
}
