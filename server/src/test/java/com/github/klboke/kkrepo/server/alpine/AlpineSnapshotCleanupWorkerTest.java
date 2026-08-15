package com.github.klboke.kkrepo.server.alpine;

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
import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
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

class AlpineSnapshotCleanupWorkerTest {
  private final AlpineRegistryDao registry = mock(AlpineRegistryDao.class);
  private final RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
  private final AlpineAssetSupport assets = mock(AlpineAssetSupport.class);
  private final AlpineLeaseManager leases = mock(AlpineLeaseManager.class);

  @Test
  void disabledCleanupDoesNotReadDurableQueues() {
    worker(false).cleanup();
    verify(registry, never()).listSnapshotCleanupCandidates(any(), anyInt(), anyInt());
    verify(registry, never()).listPackageCleanupCandidates(any(), anyInt());
  }

  @Test
  void deletesExpiredUnretainedSnapshotAndEveryGeneratedAssetAtomically() {
    AlpineRegistryDao.Snapshot candidate = snapshot(1L, 2L, Instant.EPOCH,
        Map.of("APKINDEX.tar.gz", ".alpine/2/index", "DESCRIPTION", ".alpine/2/desc"));
    AlpineLeaseManager.Lease lease = mock(AlpineLeaseManager.Lease.class);
    when(registry.listSnapshotCleanupCandidates(any(), eq(3), eq(32)))
        .thenReturn(List.of(candidate));
    when(runtimes.resolveById(1L)).thenReturn(Optional.of(runtime(1L, RepositoryType.HOSTED)));
    when(leases.tryAcquire("alpine:publish:1:v3.20/main/x86_64"))
        .thenReturn(Optional.of(lease));
    when(registry.listSnapshots(1L, "v3.20/main/x86_64", 3)).thenReturn(List.of());
    when(registry.findSnapshot(1L, "v3.20/main/x86_64", 2L))
        .thenReturn(Optional.of(candidate));
    when(registry.deleteSnapshot(1L, "v3.20/main/x86_64", 2L)).thenReturn(true);

    worker(true).cleanup();

    verify(lease).assertHeld();
    verify(assets).delete(any(), eq(".alpine/2/index"));
    verify(assets).delete(any(), eq(".alpine/2/desc"));
    verify(registry).deleteSnapshot(1L, "v3.20/main/x86_64", 2L);
    verify(lease).close();
  }

  @Test
  void retainsCurrentRecentOrNewestSnapshotsAndSkipsUnavailableRepositoriesAndLeases() {
    AlpineRegistryDao.Snapshot retained = snapshot(1L, 3L, Instant.EPOCH, Map.of());
    AlpineRegistryDao.Snapshot recent = snapshot(1L, 2L, Instant.now().plusSeconds(60), Map.of());
    AlpineRegistryDao.Snapshot missingRuntime = snapshot(2L, 1L, Instant.EPOCH, Map.of());
    AlpineRegistryDao.Snapshot wrongFormat = snapshot(3L, 1L, Instant.EPOCH, Map.of());
    AlpineRegistryDao.Snapshot busy = snapshot(4L, 1L, Instant.EPOCH, Map.of());
    when(registry.listSnapshotCleanupCandidates(any(), anyInt(), anyInt()))
        .thenReturn(List.of(retained, recent, missingRuntime, wrongFormat, busy));
    when(runtimes.resolveById(1L)).thenReturn(Optional.of(runtime(1L, RepositoryType.HOSTED)));
    when(runtimes.resolveById(2L)).thenReturn(Optional.empty());
    when(runtimes.resolveById(3L)).thenReturn(Optional.of(new RepositoryRuntime(
        3L, "raw", RepositoryFormat.RAW, RepositoryType.HOSTED, "raw-hosted", true,
        1L, "ALLOW", null, null, true, null, 1, 1, true, null, List.of())));
    when(runtimes.resolveById(4L)).thenReturn(Optional.of(runtime(4L, RepositoryType.HOSTED)));
    AlpineLeaseManager.Lease lease = mock(AlpineLeaseManager.Lease.class);
    when(leases.tryAcquire("alpine:publish:1:v3.20/main/x86_64"))
        .thenReturn(Optional.of(lease));
    when(leases.tryAcquire("alpine:publish:4:v3.20/main/x86_64"))
        .thenReturn(Optional.empty());
    when(registry.listSnapshots(1L, "v3.20/main/x86_64", 3))
        .thenReturn(List.of(retained), List.of());
    when(registry.findSnapshot(1L, "v3.20/main/x86_64", 2L))
        .thenReturn(Optional.of(recent));

    worker(true).cleanup();

    verify(registry, never()).deleteSnapshot(any(Long.class), any(), any(Long.class));
  }

  @Test
  void deletesEligibleHostedPackageTombstoneAndSkipsOtherRepositoryTypes() {
    AlpineRegistryDao.PackageTombstone hosted = tombstone(1L, "hosted.apk");
    AlpineRegistryDao.PackageTombstone proxy = tombstone(2L, "proxy.apk");
    AlpineRegistryDao.PackageTombstone missing = tombstone(3L, "missing.apk");
    AlpineRegistryDao.PackageTombstone busy = tombstone(4L, "busy.apk");
    when(registry.listPackageCleanupCandidates(any(), eq(32)))
        .thenReturn(List.of(hosted, proxy, missing, busy));
    RepositoryRuntime hostedRuntime = runtime(1L, RepositoryType.HOSTED);
    when(runtimes.resolveById(1L)).thenReturn(Optional.of(hostedRuntime));
    when(runtimes.resolveById(2L)).thenReturn(Optional.of(runtime(2L, RepositoryType.PROXY)));
    when(runtimes.resolveById(3L)).thenReturn(Optional.empty());
    when(runtimes.resolveById(4L)).thenReturn(Optional.of(runtime(4L, RepositoryType.HOSTED)));
    AlpineLeaseManager.Lease lease = mock(AlpineLeaseManager.Lease.class);
    when(leases.tryAcquire(org.mockito.ArgumentMatchers.startsWith("alpine:coordinate:1:")))
        .thenReturn(Optional.of(lease));
    when(leases.tryAcquire(org.mockito.ArgumentMatchers.startsWith("alpine:coordinate:4:")))
        .thenReturn(Optional.empty());
    when(registry.deletePackageTombstone(hosted)).thenReturn(true);

    worker(true).cleanup();

    verify(assets).delete(hostedRuntime, "hosted.apk");
    verify(registry).deletePackageTombstone(hosted);
    verify(assets, never()).delete(any(), eq("proxy.apk"));
    verify(assets, never()).delete(any(), eq("busy.apk"));
  }

  @Test
  void transientCleanupFailuresAreContainedForTheNextCycle() {
    AlpineRegistryDao.Snapshot candidate = snapshot(1L, 2L, Instant.EPOCH, Map.of("i", "hidden"));
    AlpineLeaseManager.Lease lease = mock(AlpineLeaseManager.Lease.class);
    when(registry.listSnapshotCleanupCandidates(any(), anyInt(), anyInt()))
        .thenReturn(List.of(candidate));
    when(runtimes.resolveById(1L)).thenReturn(Optional.of(runtime(1L, RepositoryType.HOSTED)));
    when(leases.tryAcquire(any())).thenReturn(Optional.of(lease));
    doThrow(new IllegalStateException("lease lost")).when(lease).assertHeld();

    worker(true).cleanup();

    verify(registry, never()).deleteSnapshot(any(Long.class), any(), any(Long.class));
  }

  private AlpineSnapshotCleanupWorker worker(boolean enabled) {
    return new AlpineSnapshotCleanupWorker(
        registry, runtimes, assets, leases, new RecordingTransactionManager(), enabled,
        32, 1, 0);
  }

  private static AlpineRegistryDao.Snapshot snapshot(
      long repositoryId, long revision, Instant createdAt, Map<String, String> manifest) {
    return new AlpineRegistryDao.Snapshot(
        repositoryId, "v3.20/main/x86_64", revision, 1, manifest, "a".repeat(64), createdAt);
  }

  private static AlpineRegistryDao.PackageTombstone tombstone(long repositoryId, String path) {
    return new AlpineRegistryDao.PackageTombstone(
        repositoryId, "v3.20/main/x86_64", "main", "x86_64", "demo", "1-r0", path,
        "cleanup", 2L, Instant.EPOCH);
  }

  private static RepositoryRuntime runtime(long id, RepositoryType type) {
    return new RepositoryRuntime(
        id, "alpine-" + id, RepositoryFormat.ALPINE, type,
        "alpine-" + type.name().toLowerCase(), true, 1L, "ALLOW", null, null, true,
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
