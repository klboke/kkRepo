package com.github.klboke.kkrepo.server.maven;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.MetadataRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.MetadataRebuildDao.Claim;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.metrics.KkRepoMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

class MetadataRebuildWorkerTest {

  @Test
  void disabledWorkerDoesNotClaimMarkers() {
    MetadataRebuildDao dao = mock(MetadataRebuildDao.class);

    worker(
        mock(AssetDao.class),
        dao,
        mock(MavenMetadataService.class),
        mock(RepositoryRuntimeRegistry.class),
        mock(BlobStorageRegistry.class),
        false).drain();

    verifyNoInteractions(dao);
  }

  @Test
  void dispatchesGaAndSnapshotScopes() {
    AssetDao assetDao = mock(AssetDao.class);
    MetadataRebuildDao dao = mock(MetadataRebuildDao.class);
    MavenMetadataService metadata = mock(MavenMetadataService.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    BlobStorageRegistry storages = mock(BlobStorageRegistry.class);
    RepositoryRuntime runtime = runtime(10L, 7L);
    BlobStorage storage = mock(BlobStorage.class);
    Instant requestedAt = Instant.now().minusSeconds(30);
    when(dao.claim(8)).thenReturn(List.of(
        new Claim(10L, MetadataRebuildScope.ga("com.acme", "demo"), requestedAt, 0, null),
        new Claim(10L, MetadataRebuildScope.gav("com.acme", "demo", "1.0-SNAPSHOT"),
            requestedAt, 0, null)));
    when(runtimes.resolveById(10L)).thenReturn(Optional.of(runtime));
    when(storages.forBlobStoreId(7L)).thenReturn(storage);
    when(assetDao.findAssetByPath(eq(10L), any())).thenReturn(Optional.empty());

    worker(assetDao, dao, metadata, runtimes, storages, true).drain();

    verify(metadata).rebuildGa(runtime, storage, 7L, "com.acme", "demo", "system", null);
    verify(metadata).rebuildBaseVersionIfSnapshot(
        eq(runtime),
        eq(storage),
        eq(7L),
        argThat(coords ->
            coords.snapshot()
                && "com.acme".equals(coords.groupId())
                && "demo".equals(coords.artifactId())
                && "1.0-SNAPSHOT".equals(coords.baseVersion())),
        eq("system"),
        eq(null));
  }

  @Test
  void skipsMalformedMissingAndSupersededMarkers() {
    AssetDao assetDao = mock(AssetDao.class);
    MetadataRebuildDao dao = mock(MetadataRebuildDao.class);
    MavenMetadataService metadata = mock(MavenMetadataService.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    BlobStorageRegistry storages = mock(BlobStorageRegistry.class);
    Instant requestedAt = Instant.now().minusSeconds(60);
    Claim malformed = new Claim(1L, "bad-scope", requestedAt, 0, null);
    Claim missing = new Claim(2L, MetadataRebuildScope.ga("com.acme", "missing"), requestedAt, 0, null);
    Claim superseded = new Claim(3L, MetadataRebuildScope.ga("com.acme", "demo"), requestedAt, 0, null);
    when(dao.claim(8)).thenReturn(List.of(malformed, missing, superseded));
    when(runtimes.resolveById(2L)).thenReturn(Optional.empty());
    when(runtimes.resolveById(3L)).thenReturn(Optional.of(runtime(3L, 7L)));
    when(assetDao.findAssetByPath(3L, "com/acme/demo/maven-metadata.xml"))
        .thenReturn(Optional.of(metadataAsset(3L, Instant.now())));

    worker(assetDao, dao, metadata, runtimes, storages, true).drain();

    verifyNoInteractions(metadata, storages);
  }

  @Test
  void failedItemIsReenqueuedAndLaterClaimStillRuns() {
    AssetDao assetDao = mock(AssetDao.class);
    MetadataRebuildDao dao = mock(MetadataRebuildDao.class);
    MavenMetadataService metadata = mock(MavenMetadataService.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    BlobStorageRegistry storages = mock(BlobStorageRegistry.class);
    RepositoryRuntime runtime = runtime(10L, 7L);
    BlobStorage storage = mock(BlobStorage.class);
    Instant requestedAt = Instant.now().minusSeconds(30);
    Claim failing = new Claim(10L, MetadataRebuildScope.ga("com.fail", "first"), requestedAt, 0, null);
    Claim succeeding = new Claim(10L, MetadataRebuildScope.ga("com.ok", "second"), requestedAt, 0, null);
    when(dao.claim(8)).thenReturn(List.of(failing, succeeding));
    when(runtimes.resolveById(10L)).thenReturn(Optional.of(runtime));
    when(storages.forBlobStoreId(7L)).thenReturn(storage);
    when(assetDao.findAssetByPath(eq(10L), any())).thenReturn(Optional.empty());
    IllegalStateException failure = new IllegalStateException("write failed");
    doThrow(failure).when(metadata)
        .rebuildGa(runtime, storage, 7L, "com.fail", "first", "system", null);

    worker(assetDao, dao, metadata, runtimes, storages, true).drain();

    verify(dao).reenqueueFailure(failing, failure);
    verify(metadata).rebuildGa(runtime, storage, 7L, "com.ok", "second", "system", null);
  }

  private static MetadataRebuildWorker worker(
      AssetDao assetDao,
      MetadataRebuildDao dao,
      MavenMetadataService metadata,
      RepositoryRuntimeRegistry runtimes,
      BlobStorageRegistry storages,
      boolean enabled) {
    return new MetadataRebuildWorker(
        assetDao,
        dao,
        metadata,
        runtimes,
        storages,
        new RecordingTransactionManager(),
        8,
        enabled,
        new KkRepoMetrics(new SimpleMeterRegistry()));
  }

  private static RepositoryRuntime runtime(long id, Long blobStoreId) {
    return new RepositoryRuntime(
        id,
        "maven-" + id,
        RepositoryFormat.MAVEN2,
        RepositoryType.HOSTED,
        "maven2-hosted",
        true,
        blobStoreId,
        "ALLOW",
        "MIXED",
        "PERMISSIVE",
        true,
        null,
        null,
        null,
        true,
        null,
        List.of());
  }

  private static AssetRecord metadataAsset(long repositoryId, Instant updatedAt) {
    String path = "com/acme/demo/maven-metadata.xml";
    return new AssetRecord(
        100L,
        repositoryId,
        null,
        200L,
        RepositoryFormat.MAVEN2,
        path,
        null,
        null,
        "metadata",
        "application/xml",
        20L,
        null,
        updatedAt,
        Map.of());
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
