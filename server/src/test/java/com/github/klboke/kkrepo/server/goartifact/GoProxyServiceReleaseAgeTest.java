package com.github.klboke.kkrepo.server.goartifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.github.klboke.kkrepo.server.releaseage.ReleaseAgeDeniedException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GoProxyServiceReleaseAgeTest {
  private static final Instant NOW = Instant.parse("2026-01-10T12:00:00Z");
  private static final int FORTY_EIGHT_HOURS = 48 * 60;
  private static final String MODULE = "example.com/acme/demo";
  private static final String LIST = MODULE + "/@v/list";
  private static final String LATEST = MODULE + "/@latest";

  @Test
  void listHidesVersionsYoungerThanTheCooldownAndVersionsWithUnknownTime() throws Exception {
    Fixture fixture = fixture();
    fixture.cached(LIST, "v1.0.0\nv1.1.0\nv1.2.0\nv1.3.0\n");
    fixture.info("v1.0.0", NOW.minus(Duration.ofHours(100)));
    fixture.info("v1.1.0", NOW.minus(Duration.ofHours(49)));
    fixture.info("v1.2.0", NOW.minus(Duration.ofHours(1)));
    // v1.3.0 has no .info upstream, so its publish time is unknown and it must stay hidden.
    fixture.upstreamMissing(MODULE + "/@v/v1.3.0.info");

    MavenResponse response = fixture.service.get(runtime(FORTY_EIGHT_HOURS), LIST, false);

    assertEquals("v1.0.0\nv1.1.0\n", body(response));
  }

  @Test
  void listIsEmptyWhenEveryVersionIsQuarantined() throws Exception {
    Fixture fixture = fixture();
    fixture.cached(LIST, "v1.0.0\n");
    fixture.info("v1.0.0", NOW.minus(Duration.ofHours(2)));

    assertEquals("", body(fixture.service.get(runtime(FORTY_EIGHT_HOURS), LIST, false)));
  }

  @Test
  void listIsUntouchedWhenThePolicyIsDisabled() throws Exception {
    Fixture fixture = fixture();
    fixture.cached(LIST, "v1.0.0\nv1.2.0\n");

    assertEquals("v1.0.0\nv1.2.0\n", body(fixture.service.get(runtime(0), LIST, false)));
    verify(fixture.cache, never()).find(eq(10L), eq(MODULE + "/@v/v1.0.0.info"), any());
  }

  @Test
  void latestIsServedWhenTheUpstreamLatestHasMatured() throws Exception {
    Fixture fixture = fixture();
    String latest = infoJson("v1.1.0", NOW.minus(Duration.ofHours(60)));
    fixture.cached(LATEST, latest);

    assertEquals(latest, body(fixture.service.get(runtime(FORTY_EIGHT_HOURS), LATEST, false)));
  }

  @Test
  void latestFallsBackToTheNewestMatureVersionWhenUpstreamLatestIsQuarantined() throws Exception {
    Fixture fixture = fixture();
    fixture.cached(LATEST, infoJson("v1.2.0", NOW.minus(Duration.ofHours(1))));
    fixture.cached(LIST, "v1.0.0\nv1.1.0\nv1.2.0\n");
    fixture.info("v1.0.0", NOW.minus(Duration.ofHours(200)));
    fixture.info("v1.1.0", NOW.minus(Duration.ofHours(60)));
    fixture.info("v1.2.0", NOW.minus(Duration.ofHours(1)));

    String json = body(fixture.service.get(runtime(FORTY_EIGHT_HOURS), LATEST, false));

    assertEquals(infoJson("v1.1.0", NOW.minus(Duration.ofHours(60))), json);
  }

  @Test
  void latestIsNotFoundWhenNothingHasMatured() {
    Fixture fixture = fixture();
    fixture.cached(LATEST, infoJson("v1.0.0", NOW.minus(Duration.ofHours(1))));
    fixture.cached(LIST, "v1.0.0\n");
    fixture.info("v1.0.0", NOW.minus(Duration.ofHours(1)));

    assertThrows(
        MavenExceptions.MavenNotFoundException.class,
        () -> fixture.service.get(runtime(FORTY_EIGHT_HOURS), LATEST, false));
  }

  @Test
  void zipAndModAreForbiddenBeforeTheCacheIsConsultedWhenTheVersionIsTooNew() {
    Fixture fixture = fixture();
    fixture.info("v1.2.0", NOW.minus(Duration.ofHours(1)));
    String zip = MODULE + "/@v/v1.2.0.zip";
    String mod = MODULE + "/@v/v1.2.0.mod";

    ReleaseAgeDeniedException denied = assertThrows(
        ReleaseAgeDeniedException.class,
        () -> fixture.service.get(runtime(FORTY_EIGHT_HOURS), zip, false));
    assertThrows(
        ReleaseAgeDeniedException.class,
        () -> fixture.service.get(runtime(FORTY_EIGHT_HOURS), mod, true));

    assertEquals(NOW.minus(Duration.ofHours(1)).plus(Duration.ofHours(48)), denied.eligibleAt());
    verify(fixture.cache, never()).find(eq(10L), eq(zip), any());
    verify(fixture.cache, never()).find(eq(10L), eq(mod), any());
    // A denial must never be remembered as "not found": the version matures later.
    verify(fixture.negativeCache, never()).rememberNotFound(any(), anyString());
  }

  @Test
  void zipIsServedOnceTheVersionHasMatured() throws Exception {
    Fixture fixture = fixture();
    fixture.info("v1.1.0", NOW.minus(Duration.ofHours(49)));
    fixture.cached(MODULE + "/@v/v1.1.0.zip", "zip-bytes");

    MavenResponse response = fixture.service.get(
        runtime(FORTY_EIGHT_HOURS), MODULE + "/@v/v1.1.0.zip", false);

    assertEquals("zip-bytes", body(response));
  }

  @Test
  void infoIsForbiddenWhileQuarantinedAndServedAfterwards() throws Exception {
    Fixture young = fixture();
    young.info("v1.2.0", NOW.minus(Duration.ofHours(1)));
    assertThrows(
        ReleaseAgeDeniedException.class,
        () -> young.service.get(
            runtime(FORTY_EIGHT_HOURS), MODULE + "/@v/v1.2.0.info", false));

    Fixture mature = fixture();
    mature.info("v1.2.0", NOW.minus(Duration.ofHours(72)));
    assertEquals(
        infoJson("v1.2.0", NOW.minus(Duration.ofHours(72))),
        body(mature.service.get(
            runtime(FORTY_EIGHT_HOURS), MODULE + "/@v/v1.2.0.info", false)));
  }

  @Test
  void maturityIsReevaluatedAsTimePasses() throws Exception {
    Instant published = NOW.minus(Duration.ofHours(47));
    Fixture early = fixture();
    early.info("v1.2.0", published);
    assertThrows(
        ReleaseAgeDeniedException.class,
        () -> early.service.get(
            runtime(FORTY_EIGHT_HOURS), MODULE + "/@v/v1.2.0.mod", false));

    Fixture later = fixture(NOW.plus(Duration.ofHours(2)));
    later.info("v1.2.0", published);
    later.cached(MODULE + "/@v/v1.2.0.mod", "module demo");
    assertEquals(
        "module demo",
        body(later.service.get(runtime(FORTY_EIGHT_HOURS), MODULE + "/@v/v1.2.0.mod", false)));
  }

  @Test
  void aVersionWithAnUnparseablePublishTimeIsQuarantined() {
    Fixture fixture = fixture();
    fixture.cached(MODULE + "/@v/v1.2.0.info", "{\"Version\":\"v1.2.0\"}");

    ReleaseAgeDeniedException denied = assertThrows(
        ReleaseAgeDeniedException.class,
        () -> fixture.service.get(
            runtime(FORTY_EIGHT_HOURS), MODULE + "/@v/v1.2.0.zip", false));

    assertEquals(null, denied.eligibleAt());
  }

  // ---------------------------------------------------------------------------------------------

  private static String body(MavenResponse response) throws IOException {
    return new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
  }

  private static String infoJson(String version, Instant time) {
    return "{\"Version\":\"" + version + "\",\"Time\":\"" + time + "\"}";
  }

  private static RepositoryRuntime runtime(int minimumReleaseAgeMinutes) {
    return new RepositoryRuntime(
        10L, "go", RepositoryFormat.GO, RepositoryType.PROXY, "go", true, 7L,
        null, null, null, true, "https://proxy.golang.org/",
        60, 60, true, null, List.of(), minimumReleaseAgeMinutes);
  }

  private static Fixture fixture() {
    return fixture(NOW);
  }

  private static Fixture fixture(Instant now) {
    return new Fixture(now);
  }

  private static final class Fixture {
    final AssetMetadataCache cache = mock(AssetMetadataCache.class);
    final HttpRemoteFetcher fetcher = mock(HttpRemoteFetcher.class);
    final ProxyNegativeCache negativeCache = mock(ProxyNegativeCache.class);
    final BlobStorage storage = mock(BlobStorage.class);
    final Map<String, byte[]> blobs = new HashMap<>();
    final Instant now;
    final GoProxyService service;

    Fixture(Instant now) {
      this.now = now;
      BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
      ProxyStateDao proxyStateDao = mock(ProxyStateDao.class);
      when(registry.forBlobStoreId(7L)).thenReturn(storage);
      when(storage.get(any())).thenAnswer(invocation -> {
        com.github.klboke.kkrepo.core.BlobReference ref = invocation.getArgument(0);
        byte[] bytes = blobs.get(ref.objectKey());
        return bytes == null
            ? Optional.empty()
            : Optional.of(new ByteArrayInputStream(bytes));
      });
      service = new GoProxyService(
          mock(AssetDao.class), registry, mock(GoAssetWriter.class), proxyStateDao, fetcher,
          negativeCache, cache, null, Clock.fixed(now, ZoneOffset.UTC));
    }

    /** A fresh cached upstream response, so no upstream fetch is needed. */
    void cached(String path, String body) {
      blobs.put(path, body.getBytes(StandardCharsets.UTF_8));
      AssetRecord asset = new AssetRecord(
          1L, 10L, 3L, 2L, RepositoryFormat.GO, path, null, path, "X", "text/plain",
          (long) body.length(), null, now, Map.of());
      AssetBlobRecord blob = new AssetBlobRecord(
          2L, 7L, "blob://bucket/" + path, null, path, null, "sha1", "sha256", "md5",
          body.length(), "text/plain", "proxy", "upstream", Instant.EPOCH, Instant.EPOCH,
          Map.of());
      when(cache.find(eq(10L), eq(path), any()))
          .thenReturn(Optional.of(CachedAssetMetadata.of(asset, blob)));
    }

    void info(String version, Instant time) {
      cached(MODULE + "/@v/" + version + ".info", infoJson(version, time));
    }

    void upstreamMissing(String path) {
      when(cache.find(eq(10L), eq(path), any())).thenReturn(Optional.empty());
      try {
        doAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          HttpRemoteFetcher.ResultHandler<MavenResponse> handler = invocation.getArgument(2);
          return handler.handle(new HttpRemoteFetcher.Result(
              404, Map.of(), new ByteArrayInputStream(new byte[0])));
        }).when(fetcher).fetchWithBodyRetry(any(), eq(path), any());
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
