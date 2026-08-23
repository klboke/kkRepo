package com.github.klboke.kkrepo.server.r;

import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import com.github.klboke.kkrepo.server.cache.VersionWatermark;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Node-local hot cache for the active, atomically published R snapshot of a suite.
 *
 * <p>The snapshot is an immutable manifest pointing at immutable hidden assets. A successful
 * publish bumps a MySQL-backed watermark, so sibling replicas discard the old manifest after the
 * watermark's short local poll TTL. Until then they may serve the previous complete snapshot,
 * never a partially published revision. Cache loss is harmless and reloads from MySQL.
 */
@Component
final class RPublishedSnapshotCache {
  private static final Logger log = LoggerFactory.getLogger(RPublishedSnapshotCache.class);
  private static final String VERSION_PREFIX = "r-published-snapshot:repo:";

  private final RRegistryDao registry;
  private final VersionWatermark watermark;
  private final LocalCache<SuiteKey, RRegistryDao.Snapshot> snapshots;
  private final LocalCache<SuiteKey, Long> observedVersions;
  private final boolean enabled;

  @Autowired
  RPublishedSnapshotCache(
      RRegistryDao registry,
      VersionWatermark watermark,
      @Value("${kkrepo.cache.r-published-snapshot.enabled:true}") boolean enabled,
      @Value("${kkrepo.cache.r-published-snapshot.ttl-seconds:60}") long ttlSeconds) {
    this.registry = registry;
    this.watermark = watermark;
    this.enabled = enabled && ttlSeconds > 0;
    Duration ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
    this.snapshots = LocalCacheFactory.standard()
        .<SuiteKey, RRegistryDao.Snapshot>builder("r-published-snapshots")
        .expireAfterWrite(ttl)
        .maximumSize(100_000)
        .build();
    this.observedVersions = LocalCacheFactory.standard()
        .<SuiteKey, Long>builder("r-published-snapshot-versions")
        .maximumSize(100_000)
        .build();
  }

  /** Direct DAO behavior for focused service tests that do not exercise cache invalidation. */
  RPublishedSnapshotCache(RRegistryDao registry) {
    this(registry, null, false, 0);
  }

  Optional<RRegistryDao.Snapshot> find(long repositoryId, String distribution) {
    SuiteKey key = new SuiteKey(repositoryId, distribution);
    if (!enabled || watermark == null || !synchronizeVersion(key)) {
      return registry.findPublishedSnapshot(repositoryId, distribution);
    }
    RRegistryDao.Snapshot cached = snapshots.getIfPresent(key);
    if (cached != null) return Optional.of(cached);
    Optional<RRegistryDao.Snapshot> loaded =
        registry.findPublishedSnapshot(repositoryId, distribution).map(RPublishedSnapshotCache::copy);
    loaded.ifPresent(snapshot -> snapshots.put(key, snapshot));
    return loaded;
  }

  /** Record a snapshot only after the durable fenced publish has succeeded. */
  void published(RRegistryDao.Snapshot snapshot) {
    if (!enabled || watermark == null || snapshot == null) return;
    SuiteKey key = new SuiteKey(snapshot.repositoryId(), snapshot.distribution());
    snapshots.invalidate(key);
    try {
      long version = watermark.bump(versionName(key));
      observedVersions.put(key, version);
      snapshots.put(key, copy(snapshot));
    } catch (RuntimeException error) {
      observedVersions.invalidate(key);
      log.warn("Failed invalidating R snapshot cache for repo {} distribution {}",
          snapshot.repositoryId(), snapshot.distribution(), error);
    }
  }

  private boolean synchronizeVersion(SuiteKey key) {
    try {
      long current = watermark.current(versionName(key));
      Long observed = observedVersions.getIfPresent(key);
      if (observed != null && observed.longValue() != current) {
        snapshots.invalidate(key);
      }
      observedVersions.put(key, current);
      return true;
    } catch (RuntimeException error) {
      snapshots.invalidate(key);
      observedVersions.invalidate(key);
      log.warn("Failed reading R snapshot cache version for repo {} distribution {}; "
              + "bypassing cache",
          key.repositoryId(), key.distribution(), error);
      return false;
    }
  }

  private static RRegistryDao.Snapshot copy(RRegistryDao.Snapshot snapshot) {
    return new RRegistryDao.Snapshot(
        snapshot.repositoryId(), snapshot.distribution(), snapshot.revision(),
        snapshot.codecRevision(), Map.copyOf(snapshot.manifest()),
        snapshot.indexSha256(), snapshot.createdAt());
  }

  private static String versionName(SuiteKey key) {
    return VERSION_PREFIX + key.repositoryId() + ":" + key.distribution();
  }

  private record SuiteKey(long repositoryId, String distribution) { }
}
