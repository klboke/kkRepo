package com.github.klboke.kkrepo.server.ansible;

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
import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AnsibleStagingCleanupWorkerTest {
  private static final String TASK_ONE = "11111111-2222-3333-4444-555555555555";
  private static final String TASK_TWO = "22222222-3333-4444-5555-666666666666";

  @Test
  void orphanedStagingAssetIsUnlinkedAndItsBlobIsHandedToGlobalGc() {
    RepositoryDao repositories = mock(RepositoryDao.class);
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    AssetDao assets = mock(AssetDao.class);
    BrowseNodeDao browse = mock(BrowseNodeDao.class);
    AssetMetadataCache cache = mock(AssetMetadataCache.class);
    KkRepoMetrics metrics = mock(KkRepoMetrics.class);
    AssetRecord staged = stagedAsset(101L, 501L, TASK_ONE);
    when(repositories.list()).thenReturn(List.of(hosted(10L, false)));
    when(assets.claimStaleAssetsByPrefix(
        eq(10L), eq(AnsibleStagingCleanupWorker.STAGING_PREFIX), any(Instant.class), eq(8)))
        .thenReturn(List.of(staged));
    when(registry.findTask(TASK_ONE)).thenReturn(Optional.empty());
    when(assets.deleteAssetById(101L)).thenReturn(1);

    worker(repositories, registry, assets, browse, cache, metrics, true).cleanup();

    verify(browse).deleteByAssetId(101L);
    verify(assets).deleteAssetById(101L);
    verify(assets).markBlobDeletedIfUnreferenced(
        501L, "abandoned Ansible publication staging asset");
    verify(cache).evictAfterCommit(10L, staged.path());
    verify(metrics).incrementWorkerItems("ansible_cleanup", "staging_asset", "deleted", 1);
  }

  @Test
  void terminalTaskStagingIsReclaimedWithoutRequiringABlobReference() {
    RepositoryDao repositories = mock(RepositoryDao.class);
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    AssetDao assets = mock(AssetDao.class);
    AssetRecord staged = stagedAsset(102L, null, TASK_ONE);
    AnsibleGalaxyRegistryDao.ImportTask task = mock(AnsibleGalaxyRegistryDao.ImportTask.class);
    when(task.state()).thenReturn(AnsibleGalaxyRegistryDao.TASK_COMPLETED);
    when(repositories.list()).thenReturn(List.of(hosted(10L, true)));
    when(assets.claimStaleAssetsByPrefix(
        eq(10L), eq(AnsibleStagingCleanupWorker.STAGING_PREFIX), any(Instant.class), eq(8)))
        .thenReturn(List.of(staged));
    when(registry.findTask(TASK_ONE)).thenReturn(Optional.of(task));
    when(assets.deleteAssetById(102L)).thenReturn(1);

    worker(repositories, registry, assets, mock(BrowseNodeDao.class),
        mock(AssetMetadataCache.class), mock(KkRepoMetrics.class), true).cleanup();

    verify(assets).deleteAssetById(102L);
    verify(assets, never()).markBlobDeletedIfUnreferenced(anyLong(), any());
  }

  @Test
  void waitingAndRunningTaskStagingRemainAvailableForReplicaRecovery() {
    RepositoryDao repositories = mock(RepositoryDao.class);
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    AssetDao assets = mock(AssetDao.class);
    KkRepoMetrics metrics = mock(KkRepoMetrics.class);
    AnsibleGalaxyRegistryDao.ImportTask waiting = mock(AnsibleGalaxyRegistryDao.ImportTask.class);
    AnsibleGalaxyRegistryDao.ImportTask running = mock(AnsibleGalaxyRegistryDao.ImportTask.class);
    when(waiting.state()).thenReturn(AnsibleGalaxyRegistryDao.TASK_WAITING);
    when(running.state()).thenReturn(AnsibleGalaxyRegistryDao.TASK_RUNNING);
    when(repositories.list()).thenReturn(List.of(hosted(10L, true)));
    when(assets.claimStaleAssetsByPrefix(
        eq(10L), eq(AnsibleStagingCleanupWorker.STAGING_PREFIX), any(Instant.class), eq(8)))
        .thenReturn(List.of(
            stagedAsset(103L, 503L, TASK_ONE),
            stagedAsset(104L, 504L, TASK_TWO)));
    when(registry.findTask(TASK_ONE)).thenReturn(Optional.of(waiting));
    when(registry.findTask(TASK_TWO)).thenReturn(Optional.of(running));

    worker(repositories, registry, assets, mock(BrowseNodeDao.class),
        mock(AssetMetadataCache.class), metrics, true).cleanup();

    verify(assets, never()).deleteAssetById(anyLong());
    verify(metrics).incrementWorkerItems("ansible_cleanup", "staging_asset", "deleted", 0);
  }

  @Test
  void malformedStagingPathIsTreatedAsAnOrphan() {
    RepositoryDao repositories = mock(RepositoryDao.class);
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    AssetDao assets = mock(AssetDao.class);
    AssetRecord staged = stagedAsset(105L, 505L, "not-a-task-id");
    when(repositories.list()).thenReturn(List.of(hosted(10L, true)));
    when(assets.claimStaleAssetsByPrefix(
        eq(10L), eq(AnsibleStagingCleanupWorker.STAGING_PREFIX), any(Instant.class), eq(8)))
        .thenReturn(List.of(staged));
    when(assets.deleteAssetById(105L)).thenReturn(1);

    worker(repositories, registry, assets, mock(BrowseNodeDao.class),
        mock(AssetMetadataCache.class), mock(KkRepoMetrics.class), true).cleanup();

    verify(registry, never()).findTask(any());
    verify(assets).deleteAssetById(105L);
  }

  @Test
  void cleanupScansOfflineHostedRepositoriesButSkipsGroupsAndOtherFormats() {
    RepositoryDao repositories = mock(RepositoryDao.class);
    AssetDao assets = mock(AssetDao.class);
    when(repositories.list()).thenReturn(List.of(
        hosted(10L, false),
        repository(11L, RepositoryFormat.ANSIBLEGALAXY, RepositoryType.GROUP, true),
        repository(12L, RepositoryFormat.MAVEN2, RepositoryType.HOSTED, true)));
    when(assets.claimStaleAssetsByPrefix(
        eq(10L), eq(AnsibleStagingCleanupWorker.STAGING_PREFIX), any(Instant.class), anyInt()))
        .thenReturn(List.of());

    worker(repositories, mock(AnsibleGalaxyRegistryDao.class), assets,
        mock(BrowseNodeDao.class), mock(AssetMetadataCache.class),
        mock(KkRepoMetrics.class), true).cleanup();

    verify(assets).claimStaleAssetsByPrefix(
        eq(10L), eq(AnsibleStagingCleanupWorker.STAGING_PREFIX), any(Instant.class), eq(8));
    verify(assets, never()).claimStaleAssetsByPrefix(
        eq(11L), any(), any(Instant.class), anyInt());
    verify(assets, never()).claimStaleAssetsByPrefix(
        eq(12L), any(), any(Instant.class), anyInt());
  }

  @Test
  void disabledCleanupDoesNotReadSharedState() {
    RepositoryDao repositories = mock(RepositoryDao.class);

    worker(repositories, mock(AnsibleGalaxyRegistryDao.class), mock(AssetDao.class),
        mock(BrowseNodeDao.class), mock(AssetMetadataCache.class),
        mock(KkRepoMetrics.class), false).cleanup();

    verify(repositories, never()).list();
  }

  @Test
  void transientClaimFailureIsRetriedByAnotherReplicaOrCycle() {
    RepositoryDao repositories = mock(RepositoryDao.class);
    AssetDao assets = mock(AssetDao.class);
    KkRepoMetrics metrics = mock(KkRepoMetrics.class);
    when(repositories.list()).thenReturn(List.of(hosted(10L, true)));
    doThrow(new IllegalStateException("database unavailable"))
        .when(assets).claimStaleAssetsByPrefix(
            eq(10L), eq(AnsibleStagingCleanupWorker.STAGING_PREFIX), any(Instant.class), eq(8));

    worker(repositories, mock(AnsibleGalaxyRegistryDao.class), assets,
        mock(BrowseNodeDao.class), mock(AssetMetadataCache.class), metrics, true).cleanup();

    verify(assets, never()).deleteAssetById(anyLong());
    verify(metrics).recordWorkerBatch(eq("ansible_staging_cleanup"), eq("error"), any());
  }

  @Test
  void concurrentDeleteWinnerDoesNotMarkOrEvictTheBlob() {
    RepositoryDao repositories = mock(RepositoryDao.class);
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    AssetDao assets = mock(AssetDao.class);
    AssetMetadataCache cache = mock(AssetMetadataCache.class);
    AssetRecord staged = stagedAsset(106L, 506L, TASK_ONE);
    when(repositories.list()).thenReturn(List.of(hosted(10L, true)));
    when(assets.claimStaleAssetsByPrefix(
        eq(10L), eq(AnsibleStagingCleanupWorker.STAGING_PREFIX), any(Instant.class), eq(8)))
        .thenReturn(List.of(staged));
    when(registry.findTask(TASK_ONE)).thenReturn(Optional.empty());
    when(assets.deleteAssetById(106L)).thenReturn(0);

    worker(repositories, registry, assets, mock(BrowseNodeDao.class), cache,
        mock(KkRepoMetrics.class), true).cleanup();

    verify(assets, never()).markBlobDeletedIfUnreferenced(anyLong(), any());
    verify(cache, never()).evictAfterCommit(anyLong(), any());
  }

  private static AnsibleStagingCleanupWorker worker(
      RepositoryDao repositories,
      AnsibleGalaxyRegistryDao registry,
      AssetDao assets,
      BrowseNodeDao browse,
      AssetMetadataCache cache,
      KkRepoMetrics metrics,
      boolean enabled) {
    return new AnsibleStagingCleanupWorker(
        repositories,
        registry,
        assets,
        browse,
        cache,
        new RecordingTransactionManager(),
        metrics,
        enabled,
        8,
        3600);
  }

  private static RepositoryRecord hosted(long id, boolean online) {
    return repository(
        id, RepositoryFormat.ANSIBLEGALAXY, RepositoryType.HOSTED, online);
  }

  private static RepositoryRecord repository(
      long id, RepositoryFormat format, RepositoryType type, boolean online) {
    return new RepositoryRecord(
        id,
        "repo-" + id,
        format,
        type,
        format == RepositoryFormat.ANSIBLEGALAXY ? "ansiblegalaxy-hosted" : "maven2-hosted",
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

  private static AssetRecord stagedAsset(long assetId, Long blobId, String taskId) {
    String path = AnsibleStagingCleanupWorker.STAGING_PREFIX + taskId + "/collection.tar.gz";
    return new AssetRecord(
        assetId,
        10L,
        null,
        blobId,
        RepositoryFormat.ANSIBLEGALAXY,
        path,
        PersistenceHashes.pathHash(path),
        "collection.tar.gz",
        "ansiblegalaxy",
        "application/octet-stream",
        42L,
        null,
        Instant.parse("2026-07-15T00:00:00Z"),
        Map.of());
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
