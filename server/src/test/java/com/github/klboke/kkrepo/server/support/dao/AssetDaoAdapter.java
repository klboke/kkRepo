package com.github.klboke.kkrepo.server.support.dao;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.BlobReconcileWindow;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.HelmIndexRow;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.PypiProjectIndexRow;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/** Test-only base class for focused AssetDao fakes. */
public class AssetDaoAdapter implements AssetDao {
  public AssetDaoAdapter() {
  }

  public AssetDaoAdapter(Object ignored) {
  }

  public AssetDaoAdapter(Object firstIgnored, Object secondIgnored) {
  }

  @Override
  public List<AssetBlobRecord> claimDeletedBlobsForGc(int arg0, Instant arg1, Instant arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long countAssetsByRepositoryId(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long countDeletedBlobsAwaitingGc() {
    throw new UnsupportedOperationException();
  }

  @Override
  public long countUnreferencedLiveBlobs() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int deleteAssetById(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int deleteBlobById(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<AssetRecord> findAssetById(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<AssetRecord> findAssetByPath(long arg0, String arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<AssetRecord> findAssetByPathHash(long arg0, byte[] arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Map<Long, AssetRecord> findAssetsByPathHash(Collection<Long> arg0, byte[] arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<AssetBlobRecord> findBlobByBlobRefHash(long arg0, byte[] arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<AssetBlobRecord> findBlobById(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<AssetBlobRecord> findBlobByObjectKeyHash(long arg0, byte[] arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Map<Long, AssetBlobRecord> findBlobsByIds(Collection<Long> arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<AssetRecord> findDockerBlobAssetBySha256(long arg0, String arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<AssetBlobRecord> findReusableBlobBySha256(long arg0, String arg1, long arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int hardDeleteBlobById(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int hardDeleteBlobByIdIfDeleted(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean hasLiveBlobForObjectKeyHash(long arg0, byte[] arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long insertAsset(AssetRecord arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long insertBlob(AssetBlobRecord arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public AssetBlobRecord insertBlobOrFindExisting(AssetBlobRecord arg0) {
    return arg0.withId(insertBlob(arg0));
  }

  @Override
  public List<AssetRecord> listAssetsByComponent(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<AssetRecord> listAssetsByPrefix(long arg0, String arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<AssetRecord> claimStaleAssetsByPrefix(
      long arg0, String arg1, Instant arg2, int arg3) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<HelmIndexRow> listHelmIndexRows(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<PypiProjectIndexRow> listPypiProjectIndexRows(long arg0, String arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<AssetBlobRecord> lockDeletedBlobById(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<AssetBlobRecord> lockLiveBlobById(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int markBlobDeletedById(long arg0, String arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int markBlobDeletedIfUnreferenced(long arg0, String arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public BlobReconcileWindow markUnreferencedBlobsDeletedAfter(long arg0, int arg1, int arg2, String arg3) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<AssetBlobRecord> recoverDeletedBlobBySha256(long arg0, String arg1, long arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int releaseBlobGcClaim(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int touchAssetLastUpdated(long arg0, Instant arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int touchAssetLastUpdatedAndAttributes(long arg0, Instant arg1, Map<String, Object> arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int touchLastDownloaded(long arg0, Instant arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public OptionalLong tryInsertAsset(AssetRecord arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int updateAssetAttributes(long arg0, Map<String, Object> arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int updateAssetBlobBinding(long arg0, long arg1, String arg2, long arg3, Instant arg4) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int updateAssetBlobBindingAndMetadata(long arg0, Long arg1, long arg2, String arg3, String arg4, long arg5, Instant arg6, Map<String, Object> arg7) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int updateAssetComponentBinding(long arg0, Long arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int updateBlobAttributes(long arg0, Map<String, Object> arg1) {
    throw new UnsupportedOperationException();
  }
}
