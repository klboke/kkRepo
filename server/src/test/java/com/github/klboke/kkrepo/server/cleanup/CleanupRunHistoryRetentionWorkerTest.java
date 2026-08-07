package com.github.klboke.kkrepo.server.cleanup;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupHistoryPruneResult;
import com.github.klboke.kkrepo.persistence.jdbc.api.MaintenanceCursorDao;
import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

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

  @Test
  void clusterReservationSkipsWhenAnotherReplicaOwnsTheCursor() {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    CleanupMetrics metrics = mock(CleanupMetrics.class);
    MaintenanceCursorDao cursors = mock(MaintenanceCursorDao.class);
    when(cursors.tryLockLastSeenId("cleanup_run_history_retention"))
        .thenReturn(OptionalLong.empty());

    new CleanupRunHistoryRetentionWorker(
        cleanupDao,
        new CleanupRuntimeProperties(),
        metrics,
        cursors,
        transactions()).runOnce();

    verify(cursors).ensureCursor("cleanup_run_history_retention");
    verify(cleanupDao, never()).pruneTerminalRunHistory(any(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void clusterReservationAdvancesBeforePruning() {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    CleanupMetrics metrics = mock(CleanupMetrics.class);
    MaintenanceCursorDao cursors = mock(MaintenanceCursorDao.class);
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    when(cursors.tryLockLastSeenId("cleanup_run_history_retention"))
        .thenReturn(OptionalLong.of(0));
    when(cursors.updateLastSeenId("cleanup_run_history_retention", now.toEpochMilli()))
        .thenReturn(1);
    when(cleanupDao.currentTime()).thenReturn(now);
    when(cleanupDao.pruneTerminalRunHistory(
        Instant.parse("2026-05-03T00:00:00Z"), 25, 10, 5_000))
        .thenReturn(new CleanupHistoryPruneResult(0, 0));

    new CleanupRunHistoryRetentionWorker(
        cleanupDao,
        new CleanupRuntimeProperties(),
        metrics,
        cursors,
        transactions()).runOnce();

    verify(cursors).updateLastSeenId(
        "cleanup_run_history_retention", now.toEpochMilli());
    verify(metrics).retention(0);
    verify(metrics).retentionItems(0);
  }

  @Test
  void retentionFailureIsCountedWithoutEscapingTheScheduler() {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    CleanupMetrics metrics = mock(CleanupMetrics.class);
    when(cleanupDao.currentTime()).thenThrow(new IllegalStateException("database unavailable"));

    new CleanupRunHistoryRetentionWorker(
        cleanupDao, new CleanupRuntimeProperties(), metrics).runOnce();

    verify(metrics).retentionFailure();
  }

  @Test
  void springConstructorHonorsDisabledRetentionBeforeOpeningATransaction() {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    CleanupRuntimeProperties properties = new CleanupRuntimeProperties();
    properties.getHistory().setEnabled(false);

    new CleanupRunHistoryRetentionWorker(
        cleanupDao,
        properties,
        mock(CleanupMetrics.class),
        mock(MaintenanceCursorDao.class),
        mock(PlatformTransactionManager.class)).runOnce();

    verify(cleanupDao, never()).currentTime();
  }

  private static TransactionTemplate transactions() {
    TransactionTemplate transactions = mock(TransactionTemplate.class);
    when(transactions.execute(any())).thenAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      TransactionCallback<Object> callback = invocation.getArgument(0);
      return callback.doInTransaction(mock(TransactionStatus.class));
    });
    return transactions;
  }
}
