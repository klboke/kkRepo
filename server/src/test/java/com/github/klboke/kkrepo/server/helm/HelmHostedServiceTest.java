package com.github.klboke.kkrepo.server.helm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.helm.HelmAssetKind;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class HelmHostedServiceTest {
  @Test
  void generatesMissingIndexBeforeServingIt() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED, 7L, "ALLOW");
    CachedAssetMetadata snapshot = snapshot("index.yaml");
    MavenResponse expected = MavenResponse.noBody(200);
    when(fixture.assetDao.findAssetByPath(runtime.id(), "index.yaml")).thenReturn(Optional.empty());
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    when(fixture.cache.find(eq(runtime.id()), eq("index.yaml"), any()))
        .thenReturn(Optional.of(snapshot));
    when(fixture.reader.serveSnapshot(snapshot, true, "index.yaml")).thenReturn(expected);

    assertSame(expected, fixture.service.get(runtime, "/index.yaml", true));

    verify(fixture.writer).write(
        eq(runtime), eq(fixture.storage), eq(7L), eq("index.yaml"), any(),
        eq("text/x-yaml"), eq(HelmAssetKind.INDEX), eq(null),
        eq(Map.of()), eq(Map.of()), eq("system"), eq(null));
    verify(fixture.indexDao).enqueue(runtime.id(), RepositoryIndexRebuildDao.HELM_INDEX);
  }

  @Test
  void putsPackagesAndProvenanceButRejectsIndexAndUnknownPaths() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED, 7L, "ALLOW");
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);

    assertEquals(200, fixture.service.put(
        runtime, "demo-1.0.0.tgz", new ByteArrayInputStream(new byte[] {1}),
        "application/gzip", "user", "ip").status());
    assertEquals(200, fixture.service.put(
        runtime, "demo-1.0.0.tgz.prov", new ByteArrayInputStream(new byte[] {2}),
        "application/octet-stream", "user", "ip").status());
    assertEquals(404, fixture.service.put(
        runtime, "index.yaml", new ByteArrayInputStream(new byte[] {3}),
        "text/x-yaml", "user", "ip").status());
    assertEquals(404, fixture.service.put(
        runtime, "README.md", new ByteArrayInputStream(new byte[] {4}),
        "text/plain", "user", "ip").status());

    verify(fixture.indexDao).enqueue(runtime.id(), RepositoryIndexRebuildDao.HELM_INDEX);
  }

  @Test
  void validatesPushCoordinatesAndPreventsDuplicateCharts() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED, 7L, "ALLOW");
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    MockMultipartFile chart = new MockMultipartFile(
        "chart", "demo.tgz", "application/gzip", chartPackage("demo", "1.2.3"));

    assertEquals(201, fixture.service.push(runtime, chart, "user", "ip").status());
    verify(fixture.indexDao).enqueue(runtime.id(), RepositoryIndexRebuildDao.HELM_INDEX);

    when(fixture.assetDao.findAssetByPath(runtime.id(), "demo-1.2.3.tgz"))
        .thenReturn(Optional.of(snapshot("demo-1.2.3.tgz").toAssetRecord()));
    assertThrows(MavenExceptions.WritePolicyDenied.class,
        () -> fixture.service.push(runtime, chart, "user", "ip"));

    MockMultipartFile invalid = new MockMultipartFile(
        "chart", "bad.tgz", "application/gzip", chartPackage("../bad", "1.0.0"));
    assertThrows(MavenExceptions.LayoutPolicyViolation.class,
        () -> fixture.service.push(runtime, invalid, "user", "ip"));
  }

  @Test
  void enforcesTypePolicyAndBlobAssignment() {
    Fixture fixture = fixture();
    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> fixture.service.get(
            runtime(RepositoryType.PROXY, 7L, "ALLOW"), "index.yaml", false));
    assertThrows(MavenExceptions.WritePolicyDenied.class,
        () -> fixture.service.put(
            runtime(RepositoryType.HOSTED, 7L, "DENY"), "demo.tgz",
            new ByteArrayInputStream(new byte[] {1}), null, "user", "ip"));
    RepositoryRuntime once = runtime(RepositoryType.HOSTED, 7L, "ALLOW_ONCE");
    when(fixture.assetDao.findAssetByPath(once.id(), "demo.tgz"))
        .thenReturn(Optional.of(snapshot("demo.tgz").toAssetRecord()));
    assertThrows(MavenExceptions.WritePolicyDenied.class,
        () -> fixture.service.put(
            once, "demo.tgz", new ByteArrayInputStream(new byte[] {1}), null, "user", "ip"));
    assertThrows(IllegalStateException.class,
        () -> fixture.service.put(
            runtime(RepositoryType.HOSTED, null, "ALLOW"), "demo.tgz",
            new ByteArrayInputStream(new byte[] {1}), null, "user", "ip"));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> HelmHostedService.normalizePath("///"));
  }

  @Test
  void deletesOnlyPackagesAndRebuildsIndexAfterSuccess() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED, 7L, "ALLOW");
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    when(fixture.writer.deleteAsset(runtime, fixture.storage, "demo.tgz")).thenReturn(0, 1);

    assertEquals(404, fixture.service.delete(runtime, "index.yaml").status());
    assertEquals(404, fixture.service.delete(runtime, "demo.tgz.prov").status());
    assertEquals(404, fixture.service.delete(runtime, "demo.tgz").status());
    assertEquals(200, fixture.service.delete(runtime, "demo.tgz").status());
    verify(fixture.indexDao).enqueue(runtime.id(), RepositoryIndexRebuildDao.HELM_INDEX);
    verify(fixture.writer, never()).deleteAsset(runtime, fixture.storage, "index.yaml");
  }

  private static Fixture fixture() {
    AssetDao assetDao = mock(AssetDao.class);
    RepositoryIndexRebuildDao indexDao = mock(RepositoryIndexRebuildDao.class);
    BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    HelmAssetWriter writer = mock(HelmAssetWriter.class);
    HelmAssetReader reader = mock(HelmAssetReader.class);
    AssetMetadataCache cache = mock(AssetMetadataCache.class);
    BlobStorage storage = mock(BlobStorage.class);
    return new Fixture(
        assetDao, indexDao, registry, writer, reader, cache, storage,
        new HelmHostedService(assetDao, indexDao, registry, writer, reader, cache));
  }

  private static RepositoryRuntime runtime(
      RepositoryType type, Long blobStoreId, String writePolicy) {
    return new RepositoryRuntime(
        10L, "helm", RepositoryFormat.HELM, type, "helm", true, blobStoreId,
        writePolicy, null, null, true, "https://charts.example.test/",
        60, 60, true, null, List.of());
  }

  private static CachedAssetMetadata snapshot(String path) {
    AssetRecord asset = new AssetRecord(
        1L, 10L, null, null, RepositoryFormat.HELM, path, null,
        path, "INDEX", "text/x-yaml", 1L, null, Instant.EPOCH, Map.of());
    return CachedAssetMetadata.of(asset, null);
  }

  private static byte[] chartPackage(String name, String version) throws Exception {
    ByteArrayOutputStream tarBytes = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(tarBytes)) {
      byte[] body = """
          apiVersion: v2
          name: %s
          version: %s
          """.formatted(name, version).getBytes(StandardCharsets.UTF_8);
      TarArchiveEntry entry = new TarArchiveEntry("chart/Chart.yaml");
      entry.setSize(body.length);
      tar.putArchiveEntry(entry);
      tar.write(body);
      tar.closeArchiveEntry();
    }
    ByteArrayOutputStream gzipBytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(gzipBytes)) {
      gzip.write(tarBytes.toByteArray());
    }
    return gzipBytes.toByteArray();
  }

  private record Fixture(
      AssetDao assetDao,
      RepositoryIndexRebuildDao indexDao,
      BlobStorageRegistry registry,
      HelmAssetWriter writer,
      HelmAssetReader reader,
      AssetMetadataCache cache,
      BlobStorage storage,
      HelmHostedService service) {
  }
}
