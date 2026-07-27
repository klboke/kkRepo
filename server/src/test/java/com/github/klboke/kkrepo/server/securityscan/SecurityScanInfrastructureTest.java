package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao.AuditLogRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.BackfillJob;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.BackfillPage;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanSummary;
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
    SecurityScanMetrics metrics = new SecurityScanMetrics(registry, scans);
    when(scans.summary()).thenReturn(new ScanSummary(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
    when(scans.oldestPendingTaskCreatedAt())
        .thenReturn(Optional.of(Instant.now().minusSeconds(30)));

    var taskTimer = metrics.start();
    metrics.recordTask(
        "MAVEN2", ScanStage.CATALOG_AND_MATCH, RequestReason.MANUAL, "success", taskTimer);
    metrics.recordPolicy("MAVEN2", PolicyDecision.BLOCK_VULNERABILITY, true);
    metrics.recordPolicy(null, null, false);
    metrics.recordInputBytes("MAVEN2", 1024);
    metrics.recordInputBytes("MAVEN2", 0);
    metrics.observeScanner(true, Instant.now().minusSeconds(60));
    metrics.recordStage("MAVEN2", "catalog", "success", metrics.start());
    metrics.recordStage("MAVEN2", "match", "skipped", null);
    metrics.refresh();

    assertEquals(
        2.0, registry.get("kkrepo_security_scan_backlog").gauge().value());
    assertEquals(
        3.0, registry.get("kkrepo_security_scan_running").gauge().value());
    assertEquals(
        1.0,
        registry.get("kkrepo_security_scan_tasks_total")
            .tag("format", "maven2")
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
    SecurityScanCandidateWorker worker = new SecurityScanCandidateWorker(changes, candidates);
    doThrow(new IllegalStateException("changes")).when(changes).processBatch();

    worker.runOnce();

    verify(candidates).processBatch();

    SecurityScanArtifactChangeService secondChanges =
        mock(SecurityScanArtifactChangeService.class);
    SecurityScanCandidateService secondCandidates = mock(SecurityScanCandidateService.class);
    doThrow(new IllegalStateException("candidates")).when(secondCandidates).processBatch();
    new SecurityScanCandidateWorker(secondChanges, secondCandidates).runOnce();
    verify(secondChanges).processBatch();
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
        any(Instant.class));
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
    when(scans.claimBackfillJobs(eq("worker"), any(), any(), eq(1))).thenReturn(List.of());

    assertEquals(List.of(), tasks.claim("worker"));
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
