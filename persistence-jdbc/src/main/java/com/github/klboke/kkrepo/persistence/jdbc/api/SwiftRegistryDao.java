package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Shared Swift Package Registry state.
 *
 * <p>Archive, manifest, and signature bytes remain in blob storage. This contract only persists
 * immutable identities, asset references, cache coordination, and cross-replica routing state.
 */
public interface SwiftRegistryDao {
  String RELEASE_READY = "READY";
  String RELEASE_TOMBSTONED = "TOMBSTONED";

  long nextRepositoryRevision(long repositoryId);

  long currentRepositoryRevision(long repositoryId);

  Release insertRelease(
      Release release, List<Manifest> manifests, List<RepositoryUrl> repositoryUrls);

  Optional<Release> findRelease(
      long repositoryId, String scopeLc, String nameLc, String version);

  Optional<Release> findReleaseById(long releaseId);

  List<Release> listReleases(long repositoryId, String scopeLc, String nameLc);

  List<Manifest> listManifests(long releaseId);

  Optional<Manifest> findManifest(long releaseId, String toolsVersion);

  List<RepositoryUrl> listRepositoryUrls(long releaseId);

  List<PackageIdentity> findIdentities(long repositoryId, String normalizedUrl);

  /**
   * Fixes a proxy version to the first observed tag/commit. Re-observations never replace that
   * immutable binding and return the already stored source to let callers detect moved tags.
   */
  ProxySource bindProxySource(ProxySource candidate);

  Optional<ProxySource> findProxySource(
      long repositoryId, String scopeLc, String nameLc, String version);

  List<ProxySource> listProxySources(long repositoryId, String scopeLc, String nameLc);

  boolean completeProxySource(
      long repositoryId,
      String scopeLc,
      String nameLc,
      String version,
      String expectedCommitSha,
      String archiveSha256,
      String cacheState,
      Long releaseId,
      Instant verifiedAt,
      long revision,
      String leaseKey,
      String leaseOwner,
      long fencingToken);

  Optional<GroupSourceBinding> findGroupSourceBinding(
      long groupRepositoryId, String scopeLc, String nameLc, String version);

  /**
   * Records the winning member only while {@link #currentRepositoryRevision(long)} still equals
   * the binding's configuration revision. The comparison and write are atomic with respect to
   * repository revision bumps, so a request using a stale member snapshot cannot recreate a
   * binding after the group configuration changes.
   *
   * <p>If another replica already recorded a binding for the same configuration revision, that
   * first binding remains canonical and this method only records another observation. A
   * {@code true} result therefore does not mean the candidate became the winner; callers must
   * re-read {@link #findGroupSourceBinding(long, String, String, String)} before serving it.
   *
   * @return {@code true} when a canonical binding was observed or recorded for the current
   *     configuration; {@code false} when the request was fenced by a newer configuration revision
   */
  boolean upsertGroupSourceBindingIfCurrent(GroupSourceBinding binding);

  void deleteGroupSourceBindings(long groupRepositoryId);

  Optional<Lease> tryAcquireLease(String leaseKey, String owner, Instant expiresAt);

  boolean renewLease(
      String leaseKey, String owner, long fencingToken, Instant expiresAt);

  void releaseLease(String leaseKey, String owner, long fencingToken);

  void tombstoneRelease(Tombstone tombstone);

  /**
   * Atomically creates the permanent coordinate tombstone and removes the release-side rows that
   * reference its assets. Callers can then unlink every returned asset in the same outer
   * transaction without violating archive/manifest foreign keys or leaving a half-visible release.
   */
  Optional<DeletedRelease> tombstoneAndDeleteReleaseState(
      long repositoryId,
      String scopeLc,
      String nameLc,
      String version,
      String reason,
      Instant deletedAt);

  Optional<Tombstone> findTombstone(
      long repositoryId, String scopeLc, String nameLc, String version);

  List<Tombstone> listTombstones(long repositoryId, String scopeLc, String nameLc);

  void putNegativeCache(NegativeCache entry);

  Optional<NegativeCache> findNegativeCache(long repositoryId, String cacheKey);

  int deleteExpiredNegativeCache(Instant expiresBefore);

  record Release(
      Long id,
      long repositoryId,
      long componentId,
      String scopeLc,
      String scopeDisplay,
      String nameLc,
      String nameDisplay,
      String version,
      Instant publishedAt,
      String metadataJson,
      String archiveSha256,
      long archiveAssetId,
      String signatureFormat,
      Long sourceSignatureAssetId,
      Long metadataSignatureAssetId,
      String sourceKind,
      long revision,
      String status,
      Instant createdAt,
      Instant updatedAt) {}

  record Manifest(
      Long releaseId, String filename, String toolsVersion, long assetId, String sha256) {}

  record RepositoryUrl(
      Long id,
      Long releaseId,
      long repositoryId,
      String scopeLc,
      String nameLc,
      String normalizedUrl,
      String displayUrl) {}

  record PackageIdentity(
      String scopeLc, String scopeDisplay, String nameLc, String nameDisplay) {}

  record ProxySource(
      long repositoryId,
      String scopeLc,
      String nameLc,
      String version,
      String upstreamRepositoryUrl,
      String upstreamTag,
      String commitSha,
      String generationProfile,
      String archiveSha256,
      String cacheState,
      Long releaseId,
      Instant verifiedAt,
      long revision,
      long observedCount,
      Instant lastCheckedAt) {}

  record GroupSourceBinding(
      long groupRepositoryId,
      String scopeLc,
      String nameLc,
      String version,
      long memberRepositoryId,
      long memberReleaseId,
      long memberRevision,
      long groupConfigRevision,
      Instant boundAt) {}

  record Lease(
      String leaseKey,
      String owner,
      long fencingToken,
      Instant expiresAt,
      Instant updatedAt) {}

  record Tombstone(
      long repositoryId,
      String scopeLc,
      String nameLc,
      String version,
      String reason,
      long revision,
      Instant deletedAt) {}

  record DeletedRelease(long componentId, List<Long> assetIds, long revision) {
    public DeletedRelease {
      assetIds = assetIds == null ? List.of() : List.copyOf(assetIds);
    }
  }

  record NegativeCache(
      long repositoryId,
      String cacheKey,
      int statusCode,
      Instant retryAfter,
      Instant expiresAt,
      Instant updatedAt) {}
}
