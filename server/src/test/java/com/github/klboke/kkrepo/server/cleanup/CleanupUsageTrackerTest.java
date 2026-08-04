package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupUsageWriteOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class CleanupUsageTrackerTest {
  private final Instant now = Instant.parse("2026-08-01T00:00:00Z");
  private CleanupPolicyDao cleanupDao;
  private CleanupUsageTrackingService tracking;
  private CleanupRuntimeProperties properties;
  private CleanupUsageTracker tracker;

  @BeforeEach
  void setUp() {
    cleanupDao = mock(CleanupPolicyDao.class);
    tracking = mock(CleanupUsageTrackingService.class);
    properties = new CleanupRuntimeProperties();
    TransactionTemplate transactions = mock(TransactionTemplate.class);
    when(transactions.execute(any())).thenAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      TransactionCallback<Object> callback = invocation.getArgument(0);
      return callback.doInTransaction(mock(TransactionStatus.class));
    });
    tracker = new CleanupUsageTracker(
        cleanupDao,
        tracking,
        properties,
        mock(CleanupMetrics.class),
        transactions,
        Clock.fixed(now, ZoneOffset.UTC));
  }

  @Test
  void successfulWritesAreCoalescedOnlyAfterTheyCommit() {
    when(tracking.isTracked(10L)).thenReturn(true);
    when(cleanupDao.recordAssetUsage(5, 10L, Duration.ofMinutes(5)))
        .thenReturn(CleanupUsageWriteOutcome.WRITTEN);

    tracker.record(5, 10L);
    tracker.record(5, 10L);

    verify(cleanupDao, times(1)).recordAssetUsage(5, 10L, Duration.ofMinutes(5));
  }

  @Test
  void wallClockRollbackCannotExtendTheNodeLocalCoalescingWindow() {
    AtomicLong monotonicNanos = new AtomicLong();
    TransactionTemplate transactions = mock(TransactionTemplate.class);
    when(transactions.execute(any())).thenAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      TransactionCallback<Object> callback = invocation.getArgument(0);
      return callback.doInTransaction(mock(TransactionStatus.class));
    });
    tracker = new CleanupUsageTracker(
        cleanupDao,
        tracking,
        properties,
        mock(CleanupMetrics.class),
        transactions,
        Clock.fixed(now, ZoneOffset.UTC),
        monotonicNanos::get);
    when(tracking.isTracked(10L)).thenReturn(true);
    when(cleanupDao.recordAssetUsage(5, 10L, Duration.ofMinutes(5)))
        .thenReturn(CleanupUsageWriteOutcome.WRITTEN);

    tracker.record(5, 10L);
    monotonicNanos.set(Duration.ofMinutes(5).toNanos() + 1);
    tracker.record(5, 10L);

    verify(cleanupDao, times(2)).recordAssetUsage(5, 10L, Duration.ofMinutes(5));
  }

  @Test
  void aTrackedDownloadFailsClosedWhenTheSharedWatermarkCannotBeStored() {
    when(tracking.isTracked(10L)).thenReturn(true);
    when(cleanupDao.recordAssetUsage(5, 10L, Duration.ofMinutes(5)))
        .thenThrow(new IllegalStateException("database"));

    assertThrows(CleanupUsageUnavailableException.class, () -> tracker.record(5, 10L));
  }

  @Test
  void explicitFailOpenDoesNotTurnAFailedWriteIntoACoalescingHit() {
    properties.getUsage().setFailClosed(false);
    when(tracking.isTracked(10L)).thenReturn(true);
    when(cleanupDao.recordAssetUsage(5, 10L, Duration.ofMinutes(5)))
        .thenThrow(new IllegalStateException("database"));

    assertDoesNotThrow(() -> tracker.record(5, 10L));
    assertDoesNotThrow(() -> tracker.record(5, 10L));

    verify(cleanupDao, times(2)).recordAssetUsage(5, 10L, Duration.ofMinutes(5));
  }

  @Test
  void repositoriesWithoutUsagePoliciesStayOffTheWritePath() {
    when(tracking.isTracked(10L)).thenReturn(false);
    tracker.record(5, 10L);

    verify(cleanupDao, never()).recordAssetUsage(
        anyLong(), anyLong(), any(Duration.class));
  }

  @Test
  void untrackedGroupEntryDoesNotWriteForATrackedMemberRepository() {
    when(tracking.isTracked(30L)).thenReturn(false);

    tracker.record(5, 30L);

    verify(cleanupDao, never()).recordAssetUsage(
        anyLong(), anyLong(), any(Duration.class));
  }
}
