package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.AssetWithBlob;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupProtection;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupScanCursor;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupUsage;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerTagRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CleanupSubjectScannerExtendedTest {
  private static final Instant CUTOFF = Instant.parse("2026-08-01T00:00:00Z");
  private static final Instant OLD = Instant.parse("2025-01-01T00:00:00Z");

  private ComponentDao components;
  private AssetDao assets;
  private DockerRegistryDao docker;
  private CleanupPolicyDao cleanup;
  private CleanupRuntimeProperties properties;
  private CleanupUsageTrackingService usageTracking;
  private CleanupSubjectScanner scanner;

  @BeforeEach
  void setUp() {
    components = mock(ComponentDao.class);
    assets = mock(AssetDao.class);
    docker = mock(DockerRegistryDao.class);
    cleanup = mock(CleanupPolicyDao.class);
    properties = new CleanupRuntimeProperties();
    usageTracking = mock(CleanupUsageTrackingService.class);
    scanner = new CleanupSubjectScanner(
        components,
        assets,
        docker,
        new CleanupPolicyCapabilities(),
        cleanup,
        properties,
        usageTracking);
    when(cleanup.findAssetUsage(any())).thenReturn(Map.of());
    when(cleanup.findActiveProtections(anyLong(), any(), any())).thenReturn(Map.of());
    when(usageTracking.trackingStartedAt(anyLong())).thenReturn(Instant.EPOCH);
  }

  @Test
  void scansStandaloneAssetsAcrossCursorPagesAndUsesBlobSizeFallback() {
    RepositoryRecord repository = repository(RepositoryFormat.RAW, RepositoryType.HOSTED);
    AssetRecord first = asset(11, null, RepositoryFormat.RAW, "one.bin", "FILE", null, OLD);
    AssetRecord second = asset(12, null, RepositoryFormat.RAW, "two.bin", "FILE", 12L, OLD);
    AssetWithBlob firstRow = new AssetWithBlob(first, blob(111, 321));
    AssetWithBlob secondRow = new AssetWithBlob(second, blob(112, 12));
    CleanupScanCursor cursor = new CleanupScanCursor(
        7, 1, "ASSET", null, null, null, 0, 3, 0);
    when(assets.listUnboundAssetWithBlobPage(1, 0, 2))
        .thenReturn(List.of(firstRow, secondRow));

    var firstPage = scanner.scan(
        repository, Map.of("publishedOlderThanDays", 30), 1, CUTOFF, cursor);

    assertEquals(1, firstPage.scannedSubjects());
    assertTrue(firstPage.truncated());
    assertEquals(321, firstPage.candidates().getFirst().subject().estimatedBytes());
    assertEquals(11, firstPage.nextCursor().subjectId());

    CleanupScanCursor next = firstPage.nextCursor();
    when(assets.listUnboundAssetWithBlobPage(1, 11, 2)).thenReturn(List.of(secondRow));
    var finalPage = scanner.scan(
        repository, Map.of("publishedOlderThanDays", 30), 1, CUTOFF, next);

    assertFalse(finalPage.truncated());
    assertEquals("COMPONENT", finalPage.nextCursor().phase());
    assertEquals(1, finalPage.nextCursor().wrappedCount());
  }

  @Test
  void recognizesHostedPackageSubjectsForEverySpecializedFormat() {
    record Fixture(RepositoryFormat format, String path, String kind) {}
    List<Fixture> fixtures = List.of(
        new Fixture(RepositoryFormat.NPM, "demo/-/demo-1.0.0.tgz", "tarball"),
        new Fixture(RepositoryFormat.PYPI, "packages/demo-1.0.0.whl", "package"),
        new Fixture(RepositoryFormat.HELM, "demo-1.0.0.tgz", "PACKAGE"),
        new Fixture(RepositoryFormat.RUBYGEMS, "gems/demo-1.0.0.gem", "package"),
        new Fixture(RepositoryFormat.YUM, "Packages/d/demo-1.0-1.noarch.rpm", "package"),
        new Fixture(RepositoryFormat.RAW, "downloads/demo.bin", "FILE"));
    long id = 30;
    for (Fixture fixture : fixtures) {
      ComponentRecord component = component(id, fixture.format(), "scope", "demo", "1.0.0");
      AssetRecord asset = asset(
          id + 100, id, fixture.format(), fixture.path(), fixture.kind(), 10L, OLD);
      when(components.listCleanupPage(1, null, 11)).thenReturn(List.of(component));
      when(assets.listAssetsByComponents(List.of(id))).thenReturn(List.of(asset));
      when(assets.listUnboundAssetWithBlobPage(1, 0, 10)).thenReturn(List.of());

      var result = scanner.scan(
          repository(fixture.format(), RepositoryType.HOSTED),
          Map.of("publishedOlderThanDays", 30),
          10,
          CUTOFF);

      assertEquals(1, result.candidates().size(), fixture.format().id());
      id++;
    }
  }

  @Test
  void proxyRepositoriesTreatStoredComponentsAndStandaloneAssetsAsOwnedSubjects() {
    RepositoryRecord repository = repository(RepositoryFormat.NPM, RepositoryType.PROXY);
    ComponentRecord component = component(51, RepositoryFormat.NPM, null, "demo", null);
    AssetRecord metadata = asset(
        151, 51L, RepositoryFormat.NPM, "demo", "package-root", 10L, OLD);
    AssetRecord unbound = asset(
        152, null, RepositoryFormat.NPM, "cached-metadata", "metadata", 10L, OLD);
    when(components.listCleanupPage(1, null, 11)).thenReturn(List.of(component));
    when(assets.listAssetsByComponents(List.of(51L))).thenReturn(List.of(metadata));
    when(assets.listUnboundAssetWithBlobPage(1, 0, 10))
        .thenReturn(List.of(new AssetWithBlob(unbound, null)));

    var result = scanner.scan(
        repository, Map.of("publishedOlderThanDays", 30), 10, CUTOFF);

    assertEquals(2, result.scannedSubjects());
    assertEquals(2, result.candidates().size());
  }

  @Test
  void dockerScanBuildsFencedSubjectsWithTagsUsageProtectionAndCursor() {
    RepositoryRecord repository = repository(RepositoryFormat.DOCKER, RepositoryType.HOSTED);
    var first = new DockerRegistryDao.CleanupManifestCandidate(
        "library/demo", "sha256:one", 201, OLD, OLD, -1);
    var second = new DockerRegistryDao.CleanupManifestCandidate(
        "library/demo", "sha256:two", 202, OLD, OLD, 20);
    AssetRecord asset = asset(
        201, null, RepositoryFormat.DOCKER, "v2/library/demo/manifests/sha256:one",
        "manifest", 50L, OLD);
    DockerManifestRecord manifest = manifest(301, 201, "library/demo", "sha256:one");
    DockerTagRecord tag = tag(401, 301, "latest", "sha256:one");
    CleanupUsage usage = new CleanupUsage(201, 1, OLD, OLD.plusSeconds(60), 4, OLD);
    String subjectKey = "docker-manifest:library/demo@sha256:one";
    CleanupProtection protection = protection(501, subjectKey);
    when(docker.listManifestCleanupCandidatesPage(1, 0, 2))
        .thenReturn(List.of(first, second));
    when(assets.findAssetsByIds(List.of(201L))).thenReturn(Map.of(201L, asset));
    when(cleanup.findAssetUsage(List.of(201L))).thenReturn(Map.of(201L, usage));
    when(docker.findManifestsByAssetIds(List.of(201L))).thenReturn(Map.of(201L, manifest));
    when(docker.listTagsForManifests(List.of(301L))).thenReturn(Map.of(301L, List.of(tag)));
    when(cleanup.findActiveProtections(anyLong(), any(), any()))
        .thenReturn(Map.of(subjectKey, protection));

    var result = scanner.scan(
        repository,
        Map.of("pattern", "library/*", "publishedOlderThanDays", 30),
        1,
        CUTOFF);

    assertEquals(1, result.scannedSubjects());
    assertTrue(result.truncated());
    assertEquals("DOCKER", result.nextCursor().phase());
    assertEquals(201, result.nextCursor().subjectId());
    var candidate = result.candidates().getFirst();
    assertEquals(501L, candidate.protectionId());
    assertEquals(4, candidate.subject().usageRevision());
    assertEquals(0, candidate.subject().estimatedBytes());
    assertNotNull(candidate.subject().contentToken());
  }

  @Test
  void dockerScanWithoutRegistryAdapterSafelyReturnsAnEmptyCycle() {
    CleanupSubjectScanner withoutDocker = new CleanupSubjectScanner(
        components, assets, new CleanupPolicyCapabilities());

    var result = withoutDocker.scan(
        repository(RepositoryFormat.DOCKER, RepositoryType.HOSTED),
        Map.of("publishedOlderThanDays", 30), 10, CUTOFF);

    assertEquals(0, result.scannedSubjects());
    assertEquals(1, result.nextCursor().wrappedCount());
  }

  @Test
  void usageAgeRulesFailClosedUntilTrackingIsWarmAndThenUseDurableWatermarks() {
    RepositoryRecord repository = repository(RepositoryFormat.RAW, RepositoryType.HOSTED);
    AssetRecord asset = asset(
        601, null, RepositoryFormat.RAW, "download.zip", "FILE", 10L, null);
    when(components.listCleanupPage(1, null, 11)).thenReturn(List.of());
    when(assets.listUnboundAssetWithBlobPage(1, 0, 11))
        .thenReturn(List.of(new AssetWithBlob(asset, null)));
    when(usageTracking.trackingStartedAt(1)).thenReturn(null);

    var inactive = scanner.scan(
        repository, Map.of("lastDownloadedOlderThanDays", 30), 10, CUTOFF);
    assertEquals("USAGE_TRACKING_NOT_ACTIVE", inactive.safetyStatus());
    assertTrue(inactive.candidates().isEmpty());

    when(usageTracking.trackingStartedAt(1)).thenReturn(CUTOFF.minusSeconds(60));
    var warming = scanner.scan(
        repository, Map.of("lastDownloadedOlderThanDays", 30), 10, CUTOFF);
    assertEquals("USAGE_TRACKING_WARMING_UP", warming.safetyStatus());

    CleanupUsage durable = new CleanupUsage(
        601, 1, OLD, OLD, Long.MAX_VALUE, CUTOFF.minusSeconds(1));
    when(usageTracking.trackingStartedAt(1)).thenReturn(Instant.EPOCH);
    when(cleanup.findAssetUsage(List.of(601L))).thenReturn(Map.of(601L, durable));
    var warm = scanner.scan(
        repository, Map.of("lastDownloadedOlderThanDays", 30), 10, CUTOFF);
    assertEquals(1, warm.candidates().size());
    assertEquals(Long.MAX_VALUE, warm.candidates().getFirst().subject().usageRevision());
  }

  @Test
  void resolvesAndLocksComponentsAssetsAndDockerManifests() {
    ComponentRecord component = component(701, RepositoryFormat.PYPI, null, "demo", "1.0.0");
    AssetRecord packageAsset = asset(
        702, 701L, RepositoryFormat.PYPI, "packages/demo-1.0.0.whl", "package", 10L, OLD);
    when(components.findById(701)).thenReturn(Optional.of(component));
    when(components.findByIdForUpdate(701)).thenReturn(Optional.of(component));
    when(assets.listAssetsByComponent(701)).thenReturn(List.of(packageAsset));
    when(assets.listAssetsByComponentForUpdate(701)).thenReturn(List.of(packageAsset));
    assertTrue(scanner.resolve(
        repository(RepositoryFormat.PYPI, RepositoryType.HOSTED),
        "COMPONENT", 701, packageAsset.path()).isPresent());
    assertTrue(scanner.resolveLocked(
        repository(RepositoryFormat.PYPI, RepositoryType.HOSTED),
        "COMPONENT", 701, packageAsset.path()).isPresent());

    AssetRecord raw = asset(
        703, null, RepositoryFormat.RAW, "standalone.bin", "FILE", null, OLD);
    when(assets.findAssetWithBlobById(703))
        .thenReturn(Optional.of(new AssetWithBlob(raw, blob(803, 44))));
    when(assets.findAssetByIdForUpdate(703)).thenReturn(Optional.of(raw));
    when(assets.findBlobById(raw.assetBlobId())).thenReturn(Optional.of(blob(803, 44)));
    assertEquals(44, scanner.resolve(
        repository(RepositoryFormat.RAW, RepositoryType.HOSTED),
        "ASSET", 703, raw.path()).orElseThrow().estimatedBytes());
    assertEquals(44, scanner.resolveLocked(
        repository(RepositoryFormat.RAW, RepositoryType.HOSTED),
        "ASSET", 703, raw.path()).orElseThrow().estimatedBytes());

    DockerManifestRecord manifest = manifest(804, 704, "demo", "sha256:704");
    AssetRecord dockerAsset = asset(
        704, null, RepositoryFormat.DOCKER, "manifest", "manifest", 10L, OLD);
    DockerTagRecord tag = tag(805, 804, "latest", "sha256:704");
    when(docker.findManifestByDigest(1, "demo", "sha256:704"))
        .thenReturn(Optional.of(manifest));
    when(docker.findManifestByDigestForUpdate(1, "demo", "sha256:704"))
        .thenReturn(Optional.of(manifest));
    when(docker.listTagsForManifest(804)).thenReturn(List.of(tag));
    when(docker.listTagsForManifestForUpdate(804)).thenReturn(List.of(tag));
    when(assets.findAssetById(704)).thenReturn(Optional.of(dockerAsset));
    when(assets.findAssetByIdForUpdate(704)).thenReturn(Optional.of(dockerAsset));
    assertTrue(scanner.resolve(
        repository(RepositoryFormat.DOCKER, RepositoryType.HOSTED),
        "DOCKER_MANIFEST", 704, "demo@sha256:704").isPresent());
    assertTrue(scanner.resolveLocked(
        repository(RepositoryFormat.DOCKER, RepositoryType.HOSTED),
        "DOCKER_MANIFEST", 704, "demo@sha256:704").isPresent());
    assertTrue(scanner.resolve(
        repository(RepositoryFormat.DOCKER, RepositoryType.HOSTED),
        "DOCKER_MANIFEST", 704, "invalid").isEmpty());
    assertTrue(scanner.resolveLocked(
        repository(RepositoryFormat.DOCKER, RepositoryType.HOSTED),
        "DOCKER_MANIFEST", 704, "demo@").isEmpty());
    assertTrue(scanner.resolve(
        repository(RepositoryFormat.RAW, RepositoryType.HOSTED),
        "UNKNOWN", 704, null).isEmpty());
  }

  @Test
  void lockedNugetResolutionLocksItsPackageAndNuspecTogether() {
    String packagePath = "v3-flatcontainer/demo/1.0.0/demo.1.0.0.nupkg";
    String nuspecPath = "v3-flatcontainer/demo/1.0.0/demo.nuspec";
    ComponentRecord component = component(901, RepositoryFormat.NUGET, null, "demo", "1.0.0");
    AssetRecord nupkg = asset(
        902, 901L, RepositoryFormat.NUGET, packagePath, "package", 10L, OLD);
    AssetRecord nuspec = asset(
        903, null, RepositoryFormat.NUGET, nuspecPath, "nuspec", 5L, OLD);
    when(components.findByIdForUpdate(901)).thenReturn(Optional.of(component));
    when(assets.listAssetsByComponentForUpdate(901)).thenReturn(List.of(nupkg));
    when(assets.findAssetByPath(1, packagePath)).thenReturn(Optional.of(nupkg));
    when(assets.findAssetByPath(1, nuspecPath)).thenReturn(Optional.of(nuspec));
    when(assets.findAssetByIdForUpdate(902)).thenReturn(Optional.of(nupkg));
    when(assets.findAssetByIdForUpdate(903)).thenReturn(Optional.of(nuspec));

    var subject = scanner.resolveLocked(
        repository(RepositoryFormat.NUGET, RepositoryType.HOSTED),
        "COMPONENT", 901, packagePath).orElseThrow();

    assertEquals(List.of(902L, 903L), subject.assetIds());
    verify(assets).findAssetByIdForUpdate(903);
  }

  @Test
  void rejectsMismatchedAndInvalidPersistedCursors() {
    RepositoryRecord raw = repository(RepositoryFormat.RAW, RepositoryType.HOSTED);
    assertThrows(IllegalArgumentException.class, () -> scanner.scan(
        raw, Map.of("publishedOlderThanDays", 30), 10, CUTOFF,
        new CleanupScanCursor(7, 99, "COMPONENT", null, null, null, 0, 1, 0)));
    assertThrows(IllegalArgumentException.class, () -> scanner.scan(
        raw, Map.of("publishedOlderThanDays", 30), 10, CUTOFF,
        new CleanupScanCursor(7, 1, "DOCKER", null, null, null, 0, 1, 0)));
    assertThrows(IllegalArgumentException.class, () -> scanner.scan(
        repository(RepositoryFormat.DOCKER, RepositoryType.HOSTED),
        Map.of("publishedOlderThanDays", 30), 10, CUTOFF,
        new CleanupScanCursor(7, 1, "ASSET", null, null, null, 0, 1, 0)));
  }

  private static RepositoryRecord repository(
      RepositoryFormat format, RepositoryType type) {
    return new RepositoryRecord(
        1L, "repo", format, type, format.id() + "-" + type.name().toLowerCase(), true, 1L,
        null, null, null, null, null, true, Map.of());
  }

  private static ComponentRecord component(
      long id, RepositoryFormat format, String namespace, String name, String version) {
    return new ComponentRecord(
        id, 1, format, namespace, name, version, format.id(), new byte[] {(byte) id},
        Map.of(), OLD);
  }

  private static AssetRecord asset(
      long id,
      Long componentId,
      RepositoryFormat format,
      String path,
      String kind,
      Long size,
      Instant lastDownloadedAt) {
    return new AssetRecord(
        id, 1, componentId, id + 1_000, format, path, new byte[] {(byte) id},
        path.substring(path.lastIndexOf('/') + 1), kind, "application/octet-stream", size,
        lastDownloadedAt, OLD, Map.of());
  }

  private static AssetBlobRecord blob(long id, long size) {
    return new AssetBlobRecord(
        id, 1, "blob:" + id, new byte[32], "objects/" + id, new byte[32],
        null, null, null, size, "application/octet-stream", "admin", "127.0.0.1",
        OLD, OLD, Map.of());
  }

  private static DockerManifestRecord manifest(
      long id, long assetId, String imageName, String digest) {
    return new DockerManifestRecord(
        id, 1, imageName, new byte[32], "sha256", digest, new byte[32],
        "application/vnd.oci.image.manifest.v1+json", null, null, null,
        assetId, 10, "admin", "127.0.0.1", null, Map.of(), OLD, OLD);
  }

  private static DockerTagRecord tag(
      long id, long manifestId, String tag, String digest) {
    return new DockerTagRecord(
        id, 1, "demo", new byte[32], tag, new byte[32], manifestId, digest,
        "admin", "127.0.0.1", OLD, OLD);
  }

  private static CleanupProtection protection(long id, String subjectKey) {
    return new CleanupProtection(
        id, "SUBJECT", 1L, "DOCKER_MANIFEST", subjectKey, new byte[32],
        "MANUAL", null, "hold", true, null, null, "admin", OLD, OLD);
  }
}
