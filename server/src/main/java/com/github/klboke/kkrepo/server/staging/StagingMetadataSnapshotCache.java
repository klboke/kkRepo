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
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p>The revision that fenced a render is stored in the same immutable entry as the rendered bytes,
 * and it is revalidated on every hit and before every publication. A render that started before a
 * newer item or live write therefore cannot overwrite newer bytes in the cache: it is returned to
 * its own caller and left uncached. A failed revision read bypasses the cache instead of serving a
 * snapshot that cannot be fenced. The TTL only bounds local retention; correctness never depends on
 * it, and dropping the cache just rebuilds from MySQL and object storage.
 */
@Component
public final class StagingMetadataSnapshotCache {
  private static final Logger log = LoggerFactory.getLogger(StagingMetadataSnapshotCache.class);
  private static final String VERSION_PREFIX = "staging-metadata:session:";

  private final VersionWatermark watermark;
  private final AssetMetadataCache assetMetadata;
  private final LocalCache<Key, Entry> snapshots;

  @Autowired
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
        ? LocalCacheFactory.standard().<Key, Entry>builder("staging-metadata-snapshots")
            .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
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
      current = currentRevision(key);
    } catch (RuntimeException error) {
      // An unreadable revision cannot fence an old snapshot: rebuild and keep no local state.
      snapshots.invalidate(key);
      log.warn("Failed reading the staging metadata revision for session {} repository {}; "
          + "rebuilding without caching", key.sessionId(), key.repositoryId(), error);
      return loader.get();
    }
    Entry cached = snapshots.getIfPresent(key);
    if (cached != null && cached.revision().equals(current)) {
      return Optional.of(cached.snapshot());
    }
    Optional<Snapshot> loaded = loader.get();
    if (loaded.isEmpty()) {
      return loaded;
    }
    Revision afterLoad;
    try {
      afterLoad = currentRevision(key);
    } catch (RuntimeException error) {
      log.warn("Failed re-reading the staging metadata revision for session {} repository {}; "
          + "serving the rebuilt snapshot without caching it",
          key.sessionId(), key.repositoryId(), error);
      return loaded;
    }
    if (!afterLoad.equals(current)) {
      // A staged item or a live asset changed while this snapshot was being rendered. Publishing
      // it would attach older bytes to the newer revision, so the render is only returned to the
      // caller that asked for it.
      log.debug("Discarding a staging metadata snapshot for session {} repository {} rendered at "
          + "revision {} because revision {} was published meanwhile",
          key.sessionId(), key.repositoryId(), current, afterLoad);
      return loaded;
    }
    snapshots.put(key, new Entry(current, loaded.get()));
    return loaded;
  }

  private Revision currentRevision(Key key) {
    return new Revision(
        watermark.current(versionName(key.sessionId())),
        assetMetadata.currentRepositoryVersion(key.repositoryId()));
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

  /** Cached bytes together with the exact revision they were rendered for. */
  private record Entry(Revision revision, Snapshot snapshot) {
  }

  private record Revision(long staged, long repository) {
  }
}
