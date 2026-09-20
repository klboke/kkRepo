package com.github.klboke.kkrepo.server.statistics;

import com.github.klboke.kkrepo.persistence.jdbc.api.StorageStatisticsDao;
import com.github.klboke.kkrepo.server.statistics.StorageStatisticsService.UsageSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** JSON boundary: decimal strings preserve inventory integers beyond JavaScript's safe range. */
public final class StorageUsageResponse {
  private StorageUsageResponse() {}

  public static UsageSnapshot<BlobStoreUsage> blobs(
      UsageSnapshot<StorageStatisticsDao.BlobStoreUsage> snapshot) {
    return map(snapshot, usage -> new BlobStoreUsage(
        usage.blobCount().toString(), usage.totalBytes().toString(),
        usage.pendingDeletionCount().toString(), usage.pendingDeletionBytes().toString()));
  }

  public static UsageSnapshot<RepositoryUsage> assets(
      UsageSnapshot<StorageStatisticsDao.RepositoryUsage> snapshot) {
    return map(snapshot, usage -> new RepositoryUsage(
        usage.assetCount().toString(), usage.totalBytes().toString(), usage.unknownSizeCount().toString()));
  }

  private static <S, T> UsageSnapshot<T> map(UsageSnapshot<S> snapshot, Function<S, T> convert) {
    Map<Long, T> usage = new LinkedHashMap<>();
    snapshot.usage().forEach((id, value) -> usage.put(id, convert.apply(value)));
    return new UsageSnapshot<>(Map.copyOf(usage), snapshot.calculatedAt(), snapshot.maxAgeSeconds());
  }

  public record BlobStoreUsage(
      String blobCount, String totalBytes, String pendingDeletionCount, String pendingDeletionBytes) {}

  public record RepositoryUsage(String assetCount, String totalBytes, String unknownSizeCount) {}
}
