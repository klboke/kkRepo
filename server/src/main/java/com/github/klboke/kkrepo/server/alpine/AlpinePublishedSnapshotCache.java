package com.github.klboke.kkrepo.server.alpine;

import com.github.klboke.kkrepo.cache.VersionedSnapshotCache;
import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
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
 * Node-local hot cache for the active, atomically published Alpine snapshot of a suite.
 *
 * <p>The snapshot is an immutable manifest pointing at immutable hidden assets. A successful
 * publish bumps a MySQL-backed watermark, so sibling replicas discard the old manifest after the
 * watermark's short local poll TTL. Until then they may serve the previous fully signed snapshot,
 * never a partially published revision. Cache loss is harmless and reloads from MySQL.
 */
@Component
final class AlpinePublishedSnapshotCache {
  private static final Logger log = LoggerFactory.getLogger(AlpinePublishedSnapshotCache.class);
  private static final String VERSION_PREFIX = "alpine-published-snapshot:repo:";

  private final AlpineRegistryDao registry;
  private final VersionedSnapshotCache<SuiteKey, AlpineRegistryDao.Snapshot> cache;

  @Autowired
  AlpinePublishedSnapshotCache(
      AlpineRegistryDao registry,
      VersionWatermark watermark,
      @Value("${kkrepo.cache.alpine-published-snapshot.enabled:true}") boolean enabled,
      @Value("${kkrepo.cache.alpine-published-snapshot.ttl-seconds:60}") long ttlSeconds) {
    this.registry = registry;
    this.cache = !enabled || ttlSeconds <= 0 || watermark == null ? null
        : new VersionedSnapshotCache<>(
            "alpine-published-snapshots", Duration.ofSeconds(ttlSeconds), 100_000,
            new VersionedSnapshotCache.Versions<SuiteKey>() {
              @Override
              public long current(SuiteKey key) {
                return watermark.current(versionName(key));
              }

              @Override
              public long bump(SuiteKey key) {
                return watermark.bump(versionName(key));
              }
            },
            key -> registry.findPublishedSnapshot(key.repositoryId(), key.distribution()),
            AlpinePublishedSnapshotCache::copy,
            (key, error) -> log.warn(
                "Failed synchronizing Alpine snapshot cache for repo {} distribution {}; "
                    + "discarding local state",
                key.repositoryId(), key.distribution(), error));
  }

  /** Direct DAO behavior for focused service tests that do not exercise cache invalidation. */
  AlpinePublishedSnapshotCache(AlpineRegistryDao registry) {
    this(registry, null, false, 0);
  }

  Optional<AlpineRegistryDao.Snapshot> find(long repositoryId, String distribution) {
    return cache == null ? registry.findPublishedSnapshot(repositoryId, distribution)
        : cache.find(new SuiteKey(repositoryId, distribution));
  }

  /** Record a snapshot only after the durable fenced publish has succeeded. */
  void published(AlpineRegistryDao.Snapshot snapshot) {
    if (cache != null && snapshot != null) {
      cache.published(new SuiteKey(snapshot.repositoryId(), snapshot.distribution()), snapshot);
    }
  }

  private static AlpineRegistryDao.Snapshot copy(AlpineRegistryDao.Snapshot snapshot) {
    return new AlpineRegistryDao.Snapshot(
        snapshot.repositoryId(), snapshot.distribution(), snapshot.revision(),
        snapshot.signingKeyRevision(), Map.copyOf(snapshot.manifest()),
        snapshot.indexSha256(), snapshot.createdAt());
  }

  private static String versionName(SuiteKey key) {
    return VERSION_PREFIX + key.repositoryId() + ":" + key.distribution();
  }

  private record SuiteKey(long repositoryId, String distribution) { }
}
