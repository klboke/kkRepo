package com.github.klboke.kkrepo.server.r;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounds immutable R metadata snapshots while preserving in-flight package bindings. */
@Component
final class RSnapshotCleanupWorker {
  private static final Logger log = LoggerFactory.getLogger(RSnapshotCleanupWorker.class);

  private final RRegistryDao registry;
  private final RepositoryRuntimeRegistry runtimes;
  private final RAssetSupport assets;
  private final RLeaseManager leases;
  private final TransactionTemplate transactions;
  private final boolean enabled;
  private final int batchSize;
  private final int minSnapshots;
  private final long graceSeconds;

  RSnapshotCleanupWorker(
      RRegistryDao registry,
      RepositoryRuntimeRegistry runtimes,
      RAssetSupport assets,
      RLeaseManager leases,
      PlatformTransactionManager transactionManager,
      @Value("${kkrepo.r.snapshot-cleanup.enabled:true}") boolean enabled,
      @Value("${kkrepo.r.snapshot-cleanup.batch-size:32}") int batchSize,
      @Value("${kkrepo.r.snapshot-cleanup.min-snapshots:3}") int minSnapshots,
      @Value("${kkrepo.r.snapshot-cleanup.grace-seconds:86400}") long graceSeconds) {
    this.registry = registry;
    this.runtimes = runtimes;
    this.assets = assets;
    this.leases = leases;
    this.transactions = new TransactionTemplate(transactionManager);
    this.enabled = enabled;
    this.batchSize = Math.max(1, Math.min(batchSize, 256));
    // Keep current plus at least two previous generations for in-flight R clients.
    this.minSnapshots = Math.max(3, Math.min(minSnapshots, 100));
    this.graceSeconds = Math.max(0, graceSeconds);
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.r.snapshot-cleanup.interval-ms:300000}",
      initialDelayString = "${kkrepo.r.snapshot-cleanup.initial-delay-ms:120000}")
  void cleanup() {
    if (!enabled) return;
    Instant cutoff = Instant.now().minusSeconds(graceSeconds);
    for (RRegistryDao.Snapshot candidate : registry.listSnapshotCleanupCandidates(
        cutoff, minSnapshots, batchSize)) {
      cleanupOne(candidate, cutoff);
    }
    cleanupPackages(cutoff);
    registry.deleteOrphanGroupBindings(cutoff, batchSize);
  }

  private void cleanupOne(RRegistryDao.Snapshot candidate, Instant cutoff) {
    RepositoryRuntime runtime = runtimes.resolveById(candidate.repositoryId()).orElse(null);
    if (runtime == null || runtime.format() != RepositoryFormat.R) {
      return;
    }
    Optional<RLeaseManager.Lease> acquired = leases.tryAcquire(
        "r:publish:" + candidate.repositoryId() + ":" + candidate.distribution());
    if (acquired.isEmpty()) return;
    try (RLeaseManager.Lease lease = acquired.orElseThrow()) {
      lease.assertHeld();
      boolean retained = registry.listSnapshots(
              candidate.repositoryId(), candidate.distribution(), minSnapshots)
          .stream()
          .anyMatch(snapshot -> snapshot.revision() == candidate.revision());
      RRegistryDao.Snapshot current = registry.findSnapshot(
              candidate.repositoryId(), candidate.distribution(), candidate.revision())
          .orElse(null);
      if (retained || current == null || current.createdAt() == null
          || !current.createdAt().isBefore(cutoff)) {
        return;
      }
      LinkedHashSet<String> generatedPaths = new LinkedHashSet<>();
      current.manifest().forEach((key, value) -> {
        if (!key.startsWith("@")) generatedPaths.add(value);
      });
      Boolean deleted = transactions.execute(status -> {
        generatedPaths.forEach(path -> assets.delete(runtime, path));
        if (!registry.deleteSnapshot(
            current.repositoryId(), current.distribution(), current.revision())) {
          status.setRollbackOnly();
          return false;
        }
        return true;
      });
      if (Boolean.TRUE.equals(deleted)) {
        log.debug(
            "Deleted expired R snapshot repository={} distribution={} revision={} assets={}",
            runtime.name(), current.distribution(), current.revision(), generatedPaths.size());
      }
    } catch (RuntimeException error) {
      log.warn(
          "R snapshot cleanup failed; will retry repositoryId={} distribution={} revision={}",
          candidate.repositoryId(), candidate.distribution(), candidate.revision(), error);
    }
  }

  private void cleanupPackages(Instant cutoff) {
    for (RRegistryDao.PackageTombstone tombstone :
        registry.listPackageCleanupCandidates(cutoff, batchSize)) {
      RepositoryRuntime runtime = runtimes.resolveById(tombstone.repositoryId()).orElse(null);
      if (runtime == null || runtime.format() != RepositoryFormat.R || !runtime.isHosted()) {
        continue;
      }
      String leaseKey = "r:coordinate:" + tombstone.repositoryId() + ":"
          + HexFormat.of().formatHex(PersistenceHashes.sha256(
              tombstone.distribution(),
              tombstone.packageName(),
              tombstone.version(),
              tombstone.architecture()));
      Optional<RLeaseManager.Lease> acquired = leases.tryAcquire(leaseKey);
      if (acquired.isEmpty()) continue;
      try (RLeaseManager.Lease lease = acquired.orElseThrow()) {
        lease.assertHeld();
        Boolean deleted = transactions.execute(status -> {
          assets.delete(runtime, tombstone.path());
          if (!registry.deletePackageTombstone(tombstone)) {
            status.setRollbackOnly();
            return false;
          }
          return true;
        });
        if (Boolean.TRUE.equals(deleted)) {
          log.debug(
              "Deleted unreferenced R package tombstone repository={} path={} revision={}",
              runtime.name(), tombstone.path(), tombstone.revision());
        }
      } catch (RuntimeException error) {
        log.warn(
            "R package tombstone cleanup failed; will retry repositoryId={} path={} revision={}",
            tombstone.repositoryId(), tombstone.path(), tombstone.revision(), error);
      }
    }
  }
}
