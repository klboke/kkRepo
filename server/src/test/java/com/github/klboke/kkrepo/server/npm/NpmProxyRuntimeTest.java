package com.github.klboke.kkrepo.server.npm;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
import com.github.klboke.kkrepo.protocol.npm.NpmPath;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
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
import org.junit.jupiter.api.Test;

class NpmProxyRuntimeTest {
  private static final NpmPackageId PACKAGE = NpmPackageId.parse("demo");
  private static final String TARBALL = "demo-1.0.0.tgz";
  private static final String TARBALL_PATH = "demo/-/" + TARBALL;

  @Test
  void servesFreshPackageAndTarballFromHostedCache() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(60, 7L);
    CachedAssetMetadata packageRoot = snapshot("demo", Instant.now(), "package-root", Map.of());
    CachedAssetMetadata tarball = snapshot(TARBALL_PATH, Instant.now(), "tarball", Map.of());
    when(fixture.cache.find(eq(10L), anyString(), any()))
        .thenAnswer(invocation -> Optional.of(
            invocation.getArgument(1).equals("demo") ? packageRoot : tarball));
    MavenResponse packageResponse = MavenResponse.noBody(200);
    MavenResponse tarballResponse = MavenResponse.noBody(200);
    when(fixture.hosted.getPackage(
        runtime, PACKAGE, "http://localhost/repository/npm", true, NpmPackumentVariant.FULL))
        .thenReturn(packageResponse);
    when(fixture.hosted.getTarball(runtime, PACKAGE, TARBALL, false))
        .thenReturn(tarballResponse);

    assertSame(packageResponse, fixture.service.getPackage(
        runtime, PACKAGE, "http://localhost/repository/npm", true));
    assertSame(tarballResponse, fixture.service.getTarball(runtime, PACKAGE, TARBALL, false));
    verify(fixture.fetcher, never()).fetchWithBodyRetry(any(), anyString(), any());
  }

  @Test
  void fetchesPackumentRewritesTarballUrlsAndSupportsInstallVariant() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(1, 7L);
    when(fixture.cache.find(eq(10L), eq("demo"), any())).thenReturn(Optional.empty());
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    respond(fixture.fetcher, new HttpRemoteFetcher.Result(
        200, Map.of("Content-Type", "application/json", "ETag", "\"root\""),
        new ByteArrayInputStream("""
            {"name":"demo","dist-tags":{"latest":"1.0.0"},"versions":{
              "1.0.0":{"name":"demo","version":"1.0.0",
                "dist":{"tarball":"https://registry.npmjs.org/demo/-/demo-1.0.0.tgz"}}
            }}
            """.getBytes(StandardCharsets.UTF_8))));
    when(fixture.writer.writePackageRoot(
        eq(runtime), eq(fixture.storage), eq(7L), eq(PACKAGE), any(),
        eq("proxy"), eq(runtime.proxyRemoteUrl()), eq(Map.of("remoteEtag", "root"))))
        .thenReturn(stored("demo", "package-root", "application/json"));

    MavenResponse response = fixture.service.getPackage(
        runtime, PACKAGE, "http://localhost/repository/npm", false,
        NpmPackumentVariant.INSTALL_V1);
    String json = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);

    assertTrue(json.contains("http://localhost/repository/npm/demo/-/demo-1.0.0.tgz"));
    assertEquals("application/json", response.contentType());
    verify(fixture.proxyStateDao).recordSuccess(eq(10L), any());
    verify(fixture.negativeCache).invalidate(runtime, "demo");
  }

  @Test
  void cachesTarballForHeadAndInfersVersion() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(1, 7L);
    when(fixture.cache.find(eq(10L), eq(TARBALL_PATH), any())).thenReturn(Optional.empty());
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    respond(fixture.fetcher, new HttpRemoteFetcher.Result(
        200, Map.of("Content-Type", "application/octet-stream", "ETag", "\"tar\""),
        new ByteArrayInputStream("tarball".getBytes(StandardCharsets.UTF_8))));
    when(fixture.writer.writeTarball(
        eq(runtime), eq(fixture.storage), eq(7L), eq(PACKAGE), eq("1.0.0"), eq(TARBALL),
        any(), eq("application/octet-stream"), eq("proxy"), eq(runtime.proxyRemoteUrl()),
        eq(Map.of("remoteEtag", "tar")), eq(false)))
        .thenReturn(stored(TARBALL_PATH, "tarball", "application/octet-stream"));

    MavenResponse response = fixture.service.getTarball(runtime, PACKAGE, TARBALL, true);

    assertEquals(200, response.status());
    assertEquals(7L, response.contentLength());
    verify(fixture.negativeCache).invalidate(runtime, TARBALL_PATH);
  }

  @Test
  void handlesNotModifiedRemoteMissAndStaleFailureFallback() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(1, 7L);
    CachedAssetMetadata stale = snapshot(
        "demo", Instant.EPOCH, "package-root",
        Map.of("remoteEtag", "etag", "remoteLastModified", "invalid"));
    MavenResponse expected = MavenResponse.noBody(200);
    when(fixture.cache.find(eq(10L), eq("demo"), any())).thenReturn(Optional.of(stale));
    when(fixture.hosted.getPackage(
        runtime, PACKAGE, "base", true, NpmPackumentVariant.FULL)).thenReturn(expected);
    respond(fixture.fetcher, new HttpRemoteFetcher.Result(
        304, Map.of(), new ByteArrayInputStream(new byte[0])));

    assertSame(expected, fixture.service.getPackage(runtime, PACKAGE, "base", true));
    verify(fixture.assetDao).touchAssetLastUpdated(eq(1L), any());
    verify(fixture.cache).touchVerified(eq(10L), eq("demo"), any(), eq(null));

    Fixture missing = fixture();
    when(missing.cache.find(eq(10L), eq("demo"), any())).thenReturn(Optional.empty());
    respond(missing.fetcher, new HttpRemoteFetcher.Result(
        404, Map.of(), new ByteArrayInputStream(new byte[0])));
    assertThrows(NpmExceptions.NpmNotFoundException.class,
        () -> missing.service.getPackage(runtime, PACKAGE, "base", false));
    verify(missing.negativeCache).rememberNotFound(runtime, "demo");

    Fixture failed = fixture();
    when(failed.cache.find(eq(10L), eq("demo"), any())).thenReturn(Optional.of(stale));
    when(failed.hosted.getPackage(
        runtime, PACKAGE, "base", false, NpmPackumentVariant.FULL)).thenReturn(expected);
    respond(failed.fetcher, new HttpRemoteFetcher.Result(
        503, Map.of(), new ByteArrayInputStream(new byte[0])));
    assertSame(expected, failed.service.getPackage(runtime, PACKAGE, "base", false));
    verify(failed.proxyStateDao).recordFailure(eq(10L), eq(30L), anyString(), any());
  }

  @Test
  void validatesNegativeCacheRuntimeKindContentAndBlobStore() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(1, 7L);
    when(fixture.cache.find(eq(10L), eq(TARBALL_PATH), any())).thenReturn(Optional.empty());
    when(fixture.negativeCache.isNotFoundCached(runtime, TARBALL_PATH)).thenReturn(true);
    assertThrows(NpmExceptions.NpmNotFoundException.class,
        () -> fixture.service.getTarball(runtime, PACKAGE, TARBALL, false));
    assertThrows(IllegalStateException.class, () -> fixture.service.get(
        hostedRuntime(),
        new NpmPath(NpmPath.Kind.PACKAGE_ROOT, "demo", PACKAGE, null, null, null, null),
        "base", false));

    Fixture html = fixture();
    when(html.cache.find(eq(10L), eq("demo"), any())).thenReturn(Optional.empty());
    respond(html.fetcher, new HttpRemoteFetcher.Result(
        200, Map.of("Content-Type", "text/html"),
        new ByteArrayInputStream("<html>login</html>".getBytes(StandardCharsets.UTF_8))));
    assertThrows(NpmExceptions.NpmNotFoundException.class,
        () -> html.service.getPackage(runtime, PACKAGE, "base", false));

    Fixture noStore = fixture();
    when(noStore.cache.find(eq(10L), eq("demo"), any())).thenReturn(Optional.empty());
    respond(noStore.fetcher, new HttpRemoteFetcher.Result(
        200, Map.of("Content-Type", "application/json"),
        new ByteArrayInputStream("{\"name\":\"demo\"}".getBytes(StandardCharsets.UTF_8))));
    assertThrows(IllegalStateException.class,
        () -> noStore.service.getPackage(runtime(1, null), PACKAGE, "base", false));
  }

  private static void respond(HttpRemoteFetcher fetcher, HttpRemoteFetcher.Result result)
      throws IOException {
    doAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      HttpRemoteFetcher.ResultHandler<Object> handler = invocation.getArgument(2);
      return handler.handle(result);
    }).when(fetcher).fetchWithBodyRetry(any(), anyString(), any());
  }

  private static Fixture fixture() {
    AssetDao assetDao = mock(AssetDao.class);
    BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    NpmAssetWriter writer = mock(NpmAssetWriter.class);
    ProxyStateDao proxyStateDao = mock(ProxyStateDao.class);
    HttpRemoteFetcher fetcher = mock(HttpRemoteFetcher.class);
    NpmHostedService hosted = mock(NpmHostedService.class);
    ProxyNegativeCache negativeCache = mock(ProxyNegativeCache.class);
    AssetMetadataCache cache = mock(AssetMetadataCache.class);
    BlobStorage storage = mock(BlobStorage.class);
    return new Fixture(
        assetDao, registry, writer, proxyStateDao, fetcher, hosted, negativeCache, cache,
        storage, new NpmProxyService(
            assetDao, registry, writer, proxyStateDao, fetcher, hosted, new ObjectMapper(),
            negativeCache, cache, null));
  }

  private static RepositoryRuntime runtime(int maxAgeMinutes, Long blobStoreId) {
    return new RepositoryRuntime(
        10L, "npm", RepositoryFormat.NPM, RepositoryType.PROXY, "npm", true, blobStoreId,
        null, null, null, true, "https://registry.npmjs.org/",
        maxAgeMinutes, maxAgeMinutes, true, null, List.of());
  }

  private static RepositoryRuntime hostedRuntime() {
    return new RepositoryRuntime(
        10L, "npm", RepositoryFormat.NPM, RepositoryType.HOSTED, "npm", true, 7L,
        "ALLOW", null, null, true, null, null, null, null, null, List.of());
  }

  private static CachedAssetMetadata snapshot(
      String path, Instant updatedAt, String kind, Map<String, Object> blobAttributes) {
    AssetRecord asset = new AssetRecord(
        1L, 10L, 3L, 2L, RepositoryFormat.NPM, path, null,
        path.substring(path.lastIndexOf('/') + 1), kind,
        "tarball".equals(kind) ? "application/octet-stream" : "application/json",
        7L, null, updatedAt, Map.of());
    return CachedAssetMetadata.of(asset, blob(blobAttributes));
  }

  private static NpmAssetWriter.Stored stored(String path, String kind, String contentType) {
    AssetRecord asset = new AssetRecord(
        1L, 10L, 3L, 2L, RepositoryFormat.NPM, path, null,
        path.substring(path.lastIndexOf('/') + 1), kind, contentType,
        7L, null, Instant.now(), Map.of());
    return new NpmAssetWriter.Stored(
        asset, blob(Map.of()), new NpmAssetWriter.Digests("md5", "sha1", "sha256", "sha512", 7L),
        true, null);
  }

  private static AssetBlobRecord blob(Map<String, Object> attributes) {
    return new AssetBlobRecord(
        2L, 7L, "blob://bucket/object", null, "object", null,
        "sha1", "sha256", "md5", 7L, "application/octet-stream",
        "proxy", "upstream", Instant.EPOCH, Instant.EPOCH, attributes);
  }

  private record Fixture(
      AssetDao assetDao,
      BlobStorageRegistry registry,
      NpmAssetWriter writer,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      NpmHostedService hosted,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache cache,
      BlobStorage storage,
      NpmProxyService service) {
  }
}
