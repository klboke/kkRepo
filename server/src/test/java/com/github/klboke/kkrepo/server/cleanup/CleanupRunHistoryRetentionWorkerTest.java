package com.github.klboke.kkrepo.server.cleanup;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupHistoryPruneResult;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CleanupRunHistoryRetentionWorkerTest {
  @Test
  void drainsOnlyTheConfiguredNumberOfBoundedBatches() {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    CleanupMetrics metrics = mock(CleanupMetrics.class);
    CleanupRuntimeProperties properties = new CleanupRuntimeProperties();
    properties.getHistory().setBatchSize(2);
    properties.getHistory().setMaxBatchesPerRun(3);
    properties.getHistory().setMinimumRunsPerPolicy(5);
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    Instant cutoff = Instant.parse("2026-05-03T00:00:00Z");
    when(cleanupDao.currentTime()).thenReturn(now);
    when(cleanupDao.pruneTerminalRunHistory(cutoff, 2, 5, 5_000)).thenReturn(
        new CleanupHistoryPruneResult(2, 5_000),
        new CleanupHistoryPruneResult(1, 10),
        new CleanupHistoryPruneResult(0, 0));

    new CleanupRunHistoryRetentionWorker(cleanupDao, properties, metrics).runOnce();

    verify(cleanupDao, org.mockito.Mockito.times(3))
        .pruneTerminalRunHistory(cutoff, 2, 5, 5_000);
    verify(metrics).retention(3);
    verify(metrics).retentionItems(5_010);
  }

  @Test
  void disabledRetentionDoesNotTouchTheDatabase() {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    CleanupRuntimeProperties properties = new CleanupRuntimeProperties();
    properties.getHistory().setEnabled(false);

    new CleanupRunHistoryRetentionWorker(
        cleanupDao, properties, mock(CleanupMetrics.class)).runOnce();

    verify(cleanupDao, never()).currentTime();
  }
}
