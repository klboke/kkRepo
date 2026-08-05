package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class CleanupUsageTrackingServiceTest {

  @Test
  void reconcilesTheDurableProjectionInsideAnExplicitTransaction() {
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    TransactionTemplate transactions = mock(TransactionTemplate.class);
    doAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      Consumer<TransactionStatus> callback = invocation.getArgument(0);
      callback.accept(mock(TransactionStatus.class));
      return null;
    }).when(transactions).executeWithoutResult(any());
    CleanupUsageTrackingService service = new CleanupUsageTrackingService(
        cleanupDao,
        mock(CleanupMetrics.class),
        Clock.fixed(now, ZoneOffset.UTC),
        transactions);

    service.reconcilePolicyProjection();

    verify(transactions).executeWithoutResult(any());
    InOrder projectionOrder = inOrder(cleanupDao);
    projectionOrder.verify(cleanupDao).lockUsageTrackingProjection();
    projectionOrder.verify(cleanupDao).listUsageTrackingRepositories();
    projectionOrder.verify(cleanupDao).listPolicies(0, 500);
    projectionOrder.verify(cleanupDao).synchronizeUsageTracking(Map.of(), now);
  }

  @Test
  void defersAfterCommitHintUntilTransactionResourcesAreUnbound() {
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    TransactionTemplate transactions = mock(TransactionTemplate.class);
    doAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      Consumer<TransactionStatus> callback = invocation.getArgument(0);
      callback.accept(mock(TransactionStatus.class));
      return null;
    }).when(transactions).executeWithoutResult(any());
    CleanupUsageTrackingService service = new CleanupUsageTrackingService(
        cleanupDao,
        mock(CleanupMetrics.class),
        Clock.fixed(now, ZoneOffset.UTC),
        transactions);

    TransactionSynchronizationManager.initSynchronization();
    try {
      service.reconcileAfterCommit();
      verifyNoInteractions(transactions);
      for (TransactionSynchronization synchronization
          : TransactionSynchronizationManager.getSynchronizations()) {
        synchronization.afterCommit();
      }
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    verifyNoInteractions(transactions);
    service.reconcilePendingSafely();
    verify(transactions).executeWithoutResult(any());
  }

  @Test
  void projectsOnlyDownloadAwarePoliciesAndRefreshesTheLocalReadHint() {
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    Instant existingStart = now.minusSeconds(3_600);
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    CleanupMetrics metrics = mock(CleanupMetrics.class);
    TransactionTemplate transactions = mock(TransactionTemplate.class);
    doAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      Consumer<TransactionStatus> callback = invocation.getArgument(0);
      callback.accept(mock(TransactionStatus.class));
      return null;
    }).when(transactions).executeWithoutResult(any());
    when(cleanupDao.listUsageTrackingRepositories()).thenReturn(List.of(
        new CleanupPolicyDao.UsageTrackingRepository(10, existingStart),
        new CleanupPolicyDao.UsageTrackingRepository(11, now.minusSeconds(60))));
    when(cleanupDao.listPolicies(0, 500)).thenReturn(List.of(
        policy(7, Map.of("lastDownloadedOlderThanDays", 30)),
        policy(8, Map.of("publishedOlderThanDays", 30))));
    when(cleanupDao.listTargets(List.of(7L))).thenReturn(Map.of(7L, List.of(
        new CleanupPolicyDao.TargetRepository(
            10, "existing", RepositoryFormat.RAW, RepositoryType.HOSTED, true),
        new CleanupPolicyDao.TargetRepository(
            12, "new", RepositoryFormat.RAW, RepositoryType.PROXY, true))));
    CleanupUsageTrackingService service = new CleanupUsageTrackingService(
        cleanupDao, metrics, Clock.fixed(now, ZoneOffset.UTC), transactions);

    service.reconcile(now);
    verify(cleanupDao).synchronizeUsageTracking(
        Map.of(10L, existingStart, 12L, now), now);

    service.refreshLocalSnapshot();
    assertTrue(service.isTracked(10L));
    assertFalse(service.isTracked(null));
    assertFalse(service.isTracked(12L));
    assertTrue(service.hasTrackedRepositories());
    assertEquals(existingStart, service.trackingStartedAt(10));
    assertNull(service.trackingStartedAt(999));
    verify(metrics).trackedRepositories(2);

    service.refreshLocalSnapshot();
    verify(cleanupDao, times(2)).listUsageTrackingRepositories();

    service.reconcileAfterCommit();
    verify(transactions).executeWithoutResult(any());
  }

  private static CleanupPolicyDao.CleanupPolicy policy(
      long id, Map<String, Object> criteria) {
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    return new CleanupPolicyDao.CleanupPolicy(
        id, "policy-" + id, RepositoryFormat.RAW, null, criteria, 1, "PAUSED",
        100, 10, now, now);
  }
}
