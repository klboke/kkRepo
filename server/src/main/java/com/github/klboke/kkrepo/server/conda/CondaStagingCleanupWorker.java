package com.github.klboke.kkrepo.server.conda;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CondaRegistryDao;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unlinks Conda publication staging assets abandoned by a terminated request or replica.
 *
 * <p>The candidate age and asset rows are shared database truth. Replicas claim bounded batches
 * with row locks and {@code SKIP LOCKED}; the global blob GC receives a blob only after the last
 * asset reference is removed. An interrupted cleanup remains safe for another replica or cycle.
 */
@Component
final class CondaStagingCleanupWorker {
  static final String STAGING_PREFIX = ".conda/staging/";
  static final String GENERATED_PREFIX = ".conda/generated/";
  private static final String DELETE_REASON = "abandoned Conda publication staging asset";
  private static final String GENERATED_DELETE_REASON = "expired Conda generated metadata";
  private static final Logger log = LoggerFactory.getLogger(CondaStagingCleanupWorker.class);

  private final RepositoryDao repositories;
  private final AssetDao assets;
  private final BrowseNodeDao browse;
  private final AssetMetadataCache assetMetadataCache;
  private final TransactionTemplate transactionTemplate;
  private final KkRepoMetrics metrics;
  private final boolean enabled;
  private final int batchSize;
  private final long graceSeconds;
  private final long generatedGraceSeconds;
  private final CondaRegistryDao registry;
  private final int leaseBatchSize;

  @Autowired
  CondaStagingCleanupWorker(
      RepositoryDao repositories,
      AssetDao assets,
      BrowseNodeDao browse,
      AssetMetadataCache assetMetadataCache,
      CondaRegistryDao registry,
      PlatformTransactionManager transactionManager,
      KkRepoMetrics metrics,
      @Value("${kkrepo.conda.staging-cleanup.enabled:true}") boolean enabled,
      @Value("${kkrepo.conda.staging-cleanup.batch-size:64}") int batchSize,
      @Value("${kkrepo.conda.staging-cleanup.grace-seconds:86400}") long graceSeconds,
      @Value("${kkrepo.conda.metadata.cleanup-grace-seconds:604800}") long generatedGraceSeconds,
      @Value("${kkrepo.conda.lease-cleanup.batch-size:256}") int leaseBatchSize) {
    this.repositories = repositories;
    this.assets = assets;
    this.browse = browse;
    this.assetMetadataCache = assetMetadataCache;
    this.registry = registry;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.metrics = metrics;
    this.enabled = enabled;
    this.batchSize = Math.max(1, batchSize);
    this.graceSeconds = Math.max(300, graceSeconds);
    this.generatedGraceSeconds = Math.max(3600, generatedGraceSeconds);
    this.leaseBatchSize = Math.max(1, leaseBatchSize);
  }

  CondaStagingCleanupWorker(
      RepositoryDao repositories,
      AssetDao assets,
      BrowseNodeDao browse,
      AssetMetadataCache assetMetadataCache,
      PlatformTransactionManager transactionManager,
      KkRepoMetrics metrics,
      boolean enabled,
      int batchSize,
      long graceSeconds) {
    this(
        repositories, assets, browse, assetMetadataCache, null, transactionManager, metrics,
        enabled, batchSize, graceSeconds, 604_800, 256);
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.conda.staging-cleanup.interval-ms:300000}",
      initialDelayString = "${kkrepo.conda.staging-cleanup.initial-delay-ms:120000}")
  void cleanup() {
    if (!enabled) {
      return;
    }
    Timer.Sample sample = metrics.startTimer();
    try {
      List<RepositoryRecord> condaRepositories = condaRepositories();
      Instant stagingBefore = Instant.now().minusSeconds(graceSeconds);
      int stagingCleaned = 0;
      for (RepositoryRecord repository : condaRepositories) {
        if (repository.type() != RepositoryType.HOSTED) continue;
        int remaining = batchSize - stagingCleaned;
        if (remaining <= 0) {
          break;
        }
        Integer repositoryCleaned = transactionTemplate.execute(
            status -> cleanupPrefix(
                repository.id(), STAGING_PREFIX, stagingBefore, remaining, DELETE_REASON, false));
        stagingCleaned += repositoryCleaned == null ? 0 : repositoryCleaned;
      }
      Instant generatedBefore = Instant.now().minusSeconds(generatedGraceSeconds);
      int generatedCleaned = 0;
      for (RepositoryRecord repository : condaRepositories) {
        int remaining = batchSize - generatedCleaned;
        if (remaining <= 0) break;
        Integer repositoryCleaned = transactionTemplate.execute(
            status -> cleanupPrefix(
                repository.id(), GENERATED_PREFIX, generatedBefore, remaining,
                GENERATED_DELETE_REASON, true));
        generatedCleaned += repositoryCleaned == null ? 0 : repositoryCleaned;
      }
      int leases = registry == null ? 0 : registry.deleteExpiredLeases(
          Instant.now().minusSeconds(300), leaseBatchSize);
      metrics.incrementWorkerItems(
          "conda_cleanup", "staging_asset", "deleted", stagingCleaned);
      metrics.incrementWorkerItems(
          "conda_cleanup", "generated_metadata", "deleted", generatedCleaned);
      metrics.incrementWorkerItems(
          "conda_cleanup", "coordinate_lease", "deleted", leases);
      metrics.recordWorkerBatch("conda_staging_cleanup", "success", sample);
    } catch (RuntimeException error) {
      metrics.recordWorkerBatch("conda_staging_cleanup", "error", sample);
      log.warn("Conda staging cleanup failed; another replica will retry", error);
    }
  }

  private List<RepositoryRecord> condaRepositories() {
    return repositories.list().stream()
        .filter(repository -> repository.id() != null)
        .filter(repository -> repository.format() == RepositoryFormat.CONDA)
        .toList();
  }

  private int cleanupPrefix(
      long repositoryId,
      String prefix,
      Instant updatedBefore,
      int remaining,
      String deleteReason,
      boolean protectCurrentRevision) {
    int cleaned = 0;
    List<AssetRecord> candidates = assets.claimStaleAssetsByPrefix(
        repositoryId, prefix, updatedBefore, remaining);
    Long protectedRevision = protectCurrentRevision && registry != null && !candidates.isEmpty()
        ? registry.currentRepositoryRevision(repositoryId)
        : null;
    for (AssetRecord asset : candidates) {
      if (protectedRevision != null && generatedAtRevision(asset, protectedRevision)) {
        assets.touchAssetLastUpdated(asset.id(), Instant.now());
        assetMetadataCache.evictAfterCommit(repositoryId, asset.path());
        continue;
      }
      browse.deleteByAssetId(asset.id());
      if (assets.deleteAssetById(asset.id()) != 1) {
        continue;
      }
      if (asset.assetBlobId() != null) {
        assets.markBlobDeletedIfUnreferenced(asset.assetBlobId(), deleteReason);
      }
      assetMetadataCache.evictAfterCommit(repositoryId, asset.path());
      cleaned++;
    }
    return cleaned;
  }

  private static boolean generatedAtRevision(AssetRecord asset, long currentRevision) {
    Object raw = asset.attributes() == null ? null : asset.attributes().get("condaRevision");
    if (raw instanceof Number number) return number.longValue() == currentRevision;
    if (raw == null) return false;
    try {
      return Long.parseLong(raw.toString()) == currentRevision;
    } catch (NumberFormatException ignored) {
      return false;
    }
  }
}
