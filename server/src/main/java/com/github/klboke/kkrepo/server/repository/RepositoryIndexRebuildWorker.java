package com.github.klboke.kkrepo.server.repository;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao.Claim;
import com.github.klboke.kkrepo.server.helm.HelmHostedService;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.pypi.PypiHostedService;
import com.github.klboke.kkrepo.server.metrics.KkRepoMetrics;
import com.github.klboke.kkrepo.server.rubygems.RubygemsService;
import com.github.klboke.kkrepo.server.yum.YumService;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class RepositoryIndexRebuildWorker {
  private static final Logger log = LoggerFactory.getLogger(RepositoryIndexRebuildWorker.class);

  private final RepositoryIndexRebuildDao dao;
  private final RepositoryRuntimeRegistry runtimeRegistry;
  private final BlobStorageRegistry blobStorageRegistry;
  private final HelmHostedService helmHostedService;
  private final PypiHostedService pypiHostedService;
  private final YumService yumService;
  private final RubygemsService rubygemsService;
  private final TransactionTemplate transactionTemplate;
  private final KkRepoMetrics metrics;
  private final int batchSize;
  private final boolean enabled;

  RepositoryIndexRebuildWorker(
      RepositoryIndexRebuildDao dao,
      RepositoryRuntimeRegistry runtimeRegistry,
      BlobStorageRegistry blobStorageRegistry,
      HelmHostedService helmHostedService,
      PypiHostedService pypiHostedService,
      YumService yumService,
      RubygemsService rubygemsService,
      PlatformTransactionManager transactionManager,
      @Value("${kkrepo.repository-index-rebuild.batch-size:16}") int batchSize,
      @Value("${kkrepo.repository-index-rebuild.enabled:true}") boolean enabled,
      KkRepoMetrics metrics) {
    this.dao = dao;
    this.runtimeRegistry = runtimeRegistry;
    this.blobStorageRegistry = blobStorageRegistry;
    this.helmHostedService = helmHostedService;
    this.pypiHostedService = pypiHostedService;
    this.yumService = yumService;
    this.rubygemsService = rubygemsService;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.metrics = metrics;
    this.batchSize = batchSize;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelayString = "${kkrepo.repository-index-rebuild.poll-interval-ms:1000}")
  public void drain() {
    if (!enabled) return;
    Timer.Sample batchSample = metrics.startTimer();
    try {
      transactionTemplate.executeWithoutResult(status -> runBatch());
      metrics.recordWorkerBatch("repository_index_rebuild", "success", batchSample);
    } catch (RuntimeException e) {
      metrics.recordWorkerBatch("repository_index_rebuild", "error", batchSample);
      log.warn("repository index rebuild batch failed; will retry next cycle", e);
    }
  }

  private void runBatch() {
    List<Claim> claims = dao.claim(batchSize);
    if (claims.isEmpty()) return;
    metrics.incrementWorkerItems("repository_index_rebuild", "claim", "claimed", claims.size());
    for (Claim claim : claims) {
      Timer.Sample itemSample = metrics.startTimer();
      String kind = claim.indexKind() == null ? "unknown" : claim.indexKind();
      try {
        execute(claim);
        metrics.recordWorkerItem("repository_index_rebuild", kind, "success", itemSample);
      } catch (RuntimeException e) {
        metrics.recordWorkerItem("repository_index_rebuild", kind, "error", itemSample);
        log.warn("repository index rebuild failed for repo={} kind={} scope={}",
            claim.repositoryId(), claim.indexKind(), claim.scopeKey(), e);
        dao.reenqueueFailure(claim, e);
      }
    }
  }

  private void execute(Claim claim) {
    RepositoryRuntime runtime = runtimeRegistry.resolveById(claim.repositoryId()).orElse(null);
    if (runtime == null || !runtime.isHosted() || runtime.blobStoreId() == null) {
      return;
    }
    switch (claim.indexKind()) {
      case RepositoryIndexRebuildDao.HELM_INDEX -> rebuildHelm(runtime);
      case RepositoryIndexRebuildDao.PYPI_ROOT -> rebuildPypiRoot(runtime);
      case RepositoryIndexRebuildDao.PYPI_PROJECT -> rebuildPypiProject(runtime, claim.scopeKey());
      case RepositoryIndexRebuildDao.YUM_METADATA -> rebuildYum(runtime);
      case RepositoryIndexRebuildDao.RUBYGEMS_METADATA -> rebuildRubygems(runtime);
      default -> log.warn("unknown repository index kind, dropping marker: {}", claim.indexKind());
    }
  }

  private void rebuildHelm(RepositoryRuntime runtime) {
    if (runtime.format() != RepositoryFormat.HELM) return;
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(runtime.blobStoreId());
    helmHostedService.rebuildIndex(runtime, storage, runtime.blobStoreId(), "system", null);
  }

  private void rebuildPypiRoot(RepositoryRuntime runtime) {
    if (runtime.format() != RepositoryFormat.PYPI) return;
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(runtime.blobStoreId());
    pypiHostedService.rebuildRootIndex(runtime, storage, runtime.blobStoreId(), "system", null);
  }

  private void rebuildPypiProject(RepositoryRuntime runtime, String scopeKey) {
    if (runtime.format() != RepositoryFormat.PYPI || scopeKey == null || scopeKey.isBlank()) return;
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(runtime.blobStoreId());
    pypiHostedService.rebuildProjectIndex(runtime, storage, runtime.blobStoreId(), scopeKey, "system", null);
  }

  private void rebuildYum(RepositoryRuntime runtime) {
    if (runtime.format() != RepositoryFormat.YUM) return;
    yumService.rebuildMetadata(runtime, "system", null);
  }

  private void rebuildRubygems(RepositoryRuntime runtime) {
    if (runtime.format() != RepositoryFormat.RUBYGEMS) return;
    rubygemsService.rebuildGeneratedMetadata(runtime);
  }
}
