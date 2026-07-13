package com.github.klboke.kkrepo.server.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.klboke.kkrepo.cache.SharedCache;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache.Loaded;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AssetMetadataCacheTest {

  private final SharedCache sharedCache = new InMemorySharedCache();

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void firstReadLoadsAndCachesPositiveHit() {
    AssetMetadataCache cache = new AssetMetadataCache(sharedCache, true, 120, 5);
    AtomicInteger loads = new AtomicInteger();

    Optional<CachedAssetMetadata> first = cache.find(7L, "com/foo/bar.jar",
        () -> {
          loads.incrementAndGet();
          return Optional.of(new Loaded(asset(101L, 7L, "com/foo/bar.jar"), blob(501L)));
        });
    Optional<CachedAssetMetadata> second = cache.find(7L, "com/foo/bar.jar",
        () -> {
          loads.incrementAndGet();
          return Optional.of(new Loaded(asset(101L, 7L, "com/foo/bar.jar"), blob(501L)));
        });

    assertEquals(1, loads.get(), "loader should fire only once across cache miss + hit");
    assertTrue(first.isPresent());
    assertTrue(second.isPresent());
    assertEquals(101L, second.get().assetId());
    assertEquals(501L, second.get().blob().id());
  }

  @Test
  void positiveHitUsesOneSharedCacheRead() {
    CountingSharedCache countingCache = new CountingSharedCache();
    AssetMetadataCache cache = new AssetMetadataCache(countingCache, true, 120, 5);
    cache.find(7L, "com/foo/bar.jar",
        () -> Optional.of(new Loaded(asset(101L, 7L, "com/foo/bar.jar"), blob(501L))));

    countingCache.resetCounts();
    Optional<CachedAssetMetadata> cached = cache.find(7L, "com/foo/bar.jar",
        () -> { throw new AssertionError("should not load"); });

    assertTrue(cached.isPresent());
    assertEquals(1, countingCache.stringGets.get(), "positive hit should read the cache once");
    assertEquals(0, countingCache.jsonGets.get(), "positive hit should not perform a second JSON cache read");
  }

  @Test
  void missingResultCachedWithNegativeMarker() {
    AssetMetadataCache cache = new AssetMetadataCache(sharedCache, true, 120, 5);
    AtomicInteger loads = new AtomicInteger();

    Optional<CachedAssetMetadata> first = cache.find(7L, "missing.jar",
        () -> {
          loads.incrementAndGet();
          return Optional.empty();
        });
    Optional<CachedAssetMetadata> second = cache.find(7L, "missing.jar",
        () -> {
          loads.incrementAndGet();
          return Optional.empty();
        });

    assertTrue(first.isEmpty());
    assertTrue(second.isEmpty());
    assertEquals(1, loads.get(), "negative marker should short-circuit second loader");
  }

  @Test
  void missingNotPersistedWhenMissingTtlIsZero() {
    AssetMetadataCache cache = new AssetMetadataCache(sharedCache, true, 120, 0);
    AtomicInteger loads = new AtomicInteger();

    cache.find(7L, "missing.jar", () -> { loads.incrementAndGet(); return Optional.empty(); });
    cache.find(7L, "missing.jar", () -> { loads.incrementAndGet(); return Optional.empty(); });

    assertEquals(2, loads.get(), "missing-ttl=0 disables negative caching");
  }

  @Test
  void evictRemovesEntry() {
    AssetMetadataCache cache = new AssetMetadataCache(sharedCache, true, 120, 5);
    AtomicInteger loads = new AtomicInteger();

    cache.find(7L, "x.jar", () -> {
      loads.incrementAndGet();
      return Optional.of(new Loaded(asset(1L, 7L, "x.jar"), blob(10L)));
    });
    cache.evict(7L, "x.jar");
    cache.find(7L, "x.jar", () -> {
      loads.incrementAndGet();
      return Optional.of(new Loaded(asset(1L, 7L, "x.jar"), blob(10L)));
    });

    assertEquals(2, loads.get());
  }

  @Test
  void evictAfterCommitRunsImmediatelyOutsideTransaction() {
    AssetMetadataCache cache = new AssetMetadataCache(sharedCache, true, 120, 5);
    AtomicInteger loads = new AtomicInteger();

    cache.find(7L, "x.jar", () -> {
      loads.incrementAndGet();
      return Optional.of(new Loaded(asset(1L, 7L, "x.jar"), blob(10L)));
    });
    cache.evictAfterCommit(7L, "x.jar");
    cache.find(7L, "x.jar", () -> {
      loads.incrementAndGet();
      return Optional.of(new Loaded(asset(1L, 7L, "x.jar"), blob(10L)));
    });

    assertEquals(2, loads.get(), "no active tx => immediate eviction");
  }

  @Test
  void evictAfterCommitDeferredUntilCommitAndCollapsesDuplicates() {
    AssetMetadataCache cache = new AssetMetadataCache(sharedCache, true, 120, 5);
    AtomicInteger loads = new AtomicInteger();
    // Seed cache so we can observe eviction.
    cache.find(7L, "x.jar", () -> {
      loads.incrementAndGet();
      return Optional.of(new Loaded(asset(1L, 7L, "x.jar"), blob(10L)));
    });

    TransactionSynchronizationManager.initSynchronization();
    try {
      cache.evictAfterCommit(7L, "x.jar");
      cache.evictAfterCommit(7L, "x.jar"); // duplicate — should collapse
      cache.evictAfterCommit(7L, "y.jar");

      // Still cached: eviction hasn't fired yet because no commit has happened.
      assertTrue(sharedCache.getString("asset-metadata", "7:x.jar").isPresent(),
          "entry should remain until afterCommit fires");

      // Run all afterCommit callbacks.
      for (var sync : TransactionSynchronizationManager.getSynchronizations()) {
        sync.afterCommit();
        sync.afterCompletion(0);
      }
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    assertFalse(sharedCache.getString("asset-metadata", "7:x.jar").isPresent());
  }

  @Test
  void touchVerifiedRefreshesLastUpdatedInPlace() {
    AssetMetadataCache cache = new AssetMetadataCache(sharedCache, true, 120, 5);
    Instant original = Instant.parse("2026-05-01T00:00:00Z");
    AssetRecord original_asset = new AssetRecord(
        1L, 7L, null, 10L, RepositoryFormat.MAVEN2, "x.jar", null, "x.jar",
        "asset", "application/java-archive", 123L, null, original, Map.of());
    cache.find(7L, "x.jar",
        () -> Optional.of(new Loaded(original_asset, blob(10L))));

    Instant later = Instant.parse("2026-05-27T12:34:56Z");
    cache.touchVerified(7L, "x.jar", later);

    Optional<CachedAssetMetadata> reread = cache.find(7L, "x.jar",
        () -> { throw new AssertionError("should not load"); });
    assertTrue(reread.isPresent());
    assertEquals(later, reread.get().lastUpdatedAt());
  }

  @Test
  void touchVerifiedIsNoopWhenEntryAbsent() {
    AssetMetadataCache cache = new AssetMetadataCache(sharedCache, true, 120, 5);
    cache.touchVerified(7L, "absent.jar", Instant.now());
    assertFalse(sharedCache.getString("asset-metadata", "7:absent.jar").isPresent());
  }

  @Test
  void evictRepositoryBulkClearsByPrefix() {
    AssetMetadataCache cache = new AssetMetadataCache(sharedCache, true, 120, 5);
    cache.find(7L, "a.jar",
        () -> Optional.of(new Loaded(asset(1L, 7L, "a.jar"), blob(10L))));
    cache.find(7L, "b.jar",
        () -> Optional.of(new Loaded(asset(2L, 7L, "b.jar"), blob(11L))));
    cache.find(8L, "c.jar",
        () -> Optional.of(new Loaded(asset(3L, 8L, "c.jar"), blob(12L))));

    cache.evictRepository(7L);

    assertFalse(sharedCache.getString("asset-metadata", "7:a.jar").isPresent());
    assertFalse(sharedCache.getString("asset-metadata", "7:b.jar").isPresent());
    assertTrue(sharedCache.getString("asset-metadata", "8:c.jar").isPresent(),
        "repo 8 entries untouched");
  }

  @Test
  void positiveTtlZeroBypassesPositiveCacheWrites() {
    AssetMetadataCache cache = new AssetMetadataCache(sharedCache, true, 0, 5);
    AtomicInteger loads = new AtomicInteger();

    cache.find(7L, "x.jar", () -> {
      loads.incrementAndGet();
      return Optional.of(new Loaded(asset(1L, 7L, "x.jar"), blob(10L)));
    });
    cache.find(7L, "x.jar", () -> {
      loads.incrementAndGet();
      return Optional.of(new Loaded(asset(1L, 7L, "x.jar"), blob(10L)));
    });

    assertEquals(2, loads.get(), "ttl-seconds=0 must skip positive writes, not persist forever");
    assertFalse(sharedCache.getString("asset-metadata", "7:x.jar").isPresent(),
        "no entry should land in the shared cache");
  }

  @Test
  void positiveTtlZeroIgnoresStalePositiveCacheEntry() {
    // Simulate cache state left by an earlier deployment (or a non-zero TTL configuration):
    // a positive entry exists for (7, x.jar). With ttl-seconds=0 the cache must ignore it and
    // re-read from the loader, otherwise toggling the knob to 0 doesn't actually disable.
    AssetMetadataCache writer = new AssetMetadataCache(sharedCache, true, 120, 5);
    writer.find(7L, "x.jar",
        () -> Optional.of(new Loaded(asset(1L, 7L, "x.jar"), blob(10L))));
    assertTrue(sharedCache.getString("asset-metadata", "7:x.jar").isPresent(),
        "precondition: stale positive entry seeded");

    AssetMetadataCache cache = new AssetMetadataCache(sharedCache, true, 0, 5);
    AtomicInteger loads = new AtomicInteger();
    Optional<CachedAssetMetadata> result = cache.find(7L, "x.jar", () -> {
      loads.incrementAndGet();
      return Optional.of(new Loaded(asset(2L, 7L, "x.jar"), blob(20L)));
    });

    assertEquals(1, loads.get(), "loader must run despite stale positive entry");
    assertTrue(result.isPresent());
    assertEquals(2L, result.get().assetId(), "fresh load wins over stale cache entry");
    assertEquals(20L, result.get().blob().id());
  }

  @Test
  void positiveTtlZeroStillHonorsNegativeMissingMarker() {
    // The negative cache is governed by missing-ttl-seconds, independent of positive ttl.
    // Disabling positive cache must not break short-lived miss absorption.
    AssetMetadataCache writer = new AssetMetadataCache(sharedCache, true, 120, 5);
    writer.find(7L, "missing.jar", Optional::empty);
    assertTrue(sharedCache.getString("asset-metadata", "7:missing.jar").isPresent());

    AssetMetadataCache cache = new AssetMetadataCache(sharedCache, true, 0, 5);
    AtomicInteger loads = new AtomicInteger();
    Optional<CachedAssetMetadata> result = cache.find(7L, "missing.jar", () -> {
      loads.incrementAndGet();
      return Optional.empty();
    });

    assertEquals(0, loads.get(), "MISSING_MARKER short-circuit must still apply");
    assertTrue(result.isEmpty());
  }

  @Test
  void touchVerifiedIsNoopWhenPositiveTtlIsZero() {
    AssetMetadataCache cache = new AssetMetadataCache(sharedCache, true, 0, 5);
    cache.touchVerified(7L, "x.jar", Instant.now());
    assertFalse(sharedCache.getString("asset-metadata", "7:x.jar").isPresent());
  }

  @Test
  void disabledCacheAlwaysCallsLoader() {
    AssetMetadataCache cache = new AssetMetadataCache(sharedCache, false, 120, 5);
    AtomicInteger loads = new AtomicInteger();

    cache.find(7L, "x.jar", () -> {
      loads.incrementAndGet();
      return Optional.of(new Loaded(asset(1L, 7L, "x.jar"), blob(10L)));
    });
    cache.find(7L, "x.jar", () -> {
      loads.incrementAndGet();
      return Optional.of(new Loaded(asset(1L, 7L, "x.jar"), blob(10L)));
    });

    assertEquals(2, loads.get());
  }

  @Test
  void snapshotRoundTripsAssetAndBlob() {
    AssetMetadataCache cache = new AssetMetadataCache(sharedCache, true, 120, 5);
    AssetRecord a = asset(1L, 7L, "x.jar");
    AssetBlobRecord b = blob(10L);
    Optional<CachedAssetMetadata> snap = cache.find(7L, "x.jar",
        () -> Optional.of(new Loaded(a, b)));
    assertTrue(snap.isPresent());

    Optional<CachedAssetMetadata> reread = cache.find(7L, "x.jar",
        () -> { throw new AssertionError("should not load"); });
    assertTrue(reread.isPresent());
    AssetRecord rehydrated = reread.get().toAssetRecord();
    AssetBlobRecord rehydratedBlob = reread.get().toBlobRecord();

    assertEquals(a.id(), rehydrated.id());
    assertEquals(a.repositoryId(), rehydrated.repositoryId());
    assertEquals(a.path(), rehydrated.path());
    assertEquals(a.contentType(), rehydrated.contentType());
    assertEquals(a.size(), rehydrated.size());
    assertNotNull(rehydratedBlob);
    assertEquals(b.id(), rehydratedBlob.id());
    assertEquals(b.sha256(), rehydratedBlob.sha256());
    assertEquals(b.size(), rehydratedBlob.size());
  }

  @Test
  void loaderFromMapsEmpty() {
    Optional<Loaded> loaded = Loaded.from(Optional.empty(), null);
    assertTrue(loaded.isEmpty());
  }

  @Test
  void loaderFromHandlesAssetWithoutBlob() {
    AssetRecord noBlobAsset = new AssetRecord(
        1L, 7L, null, null, RepositoryFormat.MAVEN2, "x.pom", null, "x.pom",
        "asset", "application/xml", 0L, null, Instant.now(), Map.of());
    Optional<Loaded> loaded = Loaded.from(Optional.of(noBlobAsset), null);
    assertTrue(loaded.isPresent());
    assertSame(noBlobAsset, loaded.get().asset());
    assertNull(loaded.get().blob());
  }

  private static AssetRecord asset(long id, long repoId, String path) {
    return new AssetRecord(
        id, repoId, null, id * 10, RepositoryFormat.MAVEN2,
        path, null, path,
        "asset", "application/java-archive",
        1024L, null, Instant.parse("2026-05-01T00:00:00Z"),
        Map.of("custom", "v"));
  }

  private static AssetBlobRecord blob(long id) {
    return new AssetBlobRecord(
        id, 1L, "blob-ref-" + id, null, "objects/" + id, null,
        "sha1-" + id, "sha256-" + id, "md5-" + id, 1024L,
        "application/java-archive", "alice", "10.0.0.1",
        Instant.parse("2026-04-30T00:00:00Z"), Instant.parse("2026-05-01T00:00:00Z"),
        Map.of());
  }

  private static final class CountingSharedCache extends InMemorySharedCache {
    private final AtomicInteger stringGets = new AtomicInteger();
    private final AtomicInteger jsonGets = new AtomicInteger();

    @Override
    public Optional<String> getString(String namespace, String key) {
      stringGets.incrementAndGet();
      return super.getString(namespace, key);
    }

    @Override
    public <T> Optional<T> getJson(String namespace, String key, Class<T> type) {
      jsonGets.incrementAndGet();
      return super.getJson(namespace, key, type);
    }

    @Override
    public <T> Optional<T> getJson(String namespace, String key, TypeReference<T> type) {
      jsonGets.incrementAndGet();
      return super.getJson(namespace, key, type);
    }

    private void resetCounts() {
      stringGets.set(0);
      jsonGets.set(0);
    }
  }
}
