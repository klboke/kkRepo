package com.github.klboke.kkrepo.server.cleanup;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
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
    verify(cleanupDao).synchronizeUsageTracking(Map.of(), now);
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
}
