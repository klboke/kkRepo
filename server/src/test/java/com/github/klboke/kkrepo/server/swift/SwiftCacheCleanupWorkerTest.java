package com.github.klboke.kkrepo.server.swift;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import com.github.klboke.kkrepo.server.metrics.KkRepoMetrics;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SwiftCacheCleanupWorkerTest {

  @Test
  void everyReplicaCanIdempotentlyDeleteExpiredSharedCacheRows() {
    SwiftRegistryDao registry = mock(SwiftRegistryDao.class);
    KkRepoMetrics metrics = mock(KkRepoMetrics.class);
    when(registry.deleteExpiredNegativeCache(any(Instant.class))).thenReturn(3);

    new SwiftCacheCleanupWorker(registry, metrics, true).cleanup();

    verify(registry).deleteExpiredNegativeCache(any(Instant.class));
    verify(metrics).incrementWorkerItems(
        "swift_cleanup", "proxy_cache_waterline", "deleted", 3);
  }

  @Test
  void disabledWorkerDoesNotTouchTheSharedDatabase() {
    SwiftRegistryDao registry = mock(SwiftRegistryDao.class);

    new SwiftCacheCleanupWorker(registry, mock(KkRepoMetrics.class), false).cleanup();

    verify(registry, never()).deleteExpiredNegativeCache(any());
  }

  @Test
  void transientCleanupFailureIsLeftForTheNextReplicaOrCycle() {
    SwiftRegistryDao registry = mock(SwiftRegistryDao.class);
    doThrow(new IllegalStateException("database unavailable"))
        .when(registry).deleteExpiredNegativeCache(any());

    new SwiftCacheCleanupWorker(registry, mock(KkRepoMetrics.class), true).cleanup();

    verify(registry).deleteExpiredNegativeCache(any());
  }
}
