package com.github.klboke.kkrepo.server.r;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import com.github.klboke.kkrepo.server.cache.VersionWatermark;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RPublishedSnapshotCacheTest {
  private final RRegistryDao registry = mock(RRegistryDao.class);
  private final VersionWatermark watermark = mock(VersionWatermark.class);

  @Test
  void cachesImmutableCopiesAndInvalidatesAcrossReplicaWatermarks() {
    LinkedHashMap<String, String> mutable = new LinkedHashMap<>();
    mutable.put("index", "hidden-1");
    RRegistryDao.Snapshot first = snapshot(1, mutable);
    when(watermark.current(anyString())).thenReturn(3L, 3L, 4L);
    when(registry.findPublishedSnapshot(1L, "src/contrib"))
        .thenReturn(Optional.of(first), Optional.of(snapshot(2, Map.of("index", "hidden-2"))));
    RPublishedSnapshotCache cache =
        new RPublishedSnapshotCache(registry, watermark, true, 60);

    assertEquals(1L, cache.find(1L, "src/contrib").orElseThrow().revision());
    mutable.put("later", "mutation");
    assertFalse(cache.find(1L, "src/contrib").orElseThrow().manifest()
        .containsKey("later"));
    assertEquals(2L, cache.find(1L, "src/contrib").orElseThrow().revision());
    verify(registry, times(2)).findPublishedSnapshot(1L, "src/contrib");
  }

  @Test
  void publishedSnapshotBumpsWatermarkAndFallsBackWhenSharedStateFails() {
    RPublishedSnapshotCache cache =
        new RPublishedSnapshotCache(registry, watermark, true, 60);
    RRegistryDao.Snapshot snapshot = snapshot(5, Map.of("index", "hidden"));
    when(watermark.bump(anyString())).thenReturn(9L);
    when(watermark.current(anyString())).thenReturn(9L);

    cache.published(snapshot);
    assertEquals(5L, cache.find(1L, "src/contrib").orElseThrow().revision());
    verify(watermark).bump("r-published-snapshot:repo:1:src/contrib");

    when(watermark.current(anyString())).thenThrow(new IllegalStateException("down"));
    when(registry.findPublishedSnapshot(1L, "other"))
        .thenReturn(Optional.of(snapshot(6, Map.of())));
    assertEquals(6L, cache.find(1L, "other").orElseThrow().revision());

    when(watermark.bump(anyString())).thenThrow(new IllegalStateException("down"));
    cache.published(snapshot(7, Map.of()));
    cache.published(null);
  }

  @Test
  void disabledCacheAlwaysUsesDao() {
    RPublishedSnapshotCache cache = new RPublishedSnapshotCache(registry);
    when(registry.findPublishedSnapshot(1L, "src/contrib"))
        .thenReturn(Optional.empty(), Optional.of(snapshot(1, Map.of())));
    assertTrue(cache.find(1L, "src/contrib").isEmpty());
    assertTrue(cache.find(1L, "src/contrib").isPresent());
    cache.published(snapshot(1, Map.of()));
    verify(registry, times(2)).findPublishedSnapshot(1L, "src/contrib");
  }

  private static RRegistryDao.Snapshot snapshot(long revision, Map<String, String> manifest) {
    return new RRegistryDao.Snapshot(
        1L, "src/contrib", revision, 1, manifest, "a".repeat(64), Instant.EPOCH);
  }
}
