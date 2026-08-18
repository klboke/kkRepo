package com.github.klboke.kkrepo.cache;

/**
 * Entry point for all strongly typed, node-local caches.
 *
 * <p>The standard factory is stateless and shared. Business modules should request a named builder
 * here instead of importing or configuring the underlying cache library directly.
 */
public interface LocalCacheFactory {
  <K, V> LocalCacheBuilder<K, V> builder(String name);

  static LocalCacheFactory standard() {
    return CaffeineLocalCacheFactory.INSTANCE;
  }
}
