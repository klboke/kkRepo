package com.github.klboke.kkrepo.server.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.VersionWatermark;
import com.github.klboke.kkrepo.server.support.InMemoryVersionWatermark;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

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

  @Test
  void doesNotPublishASnapshotRenderedBeforeANewerRevision() throws Exception {
    AtomicInteger loads = new AtomicInteger();
    CountDownLatch staleStarted = new CountDownLatch(1);
    CountDownLatch newerPublished = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Optional<StagingMetadataSnapshotCache.Snapshot>> stale = pool.submit(
          () -> cache.find(key, () -> {
            staleStarted.countDown();
            try {
              assertTrue(newerPublished.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(error);
            }
            return snapshot("stale", loads);
          }));
      assertTrue(staleStarted.await(10, TimeUnit.SECONDS));
      watermark.bump(StagingMetadataSnapshotCache.versionName(7L));
      Optional<StagingMetadataSnapshotCache.Snapshot> fresh =
          cache.find(key, () -> snapshot("fresh", loads));
      assertEquals("fresh", body(fresh.orElseThrow()));
      newerPublished.countDown();
      assertEquals("stale", body(stale.get(10, TimeUnit.SECONDS).orElseThrow()));

      // The slow render must not have replaced the newer bytes in the cache.
      AtomicBoolean reloaded = new AtomicBoolean();
      Optional<StagingMetadataSnapshotCache.Snapshot> after = cache.find(key, () -> {
        reloaded.set(true);
        return snapshot("other", loads);
      });
      assertFalse(reloaded.get());
      assertEquals("fresh", body(after.orElseThrow()));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void springCanConstructTheCacheWithBothProductionDependencies() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(VersionWatermark.class, () -> watermark);
      context.registerBean(AssetMetadataCache.class, () -> assetMetadata);
      context.register(StagingMetadataSnapshotCache.class);
      context.refresh();
      assertNotNull(context.getBean(StagingMetadataSnapshotCache.class));
    }
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
