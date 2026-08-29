package com.github.klboke.kkrepo.persistence.jdbc.api;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

public interface AssetDao {
  long insertBlob(AssetBlobRecord record);

  AssetBlobRecord insertBlobOrFindExisting(AssetBlobRecord record);

  Optional<AssetBlobRecord> findBlobByBlobRefHash(long blobStoreId, byte[] blobRefHash);

  Optional<AssetBlobRecord> findBlobByObjectKeyHash(long blobStoreId, byte[] objectKeyHash);

  Optional<AssetBlobRecord> findReusableBlobBySha256(long blobStoreId, String sha256, long size);

  Optional<AssetBlobRecord> recoverDeletedBlobBySha256(long blobStoreId, String sha256, long size);

  /**
   * Restores one known soft-deleted blob row while holding its GC fence.
   *
   * <p>The caller must have just materialized the exact object identity represented by the row
   * and publish durable ownership in the same transaction.
   */
  Optional<AssetBlobRecord> restoreDeletedBlobById(long assetBlobId);

  long insertAsset(AssetRecord record);

  /**
   * Attempts to insert an asset, returning empty when its natural path key already exists. The
   * current transaction remains usable for callers to load and reuse the winning row.
   */
  OptionalLong tryInsertAsset(AssetRecord record);

  Optional<AssetRecord> findAssetByPathHash(long repositoryId, byte[] pathHash);

  Optional<AssetRecord> findAssetByPath(long repositoryId, String path);

  Optional<AssetRecord> findAssetById(long assetId);

  /** Locks one live asset generation for cleanup revalidation. */
  default Optional<AssetRecord> findAssetByIdForUpdate(long assetId) {
    return findAssetById(assetId);
  }

  /**
   * Loads an asset and its live blob metadata in one database round trip when supported.
   *
   * <p>The default keeps lightweight test adapters source-compatible.
   */
  default Optional<AssetWithBlob> findAssetWithBlobById(long assetId) {
    return findAssetById(assetId).map(asset -> new AssetWithBlob(
        asset,
        asset.assetBlobId() == null ? null : findBlobById(asset.assetBlobId()).orElse(null)));
  }

  /**
   * Returns a stable keyset page for management API asset enumeration.
   *
   * <p>Production JDBC implementations override this with a bounded join. The default keeps
   * focused test adapters source-compatible and must not be used for large repositories.
   */
  default List<AssetWithBlob> listAssetWithBlobPage(
      long repositoryId, long afterAssetId, int maxItems) {
    return listAssetsByPrefix(repositoryId, "").stream()
        .filter(asset -> asset.id() != null && asset.id() > afterAssetId)
        .sorted(java.util.Comparator.comparingLong(AssetRecord::id))
        .limit(Math.max(1, maxItems))
        .map(asset -> new AssetWithBlob(
            asset,
            asset.assetBlobId() == null ? null : findBlobById(asset.assetBlobId()).orElse(null)))
        .toList();
  }

  /** Returns a bounded keyset page of repository-owned assets without a component binding. */
  default List<AssetWithBlob> listUnboundAssetWithBlobPage(
      long repositoryId, long afterAssetId, int maxItems) {
    return listAssetWithBlobPage(repositoryId, afterAssetId, maxItems).stream()
        .filter(row -> row.asset().componentId() == null)
        .toList();
  }

  /**
   * Returns a stable keyset page of assets whose owning component has the requested name.
   *
   * <p>Asset management search uses component coordinates rather than storage paths. Production
   * implementations override this with a bounded join; the default is intentionally empty because
   * an asset row alone does not carry its component name.
   */
  default List<AssetWithBlob> listAssetWithBlobPageByComponentName(
      long repositoryId, String componentName, long afterAssetId, int maxItems) {
    return List.of();
  }

  Optional<AssetRecord> findDockerBlobAssetBySha256(long repositoryId, String sha256);

  /**
   * Returns existing paths in one backend-specific batch when available. The default keeps
   * lightweight test adapters source-compatible.
   */
  default Set<String> findExistingAssetPaths(long repositoryId, Collection<String> paths) {
    if (paths == null || paths.isEmpty()) {
      return Set.of();
    }
    Set<String> existing = new LinkedHashSet<>();
    for (String path : paths) {
      if (path != null && findAssetByPath(repositoryId, path).isPresent()) {
        existing.add(path);
      }
    }
    return existing;
  }

  Map<Long, AssetRecord> findAssetsByPathHash(Collection<Long> repositoryIds, byte[] pathHash);

  Optional<AssetBlobRecord> findBlobById(long assetBlobId);

  Map<Long, AssetBlobRecord> findBlobsByIds(Collection<Long> assetBlobIds);

  Optional<AssetBlobRecord> lockLiveBlobById(long assetBlobId);

  Optional<AssetBlobRecord> lockDeletedBlobById(long assetBlobId);

  List<AssetRecord> listAssetsByPrefix(long repositoryId, String pathPrefix);

  /**
   * Locks a bounded batch of stale assets below a repository path prefix for cleanup. Callers must
   * invoke this inside the transaction that deletes the returned rows; {@code SKIP LOCKED}
   * semantics let every replica run the same cleanup worker without duplicate ownership.
   */
  List<AssetRecord> claimStaleAssetsByPrefix(
      long repositoryId, String pathPrefix, Instant updatedBefore, int maxItems);

  List<AssetRecord> listAssetsByComponent(long componentId);

  /**
   * Loads assets for a bounded set of components in backend-sized batches.
   *
   * <p>The default preserves source compatibility for lightweight adapters. Production scanners
   * should use this method instead of issuing one query per component.
   */
  default List<AssetRecord> listAssetsByComponents(Collection<Long> componentIds) {
    if (componentIds == null || componentIds.isEmpty()) return List.of();
    return componentIds.stream()
        .filter(java.util.Objects::nonNull)
        .distinct()
        .flatMap(componentId -> listAssetsByComponent(componentId).stream())
        .toList();
  }

  /** Loads assets by primary key in backend-sized batches. */
  default Map<Long, AssetRecord> findAssetsByIds(Collection<Long> assetIds) {
    if (assetIds == null || assetIds.isEmpty()) return Map.of();
    Map<Long, AssetRecord> result = new java.util.LinkedHashMap<>();
    assetIds.stream()
        .filter(java.util.Objects::nonNull)
        .distinct()
        .forEach(assetId -> findAssetById(assetId).ifPresent(asset -> result.put(assetId, asset)));
    return Map.copyOf(result);
  }

  /** Loads exact repository paths in backend-sized batches, keyed by the canonical stored path. */
  default Map<String, AssetRecord> findAssetsByPaths(
      long repositoryId, Collection<String> paths) {
    if (paths == null || paths.isEmpty()) return Map.of();
    Map<String, AssetRecord> result = new java.util.LinkedHashMap<>();
    paths.stream()
        .filter(java.util.Objects::nonNull)
        .distinct()
        .forEach(path -> findAssetByPath(repositoryId, path)
            .ifPresent(asset -> result.put(asset.path(), asset)));
    return Map.copyOf(result);
  }

  /** Locks all current assets of a locked component in stable order. */
  default List<AssetRecord> listAssetsByComponentForUpdate(long componentId) {
    return listAssetsByComponent(componentId);
  }

  int deleteAssetById(long assetId);

  int deleteBlobById(long assetBlobId);

  int markBlobDeletedById(long assetBlobId, String reason);

  int markBlobDeletedIfUnreferenced(long assetBlobId, String reason);

  int hardDeleteBlobById(long assetBlobId);

  int hardDeleteBlobByIdIfDeleted(long assetBlobId);

  boolean hasLiveBlobForObjectKeyHash(long blobStoreId, byte[] objectKeyHash);

  List<AssetBlobRecord> claimDeletedBlobsForGc(int maxItems, Instant deletedBefore, Instant claimRetryBefore);

  int releaseBlobGcClaim(long assetBlobId);

  BlobReconcileWindow markUnreferencedBlobsDeletedAfter(
      long lastSeenId,
      int scanBatchSize,
      int markBatchSize,
      String reason);

  long countDeletedBlobsAwaitingGc();

  long countUnreferencedLiveBlobs();

  int updateAssetBlobBinding(long assetId, long assetBlobId, String contentType,
      long size, Instant lastUpdatedAt);

  int updateAssetBlobBindingAndMetadata(long assetId, Long componentId, long assetBlobId,
      String kind, String contentType, long size, Instant lastUpdatedAt,
      java.util.Map<String, Object> attributes);

  int updateAssetComponentBinding(long assetId, Long componentId);

  int touchLastDownloaded(long assetId, Instant when);

  int touchAssetLastUpdated(long assetId, Instant when);

  int touchAssetLastUpdatedAndAttributes(long assetId, Instant when, java.util.Map<String, Object> attributes);

  int updateAssetAttributes(long assetId, java.util.Map<String, Object> attributes);

  /** Atomically adds a string attribute only when the durable row does not contain the key. */
  int putAssetStringAttributeIfAbsent(long assetId, String attributeName, String value);

  int updateBlobAttributes(long blobId, java.util.Map<String, Object> attributes);

  long countAssetsByRepositoryId(long repositoryId);

  List<HelmIndexRow> listHelmIndexRows(long repositoryId);

  List<PypiProjectIndexRow> listPypiProjectIndexRows(long repositoryId, String normalizedName);

  record AssetWithBlob(AssetRecord asset, AssetBlobRecord blob) {}

  record HelmIndexRow(
      String path,
      Instant lastUpdatedAt,
      String sha256,
      java.util.Map<String, Object> attributes) {}

  record PypiProjectIndexRow(
      String path,
      String kind,
      String md5,
      java.util.Map<String, Object> attributes) {}

  record BlobReconcileWindow(
      int marked,
      int scanned,
      long nextLastSeenId,
      boolean wrapped) {}
}
