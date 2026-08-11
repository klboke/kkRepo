package com.github.klboke.kkrepo.server.conan;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

  private static RepositoryRecord repository(long id, RepositoryFormat format) {
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
    String path = ".conan/proxy-staging/00000000-0000-0000-0000-000000000001/file";
    return new AssetRecord(
        101L,
        10L,
        null,
        501L,
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
