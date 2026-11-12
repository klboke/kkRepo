package com.github.klboke.kkrepo.server.kkguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Application;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Finding;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Policy;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Scan;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.ScanSbom;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Violation;
import com.github.klboke.kkrepo.server.kkguard.dependencytrack.DependencyTrackClient;
import com.github.klboke.kkrepo.server.kkguard.dependencytrack.DependencyTrackClient.Project;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class KkGuardScanWorkerTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:10:00Z");
  private static final String SBOM =
      "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.5\",\"components\":[]}";
  private static final String SHA = sha256(SBOM.getBytes(StandardCharsets.UTF_8));

  private KkGuardDao dao;
  private DependencyTrackClient dependencyTrack;
  private KkGuardScanService scans;
  private BlobStorageRegistry storages;
  private BlobStorage storage;
  private KkGuardProperties properties;
  private KkGuardScanWorker worker;

  @BeforeEach
  void setUp() {
    dao = mock(KkGuardDao.class);
    dependencyTrack = mock(DependencyTrackClient.class);
    scans = mock(KkGuardScanService.class);
    storages = mock(BlobStorageRegistry.class);
    storage = mock(BlobStorage.class);
    properties = new KkGuardProperties();
    properties.setEnabled(true);
    properties.getDependencyTrack().setUrl("http://dtrack.test");
    properties.getDependencyTrack().setApiKey("key");
    when(dao.listClaimableScanIds(any(), anyInt())).thenReturn(List.of(10L));
    when(dao.claimScan(eq(10L), anyString(), any(), any())).thenReturn(true);
    when(storages.forBlobStoreId(1L)).thenReturn(storage);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    worker = new KkGuardScanWorker(
        dao, properties, dependencyTrack, scans, storages, transactionManager);
  }

  @Test
  void pendingScansAreSubmittedWithACreatedDependencyTrackProject() {
    when(dao.findScan(10L)).thenReturn(Optional.of(scan("PENDING", null, null, null)));
    when(dao.findApplication(5L)).thenReturn(Optional.of(application(null)));
    when(dao.findScanSbom(10L)).thenReturn(Optional.of(
        new ScanSbom(1L, "blob://bucket/key", SHA, SBOM.length())));
    when(storage.get(any())).thenReturn(Optional.of(
        new ByteArrayInputStream(SBOM.getBytes(StandardCharsets.UTF_8))));
    when(dependencyTrack.isConfigured()).thenReturn(true);
    when(dependencyTrack.findProject("orders-api", "build")).thenReturn(Optional.empty());
    when(dependencyTrack.createProject("orders-api", "build"))
        .thenReturn(new Project("dt-uuid", "orders-api", "build"));
    when(dependencyTrack.uploadBom(eq("dt-uuid"), any())).thenReturn("dt-token");

    worker.runOnce(NOW);

    verify(dao).updateApplicationDependencyTrackProject(
        eq(5L), eq("dt-uuid"), eq("orders-api"), any());
    verify(dao).markScanSubmitted(eq(10L), eq(worker.owner()), eq("dt-uuid"), eq("dt-token"),
        any());
    verify(dao).releaseScan(10L, worker.owner());
  }

  @Test
  void existingDependencyTrackProjectsAreReused() {
    when(dao.findScan(10L)).thenReturn(Optional.of(scan("PENDING", null, null, null)));
    when(dao.findApplication(5L)).thenReturn(Optional.of(application("dt-existing")));
    when(dao.findScanSbom(10L)).thenReturn(Optional.of(
        new ScanSbom(1L, "blob://bucket/key", SHA, SBOM.length())));
    when(storage.get(any())).thenReturn(Optional.of(
        new ByteArrayInputStream(SBOM.getBytes(StandardCharsets.UTF_8))));
    when(dependencyTrack.isConfigured()).thenReturn(true);
    when(dependencyTrack.uploadBom(eq("dt-existing"), any())).thenReturn("dt-token");

    worker.runOnce(NOW);

    verify(dependencyTrack, never()).createProject(anyString(), anyString());
    verify(dependencyTrack, never()).findProject(anyString(), anyString());
    verify(dao).markScanSubmitted(
        eq(10L), eq(worker.owner()), eq("dt-existing"), eq("dt-token"), any());
  }

  @Test
  void finishedAnalysesAreNormalizedAndViolationsAreEvaluatedLocally() {
    when(dao.findScan(10L)).thenReturn(Optional.of(
        scan("SCANNING", "dt-uuid", "dt-token", NOW.minusSeconds(5))));
    when(dependencyTrack.isConfigured()).thenReturn(true);
    when(dependencyTrack.isProcessing("dt-token")).thenReturn(false);
    List<Finding> findings = List.of(new Finding(
        10L, "pkg:npm/lodash@4.17.20", "CVE-2099-0001", "HIGH", new BigDecimal("9.8"), "NVD",
        "prototype pollution", null));
    when(dependencyTrack.listFindings("dt-uuid")).thenReturn(List.of(
        new DependencyTrackClient.Finding(
            "pkg:npm/lodash@4.17.20", "pkg:npm/lodash@4.17.20", "lodash", "4.17.20",
            "CVE-2099-0001", "HIGH", new BigDecimal("9.8"), "NVD", "prototype pollution", null,
            false)));
    when(dao.listScanComponents(10L)).thenReturn(List.of());
    List<Violation> violations = List.of(new Violation(
        10L, 3L, "pkg:npm/lodash@4.17.20", 8, Map.of("severity", "HIGH")));
    when(scans.evaluatePolicies(eq(5L), eq(10L), any())).thenReturn(violations);
    when(dao.replaceScanComponents(eq(10L), anyString(), any())).thenReturn(true);
    when(dao.replaceScanFindings(eq(10L), anyString(), any())).thenReturn(true);
    when(dao.replaceViolations(eq(10L), anyString(), any())).thenReturn(true);
    when(dao.finishScan(eq(10L), anyString(), anyString(), any(), anyInt(), anyInt(), any()))
        .thenReturn(true);

    worker.runOnce(NOW);

    verify(dao).replaceScanFindings(10L, worker.owner(), findings);
    verify(dao).replaceViolations(10L, worker.owner(), violations);
    verify(dao).replaceScanComponents(eq(10L), eq(worker.owner()), any());
    verify(dao).finishScan(eq(10L), eq(worker.owner()), eq("COMPLETE"), eq(null), eq(1), eq(1),
        any());
  }

  @Test
  void aReplicaThatLostItsLeaseWritesNoSnapshot() {
    when(dao.findScan(10L)).thenReturn(Optional.of(
        scan("SCANNING", "dt-uuid", "dt-token", NOW.minusSeconds(5))));
    when(dependencyTrack.isConfigured()).thenReturn(true);
    when(dependencyTrack.isProcessing("dt-token")).thenReturn(false);
    when(dependencyTrack.listFindings("dt-uuid")).thenReturn(List.of());
    when(scans.evaluatePolicies(eq(5L), eq(10L), any())).thenReturn(List.of());
    // Another replica claimed the scan while this one was reading Dependency-Track.
    when(dao.replaceScanComponents(eq(10L), anyString(), any())).thenReturn(false);

    worker.runOnce(NOW);

    verify(dao, never()).replaceScanFindings(anyLong(), anyString(), any());
    verify(dao, never()).replaceViolations(anyLong(), anyString(), any());
    verify(dao, never()).finishScan(
        eq(10L), eq(worker.owner()), eq("COMPLETE"), any(), anyInt(), anyInt(), any());
  }

  @Test
  void scansOwnedByAnotherReplicaAreNeverProcessed() {
    when(dao.claimScan(eq(10L), anyString(), any(), any())).thenReturn(false);
    when(dependencyTrack.isConfigured()).thenReturn(true);

    worker.runOnce(NOW);

    verify(dao, never()).findScan(anyLong());
    verify(dao, never()).markScanSubmitted(anyLong(), anyString(), anyString(), anyString(), any());
  }

  @Test
  void corruptedSbomsFailTheScanInsteadOfSubmittingGarbage() {
    when(dao.findScan(10L)).thenReturn(Optional.of(scan("PENDING", null, null, null)));
    when(dao.findApplication(5L)).thenReturn(Optional.of(application(null)));
    when(dao.findScanSbom(10L)).thenReturn(Optional.of(
        new ScanSbom(1L, "blob://bucket/key", SHA, SBOM.length() + 4)));
    when(storage.get(any())).thenReturn(Optional.of(
        new ByteArrayInputStream("tampered".getBytes(StandardCharsets.UTF_8))));
    when(dependencyTrack.isConfigured()).thenReturn(true);

    worker.runOnce(NOW);

    verify(dependencyTrack, never()).uploadBom(anyString(), any());
    verify(dao).finishScan(eq(10L), eq(worker.owner()), eq("FAILED"), anyString(), eq(0),
        eq(0), any());
  }

  @Test
  void theWorkerStaysIdleWhenDependencyTrackIsNotConfigured() {
    when(dependencyTrack.isConfigured()).thenReturn(false);

    worker.runOnce(NOW);

    verify(dao, never()).listClaimableScanIds(any(), anyInt());
  }

  @Test
  void timedOutAnalysesAreMarkedFailed() {
    when(dao.findScan(10L)).thenReturn(Optional.of(
        scan("SCANNING", "dt-uuid", "dt-token",
            NOW.minus(properties.getDependencyTrack().getPollTimeout()).minusSeconds(1))));
    when(dependencyTrack.isConfigured()).thenReturn(true);

    worker.runOnce(NOW);

    verify(dao).finishScan(eq(10L), eq(worker.owner()), eq("FAILED"), anyString(), eq(0), eq(0),
        any());
    verify(dependencyTrack, never()).listFindings(anyString());
  }

  private static Application application(String dtProjectUuid) {
    return new Application(5L, 7L, "app-uuid", "orders-api", "Orders API", null, null,
        dtProjectUuid, dtProjectUuid == null ? null : "orders-api", NOW, NOW);
  }

  private static Scan scan(
      String status, String dtProjectUuid, String dtBomToken, Instant submittedAt) {
    return new Scan(
        10L, 5L, "scan-1", status, "build", "build", SHA, null, 0, 0, null, dtProjectUuid,
        dtBomToken, 0, null, null, submittedAt, null, NOW.minusSeconds(30), null, null);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
