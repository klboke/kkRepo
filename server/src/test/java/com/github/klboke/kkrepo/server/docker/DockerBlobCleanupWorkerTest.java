package com.github.klboke.kkrepo.server.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.server.metrics.KkRepoMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class DockerBlobCleanupWorkerTest {
  @Test
  void cleanupDeletesUnreferencedDockerBlobAssetAndMarksBlobForGlobalGc() {
    RepositoryDao repositoryDao = mock(RepositoryDao.class);
    DockerRegistryDao dockerRegistryDao = mock(DockerRegistryDao.class);
    AssetDao assetDao = mock(AssetDao.class);
    when(repositoryDao.list()).thenReturn(List.of(repository(10L, RepositoryFormat.DOCKER, true)));
    when(dockerRegistryDao.findUnreferencedBlobAssetIdForCleanup(anyLong(), anyLong(), anyInt(), any()))
        .thenReturn(OptionalLong.of(100L), OptionalLong.of(100L), OptionalLong.empty());
    when(assetDao.findAssetById(100L)).thenReturn(Optional.of(blobAsset(100L, 300L)));
    DockerBlobCleanupWorker worker = worker(repositoryDao, dockerRegistryDao, assetDao);

    worker.cleanup();

    verify(assetDao).deleteAssetById(100L);
    verify(assetDao).markBlobDeletedIfUnreferenced(300L, "docker blob unreferenced by live manifests");
  }

  @Test
  void cleanupRechecksCandidateBeforeDeleting() {
    RepositoryDao repositoryDao = mock(RepositoryDao.class);
    DockerRegistryDao dockerRegistryDao = mock(DockerRegistryDao.class);
    AssetDao assetDao = mock(AssetDao.class);
    when(repositoryDao.list()).thenReturn(List.of(repository(10L, RepositoryFormat.DOCKER, true)));
    when(dockerRegistryDao.findUnreferencedBlobAssetIdForCleanup(anyLong(), anyLong(), anyInt(), any()))
        .thenReturn(OptionalLong.of(100L), OptionalLong.empty(), OptionalLong.empty());
    when(assetDao.findAssetById(100L)).thenReturn(Optional.of(blobAsset(100L, 300L)));
    DockerBlobCleanupWorker worker = worker(repositoryDao, dockerRegistryDao, assetDao);

    worker.cleanup();

    verify(assetDao, never()).deleteAssetById(100L);
    verify(assetDao, never()).markBlobDeletedIfUnreferenced(anyLong(), any());
  }

  @Test
  void cleanupSkipsNonDockerAndOfflineRepositories() {
    RepositoryDao repositoryDao = mock(RepositoryDao.class);
    DockerRegistryDao dockerRegistryDao = mock(DockerRegistryDao.class);
    AssetDao assetDao = mock(AssetDao.class);
    when(repositoryDao.list()).thenReturn(List.of(
        repository(10L, RepositoryFormat.MAVEN2, true),
        repository(11L, RepositoryFormat.DOCKER, false)));
    DockerBlobCleanupWorker worker = worker(repositoryDao, dockerRegistryDao, assetDao);

    worker.cleanup();

    verify(dockerRegistryDao, never())
        .findUnreferencedBlobAssetIdForCleanup(anyLong(), anyLong(), anyInt(), any());
  }

  private static DockerBlobCleanupWorker worker(
      RepositoryDao repositoryDao,
      DockerRegistryDao dockerRegistryDao,
      AssetDao assetDao) {
    return new DockerBlobCleanupWorker(
        repositoryDao,
        dockerRegistryDao,
        assetDao,
        new RecordingTransactionManager(),
        new KkRepoMetrics(new SimpleMeterRegistry()),
        true,
        8,
        16,
        0);
  }

  private static RepositoryRecord repository(long id, RepositoryFormat format, boolean online) {
    return new RepositoryRecord(
        id,
        "repo-" + id,
        format,
        RepositoryType.HOSTED,
        "docker-hosted",
        online,
        1L,
        null,
        null,
        null,
        null,
        "ALLOW",
        true,
        Map.of());
  }

  private static AssetRecord blobAsset(long assetId, long assetBlobId) {
    Instant now = Instant.now();
    String path = "docker/blobs/sha256/aa/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    return new AssetRecord(
        assetId,
        10L,
        null,
        assetBlobId,
        RepositoryFormat.DOCKER,
        path,
        PersistenceHashes.pathHash(path),
        "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "BLOB",
        "application/octet-stream",
        42L,
        null,
        now.minusSeconds(7200),
        Map.of("docker", Map.of("digest",
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")));
  }

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
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
