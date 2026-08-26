package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.AssetWithBlob;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao.CleanupFamilyCursor;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupScanCursor;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CleanupSubjectScannerTest {
  private ComponentDao componentDao;
  private AssetDao assetDao;
  private CleanupSubjectScanner scanner;

  @BeforeEach
  void setUp() {
    componentDao = mock(ComponentDao.class);
    assetDao = mock(AssetDao.class);
    when(assetDao.listAssetsByComponents(any())).thenAnswer(invocation -> {
      Collection<Long> componentIds = invocation.getArgument(0);
      return componentIds.stream()
          .flatMap(componentId -> assetDao.listAssetsByComponent(componentId).stream())
          .toList();
    });
    when(assetDao.findAssetsByPaths(anyLong(), any())).thenAnswer(invocation -> {
      long repositoryId = invocation.getArgument(0);
      Collection<String> paths = invocation.getArgument(1);
      Map<String, AssetRecord> assets = new LinkedHashMap<>();
      paths.forEach(path -> assetDao.findAssetByPath(repositoryId, path)
          .ifPresent(asset -> assets.put(asset.path(), asset)));
      return Map.copyOf(assets);
    });
    scanner = new CleanupSubjectScanner(
        componentDao, assetDao, new CleanupPolicyCapabilities());
  }

  @Test
  void retainsNewestMavenVersionsAndMatchesArtifactGlob() {
    List<ComponentRecord> components = List.of(
        component(1, "1.0.0"),
        component(2, "2.0.0"),
        component(3, "3.0.0"));
    when(componentDao.listCleanupPage(1, null, 11)).thenReturn(components);
    doReturn(List.of(asset(1, "1.0.0"), asset(2, "2.0.0"), asset(3, "3.0.0")))
        .when(assetDao).listAssetsByComponents(List.of(1L, 2L, 3L));

    var result = scanner.scan(
        repository(),
        Map.of("pattern", "jackson-*", "patternType", "GLOB", "retainCount", 2),
        10,
        Instant.parse("2026-08-01T00:00:00Z"));

    assertEquals(3, result.scannedSubjects());
    assertEquals(1, result.candidates().size());
    var candidate = result.candidates().getFirst();
    assertEquals("1.0.0", candidate.subject().version());
    assertEquals("com/fasterxml/jackson-core/1.0.0", candidate.subject().deletePath());
    assertEquals(3, candidate.reason().get("versionRank"));
    verify(assetDao).listAssetsByComponents(List.of(1L, 2L, 3L));
    verify(assetDao, never()).listAssetsByComponent(anyLong());
  }

  @Test
  void goRetainCountTreatsMigratedAndNativeKindsAsOneModuleFamily() {
    ComponentRecord migrated = goComponent(11L, "v1.2.0", "package");
    ComponentRecord nativeHosted = goComponent(12L, "v1.10.0", "go-module");
    when(componentDao.listCleanupPage(1, null, 11))
        .thenReturn(List.of(nativeHosted, migrated));
    doReturn(List.of(goAsset(11L, "v1.2.0"), goAsset(12L, "v1.10.0")))
        .when(assetDao).listAssetsByComponents(List.of(12L, 11L));

    var result = scanner.scan(
        repository(RepositoryFormat.GO),
        Map.of("retainCount", 1),
        10,
        Instant.parse("2026-08-01T00:00:00Z"));

    assertEquals(1, result.candidates().size());
    assertEquals("v1.2.0", result.candidates().getFirst().subject().version());
    assertEquals(2, result.candidates().getFirst().reason().get("versionRank"));
  }

  @Test
  void retainsEveryCondaBuildOfTheNewestVersion() {
    List<ComponentRecord> components = List.of(
        condaComponent(11L, "1.0", "py310_0"),
        condaComponent(12L, "2.0", "py310_0"),
        condaComponent(13L, "2.0", "py311_0"));
    when(componentDao.listCleanupPage(1, null, 11)).thenReturn(components);
    doReturn(List.of(
        condaAsset(11L, "1.0", "py310_0"),
        condaAsset(12L, "2.0", "py310_0"),
        condaAsset(13L, "2.0", "py311_0")))
        .when(assetDao).listAssetsByComponents(List.of(11L, 12L, 13L));

    var result = scanner.scan(
        repository(RepositoryFormat.CONDA),
        Map.of("retainCount", 1),
        10,
        Instant.parse("2026-08-01T00:00:00Z"));

    assertEquals(3, result.scannedSubjects());
    assertEquals(1, result.candidates().size());
    var candidate = result.candidates().getFirst();
    assertEquals("1.0", candidate.subject().version());
    assertEquals("noarch/demo/1.0/demo-1.0-py310_0.conda", candidate.subject().deletePath());
    assertEquals(2, candidate.reason().get("versionRank"));
  }

  @Test
  void excludesTheFamilyCutByTryRunLimit() {
    when(componentDao.listCleanupPage(1, null, 3)).thenReturn(List.of(
        component(1, "1.0.0"),
        component(2, "2.0.0"),
        component(3, "3.0.0")));
    when(assetDao.listAssetsByComponent(1)).thenReturn(List.of(asset(1, "1.0.0")));
    when(assetDao.listAssetsByComponent(2)).thenReturn(List.of(asset(2, "2.0.0")));

    var result = scanner.scan(
        repository(),
        Map.of("retainCount", 0),
        2,
        Instant.parse("2026-08-01T00:00:00Z"));

    assertEquals(2, result.scannedSubjects());
    assertTrue(result.truncated());
    assertTrue(result.candidates().isEmpty());
    assertTrue(result.incompleteFamily().contains("jackson-core"));
    assertTrue(result.cursorWarning().contains("exceeds scanLimitPerRepository"));
    assertEquals("jackson-core", result.nextCursor().componentName());
  }

  @Test
  void skipsNeverDownloadedSubjectsEvenWhenALegacyFallbackFlagIsPresent() {
    ComponentRecord neverDownloaded = component(1, "1.0.0");
    ComponentRecord downloaded = component(2, "2.0.0");
    when(componentDao.listCleanupPage(1, null, 11))
        .thenReturn(List.of(neverDownloaded, downloaded));
    when(assetDao.listAssetsByComponent(1)).thenReturn(List.of(asset(
        1, "1.0.0", null, Instant.parse("2026-01-01T00:00:00Z"))));
    when(assetDao.listAssetsByComponent(2)).thenReturn(List.of(asset(
        2, "2.0.0", Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"))));

    var result = scanner.scan(
        repository(),
        Map.of("lastDownloadedOlderThanDays", 30, "includeNeverDownloaded", true),
        10,
        Instant.parse("2026-08-01T00:00:00Z"));

    assertEquals(1, result.candidates().size());
    assertEquals("2.0.0", result.candidates().getFirst().subject().version());
  }

  @Test
  void resumesStrictlyAfterThePersistedCompleteFamily() {
    CleanupScanCursor cursor = new CleanupScanCursor(
        7, 1, "COMPONENT", "com.fasterxml", "jackson-core", "maven-component",
        0, 4, 0);
    ComponentRecord databind = new ComponentRecord(
        20L,
        1,
        RepositoryFormat.MAVEN2,
        "com.fasterxml",
        "jackson-databind",
        "1.0.0",
        "maven-component",
        new byte[] {20},
        Map.of(),
        Instant.parse("2026-01-01T00:00:00Z"));
    when(componentDao.listCleanupPage(
        1,
        new CleanupFamilyCursor("com.fasterxml", "jackson-core", "maven-component"),
        11)).thenReturn(List.of(databind));
    when(assetDao.listAssetsByComponent(20)).thenReturn(List.of(new AssetRecord(
        20L,
        1,
        20L,
        20L,
        RepositoryFormat.MAVEN2,
        "com/fasterxml/jackson-databind/1.0.0/jackson-databind-1.0.0.jar",
        new byte[] {20},
        "jackson-databind-1.0.0.jar",
        "artifact",
        "application/java-archive",
        100L,
        null,
        Instant.parse("2026-01-01T00:00:00Z"),
        Map.of())));

    var result = scanner.scan(
        repository(),
        Map.of("publishedOlderThanDays", 30),
        10,
        Instant.parse("2026-08-01T00:00:00Z"),
        cursor);

    assertEquals(1, result.scannedSubjects());
    assertEquals("jackson-databind", result.candidates().getFirst().subject().simpleName());
    assertEquals(1, result.nextCursor().wrappedCount());
    assertEquals("COMPONENT", result.nextCursor().phase());
  }

  @Test
  void doesNotExposeHostedNpmPackumentAsAStandaloneCleanupSubject() {
    AssetRecord packageRoot = new AssetRecord(
        31L,
        1L,
        null,
        41L,
        RepositoryFormat.NPM,
        "demo",
        new byte[] {31},
        "demo",
        "package-root",
        "application/json",
        100L,
        null,
        Instant.parse("2026-01-01T00:00:00Z"),
        Map.of());
    when(componentDao.listCleanupPage(1, null, 11)).thenReturn(List.of());
    when(assetDao.listUnboundAssetWithBlobPage(1, 0, 11))
        .thenReturn(List.of(new AssetWithBlob(packageRoot, null)));

    var result = scanner.scan(
        repository(RepositoryFormat.NPM),
        Map.of("publishedOlderThanDays", 30),
        10,
        Instant.parse("2026-08-01T00:00:00Z"));

    assertEquals(0, result.scannedSubjects());
    assertTrue(result.candidates().isEmpty());
  }

  @Test
  void rejectsGroupRepositoriesBecauseTheyDoNotOwnCleanupSubjects() {
    assertThrows(
        CleanupValidationException.class,
        () -> scanner.scan(
            repository(RepositoryFormat.NPM, RepositoryType.GROUP),
            Map.of("publishedOlderThanDays", 30),
            10,
            Instant.parse("2026-08-01T00:00:00Z")));
  }

  @Test
  void excludesGeneratedYumMetadataButKeepsRpmPackage() {
    ComponentRecord rpmComponent = rawComponent(
        41L, RepositoryFormat.YUM, "Packages/d/demo-1.0-1.noarch.rpm");
    ComponentRecord metadataComponent = rawComponent(
        42L, RepositoryFormat.YUM, "repodata/repomd.xml");
    AssetRecord rpm = rawAsset(
        51L, rpmComponent.id(), RepositoryFormat.YUM, rpmComponent.name());
    AssetRecord metadata = rawAsset(
        52L, metadataComponent.id(), RepositoryFormat.YUM, metadataComponent.name());
    when(componentDao.listCleanupPage(1, null, 11))
        .thenReturn(List.of(rpmComponent, metadataComponent));
    when(assetDao.listAssetsByComponent(rpmComponent.id())).thenReturn(List.of(rpm));
    when(assetDao.listAssetsByComponent(metadataComponent.id())).thenReturn(List.of(metadata));

    var result = scanner.scan(
        repository(RepositoryFormat.YUM),
        Map.of("publishedOlderThanDays", 30),
        10,
        Instant.parse("2026-08-01T00:00:00Z"));

    assertEquals(1, result.scannedSubjects());
    assertEquals(rpm.path(), result.candidates().getFirst().subject().deletePath());
  }

  @Test
  void nugetPackageAndNuspecAreOneCleanupSubject() {
    String packagePath = "v3-flatcontainer/demo/1.0.0/demo.1.0.0.nupkg";
    String nuspecPath = "v3-flatcontainer/demo/1.0.0/demo.nuspec";
    ComponentRecord component = rawComponent(61L, RepositoryFormat.NUGET, packagePath);
    AssetRecord nupkg = rawAsset(71L, component.id(), RepositoryFormat.NUGET, packagePath);
    AssetRecord nuspec = rawAsset(72L, 62L, RepositoryFormat.NUGET, nuspecPath);
    when(componentDao.listCleanupPage(1, null, 11)).thenReturn(List.of(component));
    when(assetDao.listAssetsByComponent(component.id())).thenReturn(List.of(nupkg));
    when(assetDao.findAssetByPath(1L, packagePath)).thenReturn(java.util.Optional.of(nupkg));
    when(assetDao.findAssetByPath(1L, nuspecPath)).thenReturn(java.util.Optional.of(nuspec));

    var result = scanner.scan(
        repository(RepositoryFormat.NUGET),
        Map.of("publishedOlderThanDays", 30),
        10,
        Instant.parse("2026-08-01T00:00:00Z"));

    assertEquals(1, result.scannedSubjects());
    assertEquals(2, result.candidates().getFirst().subject().assetCount());
    assertEquals(List.of(71L, 72L), result.candidates().getFirst().subject().assetIds());
  }

  @Test
  void lockedResolutionRejectsHostedGeneratedStandaloneMetadata() {
    AssetRecord packageRoot = rawAsset(81L, null, RepositoryFormat.NPM, "demo");
    when(assetDao.findAssetByIdForUpdate(packageRoot.id()))
        .thenReturn(java.util.Optional.of(packageRoot));

    var resolved = scanner.resolveLocked(
        repository(RepositoryFormat.NPM), "ASSET", packageRoot.id(), packageRoot.path());

    assertTrue(resolved.isEmpty());
  }

  @Test
  void lockedResolutionRejectsAnAssetReboundToAComponentAfterScanning() {
    AssetRecord rebound = rawAsset(82L, 99L, RepositoryFormat.RAW, "downloads/demo.zip");
    when(assetDao.findAssetByIdForUpdate(rebound.id()))
        .thenReturn(java.util.Optional.of(rebound));

    var resolved = scanner.resolveLocked(
        repository(RepositoryFormat.RAW), "ASSET", rebound.id(), rebound.path());

    assertTrue(resolved.isEmpty());
  }

  private static RepositoryRecord repository() {
    return repository(RepositoryFormat.MAVEN2);
  }

  private static RepositoryRecord repository(RepositoryFormat format) {
    return repository(format, RepositoryType.HOSTED);
  }

  private static RepositoryRecord repository(
      RepositoryFormat format, RepositoryType type) {
    return new RepositoryRecord(
        1L,
        "releases",
        format,
        type,
        format.id() + "-" + type.name().toLowerCase(java.util.Locale.ROOT),
        true,
        1L,
        null,
        null,
        null,
        null,
        null,
        true,
        Map.of());
  }

  private static ComponentRecord rawComponent(
      long id, RepositoryFormat format, String path) {
    return new ComponentRecord(
        id,
        1L,
        format,
        path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : null,
        path,
        null,
        format.id(),
        new byte[] {(byte) id},
        Map.of("path", path),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static AssetRecord rawAsset(
      long id, Long componentId, RepositoryFormat format, String path) {
    return new AssetRecord(
        id,
        1L,
        componentId,
        id + 100,
        format,
        path,
        new byte[] {(byte) id},
        path.substring(path.lastIndexOf('/') + 1),
        format.id(),
        "application/octet-stream",
        100L,
        null,
        Instant.parse("2026-01-01T00:00:00Z"),
        Map.of());
  }

  private static ComponentRecord component(long id, String version) {
    return new ComponentRecord(
        id,
        1,
        RepositoryFormat.MAVEN2,
        "com.fasterxml",
        "jackson-core",
        version,
        "maven-component",
        new byte[] {(byte) id},
        Map.of(),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static ComponentRecord condaComponent(long id, String version, String build) {
    String filename = "demo-" + version + "-" + build + ".conda";
    return new ComponentRecord(
        id,
        1,
        RepositoryFormat.CONDA,
        "noarch",
        "demo",
        version,
        "conda-package",
        new byte[] {(byte) id},
        Map.of(
            "subdir", "noarch",
            "build", build,
            "filename", filename,
            "browsePath", "noarch/demo/" + version + "/" + filename),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static ComponentRecord goComponent(long id, String version, String kind) {
    return new ComponentRecord(
        id,
        1,
        RepositoryFormat.GO,
        null,
        "example.com/acme/demo",
        version,
        kind,
        new byte[] {(byte) id},
        Map.of(),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static AssetRecord goAsset(long componentId, String version) {
    String path = "example.com/acme/demo/@v/" + version + ".zip";
    return new AssetRecord(
        componentId,
        1,
        componentId,
        componentId,
        RepositoryFormat.GO,
        path,
        new byte[] {(byte) componentId},
        version + ".zip",
        "PACKAGE",
        "application/zip",
        100L,
        Instant.parse("2025-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"),
        Map.of());
  }

  private static AssetRecord condaAsset(long componentId, String version, String build) {
    String filename = "demo-" + version + "-" + build + ".conda";
    return new AssetRecord(
        componentId,
        1,
        componentId,
        componentId,
        RepositoryFormat.CONDA,
        "noarch/" + filename,
        new byte[] {(byte) componentId},
        filename,
        "package",
        "application/octet-stream",
        100L,
        Instant.parse("2025-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"),
        Map.of());
  }

  private static AssetRecord asset(long componentId, String version) {
    return asset(
        componentId,
        version,
        Instant.parse("2025-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static AssetRecord asset(
      long componentId,
      String version,
      Instant lastDownloadedAt,
      Instant lastUpdatedAt) {
    return new AssetRecord(
        componentId,
        1,
        componentId,
        componentId,
        RepositoryFormat.MAVEN2,
        "com/fasterxml/jackson-core/" + version + "/jackson-core-" + version + ".jar",
        new byte[] {(byte) componentId},
        "jackson-core-" + version + ".jar",
        "artifact",
        "application/java-archive",
        100L,
        lastDownloadedAt,
        lastUpdatedAt,
        Map.of());
  }
}
