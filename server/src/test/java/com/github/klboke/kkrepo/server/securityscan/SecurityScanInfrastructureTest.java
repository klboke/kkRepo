package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ArtifactChangeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao.AuditLogRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.BackfillJob;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.BackfillPage;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanMetricSummary;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScannerSnapshot;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.security.scan.ScanEnums.BackfillStatus;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyDecision;
import com.github.klboke.kkrepo.security.scan.ScanEnums.RequestReason;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanStage;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.HttpStatus;

class SecurityScanInfrastructureTest {
  @Test
  void recordsUserAndSystemAuditsWithoutSensitiveRequestState() {
    SecurityAuditDao dao = mock(SecurityAuditDao.class);
    SecurityScanAuditService service = new SecurityScanAuditService(dao);
    HttpServletRequest request = mock(HttpServletRequest.class);
    AuthenticatedSubject actor = mock(AuthenticatedSubject.class);
    when(actor.source()).thenReturn("local");
    when(actor.userId()).thenReturn("admin");
    when(actor.realmId()).thenReturn("local");
    when(request.getRemoteAddr()).thenReturn("127.0.0.1");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRequestURI()).thenReturn("/internal/security/scanning/tasks/1/retry");

    service.record(request, actor, "RETRY", 7L, Map.of("taskId", 1L));
    service.record(null, actor, "READ", null, null);
    service.recordSystem("SNAPSHOT", 7L, Map.of("ready", true));

    ArgumentCaptor<AuditLogRecord> records = ArgumentCaptor.forClass(AuditLogRecord.class);
    verify(dao, org.mockito.Mockito.times(3)).insert(records.capture());
    assertEquals("admin", records.getAllValues().getFirst().actorUserId());
    assertEquals("127.0.0.1", records.getAllValues().getFirst().remoteAddr());
    assertEquals("system", records.getAllValues().getLast().actorSource());
    assertEquals(true, records.getAllValues().getLast().details().get("ready"));
  }

  @Test
  void publishesBoundedMetricsAndRefreshesSharedState() {
    SecurityScanDao scans = mock(SecurityScanDao.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.setMetricsCountLimit(123);
    SecurityScanMetrics metrics = new SecurityScanMetrics(registry, scans, properties);
    when(scans.metricSummary(123)).thenReturn(new ScanMetricSummary(2, 3, 4, 6, 19));
    when(scans.oldestPendingTaskCreatedAt())
        .thenReturn(Optional.of(Instant.now().minusSeconds(30)));

    var taskTimer = metrics.start();
    metrics.recordTask(
        "MAVEN2", ScanStage.CATALOG_AND_MATCH, RequestReason.MANUAL, "success", taskTimer);
    metrics.recordPolicy("MAVEN2", PolicyDecision.BLOCK_VULNERABILITY, true);
    metrics.recordPolicy(null, null, false);
    metrics.recordPolicyEvaluation("MAVEN2", "allow", metrics.start());
    metrics.recordPolicyEvaluation("MAVEN2", "allow", null);
    metrics.recordInputBytes("MAVEN2", 1024);
    metrics.recordInputBytes("MAVEN2", 0);
    metrics.observeScanner(true, Instant.now().minusSeconds(60));
    metrics.recordStage("MAVEN2", "catalog", "success", metrics.start());
    metrics.recordStage("MAVEN2", "match", "skipped", null);
    metrics.recordRetention(new SecurityScanDao.RetentionResult(1, 2, 3, 4, 5, 6));
    metrics.recordRetention(new SecurityScanDao.RetentionResult(0, 0, 0, 0, 0, 0));
    metrics.refresh();

    assertEquals(
        2.0, registry.get("kkrepo_security_scan_backlog").gauge().value());
    assertEquals(
        3.0, registry.get("kkrepo_security_scan_running").gauge().value());
    verify(scans).metricSummary(123);
    assertEquals(
        1.0,
        registry.get("kkrepo_security_scan_tasks_total")
            .tag("format", "maven2")
            .counter()
            .count());
    assertEquals(
        1L,
        registry.get("kkrepo_security_policy_evaluation_duration_seconds")
            .tag("format", "maven2")
            .tag("outcome", "allow")
            .timer()
            .count());
    assertEquals(
        4.0,
        registry.get("kkrepo_security_scan_retention_deleted_total")
            .tag("object", "run")
            .counter()
            .count());
  }

  @Test
  void healthReflectsDeploymentReadinessAndDatabaseFreshness() {
    SecurityScanDao scans = mock(SecurityScanDao.class);
    SecurityScanningProperties properties = new SecurityScanningProperties();
    SecurityScannerHealthIndicator health =
        new SecurityScannerHealthIndicator(scans, properties);

    assertEquals(Status.UP, health.health().getStatus());
    properties.setEnabled(true);
    assertEquals("DEGRADED", health.health().getStatus().getCode());

    ScannerSnapshot snapshot = mock(ScannerSnapshot.class);
    when(snapshot.id()).thenReturn(3L);
    when(scans.latestScannerSnapshot()).thenReturn(Optional.of(snapshot));
    assertEquals("SCANNER_NOT_READY", health.health().getDetails().get("reasonCode"));
    when(snapshot.ready()).thenReturn(true);
    when(snapshot.observedAt()).thenReturn(Instant.now());
    assertEquals("DATABASE_AGE_UNKNOWN", health.health().getDetails().get("reasonCode"));
    when(snapshot.vulnerabilityDatabaseUpdatedAt())
        .thenReturn(Instant.now().minus(Duration.ofDays(3)));
    properties.setScannerDatabaseMaxAge(Duration.ofHours(48));
    assertEquals("DATABASE_STALE", health.health().getDetails().get("reasonCode"));
    when(snapshot.vulnerabilityDatabaseUpdatedAt()).thenReturn(Instant.now());
    assertEquals(Status.UP, health.health().getStatus());
  }

  @Test
  void candidateWorkerContinuesWhenEitherDurableBatchFails() {
    SecurityScanArtifactChangeService changes = mock(SecurityScanArtifactChangeService.class);
    SecurityScanCandidateService candidates = mock(SecurityScanCandidateService.class);
    SecurityScanArtifactChangeWorker changeWorker = new SecurityScanArtifactChangeWorker(changes);
    doThrow(new IllegalStateException("changes")).when(changes).processBatch();

    changeWorker.runOnce();
    new SecurityScanCandidateWorker(candidates).runOnce();

    verify(candidates).processBatch();

    SecurityScanCandidateService secondCandidates = mock(SecurityScanCandidateService.class);
    doThrow(new IllegalStateException("candidates")).when(secondCandidates).processBatch();
    new SecurityScanCandidateWorker(secondCandidates).runOnce();
  }

  @Test
  void backfillWorkerPersistsProgressAndFailureUnderItsLease() {
    SecurityScanDao scans = mock(SecurityScanDao.class);
    SecurityScanBackfillCoordinator coordinator = mock(SecurityScanBackfillCoordinator.class);
    SecurityScanningProperties properties = new SecurityScanningProperties();
    BackfillJob success = backfill(1L, "lease-1");
    BackfillJob failure = backfill(2L, "lease-2");
    when(coordinator.claim(any())).thenReturn(List.of(success, failure));
    when(scans.markRepositoryAssetsForBackfill(7L, 4L, 500))
        .thenReturn(new BackfillPage(3, 2, 10, true))
        .thenThrow(new IllegalStateException("x".repeat(600)));
    when(scans.updateBackfillProgress(
        eq(1L),
        eq("lease-1"),
        eq(10L),
        eq(8L),
        eq(8L),
        eq(BackfillStatus.SUCCEEDED),
        eq(null),
        eq(null),
        any(Instant.class)))
        .thenReturn(false);

    new SecurityScanBackfillWorker(scans, coordinator, properties).runOnce();

    verify(scans).updateBackfillProgress(
        eq(2L),
        eq("lease-2"),
        eq(4L),
        eq(5L),
        eq(6L),
        eq(BackfillStatus.FAILED),
        org.mockito.ArgumentMatchers.argThat(message -> message.length() == 512),
        eq(null),
        any(Instant.class));
  }

  @Test
  void backfillWorkerProcessesMultiplePagesAndReleasesLongJobsFairly() {
    SecurityScanDao scans = mock(SecurityScanDao.class);
    SecurityScanBackfillCoordinator coordinator = mock(SecurityScanBackfillCoordinator.class);
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.getWorker().setBackfillBatchSize(10);
    properties.getWorker().setBackfillMaxPagesPerRun(2);
    properties.getWorker().setLeaseSeconds(30);
    BackfillJob job = backfill(1L, "lease-1");
    when(coordinator.claim(any())).thenReturn(List.of(job));
    when(scans.markRepositoryAssetsForBackfill(7L, 4L, 10))
        .thenReturn(new BackfillPage(10, 6, 14, false));
    when(scans.markRepositoryAssetsForBackfill(7L, 14L, 10))
        .thenReturn(new BackfillPage(10, 4, 24, false));
    when(scans.updateBackfillProgress(
            eq(1L),
            eq("lease-1"),
            eq(14L),
            eq(15L),
            eq(12L),
            eq(BackfillStatus.RUNNING),
            eq(null),
            any(Instant.class),
            any(Instant.class)))
        .thenReturn(true);
    when(scans.updateBackfillProgress(
            eq(1L),
            eq("lease-1"),
            eq(24L),
            eq(25L),
            eq(16L),
            eq(BackfillStatus.PENDING),
            eq(null),
            eq(null),
            any(Instant.class)))
        .thenReturn(true);

    new SecurityScanBackfillWorker(scans, coordinator, properties).runOnce();

    verify(scans).markRepositoryAssetsForBackfill(7L, 14L, 10);
    verify(scans).updateBackfillProgress(
        eq(1L),
        eq("lease-1"),
        eq(24L),
        eq(25L),
        eq(16L),
        eq(BackfillStatus.PENDING),
        eq(null),
        eq(null),
        any(Instant.class));
  }

  @Test
  void refreshesArtifactEventMetricsAndRunsBoundedRetention() {
    ArtifactChangeDao changes = mock(ArtifactChangeDao.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    when(changes.retainedRange())
        .thenReturn(Optional.of(
            new ArtifactChangeDao.EventRange(10L, 21L, Instant.now().minusSeconds(30))));
    SecurityScanArtifactChangeMetrics eventMetrics =
        new SecurityScanArtifactChangeMetrics(changes, registry);

    eventMetrics.refresh();

    assertEquals(
        12.0,
        registry.get("kkrepo_security_scan_artifact_event_backlog").gauge().value());
    assertTrue(
        registry
                .get("kkrepo_security_scan_artifact_event_oldest_age_seconds")
                .gauge()
                .value()
            >= 29);
    when(changes.retainedRange()).thenReturn(Optional.empty());
    eventMetrics.refresh();
    assertEquals(
        0.0,
        registry.get("kkrepo_security_scan_artifact_event_backlog").gauge().value());
    assertEquals(
        0.0,
        registry
            .get("kkrepo_security_scan_artifact_event_oldest_age_seconds")
            .gauge()
            .value());

    SecurityScanDao scans = mock(SecurityScanDao.class);
    SecurityScanMetrics metrics = mock(SecurityScanMetrics.class);
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.getRetention().setTerminalTaskDays(7);
    properties.getRetention().setResultDays(30);
    properties.getRetention().setBatchSize(50);
    SecurityScanDao.RetentionResult result =
        new SecurityScanDao.RetentionResult(1, 2, 3, 4, 5, 6);
    when(scans.cleanupRetainedData(any(), any(), eq(50))).thenReturn(result);

    new SecurityScanRetentionWorker(scans, properties, metrics).runOnce();

    verify(scans).cleanupRetainedData(any(), any(), eq(50));
    verify(metrics).recordRetention(result);
    assertEquals(21, result.total());
  }

  @Test
  void coordinatorsClaimBoundedLeasesFromSharedStorage() {
    SecurityScanDao scans = mock(SecurityScanDao.class);
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.getWorker().setBatchSize(6);
    SecurityScanTaskCoordinator tasks = new SecurityScanTaskCoordinator(scans, properties);
    SecurityScanBackfillCoordinator backfills =
        new SecurityScanBackfillCoordinator(scans, properties);
    when(scans.claimTasks(eq("worker"), any(), any(), eq(6))).thenReturn(List.of());
    when(scans.claimExpiredExhaustedTasks(eq("worker"), any(), any(), eq(6)))
        .thenReturn(List.of());
    when(scans.claimBackfillJobs(eq("worker"), any(), any(), eq(1))).thenReturn(List.of());

    assertEquals(List.of(), tasks.claim("worker"));
    assertEquals(List.of(), tasks.claimExpiredExhausted("worker"));
    assertEquals(List.of(), backfills.claim("worker"));
  }

  @Test
  void storesReusesAndReadsImmutableScannerDocuments() throws Exception {
    AssetDao assets = mock(AssetDao.class);
    RepositoryDao repositories = mock(RepositoryDao.class);
    BlobStorageRegistry storages = mock(BlobStorageRegistry.class);
    BlobStorage storage = mock(BlobStorage.class);
    RepositoryRecord repository = mock(RepositoryRecord.class);
    when(repository.blobStoreId()).thenReturn(4L);
    when(repository.name()).thenReturn("maven-hosted");
    when(repositories.findById(7L)).thenReturn(Optional.of(repository));
    when(storages.forBlobStoreId(4L)).thenReturn(storage);
    byte[] bytes = "{}".getBytes();
    BlobReference reference = new BlobReference("bucket", "scanner/report.json", null, 2);
    when(storage.put(eq("maven-hosted"), any(), any(), eq(2L), any()))
        .thenReturn(reference);
    AssetBlobRecord stored = mock(AssetBlobRecord.class);
    when(stored.id()).thenReturn(9L);
    when(assets.insertBlobOrFindExisting(any())).thenReturn(stored);
    SecurityScanDocumentStore documents =
        new SecurityScanDocumentStore(assets, repositories, storages);

    var created = documents.store(7L, "../report", bytes, "application/json");

    assertEquals(9L, created.blobId());
    assertEquals(64, created.sha256().length());
    AssetBlobRecord reusable = mock(AssetBlobRecord.class);
    when(reusable.id()).thenReturn(10L);
    when(assets.findReusableBlobBySha256(eq(4L), any(), eq(2L)))
        .thenReturn(Optional.of(reusable));
    assertEquals(10L, documents.store(7L, null, bytes, "application/json").blobId());

    when(assets.findBlobById(9L)).thenReturn(Optional.of(stored));
    when(stored.blobStoreId()).thenReturn(4L);
    when(stored.objectKey()).thenReturn("scanner/report.json");
    when(stored.sha256()).thenReturn(created.sha256());
    when(stored.size()).thenReturn(2L);
    when(storage.get(any())).thenReturn(Optional.of(new ByteArrayInputStream(bytes)));
    assertArrayEquals(bytes, documents.open(9L).readAllBytes());
    when(assets.findBlobById(11L)).thenReturn(Optional.empty());
    assertThrows(IOException.class, () -> documents.open(11L));
    assertThrows(
        IllegalArgumentException.class,
        () -> documents.store(7L, "report", null, "application/json"));
  }

  @Test
  void rendersRepositoryAndDockerPolicyFailures() {
    ArtifactDownloadPolicyAdvice advice = new ArtifactDownloadPolicyAdvice();
    HttpServletRequest docker = mock(HttpServletRequest.class);
    when(docker.getRequestURI()).thenReturn("/v2/library/demo/manifests/latest");

    var pending =
        advice.blocked(new ArtifactPolicyException(PolicyDecision.BLOCK_PENDING, 12), docker);
    var denied = advice.blocked(
        new ArtifactPolicyException(PolicyDecision.BLOCK_VULNERABILITY, 0), null);

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, pending.getStatusCode());
    assertEquals("12", pending.getHeaders().getFirst("Retry-After"));
    assertNotNull(pending.getBody());
    assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
  }

  private static BackfillJob backfill(long id, String lease) {
    return new BackfillJob(
        id,
        7L,
        BackfillStatus.RUNNING,
        4L,
        5L,
        6L,
        1,
        "worker",
        lease,
        Instant.now().plusSeconds(60),
        null,
        "admin",
        Instant.now(),
        Instant.now(),
        null);
  }
}
