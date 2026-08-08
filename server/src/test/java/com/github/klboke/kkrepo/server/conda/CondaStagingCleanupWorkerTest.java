package com.github.klboke.kkrepo.server.conda;

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
import com.github.klboke.kkrepo.persistence.jdbc.api.CondaRegistryDao;
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

class CondaStagingCleanupWorkerTest {

  @Test
  void staleStagingAssetIsUnlinkedAndItsBlobIsHandedToGlobalGc() {
    RepositoryDao repositories = mock(RepositoryDao.class);
    AssetDao assets = mock(AssetDao.class);
    BrowseNodeDao browse = mock(BrowseNodeDao.class);
    AssetMetadataCache cache = mock(AssetMetadataCache.class);
    KkRepoMetrics metrics = mock(KkRepoMetrics.class);
    AssetRecord staged = stagedAsset(101L, 501L);
    when(repositories.list()).thenReturn(List.of(repository(
        10L, RepositoryFormat.CONDA, RepositoryType.HOSTED, false)));
    when(assets.claimStaleAssetsByPrefix(
        eq(10L), eq(CondaStagingCleanupWorker.STAGING_PREFIX), any(Instant.class), eq(8)))
        .thenReturn(List.of(staged));
    when(assets.deleteAssetById(101L)).thenReturn(1);

    worker(repositories, assets, browse, cache, metrics, true).cleanup();

    verify(browse).deleteByAssetId(101L);
    verify(assets).deleteAssetById(101L);
    verify(assets).markBlobDeletedIfUnreferenced(
        501L, "abandoned Conda publication staging asset");
    verify(cache).evictAfterCommit(10L, staged.path());
    verify(metrics).incrementWorkerItems("conda_cleanup", "staging_asset", "deleted", 1);
  }

  @Test
  void scansHostedStagingAndAllCondaGeneratedMetadataButSkipsOtherFormats() {
    RepositoryDao repositories = mock(RepositoryDao.class);
    AssetDao assets = mock(AssetDao.class);
    when(repositories.list()).thenReturn(List.of(
        repository(10L, RepositoryFormat.CONDA, RepositoryType.HOSTED, false),
        repository(11L, RepositoryFormat.CONDA, RepositoryType.PROXY, true),
        repository(12L, RepositoryFormat.MAVEN2, RepositoryType.HOSTED, true)));
    when(assets.claimStaleAssetsByPrefix(
        eq(10L), eq(CondaStagingCleanupWorker.STAGING_PREFIX), any(Instant.class), anyInt()))
        .thenReturn(List.of());

    worker(repositories, assets, mock(BrowseNodeDao.class), mock(AssetMetadataCache.class),
        mock(KkRepoMetrics.class), true).cleanup();

    verify(assets).claimStaleAssetsByPrefix(
        eq(10L), eq(CondaStagingCleanupWorker.STAGING_PREFIX), any(Instant.class), eq(8));
    verify(assets, never()).claimStaleAssetsByPrefix(
        eq(11L), eq(CondaStagingCleanupWorker.STAGING_PREFIX), any(Instant.class), anyInt());
    verify(assets).claimStaleAssetsByPrefix(
        eq(11L), eq(CondaStagingCleanupWorker.GENERATED_PREFIX), any(Instant.class), eq(8));
    verify(assets, never()).claimStaleAssetsByPrefix(
        eq(12L), any(), any(Instant.class), anyInt());
  }

  @Test
  void currentRevisionMetadataIsRetainedAndMovedPastTheCleanupCutoff() {
    RepositoryDao repositories = mock(RepositoryDao.class);
    AssetDao assets = mock(AssetDao.class);
    BrowseNodeDao browse = mock(BrowseNodeDao.class);
    AssetMetadataCache cache = mock(AssetMetadataCache.class);
    CondaRegistryDao registry = mock(CondaRegistryDao.class);
    KkRepoMetrics metrics = mock(KkRepoMetrics.class);
    AssetRecord generated = generatedAsset(102L, 502L, 7L);
    when(repositories.list()).thenReturn(List.of(repository(
        10L, RepositoryFormat.CONDA, RepositoryType.HOSTED, true)));
    when(assets.claimStaleAssetsByPrefix(
        eq(10L), eq(CondaStagingCleanupWorker.STAGING_PREFIX), any(Instant.class), eq(8)))
        .thenReturn(List.of());
    when(assets.claimStaleAssetsByPrefix(
        eq(10L), eq(CondaStagingCleanupWorker.GENERATED_PREFIX), any(Instant.class), eq(8)))
        .thenReturn(List.of(generated));
    when(registry.currentRepositoryRevision(10L)).thenReturn(7L);

    new CondaStagingCleanupWorker(
        repositories,
        assets,
        browse,
        cache,
        registry,
        new RecordingTransactionManager(),
        metrics,
        true,
        8,
        3600,
        3600,
        8).cleanup();

    verify(assets).touchAssetLastUpdated(eq(102L), any(Instant.class));
    verify(cache).evictAfterCommit(10L, generated.path());
    verify(assets, never()).deleteAssetById(102L);
    verify(browse, never()).deleteByAssetId(102L);
  }

  @Test
  void boundsEachBatchAndHandlesStringOrMalformedGeneratedRevisions() {
    RepositoryDao repositories = mock(RepositoryDao.class);
    AssetDao assets = mock(AssetDao.class);
    BrowseNodeDao browse = mock(BrowseNodeDao.class);
    AssetMetadataCache cache = mock(AssetMetadataCache.class);
    CondaRegistryDao registry = mock(CondaRegistryDao.class);
    KkRepoMetrics metrics = mock(KkRepoMetrics.class);
    when(repositories.list()).thenReturn(List.of(
        repository(10L, RepositoryFormat.CONDA, RepositoryType.HOSTED, true),
        repository(11L, RepositoryFormat.CONDA, RepositoryType.HOSTED, true)));
    List<AssetRecord> fullBatch = List.of(
        stagedAsset(110L, 510L), stagedAsset(111L, 511L),
        stagedAsset(112L, 512L), stagedAsset(113L, 513L),
        stagedAsset(114L, 514L), stagedAsset(115L, 515L),
        stagedAsset(116L, 516L), stagedAsset(117L, 517L));
    when(assets.claimStaleAssetsByPrefix(
        eq(10L), eq(CondaStagingCleanupWorker.STAGING_PREFIX), any(Instant.class), eq(8)))
        .thenReturn(fullBatch);
    when(assets.claimStaleAssetsByPrefix(
        eq(10L), eq(CondaStagingCleanupWorker.GENERATED_PREFIX), any(Instant.class), eq(8)))
        .thenReturn(List.of(
            generatedAssetWithAttributes(201L, Map.of("condaRevision", "7")),
            generatedAssetWithAttributes(202L, Map.of("condaRevision", "invalid")),
            generatedAssetWithAttributes(203L, Map.of())));
    when(registry.currentRepositoryRevision(10L)).thenReturn(7L);
    when(assets.deleteAssetById(anyLong())).thenReturn(1);
    when(assets.deleteAssetById(202L)).thenReturn(0);
    when(assets.deleteAssetById(203L)).thenReturn(0);

    new CondaStagingCleanupWorker(
        repositories, assets, browse, cache, registry, new RecordingTransactionManager(), metrics,
        true, 8, 3600, 3600, 8).cleanup();

    verify(assets, never()).claimStaleAssetsByPrefix(
        eq(11L), eq(CondaStagingCleanupWorker.STAGING_PREFIX), any(Instant.class), anyInt());
    verify(assets).touchAssetLastUpdated(eq(201L), any(Instant.class));
    verify(assets).deleteAssetById(202L);
    verify(assets).deleteAssetById(203L);
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
    when(repositories.list()).thenReturn(List.of(repository(
        10L, RepositoryFormat.CONDA, RepositoryType.HOSTED, true)));
    doThrow(new IllegalStateException("database unavailable"))
        .when(assets).claimStaleAssetsByPrefix(
            eq(10L), eq(CondaStagingCleanupWorker.STAGING_PREFIX), any(Instant.class), eq(8));

    worker(repositories, assets, mock(BrowseNodeDao.class), mock(AssetMetadataCache.class),
        mock(KkRepoMetrics.class), true).cleanup();

    verify(assets, never()).deleteAssetById(anyLong());
  }

  private static CondaStagingCleanupWorker worker(
      RepositoryDao repositories,
      AssetDao assets,
      BrowseNodeDao browse,
      AssetMetadataCache cache,
      KkRepoMetrics metrics,
      boolean enabled) {
    return new CondaStagingCleanupWorker(
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
      long id, RepositoryFormat format, RepositoryType type, boolean online) {
    return new RepositoryRecord(
        id,
        "repo-" + id,
        format,
        type,
        format.id() + "-" + type.name().toLowerCase(java.util.Locale.ROOT),
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
    String path = ".conda/staging/00000000-0000-0000-0000-000000000001/demo.conda";
    return new AssetRecord(
        assetId,
        10L,
        null,
        blobId,
        RepositoryFormat.CONDA,
        path,
        PersistenceHashes.pathHash(path),
        "demo.conda",
        "conda-staging",
        "application/octet-stream",
        42L,
        null,
        Instant.parse("2026-07-15T00:00:00Z"),
        Map.of("condaLogicalPath", "noarch/demo-1.0-py_0.conda"));
  }

  private static AssetRecord generatedAsset(long assetId, long blobId, long revision) {
    String path = ".conda/generated/channel/linux-64/" + revision + "/repodata.json.zst";
    return new AssetRecord(
        assetId,
        10L,
        null,
        blobId,
        RepositoryFormat.CONDA,
        path,
        PersistenceHashes.pathHash(path),
        "repodata.json.zst",
        "conda-generated",
        "application/zstd",
        42L,
        null,
        Instant.parse("2026-07-15T00:00:00Z"),
        Map.of("condaGenerated", true, "condaRevision", revision));
  }

  private static AssetRecord generatedAssetWithAttributes(
      long assetId, Map<String, Object> attributes) {
    String path = ".conda/generated/channel/linux-64/" + assetId + "/repodata.json.zst";
    return new AssetRecord(
        assetId,
        10L,
        null,
        null,
        RepositoryFormat.CONDA,
        path,
        PersistenceHashes.pathHash(path),
        "repodata.json.zst",
        "conda-generated",
        "application/zstd",
        42L,
        null,
        Instant.parse("2026-07-15T00:00:00Z"),
        attributes);
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
