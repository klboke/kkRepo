package com.github.klboke.kkrepo.server.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import com.github.klboke.kkrepo.cache.SharedCache;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public class InMemorySharedCache implements SharedCache {
  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
  private final LocalCache<String, Entry> values = LocalCacheFactory.standard()
      .<String, Entry>builder("test-shared-cache-values")
      .build();
  private final LocalCache<String, AtomicLong> counters = LocalCacheFactory.standard()
      .<String, AtomicLong>builder("test-shared-cache-counters")
      .build();

  @Override
  public Optional<String> getString(String namespace, String key) {
    String cacheKey = cacheKey(namespace, key);
    Entry entry = values.getIfPresent(cacheKey);
    if (entry == null || entry.expired()) {
      values.invalidate(cacheKey);
      return Optional.empty();
    }
    return Optional.of(entry.value());
  }

  @Override
  public void putString(String namespace, String key, String value, Duration ttl) {
    values.put(cacheKey(namespace, key), new Entry(value, expiresAt(ttl)));
  }

  @Override
  public Optional<String> getAndDeleteString(String namespace, String key) {
    Entry removed = values.asMap().remove(cacheKey(namespace, key));
    if (removed == null || removed.expired()) {
      return Optional.empty();
    }
    return Optional.of(removed.value());
  }

  @Override
  public void evict(String namespace, String key) {
    values.invalidate(cacheKey(namespace, key));
    counters.invalidate(cacheKey(namespace, key));
  }

  @Override
  public void evictByPrefix(String namespace, String keyPrefix) {
    String prefix = namespace + ":" + (keyPrefix == null ? "" : keyPrefix);
    values.asMap().keySet().removeIf(key -> key.startsWith(prefix));
    counters.asMap().keySet().removeIf(key -> key.startsWith(prefix));
  }

  @Override
  public long increment(String namespace, String key, Duration ttl) {
    String cacheKey = cacheKey(namespace, key);
    long value = counters.get(cacheKey, ignored -> new AtomicLong()).incrementAndGet();
    values.put(cacheKey, new Entry(String.valueOf(value), expiresAt(ttl)));
    return value;
  }

  @Override
  public <T> Optional<T> getJson(String namespace, String key, Class<T> type) {
    return getString(namespace, key).map(value -> readJson(value, type));
  }

  @Override
  public <T> Optional<T> getJson(String namespace, String key, TypeReference<T> type) {
    return getString(namespace, key).map(value -> readJson(value, type));
  }

  @Override
  public void putJson(String namespace, String key, Object value, Duration ttl) {
    try {
      putString(namespace, key, objectMapper.writeValueAsString(value), ttl);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private <T> T readJson(String value, Class<T> type) {
    try {
      return objectMapper.readValue(value, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private <T> T readJson(String value, TypeReference<T> type) {
    try {
      return objectMapper.readValue(value, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Instant expiresAt(Duration ttl) {
    return ttl == null || ttl.isZero() || ttl.isNegative() ? null : Instant.now().plus(ttl);
  }

  private static String cacheKey(String namespace, String key) {
    return namespace + ":" + key;
  }

  private record Entry(String value, Instant expiresAt) {
    boolean expired() {
      return expiresAt != null && expiresAt.isBefore(Instant.now());
    }
  }
}
