package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.ClaimedRunRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class CleanupRunWorkerTest {
  @Test
  void processesClaimsInParallelAndHeartbeatsEveryOccupiedSlot() throws Exception {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    CleanupRunService runs = mock(CleanupRunService.class);
    CleanupRuntimeProperties properties = new CleanupRuntimeProperties();
    properties.getWorker().setBatchSize(2);
    properties.getWorker().setConcurrency(2);
    properties.getWorker().setLeaseDuration(Duration.ofMillis(90));
    properties.getWorker().setHeartbeatInterval(Duration.ofMillis(10));
    Instant now = Instant.parse("2026-08-02T00:00:00Z");
    ClaimedRunRepository first = claim(1, now);
    ClaimedRunRepository second = claim(2, now);
    when(cleanupDao.claimRunRepositories(anyString(), any(), any(), anyInt()))
        .thenReturn(List.of(first, second));
    CountDownLatch bothHeartbeats = new CountDownLatch(2);
    Set<Long> heartbeatClaims = ConcurrentHashMap.newKeySet();
    when(cleanupDao.heartbeatRunRepository(
        anyLong(), anyString(), anyLong(), any(), any()))
        .thenAnswer(invocation -> {
          if (heartbeatClaims.add(invocation.getArgument(0))) bothHeartbeats.countDown();
          return true;
        });
    CountDownLatch started = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch completed = new CountDownLatch(2);
    doAnswer(invocation -> {
      started.countDown();
      try {
        release.await(2, TimeUnit.SECONDS);
      } finally {
        completed.countDown();
      }
      return null;
    }).when(runs).process(any());
    TransactionTemplate transactions = mock(TransactionTemplate.class);
    when(transactions.execute(any())).thenAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      TransactionCallback<Object> callback = invocation.getArgument(0);
      return callback.doInTransaction(mock(TransactionStatus.class));
    });
    CleanupRunWorker worker = new CleanupRunWorker(
        cleanupDao,
        runs,
        properties,
        transactions,
        Clock.fixed(now, ZoneOffset.UTC),
        "worker-a");

    try {
      worker.runOnce();
      assertTrue(started.await(2, TimeUnit.SECONDS));
      assertTrue(bothHeartbeats.await(2, TimeUnit.SECONDS));
      release.countDown();
      assertTrue(completed.await(2, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      worker.shutdownHeartbeatExecutor();
    }

    verify(runs).process(first);
    verify(runs).process(second);
  }

  @Test
  void transientDatabaseClockFailureDoesNotPermanentlyStopLeaseHeartbeats() throws Exception {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    CleanupRunService runs = mock(CleanupRunService.class);
    CleanupRuntimeProperties properties = new CleanupRuntimeProperties();
    properties.getWorker().setLeaseDuration(Duration.ofMillis(90));
    properties.getWorker().setHeartbeatInterval(Duration.ofMillis(10));
    Instant now = Instant.parse("2026-08-02T00:00:00Z");
    ClaimedRunRepository claim = claim(1, now);
    when(cleanupDao.currentTime())
        .thenReturn(now)
        .thenThrow(new IllegalStateException("database clock unavailable"))
        .thenReturn(now);
    when(cleanupDao.claimRunRepositories(anyString(), any(), any(), anyInt()))
        .thenReturn(List.of(claim));
    CountDownLatch heartbeatAfterRecovery = new CountDownLatch(1);
    when(cleanupDao.heartbeatRunRepository(
        anyLong(), anyString(), anyLong(), any(), any()))
        .thenAnswer(invocation -> {
          heartbeatAfterRecovery.countDown();
          return true;
        });
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch completed = new CountDownLatch(1);
    doAnswer(invocation -> {
      release.await(2, TimeUnit.SECONDS);
      completed.countDown();
      return null;
    }).when(runs).process(any());
    TransactionTemplate transactions = mock(TransactionTemplate.class);
    when(transactions.execute(any())).thenAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      TransactionCallback<Object> callback = invocation.getArgument(0);
      return callback.doInTransaction(mock(TransactionStatus.class));
    });
    CleanupRunWorker worker = new CleanupRunWorker(
        cleanupDao,
        runs,
        properties,
        transactions,
        Clock.fixed(now, ZoneOffset.UTC),
        "worker-a");

    try {
      worker.runOnce();
      assertTrue(heartbeatAfterRecovery.await(2, TimeUnit.SECONDS));
      release.countDown();
      assertTrue(completed.await(2, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      worker.shutdownHeartbeatExecutor();
    }

    verify(runs).process(claim);
    verify(cleanupDao).heartbeatRunRepository(
        anyLong(), anyString(), anyLong(), any(), any());
  }

  @Test
  void doesNotClaimPastConfiguredProcessingConcurrency() throws Exception {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    CleanupRunService runs = mock(CleanupRunService.class);
    CleanupRuntimeProperties properties = new CleanupRuntimeProperties();
    properties.getWorker().setBatchSize(32);
    properties.getWorker().setConcurrency(1);
    Instant now = Instant.parse("2026-08-02T00:00:00Z");
    ClaimedRunRepository claim = claim(1, now);
    when(cleanupDao.claimRunRepositories(anyString(), any(), any(), anyInt()))
        .thenReturn(List.of(claim));
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch completed = new CountDownLatch(1);
    doAnswer(invocation -> {
      started.countDown();
      try {
        release.await(2, TimeUnit.SECONDS);
      } finally {
        completed.countDown();
      }
      return null;
    }).when(runs).process(any());
    CleanupRunWorker worker = new CleanupRunWorker(
        cleanupDao,
        runs,
        properties,
        transactions(),
        Clock.fixed(now, ZoneOffset.UTC),
        "worker-a");

    try {
      worker.runOnce();
      assertTrue(started.await(2, TimeUnit.SECONDS));
      worker.runOnce();
      verify(cleanupDao, times(1)).claimRunRepositories(
          anyString(), any(), any(), anyInt());
      release.countDown();
      assertTrue(completed.await(2, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      worker.shutdownHeartbeatExecutor();
    }
  }

  @Test
  void backsOffRepeatedPollingWhenNoShardIsAvailable() {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    CleanupRunService runs = mock(CleanupRunService.class);
    CleanupRuntimeProperties properties = new CleanupRuntimeProperties();
    properties.getWorker().setIdleBaseDelay(Duration.ofSeconds(5));
    properties.getWorker().setIdleMaxDelay(Duration.ofSeconds(5));
    Instant now = Instant.parse("2026-08-02T00:00:00Z");
    when(cleanupDao.claimRunRepositories(anyString(), any(), any(), anyInt()))
        .thenReturn(List.of());
    CleanupRunWorker worker = new CleanupRunWorker(
        cleanupDao,
        runs,
        properties,
        transactions(),
        Clock.fixed(now, ZoneOffset.UTC),
        "worker-a");

    try {
      worker.runOnce();
      worker.runOnce();
    } finally {
      worker.shutdownHeartbeatExecutor();
    }

    verify(cleanupDao, times(1)).claimRunRepositories(
        anyString(), any(), any(), anyInt());
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

  private static ClaimedRunRepository claim(long id, Instant now) {
    return new ClaimedRunRepository(
        id,
        10,
        id,
        "raw-" + id,
        RepositoryFormat.RAW,
        RepositoryType.HOSTED,
        "worker-a",
        "lease-" + id,
        id,
        1,
        3,
        now.plusSeconds(1));
  }
}
