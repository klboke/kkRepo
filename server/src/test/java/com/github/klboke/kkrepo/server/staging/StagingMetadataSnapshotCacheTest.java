package com.github.klboke.kkrepo.server.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.support.InMemoryVersionWatermark;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StagingMetadataSnapshotCacheTest {
  private final InMemoryVersionWatermark watermark = new InMemoryVersionWatermark();
  private final AssetMetadataCache assetMetadata = mock(AssetMetadataCache.class);
  private final StagingMetadataSnapshotCache cache =
      new StagingMetadataSnapshotCache(watermark, assetMetadata, true, 60, 100);
  private final StagingMetadataSnapshotCache.Key key =
      new StagingMetadataSnapshotCache.Key(7L, 3L, "yum:");

  @Test
  void reusesTheSnapshotUntilTheStagingRevisionChanges() {
    AtomicInteger loads = new AtomicInteger();
    Optional<StagingMetadataSnapshotCache.Snapshot> first =
        cache.find(key, () -> snapshot("one", loads));

    Optional<StagingMetadataSnapshotCache.Snapshot> second =
        cache.find(key, () -> snapshot("two", loads));

    assertEquals(1, loads.get());
    assertSame(first.orElseThrow(), second.orElseThrow());

    // Another replica added, replaced or removed a staged item and bumped the shared revision.
    watermark.bump(StagingMetadataSnapshotCache.versionName(7L));
    Optional<StagingMetadataSnapshotCache.Snapshot> third =
        cache.find(key, () -> snapshot("three", loads));

    assertEquals(2, loads.get());
    assertEquals("three", body(third.orElseThrow()));
  }

  @Test
  void rebuildsWhenTheLiveRepositoryGenerationAdvances() {
    AtomicInteger loads = new AtomicInteger();
    when(assetMetadata.currentRepositoryVersion(3L)).thenReturn(1L);
    cache.find(key, () -> snapshot("live-1", loads));

    when(assetMetadata.currentRepositoryVersion(3L)).thenReturn(2L);
    Optional<StagingMetadataSnapshotCache.Snapshot> rebuilt =
        cache.find(key, () -> snapshot("live-2", loads));

    assertEquals(2, loads.get());
    assertEquals("live-2", body(rebuilt.orElseThrow()));
  }

  @Test
  void scopesAreIndependent() {
    AtomicInteger loads = new AtomicInteger();
    cache.find(key, () -> snapshot("root", loads));
    cache.find(new StagingMetadataSnapshotCache.Key(7L, 3L, "apt:stable:main"),
        () -> snapshot("apt", loads));

    assertEquals(2, loads.get());
  }

  @Test
  void bypassesTheCacheWhenTheRevisionCannotBeRead() {
    AtomicInteger loads = new AtomicInteger();
    when(assetMetadata.currentRepositoryVersion(3L))
        .thenThrow(new IllegalStateException("database unavailable"));

    cache.find(key, () -> snapshot("first", loads));
    cache.find(key, () -> snapshot("second", loads));

    assertEquals(2, loads.get());
  }

  private static Optional<StagingMetadataSnapshotCache.Snapshot> snapshot(
      String value, AtomicInteger loads) {
    loads.incrementAndGet();
    return Optional.of(new StagingMetadataSnapshotCache.Snapshot(Map.of(
        "repodata/repomd.xml",
        new StagingMetadataSnapshotCache.File(
            value.getBytes(StandardCharsets.UTF_8), "application/xml"))));
  }

  private static String body(StagingMetadataSnapshotCache.Snapshot snapshot) {
    return new String(
        snapshot.files().get("repodata/repomd.xml").body(), StandardCharsets.UTF_8);
  }
}
