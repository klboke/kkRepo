package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.MaintenanceCursorDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ReconciliationPage;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SecurityScanArtifactReconciliationServiceTest {
  @Test
  void reconciliationPropertiesKeepBoundedSafeDefaults() {
    SecurityScanningProperties.Worker properties =
        new SecurityScanningProperties().getWorker();

    properties.setArtifactReconcileBatchSize(0);
    assertEquals(1, properties.getArtifactReconcileBatchSize());
    properties.setArtifactReconcileBatchSize(20_000);
    assertEquals(10_000, properties.getArtifactReconcileBatchSize());

    properties.setArtifactReconcileRecentWindow(null);
    assertEquals(Duration.ofDays(1), properties.getArtifactReconcileRecentWindow());
    properties.setArtifactReconcileRecentWindow(Duration.ZERO);
    assertEquals(Duration.ofDays(1), properties.getArtifactReconcileRecentWindow());
    properties.setArtifactReconcileRecentWindow(Duration.ofSeconds(-1));
    assertEquals(Duration.ofDays(1), properties.getArtifactReconcileRecentWindow());
  }

  @Test
  void skipsWhenAnotherReplicaOwnsTheCyclicCursor() {
    MaintenanceCursorDao cursors = mock(MaintenanceCursorDao.class);
    SecurityScanDao scans = mock(SecurityScanDao.class);
    when(cursors.tryLockLastSeenId(SecurityScanArtifactReconciliationService.CURSOR_NAME))
        .thenReturn(OptionalLong.empty());

    ReconciliationPage page = new SecurityScanArtifactReconciliationService(
        cursors, scans, new SecurityScanningProperties()).processBatch();

    assertEquals(0, page.scannedAssets());
    verifyNoInteractions(scans);
  }

  @Test
  void advancesTheDurableCursorAndUsesTheRecentFastPath() {
    MaintenanceCursorDao cursors = mock(MaintenanceCursorDao.class);
    SecurityScanDao scans = mock(SecurityScanDao.class);
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.getWorker().setArtifactReconcileBatchSize(7);
    properties.getWorker().setArtifactReconcileRecentWindow(Duration.ofHours(2));
    when(cursors.tryLockLastSeenId(SecurityScanArtifactReconciliationService.CURSOR_NAME))
        .thenReturn(OptionalLong.of(10));
    ReconciliationPage expected = new ReconciliationPage(2, 7, 3, 19, false);
    when(scans.reconcileArtifactChanges(
        org.mockito.ArgumentMatchers.eq(10L),
        any(Instant.class),
        org.mockito.ArgumentMatchers.eq(7),
        org.mockito.ArgumentMatchers.eq(7)))
        .thenReturn(expected);
    when(cursors.updateLastSeenId(
        SecurityScanArtifactReconciliationService.CURSOR_NAME, 19))
        .thenReturn(1);
    Instant before = Instant.now().minus(Duration.ofHours(2)).minusSeconds(1);

    ReconciliationPage actual = new SecurityScanArtifactReconciliationService(
        cursors, scans, properties).processBatch();

    assertEquals(expected, actual);
    ArgumentCaptor<Instant> recentSince = ArgumentCaptor.forClass(Instant.class);
    verify(scans).reconcileArtifactChanges(
        org.mockito.ArgumentMatchers.eq(10L),
        recentSince.capture(),
        org.mockito.ArgumentMatchers.eq(7),
        org.mockito.ArgumentMatchers.eq(7));
    assertTrue(recentSince.getValue().isAfter(before));
    verify(cursors).updateLastSeenId(
        SecurityScanArtifactReconciliationService.CURSOR_NAME, 19);
  }

  @Test
  void rollsBackWhenTheLockedCursorDisappears() {
    MaintenanceCursorDao cursors = mock(MaintenanceCursorDao.class);
    SecurityScanDao scans = mock(SecurityScanDao.class);
    when(cursors.tryLockLastSeenId(SecurityScanArtifactReconciliationService.CURSOR_NAME))
        .thenReturn(OptionalLong.of(10));
    when(scans.reconcileArtifactChanges(
        org.mockito.ArgumentMatchers.eq(10L),
        any(Instant.class),
        org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(new ReconciliationPage(0, 1, 1, 11, false));

    SecurityScanArtifactReconciliationService service =
        new SecurityScanArtifactReconciliationService(
            cursors, scans, new SecurityScanningProperties());

    assertThrows(IllegalStateException.class, service::processBatch);
  }

  @Test
  void workerContainsTransientDatabaseFailures() {
    SecurityScanArtifactReconciliationService reconciliation =
        mock(SecurityScanArtifactReconciliationService.class);
    when(reconciliation.processBatch()).thenThrow(new IllegalStateException("database"));

    new SecurityScanArtifactReconciliationWorker(reconciliation).runOnce();

    verify(reconciliation).processBatch();
  }
}
