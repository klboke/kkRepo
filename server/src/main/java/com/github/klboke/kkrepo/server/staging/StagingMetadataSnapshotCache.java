package com.github.klboke.kkrepo.server.staging;

import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.VersionWatermark;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Node-local snapshot of the metadata a staging session serves for one protocol scope.
 *
 * <p>Yum repodata and APT Packages/Release are rendered from the live catalogue plus the staged
 * items of a session. Rebuilding them on every request re-reads the catalogue and recompresses the
 * whole content set, including HEAD requests that only need the length. A snapshot is therefore
 * memoized per {@code (session, repository, scope)} and fenced by two durable revisions:
 *
 * <ul>
 *   <li>{@code staging-metadata:session:<id>} is bumped in MySQL whenever a staged item of the
 *       session is added, replaced or removed, so every replica discards its snapshot after the
 *       watermark poll TTL;
 *   <li>the repository generation maintained by {@link AssetMetadataCache} changes whenever a live
 *       asset is written or deleted, so promoted or directly published content also invalidates the
 *       snapshot.
 * </ul>
 *
 * <p>A failed revision read bypasses the cache instead of serving a snapshot that cannot be fenced.
 * The TTL only bounds local retention; correctness never depends on it, and dropping the cache just
 * rebuilds from MySQL and object storage.
 */
@Component
public final class StagingMetadataSnapshotCache {
  private static final Logger log = LoggerFactory.getLogger(StagingMetadataSnapshotCache.class);
  private static final String VERSION_PREFIX = "staging-metadata:session:";

  private final VersionWatermark watermark;
  private final AssetMetadataCache assetMetadata;
  private final LocalCache<Key, Snapshot> snapshots;
  private final LocalCache<Key, Revision> revisions;

  public StagingMetadataSnapshotCache(
      VersionWatermark watermark,
      AssetMetadataCache assetMetadata,
      @Value("${kkrepo.staging.metadata-cache.enabled:true}") boolean enabled,
      @Value("${kkrepo.staging.metadata-cache.ttl-seconds:60}") long ttlSeconds) {
    this(watermark, assetMetadata, enabled, ttlSeconds, 10_000);
  }

  public StagingMetadataSnapshotCache(
      VersionWatermark watermark,
      AssetMetadataCache assetMetadata,
      boolean enabled,
      long ttlSeconds,
      long maximumSize) {
    this.watermark = watermark;
    this.assetMetadata = assetMetadata;
    boolean active = enabled && watermark != null && assetMetadata != null && ttlSeconds > 0;
    this.snapshots = active
        ? LocalCacheFactory.standard().<Key, Snapshot>builder("staging-metadata-snapshots")
            .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
            .maximumSize(maximumSize)
            .build()
        : null;
    this.revisions = active
        ? LocalCacheFactory.standard().<Key, Revision>builder("staging-metadata-revisions")
            .maximumSize(maximumSize)
            .build()
        : null;
  }

  /** Durable revision name bumped by every staged item change of a session. */
  public static String versionName(long sessionId) {
    return VERSION_PREFIX + sessionId;
  }

  /**
   * Returns the snapshot for the key, rebuilding it through {@code loader} on a miss or when either
   * revision changed since the snapshot was stored.
   */
  public Optional<Snapshot> find(Key key, Supplier<Optional<Snapshot>> loader) {
    if (snapshots == null) {
      return loader.get();
    }
    Revision current;
    try {
      current = new Revision(
          watermark.current(versionName(key.sessionId())),
          assetMetadata.currentRepositoryVersion(key.repositoryId()));
    } catch (RuntimeException error) {
      // An unreadable revision cannot fence an old snapshot: rebuild and keep no local state.
      snapshots.invalidate(key);
      revisions.invalidate(key);
      log.warn("Failed reading the staging metadata revision for session {} repository {}; "
          + "rebuilding without caching", key.sessionId(), key.repositoryId(), error);
      return loader.get();
    }
    Revision observed = revisions.getIfPresent(key);
    if (observed != null && !observed.equals(current)) {
      snapshots.invalidate(key);
    }
    revisions.put(key, current);
    Snapshot cached = snapshots.getIfPresent(key);
    if (cached != null) {
      return Optional.of(cached);
    }
    Optional<Snapshot> loaded = loader.get();
    loaded.ifPresent(snapshot -> snapshots.put(key, snapshot));
    return loaded;
  }

  /** Identifier of one rendered metadata scope inside a staging session. */
  public record Key(long sessionId, long repositoryId, String scope) {
  }

  /** Immutable rendered metadata bytes; callers must not modify {@code body}. */
  public record File(byte[] body, String contentType) {
  }

  /** All files generated together for one scope, keyed by their repository-relative path. */
  public record Snapshot(Map<String, File> files) {
    public Snapshot {
      files = Map.copyOf(files);
    }
  }

  private record Revision(long staged, long repository) {
  }
}
