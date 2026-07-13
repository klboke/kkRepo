package com.github.klboke.kkrepo.server.docker;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.metrics.KkRepoMetrics;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciles Docker blob assets that are no longer referenced by live manifests.
 *
 * <p>Docker blob assets are content-addressed staging rows that become reachable only through
 * {@code docker_manifest_reference} or live manifest digest rows. The worker uses MySQL metadata as
 * the shared truth, so multiple replicas can run it without relying on process-local state. Actual
 * object deletion is delegated to the global blob GC after the asset row is removed.
 */
@Component
class DockerBlobCleanupWorker {
  private static final Logger log = LoggerFactory.getLogger(DockerBlobCleanupWorker.class);

  private final RepositoryDao repositoryDao;
  private final DockerRegistryDao dockerRegistryDao;
  private final AssetDao assetDao;
  private final TransactionTemplate transactionTemplate;
  private final KkRepoMetrics metrics;
  private final boolean enabled;
  private final int batchSize;
  private final int scanBatchSize;
  private final long graceSeconds;

  DockerBlobCleanupWorker(
      RepositoryDao repositoryDao,
      DockerRegistryDao dockerRegistryDao,
      AssetDao assetDao,
      PlatformTransactionManager transactionManager,
      KkRepoMetrics metrics,
      @Value("${kkrepo.docker.blob-cleanup.enabled:true}") boolean enabled,
      @Value("${kkrepo.docker.blob-cleanup.batch-size:64}") int batchSize,
      @Value("${kkrepo.docker.blob-cleanup.scan-batch-size:512}") int scanBatchSize,
      @Value("${kkrepo.docker.blob-cleanup.grace-seconds:3600}") long graceSeconds) {
    this.repositoryDao = repositoryDao;
    this.dockerRegistryDao = dockerRegistryDao;
    this.assetDao = assetDao;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.metrics = metrics;
    this.enabled = enabled;
    this.batchSize = Math.max(1, batchSize);
    this.scanBatchSize = Math.max(1, scanBatchSize);
    this.graceSeconds = Math.max(0, graceSeconds);
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.docker.blob-cleanup.interval-ms:300000}",
      initialDelayString = "${kkrepo.docker.blob-cleanup.initial-delay-ms:120000}")
  public void cleanup() {
    if (!enabled) {
      return;
    }
    Timer.Sample batchSample = metrics.startTimer();
    try {
      Instant updatedBefore = Instant.now().minusSeconds(graceSeconds);
      int cleaned = 0;
      for (RepositoryRecord repository : dockerRepositories()) {
        cleaned += cleanupRepository(repository.id(), updatedBefore, batchSize - cleaned);
        if (cleaned >= batchSize) {
          break;
        }
      }
      if (cleaned > 0) {
        metrics.incrementWorkerItems("docker_blob_cleanup", "blob_asset", "success", cleaned);
      }
      metrics.recordWorkerBatch("docker_blob_cleanup", "success", batchSample);
    } catch (RuntimeException e) {
      metrics.recordWorkerBatch("docker_blob_cleanup", "error", batchSample);
      log.warn("Docker blob cleanup batch failed; will retry next cycle", e);
    }
  }

  private List<RepositoryRecord> dockerRepositories() {
    return repositoryDao.list().stream()
        .filter(repository -> repository.id() != null)
        .filter(repository -> repository.format() == RepositoryFormat.DOCKER)
        .filter(RepositoryRecord::online)
        .toList();
  }

  private int cleanupRepository(long repositoryId, Instant updatedBefore, int remainingBudget) {
    int cleaned = 0;
    long afterAssetId = 0;
    while (cleaned < remainingBudget) {
      OptionalLong candidate = dockerRegistryDao.findUnreferencedBlobAssetIdForCleanup(
          repositoryId, afterAssetId, scanBatchSize, updatedBefore);
      if (candidate.isEmpty()) {
        break;
      }
      long assetId = candidate.getAsLong();
      afterAssetId = assetId;
      if (cleanupAsset(repositoryId, assetId, updatedBefore)) {
        cleaned++;
      }
    }
    return cleaned;
  }

  private boolean cleanupAsset(long repositoryId, long assetId, Instant updatedBefore) {
    return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
      OptionalLong stillCandidate = dockerRegistryDao.findUnreferencedBlobAssetIdForCleanup(
          repositoryId, Math.max(0, assetId - 1), 1, updatedBefore);
      if (stillCandidate.isEmpty() || stillCandidate.getAsLong() != assetId) {
        return false;
      }
      AssetRecord asset = assetDao.findAssetById(assetId)
          .filter(candidate -> candidate.repositoryId() == repositoryId)
          .filter(candidate -> candidate.assetBlobId() != null)
          .orElse(null);
      if (asset == null) {
        return false;
      }
      assetDao.deleteAssetById(asset.id());
      assetDao.markBlobDeletedIfUnreferenced(
          asset.assetBlobId(), "docker blob unreferenced by live manifests");
      return true;
    }));
  }
}
