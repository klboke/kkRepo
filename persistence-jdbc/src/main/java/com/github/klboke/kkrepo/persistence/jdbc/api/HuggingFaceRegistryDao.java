package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable Hugging Face Models identities, projections, cache bindings, and fetch leases. */
public interface HuggingFaceRegistryDao {
  String FILE_DISCOVERED = "DISCOVERED";
  String FILE_FETCHING = "FETCHING";
  String FILE_READY = "READY";
  String FILE_FAILED = "FAILED";

  ModelRevision upsertRevision(ModelRevision revision);

  Optional<ModelRevision> findRevision(long repositoryId, String repoId, String commitHash);

  RevisionRef upsertRef(RevisionRef ref);

  Optional<RevisionRef> findRef(long repositoryId, String repoId, String requestedRef);

  ModelFile upsertFileMetadata(ModelFile file);

  Optional<ModelFile> findFile(
      long repositoryId, String repoId, String commitHash, String path);

  boolean markFileFetching(long fileId, long fencingToken, Instant updatedAt);

  boolean updateFetchingFileMetadata(
      long fileId,
      long fencingToken,
      String gitOid,
      String lfsSha256,
      String xetHash,
      Long expectedSize,
      String contentType,
      String fileKind,
      Instant updatedAt);

  boolean markFileReady(
      long fileId, long fencingToken, long assetId, long componentId,
      String internalSha256, String contentType, Instant updatedAt);

  boolean markFileFailed(
      long fileId, long fencingToken, String failureCode, Instant nextAttemptAt, Instant updatedAt);

  ApiCacheEntry upsertApiCache(ApiCacheEntry entry);

  Optional<ApiCacheEntry> findApiCache(
      long repositoryId, String route, String query, String requestFingerprint);

  void upsertRouteProjection(RouteProjection projection);

  Optional<RouteProjection> findRouteProjection(long repositoryId, String route);

  List<ModelFile> listRevisionFiles(long revisionId, long afterId, int limit);

  /**
   * Returns whether cleanup must retain a projected revision because a mutable ref still points at
   * it or one of its files is being filled by another replica.
   */
  boolean isRevisionProtected(long repositoryId, long componentId);

  Optional<FetchLease> tryAcquireLease(
      long repositoryId, String fetchKey, String owner, Instant expiresAt);

  boolean renewLease(
      long repositoryId, String fetchKey, String owner, long fencingToken, Instant expiresAt);

  void releaseLease(long repositoryId, String fetchKey, String owner, long fencingToken);

  int deleteExpiredLeases(Instant expiredBefore, int limit);

  void deleteRepositoryState(long repositoryId);

  record ModelRevision(
      Long id,
      long repositoryId,
      String repoId,
      String commitHash,
      Long componentId,
      Long rawMetadataAssetId,
      String author,
      Instant committedAt,
      boolean privateModel,
      boolean gated,
      String libraryName,
      String pipelineTag,
      String license,
      Instant observedAt,
      Instant updatedAt) {
  }

  record RevisionRef(
      long repositoryId,
      String repoId,
      String requestedRef,
      String commitHash,
      long generation,
      Instant expiresAt,
      Instant observedAt,
      Instant updatedAt) {
  }

  record ModelFile(
      Long id,
      long revisionId,
      long repositoryId,
      String repoId,
      String commitHash,
      String path,
      Long assetId,
      Long componentId,
      String gitOid,
      String lfsSha256,
      String xetHash,
      Long expectedSize,
      String internalSha256,
      String contentType,
      String fileKind,
      String state,
      long fencingToken,
      String failureCode,
      Instant nextAttemptAt,
      Instant updatedAt) {
  }

  record ApiCacheEntry(
      Long id,
      long repositoryId,
      String route,
      String query,
      String requestFingerprint,
      Long rawAssetId,
      Long derivedAssetId,
      String upstreamEtag,
      String derivedEtag,
      String nextLink,
      int transformVersion,
      Instant expiresAt,
      Instant updatedAt) {
  }

  record RouteProjection(
      long repositoryId,
      String route,
      long fileId,
      String requestedRef,
      long refGeneration,
      Instant updatedAt) {
  }

  record FetchLease(
      long repositoryId,
      String fetchKey,
      String owner,
      long fencingToken,
      Instant expiresAt,
      Instant updatedAt) {
  }
}
