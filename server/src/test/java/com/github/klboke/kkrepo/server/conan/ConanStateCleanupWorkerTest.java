package com.github.klboke.kkrepo.server.conan;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ConanRegistryDao;
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

class ConanStateCleanupWorkerTest {
  @Test
  void claimsAndDeletesProxyStagingInsideTheWorkerTransaction() {
    ConanRegistryDao registry = mock(ConanRegistryDao.class);
    RepositoryDao repositories = mock(RepositoryDao.class);
    AssetDao assets = mock(AssetDao.class);
    BrowseNodeDao browse = mock(BrowseNodeDao.class);
    AssetMetadataCache cache = mock(AssetMetadataCache.class);
    KkRepoMetrics metrics = mock(KkRepoMetrics.class);
    AssetRecord staged = stagedAsset();
    when(registry.claimExpiredUploadSessions(
        any(), any(Instant.class), any(Instant.class), eq(8))).thenReturn(List.of());
    when(repositories.list()).thenReturn(List.of(
        repository(10L, RepositoryFormat.CONAN),
        repository(11L, RepositoryFormat.MAVEN2)));
    when(assets.claimStaleAssetsByPrefix(
        eq(10L), eq(".conan/proxy-staging/"), any(Instant.class), eq(8)))
        .thenReturn(List.of(staged));
    when(assets.findAssetById(101L)).thenReturn(Optional.of(staged));
    when(assets.deleteAssetById(101L)).thenReturn(1);

    worker(registry, repositories, assets, browse, cache, metrics, true).cleanup();

    verify(browse).deleteByAssetId(101L);
    verify(assets).markBlobDeletedIfUnreferenced(
        501L, "abandoned Conan staging asset");
    verify(cache).evictAfterCommit(10L, staged.path());
    verify(assets, never()).claimStaleAssetsByPrefix(
        eq(11L), any(), any(Instant.class), eq(8));
    verify(metrics).incrementWorkerItems(
        "conan_cleanup", "proxy_staging_asset", "deleted", 1);
  }

  @Test
  void disabledCleanupDoesNotReadSharedState() {
    RepositoryDao repositories = mock(RepositoryDao.class);

    worker(
        mock(ConanRegistryDao.class), repositories, mock(AssetDao.class),
        mock(BrowseNodeDao.class), mock(AssetMetadataCache.class),
        mock(KkRepoMetrics.class), false).cleanup();

    verify(repositories, never()).list();
  }

  @Test
  void cleansClaimedSessionsAndDrainsBoundedTokenAndLeaseBatches() {
    ConanRegistryDao registry = mock(ConanRegistryDao.class);
    RepositoryDao repositories = mock(RepositoryDao.class);
    AssetDao assets = mock(AssetDao.class);
    BrowseNodeDao browse = mock(BrowseNodeDao.class);
    AssetMetadataCache cache = mock(AssetMetadataCache.class);
    KkRepoMetrics metrics = mock(KkRepoMetrics.class);
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    ConanRegistryDao.UploadSession deleted = uploadSession(11L, 10L, "owner-a", 7L, now);
    ConanRegistryDao.UploadSession lostClaim = uploadSession(12L, 10L, "owner-b", 8L, now);
    ConanRegistryDao.UploadFile file = uploadFile(21L, deleted.id(), 101L, now);
    AssetRecord staged = stagedAsset();
    when(registry.claimExpiredUploadSessions(
        any(), any(Instant.class), any(Instant.class), eq(8)))
        .thenReturn(List.of(deleted, lostClaim));
    when(registry.listUploadFiles(deleted.id())).thenReturn(List.of(file));
    when(registry.listUploadFiles(lostClaim.id())).thenReturn(List.of());
    when(registry.deleteClaimedUploadSession(deleted.id(), deleted.owner(), deleted.fencingToken()))
        .thenReturn(true);
    when(registry.deleteClaimedUploadSession(
        lostClaim.id(), lostClaim.owner(), lostClaim.fencingToken())).thenReturn(false);
    when(registry.deleteExpiredAuthTokens(any(Instant.class), eq(8))).thenReturn(8, 3);
    when(registry.deleteExpiredLeases(any(Instant.class), eq(8))).thenReturn(8, 0);
    when(repositories.list()).thenReturn(List.of());
    when(assets.findAssetById(101L)).thenReturn(Optional.of(staged));
    when(assets.deleteAssetById(101L)).thenReturn(1);

    worker(registry, repositories, assets, browse, cache, metrics, true).cleanup();

    verify(registry).deleteClaimedUploadSession(
        deleted.id(), deleted.owner(), deleted.fencingToken());
    verify(registry).deleteClaimedUploadSession(
        lostClaim.id(), lostClaim.owner(), lostClaim.fencingToken());
    verify(registry, times(2)).deleteExpiredAuthTokens(any(Instant.class), eq(8));
    verify(registry, times(2)).deleteExpiredLeases(any(Instant.class), eq(8));
    verify(metrics).incrementWorkerItems(
        "conan_cleanup", "upload_session", "deleted", 1);
    verify(metrics).incrementWorkerItems("conan_cleanup", "auth_token", "deleted", 11);
    verify(metrics).incrementWorkerItems("conan_cleanup", "coordinate_lease", "deleted", 8);
  }

  @Test
  void skipsUnownedOrConcurrentlyDeletedStagingAssets() {
    ConanRegistryDao registry = mock(ConanRegistryDao.class);
    RepositoryDao repositories = mock(RepositoryDao.class);
    AssetDao assets = mock(AssetDao.class);
    BrowseNodeDao browse = mock(BrowseNodeDao.class);
    AssetMetadataCache cache = mock(AssetMetadataCache.class);
    KkRepoMetrics metrics = mock(KkRepoMetrics.class);
    when(registry.claimExpiredUploadSessions(
        any(), any(Instant.class), any(Instant.class), eq(8))).thenReturn(List.of());
    when(repositories.list()).thenReturn(List.of(
        repository(null, RepositoryFormat.CONAN), repository(10L, RepositoryFormat.CONAN)));
    AssetRecord missing = stagedAsset(201L, 10L, 501L);
    AssetRecord wrongRepository = stagedAsset(202L, 11L, 502L);
    AssetRecord concurrentDelete = stagedAsset(203L, 10L, null);
    AssetRecord noBlob = stagedAsset(204L, 10L, null);
    when(assets.claimStaleAssetsByPrefix(
        eq(10L), eq(".conan/proxy-staging/"), any(Instant.class), eq(8)))
        .thenReturn(List.of(missing, wrongRepository, concurrentDelete, noBlob));
    when(assets.findAssetById(201L)).thenReturn(Optional.empty());
    when(assets.findAssetById(202L)).thenReturn(Optional.of(wrongRepository));
    when(assets.findAssetById(203L)).thenReturn(Optional.of(concurrentDelete));
    when(assets.findAssetById(204L)).thenReturn(Optional.of(noBlob));
    when(assets.deleteAssetById(203L)).thenReturn(0);
    when(assets.deleteAssetById(204L)).thenReturn(1);

    worker(registry, repositories, assets, browse, cache, metrics, true).cleanup();

    verify(assets, never()).deleteAssetById(201L);
    verify(assets, never()).deleteAssetById(202L);
    verify(assets, never()).markBlobDeletedIfUnreferenced(anyLong(), any());
    verify(cache).evictAfterCommit(10L, noBlob.path());
    verify(metrics).incrementWorkerItems(
        "conan_cleanup", "proxy_staging_asset", "deleted", 1);
  }

  @Test
  void recordsFailureSoAnotherReplicaCanRetry() {
    ConanRegistryDao registry = mock(ConanRegistryDao.class);
    KkRepoMetrics metrics = mock(KkRepoMetrics.class);
    when(registry.claimExpiredUploadSessions(
        any(), any(Instant.class), any(Instant.class), eq(8)))
        .thenThrow(new IllegalStateException("database unavailable"));

    worker(
        registry, mock(RepositoryDao.class), mock(AssetDao.class), mock(BrowseNodeDao.class),
        mock(AssetMetadataCache.class), metrics, true).cleanup();

    verify(metrics).recordWorkerBatch(eq("conan_state_cleanup"), eq("error"), any());
    verify(metrics, never()).recordWorkerBatch(eq("conan_state_cleanup"), eq("success"), any());
  }

  private static ConanStateCleanupWorker worker(
      ConanRegistryDao registry,
      RepositoryDao repositories,
      AssetDao assets,
      BrowseNodeDao browse,
      AssetMetadataCache cache,
      KkRepoMetrics metrics,
      boolean enabled) {
    return new ConanStateCleanupWorker(
        registry, repositories, assets, browse, cache, metrics,
        new RecordingTransactionManager(), enabled, 8, 64, 300, 3600);
  }

  private static RepositoryRecord repository(Long id, RepositoryFormat format) {
    return new RepositoryRecord(
        id,
        "repo-" + id,
        format,
        RepositoryType.PROXY,
        format.id() + "-proxy",
        true,
        1L,
        null,
        "https://example.invalid/",
        null,
        null,
        null,
        true,
        Map.of());
  }

  private static AssetRecord stagedAsset() {
    return stagedAsset(101L, 10L, 501L);
  }

  private static AssetRecord stagedAsset(long id, long repositoryId, Long blobId) {
    String path = ".conan/proxy-staging/00000000-0000-0000-0000-000000000001/file";
    return new AssetRecord(
        id,
        repositoryId,
        null,
        blobId,
        RepositoryFormat.CONAN,
        path,
        PersistenceHashes.pathHash(path),
        "file",
        "conan-proxy-staging",
        "application/octet-stream",
        42L,
        null,
        Instant.parse("2026-08-01T00:00:00Z"),
        Map.of());
  }

  private static ConanRegistryDao.UploadSession uploadSession(
      long id, long repositoryId, String owner, long fencingToken, Instant now) {
    return new ConanRegistryDao.UploadSession(
        id, repositoryId, ConanRegistryDao.OWNER_RECIPE, "demo/1.0#rrev", "actor",
        ConanRegistryDao.SESSION_OPEN, owner, fencingToken, now.minusSeconds(1),
        now.minusSeconds(1), now.minusSeconds(60), now);
  }

  private static ConanRegistryDao.UploadFile uploadFile(
      long id, long sessionId, long assetId, Instant now) {
    return new ConanRegistryDao.UploadFile(
        id, sessionId, "conanfile.py", assetId, "a".repeat(32), "b".repeat(40),
        "c".repeat(64), 42L, "text/x-python", now, now);
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
