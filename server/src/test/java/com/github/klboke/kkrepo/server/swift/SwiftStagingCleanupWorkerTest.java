package com.github.klboke.kkrepo.server.swift;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.metrics.KkRepoMetrics;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class SwiftStagingCleanupWorkerTest {

  @Test
  void staleStagingAssetIsUnlinkedAndItsBlobIsHandedToGlobalGc() {
    RepositoryDao repositories = mock(RepositoryDao.class);
    AssetDao assets = mock(AssetDao.class);
    BrowseNodeDao browse = mock(BrowseNodeDao.class);
    AssetMetadataCache cache = mock(AssetMetadataCache.class);
    KkRepoMetrics metrics = mock(KkRepoMetrics.class);
    AssetRecord staged = stagedAsset(101L, 501L);
    when(repositories.list()).thenReturn(List.of(repository(10L, RepositoryFormat.SWIFT, false)));
    when(assets.claimStaleAssetsByPrefix(
        eq(10L), eq(SwiftStagingCleanupWorker.STAGING_PREFIX), any(Instant.class), eq(8)))
        .thenReturn(List.of(staged));
    when(assets.deleteAssetById(101L)).thenReturn(1);

    worker(repositories, assets, browse, cache, metrics, true).cleanup();

    verify(browse).deleteByAssetId(101L);
    verify(assets).deleteAssetById(101L);
    verify(assets).markBlobDeletedIfUnreferenced(
        501L, "abandoned Swift publication staging asset");
    verify(cache).evictAfterCommit(10L, staged.path());
    verify(metrics).incrementWorkerItems("swift_cleanup", "staging_asset", "deleted", 1);
  }

  @Test
  void cleanupScansOfflineSwiftRepositoriesButSkipsOtherFormats() {
    RepositoryDao repositories = mock(RepositoryDao.class);
    AssetDao assets = mock(AssetDao.class);
    when(repositories.list()).thenReturn(List.of(
        repository(10L, RepositoryFormat.SWIFT, false),
        repository(11L, RepositoryFormat.MAVEN2, true)));
    when(assets.claimStaleAssetsByPrefix(
        eq(10L), eq(SwiftStagingCleanupWorker.STAGING_PREFIX), any(Instant.class), anyInt()))
        .thenReturn(List.of());

    worker(repositories, assets, mock(BrowseNodeDao.class), mock(AssetMetadataCache.class),
        mock(KkRepoMetrics.class), true).cleanup();

    verify(assets).claimStaleAssetsByPrefix(
        eq(10L), eq(SwiftStagingCleanupWorker.STAGING_PREFIX), any(Instant.class), eq(8));
    verify(assets, never()).claimStaleAssetsByPrefix(
        eq(11L), any(), any(Instant.class), anyInt());
  }

  @Test
  void disabledCleanupDoesNotReadSharedState() {
    RepositoryDao repositories = mock(RepositoryDao.class);

    worker(repositories, mock(AssetDao.class), mock(BrowseNodeDao.class),
        mock(AssetMetadataCache.class), mock(KkRepoMetrics.class), false).cleanup();

    verify(repositories, never()).list();
  }

  @Test
  void transientClaimFailureIsRetriedByAnotherReplicaOrCycle() {
    RepositoryDao repositories = mock(RepositoryDao.class);
    AssetDao assets = mock(AssetDao.class);
    when(repositories.list()).thenReturn(List.of(repository(10L, RepositoryFormat.SWIFT, true)));
    doThrow(new IllegalStateException("database unavailable"))
        .when(assets).claimStaleAssetsByPrefix(
            eq(10L), eq(SwiftStagingCleanupWorker.STAGING_PREFIX), any(Instant.class), eq(8));

    worker(repositories, assets, mock(BrowseNodeDao.class), mock(AssetMetadataCache.class),
        mock(KkRepoMetrics.class), true).cleanup();

    verify(assets, never()).deleteAssetById(anyLong());
  }

  private static SwiftStagingCleanupWorker worker(
      RepositoryDao repositories,
      AssetDao assets,
      BrowseNodeDao browse,
      AssetMetadataCache cache,
      KkRepoMetrics metrics,
      boolean enabled) {
    return new SwiftStagingCleanupWorker(
        repositories,
        assets,
        browse,
        cache,
        new RecordingTransactionManager(),
        metrics,
        enabled,
        8,
        3600);
  }

  private static RepositoryRecord repository(
      long id, RepositoryFormat format, boolean online) {
    return new RepositoryRecord(
        id,
        "repo-" + id,
        format,
        RepositoryType.HOSTED,
        "swift-hosted",
        online,
        1L,
        null,
        null,
        null,
        null,
        "ALLOW_ONCE",
        true,
        Map.of());
  }

  private static AssetRecord stagedAsset(long assetId, long blobId) {
    String path = ".swift/staging/00000000-0000-0000-0000-000000000001/source.zip";
    return new AssetRecord(
        assetId,
        10L,
        null,
        blobId,
        RepositoryFormat.SWIFT,
        path,
        PersistenceHashes.pathHash(path),
        "source.zip",
        "swift",
        "application/zip",
        42L,
        null,
        Instant.parse("2026-07-15T00:00:00Z"),
        Map.of("swiftLogicalPath", "acme/demo/1.0.0.zip"));
  }

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition)
        throws TransactionException {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
    }
  }
}
