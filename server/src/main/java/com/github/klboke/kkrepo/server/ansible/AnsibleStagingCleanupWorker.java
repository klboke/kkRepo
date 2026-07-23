package com.github.klboke.kkrepo.server.ansible;

import static com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao.TASK_COMPLETED;
import static com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao.TASK_FAILED;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
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
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reclaims Ansible publication staging assets left by a terminated request or replica.
 *
 * <p>The shared asset rows are claimed in bounded, age-gated batches with {@code SKIP LOCKED}, so
 * every replica may run the worker. Staging for a waiting or running durable import task is kept;
 * missing and terminal tasks are safe to unlink. Removing the asset only hands an unreferenced
 * blob to the global GC and never stores collection bytes in the database.
 */
@Component
final class AnsibleStagingCleanupWorker {
  static final String STAGING_PREFIX = ".ansible/staging/";
  private static final String DELETE_REASON = "abandoned Ansible publication staging asset";
  private static final Logger log = LoggerFactory.getLogger(AnsibleStagingCleanupWorker.class);

  private final RepositoryDao repositories;
  private final AnsibleGalaxyRegistryDao registry;
  private final AssetDao assets;
  private final BrowseNodeDao browse;
  private final AssetMetadataCache assetMetadataCache;
  private final TransactionTemplate transactionTemplate;
  private final KkRepoMetrics metrics;
  private final boolean enabled;
  private final int batchSize;
  private final long graceSeconds;

  AnsibleStagingCleanupWorker(
      RepositoryDao repositories,
      AnsibleGalaxyRegistryDao registry,
      AssetDao assets,
      BrowseNodeDao browse,
      AssetMetadataCache assetMetadataCache,
      PlatformTransactionManager transactionManager,
      KkRepoMetrics metrics,
      @Value("${kkrepo.ansible.staging-cleanup.enabled:true}") boolean enabled,
      @Value("${kkrepo.ansible.staging-cleanup.batch-size:64}") int batchSize,
      @Value("${kkrepo.ansible.staging-cleanup.grace-seconds:86400}") long graceSeconds) {
    this.repositories = repositories;
    this.registry = registry;
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
      fixedDelayString = "${kkrepo.ansible.staging-cleanup.interval-ms:300000}",
      initialDelayString = "${kkrepo.ansible.staging-cleanup.initial-delay-ms:120000}")
  void cleanup() {
    if (!enabled) return;
    Timer.Sample sample = metrics.startTimer();
    try {
      Instant updatedBefore = Instant.now().minusSeconds(graceSeconds);
      int cleaned = 0;
      for (RepositoryRecord repository : hostedRepositories()) {
        int remaining = batchSize - cleaned;
        if (remaining <= 0) break;
        Integer repositoryCleaned = transactionTemplate.execute(
            status -> cleanupRepository(repository.id(), updatedBefore, remaining));
        cleaned += repositoryCleaned == null ? 0 : repositoryCleaned;
      }
      metrics.incrementWorkerItems("ansible_cleanup", "staging_asset", "deleted", cleaned);
      metrics.recordWorkerBatch("ansible_staging_cleanup", "success", sample);
    } catch (RuntimeException error) {
      metrics.recordWorkerBatch("ansible_staging_cleanup", "error", sample);
      log.warn("Ansible staging cleanup failed; another replica will retry", error);
    }
  }

  private List<RepositoryRecord> hostedRepositories() {
    return repositories.list().stream()
        .filter(repository -> repository.id() != null)
        .filter(repository -> repository.format() == RepositoryFormat.ANSIBLEGALAXY)
        .filter(repository -> repository.type() == RepositoryType.HOSTED)
        .toList();
  }

  private int cleanupRepository(long repositoryId, Instant updatedBefore, int remaining) {
    int cleaned = 0;
    for (AssetRecord asset : assets.claimStaleAssetsByPrefix(
        repositoryId, STAGING_PREFIX, updatedBefore, remaining)) {
      if (!reclaimable(asset)) continue;
      browse.deleteByAssetId(asset.id());
      if (assets.deleteAssetById(asset.id()) != 1) continue;
      if (asset.assetBlobId() != null) {
        assets.markBlobDeletedIfUnreferenced(asset.assetBlobId(), DELETE_REASON);
      }
      assetMetadataCache.evictAfterCommit(repositoryId, asset.path());
      cleaned++;
    }
    return cleaned;
  }

  private boolean reclaimable(AssetRecord asset) {
    Optional<String> taskId = taskId(asset.path());
    if (taskId.isEmpty()) return true;
    return registry.findTask(taskId.get())
        .map(task -> TASK_COMPLETED.equals(task.state()) || TASK_FAILED.equals(task.state()))
        .orElse(true);
  }

  private static Optional<String> taskId(String path) {
    if (path == null || !path.startsWith(STAGING_PREFIX)) return Optional.empty();
    int end = path.indexOf('/', STAGING_PREFIX.length());
    if (end <= STAGING_PREFIX.length()) return Optional.empty();
    String value = path.substring(STAGING_PREFIX.length(), end);
    try {
      return Optional.of(UUID.fromString(value).toString());
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
  }
}
