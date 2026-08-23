package com.github.klboke.kkrepo.server.r;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class RSnapshotCleanupWorkerTest {
  private final RRegistryDao registry = mock(RRegistryDao.class);
  private final RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
  private final RAssetSupport assets = mock(RAssetSupport.class);
  private final RLeaseManager leases = mock(RLeaseManager.class);

  @Test
  void disabledCleanupDoesNotReadDurableQueues() {
    worker(false).cleanup();
    verify(registry, never()).listSnapshotCleanupCandidates(any(), anyInt(), anyInt());
    verify(registry, never()).listPackageCleanupCandidates(any(), anyInt());
    verify(registry, never()).deleteOrphanGroupBindings(any(), anyInt());
  }

  @Test
  void deletesExpiredUnretainedSnapshotAndGeneratedAssetsAtomically() {
    RRegistryDao.Snapshot candidate = snapshot(1L, 2L, Instant.EPOCH, Map.of(
        "src/contrib/PACKAGES.gz", ".r/2/index", "@members", "fingerprint"));
    RLeaseManager.Lease lease = mock(RLeaseManager.Lease.class);
    when(registry.listSnapshotCleanupCandidates(any(), eq(3), eq(32)))
        .thenReturn(List.of(candidate));
    when(runtimes.resolveById(1L)).thenReturn(Optional.of(runtime(1L, RepositoryType.HOSTED)));
    when(leases.tryAcquire("r:publish:1:src/contrib")).thenReturn(Optional.of(lease));
    when(registry.listSnapshots(1L, "src/contrib", 3)).thenReturn(List.of());
    when(registry.findSnapshot(1L, "src/contrib", 2L)).thenReturn(Optional.of(candidate));
    when(registry.deleteSnapshot(1L, "src/contrib", 2L)).thenReturn(true);

    worker(true).cleanup();

    verify(lease).assertHeld();
    verify(assets).delete(any(), eq(".r/2/index"));
    verify(assets, never()).delete(any(), eq("fingerprint"));
    verify(registry).deleteSnapshot(1L, "src/contrib", 2L);
    verify(registry).deleteOrphanGroupBindings(any(), eq(32));
    verify(lease).close();
  }

  @Test
  void retainsRecentOrNewestSnapshotsAndSkipsUnavailableRepositoriesAndLeases() {
    RRegistryDao.Snapshot retained = snapshot(1L, 3L, Instant.EPOCH, Map.of());
    RRegistryDao.Snapshot recent = snapshot(1L, 2L, Instant.now().plusSeconds(60), Map.of());
    RRegistryDao.Snapshot missingRuntime = snapshot(2L, 1L, Instant.EPOCH, Map.of());
    RRegistryDao.Snapshot wrongFormat = snapshot(3L, 1L, Instant.EPOCH, Map.of());
    RRegistryDao.Snapshot busy = snapshot(4L, 1L, Instant.EPOCH, Map.of());
    when(registry.listSnapshotCleanupCandidates(any(), anyInt(), anyInt()))
        .thenReturn(List.of(retained, recent, missingRuntime, wrongFormat, busy));
    when(runtimes.resolveById(1L)).thenReturn(Optional.of(runtime(1L, RepositoryType.HOSTED)));
    when(runtimes.resolveById(2L)).thenReturn(Optional.empty());
    when(runtimes.resolveById(3L)).thenReturn(Optional.of(new RepositoryRuntime(
        3L, "raw", RepositoryFormat.RAW, RepositoryType.HOSTED, "raw-hosted", true,
        1L, "ALLOW", null, null, true, null, 1, 1, true, null, List.of())));
    when(runtimes.resolveById(4L)).thenReturn(Optional.of(runtime(4L, RepositoryType.HOSTED)));
    RLeaseManager.Lease lease = mock(RLeaseManager.Lease.class);
    when(leases.tryAcquire("r:publish:1:src/contrib")).thenReturn(Optional.of(lease));
    when(leases.tryAcquire("r:publish:4:src/contrib")).thenReturn(Optional.empty());
    when(registry.listSnapshots(1L, "src/contrib", 3))
        .thenReturn(List.of(retained), List.of());
    when(registry.findSnapshot(1L, "src/contrib", 2L)).thenReturn(Optional.of(recent));

    worker(true).cleanup();

    verify(registry, never()).deleteSnapshot(any(Long.class), any(), any(Long.class));
  }

  @Test
  void deletesEligibleHostedPackageTombstoneAndSkipsOtherRepositoryTypes() {
    RRegistryDao.PackageTombstone hosted = tombstone(1L, "hosted.tar.gz");
    RRegistryDao.PackageTombstone proxy = tombstone(2L, "proxy.tar.gz");
    RRegistryDao.PackageTombstone missing = tombstone(3L, "missing.tar.gz");
    RRegistryDao.PackageTombstone busy = tombstone(4L, "busy.tar.gz");
    when(registry.listPackageCleanupCandidates(any(), eq(32)))
        .thenReturn(List.of(hosted, proxy, missing, busy));
    RepositoryRuntime hostedRuntime = runtime(1L, RepositoryType.HOSTED);
    when(runtimes.resolveById(1L)).thenReturn(Optional.of(hostedRuntime));
    when(runtimes.resolveById(2L)).thenReturn(Optional.of(runtime(2L, RepositoryType.PROXY)));
    when(runtimes.resolveById(3L)).thenReturn(Optional.empty());
    when(runtimes.resolveById(4L)).thenReturn(Optional.of(runtime(4L, RepositoryType.HOSTED)));
    RLeaseManager.Lease lease = mock(RLeaseManager.Lease.class);
    when(leases.tryAcquire(org.mockito.ArgumentMatchers.startsWith("r:coordinate:1:")))
        .thenReturn(Optional.of(lease));
    when(leases.tryAcquire(org.mockito.ArgumentMatchers.startsWith("r:coordinate:4:")))
        .thenReturn(Optional.empty());
    when(registry.deletePackageTombstone(hosted)).thenReturn(true);

    worker(true).cleanup();

    verify(assets).delete(hostedRuntime, "hosted.tar.gz");
    verify(registry).deletePackageTombstone(hosted);
    verify(assets, never()).delete(any(), eq("proxy.tar.gz"));
    verify(assets, never()).delete(any(), eq("busy.tar.gz"));
  }

  @Test
  void transientCleanupFailuresAreContainedForTheNextCycle() {
    RRegistryDao.Snapshot candidate = snapshot(
        1L, 2L, Instant.EPOCH, Map.of("index", "hidden"));
    RLeaseManager.Lease lease = mock(RLeaseManager.Lease.class);
    when(registry.listSnapshotCleanupCandidates(any(), anyInt(), anyInt()))
        .thenReturn(List.of(candidate));
    when(runtimes.resolveById(1L)).thenReturn(Optional.of(runtime(1L, RepositoryType.HOSTED)));
    when(leases.tryAcquire(any())).thenReturn(Optional.of(lease));
    doThrow(new IllegalStateException("lease lost")).when(lease).assertHeld();

    worker(true).cleanup();

    verify(registry, never()).deleteSnapshot(any(Long.class), any(), any(Long.class));
  }

  private RSnapshotCleanupWorker worker(boolean enabled) {
    return new RSnapshotCleanupWorker(
        registry, runtimes, assets, leases, new RecordingTransactionManager(), enabled,
        32, 1, 0);
  }

  private static RRegistryDao.Snapshot snapshot(
      long repositoryId, long revision, Instant createdAt, Map<String, String> manifest) {
    return new RRegistryDao.Snapshot(
        repositoryId, "src/contrib", revision, 1, manifest, "a".repeat(64), createdAt);
  }

  private static RRegistryDao.PackageTombstone tombstone(long repositoryId, String path) {
    return new RRegistryDao.PackageTombstone(
        repositoryId, "src/contrib", "source", "source", "demo", "1.0.0", path,
        "cleanup", 2L, Instant.EPOCH);
  }

  private static RepositoryRuntime runtime(long id, RepositoryType type) {
    return new RepositoryRuntime(
        id, "r-" + id, RepositoryFormat.R, type,
        "r-" + type.name().toLowerCase(), true, 1L, "ALLOW", null, null, true,
        type == RepositoryType.PROXY ? "https://example.invalid/" : null,
        60, 60, true, null, List.of());
  }

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition)
        throws TransactionException {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
    }
  }
}
