package com.github.klboke.kkrepo.server.goartifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GoProxyServiceTest {
  private static final String PATH = "example.com/acme/demo/@v/v1.2.3.mod";

  @Test
  void servesFreshCachedBodyAndMissingPhysicalBlob() throws Exception {
    Fixture fixture = fixture();
    CachedAssetMetadata cached = snapshot(PATH, Instant.now(), Map.of("remoteEtag", "etag"));
    when(fixture.cache.find(eq(10L), eq(PATH), any())).thenReturn(Optional.of(cached));
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    when(fixture.storage.get(any())).thenReturn(Optional.of(
        new ByteArrayInputStream("module demo".getBytes(StandardCharsets.UTF_8))));

    MavenResponse response = fixture.service.get(runtime(60, 7L), PATH, false);
    assertEquals("module demo",
        new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
    verify(fixture.fetcher, never()).fetchWithBodyRetry(any(), anyString(), any());

    when(fixture.storage.get(any())).thenReturn(Optional.empty());
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> fixture.service.get(runtime(60, 7L), PATH, false).body());
  }

  @Test
  void honorsNegativeCacheAndBlockedUpstreamFallback() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(1, 7L);
    when(fixture.cache.find(eq(10L), eq(PATH), any())).thenReturn(Optional.empty());
    when(fixture.negativeCache.isNotFoundCached(runtime, PATH)).thenReturn(true);
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> fixture.service.get(runtime, PATH, false));

    CachedAssetMetadata stale = snapshot(PATH, Instant.EPOCH, Map.of());
    when(fixture.cache.find(eq(10L), eq(PATH), any())).thenReturn(Optional.of(stale));
    when(fixture.negativeCache.isNotFoundCached(runtime, PATH)).thenReturn(false);
    when(fixture.proxyStateDao.isBlocked(eq(10L), any())).thenReturn(true);
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    MavenResponse response = fixture.service.get(runtime, PATH, true);
    assertEquals(200, response.status());

    when(fixture.cache.find(eq(10L), eq(PATH), any())).thenReturn(Optional.empty());
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> fixture.service.get(runtime, PATH, false));
  }

  @Test
  void cachesSuccessfulHeadResponseAndPassesRemoteAttributes() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(1, 7L);
    when(fixture.cache.find(eq(10L), eq(PATH), any())).thenReturn(Optional.empty());
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    AtomicReference<HttpRemoteFetcher.Request> request = new AtomicReference<>();
    doAnswer(invocation -> {
      request.set(invocation.getArgument(0));
      @SuppressWarnings("unchecked")
      HttpRemoteFetcher.ResultHandler<MavenResponse> handler = invocation.getArgument(2);
      return handler.handle(new HttpRemoteFetcher.Result(
          200,
          Map.of(
              "Content-Type", "text/plain",
              "ETag", "\"remote\"",
              "Last-Modified", "Wed, 01 Jan 2025 00:00:00 GMT"),
          new ByteArrayInputStream("module demo".getBytes(StandardCharsets.UTF_8))));
    }).when(fixture.fetcher).fetchWithBodyRetry(any(), eq(PATH), any());
    GoAssetWriter.Stored stored = stored(PATH, Map.of(
        "remoteEtag", "\"remote\"",
        "remoteLastModified", "2025-01-01T00:00:00Z"));
    when(fixture.writer.write(
        eq(runtime), eq(fixture.storage), eq(7L), any(GoPath.class), any(), any(), eq(false)))
        .thenReturn(stored);

    MavenResponse response = fixture.service.get(runtime, PATH, true);

    assertEquals(200, response.status());
    assertEquals("https://proxy.golang.org/" + PATH, request.get().url());
    assertEquals("\"remote\"", response.etag());
    verify(fixture.proxyStateDao).recordSuccess(eq(10L), any());
    verify(fixture.negativeCache).invalidate(runtime, PATH);
  }

  @Test
  void handlesNotModifiedAndRemoteMisses() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(1, 7L);
    CachedAssetMetadata stale = snapshot(PATH, Instant.EPOCH, Map.of());
    when(fixture.cache.find(eq(10L), eq(PATH), any())).thenReturn(Optional.of(stale));
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    respond(fixture.fetcher, new HttpRemoteFetcher.Result(
        304, Map.of(), new ByteArrayInputStream(new byte[0])));

    assertEquals(200, fixture.service.get(runtime, PATH, true).status());
    verify(fixture.assetDao).touchAssetLastUpdated(eq(1L), any());
    verify(fixture.cache).touchVerified(eq(10L), eq(PATH), any());

    Fixture missing = fixture();
    when(missing.cache.find(eq(10L), eq(PATH), any())).thenReturn(Optional.empty());
    respond(missing.fetcher, new HttpRemoteFetcher.Result(
        404, Map.of(), new ByteArrayInputStream(new byte[0])));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> missing.service.get(runtime, PATH, false));
    verify(missing.negativeCache).rememberNotFound(runtime, PATH);
  }

  @Test
  void fallsBackToStaleOnFailuresAndValidatesRuntime() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(1, 7L);
    CachedAssetMetadata stale = snapshot(PATH, Instant.EPOCH, Map.of());
    when(fixture.cache.find(eq(10L), eq(PATH), any())).thenReturn(Optional.of(stale));
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    respond(fixture.fetcher, new HttpRemoteFetcher.Result(
        503, Map.of(), new ByteArrayInputStream(new byte[0])));
    assertEquals(200, fixture.service.get(runtime, PATH, true).status());
    verify(fixture.proxyStateDao).recordFailure(eq(10L), eq(30L), anyString(), any());

    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> fixture.service.get(
            new RepositoryRuntime(
                10L, "go", RepositoryFormat.GO, RepositoryType.HOSTED, "go", true, 7L,
                null, null, null, true, null, 1, 1, true, null, List.of()),
            PATH, false));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> fixture.service.get(runtime, "invalid", false));

    Fixture noStore = fixture();
    when(noStore.cache.find(eq(10L), eq(PATH), any())).thenReturn(Optional.empty());
    respond(noStore.fetcher, new HttpRemoteFetcher.Result(
        200, Map.of(), new ByteArrayInputStream("module demo".getBytes(StandardCharsets.UTF_8))));
    assertThrows(IllegalStateException.class,
        () -> noStore.service.get(runtime(1, null), PATH, false));
  }

  private static void respond(HttpRemoteFetcher fetcher, HttpRemoteFetcher.Result result)
      throws IOException {
    doAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      HttpRemoteFetcher.ResultHandler<MavenResponse> handler = invocation.getArgument(2);
      return handler.handle(result);
    }).when(fetcher).fetchWithBodyRetry(any(), anyString(), any());
  }

  private static Fixture fixture() {
    AssetDao assetDao = mock(AssetDao.class);
    BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    GoAssetWriter writer = mock(GoAssetWriter.class);
    ProxyStateDao proxyStateDao = mock(ProxyStateDao.class);
    HttpRemoteFetcher fetcher = mock(HttpRemoteFetcher.class);
    ProxyNegativeCache negativeCache = mock(ProxyNegativeCache.class);
    AssetMetadataCache cache = mock(AssetMetadataCache.class);
    BlobStorage storage = mock(BlobStorage.class);
    return new Fixture(
        assetDao, registry, writer, proxyStateDao, fetcher, negativeCache, cache, storage,
        new GoProxyService(
            assetDao, registry, writer, proxyStateDao, fetcher, negativeCache, cache));
  }

  private static RepositoryRuntime runtime(int maxAgeMinutes, Long blobStoreId) {
    return new RepositoryRuntime(
        10L, "go", RepositoryFormat.GO, RepositoryType.PROXY, "go", true, blobStoreId,
        null, null, null, true, "https://proxy.golang.org/",
        maxAgeMinutes, maxAgeMinutes, true, null, List.of());
  }

  private static CachedAssetMetadata snapshot(
      String path, Instant updatedAt, Map<String, Object> blobAttributes) {
    AssetRecord asset = new AssetRecord(
        1L, 10L, 3L, 2L, RepositoryFormat.GO, path, null,
        "v1.2.3.mod", "MODULE", "text/plain", 11L, null, updatedAt, Map.of());
    return CachedAssetMetadata.of(asset, blob(blobAttributes));
  }

  private static GoAssetWriter.Stored stored(String path, Map<String, Object> attributes) {
    AssetRecord asset = new AssetRecord(
        1L, 10L, 3L, 2L, RepositoryFormat.GO, path, null,
        "v1.2.3.mod", "MODULE", "text/plain", 11L, null, Instant.now(), Map.of());
    return new GoAssetWriter.Stored(asset, blob(attributes), null);
  }

  private static AssetBlobRecord blob(Map<String, Object> attributes) {
    return new AssetBlobRecord(
        2L, 7L, "blob://bucket/object", null, "object", null,
        "sha1", "sha256", "md5", 11L, "text/plain", "proxy", "upstream",
        Instant.EPOCH, Instant.EPOCH, attributes);
  }

  private record Fixture(
      AssetDao assetDao,
      BlobStorageRegistry registry,
      GoAssetWriter writer,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache cache,
      BlobStorage storage,
      GoProxyService service) {
  }
}
