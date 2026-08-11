package com.github.klboke.kkrepo.server.conan;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ConanRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.metrics.KkRepoMetrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded, multi-replica cleanup for Conan upload sessions, tokens, leases, and proxy staging. */
@Component
final class ConanStateCleanupWorker {
  private static final Logger log = LoggerFactory.getLogger(ConanStateCleanupWorker.class);
  private static final String PROXY_STAGING_PREFIX = ".conan/proxy-staging/";
  private static final String STAGING_DELETE_REASON = "abandoned Conan staging asset";

  private final ConanRegistryDao registry;
  private final RepositoryDao repositories;
  private final AssetDao assets;
  private final BrowseNodeDao browse;
  private final AssetMetadataCache assetCache;
  private final KkRepoMetrics metrics;
  private final TransactionTemplate transactions;
  private final int batchSize;
  private final int maxItemsPerRun;
  private final Duration claimTtl;
  private final Duration proxyStagingGrace;
  private final boolean enabled;
  private final String nodeId = UUID.randomUUID().toString();

  ConanStateCleanupWorker(
      ConanRegistryDao registry,
      RepositoryDao repositories,
      AssetDao assets,
      BrowseNodeDao browse,
      AssetMetadataCache assetCache,
      KkRepoMetrics metrics,
      PlatformTransactionManager transactionManager,
      @Value("${kkrepo.conan.cleanup.enabled:true}") boolean enabled,
      @Value("${kkrepo.conan.cleanup.batch-size:128}") int batchSize,
      @Value("${kkrepo.conan.cleanup.max-items-per-run:4096}") int maxItemsPerRun,
      @Value("${kkrepo.conan.cleanup.claim-seconds:300}") long claimSeconds,
      @Value("${kkrepo.conan.proxy-staging-cleanup.grace-seconds:86400}")
          long proxyStagingGraceSeconds) {
    this.registry = registry;
    this.repositories = repositories;
    this.assets = assets;
    this.browse = browse;
    this.assetCache = assetCache;
    this.metrics = metrics;
    this.transactions = new TransactionTemplate(transactionManager);
    this.enabled = enabled;
    this.batchSize = Math.max(1, Math.min(10_000, batchSize));
    this.maxItemsPerRun = Math.max(1, Math.min(1_000_000, maxItemsPerRun));
    this.claimTtl = Duration.ofSeconds(Math.max(30, claimSeconds));
    this.proxyStagingGrace = Duration.ofSeconds(Math.max(300, proxyStagingGraceSeconds));
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.conan.cleanup.interval-ms:300000}",
      initialDelayString = "${kkrepo.conan.cleanup.initial-delay-ms:120000}")
  void cleanup() {
    if (!enabled) return;
    Timer.Sample sample = metrics.startTimer();
    try {
      Instant now = Instant.now();
      int sessions = cleanupSessions(now);
      int tokens = drain(() -> registry.deleteExpiredAuthTokens(now, batchSize));
      // Keeping an expired lease for a grace window preserves monotonic fencing while any old
      // worker could still be alive. It is safe to remove only after that window.
      int leases = drain(() -> registry.deleteExpiredLeases(now.minus(claimTtl), batchSize));
      int proxyAssets = cleanupProxyStaging(now.minus(proxyStagingGrace));
      metrics.incrementWorkerItems("conan_cleanup", "upload_session", "deleted", sessions);
      metrics.incrementWorkerItems("conan_cleanup", "auth_token", "deleted", tokens);
      metrics.incrementWorkerItems("conan_cleanup", "coordinate_lease", "deleted", leases);
      metrics.incrementWorkerItems("conan_cleanup", "proxy_staging_asset", "deleted", proxyAssets);
      metrics.recordWorkerBatch("conan_state_cleanup", "success", sample);
    } catch (RuntimeException failure) {
      metrics.recordWorkerBatch("conan_state_cleanup", "error", sample);
      log.warn("Conan state cleanup failed; another replica will retry", failure);
    }
  }

  private int cleanupSessions(Instant now) {
    int cleaned = 0;
    while (cleaned < maxItemsPerRun) {
      int requested = Math.min(batchSize, maxItemsPerRun - cleaned);
      List<ConanRegistryDao.UploadSession> claimed = registry.claimExpiredUploadSessions(
          nodeId, now, now.plus(claimTtl), requested);
      if (claimed.isEmpty()) break;
      for (ConanRegistryDao.UploadSession session : claimed) {
        Boolean deleted = transactions.execute(status -> deleteClaimedSession(session));
        if (Boolean.TRUE.equals(deleted)) cleaned++;
      }
      if (claimed.size() < requested) break;
    }
    return cleaned;
  }

  private boolean deleteClaimedSession(ConanRegistryDao.UploadSession session) {
    List<Long> stagingAssets = registry.listUploadFiles(session.id()).stream()
        .map(ConanRegistryDao.UploadFile::stagingAssetId)
        .toList();
    if (!registry.deleteClaimedUploadSession(
        session.id(), session.owner(), session.fencingToken())) {
      return false;
    }
    for (Long assetId : stagingAssets) deleteAsset(session.repositoryId(), assetId);
    return true;
  }

  private int cleanupProxyStaging(Instant cutoff) {
    int cleaned = 0;
    var repositoryIds = repositories.list().stream()
        .filter(row -> row.id() != null && row.format() == RepositoryFormat.CONAN)
        .map(row -> row.id())
        .toList();
    for (Long repositoryId : repositoryIds) {
      if (cleaned >= maxItemsPerRun) break;
      int remaining = Math.min(batchSize, maxItemsPerRun - cleaned);
      Integer repositoryCleaned = transactions.execute(
          status -> cleanupProxyStagingRepository(repositoryId, cutoff, remaining));
      cleaned += repositoryCleaned == null ? 0 : repositoryCleaned;
    }
    return cleaned;
  }

  private int cleanupProxyStagingRepository(long repositoryId, Instant cutoff, int limit) {
    int cleaned = 0;
    for (AssetRecord candidate : assets.claimStaleAssetsByPrefix(
        repositoryId, PROXY_STAGING_PREFIX, cutoff, limit)) {
      if (deleteAsset(repositoryId, candidate.id())) cleaned++;
    }
    return cleaned;
  }

  private boolean deleteAsset(long repositoryId, long assetId) {
    AssetRecord asset = assets.findAssetById(assetId).orElse(null);
    if (asset == null || asset.repositoryId() != repositoryId) return false;
    browse.deleteByAssetId(asset.id());
    if (assets.deleteAssetById(asset.id()) != 1) return false;
    if (asset.assetBlobId() != null) {
      assets.markBlobDeletedIfUnreferenced(asset.assetBlobId(), STAGING_DELETE_REASON);
    }
    assetCache.evictAfterCommit(repositoryId, asset.path());
    return true;
  }

  private int drain(java.util.function.IntSupplier operation) {
    int total = 0;
    while (total < maxItemsPerRun) {
      int deleted = transactions.execute(status -> operation.getAsInt());
      if (deleted <= 0) break;
      total += deleted;
      if (deleted < batchSize) break;
    }
    return total;
  }
}
