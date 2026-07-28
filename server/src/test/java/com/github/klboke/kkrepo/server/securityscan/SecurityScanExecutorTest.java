package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.AssetSecurityState;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.RepositoryScanConfig;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.Sbom;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanCandidate;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanProfile;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanRun;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanTask;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScannerSnapshot;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestRecord;
import com.github.klboke.kkrepo.security.scan.ScanEnums.CandidateDisposition;
import com.github.klboke.kkrepo.security.scan.ScanEnums.EnforcementMode;
import com.github.klboke.kkrepo.security.scan.ScanEnums.OciPlatformPolicy;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyAction;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanStage;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanState;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
import com.github.klboke.kkrepo.security.scan.ScanEnums.SubjectKind;
import com.github.klboke.kkrepo.security.scan.ScanEnums.TargetClassification;
import com.github.klboke.kkrepo.security.scan.ScanFingerprints;
import com.github.klboke.kkrepo.security.scan.ScanSubject;
import com.github.klboke.kkrepo.security.scan.ScannerContract;
import com.github.klboke.kkrepo.security.scan.ScannerContract.CatalogResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Component;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Finding;
import com.github.klboke.kkrepo.security.scan.ScannerContract.MatchResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.OciScanResponse;
import com.github.klboke.kkrepo.server.docker.DockerAuthService;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SecurityScanExecutorTest {
  private static final String SHA256 = "a".repeat(64);
  private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");

  @Test
  void catalogsMatchesPersistsAndFinalizesAnOrdinaryArtifact() throws Exception {
    Fixture fixture = new Fixture(ScanStage.CATALOG_AND_MATCH, SubjectKind.ASSET_BLOB);
    when(fixture.adapter.catalog(any(), any())).thenAnswer(invocation -> {
      ScannerContract.InputStreamSource input = invocation.getArgument(1);
      assertEquals("artifact", new String(input.open().readAllBytes()));
      return catalogResponse();
    });
    when(fixture.adapter.match(any(), any())).thenAnswer(invocation -> {
      ScannerContract.InputStreamSource input = invocation.getArgument(1);
      assertEquals("{}", new String(input.open().readAllBytes()));
      return matchResponse();
    });

    ScanRun run = fixture.executor.execute(fixture.task);

    assertEquals(ScanState.COMPLETE, run.status());
    assertEquals(2, run.findingCount());
    assertEquals(1, run.fixableFindingCount());
    assertEquals(Severity.HIGH, run.maxSeverity());
    verify(fixture.scans).insertSbomComponents(anyLong(), anyList());
    verify(fixture.metrics).recordInputBytes("MAVEN2", 8);
  }

  @Test
  void reusesPriorSbomAndMatchRunWithoutCallingTheAdapter() {
    Fixture fixture = new Fixture(ScanStage.MATCH_ONLY, SubjectKind.ASSET_BLOB);
    Sbom sbom = storedSbom();
    ScanRun prior = storedRun(88L, sbom.id());
    AssetSecurityState state = mock(AssetSecurityState.class);
    when(state.latestScanRunId()).thenReturn(prior.id());
    when(fixture.scans.findAssetState(10L, 1L)).thenReturn(Optional.of(state));
    when(fixture.scans.findRun(prior.id())).thenReturn(Optional.of(prior));
    when(fixture.scans.findSbom(sbom.id())).thenReturn(Optional.of(sbom));
    when(fixture.scans.findRunByMatchFingerprint(anyString())).thenReturn(Optional.of(prior));

    assertEquals(prior, fixture.executor.execute(fixture.task));
    verify(fixture.finalizer)
        .finalizeRun(
            fixture.task,
            fixture.profile,
            fixture.config,
            "sha256:" + SHA256,
            prior,
            List.of());
  }

  @Test
  void policyOnlyUsesTheCurrentGenerationRun() {
    Fixture fixture = new Fixture(ScanStage.POLICY_ONLY, SubjectKind.ASSET_BLOB);
    ScanRun prior = storedRun(88L, 20L);
    AssetSecurityState state = mock(AssetSecurityState.class);
    when(state.contentGeneration()).thenReturn(2L);
    when(state.latestScanRunId()).thenReturn(prior.id());
    when(fixture.scans.findAssetState(10L, 1L)).thenReturn(Optional.of(state));
    when(fixture.scans.findRun(prior.id())).thenReturn(Optional.of(prior));

    assertEquals(prior, fixture.executor.execute(fixture.task));
  }

  @Test
  void scansOciManifestAndMarksMissingPlatformsPartial() throws Exception {
    Fixture fixture = new Fixture(ScanStage.CATALOG_AND_MATCH, SubjectKind.OCI_MANIFEST);
    DockerManifestRecord manifest = mock(DockerManifestRecord.class);
    when(manifest.imageName()).thenReturn("acme/demo");
    when(manifest.digest()).thenReturn("sha256:" + "b".repeat(64));
    when(fixture.docker.findManifestsByAssetIds(List.of(10L)))
        .thenReturn(Map.of(10L, manifest));
    when(fixture.dockerAuth.grantScannerPull("repo", "acme/demo", 180L))
        .thenReturn("scanner-token");
    when(fixture.adapter.scanOci(any())).thenReturn(new OciScanResponse(
        catalogResponse(),
        matchResponse(),
        List.of("linux/amd64"),
        List.of("linux/arm64")));

    ScanRun run = fixture.executor.execute(fixture.task);

    assertEquals(ScanState.PARTIAL, run.status());
    assertEquals(ScanCompleteness.PARTIAL, run.scanCompleteness());
    assertEquals(List.of("linux/amd64"), run.scannedPlatforms());
    assertEquals(List.of("linux/arm64"), run.missingPlatforms());
    ArgumentCaptor<Sbom> sbom = ArgumentCaptor.forClass(Sbom.class);
    verify(fixture.scans).insertSbomOrFindExisting(sbom.capture());
    ScanSubject subject = new ScanSubject(
        SubjectKind.OCI_MANIFEST,
        1L,
        10L,
        11L,
        "sha256:" + SHA256,
        SHA256,
        8L,
        RepositoryFormat.MAVEN2.name(),
        "manifest",
        "application/octet-stream",
        TargetClassification.OCI_IMAGE,
        fixture.profile.requiredPlatforms(),
        Map.of("path", "acme/demo.jar"));
    assertEquals(
        ScanFingerprints.catalog(
            subject,
            "syft",
            "1.0",
            fixture.profile.configurationDigest(),
            List.of("linux/amd64"),
            List.of("linux/arm64")),
        sbom.getValue().catalogFingerprint());
    verify(fixture.adapter).scanOci(any());
  }

  @Test
  void carriesCatalogProjectionIncompletenessIntoTheFinalRun() throws Exception {
    Fixture fixture = new Fixture(ScanStage.CATALOG_AND_MATCH, SubjectKind.ASSET_BLOB);
    CatalogResponse completeDocumentWithTruncatedProjection = new CatalogResponse(
        "adapter",
        "1",
        "syft",
        "1.0",
        "cap",
        SHA256,
        ScanCompleteness.COMPLETE,
        "CycloneDX",
        "1.5",
        100_001,
        0,
        "{\"bomFormat\":\"CycloneDX\"}".getBytes(),
        catalogResponse().components(),
        Map.of());
    when(fixture.adapter.catalog(any(), any()))
        .thenReturn(completeDocumentWithTruncatedProjection);
    when(fixture.adapter.match(any(), any())).thenReturn(matchResponse());

    ScanRun run = fixture.executor.execute(fixture.task);

    assertEquals(ScanState.PARTIAL, run.status());
    assertEquals(ScanCompleteness.PARTIAL, run.scanCompleteness());
  }

  @Test
  void rejectsStaleMissingAndUnscannableSubjectsWithStableCodes() {
    Fixture missingLease = new Fixture(ScanStage.CATALOG_AND_MATCH, SubjectKind.ASSET_BLOB);
    when(missingLease.task.leaseToken()).thenReturn(null);
    assertCode("TASK_SUBJECT_INVALID", () -> missingLease.executor.execute(missingLease.task));

    Fixture missingAsset = new Fixture(ScanStage.CATALOG_AND_MATCH, SubjectKind.ASSET_BLOB);
    when(missingAsset.assets.findAssetWithBlobById(10L)).thenReturn(Optional.empty());
    assertCode("ASSET_NOT_FOUND", () -> missingAsset.executor.execute(missingAsset.task));

    Fixture missingBlobIdentity =
        new Fixture(ScanStage.CATALOG_AND_MATCH, SubjectKind.ASSET_BLOB);
    AssetBlobRecord blob = mock(AssetBlobRecord.class);
    when(missingBlobIdentity.assets.findAssetWithBlobById(10L))
        .thenReturn(Optional.of(new AssetDao.AssetWithBlob(missingBlobIdentity.asset, blob)));
    assertCode(
        "BLOB_IDENTITY_MISSING",
        () -> missingBlobIdentity.executor.execute(missingBlobIdentity.task));

    Fixture stale = new Fixture(ScanStage.CATALOG_AND_MATCH, SubjectKind.ASSET_BLOB);
    when(stale.candidate.contentGeneration()).thenReturn(3L);
    assertThrows(
        SecurityScanExecutor.SupersededSecurityScanTaskException.class,
        () -> stale.executor.execute(stale.task));

    Fixture missingRepository =
        new Fixture(ScanStage.CATALOG_AND_MATCH, SubjectKind.ASSET_BLOB);
    when(missingRepository.repositories.findById(1L)).thenReturn(Optional.empty());
    assertCode(
        "REPOSITORY_NOT_FOUND",
        () -> missingRepository.executor.execute(missingRepository.task));

    Fixture disabledProfile =
        new Fixture(ScanStage.CATALOG_AND_MATCH, SubjectKind.ASSET_BLOB);
    when(disabledProfile.scans.findProfile(1L)).thenReturn(Optional.empty());
    assertCode(
        "PROFILE_UNAVAILABLE",
        () -> disabledProfile.executor.execute(disabledProfile.task));

    Fixture unscannable = new Fixture(ScanStage.CATALOG_AND_MATCH, SubjectKind.ASSET_BLOB);
    when(unscannable.classifier.classify(any(), any(), any()))
        .thenReturn(new SecurityScanCandidateClassifier.Classification(
            CandidateDisposition.NOT_APPLICABLE, null, null, "METADATA"));
    assertCode("SUBJECT_NOT_SCANNABLE", () -> unscannable.executor.execute(unscannable.task));
  }

  @Test
  void translatesAdapterIoAndRejectsInvalidScannerDocuments() throws Exception {
    Fixture catalogIo = new Fixture(ScanStage.CATALOG_AND_MATCH, SubjectKind.ASSET_BLOB);
    when(catalogIo.adapter.catalog(any(), any())).thenThrow(new IOException("down"));
    ScannerAdapterException catalogError = assertCode(
        "SCANNER_IO", () -> catalogIo.executor.execute(catalogIo.task));
    assertTrue(catalogError.retryable());

    Fixture wrongChecksum = new Fixture(ScanStage.CATALOG_AND_MATCH, SubjectKind.ASSET_BLOB);
    CatalogResponse invalid = new CatalogResponse(
        "adapter", "1", "syft", "1", "cap", "b".repeat(64),
        ScanCompleteness.COMPLETE, "CycloneDX", "1.5", 0, 0,
        "{}".getBytes(), List.of(), Map.of());
    when(wrongChecksum.adapter.catalog(any(), any())).thenReturn(invalid);
    assertCode(
        "SCANNER_INPUT_CHECKSUM_MISMATCH",
        () -> wrongChecksum.executor.execute(wrongChecksum.task));

    Fixture invalidJson = new Fixture(ScanStage.CATALOG_AND_MATCH, SubjectKind.ASSET_BLOB);
    CatalogResponse malformed = new CatalogResponse(
        "adapter", "1", "syft", "1", "cap", SHA256,
        ScanCompleteness.COMPLETE, "CycloneDX", "1.5", 0, 0,
        "not-json".getBytes(), List.of(), Map.of());
    when(invalidJson.adapter.catalog(any(), any())).thenReturn(malformed);
    assertCode("SBOM_INVALID_JSON", () -> invalidJson.executor.execute(invalidJson.task));

    Fixture matchIo = new Fixture(ScanStage.CATALOG_AND_MATCH, SubjectKind.ASSET_BLOB);
    when(matchIo.scans.findReusableSbom(any(), any(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(storedSbom()));
    when(matchIo.adapter.match(any(), any())).thenThrow(new IOException("down"));
    assertCode("SCANNER_IO", () -> matchIo.executor.execute(matchIo.task));

    Fixture invalidMatch = new Fixture(ScanStage.CATALOG_AND_MATCH, SubjectKind.ASSET_BLOB);
    when(invalidMatch.scans.findReusableSbom(any(), any(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(storedSbom()));
    when(invalidMatch.adapter.match(any(), any())).thenReturn(new MatchResponse(
        "adapter", "1", "grype", "", "", null, "cap",
        ScanCompleteness.COMPLETE, new byte[0], List.of(), Map.of()));
    assertCode("SCANNER_PROVENANCE_MISSING", () -> invalidMatch.executor.execute(invalidMatch.task));
  }

  private static ScannerAdapterException assertCode(String code, Runnable invocation) {
    ScannerAdapterException exception =
        assertThrows(ScannerAdapterException.class, invocation::run);
    assertEquals(code, exception.code());
    return exception;
  }

  private static CatalogResponse catalogResponse() {
    Component component = new Component(
        "pkg:maven/acme/demo@1", "pkg:maven/acme/demo@1", "library", "acme",
        "demo", "1", "direct", List.of("demo.jar"), List.of("Apache-2.0"), Map.of());
    return new CatalogResponse(
        "adapter", "1", "syft", "1.0", "cap", SHA256,
        ScanCompleteness.COMPLETE, "CycloneDX", "1.5", 1, 0,
        "{\"bomFormat\":\"CycloneDX\"}".getBytes(), List.of(component), Map.of());
  }

  private static MatchResponse matchResponse() {
    Finding high = new Finding(
        "", "CVE-2026-0001", List.of("GHSA-test"), "fixture",
        "pkg:maven/acme/demo@1", "demo", "1", List.of("2"), Severity.HIGH,
        "fixture", "CVSS:3.1/AV:N", 8.0, "title", "description",
        "https://example.invalid/CVE-2026-0001", List.of("demo.jar"), "active");
    Finding unknown = new Finding(
        "known-key", "CVE-2026-0002", null, "fixture", null, "other", "1",
        null, null, "fixture", null, null, null, null, null, null, null);
    return new MatchResponse(
        "adapter", "1", "grype", "2.0", "db-1", NOW, "cap",
        ScanCompleteness.COMPLETE, "{}".getBytes(), List.of(high, unknown), null);
  }

  private static Sbom storedSbom() {
    return new Sbom(
        20L,
        SubjectKind.ASSET_BLOB,
        "sha256:" + SHA256,
        PersistenceHashes.sha256("sha256:" + SHA256),
        "syft",
        "1.0",
        "c".repeat(64),
        "catalog-fingerprint",
        30L,
        "d".repeat(64),
        "CycloneDX",
        "1.5",
        1,
        0,
        true,
        NOW);
  }

  private static ScanRun storedRun(long id, long sbomId) {
    return new ScanRun(
        id, 5L, sbomId, 40L, "c".repeat(64), "match-fingerprint",
        ScanState.COMPLETE, ScanCompleteness.COMPLETE, 31L, "e".repeat(64),
        0, 0, 0, 0, 0, 0, 0, Severity.UNKNOWN, NOW, NOW, NOW);
  }

  private static final class Fixture {
    final SecurityScanDao scans = mock(SecurityScanDao.class);
    final AssetDao assets = mock(AssetDao.class);
    final RepositoryDao repositories = mock(RepositoryDao.class);
    final DockerRegistryDao docker = mock(DockerRegistryDao.class);
    final BlobStorageRegistry storages = mock(BlobStorageRegistry.class);
    final SecurityScanCandidateClassifier classifier =
        mock(SecurityScanCandidateClassifier.class);
    final ScannerContract.Adapter adapter = mock(ScannerContract.Adapter.class);
    final SecurityScannerSnapshotService snapshots =
        mock(SecurityScannerSnapshotService.class);
    final SecurityScanDocumentStore documents = mock(SecurityScanDocumentStore.class);
    final SecurityScanFinalizer finalizer = mock(SecurityScanFinalizer.class);
    final DockerAuthService dockerAuth = mock(DockerAuthService.class);
    final SecurityScanningProperties properties = new SecurityScanningProperties();
    final SecurityScanMetrics metrics = mock(SecurityScanMetrics.class);
    final SecurityScanRepositoryScope scope = mock(SecurityScanRepositoryScope.class);
    final ScanTask task = mock(ScanTask.class);
    final ScanCandidate candidate = mock(ScanCandidate.class);
    final AssetRecord asset;
    final AssetBlobRecord blob;
    final ScanProfile profile;
    final RepositoryScanConfig config;
    final ScannerSnapshot snapshot;
    final SecurityScanExecutor executor;

    Fixture(ScanStage stage, SubjectKind kind) {
      asset = new AssetRecord(
          10L, 1L, null, 11L, RepositoryFormat.MAVEN2, "acme/demo.jar",
          PersistenceHashes.pathHash("acme/demo.jar"), "demo.jar",
          kind == SubjectKind.OCI_MANIFEST ? "manifest" : "artifact",
          "application/octet-stream", 8L, null, NOW, Map.of());
      blob = new AssetBlobRecord(
          11L, 2L, "blob://test/object", PersistenceHashes.blobRefHash("blob://test/object"),
          "object", PersistenceHashes.objectKeyHash("object"), "1".repeat(40), SHA256,
          "2".repeat(32), 8L, "application/octet-stream", "test", "127.0.0.1",
          NOW, NOW, Map.of());
      profile = new ScanProfile(
          1L, "default", true, "syft", "grype", List.of("vuln"), Map.of(),
          1024, 100, 4096, 1024, 2, 60, OciPlatformPolicy.REQUIRED_SET,
          List.of("linux/amd64", "linux/arm64"), "c".repeat(64), 1, NOW, NOW);
      config = new RepositoryScanConfig(
          1L, true, 1L, true, true, EnforcementMode.AUDIT,
          PolicyAction.ALLOW, PolicyAction.ALLOW, PolicyAction.ALLOW,
          null, null, 1, NOW, NOW);
      snapshot = new ScannerSnapshot(
          40L, "adapter", "v1", "grype", "2.0", "db-1", NOW, "cap",
          "snapshot", NOW, true, Map.of("catalogEngineVersion", "1.0"));

      when(task.id()).thenReturn(5L);
      when(task.repositoryId()).thenReturn(1L);
      when(task.assetId()).thenReturn(10L);
      when(task.subjectKey()).thenReturn("sha256:" + SHA256);
      when(task.contentGeneration()).thenReturn(2L);
      when(task.profileId()).thenReturn(1L);
      when(task.stage()).thenReturn(stage);
      when(task.leaseToken()).thenReturn("lease");
      when(task.startedAt()).thenReturn(NOW);
      when(assets.findAssetWithBlobById(10L))
          .thenReturn(Optional.of(new AssetDao.AssetWithBlob(asset, blob)));
      when(candidate.contentGeneration()).thenReturn(2L);
      when(candidate.assetBlobId()).thenReturn(11L);
      when(scans.findCandidate(10L)).thenReturn(Optional.of(candidate));
      when(repositories.findById(1L)).thenReturn(Optional.of(new RepositoryRecord(
          1L, "repo", RepositoryFormat.MAVEN2, RepositoryType.HOSTED,
          "maven2-hosted", true, 2L, null, null, null, null, null, true, Map.of())));
      when(scans.findProfile(1L)).thenReturn(Optional.of(profile));
      when(scope.effectiveConfig(1L, 1L)).thenReturn(Optional.of(config));
      when(classifier.classify(asset, blob, profile))
          .thenReturn(new SecurityScanCandidateClassifier.Classification(
              CandidateDisposition.SCANNABLE,
              kind,
              kind == SubjectKind.OCI_MANIFEST
                  ? TargetClassification.OCI_IMAGE : TargetClassification.ARCHIVE,
              "SCANNABLE"));
      when(snapshots.readySnapshot()).thenReturn(snapshot);
      when(snapshots.snapshotFor(any())).thenReturn(snapshot);
      BlobStorage storage = mock(BlobStorage.class);
      when(storages.forBlobStoreId(2L)).thenReturn(storage);
      when(storage.get(any())).thenReturn(Optional.of(
          new ByteArrayInputStream("artifact".getBytes())));
      try {
        when(documents.open(30L)).thenReturn(new ByteArrayInputStream("{}".getBytes()));
      } catch (IOException e) {
        throw new AssertionError(e);
      }
      when(documents.store(anyLong(), anyString(), any(), anyString()))
          .thenAnswer(invocation -> {
            String kindName = invocation.getArgument(1);
            return new SecurityScanDocumentStore.StoredDocument(
                kindName.equals("sbom") ? 30L : 31L,
                kindName.equals("sbom") ? "d".repeat(64) : "e".repeat(64),
                ((byte[]) invocation.getArgument(2)).length);
          });
      when(scans.insertSbomOrFindExisting(any())).thenAnswer(invocation -> {
        Sbom value = invocation.getArgument(0);
        return new Sbom(
            20L, value.subjectKind(), value.subjectIdentity(), value.subjectIdentityHash(),
            value.catalogEngine(), value.catalogEngineVersion(),
            value.catalogConfigurationDigest(), value.catalogFingerprint(),
            value.documentBlobId(), value.documentSha256(), value.specName(),
            value.specVersion(), value.componentCount(), value.dependencyCount(),
            value.inventoryComplete(), value.createdAt());
      });
      when(finalizer.finalizeRun(
              any(), any(), any(), anyString(), any(), anyList()))
          .thenAnswer(invocation -> invocation.getArgument(4));

      executor = new SecurityScanExecutor(
          scans, assets, repositories, docker, storages, classifier, adapter, snapshots,
          documents, finalizer, dockerAuth, properties, new ObjectMapper(), metrics, scope);
    }
  }
}
