package com.github.klboke.kkrepo.server.kkguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Application;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.ApplicationTag;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Finding;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Policy;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Scan;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Violation;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class KkGuardScanServiceTest {
  private static final String APP = "22222222-2222-2222-2222-222222222222";
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final String SBOM =
      "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.5\",\"components\":[]}";

  private KkGuardDao dao;
  private RepositoryDao repositories;
  private BlobStoreDao blobStores;
  private BlobStorageRegistry storages;
  private BlobStorage storage;
  private KkGuardScanService service;

  @BeforeEach
  void setUp() {
    dao = mock(KkGuardDao.class);
    repositories = mock(RepositoryDao.class);
    blobStores = mock(BlobStoreDao.class);
    storages = mock(BlobStorageRegistry.class);
    storage = mock(BlobStorage.class);
    service = new KkGuardScanService(
        dao, new KkGuardProperties(), repositories, blobStores, storages, new ObjectMapper());
    when(dao.findApplicationByExternalId(APP)).thenReturn(Optional.of(application()));
    when(blobStores.list()).thenReturn(List.of(
        new BlobStoreRecord(1L, "default", "s3", null, null, "bucket", null, Map.of())));
    when(storages.forBlobStoreId(1L)).thenReturn(storage);
  }

  @Test
  void submitStoresAnImmutableSbomAndQueuesAPendingScan() {
    when(dao.findCompletedScanBySbom(anyLong(), anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(storage.put(eq("kkguard"), anyString(), any(), anyLong(), anyString()))
        .thenReturn(new BlobReference("bucket", ".kkrepo/kkguard/sbom/x.json", "sha", 10));
    when(dao.insertScan(any())).thenReturn(10L);
    when(dao.findScan(10L)).thenReturn(Optional.of(scan("PENDING", null, null, null, 0)));

    var status = service.submit(APP, "build", "application/json", sbom());

    assertEquals("PENDING", status.status());
    assertEquals("build", status.sourceId());
    ArgumentCaptor<Scan> inserted = ArgumentCaptor.forClass(Scan.class);
    verify(dao).insertScan(inserted.capture());
    assertEquals("PENDING", inserted.getValue().status());
    assertEquals(64, inserted.getValue().sbomSha256().length());
    verify(dao).attachScanSbom(eq(10L), eq(1L), eq("blob://bucket/.kkrepo/kkguard/sbom/x.json"),
        eq(inserted.getValue().sbomSha256()), eq((long) sbom().length));
    verify(storage).put(eq("kkguard"), eq(".kkrepo/kkguard/sbom/"
        + inserted.getValue().sbomSha256() + ".json"), any(), eq((long) sbom().length),
        eq(inserted.getValue().sbomSha256()));
  }

  @Test
  void submitRejectsDocumentsThatAreNotCycloneDx() {
    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> service.submit(APP, "build", "application/json",
            "{\"bomFormat\":\"SPDX\"}".getBytes(StandardCharsets.UTF_8)));

    assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    verify(storage, never()).put(anyString(), anyString(), any(), anyLong(), anyString());
  }

  @Test
  void submitRejectsEmptyAndOversizedDocuments() {
    assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
        () -> service.submit(APP, "build", "application/json", new byte[0])).getStatusCode());

    KkGuardProperties properties = new KkGuardProperties();
    properties.setMaxSbomBytes(1024);
    KkGuardScanService small = new KkGuardScanService(
        dao, properties, repositories, blobStores, storages, new ObjectMapper());
    byte[] oversized = ("{\"bomFormat\":\"CycloneDX\",\"components\":\""
        + "x".repeat(2048) + "\"}").getBytes(StandardCharsets.UTF_8);
    assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, assertThrows(ResponseStatusException.class,
        () -> small.submit(APP, "build", "application/json", oversized)).getStatusCode());
  }

  @Test
  void identicalSbomsReuseTheCompletedScan() {
    when(dao.findCompletedScanBySbom(anyLong(), anyString(), anyString()))
        .thenReturn(Optional.of(scan("COMPLETE", "dt-project", "dt-token", NOW, 3)));

    var status = service.submit(APP, "build", "application/json", sbom());

    assertEquals("COMPLETE", status.status());
    assertEquals(3, status.violationCount());
    verify(storage, never()).put(anyString(), anyString(), any(), anyLong(), anyString());
    verify(dao, never()).insertScan(any());
  }

  @Test
  void statusReturnsTheLatestScanOrNotFound() {
    when(dao.findLatestScanByApplicationAndSource(5L, "build"))
        .thenReturn(Optional.of(scan("SCANNING", "dt-project", "dt-token", NOW, 0)));

    assertEquals("SCANNING", service.status(APP, "build").status());

    when(dao.findLatestScanByApplicationAndSource(5L, "release")).thenReturn(Optional.empty());
    assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
        () -> service.status(APP, "release")).getStatusCode());
  }

  @Test
  void reportsExposeComponentsFindingsAndLocalViolations() {
    when(dao.findScanByScanId("scan-1"))
        .thenReturn(Optional.of(scan("COMPLETE", "dt-project", "dt-token", NOW, 1)));
    when(dao.listScanComponents(10L)).thenReturn(List.of(
        new KkGuardDao.ScanComponent(
            10L, "pkg:npm/lodash@4.17.20", "pkg:npm/lodash@4.17.20", "lodash", "4.17.20",
            null, "HIGH")));
    when(dao.listScanFindings(10L)).thenReturn(List.of(finding()));
    when(dao.listViolations(10L)).thenReturn(List.of(
        new Violation(10L, 3L, "pkg:npm/lodash@4.17.20", 8, Map.of("severity", "HIGH"))));
    when(dao.findPolicy(3L)).thenReturn(Optional.of(policy()));

    var report = service.report(APP, "scan-1");

    assertEquals("COMPLETE", report.scan().status());
    assertEquals(1, report.components().size());
    assertEquals("HIGH", report.components().get(0).highestSeverity());
    assertEquals("CVE-2099-0001", report.findings().get(0).vulnerabilityId());
    assertEquals("ext-policy", report.violations().get(0).policyId());
    assertEquals("high severity", report.violations().get(0).policyName());
  }

  @Test
  void componentInventoriesKeepTheHighestSeverityPerComponent() {
    List<Finding> findings = List.of(
        new Finding(10L, "pkg:npm/demo@1.0", "CVE-1", "LOW", null, null, null, null),
        new Finding(10L, "pkg:npm/demo@1.0", "CVE-2", "CRITICAL", null, null, null, null),
        new Finding(10L, "pkg:npm/other@2.0", "CVE-3", "MEDIUM", null, null, null, null));

    var components = KkGuardScanService.componentsOf(10L, findings);

    assertEquals(2, components.size());
    assertEquals("CRITICAL", components.get(0).highestSeverity());
    assertEquals("MEDIUM", components.get(1).highestSeverity());
  }

  @Test
  void localPoliciesOnlyProduceViolationsForMatchingFindings() {
    when(dao.findApplication(5L)).thenReturn(Optional.of(application()));
    when(dao.listPolicies(7L)).thenReturn(List.of(
        policy(),
        new KkGuardDao.Policy(4L, 7L, "ext-low", "low-only", "low only", 2,
            Map.of("severity", "CRITICAL"), NOW, NOW)));

    var violations = service.evaluatePolicies(5L, 10L, List.of(finding()));

    assertEquals(1, violations.size());
    assertEquals(3L, violations.get(0).policyId());
    assertEquals(8, violations.get(0).threatLevel());
  }

  private static byte[] sbom() {
    return SBOM.getBytes(StandardCharsets.UTF_8);
  }

  private static Application application() {
    return new Application(5L, 7L, APP, "orders-api", "Orders API", null, null, null, null,
        NOW, NOW);
  }

  private static Finding finding() {
    return new Finding(10L, "pkg:npm/lodash@4.17.20", "CVE-2099-0001", "HIGH",
        new BigDecimal("9.8"), "NVD", "prototype pollution", "https://example.test/CVE");
  }

  private static Policy policy() {
    return new Policy(3L, 7L, "ext-policy", "policy-public", "high severity", 8,
        Map.of("severity", "HIGH"), NOW, NOW);
  }

  private static Scan scan(
      String status, String dtProjectUuid, String dtBomToken, Instant submittedAt,
      int violationCount) {
    return new Scan(
        10L, 5L, "scan-1", status, "build", "build", "a".repeat(64), null, 1, violationCount,
        null, dtProjectUuid, dtBomToken, 0, null, null, submittedAt, null, NOW, NOW, NOW);
  }
}
