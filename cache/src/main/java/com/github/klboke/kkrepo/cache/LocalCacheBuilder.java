package com.github.klboke.kkrepo.cache;

import java.time.Duration;
import java.util.function.ToIntBiFunction;

/** Builder for a strongly typed {@link LocalCache}. */
public interface LocalCacheBuilder<K, V> {
  LocalCacheBuilder<K, V> maximumSize(long maximumSize);

  LocalCacheBuilder<K, V> maximumWeight(
      long maximumWeight, ToIntBiFunction<? super K, ? super V> weigher);

  LocalCacheBuilder<K, V> expireAfterAccess(Duration duration);

  LocalCacheBuilder<K, V> expireAfterWrite(Duration duration);

  LocalCacheBuilder<K, V> removalListener(LocalCacheRemovalListener<K, V> listener);

  LocalCache<K, V> build();
}
