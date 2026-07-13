package com.github.klboke.kkrepo.server.repository;

import static org.mockito.ArgumentMatchers.any;
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
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao.Claim;
import com.github.klboke.kkrepo.server.helm.HelmHostedService;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.metrics.KkRepoMetrics;
import com.github.klboke.kkrepo.server.pypi.PypiHostedService;
import com.github.klboke.kkrepo.server.rubygems.RubygemsService;
import com.github.klboke.kkrepo.server.yum.YumService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class RepositoryIndexRebuildWorkerTest {

  @Test
  void disabledWorkerDoesNotClaimMarkers() {
    RepositoryIndexRebuildDao dao = mock(RepositoryIndexRebuildDao.class);

    worker(
        dao,
        mock(RepositoryRuntimeRegistry.class),
        mock(BlobStorageRegistry.class),
        mock(HelmHostedService.class),
        mock(PypiHostedService.class),
        mock(YumService.class),
        mock(RubygemsService.class),
        false).drain();

    verifyNoInteractions(dao);
  }

  @Test
  void dispatchesSupportedHostedIndexKinds() {
    RepositoryIndexRebuildDao dao = mock(RepositoryIndexRebuildDao.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    BlobStorageRegistry storages = mock(BlobStorageRegistry.class);
    HelmHostedService helm = mock(HelmHostedService.class);
    PypiHostedService pypi = mock(PypiHostedService.class);
    YumService yum = mock(YumService.class);
    RubygemsService rubygems = mock(RubygemsService.class);
    BlobStorage storage = mock(BlobStorage.class);
    RepositoryRuntime helmRuntime = runtime(1L, RepositoryFormat.HELM, 7L);
    RepositoryRuntime pypiRuntime = runtime(2L, RepositoryFormat.PYPI, 7L);
    RepositoryRuntime yumRuntime = runtime(3L, RepositoryFormat.YUM, 7L);
    RepositoryRuntime rubygemsRuntime = runtime(4L, RepositoryFormat.RUBYGEMS, 7L);
    Instant now = Instant.now();
    when(dao.claim(8)).thenReturn(List.of(
        claim(1L, RepositoryIndexRebuildDao.HELM_INDEX, null, now),
        claim(2L, RepositoryIndexRebuildDao.PYPI_ROOT, null, now),
        claim(2L, RepositoryIndexRebuildDao.PYPI_PROJECT, "demo", now),
        claim(3L, RepositoryIndexRebuildDao.YUM_METADATA, null, now),
        claim(4L, RepositoryIndexRebuildDao.RUBYGEMS_METADATA, null, now)));
    when(runtimes.resolveById(1L)).thenReturn(Optional.of(helmRuntime));
    when(runtimes.resolveById(2L)).thenReturn(Optional.of(pypiRuntime));
    when(runtimes.resolveById(3L)).thenReturn(Optional.of(yumRuntime));
    when(runtimes.resolveById(4L)).thenReturn(Optional.of(rubygemsRuntime));
    when(storages.forBlobStoreId(7L)).thenReturn(storage);

    worker(dao, runtimes, storages, helm, pypi, yum, rubygems, true).drain();

    verify(helm).rebuildIndex(helmRuntime, storage, 7L, "system", null);
    verify(pypi).rebuildRootIndex(pypiRuntime, storage, 7L, "system", null);
    verify(pypi).rebuildProjectIndex(pypiRuntime, storage, 7L, "demo", "system", null);
    verify(yum).rebuildMetadata(yumRuntime, "system", null);
    verify(rubygems).rebuildGeneratedMetadata(rubygemsRuntime);
  }

  @Test
  void ignoresMissingNonHostedWrongFormatAndBlankProjectClaims() {
    RepositoryIndexRebuildDao dao = mock(RepositoryIndexRebuildDao.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    BlobStorageRegistry storages = mock(BlobStorageRegistry.class);
    HelmHostedService helm = mock(HelmHostedService.class);
    PypiHostedService pypi = mock(PypiHostedService.class);
    YumService yum = mock(YumService.class);
    RubygemsService rubygems = mock(RubygemsService.class);
    Instant now = Instant.now();
    when(dao.claim(8)).thenReturn(List.of(
        claim(1L, RepositoryIndexRebuildDao.HELM_INDEX, null, now),
        claim(2L, RepositoryIndexRebuildDao.HELM_INDEX, null, now),
        claim(3L, RepositoryIndexRebuildDao.HELM_INDEX, null, now),
        claim(4L, RepositoryIndexRebuildDao.PYPI_PROJECT, " ", now),
        claim(5L, "unknown", null, now)));
    when(runtimes.resolveById(1L)).thenReturn(Optional.empty());
    when(runtimes.resolveById(2L)).thenReturn(Optional.of(
        runtime(2L, RepositoryFormat.HELM, RepositoryType.PROXY, 7L)));
    when(runtimes.resolveById(3L)).thenReturn(Optional.of(runtime(3L, RepositoryFormat.PYPI, 7L)));
    when(runtimes.resolveById(4L)).thenReturn(Optional.of(runtime(4L, RepositoryFormat.PYPI, 7L)));
    when(runtimes.resolveById(5L)).thenReturn(Optional.of(runtime(5L, RepositoryFormat.HELM, 7L)));

    worker(dao, runtimes, storages, helm, pypi, yum, rubygems, true).drain();

    verifyNoInteractions(helm, pypi, yum, rubygems, storages);
  }

  @Test
  void failedItemIsReenqueuedAndLaterClaimStillRuns() {
    RepositoryIndexRebuildDao dao = mock(RepositoryIndexRebuildDao.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    BlobStorageRegistry storages = mock(BlobStorageRegistry.class);
    HelmHostedService helm = mock(HelmHostedService.class);
    PypiHostedService pypi = mock(PypiHostedService.class);
    YumService yum = mock(YumService.class);
    RubygemsService rubygems = mock(RubygemsService.class);
    BlobStorage storage = mock(BlobStorage.class);
    RepositoryRuntime helmRuntime = runtime(1L, RepositoryFormat.HELM, 7L);
    RepositoryRuntime pypiRuntime = runtime(2L, RepositoryFormat.PYPI, 7L);
    Instant now = Instant.now();
    Claim failing = claim(1L, RepositoryIndexRebuildDao.HELM_INDEX, null, now);
    Claim succeeding = claim(2L, RepositoryIndexRebuildDao.PYPI_ROOT, null, now);
    when(dao.claim(8)).thenReturn(List.of(failing, succeeding));
    when(runtimes.resolveById(1L)).thenReturn(Optional.of(helmRuntime));
    when(runtimes.resolveById(2L)).thenReturn(Optional.of(pypiRuntime));
    when(storages.forBlobStoreId(7L)).thenReturn(storage);
    IllegalStateException failure = new IllegalStateException("index write failed");
    doThrow(failure).when(helm).rebuildIndex(helmRuntime, storage, 7L, "system", null);

    worker(dao, runtimes, storages, helm, pypi, yum, rubygems, true).drain();

    verify(dao).reenqueueFailure(failing, failure);
    verify(pypi).rebuildRootIndex(pypiRuntime, storage, 7L, "system", null);
  }

  private static RepositoryIndexRebuildWorker worker(
      RepositoryIndexRebuildDao dao,
      RepositoryRuntimeRegistry runtimes,
      BlobStorageRegistry storages,
      HelmHostedService helm,
      PypiHostedService pypi,
      YumService yum,
      RubygemsService rubygems,
      boolean enabled) {
    return new RepositoryIndexRebuildWorker(
        dao,
        runtimes,
        storages,
        helm,
        pypi,
        yum,
        rubygems,
        new RecordingTransactionManager(),
        8,
        enabled,
        new KkRepoMetrics(new SimpleMeterRegistry()));
  }

  private static Claim claim(long repositoryId, String kind, String scope, Instant requestedAt) {
    return new Claim(repositoryId, kind, scope, requestedAt, 0, null);
  }

  private static RepositoryRuntime runtime(long id, RepositoryFormat format, Long blobStoreId) {
    return runtime(id, format, RepositoryType.HOSTED, blobStoreId);
  }

  private static RepositoryRuntime runtime(
      long id,
      RepositoryFormat format,
      RepositoryType type,
      Long blobStoreId) {
    return new RepositoryRuntime(
        id,
        format.id() + "-" + id,
        format,
        type,
        format.id() + "-" + type.name().toLowerCase(),
        true,
        blobStoreId,
        "ALLOW",
        null,
        null,
        true,
        null,
        null,
        null,
        true,
        null,
        List.of());
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
