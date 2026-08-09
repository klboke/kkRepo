package com.github.klboke.kkrepo.server.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.server.cache.VersionWatermark;
import com.github.klboke.kkrepo.server.support.InMemoryVersionWatermark;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AptPublishedSnapshotCacheTest {

  @Test
  void cachesImmutablePublishedManifestOnTheReadPath() {
    AptRegistryDao registry = mock(AptRegistryDao.class);
    AptRegistryDao.Snapshot snapshot = snapshot(1);
    when(registry.findPublishedSnapshot(7L, "stable")).thenReturn(Optional.of(snapshot));
    AptPublishedSnapshotCache cache = new AptPublishedSnapshotCache(
        registry, new InMemoryVersionWatermark(), true, 60);

    assertEquals(1, cache.find(7L, "stable").orElseThrow().revision());
    assertEquals(1, cache.find(7L, "stable").orElseThrow().revision());

    verify(registry, times(1)).findPublishedSnapshot(7L, "stable");
  }

  @Test
  void publishWatermarkInvalidatesSiblingReplica() {
    AtomicReference<AptRegistryDao.Snapshot> durable = new AtomicReference<>(snapshot(1));
    AptRegistryDao registry = mock(AptRegistryDao.class);
    when(registry.findPublishedSnapshot(7L, "stable"))
        .thenAnswer(ignored -> Optional.of(durable.get()));
    InMemoryVersionWatermark watermark = new InMemoryVersionWatermark();
    AptPublishedSnapshotCache writer = new AptPublishedSnapshotCache(registry, watermark, true, 60);
    AptPublishedSnapshotCache reader = new AptPublishedSnapshotCache(registry, watermark, true, 60);

    assertEquals(1, reader.find(7L, "stable").orElseThrow().revision());
    AptRegistryDao.Snapshot second = snapshot(2);
    durable.set(second);
    writer.published(second);

    assertEquals(2, reader.find(7L, "stable").orElseThrow().revision());
    verify(registry, times(2)).findPublishedSnapshot(7L, "stable");
  }

  @Test
  void watermarkFailureBypassesCache() {
    AptRegistryDao registry = mock(AptRegistryDao.class);
    when(registry.findPublishedSnapshot(7L, "stable"))
        .thenReturn(Optional.of(snapshot(1)));
    VersionWatermark watermark = mock(VersionWatermark.class);
    when(watermark.current("apt-published-snapshot:repo:7:stable"))
        .thenThrow(new IllegalStateException("watermark unavailable"));
    AptPublishedSnapshotCache cache = new AptPublishedSnapshotCache(
        registry, watermark, true, 60);

    cache.find(7L, "stable");
    cache.find(7L, "stable");

    verify(registry, times(2)).findPublishedSnapshot(7L, "stable");
  }

  @Test
  void publishWatermarkFailureDropsLocalSnapshotAndReloadsDurableState() {
    AtomicReference<AptRegistryDao.Snapshot> durable = new AtomicReference<>(snapshot(1));
    AptRegistryDao registry = mock(AptRegistryDao.class);
    when(registry.findPublishedSnapshot(7L, "stable"))
        .thenAnswer(ignored -> Optional.of(durable.get()));
    VersionWatermark watermark = mock(VersionWatermark.class);
    when(watermark.current("apt-published-snapshot:repo:7:stable")).thenReturn(0L);
    when(watermark.bump("apt-published-snapshot:repo:7:stable"))
        .thenThrow(new IllegalStateException("watermark unavailable"));
    AptPublishedSnapshotCache cache = new AptPublishedSnapshotCache(
        registry, watermark, true, 60);

    assertEquals(1, cache.find(7L, "stable").orElseThrow().revision());
    AptRegistryDao.Snapshot second = snapshot(2);
    durable.set(second);
    cache.published(second);

    assertEquals(2, cache.find(7L, "stable").orElseThrow().revision());
    verify(registry, times(2)).findPublishedSnapshot(7L, "stable");
  }

  private static AptRegistryDao.Snapshot snapshot(long revision) {
    return new AptRegistryDao.Snapshot(
        7L, "stable", revision, 1,
        Map.of("dists/stable/InRelease", ".apt/revisions/" + revision + "/InRelease"),
        "a".repeat(64), Instant.EPOCH.plusSeconds(revision));
  }
}
