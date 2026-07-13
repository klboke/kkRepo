package com.github.klboke.kkrepo.server.maven;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.MetadataRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.MetadataRebuildDao.Claim;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.maven.path.Coordinates;
import com.github.klboke.kkrepo.server.metrics.KkRepoMetrics;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drains {@code metadata_rebuild_marker} on a fixed cadence and runs the GA / GAV-SNAPSHOT
 * rebuild for each claim. Multiple replicas can run concurrently — the {@code FOR UPDATE SKIP
 * LOCKED} clause in {@link MetadataRebuildDao#claim(int)} partitions work without coordination.
 *
 * <p>Each batch runs in its own transaction so that a single failed rebuild rolls back the marker
 * delete for that batch (re-queueing it) without losing earlier successful work in another batch.
 * Within a batch we tolerate per-item failures by logging and continuing; the next enqueue from a
 * subsequent PUT will re-cover the failed scope.
 */
@Component
public class MetadataRebuildWorker {
  private static final Logger log = LoggerFactory.getLogger(MetadataRebuildWorker.class);

  private final AssetDao assetDao;
  private final MetadataRebuildDao dao;
  private final MavenMetadataService metadataService;
  private final RepositoryRuntimeRegistry runtimeRegistry;
  private final BlobStorageRegistry blobStorageRegistry;
  private final TransactionTemplate transactionTemplate;
  private final KkRepoMetrics metrics;
  private final int batchSize;
  private final boolean enabled;

  public MetadataRebuildWorker(
      AssetDao assetDao,
      MetadataRebuildDao dao,
      MavenMetadataService metadataService,
      RepositoryRuntimeRegistry runtimeRegistry,
      BlobStorageRegistry blobStorageRegistry,
      PlatformTransactionManager transactionManager,
      @Value("${kkrepo.maven.metadata-rebuild.batch-size:32}") int batchSize,
      @Value("${kkrepo.maven.metadata-rebuild.enabled:true}") boolean enabled,
      KkRepoMetrics metrics) {
    this.assetDao = assetDao;
    this.dao = dao;
    this.metadataService = metadataService;
    this.runtimeRegistry = runtimeRegistry;
    this.blobStorageRegistry = blobStorageRegistry;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.metrics = metrics;
    this.batchSize = batchSize;
    this.enabled = enabled;
  }

  /**
   * Polled every {@value 1000}ms (configurable elsewhere if needed) — short enough that a Maven
   * client doing back-to-back deploy + dependency:get sees the new metadata within a second, but
   * long enough that the queue collapses bursts.
   */
  @Scheduled(fixedDelayString = "${kkrepo.maven.metadata-rebuild.poll-interval-ms:1000}")
  public void drain() {
    if (!enabled) return;
    Timer.Sample batchSample = metrics.startTimer();
    try {
      transactionTemplate.executeWithoutResult(status -> runBatch());
      metrics.recordWorkerBatch("metadata_rebuild", "success", batchSample);
    } catch (RuntimeException e) {
      metrics.recordWorkerBatch("metadata_rebuild", "error", batchSample);
      log.warn("metadata rebuild batch failed; will retry next cycle", e);
    }
  }

  private void runBatch() {
    List<Claim> claims = dao.claim(batchSize);
    if (claims.isEmpty()) return;
    metrics.incrementWorkerItems("metadata_rebuild", "claim", "claimed", claims.size());
    for (Claim claim : claims) {
      Timer.Sample itemSample = metrics.startTimer();
      String kind = kind(claim.scopeKey());
      try {
        execute(claim);
        metrics.recordWorkerItem("metadata_rebuild", kind, "success", itemSample);
      } catch (RuntimeException e) {
        metrics.recordWorkerItem("metadata_rebuild", kind, "error", itemSample);
        log.warn("metadata rebuild failed for repo={} scope={}", claim.repositoryId(), claim.scopeKey(), e);
        // Re-enqueue so it isn't lost when this transaction commits (the row is being deleted).
        dao.reenqueueFailure(claim, e);
      }
    }
  }

  private void execute(Claim claim) {
    MetadataRebuildScope.Parsed scope = MetadataRebuildScope.parse(claim.scopeKey());
    if (scope == null) {
      log.warn("unknown scope key, dropping: {}", claim.scopeKey());
      return;
    }
    RepositoryRuntime runtime = runtimeRegistry.resolveById(claim.repositoryId()).orElse(null);
    if (runtime == null) {
      // repository was deleted; the marker FK is ON DELETE CASCADE so this is rare — just skip.
      return;
    }
    Long blobStoreId = runtime.blobStoreId();
    if (blobStoreId == null) return;
    if (explicitMetadataIsNewerThanClaim(runtime, scope, claim)) return;
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(blobStoreId);
    switch (scope.kind()) {
      case GA -> metadataService.rebuildGa(runtime, storage, blobStoreId,
          scope.groupId(), scope.artifactId(), "system", null);
      case GAV -> {
        // Synthetic coords — rebuildBaseVersionIfSnapshot only reads groupId/artifactId/
        // baseVersion/snapshot from this; everything else is recomputed from the assets it
        // discovers under that GAV.
        Coordinates synthetic = new Coordinates(
            true, scope.groupId(), scope.artifactId(), scope.baseVersion(),
            null, null, scope.baseVersion(), null, null, null);
        metadataService.rebuildBaseVersionIfSnapshot(runtime, storage, blobStoreId,
            synthetic, "system", null);
      }
    }
  }

  private boolean explicitMetadataIsNewerThanClaim(
      RepositoryRuntime runtime, MetadataRebuildScope.Parsed scope, Claim claim) {
    String metadataPath = metadataPath(scope);
    AssetRecord asset = assetDao.findAssetByPath(runtime.id(), metadataPath).orElse(null);
    return asset != null
        && asset.lastUpdatedAt() != null
        && asset.lastUpdatedAt().isAfter(claim.requestedAt());
  }

  private String metadataPath(MetadataRebuildScope.Parsed scope) {
    String base = scope.groupId().replace('.', '/') + "/" + scope.artifactId();
    if (scope.kind() == MetadataRebuildScope.Kind.GAV) {
      return base + "/" + scope.baseVersion() + "/maven-metadata.xml";
    }
    return base + "/maven-metadata.xml";
  }

  private static String kind(String scopeKey) {
    MetadataRebuildScope.Parsed scope = MetadataRebuildScope.parse(scopeKey);
    return scope == null ? "unknown" : scope.kind().name().toLowerCase(java.util.Locale.ROOT);
  }
}
