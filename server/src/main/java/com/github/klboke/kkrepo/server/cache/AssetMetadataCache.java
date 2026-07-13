package com.github.klboke.kkrepo.server.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.klboke.kkrepo.cache.SharedCache;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Node-local TTL cache for {@code asset + asset_blob} hot reads on the download path.
 *
 * <p>A {@link AssetDao#findAssetByPath} + {@link AssetDao#findBlobById} pair fires on every GET /
 * HEAD in every format. Both are point queries on indexed columns, but at high QPS they dominate
 * MySQL load. This cache stores a {@link CachedAssetMetadata} snapshot under a per-repository,
 * per-storage-path key so a single cache lookup replaces both. The entry is a recoverable
 * performance cache; writers evict the local copy after commit and sibling replicas converge via
 * TTL or durable repository cache-token checks.
 *
 * <p>Negative hits (path that doesn't exist) are cached with a much shorter TTL to absorb
 * tight-loop misses (e.g. clients probing siblings) without inviting long-lived staleness.
 *
 * <p>Writers MUST call {@link #evictAfterCommit} for every {@code (repoId, path)} they touch so
 * the editing replica drops stale hot data once the transaction commits.
 */
@Service
public class AssetMetadataCache {
  private static final Logger log = LoggerFactory.getLogger(AssetMetadataCache.class);
  private static final String NAMESPACE = "asset-metadata";
  private static final String MISSING_MARKER = "\"__missing__\"";
  private static final Object SYNC_RESOURCE_KEY =
      AssetMetadataCache.class.getName() + ".PENDING_EVICTS";

  private final SharedCache sharedCache;
  private final ObjectMapper objectMapper;
  private final boolean enabled;
  private final Duration positiveTtl;
  private final Duration missingTtl;

  @Autowired
  public AssetMetadataCache(
      SharedCache sharedCache,
      ObjectMapper objectMapper,
      @Value("${kkrepo.cache.asset-metadata.enabled:true}") boolean enabled,
      @Value("${kkrepo.cache.asset-metadata.ttl-seconds:60}") long ttlSeconds,
      @Value("${kkrepo.cache.asset-metadata.missing-ttl-seconds:5}") long missingTtlSeconds) {
    this.sharedCache = sharedCache;
    this.objectMapper = objectMapper;
    this.enabled = enabled;
    this.positiveTtl = ttlSeconds <= 0 ? Duration.ZERO : Duration.ofSeconds(ttlSeconds);
    this.missingTtl = missingTtlSeconds <= 0 ? Duration.ZERO : Duration.ofSeconds(missingTtlSeconds);
  }

  public AssetMetadataCache(
      SharedCache sharedCache,
      boolean enabled,
      long ttlSeconds,
      long missingTtlSeconds) {
    this(sharedCache, new ObjectMapper().registerModule(new JavaTimeModule()),
        enabled, ttlSeconds, missingTtlSeconds);
  }

  /**
   * Read-through. Returns the cached snapshot if present, else invokes {@code loader} and stashes
   * the result (positive or negative) before returning. {@link Optional#empty()} means the asset
   * truly does not exist or has no blob.
   */
  public Optional<CachedAssetMetadata> find(
      long repositoryId,
      String storagePath,
      Supplier<Optional<Loaded>> loader) {
    if (!enabled) {
      return loader.get().map(loaded -> CachedAssetMetadata.of(loaded.asset(), loaded.blob()));
    }
    String key = key(repositoryId, storagePath);
    try {
      Optional<String> raw = sharedCache.getString(NAMESPACE, key);
      if (raw.isPresent()) {
        if (MISSING_MARKER.equals(raw.get())) {
          return Optional.empty();
        }
        // ttl-seconds<=0 disables positive caching — ignore stale entries left by a prior
        // deployment or a sibling pod that still writes them, and force a fresh DB read.
        if (!positiveTtl.isZero()) {
          Optional<CachedAssetMetadata> cached = readSnapshot(key, raw.get());
          if (cached.isPresent()) {
            return cached;
          }
        }
      }
    } catch (RuntimeException e) {
      log.warn("Failed reading asset metadata cache for repo {} path {}, falling back to MySQL",
          repositoryId, storagePath, e);
      return loader.get().map(loaded -> CachedAssetMetadata.of(loaded.asset(), loaded.blob()));
    }

    Optional<Loaded> loaded = loader.get();
    if (loaded.isEmpty()) {
      writeMissing(key);
      return Optional.empty();
    }
    CachedAssetMetadata snapshot = CachedAssetMetadata.of(loaded.get().asset(), loaded.get().blob());
    writeSnapshot(key, snapshot);
    return Optional.of(snapshot);
  }

  /**
   * Refresh the cached {@code lastUpdatedAt} without re-reading the database. Used by proxy
   * services after a 304 keepalive so other replicas observe the freshness bump.
   */
  public void touchVerified(long repositoryId, String storagePath, java.time.Instant verifiedAt) {
    touchVerified(repositoryId, storagePath, verifiedAt, null);
  }

  public void touchVerified(
      long repositoryId,
      String storagePath,
      java.time.Instant verifiedAt,
      Map<String, Object> attributes) {
    if (!enabled || verifiedAt == null || positiveTtl.isZero()) {
      return;
    }
    String key = key(repositoryId, storagePath);
    try {
      Optional<CachedAssetMetadata> existing = sharedCache.getJson(NAMESPACE, key, CachedAssetMetadata.class);
      existing.ifPresent(snapshot -> writeSnapshot(key, attributes == null
          ? snapshot.withLastUpdatedAt(verifiedAt)
          : snapshot.withLastUpdatedAtAndAttributes(verifiedAt, attributes)));
    } catch (RuntimeException e) {
      log.warn("Failed refreshing asset metadata cache for repo {} path {}",
          repositoryId, storagePath, e);
    }
  }

  /** Evict immediately. Prefer {@link #evictAfterCommit} from inside a transaction. */
  public void evict(long repositoryId, String storagePath) {
    if (!enabled) {
      return;
    }
    try {
      sharedCache.evict(NAMESPACE, key(repositoryId, storagePath));
    } catch (RuntimeException e) {
      log.warn("Failed evicting asset metadata cache for repo {} path {}",
          repositoryId, storagePath, e);
    }
  }

  /**
   * Evict the entry after the current transaction commits. If there is no active transaction the
   * evict runs immediately. Repeated calls within the same transaction for the same key collapse
   * into a single eviction.
   */
  public void evictAfterCommit(long repositoryId, String storagePath) {
    if (!enabled || storagePath == null) {
      return;
    }
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      evict(repositoryId, storagePath);
      return;
    }
    @SuppressWarnings("unchecked")
    java.util.Set<String> pending =
        (java.util.Set<String>) TransactionSynchronizationManager.getResource(SYNC_RESOURCE_KEY);
    if (pending == null) {
      pending = new java.util.LinkedHashSet<>();
      TransactionSynchronizationManager.bindResource(SYNC_RESOURCE_KEY, pending);
      java.util.Set<String> snapshot = pending;
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          for (String key : snapshot) {
            int slash = key.indexOf(':');
            if (slash <= 0) continue;
            long repoId;
            try {
              repoId = Long.parseLong(key.substring(0, slash));
            } catch (NumberFormatException ignored) {
              continue;
            }
            String path = key.substring(slash + 1);
            evict(repoId, path);
          }
        }

        @Override
        public void afterCompletion(int status) {
          TransactionSynchronizationManager.unbindResourceIfPossible(SYNC_RESOURCE_KEY);
        }
      });
    }
    pending.add(repositoryId + ":" + storagePath);
  }

  /** Bulk-evict every entry under a repository. Use on repository delete / format wipe. */
  public void evictRepository(long repositoryId) {
    if (!enabled) {
      return;
    }
    try {
      sharedCache.evictByPrefix(NAMESPACE, repositoryId + ":");
    } catch (RuntimeException e) {
      log.warn("Failed bulk-evicting asset metadata cache for repo {}", repositoryId, e);
    }
  }

  private Optional<CachedAssetMetadata> readSnapshot(String key, String raw) {
    try {
      return Optional.of(objectMapper.readValue(raw, CachedAssetMetadata.class));
    } catch (JsonProcessingException e) {
      try {
        sharedCache.evict(NAMESPACE, key);
      } catch (RuntimeException ignored) {
        // If eviction also fails, fall through to the DB loader; correctness is still preserved.
      }
      log.warn("Failed deserializing asset metadata cache entry {}, falling back to MySQL", key, e);
      return Optional.empty();
    }
  }

  private void writeSnapshot(String key, CachedAssetMetadata snapshot) {
    if (positiveTtl.isZero()) {
      return;
    }
    try {
      sharedCache.putJson(NAMESPACE, key, snapshot, positiveTtl);
    } catch (RuntimeException e) {
      log.warn("Failed writing asset metadata cache", e);
    }
  }

  private void writeMissing(String key) {
    if (missingTtl.isZero()) {
      return;
    }
    try {
      sharedCache.putString(NAMESPACE, key, MISSING_MARKER, missingTtl);
    } catch (RuntimeException e) {
      log.warn("Failed writing asset metadata missing-marker", e);
    }
  }

  private static String key(long repositoryId, String storagePath) {
    return repositoryId + ":" + storagePath;
  }

  /** Loader payload for {@link #find}: a database-resident asset and its blob. */
  public record Loaded(AssetRecord asset, AssetBlobRecord blob) {
    public static Optional<Loaded> from(Optional<AssetRecord> asset, AssetDao dao) {
      if (asset.isEmpty()) return Optional.empty();
      AssetRecord a = asset.get();
      if (a.assetBlobId() == null) {
        return Optional.of(new Loaded(a, null));
      }
      Optional<AssetBlobRecord> blob = dao.findBlobById(a.assetBlobId());
      return blob.map(b -> new Loaded(a, b)).or(() -> Optional.of(new Loaded(a, null)));
    }
  }
}
