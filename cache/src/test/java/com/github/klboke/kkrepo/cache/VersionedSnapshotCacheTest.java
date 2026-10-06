package com.github.klboke.kkrepo.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class VersionedSnapshotCacheTest {
  @Test
  void copiesPublishedValuesAndInvalidatesSiblingReplicas() {
    Backend backend = new Backend();
    var writer = backend.cache(Duration.ofMinutes(1));
    var reader = backend.cache(Duration.ofMinutes(1));
    assertEquals(Map.of("revision", 1), reader.find("suite").orElseThrow());
    backend.snapshot = new HashMap<>(Map.of("revision", 2));
    writer.published("suite", backend.snapshot);
    backend.snapshot.put("local-mutation", 3);
    assertEquals(Map.of("revision", 2), writer.find("suite").orElseThrow());
    assertEquals(2, reader.find("suite").orElseThrow().get("revision"));
    assertEquals(2, backend.loads);
  }

  @Test
  void versionFailuresDropCachedValuesAndUseDurableState() {
    Backend backend = new Backend();
    var cache = backend.cache(Duration.ofMinutes(1));
    cache.find("suite");
    backend.snapshot = Map.of("revision", 2);
    backend.failReads = true;
    assertEquals(2, cache.find("suite").orElseThrow().get("revision"));
    backend.failReads = false;
    assertEquals(2, cache.find("suite").orElseThrow().get("revision"));
    assertEquals(3, backend.loads);
    assertEquals(1, backend.failures);

    backend.snapshot = Map.of("revision", 3);
    backend.failBumps = true;
    cache.published("suite", Map.of("uncommitted", 4));
    assertEquals(Map.of("revision", 3), cache.find("suite").orElseThrow());
    assertEquals(2, backend.failures);
  }

  @Test
  void missingSnapshotsAreNotNegativelyCached() {
    Backend backend = new Backend();
    backend.snapshot = null;
    var cache = backend.cache(Duration.ofMinutes(1));
    assertTrue(cache.find("suite").isEmpty());
    backend.snapshot = Map.of("revision", 1);
    assertEquals(1, cache.find("suite").orElseThrow().get("revision"));
    assertEquals(2, backend.loads);
  }

  @Test
  void ttlReloadsEvenWithoutAWatermarkChange() {
    Backend backend = new Backend();
    var cache = backend.cache(Duration.ofNanos(1));
    cache.find("suite");
    backend.snapshot = Map.of("revision", 2);
    assertEquals(2, cache.find("suite").orElseThrow().get("revision"));
    assertEquals(2, backend.loads);
  }

  private static final class Backend implements VersionedSnapshotCache.Versions<String> {
    Map<String, Integer> snapshot = Map.of("revision", 1);
    long version;
    int loads;
    int failures;
    boolean failReads;
    boolean failBumps;

    @Override public long current(String key) {
      if (failReads) throw new IllegalStateException("version read failed");
      return version;
    }

    @Override public long bump(String key) {
      if (failBumps) throw new IllegalStateException("version write failed");
      return ++version;
    }

    VersionedSnapshotCache<String, Map<String, Integer>> cache(Duration ttl) {
      return new VersionedSnapshotCache<>("test-snapshots", ttl, 100, this,
          key -> { loads++; return Optional.ofNullable(snapshot); }, Map::copyOf,
          (key, failure) -> failures++);
    }
  }
}
