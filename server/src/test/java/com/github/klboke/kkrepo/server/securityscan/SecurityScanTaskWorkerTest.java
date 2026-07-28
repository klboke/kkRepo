package com.github.klboke.kkrepo.server.securityscan;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanTask;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.security.scan.ScanEnums.RequestReason;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanStage;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SecurityScanTaskWorkerTest {
  @Test
  void claimsExecutesAndRecordsACompletedTask() {
    Fixture fixture = new Fixture();
    try {
      fixture.worker.runOnce();
      verify(fixture.executor).execute(fixture.task);
      verify(fixture.metrics).recordTask(
          eq("MAVEN2"), eq(ScanStage.CATALOG_AND_MATCH),
          eq(RequestReason.MANUAL), eq("success"), any());
    } finally {
      fixture.worker.shutdown();
    }
  }

  @Test
  void containsClaimFailureAndSupersededTask() {
    Fixture claimFailure = new Fixture();
    try {
      when(claimFailure.coordinator.claim(anyString()))
          .thenThrow(new IllegalStateException("database unavailable"));
      claimFailure.worker.runOnce();
      verify(claimFailure.executor, never()).execute(any());
    } finally {
      claimFailure.worker.shutdown();
    }

    Fixture superseded = new Fixture();
    try {
      when(superseded.executor.execute(superseded.task))
          .thenThrow(new SecurityScanExecutor.SupersededSecurityScanTaskException(5L));
      when(superseded.scans.cancelClaimedTask(eq(5L), eq("lease"), any()))
          .thenReturn(false);
      superseded.worker.runOnce();
      verify(superseded.scans).cancelClaimedTask(eq(5L), eq("lease"), any());
      verify(superseded.metrics).recordTask(
          eq("MAVEN2"), any(), any(), eq("superseded"), any());
    } finally {
      superseded.worker.shutdown();
    }
  }

  @Test
  void classifiesRetryableTerminalAndUnexpectedFailures() {
    Fixture retryable = new Fixture();
    try {
      when(retryable.executor.execute(retryable.task))
          .thenThrow(new ScannerAdapterException("DOWN", "scanner down", true));
      retryable.worker.runOnce();
      verify(retryable.finalizer).failCurrentTask(
          eq(retryable.task), eq("DOWN"), eq("scanner down"), eq(true), any());
      verify(retryable.metrics).recordTask(
          eq("MAVEN2"), any(), any(), eq("retry"), any());
    } finally {
      retryable.worker.shutdown();
    }

    Fixture terminal = new Fixture();
    try {
      when(terminal.task.attempts()).thenReturn(5);
      when(terminal.task.maxAttempts()).thenReturn(5);
      when(terminal.executor.execute(terminal.task))
          .thenThrow(new ScannerAdapterException("INVALID", "bad response", false));
      terminal.worker.runOnce();
      verify(terminal.finalizer).failCurrentTask(
          eq(terminal.task), eq("INVALID"), eq("bad response"), eq(false), any());
      verify(terminal.metrics).recordTask(
          eq("MAVEN2"), any(), any(), eq("failed"), any());
    } finally {
      terminal.worker.shutdown();
    }

    Fixture unexpected = new Fixture();
    try {
      when(unexpected.executor.execute(unexpected.task))
          .thenThrow(new IllegalStateException());
      unexpected.worker.runOnce();
      verify(unexpected.finalizer).failCurrentTask(
          eq(unexpected.task),
          eq("SCAN_INTERNAL_ERROR"),
          eq("IllegalStateException"),
          eq(true),
          any());
    } finally {
      unexpected.worker.shutdown();
    }
  }

  @Test
  void toleratesLeaseLossDuringExecutionAndFailureFinalization() {
    Fixture executionLoss = new Fixture();
    try {
      when(executionLoss.executor.execute(executionLoss.task))
          .thenThrow(new SecurityScanFinalizer.LostSecurityScanLeaseException(5L));
      executionLoss.worker.runOnce();
      verify(executionLoss.finalizer, never()).failCurrentTask(
          any(), anyString(), anyString(), eq(true), any());
      verify(executionLoss.metrics).recordTask(
          eq("MAVEN2"), any(), any(), eq("lease_lost"), any());
    } finally {
      executionLoss.worker.shutdown();
    }

    Fixture finalizationLoss = new Fixture();
    try {
      when(finalizationLoss.executor.execute(finalizationLoss.task))
          .thenThrow(new ScannerAdapterException("DOWN", "scanner down", true));
      doThrow(new SecurityScanFinalizer.LostSecurityScanLeaseException(5L))
          .when(finalizationLoss.finalizer)
          .failCurrentTask(any(), anyString(), anyString(), eq(true), any());
      finalizationLoss.worker.runOnce();
      verify(finalizationLoss.metrics).recordTask(
          eq("MAVEN2"), any(), any(), eq("retry"), any());
    } finally {
      finalizationLoss.worker.shutdown();
    }
  }

  @Test
  void renewsHeartbeatLeaseUsingTheSharedDao() throws Exception {
    Fixture fixture = new Fixture();
    try {
      when(fixture.scans.heartbeatTask(eq(5L), eq("lease"), any(), any()))
          .thenReturn(false);
      Method heartbeat =
          SecurityScanTaskWorker.class.getDeclaredMethod("heartbeat", ScanTask.class);
      heartbeat.setAccessible(true);
      heartbeat.invoke(fixture.worker, fixture.task);
      verify(fixture.scans).heartbeatTask(eq(5L), eq("lease"), any(), any());
    } finally {
      fixture.worker.shutdown();
    }
  }

  @Test
  void containsHeartbeatDatabaseFailuresSoThePeriodicCallbackCanRetry() throws Exception {
    Fixture fixture = new Fixture();
    try {
      when(fixture.scans.heartbeatTask(eq(5L), eq("lease"), any(), any()))
          .thenThrow(new IllegalStateException("temporary"));
      Method heartbeat =
          SecurityScanTaskWorker.class.getDeclaredMethod("heartbeat", ScanTask.class);
      heartbeat.setAccessible(true);

      heartbeat.invoke(fixture.worker, fixture.task);
      heartbeat.invoke(fixture.worker, fixture.task);

      verify(fixture.scans, times(2))
          .heartbeatTask(eq(5L), eq("lease"), any(), any());
    } finally {
      fixture.worker.shutdown();
    }
  }

  @Test
  void terminalizesAnExpiredFinalAttemptWithoutExecutingItAgain() {
    Fixture fixture = new Fixture();
    try {
      when(fixture.coordinator.claimExpiredExhausted(anyString()))
          .thenReturn(List.of(fixture.task));
      when(fixture.coordinator.claim(anyString())).thenReturn(List.of());

      fixture.worker.runOnce();

      verify(fixture.finalizer).failCurrentTask(
          eq(fixture.task),
          eq("SCAN_ATTEMPTS_EXHAUSTED"),
          eq("The worker lease expired after the final permitted scan attempt"),
          eq(false),
          any());
      verify(fixture.executor, never()).execute(any());
    } finally {
      fixture.worker.shutdown();
    }
  }

  private static final class Fixture {
    final SecurityScanDao scans = mock(SecurityScanDao.class);
    final SecurityScanTaskCoordinator coordinator = mock(SecurityScanTaskCoordinator.class);
    final SecurityScanExecutor executor = mock(SecurityScanExecutor.class);
    final SecurityScanFinalizer finalizer = mock(SecurityScanFinalizer.class);
    final SecurityScanningProperties properties = new SecurityScanningProperties();
    final AssetDao assets = mock(AssetDao.class);
    final SecurityScanMetrics metrics = mock(SecurityScanMetrics.class);
    final ScanTask task = mock(ScanTask.class);
    final SecurityScanTaskWorker worker;

    Fixture() {
      properties.getWorker().setBatchSize(1);
      properties.getWorker().setLeaseSeconds(30);
      properties.getWorker().setHeartbeatSeconds(5);
      properties.getWorker().setMaxBackoffSeconds(30);
      when(task.id()).thenReturn(5L);
      when(task.assetId()).thenReturn(10L);
      when(task.leaseToken()).thenReturn("lease");
      when(task.stage()).thenReturn(ScanStage.CATALOG_AND_MATCH);
      when(task.requestReason()).thenReturn(RequestReason.MANUAL);
      when(task.attempts()).thenReturn(1);
      when(task.maxAttempts()).thenReturn(5);
      when(coordinator.claim(anyString())).thenReturn(List.of(task));
      when(assets.findAssetById(10L)).thenReturn(Optional.of(new AssetRecord(
          10L, 1L, null, 11L, RepositoryFormat.MAVEN2, "demo.jar", new byte[32],
          "demo.jar", "artifact", "application/java-archive", 8L,
          null, Instant.EPOCH, Map.of())));
      worker = new SecurityScanTaskWorker(
          scans, coordinator, executor, finalizer, properties, assets, metrics);
    }
  }
}
