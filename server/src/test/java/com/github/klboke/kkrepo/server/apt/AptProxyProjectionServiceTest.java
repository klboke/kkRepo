package com.github.klboke.kkrepo.server.apt;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.apt.AptPath;
import com.github.klboke.kkrepo.protocol.apt.AptPathParser;
import com.github.klboke.kkrepo.protocol.apt.AptRelease;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import com.github.luben.zstd.ZstdOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AptProxyProjectionServiceTest {
  private static final String INDEX = """
      Package: demo
      Version: 1.0-1
      Architecture: amd64
      Maintainer: Demo <demo@example.invalid>
      Description: demo package
      Filename: pool/d/demo/demo_1.0-1_amd64.deb
      Size: 3
      MD5sum: %s
      SHA1: %s
      SHA256: %s

      """;

  @Test
  void projectsEverySupportedPackagesCompressionAndPreservesCachedBindings() throws Exception {
    Fixture fixture = new Fixture();
    byte[] packageBytes = "deb".getBytes(StandardCharsets.UTF_8);
    String index = INDEX.formatted(
        digest("MD5", packageBytes), digest("SHA-1", packageBytes), digest("SHA-256", packageBytes));
    LinkedHashMap<String, byte[]> encoded = new LinkedHashMap<>();
    encoded.put("dists/stable/main/binary-amd64/Packages", index.getBytes(StandardCharsets.UTF_8));
    encoded.put("dists/stable/main/binary-amd64/Packages.gz", compress("gz", index));
    encoded.put("dists/stable/main/binary-amd64/Packages.bz2", compress("bz2", index));
    encoded.put("dists/stable/main/binary-amd64/Packages.xz", compress("xz", index));
    encoded.put("dists/stable/main/binary-amd64/Packages.zst", compress("zst", index));
    LinkedHashMap<String, AptRegistryDao.ProxyIndex> indices = new LinkedHashMap<>();
    encoded.forEach((path, bytes) -> indices.put(
        path, new AptRegistryDao.ProxyIndex(uncheckedDigest("SHA-256", bytes), bytes.length)));
    when(fixture.registry.findProxyDistribution(fixture.runtime.id(), "stable"))
        .thenReturn(Optional.of(fixture.distribution(indices)));
    when(fixture.proxy.getMetadataFromUrlUnindexed(
        eq(fixture.runtime), anyString(), anyString(), eq(false)))
        .thenReturn(MavenResponse.noBody(200));
    when(fixture.assets.requireBlob(eq(fixture.runtime), anyString())).thenAnswer(invocation -> {
      byte[] bytes = encoded.get(invocation.getArgument(1, String.class));
      return blob(bytes);
    });
    when(fixture.assets.serve(eq(fixture.runtime), anyString(), eq(false))).thenAnswer(invocation ->
        response(encoded.get(invocation.getArgument(1, String.class))));
    AptRegistryDao.PackageRecord cached = record(
        fixture.runtime, "stable", "main", "demo", "1.0-1", "amd64",
        "pool/d/demo/demo_1.0-1_amd64.deb", packageBytes, 41L, 51L);
    AtomicReference<Integer> lookup = new AtomicReference<>(0);
    when(fixture.registry.findPackage(
        anyLong(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> lookup.getAndSet(lookup.get() + 1) == 0
            ? Optional.of(cached) : Optional.empty());
    when(fixture.registry.savePackage(any())).thenAnswer(invocation -> invocation.getArgument(0));

    AptPathParser parser = new AptPathParser();
    encoded.keySet().forEach(path -> fixture.service.observePassthrough(
        fixture.runtime, fixture.settings, parser.parse(path)));

    ArgumentCaptor<AptRegistryDao.PackageRecord> saved =
        ArgumentCaptor.forClass(AptRegistryDao.PackageRecord.class);
    verify(fixture.registry, times(5)).savePackage(saved.capture());
    assertEquals(41L, saved.getAllValues().get(0).assetId());
    assertEquals(51L, saved.getAllValues().get(0).componentId());
    assertEquals("demo", saved.getValue().sourcePackage());
    assertEquals(digest("SHA-256", packageBytes), saved.getValue().sha256());
  }

  @Test
  void refreshesReleaseAndBuildsCompleteResignedProjection() throws Exception {
    Fixture fixture = new Fixture();
    byte[] packageBytes = "deb".getBytes(StandardCharsets.UTF_8);
    String packageDigest = digest("SHA-256", packageBytes);
    String indexText = INDEX.formatted(
        digest("MD5", packageBytes), digest("SHA-1", packageBytes), packageDigest);
    byte[] indexBytes = compress("xz", indexText);
    String indexPath = "dists/stable/main/binary-amd64/Packages.xz";
    byte[] releaseBytes = release(indexPath, indexBytes);
    AptRegistryDao.ProxyDistribution distribution = fixture.distribution(Map.of(
        indexPath, new AptRegistryDao.ProxyIndex(digest("SHA-256", indexBytes), indexBytes.length)));

    when(fixture.proxy.getMetadataFromUrlUnindexed(
        eq(fixture.runtime), anyString(), anyString(), eq(false)))
        .thenReturn(MavenResponse.noBody(200));
    when(fixture.proxy.getPinnedAssetFromUrlUnindexed(
        eq(fixture.runtime), anyString(), anyString(), eq(true)))
        .thenReturn(MavenResponse.noBody(200));
    when(fixture.registry.findProxyDistribution(fixture.runtime.id(), "stable"))
        .thenReturn(Optional.of(distribution));
    when(fixture.assets.requireBlob(eq(fixture.runtime), anyString())).thenAnswer(invocation -> {
      return switch (invocation.getArgument(1, String.class)) {
        case "dists/stable/Release" -> blob(releaseBytes);
        case "dists/stable/main/binary-amd64/Packages.xz" -> blob(indexBytes);
        case "pool/d/demo/demo_1.0-1_amd64.deb" -> blob(packageBytes);
        default -> throw new AssertionError("unexpected path");
      };
    });
    when(fixture.assets.serve(eq(fixture.runtime), anyString(), eq(false))).thenAnswer(invocation -> {
      String path = invocation.getArgument(1, String.class);
      return response("dists/stable/Release".equals(path) ? releaseBytes : indexBytes);
    });
    when(fixture.assets.bindProxyPackage(
        eq(fixture.runtime), anyString(), any(), anyString(), any()))
        .thenReturn(asset(fixture.runtime, 10L, 20L));
    when(fixture.registry.findPackage(
        anyLong(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(fixture.registry.savePackage(any())).thenAnswer(invocation -> invocation.getArgument(0));
    AptRegistryDao.PackageRecord stale = record(
        fixture.runtime, "stable", "main", "old", "0.1", "amd64",
        "pool/o/old/old_0.1_amd64.deb", "old".getBytes(StandardCharsets.UTF_8), 99L, 98L);
    AptRegistryDao.PackageRecord hosted = withSource(stale, AptRegistryDao.SOURCE_HOSTED);
    AptRegistryDao.PackageRecord otherComponent = new AptRegistryDao.PackageRecord(
        stale.id(), stale.repositoryId(), stale.distribution(), "contrib", stale.architecture(),
        stale.packageName(), stale.version(), stale.sourcePackage(), stale.filename(), stale.path(),
        stale.controlFields(), stale.md5(), stale.sha1(), stale.sha256(), stale.size(),
        stale.assetId(), stale.componentId(), stale.sourceKind(), stale.revision(), stale.indexedAt(),
        stale.createdAt(), stale.updatedAt());
    when(fixture.registry.listPackages(fixture.runtime.id(), "stable"))
        .thenReturn(List.of(stale, hosted, otherComponent));

    fixture.service.refreshForResign(fixture.runtime, fixture.settings, "stable");

    verify(fixture.registry).observeProxyDistribution(
        eq(fixture.runtime.id()), eq("stable"), eq(digest("SHA-256", releaseBytes)),
        eq(distribution.indices()), eq(false), any());
    verify(fixture.proxy).getPinnedAssetFromUrlUnindexed(
        eq(fixture.runtime), eq("pool/d/demo/demo_1.0-1_amd64.deb"),
        anyString(), eq(true));
    ArgumentCaptor<AptRegistryDao.PackageRecord> saved =
        ArgumentCaptor.forClass(AptRegistryDao.PackageRecord.class);
    verify(fixture.registry).savePackage(saved.capture());
    assertEquals(10L, saved.getValue().assetId());
    assertEquals(packageDigest, saved.getValue().sha256());
    verify(fixture.registry).deletePackage(
        eq(fixture.runtime.id()), eq("stable"), eq("main"), eq("old"), eq("0.1"),
        eq("amd64"), eq("upstream-release-replaced"), any());
    verify(fixture.assets).retirePackageProjection(99L);
  }

  @Test
  void verifiesKnownPackagesAndRetiresOnlyChangedProjection() throws Exception {
    byte[] bytes = "deb".getBytes(StandardCharsets.UTF_8);
    Fixture missing = new Fixture();
    assertNull(missing.service.verifyAndBindKnownPackage(missing.runtime, "pool/d/demo.deb"));

    Fixture changed = new Fixture();
    AptRegistryDao.PackageRecord expected = record(
        changed.runtime, "stable", "main", "demo", "1", "amd64",
        "pool/d/demo/demo_1_amd64.deb", bytes, null, null);
    AptRegistryDao.PackageRecord existing = record(
        changed.runtime, "stable", "main", "demo", "1", "amd64",
        expected.path(), bytes, 7L, 8L);
    when(changed.registry.findPackageByPath(changed.runtime.id(), expected.path()))
        .thenReturn(Optional.of(expected));
    when(changed.assets.requireBlob(changed.runtime, expected.path())).thenReturn(blob(bytes));
    when(changed.assets.bindProxyPackage(
        eq(changed.runtime), eq(expected.path()), any(), anyString(), any()))
        .thenReturn(asset(changed.runtime, 10L, 20L));
    when(changed.registry.findPackage(
        anyLong(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(existing));
    when(changed.registry.savePackage(any())).thenAnswer(invocation -> invocation.getArgument(0));
    AptRegistryDao.PackageRecord result =
        changed.service.verifyAndBindKnownPackage(changed.runtime, expected.path());
    assertEquals(10L, result.assetId());
    verify(changed.assets).retirePackageProjection(7L);

    Fixture unchanged = new Fixture();
    AptRegistryDao.PackageRecord bound = record(
        unchanged.runtime, "stable", "main", "demo", "1", "amd64",
        expected.path(), bytes, 10L, 20L);
    when(unchanged.registry.findPackageByPath(unchanged.runtime.id(), bound.path()))
        .thenReturn(Optional.of(bound));
    when(unchanged.assets.requireBlob(unchanged.runtime, bound.path())).thenReturn(blob(bytes));
    when(unchanged.assets.bindProxyPackage(
        eq(unchanged.runtime), eq(bound.path()), any(), anyString(), any()))
        .thenReturn(asset(unchanged.runtime, 10L, 20L));
    when(unchanged.registry.findPackage(
        anyLong(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(bound));
    assertEquals(bound, unchanged.service.verifyAndBindKnownPackage(
        unchanged.runtime, bound.path()));
    verify(unchanged.registry, never()).savePackage(any());
  }

  @Test
  void keepsPassthroughProjectionBestEffort() {
    Fixture fixture = new Fixture();
    AptPathParser parser = new AptPathParser();
    when(fixture.proxy.getMetadataFromUrlUnindexed(
        eq(fixture.runtime), anyString(), anyString(), anyBoolean()))
        .thenReturn(MavenResponse.noBody(200));
    when(fixture.assets.requireBlob(fixture.runtime, "dists/stable/Release"))
        .thenReturn(blob("invalid".getBytes(StandardCharsets.UTF_8)));
    when(fixture.assets.serve(fixture.runtime, "dists/stable/Release", false))
        .thenReturn(response("SHA256:\n invalid\n".getBytes(StandardCharsets.UTF_8)));

    assertDoesNotThrow(() -> fixture.service.observePassthrough(
        fixture.runtime, fixture.settings, parser.parse("pool/d/demo/demo_1_amd64.deb")));
    assertDoesNotThrow(() -> fixture.service.observePassthrough(
        fixture.runtime, fixture.settings, parser.parse("dists/stable/Release")));
    when(fixture.registry.findProxyDistribution(fixture.runtime.id(), "stable"))
        .thenReturn(Optional.of(fixture.distribution(Map.of())));
    assertDoesNotThrow(() -> fixture.service.observePassthrough(
        fixture.runtime, fixture.settings,
        parser.parse("dists/stable/main/binary-amd64/Packages.gz")));
    assertDoesNotThrow(() -> fixture.service.observePassthrough(
        fixture.runtime, fixture.settings, parser.parse("unrecognized")));
    verify(fixture.registry, never()).savePackage(any());
  }

  @Test
  void rejectsIncompleteReleaseInvalidIndexAndPackageMismatch() throws Exception {
    Fixture incomplete = new Fixture();
    incomplete.installRelease(Map.of(), new byte[0], new byte[0]);
    assertThrows(MavenExceptions.BadUpstreamException.class, () ->
        incomplete.service.refreshForResign(incomplete.runtime, incomplete.settings, "stable"));

    byte[] packageBytes = "deb".getBytes(StandardCharsets.UTF_8);
    String badArchitecture = INDEX.formatted(
        digest("MD5", packageBytes), digest("SHA-1", packageBytes), digest("SHA-256", packageBytes))
        .replace("Architecture: amd64", "Architecture: arm64");
    Fixture invalidIndex = configuredIndex(badArchitecture, packageBytes);
    assertThrows(MavenExceptions.BadUpstreamException.class, () ->
        invalidIndex.service.refreshForResign(
            invalidIndex.runtime, invalidIndex.settings, "stable"));

    String goodIndex = INDEX.formatted(
        digest("MD5", packageBytes), digest("SHA-1", packageBytes), digest("SHA-256", packageBytes));
    Fixture mismatch = configuredIndex(goodIndex, "bad".getBytes(StandardCharsets.UTF_8));
    assertThrows(MavenExceptions.BadUpstreamException.class, () ->
        mismatch.service.refreshForResign(mismatch.runtime, mismatch.settings, "stable"));
  }

  @Test
  void rejectsUnsafePackagePathsNegativeSizesAndInvalidDigests() throws Exception {
    byte[] packageBytes = "deb".getBytes(StandardCharsets.UTF_8);
    String validIndex = INDEX.formatted(
        digest("MD5", packageBytes), digest("SHA-1", packageBytes),
        digest("SHA-256", packageBytes));
    List<String> invalidIndices = List.of(
        validIndex.replace(
            "Filename: pool/d/demo/demo_1.0-1_amd64.deb", "Filename: ../demo.deb"),
        validIndex.replace("Size: 3", "Size: -1"),
        validIndex.replace("SHA256: " + digest("SHA-256", packageBytes), "SHA256: invalid"));

    for (String invalidIndex : invalidIndices) {
      Fixture fixture = configuredIndex(invalidIndex, packageBytes);
      assertThrows(MavenExceptions.BadUpstreamException.class, () ->
          fixture.service.refreshForResign(fixture.runtime, fixture.settings, "stable"));
    }
  }

  @Test
  void rejectsUnsafeInvalidOversizedAndUnreadableReleases() throws Exception {
    String checksum = "a".repeat(64);
    Fixture unsafe = new Fixture();
    byte[] unsafeRelease = ("SHA256:\n " + checksum + " 1 metadata/not-packages\n")
        .getBytes(StandardCharsets.UTF_8);
    unsafe.installRawRelease(unsafeRelease);
    assertThrows(MavenExceptions.BadUpstreamException.class, () ->
        unsafe.service.refreshForResign(unsafe.runtime, unsafe.settings, "stable"));

    Fixture invalid = new Fixture();
    invalid.installRawRelease("SHA256:\n broken\n".getBytes(StandardCharsets.UTF_8));
    assertThrows(MavenExceptions.BadUpstreamException.class, () ->
        invalid.service.refreshForResign(invalid.runtime, invalid.settings, "stable"));

    Fixture oversized = new Fixture();
    oversized.installRawRelease(new byte[8 * 1024 * 1024 + 1]);
    assertThrows(MavenExceptions.BadUpstreamException.class, () ->
        oversized.service.refreshForResign(oversized.runtime, oversized.settings, "stable"));

    Fixture unreadable = new Fixture();
    when(unreadable.proxy.getMetadataFromUrlUnindexed(
        eq(unreadable.runtime), anyString(), anyString(), eq(false)))
        .thenReturn(MavenResponse.noBody(200));
    when(unreadable.assets.requireBlob(unreadable.runtime, "dists/stable/Release"))
        .thenReturn(blob(new byte[0]));
    when(unreadable.assets.serve(unreadable.runtime, "dists/stable/Release", false))
        .thenReturn(MavenResponse.ok(new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("broken stream");
          }
        }, 0, "text/plain", null, Instant.EPOCH));
    assertThrows(MavenExceptions.BadUpstreamException.class, () ->
        unreadable.service.refreshForResign(
            unreadable.runtime, unreadable.settings, "stable"));
  }

  private static Fixture configuredIndex(String indexText, byte[] packageBlob) throws Exception {
    Fixture fixture = new Fixture();
    byte[] encoded = compress("gz", indexText);
    String indexPath = "dists/stable/main/binary-amd64/Packages.gz";
    Map<String, AptRegistryDao.ProxyIndex> indices = Map.of(
        indexPath, new AptRegistryDao.ProxyIndex(digest("SHA-256", encoded), encoded.length));
    fixture.installRelease(indices, release(indexPath, encoded), encoded);
    when(fixture.assets.requireBlob(fixture.runtime, "pool/d/demo/demo_1.0-1_amd64.deb"))
        .thenReturn(blob(packageBlob));
    when(fixture.proxy.getPinnedAssetFromUrlUnindexed(
        eq(fixture.runtime), anyString(), anyString(), eq(true)))
        .thenReturn(MavenResponse.noBody(200));
    when(fixture.assets.bindProxyPackage(
        eq(fixture.runtime), anyString(), any(), anyString(), any()))
        .thenReturn(asset(fixture.runtime, 10L, 20L));
    return fixture;
  }

  private static final class Fixture {
    final AptRegistryDao registry = mock(AptRegistryDao.class);
    final RawProxyService proxy = mock(RawProxyService.class);
    final AptAssetSupport assets = mock(AptAssetSupport.class);
    final RepositoryRuntime runtime = runtime();
    final AptRepositorySettings.Settings settings = new AptRepositorySettings.Settings(
        "stable", "main", List.of("amd64"), false, true, true, null,
        "kkRepo", "kkRepo");
    final AptProxyProjectionService service = new AptProxyProjectionService(
        registry, proxy, assets, new AptComponentFactory());

    Fixture() {
      when(registry.findPackageByPath(anyLong(), anyString())).thenReturn(Optional.empty());
      when(registry.findPackage(
          anyLong(), anyString(), anyString(), anyString(), anyString(), anyString()))
          .thenReturn(Optional.empty());
      when(registry.listPackages(anyLong(), anyString())).thenReturn(List.of());
      when(registry.savePackage(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    AptRegistryDao.ProxyDistribution distribution(
        Map<String, AptRegistryDao.ProxyIndex> indices) {
      return new AptRegistryDao.ProxyDistribution(
          runtime.id(), "stable", "release", indices, false, Instant.EPOCH, Instant.EPOCH);
    }

    void installRelease(
        Map<String, AptRegistryDao.ProxyIndex> indices,
        byte[] releaseBytes,
        byte[] indexBytes) {
      AptRegistryDao.ProxyDistribution distribution = distribution(indices);
      when(registry.findProxyDistribution(runtime.id(), "stable"))
          .thenReturn(Optional.of(distribution));
      when(proxy.getMetadataFromUrlUnindexed(
          eq(runtime), anyString(), anyString(), eq(false)))
          .thenReturn(MavenResponse.noBody(200));
      when(assets.requireBlob(eq(runtime), anyString())).thenAnswer(invocation -> {
        String path = invocation.getArgument(1, String.class);
        return blob("dists/stable/Release".equals(path) ? releaseBytes : indexBytes);
      });
      when(assets.serve(eq(runtime), anyString(), eq(false))).thenAnswer(invocation -> {
        String path = invocation.getArgument(1, String.class);
        return response("dists/stable/Release".equals(path) ? releaseBytes : indexBytes);
      });
    }

    void installRawRelease(byte[] releaseBytes) {
      when(proxy.getMetadataFromUrlUnindexed(
          eq(runtime), anyString(), anyString(), eq(false)))
          .thenReturn(MavenResponse.noBody(200));
      when(assets.requireBlob(runtime, "dists/stable/Release")).thenReturn(blob(releaseBytes));
      when(assets.serve(runtime, "dists/stable/Release", false))
          .thenReturn(response(releaseBytes));
    }
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1, "apt-proxy", RepositoryFormat.APT, RepositoryType.PROXY, "apt-proxy", true, 1L,
        "ALLOW", null, null, true, "https://apt.example/repository", 60, 60, true,
        null, List.of());
  }

  private static byte[] release(String indexPath, byte[] indexBytes) throws Exception {
    String relative = indexPath.substring("dists/stable/".length());
    return AptRelease.builder("stable", Instant.EPOCH)
        .architectures(List.of("amd64"))
        .components(List.of("main"))
        .checksum("SHA256", digest("SHA-256", indexBytes), indexBytes.length, relative)
        .build().render().getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] compress(String compression, String value) throws IOException {
    byte[] input = value.getBytes(StandardCharsets.UTF_8);
    if ("none".equals(compression)) return input;
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (OutputStream output = switch (compression) {
      case "gz" -> new GZIPOutputStream(bytes);
      case "bz2" -> new BZip2CompressorOutputStream(bytes);
      case "xz" -> new XZCompressorOutputStream(bytes);
      case "zst" -> new ZstdOutputStream(bytes);
      default -> throw new IllegalArgumentException(compression);
    }) {
      output.write(input);
    }
    return bytes.toByteArray();
  }

  private static MavenResponse response(byte[] bytes) {
    return MavenResponse.ok(
        new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream", null,
        Instant.EPOCH);
  }

  private static AssetBlobRecord blob(byte[] bytes) {
    return new AssetBlobRecord(
        1L, 1L, "blob", new byte[32], "key", new byte[32],
        uncheckedDigest("SHA-1", bytes), uncheckedDigest("SHA-256", bytes),
        uncheckedDigest("MD5", bytes), bytes.length, "application/octet-stream", "test", "ip",
        Instant.EPOCH, Instant.EPOCH, Map.of());
  }

  private static AssetRecord asset(RepositoryRuntime runtime, long id, long componentId) {
    return new AssetRecord(
        id, runtime.id(), componentId, 1L, RepositoryFormat.APT, "pool/d/demo.deb",
        new byte[32], "demo.deb", "package", "application/vnd.debian.binary-package",
        3L, null, Instant.EPOCH, Map.of());
  }

  private static AptRegistryDao.PackageRecord record(
      RepositoryRuntime runtime,
      String distribution,
      String component,
      String name,
      String version,
      String architecture,
      String path,
      byte[] bytes,
      Long assetId,
      Long componentId) {
    Map<String, Object> fields = Map.of(
        "Package", name,
        "Version", version,
        "Architecture", architecture,
        "Maintainer", "Demo <demo@example.invalid>",
        "Description", "demo");
    return new AptRegistryDao.PackageRecord(
        1L, runtime.id(), distribution, component, architecture, name, version, name,
        path.substring(path.lastIndexOf('/') + 1), path, fields,
        uncheckedDigest("MD5", bytes), uncheckedDigest("SHA-1", bytes),
        uncheckedDigest("SHA-256", bytes), bytes.length, assetId, componentId,
        AptRegistryDao.SOURCE_PROXY, 1, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
  }

  private static AptRegistryDao.PackageRecord withSource(
      AptRegistryDao.PackageRecord row, String source) {
    return new AptRegistryDao.PackageRecord(
        row.id(), row.repositoryId(), row.distribution(), row.component(), row.architecture(),
        row.packageName(), row.version(), row.sourcePackage(), row.filename(), row.path(),
        row.controlFields(), row.md5(), row.sha1(), row.sha256(), row.size(), row.assetId(),
        row.componentId(), source, row.revision(), row.indexedAt(), row.createdAt(), row.updatedAt());
  }

  private static String digest(String algorithm, byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
  }

  private static String uncheckedDigest(String algorithm, byte[] bytes) {
    try {
      return digest(algorithm, bytes);
    } catch (Exception impossible) {
      throw new AssertionError(impossible);
    }
  }
}
