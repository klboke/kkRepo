package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable, rebuildable npm publish-time index tied to one package-root blob revision.
 *
 * <p>The raw packument blob remains the source of truth. This index exists so every replica can
 * enforce release age without downloading and parsing that blob on tarball requests.
 */
public interface NpmReleaseIndexDao {
  Optional<Status> findStatus(long packageRootAssetId, long sourceBlobId);

  Optional<Snapshot> findSnapshot(long packageRootAssetId, long sourceBlobId);

  /** Empty optional means the requested blob revision has not been indexed. */
  Optional<List<Release>> findByTarball(
      long packageRootAssetId, long sourceBlobId, String tarballName);

  /**
   * Loads everything needed to enforce one tarball request in a single database round trip.
   * Empty optional means the requested blob revision has not been indexed.
   */
  Optional<TarballPolicy> findTarballPolicy(
      long packageRootAssetId,
      long sourceBlobId,
      String tarballName,
      Instant publishedAfterExclusive,
      Instant publishedAtOrBefore);

  boolean hasMaturityBoundary(
      long packageRootAssetId,
      long sourceBlobId,
      Instant publishedAfterExclusive,
      Instant publishedAtOrBefore);

  Optional<Instant> findNextPublishedAfter(
      long packageRootAssetId, long sourceBlobId, Instant publishedAfterExclusive);

  /**
   * Atomically replaces the index only if the package-root asset still points at the expected
   * blob. The asset row is locked so concurrent metadata writers cannot publish mismatched state.
   */
  boolean replaceIfCurrent(
      long packageRootAssetId,
      long sourceBlobId,
      boolean completePublishTimes,
      List<Release> releases,
      Instant indexedAt);

  record Status(
      long packageRootAssetId,
      long sourceBlobId,
      boolean completePublishTimes,
      int releaseCount,
      Instant indexedAt) {
  }

  record Snapshot(Status status, List<Release> releases) {
    public Snapshot {
      releases = releases == null ? List.of() : List.copyOf(releases);
    }
  }

  record TarballPolicy(
      Status status,
      boolean maturityBoundaryCrossed,
      List<Release> releases) {
    public TarballPolicy {
      releases = releases == null ? List.of() : List.copyOf(releases);
    }
  }

  record Release(
      int ordinal,
      String version,
      Instant publishedAt,
      String invalidReason,
      String tarballName) {
  }
}
