package com.github.klboke.kkrepo.server.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.NpmReleaseIndexDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
import com.github.klboke.kkrepo.protocol.npm.NpmMinimumReleaseAge;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
  void minimumReleaseAgeFiltersPackumentRewindsLatestAndRequestsFullMetadata() throws Exception {
    Instant now = Instant.parse("2026-07-19T12:00:00Z");
    Fixture fixture = fixture(Clock.fixed(now, ZoneOffset.UTC));
    RepositoryRuntime runtime = runtime(1, 7L, 60);
    when(fixture.cache.find(eq(10L), eq("demo"), any())).thenReturn(Optional.empty());
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    respond(fixture.fetcher, new HttpRemoteFetcher.Result(
        200, Map.of("Content-Type", "application/json"),
        new ByteArrayInputStream("""
            {"name":"demo","dist-tags":{"latest":"1.1.0"},
             "time":{"1.0.0":"2026-07-18T12:00:00Z","1.1.0":"2026-07-19T11:30:01Z"},
             "versions":{
               "1.0.0":{"name":"demo","version":"1.0.0","dist":{"tarball":"https://registry.npmjs.org/demo/-/demo-1.0.0.tgz"}},
               "1.1.0":{"name":"demo","version":"1.1.0","dist":{"tarball":"https://registry.npmjs.org/demo/-/demo-1.1.0.tgz"}}
             }}
            """.getBytes(StandardCharsets.UTF_8))));
    when(fixture.writer.writePackageRoot(
        eq(runtime), eq(fixture.storage), eq(7L), eq(PACKAGE), any(),
        eq("proxy"), eq(runtime.proxyRemoteUrl()), any(),
        any(NpmMinimumReleaseAge.ReleaseIndex.class)))
        .thenAnswer(invocation -> stored(
            "demo", "package-root", "application/json", invocation.getArgument(7)));

    MavenResponse response = fixture.service.getPackage(runtime, PACKAGE, "base", false);
    Map<?, ?> body = new ObjectMapper().readValue(response.body(), Map.class);
    Map<?, ?> versions = (Map<?, ?>) body.get("versions");
    Map<?, ?> tags = (Map<?, ?>) body.get("dist-tags");

    assertTrue(versions.containsKey("1.0.0"));
    assertFalse(versions.containsKey("1.1.0"));
    assertEquals("1.0.0", tags.get("latest"));
    ArgumentCaptor<HttpRemoteFetcher.Request> request =
        ArgumentCaptor.forClass(HttpRemoteFetcher.Request.class);
    verify(fixture.fetcher).fetchWithBodyRetry(request.capture(), eq("demo"), any());
    assertEquals("application/json", request.getValue().accept());
  }

  @Test
  void minimumReleaseAgeBlocksDirectCachedTarballRequests() {
    Instant now = Instant.parse("2026-07-19T12:00:00Z");
    Fixture fixture = fixture(Clock.fixed(now, ZoneOffset.UTC));
    RepositoryRuntime runtime = runtime(60, 7L, 60);
    CachedAssetMetadata packageSnapshot = snapshot("demo", now, "package-root", Map.of());
    when(fixture.cache.find(eq(10L), eq("demo"), any())).thenReturn(Optional.of(packageSnapshot));
    when(fixture.hosted.packageRoot(packageSnapshot)).thenReturn(Optional.of(packument(
        "1.0.0", "2026-07-19T11:30:01Z")));
    NpmReleaseIndexDao.TarballPolicy indexed = new NpmReleaseIndexDao.TarballPolicy(
        new NpmReleaseIndexDao.Status(1L, 2L, true, 1, now),
        false,
        List.of(new NpmReleaseIndexDao.Release(
            0, "1.0.0", Instant.parse("2026-07-19T11:30:01Z"), null, TARBALL)));
    when(fixture.releaseIndexDao.findTarballPolicy(1L, 2L, TARBALL, null, null))
        .thenReturn(Optional.empty(), Optional.of(indexed), Optional.of(indexed));

    NpmExceptions.ReleaseAgeDenied denied = null;
    for (int attempt = 0; attempt < 2; attempt++) {
      denied = assertThrows(
          NpmExceptions.ReleaseAgeDenied.class,
          () -> fixture.service.getTarball(runtime, PACKAGE, TARBALL, false));
    }

    assertTrue(denied.getMessage().contains("until 2026-07-19T12:30:01Z"));
    verify(fixture.hosted).packageRoot(packageSnapshot);
    verify(fixture.hosted, never()).getTarball(runtime, PACKAGE, TARBALL, false);
  }

  @Test
  void minimumReleaseAgeAllowsRepeatedCachedTarballWithoutReloadingPackument() throws Exception {
    Instant now = Instant.parse("2026-07-19T12:00:00Z");
    Fixture fixture = fixture(Clock.fixed(now, ZoneOffset.UTC));
    RepositoryRuntime runtime = runtime(60, 7L, 60);
    CachedAssetMetadata packageSnapshot = snapshot("demo", now, "package-root", Map.of());
    CachedAssetMetadata tarballSnapshot = snapshot(TARBALL_PATH, now, "tarball", Map.of());
    when(fixture.cache.find(eq(10L), anyString(), any())).thenAnswer(invocation ->
        Optional.of("demo".equals(invocation.getArgument(1))
            ? packageSnapshot
            : tarballSnapshot));
    when(fixture.hosted.packageRoot(packageSnapshot)).thenReturn(Optional.of(packument(
        "1.0.0", "2026-07-19T10:00:00Z")));
    NpmReleaseIndexDao.TarballPolicy indexed = new NpmReleaseIndexDao.TarballPolicy(
        new NpmReleaseIndexDao.Status(1L, 2L, true, 1, now),
        false,
        List.of(new NpmReleaseIndexDao.Release(
            0, "1.0.0", Instant.parse("2026-07-19T10:00:00Z"), null, TARBALL)));
    when(fixture.releaseIndexDao.findTarballPolicy(1L, 2L, TARBALL, null, null))
        .thenReturn(Optional.empty(), Optional.of(indexed), Optional.of(indexed));
    MavenResponse expected = MavenResponse.noBody(200);
    when(fixture.hosted.getTarball(runtime, PACKAGE, TARBALL, false)).thenReturn(expected);

    assertSame(expected, fixture.service.getTarball(runtime, PACKAGE, TARBALL, false));
    assertSame(expected, fixture.service.getTarball(runtime, PACKAGE, TARBALL, false));

    verify(fixture.hosted).packageRoot(packageSnapshot);
    verify(fixture.fetcher, never()).fetchWithBodyRetry(any(), anyString(), any());
  }

  @Test
  void minimumReleaseAgeAllowsSharedTarballWhenAnyIndexedVersionIsEligible() throws Exception {
    Instant now = Instant.parse("2026-07-19T12:00:00Z");
    Fixture fixture = fixture(Clock.fixed(now, ZoneOffset.UTC));
    RepositoryRuntime runtime = runtime(60, 7L, 60);
    CachedAssetMetadata packageSnapshot = snapshot(
        "demo", now, "package-root", Map.of("npmFullMetadata", "true"));
    CachedAssetMetadata tarballSnapshot = snapshot(TARBALL_PATH, now, "tarball", Map.of());
    when(fixture.cache.find(eq(10L), anyString(), any())).thenAnswer(invocation ->
        Optional.of("demo".equals(invocation.getArgument(1))
            ? packageSnapshot
            : tarballSnapshot));
    NpmReleaseIndexDao.TarballPolicy indexed = new NpmReleaseIndexDao.TarballPolicy(
        new NpmReleaseIndexDao.Status(1L, 2L, true, 2, now),
        false,
        List.of(
            new NpmReleaseIndexDao.Release(
                0, "1.0.0", Instant.parse("2026-07-19T10:00:00Z"), null, TARBALL),
            new NpmReleaseIndexDao.Release(
                1, "1.1.0", Instant.parse("2026-07-19T11:30:01Z"), null, TARBALL)));
    when(fixture.releaseIndexDao.findTarballPolicy(1L, 2L, TARBALL, null, null))
        .thenReturn(Optional.of(indexed));
    MavenResponse expected = MavenResponse.noBody(200);
    when(fixture.hosted.getTarball(runtime, PACKAGE, TARBALL, false)).thenReturn(expected);

    assertSame(expected, fixture.service.getTarball(runtime, PACKAGE, TARBALL, false));

    verify(fixture.hosted, never()).packageRoot(any(CachedAssetMetadata.class));
    verify(fixture.fetcher, never()).fetchWithBodyRetry(any(), anyString(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void minimumReleaseAgeAllowsSharedTarballOnPackumentFallback() throws Exception {
    Instant now = Instant.parse("2026-07-19T12:00:00Z");
    Fixture fixture = fixtureWithoutReleaseIndex(Clock.fixed(now, ZoneOffset.UTC));
    RepositoryRuntime runtime = runtime(60, 7L, 60);
    CachedAssetMetadata packageSnapshot = snapshot(
        "demo", now, "package-root", Map.of("npmFullMetadata", "true"));
    CachedAssetMetadata tarballSnapshot = snapshot(TARBALL_PATH, now, "tarball", Map.of());
    when(fixture.cache.find(eq(10L), anyString(), any())).thenAnswer(invocation ->
        Optional.of("demo".equals(invocation.getArgument(1))
            ? packageSnapshot
            : tarballSnapshot));
    Map<String, Object> root = new ObjectMapper().readValue("""
        {"name":"demo","dist-tags":{"latest":"1.1.0"},
         "time":{"1.0.0":"2026-07-19T10:00:00Z","1.1.0":"2026-07-19T11:30:01Z"},
         "versions":{
           "1.0.0":{"name":"demo","version":"1.0.0","dist":{"tarball":"https://registry.npmjs.org/demo/-/demo-1.0.0.tgz"}},
           "1.1.0":{"name":"demo","version":"1.1.0","dist":{"tarball":"https://registry.npmjs.org/demo/-/demo-1.0.0.tgz"}}
         }}
        """, Map.class);
    when(fixture.hosted.packageRoot(packageSnapshot)).thenReturn(Optional.of(root));
    MavenResponse expected = MavenResponse.noBody(200);
    when(fixture.hosted.getTarball(runtime, PACKAGE, TARBALL, false)).thenReturn(expected);

    assertSame(expected, fixture.service.getTarball(runtime, PACKAGE, TARBALL, false));

    verify(fixture.fetcher, never()).fetchWithBodyRetry(any(), anyString(), any());
  }

  @Test
  void minimumReleaseAgeUsesTargetedIndexWithoutLoadingPackumentBlob() throws Exception {
    Instant now = Instant.parse("2026-07-19T12:00:00Z");
    Instant lastVerified = Instant.parse("2026-07-19T11:59:00Z");
    Fixture fixture = fixture(Clock.fixed(now, ZoneOffset.UTC));
    RepositoryRuntime runtime = runtime(60, 7L, 60);
    CachedAssetMetadata packageSnapshot = snapshot(
        "demo", lastVerified, "package-root", Map.of("npmFullMetadata", "true"));
    CachedAssetMetadata tarballSnapshot = snapshot(TARBALL_PATH, now, "tarball", Map.of());
    when(fixture.cache.find(eq(10L), anyString(), any())).thenAnswer(invocation ->
        Optional.of("demo".equals(invocation.getArgument(1))
            ? packageSnapshot
            : tarballSnapshot));
    NpmReleaseIndexDao.Status status =
        new NpmReleaseIndexDao.Status(1L, 2L, true, 1, lastVerified);
    List<NpmReleaseIndexDao.Release> releases = List.of(new NpmReleaseIndexDao.Release(
        0, "1.0.0", Instant.parse("2026-07-19T10:00:00Z"), null, TARBALL));
    when(fixture.releaseIndexDao.findTarballPolicy(
        1L, 2L, TARBALL,
        Instant.parse("2026-07-19T10:59:00Z"),
        Instant.parse("2026-07-19T11:00:00Z")))
        .thenReturn(Optional.of(new NpmReleaseIndexDao.TarballPolicy(
            status, false, releases)));
    MavenResponse expected = MavenResponse.noBody(200);
    when(fixture.hosted.getTarball(runtime, PACKAGE, TARBALL, false)).thenReturn(expected);

    assertSame(expected, fixture.service.getTarball(runtime, PACKAGE, TARBALL, false));

    verify(fixture.releaseIndexDao).findTarballPolicy(
        1L, 2L, TARBALL,
        Instant.parse("2026-07-19T10:59:00Z"),
        Instant.parse("2026-07-19T11:00:00Z"));
    verify(fixture.releaseIndexDao, never()).findStatus(eq(1L), eq(2L));
    verify(fixture.releaseIndexDao, never()).hasMaturityBoundary(
        eq(1L), eq(2L), any(), any());
    verify(fixture.releaseIndexDao, never()).findByTarball(eq(1L), eq(2L), anyString());
    verify(fixture.releaseIndexDao, never()).findSnapshot(eq(1L), eq(2L));
    verify(fixture.hosted, never()).packageRoot(any(CachedAssetMetadata.class));
    verify(fixture.fetcher, never()).fetchWithBodyRetry(any(), anyString(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void minimumReleaseAgeAlsoFiltersDirectDistTagEndpoint() throws Exception {
    Instant now = Instant.parse("2026-07-19T12:00:00Z");
    Fixture fixture = fixture(Clock.fixed(now, ZoneOffset.UTC));
    RepositoryRuntime runtime = runtime(60, 7L, 60);
    CachedAssetMetadata packageSnapshot = snapshot("demo", now, "package-root", Map.of());
    Map<String, Object> raw = new ObjectMapper().readValue("""
        {"name":"demo","dist-tags":{"latest":"2.0.0"},
         "time":{"1.0.0":"2026-07-18T12:00:00Z","2.0.0":"2026-07-19T11:30:01Z"},
         "versions":{
           "1.0.0":{"name":"demo","version":"1.0.0","dist":{"tarball":"https://registry.npmjs.org/demo/-/demo-1.0.0.tgz"}},
           "2.0.0":{"name":"demo","version":"2.0.0","dist":{"tarball":"https://registry.npmjs.org/demo/-/demo-2.0.0.tgz"}}
         }}
        """, Map.class);
    when(fixture.cache.find(eq(10L), eq("demo"), any())).thenReturn(Optional.of(packageSnapshot));
    when(fixture.hosted.packageRoot(packageSnapshot)).thenReturn(Optional.of(raw));

    MavenResponse response = fixture.service.getDistTags(runtime, PACKAGE, false);
    Map<?, ?> tags = new ObjectMapper().readValue(response.body(), Map.class);
    MavenResponse repeated = fixture.service.getDistTags(runtime, PACKAGE, false);

    assertEquals("1.0.0", tags.get("latest"));
    assertEquals(tags, new ObjectMapper().readValue(repeated.body(), Map.class));
    verify(fixture.hosted).packageRoot(packageSnapshot);
    verify(fixture.hosted, never()).getDistTags(runtime, PACKAGE, false);
  }

  @Test
  void verifiedFullMetadataWithMissingTimesDoesNotForceRefetchOnEveryRequest() throws Exception {
    Instant now = Instant.parse("2026-07-19T12:00:00Z");
    Fixture fixture = fixture(Clock.fixed(now, ZoneOffset.UTC));
    RepositoryRuntime runtime = runtime(60, 7L, 60);
    CachedAssetMetadata packageSnapshot = snapshot(
        "demo", now, "package-root",
        Map.of("npmFullMetadata", "true", "npmCompletePublishTimes", "false"));
    Map<String, Object> raw = packument("1.0.0", "2026-07-19T10:00:00Z");
    raw.remove("time");
    when(fixture.cache.find(eq(10L), eq("demo"), any())).thenReturn(Optional.of(packageSnapshot));
    when(fixture.hosted.packageRoot(packageSnapshot)).thenReturn(Optional.of(raw));

    MavenResponse first = fixture.service.getPackage(runtime, PACKAGE, "base", false);
    MavenResponse second = fixture.service.getPackage(runtime, PACKAGE, "base", false);

    assertTrue(((Map<?, ?>) new ObjectMapper().readValue(first.body(), Map.class)
        .get("versions")).isEmpty());
    assertTrue(((Map<?, ?>) new ObjectMapper().readValue(second.body(), Map.class)
        .get("versions")).isEmpty());
    verify(fixture.hosted).packageRoot(packageSnapshot);
    verify(fixture.fetcher, never()).fetchWithBodyRetry(any(), anyString(), any());
  }

  @Test
  void maturityBoundaryForcesRevalidationAndFailureDoesNotExposeVersion() throws Exception {
    Instant now = Instant.parse("2026-07-19T12:01:00Z");
    Instant lastVerified = Instant.parse("2026-07-19T11:59:00Z");
    Fixture fixture = fixture(Clock.fixed(now, ZoneOffset.UTC));
    RepositoryRuntime runtime = runtime(-1, 7L, 60);
    CachedAssetMetadata packageSnapshot = snapshot(
        "demo", lastVerified, "package-root", Map.of("remoteEtag", "root"));
    Map<String, Object> raw = packument("1.0.0", "2026-07-19T11:00:00Z");
    when(fixture.cache.find(eq(10L), eq("demo"), any())).thenReturn(Optional.of(packageSnapshot));
    when(fixture.hosted.packageRoot(packageSnapshot)).thenReturn(Optional.of(raw));
    respond(fixture.fetcher, new HttpRemoteFetcher.Result(
        503, Map.of(), new ByteArrayInputStream(new byte[0])));

    MavenResponse response = fixture.service.getPackage(runtime, PACKAGE, "base", false);
    Map<?, ?> body = new ObjectMapper().readValue(response.body(), Map.class);

    assertTrue(((Map<?, ?>) body.get("versions")).isEmpty(),
        "a failed maturity-boundary revalidation must keep the release hidden");
    verify(fixture.fetcher).fetchWithBodyRetry(any(), eq("demo"), any());
    verify(fixture.proxyStateDao).recordFailure(eq(10L), eq(30L), anyString(), eq(now));
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
    return fixture(Clock.systemUTC());
  }

  private static Fixture fixture(Clock clock) {
    return fixture(clock, true);
  }

  private static Fixture fixtureWithoutReleaseIndex(Clock clock) {
    return fixture(clock, false);
  }

  private static Fixture fixture(Clock clock, boolean releaseIndexEnabled) {
    AssetDao assetDao = mock(AssetDao.class);
    BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    NpmAssetWriter writer = mock(NpmAssetWriter.class);
    ProxyStateDao proxyStateDao = mock(ProxyStateDao.class);
    HttpRemoteFetcher fetcher = mock(HttpRemoteFetcher.class);
    NpmHostedService hosted = mock(NpmHostedService.class);
    ProxyNegativeCache negativeCache = mock(ProxyNegativeCache.class);
    AssetMetadataCache cache = mock(AssetMetadataCache.class);
    NpmReleaseIndexDao releaseIndexDao = releaseIndexEnabled
        ? mock(NpmReleaseIndexDao.class)
        : null;
    BlobStorage storage = mock(BlobStorage.class);
    return new Fixture(
        assetDao, registry, writer, proxyStateDao, fetcher, hosted, negativeCache, cache,
        releaseIndexDao, storage, new NpmProxyService(
            assetDao, registry, writer, proxyStateDao, fetcher, hosted, new ObjectMapper(),
            negativeCache, cache, null,
            new NpmReleaseAgeCache(10000, 16L * 1024 * 1024, 30), releaseIndexDao, clock));
  }

  private static RepositoryRuntime runtime(int maxAgeMinutes, Long blobStoreId) {
    return runtime(maxAgeMinutes, blobStoreId, 0);
  }

  private static RepositoryRuntime runtime(
      int maxAgeMinutes, Long blobStoreId, int minimumReleaseAgeMinutes) {
    return new RepositoryRuntime(
        10L, "npm", RepositoryFormat.NPM, RepositoryType.PROXY, "npm", true, blobStoreId,
        null, null, null, true, "https://registry.npmjs.org/",
        maxAgeMinutes, maxAgeMinutes, true, null, List.of(), minimumReleaseAgeMinutes);
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
    return stored(path, kind, contentType, Map.of());
  }

  private static NpmAssetWriter.Stored stored(
      String path,
      String kind,
      String contentType,
      Map<String, Object> blobAttributes) {
    AssetRecord asset = new AssetRecord(
        1L, 10L, 3L, 2L, RepositoryFormat.NPM, path, null,
        path.substring(path.lastIndexOf('/') + 1), kind, contentType,
        7L, null, Instant.now(), Map.of());
    return new NpmAssetWriter.Stored(
        asset, blob(blobAttributes),
        new NpmAssetWriter.Digests("md5", "sha1", "sha256", "sha512", 7L),
        true, null);
  }

  private static AssetBlobRecord blob(Map<String, Object> attributes) {
    return new AssetBlobRecord(
        2L, 7L, "blob://bucket/object", null, "object", null,
        "sha1", "sha256", "md5", 7L, "application/octet-stream",
        "proxy", "upstream", Instant.EPOCH, Instant.EPOCH, attributes);
  }

  private static Map<String, Object> packument(String version, String publishedAt) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("name", "demo");
    root.put("dist-tags", new LinkedHashMap<>(Map.of("latest", version)));
    root.put("time", new LinkedHashMap<>(Map.of(version, publishedAt)));
    root.put("versions", new LinkedHashMap<>(Map.of(
        version,
        new LinkedHashMap<>(Map.of(
            "name", "demo",
            "version", version,
            "dist", new LinkedHashMap<>(Map.of(
                "tarball", "https://registry.npmjs.org/demo/-/demo-" + version + ".tgz")))))));
    return root;
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
      NpmReleaseIndexDao releaseIndexDao,
      BlobStorage storage,
      NpmProxyService service) {
  }
}
