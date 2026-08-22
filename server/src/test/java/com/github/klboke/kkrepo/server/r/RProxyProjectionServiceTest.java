package com.github.klboke.kkrepo.server.r;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.r.RPackageIndex;
import com.github.klboke.kkrepo.protocol.r.RPackageMetadata;
import com.github.klboke.kkrepo.protocol.r.RPathParser;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RProxyProjectionServiceTest {
  private final RRegistryDao registry = mock(RRegistryDao.class);
  private final RawProxyService proxy = mock(RawProxyService.class);
  private final RAssetSupport assets = mock(RAssetSupport.class);
  private final RProxyProjectionService service = new RProxyProjectionService(
      registry,
      proxy,
      assets,
      mock(RSourcePackageInspector.class),
      mock(RComponentFactory.class));

  @Test
  void groupPreparationProjectsOnceAndSkipsVerifiedUnchangedIndex() throws Exception {
    RepositoryRuntime runtime = runtime();
    byte[] compressed = gzip("Package: demo\nVersion: 1.0.0\nMD5sum: " + "a".repeat(32) + "\n\n");
    String releaseIdentity = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(compressed));
    when(proxy.getMetadataFromUrlUnindexed(
        any(), anyString(), anyString(), anyBoolean())).thenReturn(MavenResponse.noBody(200));
    when(assets.serve(runtime, "src/contrib/PACKAGES.gz", false)).thenReturn(
        MavenResponse.ok(
            new ByteArrayInputStream(compressed), compressed.length, "application/x-gzip",
            null, Instant.EPOCH));
    when(registry.findProxyDistribution(runtime.id(), "src/contrib")).thenReturn(Optional.of(
        new RRegistryDao.ProxyDistribution(
            runtime.id(), "src/contrib", releaseIdentity, Map.of(), true,
            Instant.EPOCH, Instant.EPOCH)));
    when(registry.findSuite(runtime.id(), "src/contrib")).thenReturn(Optional.of(
        new RRegistryDao.SuiteState(
            runtime.id(), "src/contrib", 9L, Instant.EPOCH, 9L, 1,
            Instant.EPOCH, null, null, Instant.EPOCH)));

    assertEquals(9L, service.prepareGroupMember(runtime, Instant.EPOCH));

    verify(proxy).getMetadataFromUrlUnindexed(
        runtime, "src/contrib/PACKAGES.gz",
        "https://example.invalid/src/contrib/PACKAGES.gz", false);
    verify(assets).serve(runtime, "src/contrib/PACKAGES.gz", false);
    verify(registry, never()).savePackage(any());
    verify(registry, never()).observeProxyDistribution(
        anyLong(), anyString(), anyString(), any(), anyBoolean(), any());
  }

  @Test
  void projectsUpstreamIndexRetiresStaleRowsAndPersistsVerifiedManifest() throws Exception {
    RepositoryRuntime runtime = runtime();
    String md5 = "a".repeat(32);
    byte[] compressed = gzip("""
        Package: demo
        Version: 1.0.0
        MD5sum: %s
        Imports: methods, utils (>= 4.0.0)

        Package: fresh
        Version: 2.0.0

        Package: ignored
        Version: 3.0.0
        File: other_3.0.0.tar.gz

        """.formatted(md5));
    when(proxy.getMetadataFromUrlUnindexed(any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(MavenResponse.noBody(200));
    when(assets.serve(runtime, "src/contrib/PACKAGES.gz", false)).thenReturn(
        response(compressed), MavenResponse.noBody(200));
    when(registry.findProxyDistribution(runtime.id(), "src/contrib"))
        .thenReturn(Optional.empty());
    when(registry.findPackage(anyLong(), anyString(), anyString(), anyString(), anyString(),
        anyString())).thenReturn(Optional.empty());
    when(registry.savePackage(any())).thenAnswer(invocation -> withId(
        invocation.getArgument(0),
        "demo".equals(((RRegistryDao.PackageRecord) invocation.getArgument(0)).packageName())
            ? 10L : 11L));
    RRegistryDao.PackageRecord stale = row(
        12L, "stale", "1.0.0", "src/contrib/stale_1.0.0.tar.gz",
        "b".repeat(32), "c".repeat(64), 7L, 91L);
    RRegistryDao.PackageRecord hosted = new RRegistryDao.PackageRecord(
        13L, stale.repositoryId(), stale.distribution(), stale.component(), stale.architecture(),
        "hosted", stale.version(), stale.versionOrderKey(), stale.packageArchitecture(),
        "hosted_1.0.0.tar.gz", "src/contrib/hosted_1.0.0.tar.gz", stale.controlFields(),
        stale.identity(), stale.dataSha256(), stale.sha256(), stale.size(), 92L, 93L,
        RRegistryDao.SOURCE_HOSTED, stale.revision(), stale.indexedAt(), stale.createdAt(),
        stale.updatedAt());
    when(registry.listPackagePage(
        runtime.id(), "src/contrib", "", 0L, RRegistryDao.PACKAGE_PAGE_SIZE))
        .thenReturn(List.of(stale, hosted));

    MavenResponse response = realService().get(
        runtime, new RPathParser().parse("src/contrib/PACKAGES.gz"), false);

    assertEquals(200, response.status());
    ArgumentCaptor<RRegistryDao.PackageRecord> saved =
        ArgumentCaptor.forClass(RRegistryDao.PackageRecord.class);
    verify(registry, times(2)).savePackage(saved.capture());
    assertEquals(List.of("demo", "fresh"),
        saved.getAllValues().stream().map(RRegistryDao.PackageRecord::packageName).toList());
    assertEquals(md5, saved.getAllValues().getFirst().identity());
    assertEquals(RProxyProjectionService.UNKNOWN_SHA256,
        saved.getAllValues().getLast().sha256());
    verify(registry).deletePackage(
        org.mockito.ArgumentMatchers.eq(runtime.id()),
        org.mockito.ArgumentMatchers.eq("src/contrib"),
        org.mockito.ArgumentMatchers.eq("source"),
        org.mockito.ArgumentMatchers.eq("stale"),
        org.mockito.ArgumentMatchers.eq("1.0.0"),
        org.mockito.ArgumentMatchers.eq("source"),
        org.mockito.ArgumentMatchers.eq("upstream-index-replaced"),
        org.mockito.ArgumentMatchers.any(Instant.class));
    verify(assets).retirePackageProjection(91L);
    ArgumentCaptor<Map<String, RRegistryDao.ProxyIndex>> manifest =
        org.mockito.ArgumentCaptor.forClass(Map.class);
    verify(registry).observeProxyDistribution(
        org.mockito.ArgumentMatchers.eq(runtime.id()),
        org.mockito.ArgumentMatchers.eq("src/contrib"),
        org.mockito.ArgumentMatchers.argThat(identity -> identity.length() == 64),
        manifest.capture(), org.mockito.ArgumentMatchers.eq(true), any());
    assertEquals(2, manifest.getValue().size());
  }

  @Test
  void preservesUnchangedProjectionAndRetiresReplacedCachedAsset() throws Exception {
    RepositoryRuntime runtime = runtime();
    String firstMd5 = "a".repeat(32);
    String secondMd5 = "b".repeat(32);
    byte[] compressed = gzip("""
        Package: same
        Version: 1.0.0
        MD5sum: %s

        Package: changed
        Version: 2.0.0
        MD5sum: %s

        """.formatted(firstMd5, secondMd5));
    List<RPackageMetadata> metadata = RPackageIndex.parse(gunzip(compressed));
    RRegistryDao.PackageRecord same = projection(runtime, metadata.getFirst(), firstMd5, 10L, null);
    RRegistryDao.PackageRecord changed = projection(
        runtime, metadata.getLast(), "0".repeat(32), 11L, 77L);
    when(proxy.getMetadataFromUrlUnindexed(any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(MavenResponse.noBody(200));
    when(assets.serve(runtime, "src/contrib/PACKAGES.gz", false)).thenReturn(response(compressed));
    when(registry.findPackage(anyLong(), anyString(), anyString(), anyString(), anyString(),
        anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(3)) {
          case "same" -> Optional.of(same);
          case "changed" -> Optional.of(changed);
          default -> Optional.empty();
        });
    when(registry.savePackage(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(registry.listPackagePage(anyLong(), anyString(), anyString(), anyLong(),
        org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(List.of());
    when(registry.ensureSuite(runtime.id(), "src/contrib", Instant.EPOCH)).thenReturn(
        new RRegistryDao.SuiteState(
            runtime.id(), "src/contrib", 9L, Instant.EPOCH, 8L, 1,
            Instant.EPOCH, null, null, Instant.EPOCH));

    assertEquals(9L, realService().prepareGroupMember(runtime, Instant.EPOCH));

    verify(registry, times(1)).savePackage(any());
    verify(assets).retirePackageProjection(77L);
    verify(registry).ensureSuite(runtime.id(), "src/contrib", Instant.EPOCH);
  }

  @Test
  void downloadsVerifiesAndMaterializesDeclaredProxyPackage() throws Exception {
    RepositoryRuntime runtime = runtime();
    byte[] archive = RTestPackage.source("demo", "1.0.0");
    String md5 = digest(archive, "MD5");
    String sha256 = digest(archive, "SHA-256");
    RRegistryDao.PackageRecord expected = row(
        10L, "demo", "1.0.0", "src/contrib/demo_1.0.0.tar.gz",
        md5, RProxyProjectionService.UNKNOWN_SHA256, 0L, null);
    AssetRecord asset = asset(30L, 40L);
    when(proxy.getPinnedAssetFromUrlUnindexed(any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(MavenResponse.noBody(200));
    when(assets.serve(
        org.mockito.ArgumentMatchers.eq(runtime),
        org.mockito.ArgumentMatchers.eq(expected.path()), anyBoolean()))
        .thenAnswer(invocation -> response(archive));
    when(registry.findPackageByPath(runtime.id(), expected.path()))
        .thenReturn(Optional.of(expected));
    when(assets.bindProxyPackage(any(), anyString(), any(), anyString(), any()))
        .thenReturn(asset);
    when(registry.materializeProxyPackage(any(), org.mockito.ArgumentMatchers.eq(md5),
        org.mockito.ArgumentMatchers.eq(1L)))
        .thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));

    MavenResponse result = realService().get(
        runtime, new RPathParser().parse(expected.path()), true);

    assertEquals(200, result.status());
    ArgumentCaptor<RRegistryDao.PackageRecord> materialized =
        ArgumentCaptor.forClass(RRegistryDao.PackageRecord.class);
    verify(registry).materializeProxyPackage(materialized.capture(),
        org.mockito.ArgumentMatchers.eq(md5), org.mockito.ArgumentMatchers.eq(1L));
    assertEquals(sha256, materialized.getValue().sha256());
    assertEquals(archive.length, materialized.getValue().size());
    verify(registry).replacePackageRelations(
        org.mockito.ArgumentMatchers.eq(runtime.id()),
        org.mockito.ArgumentMatchers.eq(expected.id()), any());
  }

  @Test
  void directPackageFetchStaysOutsideGroupProjection() throws Exception {
    RepositoryRuntime runtime = runtime();
    byte[] archive = RTestPackage.source("direct", "1.2.0");
    String path = "src/contrib/direct_1.2.0.tar.gz";
    when(proxy.getPinnedAssetFromUrlUnindexed(any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(MavenResponse.noBody(200));
    when(assets.serve(
        org.mockito.ArgumentMatchers.eq(runtime),
        org.mockito.ArgumentMatchers.eq(path), anyBoolean()))
        .thenAnswer(invocation -> response(archive));
    when(registry.findPackageByPath(runtime.id(), path)).thenReturn(Optional.empty());
    when(assets.bindProxyPackage(any(), anyString(), any(), anyString(), any()))
        .thenReturn(asset(30L, 40L));

    assertEquals(200, realService().get(
        runtime, new RPathParser().parse(path), false).status());

    verify(assets).bindProxyPackage(
        org.mockito.ArgumentMatchers.eq(runtime),
        org.mockito.ArgumentMatchers.eq(path),
        any(),
        org.mockito.ArgumentMatchers.eq(
            "src/contrib/direct/1.2.0/direct_1.2.0.tar.gz"),
        any());
    verify(registry, never()).materializeProxyPackage(any(), anyString(), anyLong());
    verify(registry, never()).replacePackageRelations(anyLong(), anyLong(), any());
  }

  @Test
  void rejectsPackageBytesThatDisagreeWithIndexOrLoseTheProjectionRace() throws Exception {
    RepositoryRuntime runtime = runtime();
    byte[] archive = RTestPackage.source("actual", "1.0.0");
    String path = "src/contrib/actual_1.0.0.tar.gz";
    RRegistryDao.PackageRecord wrong = row(
        10L, "other", "1.0.0", path, "a".repeat(32),
        RProxyProjectionService.UNKNOWN_SHA256, 0L, null);
    when(proxy.getPinnedAssetFromUrlUnindexed(any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(MavenResponse.noBody(200));
    when(assets.serve(
        org.mockito.ArgumentMatchers.eq(runtime),
        org.mockito.ArgumentMatchers.eq(path), anyBoolean()))
        .thenAnswer(invocation -> response(archive));
    when(registry.findPackageByPath(runtime.id(), path)).thenReturn(Optional.of(wrong));

    assertThrows(MavenExceptions.BadUpstreamException.class, () -> realService().get(
        runtime, new RPathParser().parse(path), false));

    String md5 = digest(archive, "MD5");
    RRegistryDao.PackageRecord expected = row(
        11L, "actual", "1.0.0", path, md5,
        RProxyProjectionService.UNKNOWN_SHA256, 0L, null);
    when(registry.findPackageByPath(runtime.id(), path)).thenReturn(Optional.of(expected));
    when(assets.bindProxyPackage(any(), anyString(), any(), anyString(), any()))
        .thenReturn(asset(31L, 41L));
    when(registry.materializeProxyPackage(any(), anyString(), anyLong()))
        .thenReturn(Optional.empty());

    assertThrows(MavenExceptions.BadUpstreamException.class, () -> realService().get(
        runtime, new RPathParser().parse(path), false));
    verify(assets).retirePackageProjection(31L);
  }

  @Test
  void boundGroupFetchMaterializesDeferredMd5AndRejectsSnapshotDrift() throws Exception {
    RepositoryRuntime runtime = runtime();
    byte[] archive = RTestPackage.source("demo", "1.0.0");
    String md5 = digest(archive, "MD5");
    String sha256 = digest(archive, "SHA-256");
    String path = "src/contrib/demo_1.0.0.tar.gz";
    RRegistryDao.PackageRecord expected = row(
        10L, "demo", "1.0.0", path, md5,
        RProxyProjectionService.UNKNOWN_SHA256, 0L, null);
    RRegistryDao.PackageRecord materialized = row(
        10L, "demo", "1.0.0", path, md5, sha256, archive.length, 31L);
    RRegistryDao.GroupBinding deferred = binding(path, md5,
        RProxyProjectionService.UNKNOWN_SHA256, 0L);
    when(registry.findPackageByPath(runtime.id(), path))
        .thenReturn(Optional.of(expected), Optional.of(expected), Optional.of(materialized));
    when(proxy.getPinnedAssetFromUrlUnindexed(any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(MavenResponse.noBody(200));
    when(assets.serve(
        org.mockito.ArgumentMatchers.eq(runtime),
        org.mockito.ArgumentMatchers.eq(path), anyBoolean()))
        .thenAnswer(invocation -> response(archive));
    when(assets.bindProxyPackage(any(), anyString(), any(), anyString(), any()))
        .thenReturn(asset(31L, 41L));
    when(registry.materializeProxyPackage(any(), anyString(), anyLong()))
        .thenReturn(Optional.of(materialized));

    assertEquals(200, realService().getBoundGroupPackage(runtime, deferred, false).status());

    when(registry.findPackageByPath(runtime.id(), path)).thenReturn(Optional.empty());
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> realService().getBoundGroupPackage(runtime, deferred, false));
    when(registry.findPackageByPath(runtime.id(), path)).thenReturn(Optional.of(materialized));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> realService().getBoundGroupPackage(
            runtime, binding(path, "f".repeat(32), sha256, archive.length), false));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> realService().getBoundGroupPackage(
            runtime, binding(path, md5, "f".repeat(64), archive.length), false));
  }

  @Test
  void malformedIndexFailsClosedForGroupsButDirectProxyRemainsReadable() {
    RepositoryRuntime runtime = runtime();
    byte[] invalid = "not-gzip".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    when(proxy.getMetadataFromUrlUnindexed(any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(MavenResponse.noBody(200));
    when(assets.serve(runtime, "src/contrib/PACKAGES.gz", false))
        .thenReturn(response(invalid), response(invalid), MavenResponse.noBody(200));

    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> realService().prepareGroupMember(runtime, Instant.EPOCH));
    assertEquals(200, realService().get(
        runtime, new RPathParser().parse("src/contrib/PACKAGES.gz"), false).status());
  }

  @Test
  void packageAttributesRelationsAndMd5ValidationAreTyped() throws Exception {
    byte[] archive = RTestPackage.source("demo", "1.0.0");
    try (RSourcePackageInspector.InspectedPackage inspected =
        new RSourcePackageInspector().inspect(
            new ByteArrayInputStream(archive), "demo_1.0.0.tar.gz")) {
      Map<String, Object> attributes =
          RProxyProjectionService.packageAttributes(inspected, "proxy");
      assertEquals("demo", attributes.get("rPackage"));
      assertEquals("MIT", attributes.get("rLicense"));
      assertEquals("proxy", attributes.get("rSource"));
      List<RRegistryDao.PackageRelation> relations =
          RProxyProjectionService.relations(9L, inspected.metadata());
      assertEquals(List.of("methods", "utils"),
          relations.stream().map(RRegistryDao.PackageRelation::token).toList());
    }
    assertTrue(RProxyProjectionService.validMd5("A".repeat(32)));
    assertFalse(RProxyProjectionService.validMd5(null));
    assertFalse(RProxyProjectionService.validMd5("xyz"));
  }

  private RProxyProjectionService realService() {
    return new RProxyProjectionService(
        registry, proxy, assets, new RSourcePackageInspector(), new RComponentFactory());
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        7L, "r-proxy", RepositoryFormat.R, RepositoryType.PROXY, "r-proxy", true,
        1L, null, null, null, true, "https://example.invalid/", 60, 60, true,
        null, List.of());
  }

  private static MavenResponse response(byte[] bytes) {
    return MavenResponse.ok(
        new ByteArrayInputStream(bytes), bytes.length, "application/x-gzip", null, Instant.EPOCH);
  }

  private static byte[] gunzip(byte[] compressed) throws Exception {
    try (java.util.zip.GZIPInputStream input =
        new java.util.zip.GZIPInputStream(new ByteArrayInputStream(compressed))) {
      return input.readAllBytes();
    }
  }

  private static String digest(byte[] bytes, String algorithm) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
  }

  private static RRegistryDao.PackageRecord projection(
      RepositoryRuntime runtime,
      RPackageMetadata metadata,
      String identity,
      Long id,
      Long assetId) {
    String filename = RPathParser.sourceFilename(metadata.packageName(), metadata.version());
    Map<String, Object> fields = new java.util.LinkedHashMap<>();
    fields.putAll(metadata.indexFields(identity, filename));
    return new RRegistryDao.PackageRecord(
        id, runtime.id(), "src/contrib", "source", "source", metadata.packageName(),
        metadata.version(), com.github.klboke.kkrepo.protocol.r.RVersions.orderKey(
            metadata.version()), "source", filename, "src/contrib/" + filename,
        Map.copyOf(fields), identity, RProxyProjectionService.UNKNOWN_SHA256,
        RProxyProjectionService.UNKNOWN_SHA256, 0L, assetId, null,
        RRegistryDao.SOURCE_PROXY, 1L, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
  }

  private static RRegistryDao.PackageRecord row(
      long id,
      String name,
      String version,
      String path,
      String identity,
      String sha256,
      long size,
      Long assetId) {
    return new RRegistryDao.PackageRecord(
        id, 7L, "src/contrib", "source", "source", name, version,
        ("r1|" + version).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        "source", path.substring(path.lastIndexOf('/') + 1), path,
        Map.of("Package", name, "Version", version), identity, sha256, sha256,
        size, assetId, assetId == null ? null : assetId + 100,
        RRegistryDao.SOURCE_PROXY, 1L, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
  }

  private static RRegistryDao.PackageRecord withId(
      RRegistryDao.PackageRecord row, long id) {
    return new RRegistryDao.PackageRecord(
        id, row.repositoryId(), row.distribution(), row.component(), row.architecture(),
        row.packageName(), row.version(), row.versionOrderKey(), row.packageArchitecture(),
        row.filename(), row.path(), row.controlFields(), row.identity(), row.dataSha256(),
        row.sha256(), row.size(), row.assetId(), row.componentId(), row.sourceKind(), 1L,
        row.indexedAt(), row.createdAt(), row.updatedAt());
  }

  private static AssetRecord asset(long id, long componentId) {
    return new AssetRecord(
        id, 7L, componentId, 20L, RepositoryFormat.R,
        "src/contrib/demo_1.0.0.tar.gz", new byte[32], "demo_1.0.0.tar.gz", "r",
        "application/x-gzip", 12L, null, Instant.EPOCH, Map.of());
  }

  private static RRegistryDao.GroupBinding binding(
      String path, String identity, String sha256, long size) {
    return new RRegistryDao.GroupBinding(
        1L, 8L, "src/contrib", 4L, path, 7L, 3L, path,
        identity, sha256, size, Instant.EPOCH);
  }

  private static byte[] gzip(String value) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
      gzip.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    return output.toByteArray();
  }
}
