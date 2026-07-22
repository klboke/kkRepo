package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Shared Galaxy v3 state used by hosted, proxy, group, and importer replicas. */
public interface AnsibleGalaxyRegistryDao {
  String VERSION_READY = "READY";
  String TASK_WAITING = "WAITING";
  String TASK_RUNNING = "RUNNING";
  String TASK_COMPLETED = "COMPLETED";
  String TASK_FAILED = "FAILED";

  long nextRepositoryRevision(long repositoryId);

  long currentRepositoryRevision(long repositoryId);

  Map<Long, Long> currentRepositoryRevisions(Collection<Long> repositoryIds);

  long nextGroupConfigRevision(long repositoryId);

  long currentGroupConfigRevision(long repositoryId);

  Map<Long, Long> currentGroupConfigRevisions(Collection<Long> repositoryIds);

  /** Advances content state and invalidates only this coordinate in containing groups. */
  long nextCoordinateRevision(
      long repositoryId, String namespaceLc, String nameLc, String versionNormalized);

  Optional<String> currentCoordinateSha256(
      long repositoryId, String namespaceLc, String nameLc, String versionNormalized);

  /** Inserts a version and advances its coordinate revision atomically. */
  CollectionVersion insertVersion(CollectionVersion version);

  Optional<CollectionVersion> findVersion(
      long repositoryId, String namespaceLc, String nameLc, String versionNormalized);

  Optional<CollectionVersion> findVersionById(long versionId);

  Optional<CollectionVersion> findVersionByArtifactFilename(
      long repositoryId, String artifactFilename);

  Optional<ArtifactRef> findArtifactByVersionId(long versionId);

  Optional<ArtifactRef> findArtifactByFilename(long repositoryId, String artifactFilename);

  /**
   * Removes one collection version before its archive asset is unlinked.
   *
   * <p>The implementation also clears coordinate-scoped proxy state, invalidates containing
   * group bindings, and advances shared repository revisions. The repository, version, and asset
   * identifiers must all describe the same ready version.
   */
  boolean deleteVersion(long repositoryId, long versionId, long artifactAssetId);

  List<CollectionVersion> listVersions(
      long repositoryId, String namespaceLc, String nameLc);

  List<String> listVersionNames(
      long repositoryId, String namespaceLc, String nameLc);

  Signature insertSignature(Signature signature);

  List<Signature> listSignatures(long collectionVersionId);

  ImportTask createTask(ImportTask task);

  /** Persists a new task already owned by the request replica, atomically hiding it from recovery. */
  ImportTask createClaimedTask(
      ImportTask task, String owner, Instant leaseExpiresAt, Instant now);

  Optional<ImportTask> findTask(String taskId);

  List<ImportTask> listClaimableTasks(Instant now, int limit);

  /** Atomically claims a disjoint task batch with row locking and skip-locked semantics. */
  List<ImportTask> claimTasks(
      String owner, Instant leaseExpiresAt, Instant now, int limit);

  Optional<ImportTask> claimTask(
      String taskId, String owner, Instant leaseExpiresAt, Instant now);

  boolean renewTaskLease(
      String taskId, String owner, long fencingToken, Instant leaseExpiresAt);

  boolean finishTask(
      String taskId,
      String owner,
      long fencingToken,
      String state,
      List<Map<String, Object>> messages,
      String errorCode,
      String errorDetail,
      String namespaceLc,
      String nameLc,
      String versionNormalized,
      String artifactFilename,
      String actualSha256,
      Instant finishedAt);

  /** Persists changed proxy state and advances its content revision in one transaction. */
  long upsertProxyState(ProxyVersionState state);

  boolean touchProxyState(
      long repositoryId,
      String namespaceLc,
      String nameLc,
      String versionNormalized,
      String metadataEtag,
      String metadataLastModified,
      Instant cacheUntil,
      Instant verifiedAt);

  Optional<ProxyVersionState> findProxyState(
      long repositoryId, String namespaceLc, String nameLc, String versionNormalized);

  Optional<ProxyVersionState> findProxyStateByArtifactFilename(
      long repositoryId, String artifactFilename);

  Optional<ProxyInventory> findProxyInventory(
      long repositoryId, String namespaceLc, String nameLc);

  Map<Long, Instant> currentProxyInventoryCacheUntil(
      Collection<Long> repositoryIds, String namespaceLc, String nameLc);

  List<String> listProxyInventoryVersionNames(
      long repositoryId, String namespaceLc, String nameLc);

  /** Replaces a normalized inventory and advances its repository revision atomically. */
  long replaceProxyInventory(ProxyInventory inventory, List<String> versions);

  boolean touchProxyInventory(
      long repositoryId,
      String namespaceLc,
      String nameLc,
      Instant cacheUntil,
      Instant checkedAt);

  Optional<GroupBinding> findGroupBinding(
      long groupRepositoryId, String namespaceLc, String nameLc, String versionNormalized);

  Optional<GroupBinding> findGroupBindingByArtifactFilename(
      long groupRepositoryId, String artifactFilename);

  boolean bindGroupSourceIfCurrent(GroupBinding binding);

  void deleteGroupBindings(long groupRepositoryId);

  Optional<Lease> tryAcquireLease(String leaseKey, String owner, Instant expiresAt);

  boolean renewLease(String leaseKey, String owner, long fencingToken, Instant expiresAt);

  void releaseLease(String leaseKey, String owner, long fencingToken);

  int deleteExpiredProxyCache(Instant now, Instant stalePageBefore, int limit);

  int deleteTerminalTasksBefore(Instant finishedBefore, int limit);

  int deleteExpiredLeasesBefore(Instant expiresBefore, int limit);

  void deleteRepositoryState(long repositoryId);

  record CollectionVersion(
      Long id,
      long repositoryId,
      long componentId,
      long artifactAssetId,
      String namespaceLc,
      String namespaceDisplay,
      String nameLc,
      String nameDisplay,
      String versionOriginal,
      String versionNormalized,
      String artifactFilename,
      String artifactSha256,
      long artifactSize,
      Map<String, Object> metadata,
      Map<String, Object> dependencies,
      String requiresAnsible,
      String sourceKind,
      long revision,
      String state,
      Instant publishedAt,
      Instant createdAt,
      Instant updatedAt) {
  }

  record ArtifactRef(
      long versionId,
      long repositoryId,
      long artifactAssetId,
      String namespaceLc,
      String nameLc,
      String versionNormalized,
      String artifactFilename,
      String artifactSha256,
      long revision) {
  }

  record Signature(
      Long id,
      long collectionVersionId,
      Long signatureAssetId,
      String sha256,
      String keyFingerprint,
      String sourceKind,
      Instant createdAt) {
  }

  record ImportTask(
      String taskId,
      long repositoryId,
      String requester,
      String state,
      List<Map<String, Object>> messages,
      String errorCode,
      String errorDetail,
      String namespaceLc,
      String nameLc,
      String versionNormalized,
      String artifactFilename,
      String expectedSha256,
      String actualSha256,
      Long stagingAssetId,
      int attemptCount,
      String leaseOwner,
      Instant leaseExpiresAt,
      long fencingToken,
      Instant createdAt,
      Instant startedAt,
      Instant finishedAt,
      Instant updatedAt) {
  }

  record ProxyVersionState(
      long repositoryId,
      String namespaceLc,
      String nameLc,
      String versionNormalized,
      String artifactFilename,
      String upstreamHref,
      String upstreamDownloadUrl,
      String artifactSha256,
      String metadataEtag,
      String metadataLastModified,
      Instant cacheUntil,
      Instant verifiedAt,
      Integer negativeStatus,
      Instant negativeExpiresAt,
      Map<String, Object> upstreamIdentity,
      long revision,
      Instant updatedAt) {
  }

  record ProxyInventory(
      long repositoryId,
      String namespaceLc,
      String nameLc,
      Instant cacheUntil,
      Instant checkedAt,
      long revision,
      int versionCount,
      Instant updatedAt) {
  }

  record GroupBinding(
      long groupRepositoryId,
      String namespaceLc,
      String nameLc,
      String versionNormalized,
      long memberRepositoryId,
      Long memberVersionId,
      String artifactFilename,
      long memberRevision,
      long groupConfigRevision,
      String artifactSha256,
      Instant boundAt,
      Instant updatedAt) {
  }

  record Lease(
      String leaseKey,
      String owner,
      long fencingToken,
      Instant expiresAt,
      Instant updatedAt) {
  }
}
