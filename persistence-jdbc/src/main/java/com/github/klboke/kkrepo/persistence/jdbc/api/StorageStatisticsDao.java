package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.math.BigInteger;
import java.util.Map;

/** Database inventory counts; callers should cache these potentially large index scans. */
public interface StorageStatisticsDao {
  /** Counts registered objects, including soft-deleted blobs until GC removes their rows. */
  Map<Long, BlobStoreUsage> blobStoreUsage();

  /** Counts a repository's own asset rows, including metadata and group cache entries. */
  Map<Long, RepositoryUsage> repositoryUsage();

  // SUM(BIGINT) can exceed long even when every stored size fits in a long.
  record BlobStoreUsage(BigInteger blobCount, BigInteger totalBytes,
      BigInteger pendingDeletionCount, BigInteger pendingDeletionBytes) {
    public static final BlobStoreUsage EMPTY = new BlobStoreUsage(0, 0, 0, 0);

    public BlobStoreUsage(long blobCount, long totalBytes, long pendingDeletionCount, long pendingDeletionBytes) {
      this(BigInteger.valueOf(blobCount), BigInteger.valueOf(totalBytes),
          BigInteger.valueOf(pendingDeletionCount), BigInteger.valueOf(pendingDeletionBytes));
    }
  }

  /** Asset sizes are logical: shared blobs may contribute to multiple assets/repositories. */
  record RepositoryUsage(BigInteger assetCount, BigInteger totalBytes, BigInteger unknownSizeCount) {
    public static final RepositoryUsage EMPTY = new RepositoryUsage(0, 0, 0);

    public RepositoryUsage(long assetCount, long totalBytes, long unknownSizeCount) {
      this(BigInteger.valueOf(assetCount), BigInteger.valueOf(totalBytes), BigInteger.valueOf(unknownSizeCount));
    }
  }
}
