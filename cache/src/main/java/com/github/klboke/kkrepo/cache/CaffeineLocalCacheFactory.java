package com.github.klboke.kkrepo.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.ToIntBiFunction;

/** Caffeine-backed implementation kept private to the cache module. */
final class CaffeineLocalCacheFactory implements LocalCacheFactory {
  static final CaffeineLocalCacheFactory INSTANCE = new CaffeineLocalCacheFactory();

  private CaffeineLocalCacheFactory() {
  }

  @Override
  public <K, V> LocalCacheBuilder<K, V> builder(String name) {
    return new Builder<>(name);
  }

  private static final class Builder<K, V> implements LocalCacheBuilder<K, V> {
    private final String name;
    private final Caffeine<K, V> delegate;
    private boolean built;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Builder(String name) {
      this.name = requireName(name);
      this.delegate = (Caffeine<K, V>) (Caffeine) Caffeine.newBuilder();
    }

    @Override
    public LocalCacheBuilder<K, V> maximumSize(long maximumSize) {
      delegate.maximumSize(requirePositive(maximumSize, "maximumSize"));
      return this;
    }

    @Override
    public LocalCacheBuilder<K, V> maximumWeight(
        long maximumWeight, ToIntBiFunction<? super K, ? super V> weigher) {
      Objects.requireNonNull(weigher, "weigher");
      delegate.maximumWeight(requirePositive(maximumWeight, "maximumWeight"));
      delegate.weigher((key, value) -> weigher.applyAsInt(key, value));
      return this;
    }

    @Override
    public LocalCacheBuilder<K, V> expireAfterAccess(Duration duration) {
      delegate.expireAfterAccess(requirePositive(duration, "expireAfterAccess"));
      return this;
    }

    @Override
    public LocalCacheBuilder<K, V> expireAfterWrite(Duration duration) {
      delegate.expireAfterWrite(requirePositive(duration, "expireAfterWrite"));
      return this;
    }

    @Override
    public LocalCacheBuilder<K, V> removalListener(LocalCacheRemovalListener<K, V> listener) {
      Objects.requireNonNull(listener, "listener");
      delegate.removalListener((key, value, cause) -> listener.onRemoval(
          key, value, LocalCacheRemovalCause.valueOf(cause.name())));
      return this;
    }

    @Override
    public LocalCache<K, V> build() {
      if (built) {
        throw new IllegalStateException("Local cache builder has already built " + name);
      }
      built = true;
      return new CaffeineLocalCache<>(delegate.build());
    }

    private static String requireName(String name) {
      String normalized = Objects.requireNonNull(name, "name").trim();
      if (normalized.isEmpty()) {
        throw new IllegalArgumentException("Local cache name must not be blank");
      }
      return normalized;
    }

    private static long requirePositive(long value, String option) {
      if (value <= 0) {
        throw new IllegalArgumentException(option + " must be positive");
      }
      return value;
    }

    private static Duration requirePositive(Duration duration, String option) {
      Objects.requireNonNull(duration, option);
      if (duration.isZero() || duration.isNegative()) {
        throw new IllegalArgumentException(option + " must be positive");
      }
      return duration;
    }
  }

  private static final class CaffeineLocalCache<K, V> implements LocalCache<K, V> {
    private final Cache<K, V> delegate;

    private CaffeineLocalCache(Cache<K, V> delegate) {
      this.delegate = delegate;
    }

    @Override
    public V getIfPresent(K key) {
      return delegate.getIfPresent(key);
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> mappingFunction) {
      return delegate.get(key, mappingFunction);
    }

    @Override
    public void put(K key, V value) {
      delegate.put(key, value);
    }

    @Override
    public void invalidate(K key) {
      delegate.invalidate(key);
    }

    @Override
    public void invalidateAll() {
      delegate.invalidateAll();
    }

    @Override
    public long estimatedSize() {
      return delegate.estimatedSize();
    }

    @Override
    public ConcurrentMap<K, V> asMap() {
      return delegate.asMap();
    }

    @Override
    public void cleanUp() {
      delegate.cleanUp();
    }
  }
}
