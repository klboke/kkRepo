package com.github.klboke.kkrepo.server.apt;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
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
 * Node-local hot cache for the active, atomically published APT snapshot of a suite.
 *
 * <p>The snapshot is an immutable manifest pointing at immutable hidden assets. A successful
 * publish bumps a MySQL-backed watermark, so sibling replicas discard the old manifest after the
 * watermark's short local poll TTL. Until then they may serve the previous fully signed snapshot,
 * never a partially published revision. Cache loss is harmless and reloads from MySQL.
 */
@Component
final class AptPublishedSnapshotCache {
  private static final Logger log = LoggerFactory.getLogger(AptPublishedSnapshotCache.class);
  private static final String VERSION_PREFIX = "apt-published-snapshot:repo:";

  private final AptRegistryDao registry;
  private final VersionWatermark watermark;
  private final Cache<SuiteKey, AptRegistryDao.Snapshot> snapshots;
  private final Cache<SuiteKey, Long> observedVersions;
  private final boolean enabled;

  @Autowired
  AptPublishedSnapshotCache(
      AptRegistryDao registry,
      VersionWatermark watermark,
      @Value("${kkrepo.cache.apt-published-snapshot.enabled:true}") boolean enabled,
      @Value("${kkrepo.cache.apt-published-snapshot.ttl-seconds:60}") long ttlSeconds) {
    this.registry = registry;
    this.watermark = watermark;
    this.enabled = enabled && ttlSeconds > 0;
    Duration ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
    this.snapshots = Caffeine.newBuilder()
        .expireAfterWrite(ttl)
        .maximumSize(100_000)
        .build();
    this.observedVersions = Caffeine.newBuilder()
        .maximumSize(100_000)
        .build();
  }

  /** Direct DAO behavior for focused service tests that do not exercise cache invalidation. */
  AptPublishedSnapshotCache(AptRegistryDao registry) {
    this(registry, null, false, 0);
  }

  Optional<AptRegistryDao.Snapshot> find(long repositoryId, String distribution) {
    SuiteKey key = new SuiteKey(repositoryId, distribution);
    if (!enabled || watermark == null || !synchronizeVersion(key)) {
      return registry.findPublishedSnapshot(repositoryId, distribution);
    }
    AptRegistryDao.Snapshot cached = snapshots.getIfPresent(key);
    if (cached != null) return Optional.of(cached);
    Optional<AptRegistryDao.Snapshot> loaded =
        registry.findPublishedSnapshot(repositoryId, distribution).map(AptPublishedSnapshotCache::copy);
    loaded.ifPresent(snapshot -> snapshots.put(key, snapshot));
    return loaded;
  }

  /** Record a snapshot only after the durable fenced publish has succeeded. */
  void published(AptRegistryDao.Snapshot snapshot) {
    if (!enabled || watermark == null || snapshot == null) return;
    SuiteKey key = new SuiteKey(snapshot.repositoryId(), snapshot.distribution());
    snapshots.invalidate(key);
    try {
      long version = watermark.bump(versionName(key));
      observedVersions.put(key, version);
      snapshots.put(key, copy(snapshot));
    } catch (RuntimeException error) {
      observedVersions.invalidate(key);
      log.warn("Failed invalidating APT snapshot cache for repo {} distribution {}",
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
      log.warn("Failed reading APT snapshot cache version for repo {} distribution {}; "
              + "bypassing cache",
          key.repositoryId(), key.distribution(), error);
      return false;
    }
  }

  private static AptRegistryDao.Snapshot copy(AptRegistryDao.Snapshot snapshot) {
    return new AptRegistryDao.Snapshot(
        snapshot.repositoryId(), snapshot.distribution(), snapshot.revision(),
        snapshot.signingKeyRevision(), Map.copyOf(snapshot.manifest()),
        snapshot.releaseSha256(), snapshot.createdAt());
  }

  private static String versionName(SuiteKey key) {
    return VERSION_PREFIX + key.repositoryId() + ":" + key.distribution();
  }

  private record SuiteKey(long repositoryId, String distribution) { }
}
