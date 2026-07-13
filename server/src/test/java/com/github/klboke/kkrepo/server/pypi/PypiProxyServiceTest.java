package com.github.klboke.kkrepo.server.pypi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
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

class PypiProxyServiceTest {
  @Test
  void servesFreshCacheAndBlockedStaleFallback() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(60, 7L);
    CachedAssetMetadata fresh = snapshot("simple/demo/", Instant.now(), Map.of());
    PypiResponse expected = PypiResponse.noBody(200);
    when(fixture.cache.find(eq(10L), eq("simple/demo/"), any()))
        .thenReturn(Optional.of(fresh));
    when(fixture.reader.serveSnapshot(fresh, true, "simple/demo/")).thenReturn(expected);

    assertSame(expected, fixture.service.getIndex(runtime, "Demo", true));
    verify(fixture.fetcher, never()).fetchWithBodyRetry(any(), anyString(), any());

    CachedAssetMetadata stale = snapshot("simple/demo/", Instant.EPOCH, Map.of());
    when(fixture.cache.find(eq(10L), eq("simple/demo/"), any()))
        .thenReturn(Optional.of(stale));
    when(fixture.proxyStateDao.isBlocked(eq(10L), any())).thenReturn(true);
    when(fixture.reader.serveSnapshot(stale, false, "simple/demo/")).thenReturn(expected);
    assertSame(expected, fixture.service.getIndex(runtime(1, 7L), "demo", false));
  }

  @Test
  void rewritesAndCachesRemoteProjectIndex() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(1, 7L);
    when(fixture.cache.find(eq(10L), eq("simple/demo/"), any()))
        .thenReturn(Optional.empty());
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    respond(fixture.fetcher, new HttpRemoteFetcher.Result(
        200, Map.of("Content-Type", "text/html"),
        new ByteArrayInputStream("""
            <html><body>
            <a href="../../files/demo-1.0.0.whl#sha256=abc"
               data-requires-python="&gt;=3.11">demo-1.0.0.whl</a>
            </body></html>
            """.getBytes(StandardCharsets.UTF_8))));
    AtomicReference<String> written = new AtomicReference<>();
    doAnswer(invocation -> {
      written.set(new String(
          ((java.io.InputStream) invocation.getArgument(4)).readAllBytes(),
          StandardCharsets.UTF_8));
      return stored("simple/demo/", "text/html");
    }).when(fixture.writer).write(
        eq(runtime), eq(fixture.storage), eq(7L), eq("simple/demo/"), any(),
        eq("text/html"), eq("index"), eq(null), any(), any(),
        eq("proxy"), eq(runtime.proxyRemoteUrl()), eq(false));

    PypiResponse response = fixture.service.getIndex(runtime, "Demo", true);

    assertEquals(200, response.status());
    assertTrue(written.get().contains("../../packages/demo/1.0.0/demo-1.0.0.whl#sha256=abc"));
    assertTrue(written.get().contains("data-requires-python=\"&gt;=3.11\""));
    verify(fixture.proxyStateDao).recordSuccess(eq(10L), any());
    verify(fixture.negativeCache).invalidate(runtime, "simple/demo/");
  }

  @Test
  void resolvesPackageUrlFromRemoteIndexAndCachesPackage() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(1, 7L);
    String packagePath = "packages/demo/1.0.0/demo-1.0.0.whl";
    when(fixture.cache.find(eq(10L), eq(packagePath), any())).thenReturn(Optional.empty());
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    AtomicReference<String> packageUrl = new AtomicReference<>();
    doAnswer(invocation -> {
      HttpRemoteFetcher.Request request = invocation.getArgument(0);
      String path = invocation.getArgument(1);
      @SuppressWarnings("unchecked")
      HttpRemoteFetcher.ResultHandler<PypiResponse> handler = invocation.getArgument(2);
      if (path.startsWith("simple/")) {
        return handler.handle(new HttpRemoteFetcher.Result(
            200, Map.of(),
            new ByteArrayInputStream("""
                <a href="../../files/demo-1.0.0.whl#sha256=abc">demo-1.0.0.whl</a>
                """.getBytes(StandardCharsets.UTF_8))));
      }
      packageUrl.set(request.url());
      return handler.handle(new HttpRemoteFetcher.Result(
          200, Map.of("Content-Type", "application/zip", "ETag", "\"wheel\""),
          new ByteArrayInputStream("wheel".getBytes(StandardCharsets.UTF_8))));
    }).when(fixture.fetcher).fetchWithBodyRetry(any(), anyString(), any());
    when(fixture.writer.write(
        eq(runtime), eq(fixture.storage), eq(7L), eq(packagePath), any(),
        eq("application/zip"), eq("package"), any(), any(), any(),
        eq("proxy"), eq(runtime.proxyRemoteUrl()), eq(false)))
        .thenReturn(stored(packagePath, "application/zip"));

    assertEquals(200, fixture.service.getPackage(runtime, packagePath, true).status());
    assertEquals("https://pypi.example.test/files/demo-1.0.0.whl", packageUrl.get());
    verify(fixture.writer).write(
        eq(runtime), eq(fixture.storage), eq(7L), eq(packagePath), any(),
        eq("application/zip"), eq("package"),
        eq(new PypiAssetWriter.PackageCoordinate("demo", "demo", "1.0.0", null)),
        any(), eq(Map.of("remoteEtag", "wheel")),
        eq("proxy"), eq(runtime.proxyRemoteUrl()), eq(false));
  }

  @Test
  void handlesNotModifiedMissAndUpstreamFailure() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(1, 7L);
    CachedAssetMetadata stale = snapshot("simple/", Instant.EPOCH, Map.of());
    PypiResponse expected = PypiResponse.noBody(200);
    when(fixture.cache.find(eq(10L), eq("simple/"), any())).thenReturn(Optional.of(stale));
    when(fixture.reader.serveSnapshot(stale, true, "simple/")).thenReturn(expected);
    respond(fixture.fetcher, new HttpRemoteFetcher.Result(
        304, Map.of(), new ByteArrayInputStream(new byte[0])));

    assertSame(expected, fixture.service.getRootIndex(runtime, true));
    verify(fixture.assetDao).touchAssetLastUpdated(eq(1L), any());
    verify(fixture.cache).touchVerified(eq(10L), eq("simple/"), any(), eq(null));

    Fixture missing = fixture();
    when(missing.cache.find(eq(10L), eq("simple/demo/"), any()))
        .thenReturn(Optional.empty());
    respond(missing.fetcher, new HttpRemoteFetcher.Result(
        404, Map.of(), new ByteArrayInputStream(new byte[0])));
    assertThrows(PypiExceptions.PypiNotFoundException.class,
        () -> missing.service.getIndex(runtime, "demo", false));
    verify(missing.negativeCache).rememberNotFound(runtime, "simple/demo/");

    Fixture failed = fixture();
    when(failed.cache.find(eq(10L), eq("simple/"), any())).thenReturn(Optional.empty());
    respond(failed.fetcher, new HttpRemoteFetcher.Result(
        503, Map.of(), new ByteArrayInputStream(new byte[0])));
    assertThrows(PypiExceptions.BadUpstreamException.class,
        () -> failed.service.getRootIndex(runtime, false));
    verify(failed.proxyStateDao).recordFailure(eq(10L), eq(30L), anyString(), any());
  }

  @Test
  void validatesRuntimeNegativeCacheAndBlobStore() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(1, 7L);
    when(fixture.cache.find(eq(10L), eq("simple/demo/"), any()))
        .thenReturn(Optional.empty());
    when(fixture.negativeCache.isNotFoundCached(runtime, "simple/demo/")).thenReturn(true);
    assertThrows(PypiExceptions.PypiNotFoundException.class,
        () -> fixture.service.getIndex(runtime, "demo", false));
    assertThrows(IllegalStateException.class,
        () -> fixture.service.getIndex(
            new RepositoryRuntime(
                10L, "pypi", RepositoryFormat.PYPI, RepositoryType.HOSTED, "pypi", true, 7L,
                "ALLOW", null, null, true, null, 1, 1, true, null, List.of()),
            "demo", false));

    Fixture noStore = fixture();
    when(noStore.cache.find(eq(10L), eq("simple/"), any())).thenReturn(Optional.empty());
    respond(noStore.fetcher, new HttpRemoteFetcher.Result(
        200, Map.of(),
        new ByteArrayInputStream("<a href=\"demo/\">demo</a>".getBytes(StandardCharsets.UTF_8))));
    assertThrows(IllegalStateException.class,
        () -> noStore.service.getRootIndex(runtime(1, null), false));
  }

  private static void respond(HttpRemoteFetcher fetcher, HttpRemoteFetcher.Result result)
      throws IOException {
    doAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      HttpRemoteFetcher.ResultHandler<PypiResponse> handler = invocation.getArgument(2);
      return handler.handle(result);
    }).when(fetcher).fetchWithBodyRetry(any(), anyString(), any());
  }

  private static Fixture fixture() {
    AssetDao assetDao = mock(AssetDao.class);
    BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    PypiAssetWriter writer = mock(PypiAssetWriter.class);
    PypiAssetReader reader = mock(PypiAssetReader.class);
    ProxyStateDao proxyStateDao = mock(ProxyStateDao.class);
    HttpRemoteFetcher fetcher = mock(HttpRemoteFetcher.class);
    ProxyNegativeCache negativeCache = mock(ProxyNegativeCache.class);
    AssetMetadataCache cache = mock(AssetMetadataCache.class);
    BlobStorage storage = mock(BlobStorage.class);
    return new Fixture(
        assetDao, registry, writer, reader, proxyStateDao, fetcher, negativeCache, cache,
        storage, new PypiProxyService(
            assetDao, registry, writer, reader, proxyStateDao, fetcher, negativeCache, cache, null));
  }

  private static RepositoryRuntime runtime(int maxAgeMinutes, Long blobStoreId) {
    return new RepositoryRuntime(
        10L, "pypi", RepositoryFormat.PYPI, RepositoryType.PROXY, "pypi", true, blobStoreId,
        null, null, null, true, "https://pypi.example.test/",
        maxAgeMinutes, maxAgeMinutes, true, null, List.of());
  }

  private static CachedAssetMetadata snapshot(
      String path, Instant updatedAt, Map<String, Object> attributes) {
    AssetRecord asset = new AssetRecord(
        1L, 10L, null, 2L, RepositoryFormat.PYPI, path, null,
        PypiPaths.fileName(path), path.startsWith("simple/") ? "index" : "package",
        path.startsWith("simple/") ? "text/html" : "application/zip",
        5L, null, updatedAt, attributes);
    return CachedAssetMetadata.of(asset, blob());
  }

  private static PypiAssetWriter.Stored stored(String path, String contentType) {
    AssetRecord asset = new AssetRecord(
        1L, 10L, null, 2L, RepositoryFormat.PYPI, path, null,
        PypiPaths.fileName(path), path.startsWith("simple/") ? "index" : "package",
        contentType, 5L, null, Instant.now(), Map.of());
    return new PypiAssetWriter.Stored(
        asset, blob(), new PypiAssetWriter.Digests("md5", "sha1", "sha256", "sha512", 5L),
        true, null);
  }

  private static AssetBlobRecord blob() {
    return new AssetBlobRecord(
        2L, 7L, "blob://bucket/object", null, "object", null,
        "sha1", "sha256", "md5", 5L, "application/octet-stream",
        "proxy", "upstream", Instant.EPOCH, Instant.EPOCH, Map.of());
  }

  private record Fixture(
      AssetDao assetDao,
      BlobStorageRegistry registry,
      PypiAssetWriter writer,
      PypiAssetReader reader,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache cache,
      BlobStorage storage,
      PypiProxyService service) {
  }
}
