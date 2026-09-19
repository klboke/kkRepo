package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.util.Map;

/** Database inventory counts; callers should cache these potentially large index scans. */
public interface StorageStatisticsDao {
  /** Counts registered objects, including soft-deleted blobs until GC removes their rows. */
  Map<Long, BlobStoreUsage> blobStoreUsage();

  /** Counts a repository's own asset rows, including metadata and group cache entries. */
  Map<Long, RepositoryUsage> repositoryUsage();

  record BlobStoreUsage(long blobCount, long totalBytes, long pendingDeletionCount, long pendingDeletionBytes) {
    public static final BlobStoreUsage EMPTY = new BlobStoreUsage(0, 0, 0, 0);
  }

  /** Asset sizes are logical: shared blobs may contribute to multiple assets/repositories. */
  record RepositoryUsage(long assetCount, long totalBytes, long unknownSizeCount) {
    public static final RepositoryUsage EMPTY = new RepositoryUsage(0, 0, 0);
  }
}
