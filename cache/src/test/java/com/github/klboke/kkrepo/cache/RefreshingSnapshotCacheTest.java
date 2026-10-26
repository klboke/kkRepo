package com.github.klboke.kkrepo.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RefreshingSnapshotCacheTest {
  @Test void readsNeverLoadAndRefreshAtomicallyReplacesSnapshotsIncludingEmptyOnes() {
    var source = new AtomicReference<>(Map.of("rule", "BLOCK"));
    var calls = new AtomicInteger();
    var cache = new RefreshingSnapshotCache<>("rules", () -> {
      calls.incrementAndGet();
      return source.get();
    });
    assertThrows(IllegalStateException.class, cache::current);
    assertEquals(0, calls.get());
    cache.initialize();
    var pinned = cache.current();
    source.set(Map.of());
    for (int i = 0; i < 1000; i++) assertSame(pinned, cache.current());
    assertEquals(1, calls.get());
    assertTrue(cache.refresh());
    assertEquals(Map.of(), cache.current());
    assertEquals(Map.of("rule", "BLOCK"), pinned);
    for (int i = 0; i < 1000; i++) assertEquals(Map.of(), cache.current());
    assertEquals(2, calls.get());
  }

  @Test void failedAndNullLoadsKeepLastSuccessfulSnapshotAndAllowRecovery() {
    var source = new AtomicReference<>("first");
    var cache = new RefreshingSnapshotCache<>("rules", () -> {
      if ("failure".equals(source.get())) throw new IllegalArgumentException("bad snapshot");
      return source.get();
    });
    cache.initialize();
    source.set("failure");
    assertThrows(IllegalArgumentException.class, cache::refresh);
    assertEquals("first", cache.current());
    source.set(null);
    assertThrows(NullPointerException.class, cache::refresh);
    assertEquals("first", cache.current());
    source.set("second");
    cache.refresh();
    assertEquals("second", cache.current());
  }
}
