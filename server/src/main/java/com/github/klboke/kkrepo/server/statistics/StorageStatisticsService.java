package com.github.klboke.kkrepo.server.statistics;

import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import com.github.klboke.kkrepo.persistence.jdbc.api.StorageStatisticsDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.StorageStatisticsDao.BlobStoreUsage;
import com.github.klboke.kkrepo.persistence.jdbc.api.StorageStatisticsDao.RepositoryUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Rebuildable per-node inventory snapshots, never used for quota or authorization decisions.
 * Database commits on any replica become visible on the next load after the 30-second TTL.
 * Atomic cache loaders coalesce concurrent requests on each node. Failed loads are not cached.
 * There are no write-path counters, object-store scans, or per-repository aggregate queries.
 */
@Service
public class StorageStatisticsService {
  public static final long MAX_AGE_SECONDS = 30;
  private final StorageStatisticsDao statistics;
  private final LocalCache<String, UsageSnapshot<BlobStoreUsage>> blobs;
  private final LocalCache<String, UsageSnapshot<RepositoryUsage>> assets;

  @Autowired
  public StorageStatisticsService(StorageStatisticsDao statistics) {
    this(statistics, Duration.ofSeconds(MAX_AGE_SECONDS));
  }

  StorageStatisticsService(StorageStatisticsDao statistics, Duration ttl) {
    this.statistics = statistics;
    this.blobs = cache("admin-blob-store-usage", ttl);
    this.assets = cache("admin-repository-usage", ttl);
  }

  private static <T> LocalCache<String, UsageSnapshot<T>> cache(String name, Duration ttl) {
    return LocalCacheFactory.standard().<String, UsageSnapshot<T>>builder(name)
        .maximumSize(1).expireAfterWrite(ttl).build();
  }

  public UsageSnapshot<BlobStoreUsage> blobs(Collection<Long> visibleStoreIds) {
    return select(blobs, statistics::blobStoreUsage, visibleStoreIds, BlobStoreUsage.EMPTY);
  }

  public UsageSnapshot<RepositoryUsage> assets(Collection<Long> visibleRepositoryIds) {
    return select(assets, statistics::repositoryUsage, visibleRepositoryIds, RepositoryUsage.EMPTY);
  }

  private <T> UsageSnapshot<T> select(LocalCache<String, UsageSnapshot<T>> cache,
      Supplier<Map<Long, T>> loader, Collection<Long> visibleIds, T empty) {
    if (visibleIds.isEmpty()) return new UsageSnapshot<>(Map.of(), Instant.now(), MAX_AGE_SECONDS);
    UsageSnapshot<T> snapshot = cache.get("all", key -> {
      Instant startedAt = Instant.now();
      return new UsageSnapshot<>(Map.copyOf(loader.get()), startedAt, MAX_AGE_SECONDS);
    });
    // Authorization/catalog membership are evaluated by the caller on every request.
    // Never expose cached usage for hidden/deleted entities, even during the cache TTL.
    Map<Long, T> selected = new LinkedHashMap<>();
    for (Long id : visibleIds) selected.put(id, snapshot.usage().getOrDefault(id, empty));
    return new UsageSnapshot<>(Map.copyOf(selected), snapshot.calculatedAt(), MAX_AGE_SECONDS);
  }

  public record UsageSnapshot<T>(Map<Long, T> usage, Instant calculatedAt, long maxAgeSeconds) {}
}
