package com.github.klboke.kkrepo.server.goartifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.securityscan.ArtifactDownloadPolicy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GoHostedServiceTest {
  private static final String MODULE = "example.com/acme/demo";
  private static final String VERSION = "v1.2.3";
  private static final String ZIP_PATH = MODULE + "/@v/" + VERSION + ".zip";

  @Test
  void publishesValidatedReleaseUsingRepositoryWritePolicy() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime("ALLOW_ONCE");
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    when(fixture.inspector.inspect(any(Path.class), eq(VERSION)))
        .thenReturn(new GoModuleArchiveInspector.Inspected(
            MODULE, MODULE, VERSION, ("module " + MODULE + "\n").getBytes(StandardCharsets.UTF_8)));
    when(fixture.writer.writeHostedRelease(
        eq(runtime), eq(fixture.storage), eq(7L), any(), any(), any(), any(), any(), any(Path.class),
        eq("alice"), eq("127.0.0.1"), eq(false)))
        .thenReturn(releaseStored());

    GoHostedService.Published published = fixture.service.publish(
        runtime,
        VERSION + ".zip",
        new ByteArrayInputStream("zip".getBytes(StandardCharsets.UTF_8)),
        "alice",
        "127.0.0.1");

    assertEquals(MODULE, published.module());
    assertEquals(VERSION, published.version());
    assertEquals(ZIP_PATH, published.archivePath());

    ArgumentCaptor<GoPath> module = ArgumentCaptor.forClass(GoPath.class);
    ArgumentCaptor<GoPath> info = ArgumentCaptor.forClass(GoPath.class);
    ArgumentCaptor<GoPath> archive = ArgumentCaptor.forClass(GoPath.class);
    verify(fixture.writer).writeHostedRelease(
        eq(runtime), eq(fixture.storage), eq(7L), module.capture(), any(), info.capture(), any(),
        archive.capture(), any(Path.class), eq("alice"), eq("127.0.0.1"), eq(false));
    assertEquals(MODULE + "/@v/" + VERSION + ".mod", module.getValue().path());
    assertEquals(MODULE + "/@v/" + VERSION + ".info", info.getValue().path());
    assertEquals(ZIP_PATH, archive.getValue().path());
  }

  @Test
  void rejectsDeniedOrInvalidUploadsBeforeBlobPublication() {
    Fixture fixture = fixture();

    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> fixture.service.publish(
        runtime("DENY"), VERSION + ".zip", new ByteArrayInputStream(new byte[] {1}), "alice", "ip"));
    assertThrows(MavenExceptions.LayoutPolicyViolation.class, () -> fixture.service.publish(
        runtime("ALLOW"), MODULE + "/" + VERSION + ".zip",
        new ByteArrayInputStream(new byte[] {1}), "alice", "ip"));

    verifyNoInteractions(fixture.writer);
  }

  @Test
  void listsMigratedAndNativeComponentsInGoSemverOrderWithoutPseudoVersions() throws Exception {
    Fixture fixture = fixture();
    when(fixture.componentDao.listByName(10L, MODULE)).thenReturn(List.of(
        component(1L, "v1.10.0", "go-module", Instant.parse("2026-01-03T00:00:00Z")),
        component(2L, "v1.2.0", "package", Instant.parse("2026-01-02T00:00:00Z")),
        component(3L, "v1.3.0-0.20260101000000-abcdef123456", "package",
            Instant.parse("2026-01-01T00:00:00Z"))));

    MavenResponse response = fixture.service.get(runtime("ALLOW_ONCE"), MODULE + "/@v/list", false);

    assertEquals("v1.2.0\nv1.10.0",
        new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
    assertEquals("text/plain", response.contentType());
    assertNull(response.lastModified());
  }

  @Test
  void latestPrefersReleaseAndReadsItsInfoAsset() throws Exception {
    Fixture fixture = fixture();
    Instant releaseTime = Instant.parse("2026-01-01T00:00:00Z");
    when(fixture.componentDao.listByName(10L, MODULE)).thenReturn(List.of(
        component(1L, "v2.0.0-rc.1", "go-module", releaseTime.plusSeconds(60)),
        component(2L, VERSION, "go-module", releaseTime)));
    String infoPath = MODULE + "/@v/" + VERSION + ".info";
    when(fixture.cache.find(eq(10L), eq(infoPath), any()))
        .thenReturn(Optional.of(snapshot(infoPath, "application/json")));
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    when(fixture.storage.get(any())).thenReturn(Optional.of(new ByteArrayInputStream(
        "{\"Version\":\"v1.2.3\"}".getBytes(StandardCharsets.UTF_8))));

    MavenResponse response = fixture.service.get(runtime("ALLOW_ONCE"), MODULE + "/@latest", false);

    assertEquals("{\"Version\":\"v1.2.3\"}",
        new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
    assertEquals("text/plain", response.contentType());
  }

  @Test
  void enforcesArtifactScanningAndTracksCleanupUsageBeforeOpeningBlob() throws Exception {
    Fixture fixture = fixture();
    when(fixture.cache.find(eq(10L), eq(ZIP_PATH), any()))
        .thenReturn(Optional.of(snapshot(ZIP_PATH, "application/zip")));
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    when(fixture.storage.get(any())).thenReturn(Optional.of(
        new ByteArrayInputStream("archive".getBytes(StandardCharsets.UTF_8))));

    MavenResponse response = fixture.service.get(runtime("ALLOW_ONCE"), ZIP_PATH, false);

    verify(fixture.downloadPolicy).beforeReadFromRepository(1L, 2L, 10L);
    assertEquals("archive", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
  }

  @Test
  void mapsInvalidArchivesAndInputFailuresToProtocolErrors() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime("ALLOW");

    assertThrows(MavenExceptions.LayoutPolicyViolation.class, () -> fixture.service.publish(
        runtime, "not-semver.zip", new ByteArrayInputStream(new byte[] {1}), "alice", "ip"));
    assertThrows(MavenExceptions.LayoutPolicyViolation.class, () -> fixture.service.publish(
        runtime, VERSION + ".zip", null, "alice", "ip"));
    assertThrows(MavenExceptions.LayoutPolicyViolation.class, () -> fixture.service.publish(
        runtime, VERSION + ".zip", new ByteArrayInputStream(new byte[0]), "alice", "ip"));
    assertThrows(MavenExceptions.BadRequestException.class, () -> fixture.service.publish(
        runtime, VERSION + ".zip", brokenInput(), "alice", "ip"));

    when(fixture.inspector.inspect(any(Path.class), eq(VERSION)))
        .thenThrow(new IllegalArgumentException("archive coordinate mismatch"));
    assertThrows(MavenExceptions.LayoutPolicyViolation.class, () -> fixture.service.publish(
        runtime, VERSION + ".zip", new ByteArrayInputStream(new byte[] {1}), "alice", "ip"));
    verifyNoInteractions(fixture.writer);
  }

  @Test
  void rejectsEmptyListsInvalidPathsAndNonHostedRepositories() {
    Fixture fixture = fixture();
    when(fixture.componentDao.listByName(10L, MODULE)).thenReturn(List.of());

    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> fixture.service.get(runtime("ALLOW_ONCE"), MODULE + "/@v/list", false));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> fixture.service.get(runtime("ALLOW_ONCE"), "not-a-module/@v/list", false));
    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> fixture.service.get(runtime("ALLOW_ONCE", RepositoryType.PROXY, 7L), ZIP_PATH, false));
  }

  @Test
  void loadsMetadataOnCacheMissAndServesHeadWithoutDownloadPolicy() {
    Fixture fixture = fixture();
    AssetRecord asset = snapshot(ZIP_PATH, "application/zip").toAssetRecord();
    AssetBlobRecord blob = snapshot(ZIP_PATH, "application/zip").toBlobRecord();
    when(fixture.assetDao.findAssetByPath(10L, ZIP_PATH)).thenReturn(Optional.of(asset));
    when(fixture.assetDao.findBlobById(2L)).thenReturn(Optional.of(blob));
    when(fixture.cache.find(eq(10L), eq(ZIP_PATH), any())).thenAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      Supplier<Optional<AssetMetadataCache.Loaded>> loader = invocation.getArgument(2);
      AssetMetadataCache.Loaded loaded = loader.get().orElseThrow();
      return Optional.of(CachedAssetMetadata.of(loaded.asset(), loaded.blob()));
    });
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    GoHostedService service = new GoHostedService(
        fixture.assetDao,
        fixture.componentDao,
        fixture.registry,
        fixture.writer,
        fixture.inspector,
        fixture.cache,
        new ObjectMapper().findAndRegisterModules());

    MavenResponse response = service.get(runtime("ALLOW_ONCE"), ZIP_PATH, true);

    assertNull(response.body());
    assertEquals(7L, response.contentLength());
    assertEquals("sha256", response.etag());
    verifyNoInteractions(fixture.downloadPolicy);
  }

  @Test
  void requiresHostedRepositoriesToHaveBlobStorage() {
    Fixture fixture = fixture();
    when(fixture.inspector.inspect(any(Path.class), eq(VERSION)))
        .thenReturn(new GoModuleArchiveInspector.Inspected(
            MODULE, MODULE, VERSION, ("module " + MODULE + "\n").getBytes(StandardCharsets.UTF_8)));

    assertThrows(IllegalStateException.class, () -> fixture.service.publish(
        runtime("ALLOW", RepositoryType.HOSTED, null),
        VERSION + ".zip",
        new ByteArrayInputStream(new byte[] {1}),
        "alice",
        "ip"));
  }

  private static Fixture fixture() {
    AssetDao assetDao = mock(AssetDao.class);
    ComponentDao componentDao = mock(ComponentDao.class);
    BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    GoAssetWriter writer = mock(GoAssetWriter.class);
    GoModuleArchiveInspector inspector = mock(GoModuleArchiveInspector.class);
    AssetMetadataCache cache = mock(AssetMetadataCache.class);
    BlobStorage storage = mock(BlobStorage.class);
    ArtifactDownloadPolicy downloadPolicy = mock(ArtifactDownloadPolicy.class);
    GoHostedService service = new GoHostedService(
        assetDao,
        componentDao,
        registry,
        writer,
        inspector,
        cache,
        new ObjectMapper().findAndRegisterModules(),
        downloadPolicy);
    return new Fixture(
        assetDao, componentDao, registry, writer, inspector, cache, storage, downloadPolicy, service);
  }

  private static RepositoryRuntime runtime(String writePolicy) {
    return runtime(writePolicy, RepositoryType.HOSTED, 7L);
  }

  private static RepositoryRuntime runtime(
      String writePolicy, RepositoryType type, Long blobStoreId) {
    return new RepositoryRuntime(
        10L, "go-hosted", RepositoryFormat.GO, type, "go-hosted", true, blobStoreId,
        writePolicy, null, null, true, null, 60, 60, true, null, List.of());
  }

  private static InputStream brokenInput() {
    return new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("broken upload");
      }
    };
  }

  private static ComponentRecord component(long id, String version, String kind, Instant updatedAt) {
    return new ComponentRecord(
        id, 10L, RepositoryFormat.GO, null, MODULE, version, kind, null, Map.of(), updatedAt);
  }

  private static CachedAssetMetadata snapshot(String path, String contentType) {
    AssetRecord asset = new AssetRecord(
        1L, 10L, 3L, 2L, RepositoryFormat.GO, path, null,
        path.substring(path.lastIndexOf('/') + 1), "PACKAGE", contentType, 7L,
        null, Instant.EPOCH, Map.of());
    AssetBlobRecord blob = new AssetBlobRecord(
        2L, 7L, "blob://bucket/object", null, "object", null,
        "sha1", "sha256", "md5", 7L, contentType, "alice", "127.0.0.1",
        Instant.EPOCH, Instant.EPOCH, Map.of());
    return CachedAssetMetadata.of(asset, blob);
  }

  private static GoAssetWriter.ReleaseStored releaseStored() {
    AssetRecord asset = new AssetRecord(
        1L, 10L, 3L, 2L, RepositoryFormat.GO, ZIP_PATH, null,
        VERSION + ".zip", "PACKAGE", "application/zip", 3L,
        null, Instant.EPOCH, Map.of());
    AssetBlobRecord blob = new AssetBlobRecord(
        2L, 7L, "blob://bucket/object", null, "object", null,
        "sha1", "sha256", "md5", 3L, "application/zip", "alice", "127.0.0.1",
        Instant.EPOCH, Instant.EPOCH, Map.of());
    GoAssetWriter.Stored stored = new GoAssetWriter.Stored(asset, blob, null);
    return new GoAssetWriter.ReleaseStored(stored, stored, stored);
  }

  private record Fixture(
      AssetDao assetDao,
      ComponentDao componentDao,
      BlobStorageRegistry registry,
      GoAssetWriter writer,
      GoModuleArchiveInspector inspector,
      AssetMetadataCache cache,
      BlobStorage storage,
      ArtifactDownloadPolicy downloadPolicy,
      GoHostedService service) {
  }
}
