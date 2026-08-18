package com.github.klboke.kkrepo.cache;

/** Listener invoked when an entry leaves a {@link LocalCache}. */
@FunctionalInterface
public interface LocalCacheRemovalListener<K, V> {
  void onRemoval(K key, V value, LocalCacheRemovalCause cause);
}
