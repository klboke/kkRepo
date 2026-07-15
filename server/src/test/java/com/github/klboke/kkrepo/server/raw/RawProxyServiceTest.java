package com.github.klboke.kkrepo.server.raw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RawProxyServiceTest {
  @Test
  void cachePopulationAlwaysUsesUpstreamGetForClientHeadRequests() {
    RepositoryRuntime runtime = new RepositoryRuntime(
        10L,
        "composer-proxy",
        RepositoryFormat.COMPOSER,
        RepositoryType.PROXY,
        "composer-proxy",
        true,
        1L,
        null,
        null,
        null,
        true,
        "https://repo.packagist.org/",
        1440,
        1440,
        true,
        null,
        List.of());
    Instant lastModified = Instant.parse("2026-07-12T00:00:00Z");

    HttpRemoteFetcher.Request request = RawProxyService.cachePopulationRequest(
        runtime,
        "https://github.com/php-fig/log/archive/refs/tags/3.0.2.zip",
        "etag",
        lastModified);

    assertFalse(request.headOnly());
    assertEquals("GET", request.method());
    assertEquals(HttpRemoteFetcher.TimeoutProfile.CONTENT, request.timeoutProfile());
    assertEquals("composer-proxy", request.repository());
    assertEquals("COMPOSER", request.format());
    assertEquals(lastModified, request.lastModified());
  }

  @Test
  void servesFreshCachedAssetWithoutConsultingUpstreamState() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.PROXY, 60);
    CachedAssetMetadata cached = snapshot(Instant.now().plusSeconds(60));
    MavenResponse expected = MavenResponse.noBody(200);
    when(fixture.cache.find(eq(runtime.id()), eq("file.txt"), any()))
        .thenReturn(Optional.of(cached));
    when(fixture.reader.serveSnapshot(cached, false, "file.txt", "ATTACHMENT"))
        .thenReturn(expected);

    assertSame(expected, fixture.service.get(runtime, "file.txt", false));

    verify(fixture.negativeCache, never()).isNotFoundCached(runtime, "file.txt");
    verify(fixture.proxyStateDao, never()).isBlocked(eq(runtime.id()), any());
  }

  @Test
  void honorsNegativeCacheBeforeCheckingCircuitBreaker() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.PROXY, 60);
    when(fixture.cache.find(eq(runtime.id()), eq("missing.txt"), any()))
        .thenReturn(Optional.empty());
    when(fixture.negativeCache.isNotFoundCached(runtime, "missing.txt")).thenReturn(true);

    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> fixture.service.getAsset(runtime, "missing.txt", false));

    verify(fixture.proxyStateDao, never()).isBlocked(eq(runtime.id()), any());
  }

  @Test
  void servesStaleCacheWhileUpstreamIsBlocked() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.PROXY, 1);
    CachedAssetMetadata cached = snapshot(Instant.EPOCH);
    MavenResponse expected = MavenResponse.noBody(200);
    when(fixture.cache.find(eq(runtime.id()), eq("file.txt"), any()))
        .thenReturn(Optional.of(cached));
    when(fixture.proxyStateDao.isBlocked(eq(runtime.id()), any())).thenReturn(true);
    when(fixture.reader.serveSnapshot(cached, true, "file.txt", "ATTACHMENT"))
        .thenReturn(expected);

    assertSame(expected, fixture.service.getAsset(runtime, "file.txt", true));
  }

  @Test
  void metadataRequestsUseTheMetadataMaxAgeInsteadOfTheContentMaxAge() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.PROXY, 60, 1);
    CachedAssetMetadata cached = snapshot(Instant.now().minusSeconds(5 * 60));
    MavenResponse expected = MavenResponse.noBody(200);
    when(fixture.cache.find(eq(runtime.id()), eq("versions.json"), any()))
        .thenReturn(Optional.of(cached));
    when(fixture.proxyStateDao.isBlocked(eq(runtime.id()), any())).thenReturn(true);
    when(fixture.reader.serveSnapshot(cached, false, "versions.json", "ATTACHMENT"))
        .thenReturn(expected);

    assertSame(expected, fixture.service.getMetadataFromUrl(
        runtime, "versions.json", "https://upstream.example.test/versions", false));

    verify(fixture.proxyStateDao).isBlocked(eq(runtime.id()), any());
  }

  @Test
  void reportsBlockedUpstreamWhenNoCacheExists() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.PROXY, 1);
    when(fixture.cache.find(eq(runtime.id()), eq("file.txt"), any()))
        .thenReturn(Optional.empty());
    when(fixture.proxyStateDao.isBlocked(eq(runtime.id()), any())).thenReturn(true);

    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> fixture.service.getAssetFromUrl(
            runtime, "file.txt", "https://cdn.example.test/file.txt", false));
  }

  @Test
  void rejectsNonProxyRepositories() {
    Fixture fixture = fixture();

    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> fixture.service.get(runtime(RepositoryType.HOSTED, 60), "file.txt", false));
  }

  private static Fixture fixture() {
    AssetDao assetDao = mock(AssetDao.class);
    BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    RawAssetWriter writer = mock(RawAssetWriter.class);
    RawAssetReader reader = mock(RawAssetReader.class);
    ProxyStateDao proxyStateDao = mock(ProxyStateDao.class);
    HttpRemoteFetcher fetcher = mock(HttpRemoteFetcher.class);
    ProxyNegativeCache negativeCache = mock(ProxyNegativeCache.class);
    AssetMetadataCache cache = mock(AssetMetadataCache.class);
    return new Fixture(
        proxyStateDao,
        reader,
        negativeCache,
        cache,
        new RawProxyService(
            assetDao, registry, writer, reader, proxyStateDao, fetcher, negativeCache, cache));
  }

  private static RepositoryRuntime runtime(RepositoryType type, int maxAgeMinutes) {
    return runtime(type, maxAgeMinutes, maxAgeMinutes);
  }

  private static RepositoryRuntime runtime(
      RepositoryType type, int contentMaxAgeMinutes, int metadataMaxAgeMinutes) {
    return new RepositoryRuntime(
        10L,
        "raw-proxy",
        RepositoryFormat.RAW,
        type,
        "raw",
        true,
        1L,
        null,
        null,
        null,
        true,
        "https://upstream.example.test/",
        contentMaxAgeMinutes,
        metadataMaxAgeMinutes,
        true,
        "ATTACHMENT",
        List.of());
  }

  private static CachedAssetMetadata snapshot(Instant updatedAt) {
    AssetRecord asset = new AssetRecord(
        1L, 10L, null, 2L, RepositoryFormat.RAW, "file.txt", null,
        "file.txt", "raw", "text/plain", 4L, null, updatedAt, Map.of());
    AssetBlobRecord blob = new AssetBlobRecord(
        2L, 1L, "blob://bucket/file.txt", null, "file.txt", null,
        "sha1", "sha256", "md5", 4, "text/plain", "proxy", "upstream",
        Instant.EPOCH, updatedAt, Map.of());
    return CachedAssetMetadata.of(asset, blob);
  }

  private record Fixture(
      ProxyStateDao proxyStateDao,
      RawAssetReader reader,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache cache,
      RawProxyService service) {
  }
}
