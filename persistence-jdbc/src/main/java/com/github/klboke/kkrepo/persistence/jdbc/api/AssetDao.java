package com.github.klboke.kkrepo.persistence.jdbc.api;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetPublicIdentifierRecord;
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

  /** Finds an identifier by the exact externally encoded repository/opaque key. */
  default Optional<AssetPublicIdentifierRecord> findPublicIdentifier(
      long repositoryId, String opaqueId) {
    return Optional.empty();
  }

  /**
   * Loads an identifier with current-read locking for registration conflict arbitration.
   * Implementations without transaction support may delegate to the ordinary lookup.
   */
  default Optional<AssetPublicIdentifierRecord> lockPublicIdentifier(
      long repositoryId, String opaqueId) {
    return findPublicIdentifier(repositoryId, opaqueId);
  }

  /** Finds the single kkRepo-native identifier assigned to an asset. */
  default Optional<AssetPublicIdentifierRecord> findNativePublicIdentifier(long assetId) {
    return Optional.empty();
  }

  /** Loads the native registration using current-read locking. */
  default Optional<AssetPublicIdentifierRecord> lockNativePublicIdentifier(long assetId) {
    return findNativePublicIdentifier(assetId);
  }

  /**
   * Attempts to register an identifier, returning false on either uniqueness conflict.
   * Implementations must leave the surrounding transaction usable after a conflict.
   */
  default boolean tryInsertPublicIdentifier(AssetPublicIdentifierRecord record) {
    throw new UnsupportedOperationException("Public asset identifiers are not supported");
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

  /** Loads a component's assets and live blob metadata in one database round trip when supported. */
  default List<AssetWithBlob> listAssetWithBlobByComponent(long componentId) {
    return listAssetsByComponent(componentId).stream()
        .map(asset -> new AssetWithBlob(
            asset,
            asset.assetBlobId() == null ? null : findBlobById(asset.assetBlobId()).orElse(null)))
        .toList();
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

  int updateBlobAttributes(long blobId, java.util.Map<String, Object> attributes);

  long countAssetsByRepositoryId(long repositoryId);

  List<HelmIndexRow> listHelmIndexRows(long repositoryId);

  List<String> listPypiProjectNames(long repositoryId);

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
