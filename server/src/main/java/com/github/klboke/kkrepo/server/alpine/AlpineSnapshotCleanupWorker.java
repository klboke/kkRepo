package com.github.klboke.kkrepo.server.alpine;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
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

/** Bounds immutable Alpine metadata snapshots while preserving in-flight package bindings. */
@Component
final class AlpineSnapshotCleanupWorker {
  private static final Logger log = LoggerFactory.getLogger(AlpineSnapshotCleanupWorker.class);

  private final AlpineRegistryDao registry;
  private final RepositoryRuntimeRegistry runtimes;
  private final AlpineAssetSupport assets;
  private final AlpineLeaseManager leases;
  private final TransactionTemplate transactions;
  private final boolean enabled;
  private final int batchSize;
  private final int minSnapshots;
  private final long graceSeconds;

  AlpineSnapshotCleanupWorker(
      AlpineRegistryDao registry,
      RepositoryRuntimeRegistry runtimes,
      AlpineAssetSupport assets,
      AlpineLeaseManager leases,
      PlatformTransactionManager transactionManager,
      @Value("${kkrepo.alpine.snapshot-cleanup.enabled:true}") boolean enabled,
      @Value("${kkrepo.alpine.snapshot-cleanup.batch-size:32}") int batchSize,
      @Value("${kkrepo.alpine.snapshot-cleanup.min-snapshots:3}") int minSnapshots,
      @Value("${kkrepo.alpine.snapshot-cleanup.grace-seconds:86400}") long graceSeconds) {
    this.registry = registry;
    this.runtimes = runtimes;
    this.assets = assets;
    this.leases = leases;
    this.transactions = new TransactionTemplate(transactionManager);
    this.enabled = enabled;
    this.batchSize = Math.max(1, Math.min(batchSize, 256));
    // Keep current plus at least two previous generations for in-flight apk clients.
    this.minSnapshots = Math.max(3, Math.min(minSnapshots, 100));
    this.graceSeconds = Math.max(0, graceSeconds);
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.alpine.snapshot-cleanup.interval-ms:300000}",
      initialDelayString = "${kkrepo.alpine.snapshot-cleanup.initial-delay-ms:120000}")
  void cleanup() {
    if (!enabled) return;
    Instant cutoff = Instant.now().minusSeconds(graceSeconds);
    for (AlpineRegistryDao.Snapshot candidate : registry.listSnapshotCleanupCandidates(
        cutoff, minSnapshots, batchSize)) {
      cleanupOne(candidate, cutoff);
    }
    cleanupPackages(cutoff);
    registry.deleteOrphanGroupBindings(cutoff, batchSize);
  }

  private void cleanupOne(AlpineRegistryDao.Snapshot candidate, Instant cutoff) {
    RepositoryRuntime runtime = runtimes.resolveById(candidate.repositoryId()).orElse(null);
    if (runtime == null || runtime.format() != RepositoryFormat.ALPINE) {
      return;
    }
    Optional<AlpineLeaseManager.Lease> acquired = leases.tryAcquire(
        "alpine:publish:" + candidate.repositoryId() + ":" + candidate.distribution());
    if (acquired.isEmpty()) return;
    try (AlpineLeaseManager.Lease lease = acquired.orElseThrow()) {
      lease.assertHeld();
      boolean retained = registry.listSnapshots(
              candidate.repositoryId(), candidate.distribution(), minSnapshots)
          .stream()
          .anyMatch(snapshot -> snapshot.revision() == candidate.revision());
      AlpineRegistryDao.Snapshot current = registry.findSnapshot(
              candidate.repositoryId(), candidate.distribution(), candidate.revision())
          .orElse(null);
      if (retained || current == null || current.createdAt() == null
          || !current.createdAt().isBefore(cutoff)) {
        return;
      }
      LinkedHashSet<String> generatedPaths = new LinkedHashSet<>(current.manifest().values());
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
            "Deleted expired Alpine snapshot repository={} distribution={} revision={} assets={}",
            runtime.name(), current.distribution(), current.revision(), generatedPaths.size());
      }
    } catch (RuntimeException error) {
      log.warn(
          "Alpine snapshot cleanup failed; will retry repositoryId={} distribution={} revision={}",
          candidate.repositoryId(), candidate.distribution(), candidate.revision(), error);
    }
  }

  private void cleanupPackages(Instant cutoff) {
    for (AlpineRegistryDao.PackageTombstone tombstone :
        registry.listPackageCleanupCandidates(cutoff, batchSize)) {
      RepositoryRuntime runtime = runtimes.resolveById(tombstone.repositoryId()).orElse(null);
      if (runtime == null || runtime.format() != RepositoryFormat.ALPINE || !runtime.isHosted()) {
        continue;
      }
      String leaseKey = "alpine:coordinate:" + tombstone.repositoryId() + ":"
          + HexFormat.of().formatHex(PersistenceHashes.sha256(
              tombstone.distribution(),
              tombstone.component(),
              tombstone.packageName(),
              tombstone.version(),
              tombstone.architecture()));
      Optional<AlpineLeaseManager.Lease> acquired = leases.tryAcquire(leaseKey);
      if (acquired.isEmpty()) continue;
      try (AlpineLeaseManager.Lease lease = acquired.orElseThrow()) {
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
              "Deleted unreferenced Alpine package tombstone repository={} path={} revision={}",
              runtime.name(), tombstone.path(), tombstone.revision());
        }
      } catch (RuntimeException error) {
        log.warn(
            "Alpine package tombstone cleanup failed; will retry repositoryId={} path={} revision={}",
            tombstone.repositoryId(), tombstone.path(), tombstone.revision(), error);
      }
    }
  }
}
