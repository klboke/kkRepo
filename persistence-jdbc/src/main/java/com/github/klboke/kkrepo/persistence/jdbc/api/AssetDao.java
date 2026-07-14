package com.github.klboke.kkrepo.persistence.jdbc.api;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

public interface AssetDao {
  long insertBlob(AssetBlobRecord record);

  AssetBlobRecord insertBlobOrFindExisting(AssetBlobRecord record);

  Optional<AssetBlobRecord> findBlobByBlobRefHash(long blobStoreId, byte[] blobRefHash);

  Optional<AssetBlobRecord> findBlobByObjectKeyHash(long blobStoreId, byte[] objectKeyHash);

  Optional<AssetBlobRecord> findReusableBlobBySha256(long blobStoreId, String sha256, long size);

  Optional<AssetBlobRecord> recoverDeletedBlobBySha256(long blobStoreId, String sha256, long size);

  long insertAsset(AssetRecord record);

  /**
   * Attempts to insert an asset, returning empty when its natural path key already exists. The
   * current transaction remains usable for callers to load and reuse the winning row.
   */
  OptionalLong tryInsertAsset(AssetRecord record);

  Optional<AssetRecord> findAssetByPathHash(long repositoryId, byte[] pathHash);

  Optional<AssetRecord> findAssetByPath(long repositoryId, String path);

  Optional<AssetRecord> findAssetById(long assetId);

  Optional<AssetRecord> findDockerBlobAssetBySha256(long repositoryId, String sha256);

  Map<Long, AssetRecord> findAssetsByPathHash(Collection<Long> repositoryIds, byte[] pathHash);

  Optional<AssetBlobRecord> findBlobById(long assetBlobId);

  Map<Long, AssetBlobRecord> findBlobsByIds(Collection<Long> assetBlobIds);

  Optional<AssetBlobRecord> lockLiveBlobById(long assetBlobId);

  Optional<AssetBlobRecord> lockDeletedBlobById(long assetBlobId);

  List<AssetRecord> listAssetsByPrefix(long repositoryId, String pathPrefix);

  List<AssetRecord> listAssetsByComponent(long componentId);

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

  int touchLastDownloaded(long assetId, Instant when);

  int touchAssetLastUpdated(long assetId, Instant when);

  int touchAssetLastUpdatedAndAttributes(long assetId, Instant when, java.util.Map<String, Object> attributes);

  int updateAssetAttributes(long assetId, java.util.Map<String, Object> attributes);

  int updateBlobAttributes(long blobId, java.util.Map<String, Object> attributes);

  long countAssetsByRepositoryId(long repositoryId);

  List<HelmIndexRow> listHelmIndexRows(long repositoryId);

  List<PypiProjectIndexRow> listPypiProjectIndexRows(long repositoryId, String normalizedName);

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
