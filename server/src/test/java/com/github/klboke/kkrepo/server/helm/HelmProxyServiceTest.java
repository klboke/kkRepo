package com.github.klboke.kkrepo.server.helm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.github.klboke.kkrepo.protocol.helm.HelmAssetKind;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HelmProxyServiceTest {
  @Test
  void servesFreshIndexAndPackageFromCache() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.PROXY, 60);
    CachedAssetMetadata index = snapshot("index.yaml", Instant.now().plusSeconds(60), Map.of());
    CachedAssetMetadata chart = snapshot("demo-1.0.0.tgz", Instant.now().plusSeconds(60), Map.of());
    MavenResponse indexResponse = MavenResponse.noBody(200);
    MavenResponse chartResponse = MavenResponse.noBody(200);
    when(fixture.cache.find(eq(runtime.id()), anyString(), any()))
        .thenAnswer(invocation -> Optional.of(
            invocation.getArgument(1).equals("index.yaml") ? index : chart));
    when(fixture.reader.serveSnapshot(index, true, "index.yaml")).thenReturn(indexResponse);
    when(fixture.reader.serveSnapshot(chart, false, "demo-1.0.0.tgz")).thenReturn(chartResponse);

    assertSame(indexResponse, fixture.service.get(runtime, "index.yaml", true));
    assertEquals(
        index.lastUpdatedAt().plusSeconds(60L * 60),
        indexResponse.internalAttribute(HelmProxyService.INDEX_FRESH_UNTIL_ATTRIBUTE));
    assertSame(chartResponse, fixture.service.get(runtime, "demo-1.0.0.tgz", false));
    verify(fixture.negativeCache, never()).isNotFoundCached(eq(runtime), anyString());
  }

  @Test
  void groupIndexReadsTheCurrentDurableBindingInsteadOfAStaleMetadataSnapshot() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.PROXY, 60);
    Map<String, Object> attributes = Map.of(
        HelmProxyService.PROXY_CONFIGURATION_ATTRIBUTE,
        HelmProxyService.configurationFingerprint(runtime));
    AssetRecord asset = new AssetRecord(
        3L, runtime.id(), null, 4L, RepositoryFormat.HELM, "index.yaml", null,
        "index.yaml", "INDEX", "text/x-yaml", 8L, null, Instant.now(), attributes);
    AssetBlobRecord blob = new AssetBlobRecord(
        4L, 7L, "blob://bucket/current-index", null, "current-index", null,
        "sha1-current", "sha256-current", "md5-current", 8L, "text/x-yaml",
        "proxy", "upstream", Instant.EPOCH, Instant.EPOCH, Map.of());
    CachedAssetMetadata current = CachedAssetMetadata.of(asset, blob);
    MavenResponse expected = MavenResponse.noBody(200);
    when(fixture.assetDao.findAssetByPath(runtime.id(), "index.yaml"))
        .thenReturn(Optional.of(asset));
    when(fixture.assetDao.findBlobById(blob.id())).thenReturn(Optional.of(blob));
    when(fixture.reader.serveSnapshot(current, false, "index.yaml")).thenReturn(expected);

    assertSame(expected, fixture.service.getIndexForGroup(runtime, false));

    verify(fixture.cache, never()).find(eq(runtime.id()), eq("index.yaml"), any());
    verify(fixture.assetDao).findAssetByPath(runtime.id(), "index.yaml");
    verify(fixture.assetDao).findBlobById(blob.id());
  }

  @Test
  void honorsNegativeCacheAndBlockedUpstreamFallback() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.PROXY, 1);
    when(fixture.cache.find(eq(runtime.id()), eq("missing.tgz"), any()))
        .thenReturn(Optional.empty());
    when(fixture.negativeCache.isNotFoundCached(runtime, "missing.tgz")).thenReturn(true);
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> fixture.service.get(runtime, "missing.tgz", false));

    CachedAssetMetadata stale = snapshot("demo.tgz", Instant.EPOCH, Map.of());
    MavenResponse expected = MavenResponse.noBody(200);
    when(fixture.cache.find(eq(runtime.id()), eq("demo.tgz"), any()))
        .thenReturn(Optional.of(stale));
    when(fixture.proxyStateDao.isBlocked(eq(runtime.id()), any())).thenReturn(true);
    when(fixture.reader.serveSnapshot(stale, true, "demo.tgz")).thenReturn(expected);
    assertSame(expected, fixture.service.get(runtime, "demo.tgz", true));

    when(fixture.cache.find(eq(runtime.id()), eq("other.tgz"), any()))
        .thenReturn(Optional.empty());
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> fixture.service.get(runtime, "other.tgz", false));
  }

  @Test
  void cachesAndRewritesRemoteIndexForHead() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.PROXY, 1);
    when(fixture.cache.find(eq(runtime.id()), eq("index.yaml"), any()))
        .thenReturn(Optional.empty());
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    byte[] upstream = """
        apiVersion: v1
        entries:
          demo:
            - name: demo
              version: 1.0.0
              urls:
                - charts/original.tgz
        """.getBytes(StandardCharsets.UTF_8);
    respond(fixture.fetcher, new HttpRemoteFetcher.Result(
        200, Map.of("Content-Type", "text/x-yaml"), new ByteArrayInputStream(upstream)));
    HelmAssetWriter.Stored stored = stored("index.yaml", "text/x-yaml");
    when(fixture.writer.writeBytes(
        eq(runtime), eq(fixture.storage), eq(7L), eq("index.yaml"), any(byte[].class),
        eq("text/x-yaml"), eq(HelmAssetKind.INDEX), eq(null), any(), any(),
        eq("proxy"), isNull(), eq(false)))
        .thenReturn(stored);

    MavenResponse response = fixture.service.get(runtime, "index.yaml", true);

    assertEquals(200, response.status());
    assertEquals(stored.blob().size(), response.contentLength());
    verify(fixture.reader).beforeRead(
        stored.asset().id(), stored.blob().id(), stored.asset().repositoryId());
    verify(fixture.proxyStateDao).recordSuccess(eq(runtime.id()), any());
    verify(fixture.negativeCache).invalidate(runtime, "index.yaml");
  }

  @Test
  void usesRemoteUrlRecordedInCachedIndexForPackage() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.PROXY, 1);
    CachedAssetMetadata index = snapshot(
        "index.yaml", Instant.now(),
        Map.of("remoteUrls", Map.of(
            "demo-1.0.0.tgz", "https://cdn.example.test/charts/demo.tgz")));
    when(fixture.cache.find(eq(runtime.id()), anyString(), any()))
        .thenAnswer(invocation -> invocation.getArgument(1).equals("index.yaml")
            ? Optional.of(index)
            : Optional.empty());
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    AtomicReference<String> requestedUrl = new AtomicReference<>();
    doAnswer(invocation -> {
      HttpRemoteFetcher.Request request = invocation.getArgument(0);
      requestedUrl.set(request.url());
      @SuppressWarnings("unchecked")
      HttpRemoteFetcher.ResultHandler<MavenResponse> handler = invocation.getArgument(2);
      return handler.handle(new HttpRemoteFetcher.Result(
          200, Map.of("Content-Type", "application/gzip"),
          new ByteArrayInputStream(new byte[] {1, 2, 3})));
    }).when(fixture.fetcher).fetchWithBodyRetry(any(), eq("demo-1.0.0.tgz"), any());
    Path responseFile = Files.createTempFile("kkrepo-helm-proxy-response-", ".tgz");
    Files.write(responseFile, new byte[] {1, 2, 3, 4});
    HelmAssetWriter.Stored stored = stored(
        "demo-1.0.0.tgz", "application/gzip", responseFile);
    when(fixture.writer.write(
        eq(runtime), eq(fixture.storage), eq(7L), eq("demo-1.0.0.tgz"), any(),
        eq("application/gzip"), eq(HelmAssetKind.PACKAGE), eq(null), any(), any(),
        eq("proxy"), isNull(), eq(true)))
        .thenReturn(stored);

    MavenResponse response = fixture.service.get(runtime, "demo-1.0.0.tgz", false);
    assertEquals(200, response.status());
    assertEquals("sha256", response.internalAttribute(HelmAssetReader.SHA256_ATTRIBUTE));
    try (var body = response.body()) {
      assertEquals(4, body.readAllBytes().length);
    }
    assertEquals("https://cdn.example.test/charts/demo.tgz", requestedUrl.get());
  }

  @Test
  void derivesProvenanceFromALegacyChartOnlyRemoteMapping() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.PROXY, 1);
    CachedAssetMetadata index = snapshot(
        "index.yaml", Instant.now(),
        Map.of("remoteUrls", Map.of(
            "demo-1.0.0.tgz", "https://cdn.example.test/charts/demo.tgz?download=1#release")));
    when(fixture.cache.find(eq(runtime.id()), anyString(), any()))
        .thenAnswer(invocation -> invocation.getArgument(1).equals("index.yaml")
            ? Optional.of(index)
            : Optional.empty());
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    AtomicReference<String> requestedUrl = new AtomicReference<>();
    doAnswer(invocation -> {
      HttpRemoteFetcher.Request request = invocation.getArgument(0);
      requestedUrl.set(request.url());
      @SuppressWarnings("unchecked")
      HttpRemoteFetcher.ResultHandler<MavenResponse> handler = invocation.getArgument(2);
      return handler.handle(new HttpRemoteFetcher.Result(
          200, Map.of("Content-Type", "application/octet-stream"),
          new ByteArrayInputStream(new byte[] {1, 2, 3})));
    }).when(fixture.fetcher).fetchWithBodyRetry(any(), eq("demo-1.0.0.tgz.prov"), any());
    HelmAssetWriter.Stored stored = stored("demo-1.0.0.tgz.prov", "application/octet-stream");
    when(fixture.writer.write(
        eq(runtime), eq(fixture.storage), eq(7L), eq("demo-1.0.0.tgz.prov"), any(),
        eq("application/octet-stream"), eq(HelmAssetKind.PROVENANCE), eq(null), any(), any(),
        eq("proxy"), isNull(), eq(false)))
        .thenReturn(stored);

    assertEquals(200, fixture.service.get(runtime, "demo-1.0.0.tgz.prov", true).status());
    assertEquals(
        "https://cdn.example.test/charts/demo.tgz.prov?download=1#release",
        requestedUrl.get());
  }

  @Test
  void translatesPackageCacheWriteFailuresToBadUpstream() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.PROXY, 1);
    CachedAssetMetadata index = snapshot(
        "index.yaml",
        Instant.now(),
        Map.of("remoteUrls", Map.of(
            "demo-1.0.0.tgz", "https://cdn.example.test/demo-1.0.0.tgz")));
    when(fixture.cache.find(eq(runtime.id()), anyString(), any()))
        .thenAnswer(invocation -> invocation.getArgument(1).equals("index.yaml")
            ? Optional.of(index)
            : Optional.empty());
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    respond(fixture.fetcher, new HttpRemoteFetcher.Result(
        200,
        Map.of("Content-Type", "application/gzip"),
        new ByteArrayInputStream(new byte[] {1, 2, 3})));
    when(fixture.writer.write(
        eq(runtime), eq(fixture.storage), eq(7L), eq("demo-1.0.0.tgz"), any(),
        eq("application/gzip"), eq(HelmAssetKind.PACKAGE), isNull(), any(), any(),
        eq("proxy"), isNull(), eq(true)))
        .thenThrow(new IllegalStateException("object storage unavailable"));

    MavenExceptions.BadUpstreamException failure = assertThrows(
        MavenExceptions.BadUpstreamException.class,
        () -> fixture.service.get(runtime, "demo-1.0.0.tgz", false));

    assertTrue(failure.getMessage().contains("Failed caching upstream Helm content"));
    verify(fixture.proxyStateDao).recordFailure(
        eq(runtime.id()), eq(30L), anyString(), any());
  }

  @Test
  void refetchesAStillFreshIndexWhenTheProxyConfigurationChanges() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime oldRuntime = runtime(
        RepositoryType.PROXY, 60, "https://old.example.test/");
    RepositoryRuntime runtime = runtime(
        RepositoryType.PROXY, 60, "https://new.example.test/");
    CachedAssetMetadata oldIndex = snapshot(
        "index.yaml",
        Instant.now(),
        Map.of(
            "remoteUrls", Map.of(),
            HelmProxyService.PROXY_CONFIGURATION_ATTRIBUTE,
            HelmProxyService.configurationFingerprint(oldRuntime)),
        false);
    when(fixture.cache.find(eq(runtime.id()), eq("index.yaml"), any()))
        .thenReturn(Optional.of(oldIndex));
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    respond(fixture.fetcher, new HttpRemoteFetcher.Result(
        200,
        Map.of("Content-Type", "text/x-yaml"),
        new ByteArrayInputStream("apiVersion: v1\nentries: {}\n".getBytes(StandardCharsets.UTF_8))));
    HelmAssetWriter.Stored stored = stored("index.yaml", "text/x-yaml");
    when(fixture.writer.writeBytes(
        eq(runtime), eq(fixture.storage), eq(7L), eq("index.yaml"), any(byte[].class),
        eq("text/x-yaml"), eq(HelmAssetKind.INDEX), eq(null), any(), any(),
        eq("proxy"), isNull(), eq(false)))
        .thenReturn(stored);

    MavenResponse response = fixture.service.get(runtime, "index.yaml", true);

    assertEquals(200, response.status());
    verify(fixture.reader, never()).serveSnapshot(oldIndex, true, "index.yaml");
  }

  @Test
  void atomicallyBindsLegacyIndexAndPackageCachesToTheUpgradeConfiguration() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.PROXY, 60);
    Instant updatedAt = Instant.now();
    Map<String, Object> legacyIndexAttributes = Map.of(
        "remoteUrls", Map.of(
            "demo-1.0.0.tgz", "https://charts.example.test/demo-1.0.0.tgz"));
    Map<String, Object> legacyPackageAttributes = Map.of(
        "remoteUrl", "https://charts.example.test/demo-1.0.0.tgz");
    CachedAssetMetadata index = snapshot(
        "index.yaml", updatedAt, legacyIndexAttributes, false);
    CachedAssetMetadata chart = snapshot(
        "demo-1.0.0.tgz", updatedAt, legacyPackageAttributes, false);
    String fingerprint = HelmProxyService.configurationFingerprint(runtime);
    CachedAssetMetadata boundIndex = snapshot(
        "index.yaml", updatedAt,
        Map.of(
            "remoteUrls", legacyIndexAttributes.get("remoteUrls"),
            HelmProxyService.PROXY_CONFIGURATION_ATTRIBUTE, fingerprint),
        false);
    CachedAssetMetadata boundChart = snapshot(
        "demo-1.0.0.tgz", updatedAt,
        Map.of(
            "remoteUrl", legacyPackageAttributes.get("remoteUrl"),
            HelmProxyService.PROXY_CONFIGURATION_ATTRIBUTE, fingerprint),
        false);
    MavenResponse indexResponse = MavenResponse.noBody(200);
    MavenResponse chartResponse = MavenResponse.noBody(200);
    Map<String, Integer> cacheLoads = new java.util.HashMap<>();
    when(fixture.cache.find(eq(runtime.id()), anyString(), any()))
        .thenAnswer(invocation -> {
          String path = invocation.getArgument(1);
          int load = cacheLoads.merge(path, 1, Integer::sum);
          if (path.equals("index.yaml")) return Optional.of(load == 1 ? index : boundIndex);
          return Optional.of(load == 1 ? chart : boundChart);
        });
    when(fixture.assetDao.bindLegacyHelmProxyCacheConfiguration(
        eq(index.assetId()), eq(runtime.id()),
        eq(HelmProxyService.PROXY_CONFIGURATION_ATTRIBUTE), anyString()))
        .thenReturn(1);
    when(fixture.reader.serveSnapshot(any(), eq(true), eq("index.yaml")))
        .thenReturn(indexResponse);
    when(fixture.reader.serveSnapshot(any(), eq(false), eq("demo-1.0.0.tgz")))
        .thenReturn(chartResponse);

    assertSame(indexResponse, fixture.service.get(runtime, "index.yaml", true));
    assertSame(chartResponse, fixture.service.get(runtime, "demo-1.0.0.tgz", false));

    org.mockito.ArgumentCaptor<String> fingerprints =
        org.mockito.ArgumentCaptor.forClass(String.class);
    verify(fixture.assetDao, org.mockito.Mockito.times(2))
        .bindLegacyHelmProxyCacheConfiguration(
        eq(index.assetId()),
        eq(runtime.id()),
        eq(HelmProxyService.PROXY_CONFIGURATION_ATTRIBUTE),
        fingerprints.capture());
    assertTrue(fingerprints.getAllValues().stream().allMatch(
        HelmProxyService.configurationFingerprint(runtime)::equals));
    verify(fixture.cache).evict(runtime.id(), "index.yaml");
    verify(fixture.cache).evict(runtime.id(), "demo-1.0.0.tgz");
  }

  @Test
  void rejectsLegacyCacheWhenTheDurableUpgradeFenceDoesNotBindIt() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(
        RepositoryType.PROXY, 60, "https://new.example.test/");
    CachedAssetMetadata legacy = snapshot(
        "index.yaml",
        Instant.parse("2026-08-30T00:00:00Z"),
        Map.of("remoteUrls", Map.of(
            "demo-1.0.0.tgz", "https://old.example.test/demo-1.0.0.tgz")),
        false);
    when(fixture.cache.find(eq(runtime.id()), eq("index.yaml"), any()))
        .thenReturn(Optional.of(legacy));
    when(fixture.assetDao.bindLegacyHelmProxyCacheConfiguration(
        eq(legacy.assetId()), eq(runtime.id()),
        eq(HelmProxyService.PROXY_CONFIGURATION_ATTRIBUTE), anyString()))
        .thenReturn(0);
    when(fixture.proxyStateDao.isBlocked(eq(runtime.id()), any())).thenReturn(true);

    assertThrows(
        MavenExceptions.BadUpstreamException.class,
        () -> fixture.service.get(runtime, "index.yaml", false));

    verify(fixture.reader, never()).serveSnapshot(legacy, false, "index.yaml");
    verify(fixture.assetDao).bindLegacyHelmProxyCacheConfiguration(
        eq(legacy.assetId()), eq(runtime.id()),
        eq(HelmProxyService.PROXY_CONFIGURATION_ATTRIBUTE), anyString());
    verify(fixture.cache).evictEntry(runtime.id(), "index.yaml");
    verify(fixture.cache, never()).evict(runtime.id(), "index.yaml");
  }

  @Test
  void handles304AndRemoteNotFound() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.PROXY, 1);
    CachedAssetMetadata stale = snapshot("index.yaml", Instant.EPOCH, Map.of());
    MavenResponse expected = MavenResponse.noBody(200);
    when(fixture.cache.find(eq(runtime.id()), eq("index.yaml"), any()))
        .thenReturn(Optional.of(stale));
    when(fixture.reader.serveSnapshot(stale, false, "index.yaml")).thenReturn(expected);
    respond(fixture.fetcher, new HttpRemoteFetcher.Result(
        304, Map.of(), new ByteArrayInputStream(new byte[0])));

    MavenResponse response = fixture.service.get(runtime, "index.yaml", false);
    assertSame(expected, response);
    org.mockito.ArgumentCaptor<Instant> verifiedAt =
        org.mockito.ArgumentCaptor.forClass(Instant.class);
    verify(fixture.assetDao).touchAssetLastUpdated(eq(stale.assetId()), verifiedAt.capture());
    assertEquals(
        verifiedAt.getValue().plusSeconds(60),
        response.internalAttribute(HelmProxyService.INDEX_FRESH_UNTIL_ATTRIBUTE));
    verify(fixture.cache).touchVerified(eq(runtime.id()), eq("index.yaml"), any());

    Fixture missing = fixture();
    when(missing.cache.find(eq(runtime.id()), eq("index.yaml"), any()))
        .thenReturn(Optional.empty());
    respond(missing.fetcher, new HttpRemoteFetcher.Result(
        404, Map.of(), new ByteArrayInputStream(new byte[0])));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> missing.service.get(runtime, "index.yaml", false));
    verify(missing.negativeCache).rememberNotFound(runtime, "index.yaml");
  }

  @Test
  void marksStaleIndexFallbackAsNonAuthoritativeWhenRefreshFails() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.PROXY, 1);
    CachedAssetMetadata stale = snapshot("index.yaml", Instant.EPOCH, Map.of());
    MavenResponse expected = MavenResponse.noBody(200);
    when(fixture.cache.find(eq(runtime.id()), eq("index.yaml"), any()))
        .thenReturn(Optional.of(stale));
    when(fixture.reader.serveSnapshot(stale, false, "index.yaml")).thenReturn(expected);
    respond(fixture.fetcher, new HttpRemoteFetcher.Result(
        503, Map.of(), new ByteArrayInputStream(new byte[0])));

    MavenResponse response = fixture.service.get(runtime, "index.yaml", false);

    assertSame(expected, response);
    assertEquals(Boolean.FALSE,
        response.internalAttribute(HelmProxyService.INDEX_AUTHORITATIVE_ATTRIBUTE));
    verify(fixture.proxyStateDao).recordFailure(eq(runtime.id()), eq(30L), anyString(), any());
  }

  @Test
  void marksBlockedAndRemoteNotFoundIndexFallbacksAsNonAuthoritative() throws Exception {
    RepositoryRuntime runtime = runtime(RepositoryType.PROXY, 1);

    Fixture blocked = fixture();
    CachedAssetMetadata blockedStale = snapshot("index.yaml", Instant.EPOCH, Map.of());
    MavenResponse blockedResponse = MavenResponse.noBody(200);
    when(blocked.cache.find(eq(runtime.id()), eq("index.yaml"), any()))
        .thenReturn(Optional.of(blockedStale));
    when(blocked.proxyStateDao.isBlocked(eq(runtime.id()), any())).thenReturn(true);
    when(blocked.reader.serveSnapshot(blockedStale, true, "index.yaml"))
        .thenReturn(blockedResponse);

    assertEquals(Boolean.FALSE,
        blocked.service.get(runtime, "index.yaml", true)
            .internalAttribute(HelmProxyService.INDEX_AUTHORITATIVE_ATTRIBUTE));

    Fixture missing = fixture();
    CachedAssetMetadata missingStale = snapshot("index.yaml", Instant.EPOCH, Map.of());
    MavenResponse missingResponse = MavenResponse.noBody(200);
    when(missing.cache.find(eq(runtime.id()), eq("index.yaml"), any()))
        .thenReturn(Optional.of(missingStale));
    when(missing.reader.serveSnapshot(missingStale, false, "index.yaml"))
        .thenReturn(missingResponse);
    respond(missing.fetcher, new HttpRemoteFetcher.Result(
        404, Map.of(), new ByteArrayInputStream(new byte[0])));

    assertEquals(Boolean.FALSE,
        missing.service.get(runtime, "index.yaml", false)
            .internalAttribute(HelmProxyService.INDEX_AUTHORITATIVE_ATTRIBUTE));
  }

  @Test
  void marksMalformedSchemaInvalidAndUnreadableIndexFallbacksAsNonAuthoritative()
      throws Exception {
    RepositoryRuntime runtime = runtime(RepositoryType.PROXY, 1);

    Fixture invalid = fixture();
    CachedAssetMetadata invalidStale = snapshot("index.yaml", Instant.EPOCH, Map.of());
    MavenResponse invalidResponse = MavenResponse.noBody(200);
    when(invalid.cache.find(eq(runtime.id()), eq("index.yaml"), any()))
        .thenReturn(Optional.of(invalidStale));
    when(invalid.reader.serveSnapshot(invalidStale, false, "index.yaml"))
        .thenReturn(invalidResponse);
    respond(invalid.fetcher, new HttpRemoteFetcher.Result(
        200, Map.of(), new ByteArrayInputStream("entries: [".getBytes(StandardCharsets.UTF_8))));

    assertEquals(Boolean.FALSE,
        invalid.service.get(runtime, "index.yaml", false)
            .internalAttribute(HelmProxyService.INDEX_AUTHORITATIVE_ATTRIBUTE));

    Fixture schemaInvalid = fixture();
    CachedAssetMetadata schemaInvalidStale = snapshot("index.yaml", Instant.EPOCH, Map.of());
    MavenResponse schemaInvalidResponse = MavenResponse.noBody(200);
    when(schemaInvalid.cache.find(eq(runtime.id()), eq("index.yaml"), any()))
        .thenReturn(Optional.of(schemaInvalidStale));
    when(schemaInvalid.reader.serveSnapshot(schemaInvalidStale, false, "index.yaml"))
        .thenReturn(schemaInvalidResponse);
    respond(schemaInvalid.fetcher, new HttpRemoteFetcher.Result(
        200,
        Map.of(),
        new ByteArrayInputStream(
            "entries: {demo: error}\n".getBytes(StandardCharsets.UTF_8))));

    assertEquals(Boolean.FALSE,
        schemaInvalid.service.get(runtime, "index.yaml", false)
            .internalAttribute(HelmProxyService.INDEX_AUTHORITATIVE_ATTRIBUTE));
    verify(schemaInvalid.writer, never()).writeBytes(
        any(), any(), anyLong(), anyString(), any(), anyString(), any(), any(), any(), any(),
        anyString(), any(), eq(false));

    Fixture unreadable = fixture();
    CachedAssetMetadata unreadableStale = snapshot("index.yaml", Instant.EPOCH, Map.of());
    MavenResponse unreadableResponse = MavenResponse.noBody(200);
    when(unreadable.cache.find(eq(runtime.id()), eq("index.yaml"), any()))
        .thenReturn(Optional.of(unreadableStale));
    when(unreadable.reader.serveSnapshot(unreadableStale, false, "index.yaml"))
        .thenReturn(unreadableResponse);
    doAnswer(invocation -> {
      throw new IOException("unreadable index");
    }).when(unreadable.fetcher).fetchWithBodyRetry(any(), eq("index.yaml"), any());

    assertEquals(Boolean.FALSE,
        unreadable.service.get(runtime, "index.yaml", false)
            .internalAttribute(HelmProxyService.INDEX_AUTHORITATIVE_ATTRIBUTE));
  }

  @Test
  void isolatesIndexCacheWriteFailuresBehindAStaleNonAuthoritativeFallback()
      throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.PROXY, 1);
    CachedAssetMetadata stale = snapshot("index.yaml", Instant.EPOCH, Map.of());
    MavenResponse expected = MavenResponse.noBody(200);
    when(fixture.cache.find(eq(runtime.id()), eq("index.yaml"), any()))
        .thenReturn(Optional.of(stale));
    when(fixture.reader.serveSnapshot(stale, false, "index.yaml")).thenReturn(expected);
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    respond(fixture.fetcher, new HttpRemoteFetcher.Result(
        200,
        Map.of("Content-Type", "text/x-yaml"),
        new ByteArrayInputStream(
            "apiVersion: v1\nentries: {}\n".getBytes(StandardCharsets.UTF_8))));
    when(fixture.writer.writeBytes(
        eq(runtime), eq(fixture.storage), eq(7L), eq("index.yaml"), any(byte[].class),
        eq("text/x-yaml"), eq(HelmAssetKind.INDEX), isNull(), any(), any(),
        eq("proxy"), isNull(), eq(true)))
        .thenThrow(new IllegalStateException("object storage unavailable"));

    MavenResponse response = fixture.service.get(runtime, "index.yaml", false);

    assertSame(expected, response);
    assertEquals(
        Boolean.FALSE,
        response.internalAttribute(HelmProxyService.INDEX_AUTHORITATIVE_ATTRIBUTE));
    verify(fixture.proxyStateDao).recordFailure(
        eq(runtime.id()), eq(30L), anyString(), any());
    verify(fixture.proxyStateDao, never()).recordSuccess(eq(runtime.id()), any());
  }

  @Test
  void rejectsUnsupportedRepositoryAndAssetKinds() throws Exception {
    Fixture fixture = fixture();
    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> fixture.service.get(runtime(RepositoryType.HOSTED, 1), "index.yaml", false));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> fixture.service.get(runtime(RepositoryType.PROXY, 1), "README.md", false));
    respond(fixture.fetcher, new HttpRemoteFetcher.Result(
        200, Map.of("Content-Type", "text/x-yaml"),
        new ByteArrayInputStream("apiVersion: v1\nentries: {}\n".getBytes(StandardCharsets.UTF_8))));
    assertThrows(IllegalStateException.class,
        () -> fixture.service.get(
            new RepositoryRuntime(
                10L, "helm", RepositoryFormat.HELM, RepositoryType.PROXY, "helm", true, null,
                null, null, null, true, "https://charts.example.test/",
                1, 1, true, null, List.of()),
            "index.yaml", false));
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
    HelmAssetWriter writer = mock(HelmAssetWriter.class);
    HelmAssetReader reader = mock(HelmAssetReader.class);
    ProxyStateDao proxyStateDao = mock(ProxyStateDao.class);
    HttpRemoteFetcher fetcher = mock(HttpRemoteFetcher.class);
    ProxyNegativeCache negativeCache = mock(ProxyNegativeCache.class);
    AssetMetadataCache cache = mock(AssetMetadataCache.class);
    BlobStorage storage = mock(BlobStorage.class);
    return new Fixture(
        assetDao, registry, writer, reader, proxyStateDao, fetcher, negativeCache,
        cache, storage,
        new HelmProxyService(
            assetDao, registry, writer, reader, proxyStateDao, fetcher,
            negativeCache, cache));
  }

  private static RepositoryRuntime runtime(RepositoryType type, int maxAgeMinutes) {
    return runtime(type, maxAgeMinutes, "https://charts.example.test/");
  }

  private static RepositoryRuntime runtime(
      RepositoryType type, int maxAgeMinutes, String remoteUrl) {
    return new RepositoryRuntime(
        10L, "helm", RepositoryFormat.HELM, type, "helm", true, 7L,
        null, null, null, true, remoteUrl,
        maxAgeMinutes, maxAgeMinutes, true, null, List.of());
  }

  private static CachedAssetMetadata snapshot(
      String path, Instant updatedAt, Map<String, Object> attributes) {
    return snapshot(path, updatedAt, attributes, true);
  }

  private static CachedAssetMetadata snapshot(
      String path, Instant updatedAt, Map<String, Object> attributes, boolean addConfiguration) {
    Map<String, Object> storedAttributes = new java.util.LinkedHashMap<>();
    if (attributes != null) storedAttributes.putAll(attributes);
    if (addConfiguration) {
      storedAttributes.putIfAbsent(
          HelmProxyService.PROXY_CONFIGURATION_ATTRIBUTE,
          HelmProxyService.configurationFingerprint(runtime(RepositoryType.PROXY, 1)));
    }
    AssetRecord asset = new AssetRecord(
        1L, 10L, null, 2L, RepositoryFormat.HELM, path, null,
        path, path.equals("index.yaml") ? "INDEX" : "PACKAGE",
        path.equals("index.yaml") ? "text/x-yaml" : "application/gzip",
        4L, null, updatedAt, storedAttributes);
    return CachedAssetMetadata.of(asset, blob());
  }

  private static HelmAssetWriter.Stored stored(String path, String contentType) {
    return stored(path, contentType, null);
  }

  private static HelmAssetWriter.Stored stored(
      String path, String contentType, Path responseFile) {
    AssetRecord asset = new AssetRecord(
        1L, 10L, null, 2L, RepositoryFormat.HELM, path, null,
        path, path.equals("index.yaml") ? "INDEX" : "PACKAGE", contentType,
        4L, null, Instant.now(), Map.of());
    return new HelmAssetWriter.Stored(
        asset, blob(), new HelmAssetWriter.Digests("md5", "sha1", "sha256", "sha512", 4),
        true, responseFile);
  }

  private static AssetBlobRecord blob() {
    return new AssetBlobRecord(
        2L, 7L, "blob://bucket/object", null, "object", null,
        "sha1", "sha256", "md5", 4, "application/gzip", "proxy", "upstream",
        Instant.EPOCH, Instant.EPOCH, Map.of());
  }

  private record Fixture(
      AssetDao assetDao,
      BlobStorageRegistry registry,
      HelmAssetWriter writer,
      HelmAssetReader reader,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache cache,
      BlobStorage storage,
      HelmProxyService service) {
  }
}
