package com.github.klboke.kkrepo.server.raw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenErrorAdvice;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.HexFormat;
import java.util.HashSet;
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
  void pinnedAssetsUseMetadataMaxAgeWithTheContentServingPath() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.PROXY, 1, 60);
    CachedAssetMetadata cached = snapshot(Instant.now().minusSeconds(5 * 60));
    MavenResponse expected = MavenResponse.noBody(200);
    when(fixture.cache.find(eq(runtime.id()), eq("provider.zip"), any()))
        .thenReturn(Optional.of(cached));
    when(fixture.reader.serveSnapshot(cached, false, "provider.zip", "ATTACHMENT"))
        .thenReturn(expected);

    assertSame(expected, fixture.service.getPinnedAssetFromUrl(
        runtime, "provider.zip", "https://upstream.example.test/provider.zip", false));

    verify(fixture.proxyStateDao, never()).isBlocked(eq(runtime.id()), any());
  }

  @Test
  void refetchesFreshAssetAfterConfiguredProxySourceChanges() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.PROXY, 60);
    String path = "src/contrib/PACKAGES.gz";
    String remoteUrl = "https://upstream.example.test/" + path;
    CachedAssetMetadata cached = snapshot(
        Instant.now().plusSeconds(60),
        Map.of(
            RawProxyService.REMOTE_SOURCE_FINGERPRINT, "different-upstream",
            "remoteEtag", "old-etag",
            "remoteLastModified", "2026-07-12T00:00:00Z"));
    when(fixture.cache.find(eq(runtime.id()), eq(path), any()))
        .thenReturn(Optional.of(cached));

    BlobStorage storage = mock(BlobStorage.class);
    when(fixture.registry.forBlobStoreId(1L)).thenReturn(storage);
    AssetRecord storedAsset = new AssetRecord(
        11L, runtime.id(), null, 12L, RepositoryFormat.R, path, null,
        "PACKAGES.gz", "package-index", "application/gzip", 4L, null,
        Instant.EPOCH, Map.of());
    AssetBlobRecord storedBlob = new AssetBlobRecord(
        12L, 1L, "blob://r/PACKAGES.gz", null, "PACKAGES.gz", null,
        "sha1", "sha256", "md5", 4L, "application/gzip", "proxy", null,
        Instant.EPOCH, Instant.EPOCH, Map.of());
    RawAssetWriter.Stored stored = new RawAssetWriter.Stored(
        storedAsset, storedBlob, null, true, null);
    MavenResponse expected = MavenResponse.noBody(200);
    when(fixture.reader.serve(storedAsset, true, path, "ATTACHMENT"))
        .thenReturn(expected);
    when(fixture.writer.writeUnindexed(
        eq(runtime), eq(storage), eq(1L), eq(path), any(), eq("application/gzip"),
        any(), eq("proxy"), isNull(), eq(true)))
        .thenAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          Map<String, String> extras = invocation.getArgument(6);
          assertEquals(
              RawProxyService.remoteSourceFingerprint(runtime, remoteUrl),
              extras.get(RawProxyService.REMOTE_SOURCE_FINGERPRINT));
          return stored;
        });
    doAnswer(invocation -> {
      HttpRemoteFetcher.Request request = invocation.getArgument(0);
      assertEquals(remoteUrl, request.url());
      assertEquals(null, request.etag());
      assertEquals(null, request.lastModified());
      HttpRemoteFetcher.ResultHandler<?> handler = invocation.getArgument(2);
      return handler.handle(new HttpRemoteFetcher.Result(
          200,
          Map.of("Content-Type", "application/gzip", "Content-Length", "4"),
          new ByteArrayInputStream(new byte[] {1, 2, 3, 4})));
    }).when(fixture.fetcher).fetchWithBodyRetry(any(), eq(path), any());

    assertSame(expected, fixture.service.getMetadataFromUrlUnindexed(
        runtime, path, remoteUrl, true));

    verify(fixture.reader, never()).serveSnapshot(
        cached, true, path, "ATTACHMENT");
  }

  @Test
  void coldPinnedAssetsSupportEveryComponentBinding() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.PROXY, 60);
    BlobStorage storage = mock(BlobStorage.class);
    when(fixture.registry.forBlobStoreId(1L)).thenReturn(storage);
    AssetRecord asset = new AssetRecord(
        11L, runtime.id(), null, 12L, RepositoryFormat.CONDA, "main/noarch/demo.conda",
        null, "demo.conda", "package", "application/octet-stream", 4L, null,
        Instant.EPOCH, Map.of());
    AssetBlobRecord blob = new AssetBlobRecord(
        12L, 1L, "blob://demo", null, "demo", null, "sha1", "sha256", "md5", 4L,
        "application/octet-stream", "proxy", "upstream", Instant.EPOCH, Instant.EPOCH,
        Map.of());
    RawAssetWriter.Stored stored = new RawAssetWriter.Stored(asset, blob, null, true, null);
    MavenResponse expected = MavenResponse.noBody(200);
    when(fixture.reader.serve(asset, true, asset.path(), "ATTACHMENT")).thenReturn(expected);
    when(fixture.writer.writeUnindexed(
        eq(runtime), eq(storage), eq(1L), eq(asset.path()), any(),
        eq("application/octet-stream"), any(), eq("proxy"),
        isNull(), eq(true)))
        .thenReturn(stored);
    when(fixture.writer.write(
        eq(runtime), eq(storage), eq(1L), eq(asset.path()), any(),
        eq("application/octet-stream"), any(), eq("proxy"),
        isNull(), eq(true)))
        .thenReturn(stored);
    when(fixture.writer.writeHidden(
        eq(runtime), eq(storage), eq(1L), eq(asset.path()), any(),
        eq("application/octet-stream"), any(), eq("proxy"),
        isNull(), eq(true)))
        .thenReturn(stored);
    ComponentRecord component = new ComponentRecord(
        null, runtime.id(), RepositoryFormat.CONDA, "main/noarch", "demo", "1.0", "package",
        new byte[32], Map.of(), Instant.EPOCH);
    when(fixture.writer.writeWithComponentAtBrowsePath(
        eq(runtime), eq(storage), eq(1L), eq("main/noarch/demo-1.0-0.conda"), any(),
        eq("application/octet-stream"), any(), eq("proxy"), isNull(),
        eq(component), eq("main/noarch/demo/1.0/demo-1.0-0.conda"), eq(true)))
        .thenReturn(new RawAssetWriter.Stored(
            new AssetRecord(
                13L, runtime.id(), 14L, 15L, RepositoryFormat.CONDA,
                "main/noarch/demo-1.0-0.conda", null, "demo-1.0-0.conda", "package",
                "application/octet-stream", 4L, null, Instant.EPOCH, Map.of()),
            blob, null, true, null));
    when(fixture.reader.serve(
        any(AssetRecord.class), eq(true), eq("main/noarch/demo-1.0-0.conda"), eq("ATTACHMENT")))
        .thenReturn(expected);
    doAnswer(invocation -> {
      HttpRemoteFetcher.ResultHandler<?> handler = invocation.getArgument(2);
      return handler.handle(new HttpRemoteFetcher.Result(
          200,
          Map.of("Content-Type", "application/octet-stream", "Content-Length", "4"),
          new ByteArrayInputStream(new byte[] {1, 2, 3, 4})));
    }).when(fixture.fetcher).fetchWithBodyRetry(any(), any(String.class), any());

    assertSame(expected, fixture.service.getPinnedAssetFromUrlUnindexed(
        runtime, asset.path(), "https://upstream.example.test/demo.conda", true));
    assertSame(expected, fixture.service.getPinnedAssetFromUrl(
        runtime, asset.path(), "https://upstream.example.test/demo.conda", true));
    assertSame(expected, fixture.service.getMetadataFromUrlHidden(
        runtime, asset.path(), "https://upstream.example.test/demo.conda", true));
    assertSame(expected, fixture.service.getPinnedAssetFromUrlWithComponentAtBrowsePath(
        runtime, "main/noarch/demo-1.0-0.conda",
        "https://upstream.example.test/demo-1.0-0.conda", component,
        "main/noarch/demo/1.0/demo-1.0-0.conda", true));

    verify(fixture.writer).writeUnindexed(
        eq(runtime), eq(storage), eq(1L), eq(asset.path()), any(),
        eq("application/octet-stream"), any(), eq("proxy"),
        isNull(), eq(true));
    verify(fixture.writer).write(
        eq(runtime), eq(storage), eq(1L), eq(asset.path()), any(),
        eq("application/octet-stream"), any(), eq("proxy"),
        isNull(), eq(true));
    verify(fixture.writer).writeHidden(
        eq(runtime), eq(storage), eq(1L), eq(asset.path()), any(),
        eq("application/octet-stream"), any(), eq("proxy"),
        isNull(), eq(true));
    verify(fixture.writer).writeWithComponentAtBrowsePath(
        eq(runtime), eq(storage), eq(1L), eq("main/noarch/demo-1.0-0.conda"), any(),
        eq("application/octet-stream"), any(), eq("proxy"), isNull(),
        eq(component), eq("main/noarch/demo/1.0/demo-1.0-0.conda"), eq(true));
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
  void blockedUpstreamResponsesDoNotExposeConfiguredOrDiscoveredUrls() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = nugetRuntime();
    when(fixture.proxyStateDao.isBlocked(eq(runtime.id()), any())).thenReturn(true);
    String path = "v3-flatcontainer/demo/1.0.0/demo.1.0.0.nupkg";
    String signed = "https://upstream.example.test/flat2/demo.nupkg?token=private-secret";
    for (boolean discovered : List.of(true, false)) {
      var error = assertThrows(MavenExceptions.BadUpstreamException.class, () -> {
        if (discovered) fixture.service.getAssetFromUrl(runtime, path, signed, false);
        else fixture.service.getAsset(runtime, path, false);
      });
      var response = new MavenErrorAdvice().upstream(error);
      assertEquals(502, response.getStatusCode().value());
      assertEquals("Upstream temporarily blocked", response.getBody().get("message"));
    }
  }

  @Test
  void transportErrorsDoNotExposeSignedUrlsToRepositoryReaders() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = nugetRuntime();
    String path = "v3-flatcontainer/demo/1.0.0/demo.1.0.0.nupkg";
    String signed = "https://upstream.example.test/flat2/demo.nupkg?token=private-secret";
    when(fixture.fetcher.fetchWithBodyRetry(any(), eq(path), any()))
        .thenThrow(new IOException("Too many redirects fetching " + signed));
    var error = assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> fixture.service.getAssetFromUrl(runtime, path, signed, false));
    var response = new MavenErrorAdvice().upstream(error);
    assertEquals(502, response.getStatusCode().value());
    assertEquals("Upstream IO error: IOException", response.getBody().get("message"));
  }

  @Test
  void nugetUpgradeKeepsLegacyMetadataAndContentUsableEvenWhenBlocked() throws Exception {
    RepositoryRuntime runtime = nugetRuntime();
    String legacy = HexFormat.of().formatHex(PersistenceHashes.sha256("raw-proxy-source-v1", runtime.proxyRemoteUrl()));
    for (String path : List.of("_nuget/index/abc", "v3-flatcontainer/demo/1.0.0/demo.1.0.0.nupkg")) {
      for (Instant updatedAt : List.of(Instant.now(), Instant.EPOCH)) {
        Fixture fixture = fixture();
        CachedAssetMetadata cached = snapshot(updatedAt, Map.of(RawProxyService.REMOTE_SOURCE_FINGERPRINT, legacy));
        when(fixture.cache.find(eq(runtime.id()), eq(path), any())).thenReturn(Optional.of(cached));
        when(fixture.proxyStateDao.isBlocked(eq(runtime.id()), any())).thenReturn(true);
        MavenResponse expected = MavenResponse.noBody(200);
        when(fixture.reader.serveSnapshot(cached, true, path, "ATTACHMENT")).thenReturn(expected);
        MavenResponse actual = path.startsWith("_nuget/")
            ? fixture.service.getMetadataFromUrlHidden(runtime, path, runtime.proxyRemoteUrl(), true)
            : fixture.service.getAssetFromUrl(runtime, path, "https://upstream.example.test/flat2/demo.nupkg", true);
        assertSame(expected, actual);
        verify(fixture.fetcher, never()).fetchWithBodyRetry(any(), any(), any());
      }
    }
  }

  @Test
  void legacyNugetEntriesOnlyFallBackOnOutagesAndNeverOnAuthoritativeMisses() throws Exception {
    RepositoryRuntime runtime = nugetRuntime();
    String path = "v3-flatcontainer/demo/1.0.0/demo.1.0.0.nupkg";
    String url = "https://upstream.example.test/new-flat2/demo.nupkg";
    String legacy = HexFormat.of().formatHex(PersistenceHashes.sha256("raw-proxy-source-v1", runtime.proxyRemoteUrl()));
    for (int status : List.of(404, 410, 503)) {
      for (Instant updatedAt : List.of(Instant.EPOCH, Instant.now())) {
        Fixture fixture = fixture();
        CachedAssetMetadata cached = snapshot(updatedAt, Map.of(RawProxyService.REMOTE_SOURCE_FINGERPRINT, legacy));
        when(fixture.cache.find(eq(runtime.id()), eq(path), any())).thenReturn(Optional.of(cached));
        MavenResponse fallback = MavenResponse.noBody(200);
        when(fixture.reader.serveSnapshot(cached, true, path, "ATTACHMENT")).thenReturn(fallback);
        doAnswer(invocation -> {
          HttpRemoteFetcher.ResultHandler<?> handler = invocation.getArgument(2);
          return handler.handle(new HttpRemoteFetcher.Result(status, Map.of(), InputStream.nullInputStream()));
        }).when(fixture.fetcher).fetchWithBodyRetry(any(), eq(path), any());
        if (status == 503) {
          assertSame(fallback, fixture.service.getAssetFromUrl(runtime, path, url, true));
        } else {
          assertThrows(MavenExceptions.MavenNotFoundException.class,
              () -> fixture.service.getAssetFromUrl(runtime, path, url, true));
          verify(fixture.reader, never()).serveSnapshot(cached, true, path, "ATTACHMENT");
        }
        verify(fixture.fetcher).fetchWithBodyRetry(any(), eq(path), any());
      }
    }
  }

  @Test
  void nugetUpgradeRejectsLegacyCacheFromADifferentConfiguredIndex() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = nugetRuntime();
    String legacy = HexFormat.of().formatHex(PersistenceHashes.sha256(
        "raw-proxy-source-v1", "https://previous.example.test/v3/index.json"));
    String path = "v3-flatcontainer/demo/1.0.0/demo.1.0.0.nupkg";
    CachedAssetMetadata cached = snapshot(Instant.now(), Map.of(RawProxyService.REMOTE_SOURCE_FINGERPRINT, legacy));
    when(fixture.cache.find(eq(runtime.id()), eq(path), any())).thenReturn(Optional.of(cached));
    when(fixture.proxyStateDao.isBlocked(eq(runtime.id()), any())).thenReturn(true);
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> fixture.service.getAssetFromUrl(runtime, path, "https://upstream.example.test/flat2/demo.nupkg", false));
    verify(fixture.reader, never()).serveSnapshot(cached, false, path, "ATTACHMENT");
  }

  @Test
  void nugetQueriesAndResourceMovesNeverReuseContentFromADifferentUrl() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = nugetRuntime();
    String path = "v3-flatcontainer/demo/1.0.0/demo.1.0.0.nupkg";
    String url = "https://upstream.example.test/flat2/demo.nupkg?tenant=feed&variant=fips&sig=first";
    CachedAssetMetadata cached = snapshot(Instant.now(), Map.of(
        RawProxyService.REMOTE_SOURCE_FINGERPRINT, RawProxyService.remoteSourceFingerprint(runtime, url)));
    when(fixture.cache.find(eq(runtime.id()), eq(path), any())).thenReturn(Optional.of(cached));
    MavenResponse expected = MavenResponse.noBody(200);
    when(fixture.reader.serveSnapshot(cached, true, path, "ATTACHMENT")).thenReturn(expected);
    assertSame(expected, fixture.service.getAssetFromUrl(runtime, path, url, true));
    when(fixture.proxyStateDao.isBlocked(eq(runtime.id()), any())).thenReturn(true);
    for (String changed : List.of(url.replace("variant=fips", "variant=standard"),
        url.replace("sig=first", "sig=rotated"), url.replace("/flat2/", "/new-flat2/"),
        url.replace("tenant=feed", "tenant=other"))) {
      assertThrows(MavenExceptions.BadUpstreamException.class,
          () -> fixture.service.getAssetFromUrl(runtime, path, changed, true));
    }
    verify(fixture.fetcher, never()).fetchWithBodyRetry(any(), any(), any());
    verify(fixture.reader).serveSnapshot(cached, true, path, "ATTACHMENT");
  }

  @Test
  void querySelectedNugetContentNeverFallsBackToUnverifiedLegacyBytes() throws Exception {
    RepositoryRuntime runtime = nugetRuntime();
    String path = "v3-flatcontainer/demo/1.0.0/demo.1.0.0.nupkg";
    String url = "https://upstream.example.test/flat2/demo.nupkg?variant=fips";
    String legacy = HexFormat.of().formatHex(PersistenceHashes.sha256("raw-proxy-source-v1", runtime.proxyRemoteUrl()));
    for (boolean fingerprinted : List.of(false, true)) {
      for (boolean blocked : List.of(false, true)) {
        Fixture fixture = fixture();
        CachedAssetMetadata cached = snapshot(Instant.EPOCH,
            fingerprinted ? Map.of(RawProxyService.REMOTE_SOURCE_FINGERPRINT, legacy) : Map.of());
        when(fixture.cache.find(eq(runtime.id()), eq(path), any())).thenReturn(Optional.of(cached));
        when(fixture.proxyStateDao.isBlocked(eq(runtime.id()), any())).thenReturn(blocked);
        if (!blocked) {
          doAnswer(call -> {
            HttpRemoteFetcher.ResultHandler<?> handler = call.getArgument(2);
            return handler.handle(new HttpRemoteFetcher.Result(503, Map.of(), InputStream.nullInputStream()));
          }).when(fixture.fetcher).fetchWithBodyRetry(any(), eq(path), any());
        }
        assertThrows(MavenExceptions.BadUpstreamException.class,
            () -> fixture.service.getAssetFromUrl(runtime, path, url, true));
        verify(fixture.reader, never()).serveSnapshot(any(), eq(true), eq(path), any());
      }
    }
  }

  @Test
  void legacyNugetRefreshDropsValidatorsAndEstablishesSourceOnlyFromAFullResponse() throws Exception {
    RepositoryRuntime runtime = nugetRuntime();
    String path = "v3-flatcontainer/demo/1.0.0/demo.1.0.0.nupkg";
    String legacy = HexFormat.of().formatHex(PersistenceHashes.sha256("raw-proxy-source-v1", runtime.proxyRemoteUrl()));
    for (boolean discovered : List.of(true, false)) {
      for (boolean fingerprinted : List.of(true, false)) {
        Fixture fixture = fixture();
        String url = discovered ? "https://upstream.example.test/flat2/demo.nupkg" : runtime.proxyRemoteUrl() + "/" + path;
        Map<String, Object> attributes = new java.util.HashMap<>(Map.of("remoteEtag", "same-etag",
            "remoteLastModified", "2026-07-12T00:00:00Z"));
        if (fingerprinted) attributes.put(RawProxyService.REMOTE_SOURCE_FINGERPRINT, legacy);
        CachedAssetMetadata cached = snapshot(Instant.EPOCH, attributes);
        when(fixture.cache.find(eq(runtime.id()), eq(path), any())).thenReturn(Optional.of(cached));
        BlobStorage storage = mock(BlobStorage.class);
        when(fixture.registry.forBlobStoreId(1L)).thenReturn(storage);
        CachedAssetMetadata content = snapshot(Instant.now());
        RawAssetWriter.Stored stored = new RawAssetWriter.Stored(content.toAssetRecord(), content.toBlobRecord(), null, true, null);
        when(fixture.writer.write(eq(runtime), eq(storage), eq(1L), eq(path), any(), any(), any(), eq("proxy"), isNull(), eq(true)))
            .thenReturn(stored);
        when(fixture.reader.serve(stored.asset(), true, path, "ATTACHMENT")).thenReturn(MavenResponse.noBody(200));
        doAnswer(invocation -> {
          HttpRemoteFetcher.Request request = invocation.getArgument(0);
          assertEquals(null, request.etag());
          assertEquals(null, request.lastModified());
          HttpRemoteFetcher.ResultHandler<?> handler = invocation.getArgument(2);
          // The different URL may legitimately use the same opaque ETag.
          return handler.handle(new HttpRemoteFetcher.Result(200,
              Map.of("Content-Type", "application/zip", "ETag", "same-etag"),
              new ByteArrayInputStream(new byte[] {4, 3, 2, 1})));
        }).when(fixture.fetcher).fetchWithBodyRetry(any(), eq(path), any());
        MavenResponse response = discovered ? fixture.service.getAssetFromUrl(runtime, path, url, true)
            : fixture.service.getAsset(runtime, path, true);
        assertEquals(200, response.status());
        verify(fixture.writer).write(eq(runtime), eq(storage), eq(1L), eq(path), any(), eq("application/zip"),
            eq(Map.of(RawProxyService.REMOTE_SOURCE_FINGERPRINT, RawProxyService.remoteSourceFingerprint(runtime, url),
                "remoteEtag", "same-etag")), eq("proxy"), isNull(), eq(true));
        verify(fixture.assetDao, never()).updateBlobAttributes(eq(cached.blob().id()), any());
        verify(fixture.cache, never()).touchVerified(eq(runtime.id()), eq(path), any());
      }
    }
  }

  @Test
  void nuget304OnlyRefreshesAnAlreadyMatchingSource() throws Exception {
    RepositoryRuntime runtime = nugetRuntime();
    String path = "v3-flatcontainer/demo/1.0.0/demo.1.0.0.nupkg";
    String url = "https://upstream.example.test/flat2/demo.nupkg";
    for (boolean sameSource : List.of(true, false)) {
      Fixture fixture = fixture();
      String fingerprint = sameSource ? RawProxyService.remoteSourceFingerprint(runtime, url)
          : HexFormat.of().formatHex(PersistenceHashes.sha256("raw-proxy-source-v1", runtime.proxyRemoteUrl()));
      CachedAssetMetadata cached = snapshot(Instant.EPOCH, Map.of(
          RawProxyService.REMOTE_SOURCE_FINGERPRINT, fingerprint, "remoteEtag", "etag"));
      when(fixture.cache.find(eq(runtime.id()), eq(path), any())).thenReturn(Optional.of(cached));
      doAnswer(invocation -> {
        HttpRemoteFetcher.Request request = invocation.getArgument(0);
        assertEquals(sameSource ? "etag" : null, request.etag());
        HttpRemoteFetcher.ResultHandler<?> handler = invocation.getArgument(2);
        return handler.handle(new HttpRemoteFetcher.Result(304, Map.of(), InputStream.nullInputStream()));
      }).when(fixture.fetcher).fetchWithBodyRetry(any(), eq(path), any());
      fixture.service.getAssetFromUrl(runtime, path, url, false);
      verify(fixture.cache, times(sameSource ? 1 : 0)).touchVerified(eq(runtime.id()), eq(path), any());
      verify(fixture.assetDao, never()).updateBlobAttributes(eq(cached.blob().id()), any());
    }
  }

  @Test
  void offlineDiscoveryFallbackServesOnlyLegacyContentFromTheConfiguredIndex() throws Exception {
    RepositoryRuntime runtime = nugetRuntime();
    String path = "v3-flatcontainer/demo/1.0.0/demo.1.0.0.nupkg";
    String legacy = HexFormat.of().formatHex(PersistenceHashes.sha256("raw-proxy-source-v1", runtime.proxyRemoteUrl()));
    for (String fingerprint : List.of(legacy, "other-index", RawProxyService.remoteSourceFingerprint(runtime, "https://cdn/flat2/"))) {
      Fixture fixture = fixture();
      CachedAssetMetadata cached = snapshot(Instant.EPOCH, Map.of(RawProxyService.REMOTE_SOURCE_FINGERPRINT, fingerprint));
      when(fixture.cache.find(eq(runtime.id()), eq(path), any())).thenReturn(Optional.of(cached));
      MavenResponse expected = MavenResponse.noBody(200);
      when(fixture.reader.serveSnapshot(cached, true, path, "ATTACHMENT")).thenReturn(expected);
      assertEquals(fingerprint.equals(legacy) ? Optional.of(expected) : Optional.empty(),
          fixture.service.getLegacyNugetAsset(runtime, path, true));
      verify(fixture.fetcher, never()).fetchWithBodyRetry(any(), any(), any());
    }
    assertEquals(Optional.empty(), fixture().service.getLegacyNugetAsset(runtime(RepositoryType.PROXY, 60), path, false));
  }

  @Test
  void signedMissesAreIsolatedByTheirCompleteUrl() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = nugetRuntime();
    String path = "v3-flatcontainer/demo/1.0.0/demo.1.0.0.nupkg";
    String stable = "https://upstream.example.test/flat2/demo.nupkg";
    BlobStorage storage = mock(BlobStorage.class);
    when(fixture.registry.forBlobStoreId(1L)).thenReturn(storage);
    CachedAssetMetadata content = snapshot(Instant.now());
    RawAssetWriter.Stored stored = new RawAssetWriter.Stored(content.toAssetRecord(), content.toBlobRecord(), null, true, null);
    when(fixture.writer.write(eq(runtime), eq(storage), eq(1L), eq(path), any(), any(), any(), eq("proxy"), isNull(), eq(true)))
        .thenReturn(stored);
    when(fixture.reader.serve(stored.asset(), true, path, "ATTACHMENT")).thenReturn(MavenResponse.noBody(200));
    var misses = new HashSet<String>();
    when(fixture.negativeCache.isNotFoundCached(eq(runtime), any(String.class)))
        .thenAnswer(invocation -> misses.contains(invocation.getArgument(1)));
    doAnswer(invocation -> { misses.add(invocation.getArgument(1)); return null; })
        .when(fixture.negativeCache).rememberNotFound(eq(runtime), any(String.class));
    doAnswer(invocation -> {
      HttpRemoteFetcher.Request request = invocation.getArgument(0);
      HttpRemoteFetcher.ResultHandler<?> handler = invocation.getArgument(2);
      return handler.handle(new HttpRemoteFetcher.Result(request.url().endsWith("expired") ? 404 : 200,
          Map.of("Content-Type", "application/zip"), InputStream.nullInputStream()));
    }).when(fixture.fetcher).fetchWithBodyRetry(any(), eq(path), any());
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> fixture.service.getAssetFromUrl(runtime, path, stable + "?sig=expired", true));
    assertEquals(200, fixture.service.getAssetFromUrl(runtime, path, stable + "?sig=valid", true).status());
    verify(fixture.fetcher, times(2)).fetchWithBodyRetry(any(), eq(path), any());
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> fixture.service.getAssetFromUrl(runtime, path, stable + "?sig=expired", true));
    verify(fixture.fetcher, times(2)).fetchWithBodyRetry(any(), eq(path), any());
    assertEquals(1, misses.size());
  }

  @Test
  void nugetResourceChangesDoNotReuseFreshContentFromThePreviousEndpoint() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = nugetRuntime();
    String path = "v3-flatcontainer/demo/1.0.0/demo.1.0.0.nupkg";
    String previous = "https://upstream.example.test/flat2/" + path;
    String replacement = "https://upstream.example.test/new-flat2/" + path;
    CachedAssetMetadata cached = snapshot(Instant.now(), Map.of(
        RawProxyService.REMOTE_SOURCE_FINGERPRINT, RawProxyService.remoteSourceFingerprint(runtime, previous),
        "remoteEtag", "old-etag"));
    when(fixture.cache.find(eq(runtime.id()), eq(path), any())).thenReturn(Optional.of(cached));
    doAnswer(invocation -> {
      HttpRemoteFetcher.Request request = invocation.getArgument(0);
      assertEquals(replacement, request.url());
      assertEquals(null, request.etag());
      HttpRemoteFetcher.ResultHandler<?> handler = invocation.getArgument(2);
      return handler.handle(new HttpRemoteFetcher.Result(404, Map.of(), InputStream.nullInputStream()));
    }).when(fixture.fetcher).fetchWithBodyRetry(any(), eq(path), any());

    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> fixture.service.getAssetFromUrl(runtime, path, replacement, false));
    verify(fixture.reader, never()).serveSnapshot(cached, false, path, "ATTACHMENT");
  }

  @Test
  void nugetMissesAreCachedPerDiscoveredEndpointAndIgnoreLegacyGuessedPathMisses() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = nugetRuntime();
    String path = "v3-flatcontainer/demo/1.0.0/demo.1.0.0.nupkg";
    String first = "https://upstream.example.test/flat2/" + path;
    String second = "https://upstream.example.test/new-flat2/" + path;
    var misses = new HashSet<>(List.of(path)); // A 404 left by the pre-discovery implementation.
    when(fixture.negativeCache.isNotFoundCached(eq(runtime), any(String.class)))
        .thenAnswer(invocation -> misses.contains(invocation.getArgument(1)));
    doAnswer(invocation -> { misses.add(invocation.getArgument(1)); return null; })
        .when(fixture.negativeCache).rememberNotFound(eq(runtime), any(String.class));
    doAnswer(invocation -> {
      HttpRemoteFetcher.ResultHandler<?> handler = invocation.getArgument(2);
      return handler.handle(new HttpRemoteFetcher.Result(404, Map.of(), InputStream.nullInputStream()));
    }).when(fixture.fetcher).fetchWithBodyRetry(any(), eq(path), any());

    for (String url : List.of(first, first, second, second)) {
      assertThrows(MavenExceptions.MavenNotFoundException.class,
          () -> fixture.service.getAssetFromUrl(runtime, path, url, false));
    }
    verify(fixture.fetcher, times(2)).fetchWithBodyRetry(any(), eq(path), any());
    assertEquals(3, misses.size());
  }

  private static RepositoryRuntime nugetRuntime() {
    return new RepositoryRuntime(10L, "nuget-proxy", RepositoryFormat.NUGET, RepositoryType.PROXY,
        "nuget-proxy", true, 1L, null, null, null, true,
        "https://upstream.example.test/v3/index.json", 60, 60, true, "ATTACHMENT", List.of());
  }

  @Test
  void invalidMetadataNeverReplacesCacheAndUnvalidatedBadEntriesRecoverImmediately() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = nugetRuntime();
    String path = "_nuget/index/test";
    String url = runtime.proxyRemoteUrl();
    String fingerprint = RawProxyService.remoteSourceFingerprint(runtime, url);
    CachedAssetMetadata bad = snapshot(Instant.now(), Map.of(RawProxyService.REMOTE_SOURCE_FINGERPRINT, fingerprint));
    var current = new java.util.concurrent.atomic.AtomicReference<>(bad);
    when(fixture.cache.find(eq(runtime.id()), eq(path), any())).thenAnswer(call -> Optional.of(current.get()));
    when(fixture.reader.serveSnapshot(eq(bad), eq(false), eq(path), any())).thenAnswer(call ->
        MavenResponse.ok(new ByteArrayInputStream("broken".getBytes()), 6, "application/json", null, null));
    MavenResponse expected = MavenResponse.noBody(200);
    when(fixture.reader.serveSnapshot(any(), eq(true), eq(path), any())).thenReturn(expected);
    BlobStorage storage = mock(BlobStorage.class);
    when(fixture.registry.forBlobStoreId(1L)).thenReturn(storage);
    AssetRecord asset = bad.toAssetRecord();
    RawAssetWriter.Stored stored = new RawAssetWriter.Stored(asset, bad.toBlobRecord(), null, true, null);
    when(fixture.reader.serve(asset, true, path, "ATTACHMENT")).thenReturn(expected);
    when(fixture.writer.writeHidden(eq(runtime), eq(storage), eq(1L), eq(path), any(),
        eq("application/json"), any(), eq("proxy"), isNull(), eq(true))).thenAnswer(call -> {
          assertEquals("valid", new String(((InputStream) call.getArgument(4)).readAllBytes()));
          Map<String, String> extras = call.getArgument(6);
          assertEquals("test-index-v1", extras.get("metadataValidation"));
          current.set(snapshot(Instant.now(), Map.of(RawProxyService.REMOTE_SOURCE_FINGERPRINT, fingerprint,
              "metadataValidation", "test-index-v1")));
          return stored;
        });
    var fetches = new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(call -> {
      HttpRemoteFetcher.Request request = call.getArgument(0);
      assertEquals(null, request.etag());
      HttpRemoteFetcher.ResultHandler<?> handler = call.getArgument(2);
      return handler.handle(new HttpRemoteFetcher.Result(200, Map.of("Content-Type", "application/json"),
          new ByteArrayInputStream((fetches.getAndIncrement() == 0 ? "broken" : "valid").getBytes())));
    }).when(fixture.fetcher).fetchWithBodyRetry(any(), eq(path), any());
    java.util.function.UnaryOperator<InputStream> validator = body -> {
      try (body) {
        byte[] bytes = body.readAllBytes();
        if (!new String(bytes).equals("valid")) throw new MavenExceptions.BadUpstreamException("Invalid metadata");
        return new ByteArrayInputStream(bytes);
      } catch (IOException e) { throw new MavenExceptions.BadUpstreamException("Invalid metadata"); }
    };
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> fixture.service.getMetadataFromUrlHidden(runtime, path, url, true, "test-index-v1", validator));
    verify(fixture.writer, never()).writeHidden(any(), any(), eq(1L), any(), any(), any(), any(), any(), any(), eq(true));
    assertSame(expected, fixture.service.getMetadataFromUrlHidden(runtime, path, url, true, "test-index-v1", validator));
    assertSame(expected, fixture.service.getMetadataFromUrlHidden(runtime, path, url, true, "test-index-v1", validator));
    assertEquals(2, fetches.get());
    verify(fixture.writer, times(1)).writeHidden(any(), any(), eq(1L), any(), any(), any(), any(), any(), any(), eq(true));
  }

  @Test
  void validUnmarkedMetadataRemainsUsableWithoutAnUpstreamDuringUpgrade() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = nugetRuntime();
    String path = "_nuget/index/test";
    CachedAssetMetadata cached = snapshot(Instant.now(), Map.of(
        RawProxyService.REMOTE_SOURCE_FINGERPRINT, RawProxyService.remoteSourceFingerprint(runtime, runtime.proxyRemoteUrl())));
    when(fixture.cache.find(eq(runtime.id()), eq(path), any())).thenReturn(Optional.of(cached));
    byte[] bytes = "{\"resources\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    when(fixture.reader.serveSnapshot(cached, false, path, "ATTACHMENT")).thenAnswer(call ->
        MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length, "application/json", null, null));
    var validations = new java.util.concurrent.atomic.AtomicInteger();
    try (InputStream result = fixture.service.getMetadataFromUrlHidden(runtime, path, runtime.proxyRemoteUrl(), false,
        "test-index-v1", body -> {
          try (body) {
            byte[] validated = body.readAllBytes();
            assertEquals("{\"resources\":[]}", new String(validated, java.nio.charset.StandardCharsets.UTF_8));
            validations.incrementAndGet();
            return new ByteArrayInputStream(validated);
          } catch (IOException e) { throw new AssertionError(e); }
        }).body()) {
      assertEquals("{\"resources\":[]}", new String(result.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
    assertEquals(1, validations.get());
    verify(fixture.fetcher, never()).fetchWithBodyRetry(any(), any(), any());
    verify(fixture.proxyStateDao, never()).isBlocked(eq(runtime.id()), any());
    verify(fixture.assetDao, never()).touchAssetLastUpdated(eq(cached.assetId()), any());
  }

  @Test
  void invalidRefreshKeepsLastValidatedMetadataWithoutExtendingItsTtl() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = nugetRuntime();
    String path = "_nuget/index/test";
    CachedAssetMetadata cached = snapshot(Instant.EPOCH, Map.of(
        RawProxyService.REMOTE_SOURCE_FINGERPRINT, RawProxyService.remoteSourceFingerprint(runtime, runtime.proxyRemoteUrl()),
        "metadataValidation", "test-index-v1"));
    when(fixture.cache.find(eq(runtime.id()), eq(path), any())).thenReturn(Optional.of(cached));
    MavenResponse expected = MavenResponse.noBody(200);
    when(fixture.reader.serveSnapshot(cached, true, path, "ATTACHMENT")).thenReturn(expected);
    doAnswer(call -> {
      HttpRemoteFetcher.ResultHandler<?> handler = call.getArgument(2);
      return handler.handle(new HttpRemoteFetcher.Result(200, Map.of(), InputStream.nullInputStream()));
    }).when(fixture.fetcher).fetchWithBodyRetry(any(), eq(path), any());
    assertSame(expected, fixture.service.getMetadataFromUrlHidden(runtime, path, runtime.proxyRemoteUrl(), true,
        "test-index-v1", body -> { throw new MavenExceptions.BadUpstreamException("Invalid metadata"); }));
    verify(fixture.writer, never()).writeHidden(any(), any(), eq(1L), any(), any(), any(), any(), any(), any(), eq(true));
    verify(fixture.assetDao, never()).touchAssetLastUpdated(eq(cached.assetId()), any());
    verify(fixture.proxyStateDao, never()).recordSuccess(eq(runtime.id()), any());
  }

  @Test
  void malformedRefreshBackoffIsSharedWithOtherReadersAndRetainsStaleData() throws Exception {
    for (boolean hasCache : List.of(false, true)) {
      Fixture fixture = fixture();
      RepositoryRuntime runtime = nugetRuntime();
      String path = "_nuget/index/test";
      CachedAssetMetadata cached = snapshot(Instant.EPOCH, Map.of(
          RawProxyService.REMOTE_SOURCE_FINGERPRINT, RawProxyService.remoteSourceFingerprint(runtime, runtime.proxyRemoteUrl()),
          "metadataValidation", "test-index-v1"));
      when(fixture.cache.find(eq(runtime.id()), eq(path), any())).thenReturn(hasCache ? Optional.of(cached) : Optional.empty());
      MavenResponse stale = MavenResponse.noBody(200);
      when(fixture.reader.serveSnapshot(cached, true, path, "ATTACHMENT")).thenReturn(stale);
      var blocked = new java.util.concurrent.atomic.AtomicBoolean();
      when(fixture.proxyStateDao.isBlocked(eq(runtime.id()), any())).thenAnswer(call -> blocked.get());
      doAnswer(call -> { blocked.set(true); return null; }).when(fixture.proxyStateDao)
          .recordFailure(eq(runtime.id()), eq(30L), eq("Invalid upstream metadata"), any());
      doAnswer(call -> ((HttpRemoteFetcher.ResultHandler<?>) call.getArgument(2)).handle(
          new HttpRemoteFetcher.Result(200, Map.of(), InputStream.nullInputStream())))
          .when(fixture.fetcher).fetchWithBodyRetry(any(), eq(path), any());
      // A second service instance represents another replica using the same DAO state.
      RawProxyService replica = new RawProxyService(fixture.assetDao, fixture.registry, fixture.writer,
          fixture.reader, fixture.proxyStateDao, fixture.fetcher, fixture.negativeCache, fixture.cache);
      for (RawProxyService service : List.of(fixture.service, replica)) {
        java.util.function.Supplier<MavenResponse> read = () -> service.getMetadataFromUrlHidden(runtime, path,
            runtime.proxyRemoteUrl(), true, "test-index-v1", body -> {
              throw new MavenExceptions.BadUpstreamException("malformed response containing private URL");
            });
        if (hasCache) assertSame(stale, read.get());
        else assertThrows(MavenExceptions.BadUpstreamException.class, read::get);
      }
      verify(fixture.fetcher, times(1)).fetchWithBodyRetry(any(), eq(path), any());
      verify(fixture.proxyStateDao).recordFailure(eq(runtime.id()), eq(30L), eq("Invalid upstream metadata"), any());
      verify(fixture.proxyStateDao, never()).recordSuccess(eq(runtime.id()), any());
      verify(fixture.assetDao, never()).touchAssetLastUpdated(eq(cached.assetId()), any());
    }
  }

  @Test
  void discoveryHintsRequireValidatedMetadataFromTheExactConfiguredSource() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = nugetRuntime();
    String path = "_nuget/index/fallback";
    String url = runtime.proxyRemoteUrl() + "/index.json";
    String fingerprint = RawProxyService.remoteSourceFingerprint(runtime, url);
    for (Map<String, Object> attributes : List.<Map<String, Object>>of(Map.of(),
        Map.of("metadataValidation", "index-v1"),
        Map.of(RawProxyService.REMOTE_SOURCE_FINGERPRINT, fingerprint),
        Map.of("metadataValidation", "different-validator", RawProxyService.REMOTE_SOURCE_FINGERPRINT, fingerprint),
        Map.of("metadataValidation", "index-v1", RawProxyService.REMOTE_SOURCE_FINGERPRINT,
            HexFormat.of().formatHex(PersistenceHashes.sha256("raw-proxy-source-v1", runtime.proxyRemoteUrl()))),
        Map.of("metadataValidation", "index-v1", RawProxyService.REMOTE_SOURCE_FINGERPRINT,
            RawProxyService.remoteSourceFingerprint(runtime, url + "?key=different")))) {
      when(fixture.cache.find(eq(runtime.id()), eq(path), any())).thenReturn(Optional.of(snapshot(Instant.EPOCH, attributes)));
      assertFalse(fixture.service.hasValidatedMetadataFromUrlHidden(runtime, path, url, "index-v1"));
    }
    when(fixture.cache.find(eq(runtime.id()), eq(path), any())).thenReturn(Optional.of(snapshot(Instant.EPOCH,
        Map.of("metadataValidation", "index-v1", RawProxyService.REMOTE_SOURCE_FINGERPRINT, fingerprint))));
    assertEquals(true, fixture.service.hasValidatedMetadataFromUrlHidden(runtime, path, url, "index-v1"));
    assertFalse(fixture.service.hasValidatedMetadataFromUrlHidden(runtime, path, url + "?key=different", "index-v1"));
    when(fixture.cache.find(eq(runtime.id()), eq(path), any())).thenReturn(Optional.empty());
    assertFalse(fixture.service.hasValidatedMetadataFromUrlHidden(runtime, path, url, "index-v1"));
    when(fixture.cache.find(eq(runtime.id()), eq(path), any())).thenReturn(Optional.of(mock(CachedAssetMetadata.class)));
    assertFalse(fixture.service.hasValidatedMetadataFromUrlHidden(runtime, path, url, "index-v1"));
    verify(fixture.fetcher, never()).fetchWithBodyRetry(any(), any(), any());
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
        assetDao,
        proxyStateDao,
        reader,
        negativeCache,
        cache,
        registry,
        writer,
        fetcher,
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
    return snapshot(updatedAt, Map.of());
  }

  private static CachedAssetMetadata snapshot(
      Instant updatedAt,
      Map<String, Object> blobAttributes) {
    AssetRecord asset = new AssetRecord(
        1L, 10L, null, 2L, RepositoryFormat.RAW, "file.txt", null,
        "file.txt", "raw", "text/plain", 4L, null, updatedAt, Map.of());
    AssetBlobRecord blob = new AssetBlobRecord(
        2L, 1L, "blob://bucket/file.txt", null, "file.txt", null,
        "sha1", "sha256", "md5", 4, "text/plain", "proxy", "upstream",
        Instant.EPOCH, updatedAt, blobAttributes);
    return CachedAssetMetadata.of(asset, blob);
  }

  private record Fixture(
      AssetDao assetDao,
      ProxyStateDao proxyStateDao,
      RawAssetReader reader,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache cache,
      BlobStorageRegistry registry,
      RawAssetWriter writer,
      HttpRemoteFetcher fetcher,
      RawProxyService service) {
  }
}
