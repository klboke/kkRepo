package com.github.klboke.kkrepo.server.apt;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
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

/** Bounds immutable APT metadata snapshots while preserving by-hash client safety. */
@Component
final class AptSnapshotCleanupWorker {
  private static final Logger log = LoggerFactory.getLogger(AptSnapshotCleanupWorker.class);

  private final AptRegistryDao registry;
  private final RepositoryRuntimeRegistry runtimes;
  private final AptAssetSupport assets;
  private final AptLeaseManager leases;
  private final TransactionTemplate transactions;
  private final boolean enabled;
  private final int batchSize;
  private final int minSnapshots;
  private final long graceSeconds;

  AptSnapshotCleanupWorker(
      AptRegistryDao registry,
      RepositoryRuntimeRegistry runtimes,
      AptAssetSupport assets,
      AptLeaseManager leases,
      PlatformTransactionManager transactionManager,
      @Value("${kkrepo.apt.snapshot-cleanup.enabled:true}") boolean enabled,
      @Value("${kkrepo.apt.snapshot-cleanup.batch-size:32}") int batchSize,
      @Value("${kkrepo.apt.snapshot-cleanup.min-snapshots:3}") int minSnapshots,
      @Value("${kkrepo.apt.snapshot-cleanup.grace-seconds:86400}") long graceSeconds) {
    this.registry = registry;
    this.runtimes = runtimes;
    this.assets = assets;
    this.leases = leases;
    this.transactions = new TransactionTemplate(transactionManager);
    this.enabled = enabled;
    this.batchSize = Math.max(1, Math.min(batchSize, 256));
    // Acquire-By-Hash requires current plus at least two previous versions to remain available.
    this.minSnapshots = Math.max(3, Math.min(minSnapshots, 100));
    this.graceSeconds = Math.max(0, graceSeconds);
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.apt.snapshot-cleanup.interval-ms:300000}",
      initialDelayString = "${kkrepo.apt.snapshot-cleanup.initial-delay-ms:120000}")
  void cleanup() {
    if (!enabled) return;
    Instant cutoff = Instant.now().minusSeconds(graceSeconds);
    for (AptRegistryDao.Snapshot candidate : registry.listSnapshotCleanupCandidates(
        cutoff, minSnapshots, batchSize)) {
      cleanupOne(candidate, cutoff);
    }
    cleanupPackages(cutoff);
  }

  private void cleanupOne(AptRegistryDao.Snapshot candidate, Instant cutoff) {
    RepositoryRuntime runtime = runtimes.resolveById(candidate.repositoryId()).orElse(null);
    if (runtime == null || runtime.format() != RepositoryFormat.APT || runtime.isGroup()) {
      return;
    }
    Optional<AptLeaseManager.Lease> acquired = leases.tryAcquire(
        "apt:publish:" + candidate.repositoryId() + ":" + candidate.distribution());
    if (acquired.isEmpty()) return;
    try (AptLeaseManager.Lease lease = acquired.orElseThrow()) {
      lease.assertHeld();
      boolean retained = registry.listSnapshots(
              candidate.repositoryId(), candidate.distribution(), minSnapshots)
          .stream()
          .anyMatch(snapshot -> snapshot.revision() == candidate.revision());
      AptRegistryDao.Snapshot current = registry.findSnapshot(
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
            "Deleted expired APT snapshot repository={} distribution={} revision={} assets={}",
            runtime.name(), current.distribution(), current.revision(), generatedPaths.size());
      }
    } catch (RuntimeException error) {
      log.warn(
          "APT snapshot cleanup failed; will retry repositoryId={} distribution={} revision={}",
          candidate.repositoryId(), candidate.distribution(), candidate.revision(), error);
    }
  }

  private void cleanupPackages(Instant cutoff) {
    for (AptRegistryDao.PackageTombstone tombstone :
        registry.listPackageCleanupCandidates(cutoff, batchSize)) {
      RepositoryRuntime runtime = runtimes.resolveById(tombstone.repositoryId()).orElse(null);
      if (runtime == null || runtime.format() != RepositoryFormat.APT || !runtime.isHosted()) {
        continue;
      }
      String leaseKey = "apt:coordinate:" + tombstone.repositoryId() + ":"
          + HexFormat.of().formatHex(PersistenceHashes.sha256(
              tombstone.distribution(),
              tombstone.component(),
              tombstone.packageName(),
              tombstone.version(),
              tombstone.architecture()));
      Optional<AptLeaseManager.Lease> acquired = leases.tryAcquire(leaseKey);
      if (acquired.isEmpty()) continue;
      try (AptLeaseManager.Lease lease = acquired.orElseThrow()) {
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
              "Deleted unreferenced APT package tombstone repository={} path={} revision={}",
              runtime.name(), tombstone.path(), tombstone.revision());
        }
      } catch (RuntimeException error) {
        log.warn(
            "APT package tombstone cleanup failed; will retry repositoryId={} path={} revision={}",
            tombstone.repositoryId(), tombstone.path(), tombstone.revision(), error);
      }
    }
  }
}
