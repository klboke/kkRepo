package com.github.klboke.kkrepo.server.raw;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RawHostedServiceTest {
  @Test
  void normalizesAssetAndDirectoryPaths() {
    assertTrue(RawHostedService.isDirectoryRequest(null));
    assertTrue(RawHostedService.isDirectoryRequest(" "));
    assertTrue(RawHostedService.isDirectoryRequest("docs/"));
    assertFalse(RawHostedService.isDirectoryRequest("docs/file.txt"));
    assertEquals("docs/file.txt", RawHostedService.normalizeAssetPath(" //docs///file.txt/ "));
    assertEquals("docs", RawHostedService.normalizeDirectoryPath("///docs///"));
    assertArrayEquals(
        new String[] {"index.html", "index.htm"},
        RawHostedService.indexCandidates(""));
    assertArrayEquals(
        new String[] {"docs/index.html", "docs/index.htm"},
        RawHostedService.indexCandidates("docs"));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> RawHostedService.normalizeAssetPath("///"));
  }

  @Test
  void servesDirectoryIndexFallback() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED, 1L, "ALLOW");
    CachedAssetMetadata snapshot = snapshot("docs/index.htm");
    MavenResponse expected = MavenResponse.noBody(200);
    when(fixture.cache.find(eq(runtime.id()), any(String.class), any()))
        .thenAnswer(invocation -> invocation.getArgument(1).equals("docs/index.htm")
            ? Optional.of(snapshot)
            : Optional.empty());
    when(fixture.reader.serveSnapshot(
        snapshot, true, "docs/index.htm", "ATTACHMENT")).thenReturn(expected);

    assertSame(expected, fixture.service.get(runtime, "/docs/", true));
  }

  @Test
  void enforcesRepositoryTypeWritePolicyAndBlobAssignment() {
    Fixture fixture = fixture();
    ByteArrayInputStream body = new ByteArrayInputStream(new byte[] {1});

    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> fixture.service.get(runtime(RepositoryType.PROXY, 1L, "ALLOW"), "file", false));
    assertThrows(MavenExceptions.WritePolicyDenied.class,
        () -> fixture.service.put(
            runtime(RepositoryType.HOSTED, 1L, "DENY"), "file", body,
            null, "user", "127.0.0.1"));

    RepositoryRuntime allowOnce = runtime(RepositoryType.HOSTED, 1L, "ALLOW_ONCE");
    when(fixture.assetDao.findAssetByPath(allowOnce.id(), "file"))
        .thenReturn(Optional.of(snapshot("file").toAssetRecord()));
    assertThrows(MavenExceptions.WritePolicyDenied.class,
        () -> fixture.service.put(
            allowOnce, "file", new ByteArrayInputStream(new byte[] {1}),
            null, "user", "127.0.0.1"));

    assertThrows(IllegalStateException.class,
        () -> fixture.service.putInternal(
            runtime(RepositoryType.HOSTED, null, "ALLOW"), "file",
            new ByteArrayInputStream(new byte[] {1}), null, null, "user", "127.0.0.1"));
  }

  @Test
  void delegatesInternalWritesAndMapsDeleteResults() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED, 1L, "ALLOW");
    BlobStorage storage = mock(BlobStorage.class);
    when(fixture.registry.forBlobStoreId(1L)).thenReturn(storage);
    when(fixture.writer.deleteAsset(runtime, storage, "file")).thenReturn(0, 1);

    MavenResponse created = fixture.service.putInternal(
        runtime, "/file", new ByteArrayInputStream(new byte[] {1}),
        "text/plain", null, "user", "127.0.0.1");
    assertEquals(201, created.status());
    verify(fixture.writer).write(
        eq(runtime), eq(storage), eq(1L), eq("file"), any(),
        eq("text/plain"), eq(Map.of()), eq("user"), eq("127.0.0.1"));

    assertEquals(404, fixture.service.delete(runtime, "file").status());
    assertEquals(204, fixture.service.delete(runtime, "file").status());
  }

  private static Fixture fixture() {
    AssetDao assetDao = mock(AssetDao.class);
    BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    RawAssetWriter writer = mock(RawAssetWriter.class);
    RawAssetReader reader = mock(RawAssetReader.class);
    AssetMetadataCache cache = mock(AssetMetadataCache.class);
    return new Fixture(
        assetDao,
        registry,
        writer,
        reader,
        cache,
        new RawHostedService(assetDao, registry, writer, reader, cache));
  }

  private static RepositoryRuntime runtime(
      RepositoryType type, Long blobStoreId, String writePolicy) {
    return new RepositoryRuntime(
        1L, "raw", RepositoryFormat.RAW, type, "raw", true, blobStoreId,
        writePolicy, null, null, true, "https://upstream.example.test/",
        60, 60, true, "ATTACHMENT", List.of());
  }

  private static CachedAssetMetadata snapshot(String path) {
    AssetRecord asset = new AssetRecord(
        1L, 1L, null, 2L, RepositoryFormat.RAW, path, null, path, "raw",
        "text/plain", 1L, null, Instant.EPOCH, Map.of());
    return CachedAssetMetadata.of(asset, null);
  }

  private record Fixture(
      AssetDao assetDao,
      BlobStorageRegistry registry,
      RawAssetWriter writer,
      RawAssetReader reader,
      AssetMetadataCache cache,
      RawHostedService service) {
  }
}
