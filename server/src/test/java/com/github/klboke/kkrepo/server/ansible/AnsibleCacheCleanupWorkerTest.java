package com.github.klboke.kkrepo.server.ansible;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import com.github.klboke.kkrepo.server.metrics.KkRepoMetrics;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AnsibleCacheCleanupWorkerTest {

  @Test
  void drainsBoundedBatchesAndRecordsDeletedRows() {
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    KkRepoMetrics metrics = mock(KkRepoMetrics.class);
    when(registry.deleteExpiredProxyCache(any(), any(), eq(2))).thenReturn(2, 1);
    when(registry.deleteTerminalTasksBefore(any(), eq(2))).thenReturn(0);
    when(registry.deleteExpiredLeasesBefore(any(), eq(2))).thenReturn(2, 2);

    new AnsibleCacheCleanupWorker(
        registry, metrics, true, 2, 2, 24, 30, 7).cleanup();

    verify(registry, org.mockito.Mockito.times(2))
        .deleteExpiredProxyCache(any(), any(), eq(2));
    verify(registry).deleteTerminalTasksBefore(any(), eq(2));
    verify(registry, org.mockito.Mockito.times(2)).deleteExpiredLeasesBefore(any(), eq(2));
    verify(metrics).incrementWorkerItems(
        "ansible_cleanup", "proxy_cache", "deleted", 3);
    verify(metrics).incrementWorkerItems(
        "ansible_cleanup", "terminal_task", "deleted", 0);
    verify(metrics).incrementWorkerItems(
        "ansible_cleanup", "expired_lease", "deleted", 4);
  }

  @Test
  void disabledWorkerLeavesSharedStateUntouched() {
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    KkRepoMetrics metrics = mock(KkRepoMetrics.class);

    new AnsibleCacheCleanupWorker(
        registry, metrics, false, 0, 0, 0, 0, 0).cleanup();

    verifyNoInteractions(registry, metrics);
  }

  @Test
  void transientFailureIsLeftForAnotherReplicaOrCycle() {
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    doThrow(new IllegalStateException("database unavailable"))
        .when(registry).deleteExpiredProxyCache(any(Instant.class), any(Instant.class), eq(1));

    new AnsibleCacheCleanupWorker(
        registry, mock(KkRepoMetrics.class), true, 0, 0, 0, 0, 0).cleanup();

    verify(registry).deleteExpiredProxyCache(any(Instant.class), any(Instant.class), eq(1));
    verify(registry, never()).deleteTerminalTasksBefore(any(), anyInt());
  }
}
