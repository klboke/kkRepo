package com.github.klboke.kkrepo.server.swift;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.metrics.KkRepoMetrics;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unlinks Swift publication staging assets abandoned by a terminated request or replica.
 *
 * <p>The asset rows and their age are shared database truth. Each replica claims a bounded batch
 * with row locks and {@code SKIP LOCKED}, then removes the asset and marks its blob for the global
 * GC only when no other asset references it. A long grace period keeps active publications out of
 * the candidate set; losing a worker midway merely leaves the transaction for another cycle.
 */
@Component
final class SwiftStagingCleanupWorker {
  static final String STAGING_PREFIX = ".swift/staging/";
  private static final String DELETE_REASON = "abandoned Swift publication staging asset";
  private static final Logger log = LoggerFactory.getLogger(SwiftStagingCleanupWorker.class);

  private final RepositoryDao repositories;
  private final AssetDao assets;
  private final BrowseNodeDao browse;
  private final AssetMetadataCache assetMetadataCache;
  private final TransactionTemplate transactionTemplate;
  private final KkRepoMetrics metrics;
  private final boolean enabled;
  private final int batchSize;
  private final long graceSeconds;

  SwiftStagingCleanupWorker(
      RepositoryDao repositories,
      AssetDao assets,
      BrowseNodeDao browse,
      AssetMetadataCache assetMetadataCache,
      PlatformTransactionManager transactionManager,
      KkRepoMetrics metrics,
      @Value("${kkrepo.swift.staging-cleanup.enabled:true}") boolean enabled,
      @Value("${kkrepo.swift.staging-cleanup.batch-size:64}") int batchSize,
      @Value("${kkrepo.swift.staging-cleanup.grace-seconds:86400}") long graceSeconds) {
    this.repositories = repositories;
    this.assets = assets;
    this.browse = browse;
    this.assetMetadataCache = assetMetadataCache;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.metrics = metrics;
    this.enabled = enabled;
    this.batchSize = Math.max(1, batchSize);
    this.graceSeconds = Math.max(300, graceSeconds);
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.swift.staging-cleanup.interval-ms:300000}",
      initialDelayString = "${kkrepo.swift.staging-cleanup.initial-delay-ms:120000}")
  void cleanup() {
    if (!enabled) {
      return;
    }
    Timer.Sample sample = metrics.startTimer();
    try {
      Instant updatedBefore = Instant.now().minusSeconds(graceSeconds);
      int cleaned = 0;
      for (RepositoryRecord repository : swiftRepositories()) {
        int remaining = batchSize - cleaned;
        if (remaining <= 0) {
          break;
        }
        Integer repositoryCleaned = transactionTemplate.execute(
            status -> cleanupRepository(repository.id(), updatedBefore, remaining));
        cleaned += repositoryCleaned == null ? 0 : repositoryCleaned;
      }
      metrics.incrementWorkerItems("swift_cleanup", "staging_asset", "deleted", cleaned);
      metrics.recordWorkerBatch("swift_staging_cleanup", "success", sample);
    } catch (RuntimeException error) {
      metrics.recordWorkerBatch("swift_staging_cleanup", "error", sample);
      log.warn("Swift staging cleanup failed; another replica will retry", error);
    }
  }

  private List<RepositoryRecord> swiftRepositories() {
    return repositories.list().stream()
        .filter(repository -> repository.id() != null)
        .filter(repository -> repository.format() == RepositoryFormat.SWIFT)
        .toList();
  }

  private int cleanupRepository(long repositoryId, Instant updatedBefore, int remaining) {
    int cleaned = 0;
    for (AssetRecord asset : assets.claimStaleAssetsByPrefix(
        repositoryId, STAGING_PREFIX, updatedBefore, remaining)) {
      browse.deleteByAssetId(asset.id());
      if (assets.deleteAssetById(asset.id()) != 1) {
        continue;
      }
      if (asset.assetBlobId() != null) {
        assets.markBlobDeletedIfUnreferenced(asset.assetBlobId(), DELETE_REASON);
      }
      assetMetadataCache.evictAfterCommit(repositoryId, asset.path());
      cleaned++;
    }
    return cleaned;
  }
}
