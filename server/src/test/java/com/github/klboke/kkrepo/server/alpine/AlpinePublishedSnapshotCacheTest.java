package com.github.klboke.kkrepo.server.alpine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
import com.github.klboke.kkrepo.server.cache.VersionWatermark;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AlpinePublishedSnapshotCacheTest {
  private final AlpineRegistryDao registry = mock(AlpineRegistryDao.class);
  private final VersionWatermark watermark = mock(VersionWatermark.class);

  @Test
  void cachesImmutableCopiesAndInvalidatesAcrossReplicaWatermarks() {
    LinkedHashMap<String, String> mutable = new LinkedHashMap<>();
    mutable.put("index", "hidden-1");
    AlpineRegistryDao.Snapshot first = snapshot(1, mutable);
    when(watermark.current(anyString())).thenReturn(3L, 3L, 4L);
    when(registry.findPublishedSnapshot(1L, "v3.20/main/x86_64"))
        .thenReturn(Optional.of(first), Optional.of(snapshot(2, Map.of("index", "hidden-2"))));
    AlpinePublishedSnapshotCache cache =
        new AlpinePublishedSnapshotCache(registry, watermark, true, 60);

    assertEquals(1L, cache.find(1L, "v3.20/main/x86_64").orElseThrow().revision());
    mutable.put("later", "mutation");
    assertFalse(cache.find(1L, "v3.20/main/x86_64").orElseThrow().manifest()
        .containsKey("later"));
    assertEquals(2L, cache.find(1L, "v3.20/main/x86_64").orElseThrow().revision());
    verify(registry, times(2)).findPublishedSnapshot(1L, "v3.20/main/x86_64");
  }

  @Test
  void publishedSnapshotBumpsWatermarkAndFallsBackWhenSharedStateFails() {
    AlpinePublishedSnapshotCache cache =
        new AlpinePublishedSnapshotCache(registry, watermark, true, 60);
    AlpineRegistryDao.Snapshot snapshot = snapshot(5, Map.of("index", "hidden"));
    when(watermark.bump(anyString())).thenReturn(9L);
    when(watermark.current(anyString())).thenReturn(9L);

    cache.published(snapshot);
    assertEquals(5L, cache.find(1L, "v3.20/main/x86_64").orElseThrow().revision());
    verify(watermark).bump("alpine-published-snapshot:repo:1:v3.20/main/x86_64");

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
    AlpinePublishedSnapshotCache cache = new AlpinePublishedSnapshotCache(registry);
    when(registry.findPublishedSnapshot(1L, "suite"))
        .thenReturn(Optional.empty(), Optional.of(snapshot(1, Map.of())));
    assertTrue(cache.find(1L, "suite").isEmpty());
    assertTrue(cache.find(1L, "suite").isPresent());
    cache.published(snapshot(1, Map.of()));
    verify(registry, times(2)).findPublishedSnapshot(1L, "suite");
  }

  private static AlpineRegistryDao.Snapshot snapshot(long revision, Map<String, String> manifest) {
    return new AlpineRegistryDao.Snapshot(
        1L, "v3.20/main/x86_64", revision, 1, manifest, "a".repeat(64), Instant.EPOCH);
  }
}
