package com.github.klboke.kkrepo.server.apt;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AptSnapshotCleanupWorkerTest {

  @Test
  void deletesDeduplicatedGeneratedAssetsOutsideThreeSnapshotAndGraceWindow() {
    AptRegistryDao registry = mock(AptRegistryDao.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    AptAssetSupport assets = mock(AptAssetSupport.class);
    AptLeaseManager leases = mock(AptLeaseManager.class);
    AptLeaseManager.Lease lease = mock(AptLeaseManager.Lease.class);
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    RepositoryRuntime runtime = runtime();
    AptRegistryDao.Snapshot expired = snapshot(
        1,
        Map.of(
            "dists/stable/Packages", ".apt/snapshots/stable/1/Packages",
            "dists/stable/by-hash/SHA256/a", ".apt/snapshots/stable/1/Packages",
            "dists/stable/Release", ".apt/snapshots/stable/1/Release"),
        Instant.now().minusSeconds(172_800));
    when(registry.listSnapshotCleanupCandidates(any(), eq(3), eq(32)))
        .thenReturn(List.of(expired));
    when(runtimes.resolveById(runtime.id())).thenReturn(Optional.of(runtime));
    when(leases.tryAcquire("apt:publish:1:stable")).thenReturn(Optional.of(lease));
    when(registry.listSnapshots(1, "stable", 3)).thenReturn(List.of(
        snapshot(4, Map.of(), Instant.now()),
        snapshot(3, Map.of(), Instant.now()),
        snapshot(2, Map.of(), Instant.now())));
    when(registry.findSnapshot(1, "stable", 1)).thenReturn(Optional.of(expired));
    when(registry.deleteSnapshot(1, "stable", 1)).thenReturn(true);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

    new AptSnapshotCleanupWorker(
        registry, runtimes, assets, leases, transactions, true, 32, 1, 86_400)
        .cleanup();

    verify(assets, times(1)).delete(runtime, ".apt/snapshots/stable/1/Packages");
    verify(assets, times(1)).delete(runtime, ".apt/snapshots/stable/1/Release");
    verify(registry).deleteSnapshot(1, "stable", 1);
    verify(transactions).commit(any());
  }

  @Test
  void neverDeletesASnapshotThatMovedIntoTheRetainedWindow() {
    AptRegistryDao registry = mock(AptRegistryDao.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    AptAssetSupport assets = mock(AptAssetSupport.class);
    AptLeaseManager leases = mock(AptLeaseManager.class);
    AptLeaseManager.Lease lease = mock(AptLeaseManager.Lease.class);
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    AptRegistryDao.Snapshot candidate = snapshot(
        2, Map.of("Release", ".apt/snapshots/stable/2/Release"), Instant.EPOCH);
    when(registry.listSnapshotCleanupCandidates(any(), eq(3), eq(1)))
        .thenReturn(List.of(candidate));
    when(runtimes.resolveById(1)).thenReturn(Optional.of(runtime()));
    when(leases.tryAcquire("apt:publish:1:stable")).thenReturn(Optional.of(lease));
    when(registry.listSnapshots(1, "stable", 3)).thenReturn(List.of(candidate));

    new AptSnapshotCleanupWorker(
        registry, runtimes, assets, leases, transactions, true, 1, 3, 0)
        .cleanup();

    verify(assets, never()).delete(any(), any());
    verify(registry, never()).deleteSnapshot(anyLong(), any(), anyLong());
  }

  @Test
  void deletesPackageAssetOnlyAfterNoPublishedSnapshotPredatesItsTombstone() {
    AptRegistryDao registry = mock(AptRegistryDao.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    AptAssetSupport assets = mock(AptAssetSupport.class);
    AptLeaseManager leases = mock(AptLeaseManager.class);
    AptLeaseManager.Lease lease = mock(AptLeaseManager.Lease.class);
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    RepositoryRuntime runtime = runtime();
    AptRegistryDao.PackageTombstone tombstone = new AptRegistryDao.PackageTombstone(
        1,
        "stable",
        "main",
        "amd64",
        "demo",
        "1.0",
        "pool/d/demo/demo_1.0_amd64.deb",
        "cleanup",
        8,
        Instant.EPOCH);
    when(registry.listPackageCleanupCandidates(any(), eq(4))).thenReturn(List.of(tombstone));
    when(runtimes.resolveById(1)).thenReturn(Optional.of(runtime));
    when(leases.tryAcquire(any())).thenReturn(Optional.of(lease));
    when(registry.deletePackageTombstone(tombstone)).thenReturn(true);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

    new AptSnapshotCleanupWorker(
        registry, runtimes, assets, leases, transactions, true, 4, 3, 0)
        .cleanup();

    verify(assets).delete(runtime, tombstone.path());
    verify(registry).deletePackageTombstone(tombstone);
    verify(transactions).commit(any());
  }

  private static AptRegistryDao.Snapshot snapshot(
      long revision, Map<String, String> manifest, Instant createdAt) {
    return new AptRegistryDao.Snapshot(
        1, "stable", revision, 1, manifest, "a".repeat(64), createdAt);
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1,
        "apt",
        RepositoryFormat.APT,
        RepositoryType.HOSTED,
        "apt-hosted",
        true,
        1L,
        "ALLOW",
        null,
        null,
        true,
        null,
        null,
        null,
        null,
        null,
        List.of());
  }
}
