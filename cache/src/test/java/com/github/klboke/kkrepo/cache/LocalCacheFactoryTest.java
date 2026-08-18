package com.github.klboke.kkrepo.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LocalCacheFactoryTest {

  @Test
  void providesTypedSingleFlightCacheOperationsWithoutExposingTheBackend() {
    LocalCache<String, Integer> cache = LocalCacheFactory.standard()
        .<String, Integer>builder("test-typed-cache")
        .maximumSize(10)
        .expireAfterAccess(Duration.ofMinutes(1))
        .build();
    AtomicInteger loads = new AtomicInteger();

    assertEquals(1, cache.get("key", ignored -> loads.incrementAndGet()));
    assertEquals(1, cache.get("key", ignored -> loads.incrementAndGet()));
    assertEquals(1, loads.get());

    cache.asMap().compute("key", (ignored, current) -> current + 1);
    assertEquals(2, cache.getIfPresent("key"));
    cache.invalidate("key");
    assertNull(cache.getIfPresent("key"));
  }

  @Test
  void supportsWeightedCachesLifecycleOperationsAndBackendNeutralRemovalEvents()
      throws InterruptedException {
    CountDownLatch removed = new CountDownLatch(1);
    AtomicReference<String> removedKey = new AtomicReference<>();
    AtomicReference<LocalCacheRemovalCause> removalCause = new AtomicReference<>();
    LocalCache<String, String> cache = LocalCacheFactory.standard()
        .<String, String>builder("test-weighted-cache")
        .maximumWeight(10, (ignored, value) -> value.length())
        .expireAfterWrite(Duration.ofMinutes(1))
        .removalListener((key, value, cause) -> {
          removedKey.set(key);
          removalCause.set(cause);
          removed.countDown();
        })
        .build();

    cache.put("first", "value");
    assertEquals(1, cache.estimatedSize());
    cache.invalidate("first");
    cache.cleanUp();

    assertTrue(removed.await(5, TimeUnit.SECONDS));
    assertEquals("first", removedKey.get());
    assertEquals(LocalCacheRemovalCause.EXPLICIT, removalCause.get());

    cache.put("second", "value");
    cache.invalidateAll();
    cache.cleanUp();
    assertTrue(cache.asMap().isEmpty());
  }

  @Test
  void validatesNamesLimitsDurationsAndBuilderReuse() {
    assertThrows(IllegalArgumentException.class,
        () -> LocalCacheFactory.standard().builder(" "));
    assertThrows(IllegalArgumentException.class,
        () -> LocalCacheFactory.standard().builder("size").maximumSize(0));
    assertThrows(IllegalArgumentException.class,
        () -> LocalCacheFactory.standard().builder("weight")
            .maximumWeight(0, (ignoredKey, ignoredValue) -> 1));
    assertThrows(IllegalArgumentException.class,
        () -> LocalCacheFactory.standard().builder("ttl")
            .expireAfterWrite(Duration.ZERO));

    LocalCacheBuilder<String, String> builder = LocalCacheFactory.standard()
        .<String, String>builder("single-use");
    builder.build();
    assertThrows(IllegalStateException.class, builder::build);
  }
}
