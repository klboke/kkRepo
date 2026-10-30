package com.github.klboke.kkrepo.server.pypi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PypiProxyServiceReleaseAgeTest {
  private static final Instant NOW = Instant.parse("2026-01-10T12:00:00Z");
  private static final int FORTY_EIGHT_HOURS = 48 * 60;
  private static final String INDEX = "simple/demo/";

  @Test
  void indexHidesYoungFilesAndFilesWithUnknownUploadTime() throws Exception {
    Fixture fixture = fixture();
    fixture.cachedIndex(
        link("demo-1.0.0.whl", NOW.minus(Duration.ofHours(100)))
            + link("demo-1.1.0.whl", NOW.minus(Duration.ofHours(49)))
            + link("demo-1.2.0.whl", NOW.minus(Duration.ofHours(1)))
            + link("demo-1.3.0.whl", null));

    String html = body(fixture.service.getIndex(runtime(FORTY_EIGHT_HOURS), "Demo", false));

    assertTrue(html.contains("demo-1.0.0.whl"), html);
    assertTrue(html.contains("demo-1.1.0.whl"), html);
    assertFalse(html.contains("demo-1.2.0.whl"), html);
    assertFalse(html.contains("demo-1.3.0.whl"), html);
    // The stored upload times are internal and never leak to clients.
    assertFalse(html.contains("data-upload-time"), html);
  }

  @Test
  void indexIsNotFoundWhenEveryFileIsQuarantined() {
    Fixture fixture = fixture();
    fixture.cachedIndex(link("demo-1.2.0.whl", NOW.minus(Duration.ofHours(1))));

    assertThrows(
        PypiExceptions.PypiNotFoundException.class,
        () -> fixture.service.getIndex(runtime(FORTY_EIGHT_HOURS), "demo", false));
  }

  @Test
  void packageDownloadIsForbiddenBeforeTheCacheIsConsultedWhileQuarantined() {
    Fixture fixture = fixture();
    fixture.cachedIndex(link("demo-1.2.0.whl", NOW.minus(Duration.ofHours(1))));
    String path = "packages/demo/1.2.0/demo-1.2.0.whl";

    ReleaseAgeDeniedException denied = assertThrows(
        ReleaseAgeDeniedException.class,
        () -> fixture.service.getPackage(runtime(FORTY_EIGHT_HOURS), path, false));
    assertThrows(
        ReleaseAgeDeniedException.class,
        () -> fixture.service.getPackage(
            runtime(FORTY_EIGHT_HOURS), path + ".asc", false));

    assertEquals(NOW.minus(Duration.ofHours(1)).plus(Duration.ofHours(48)), denied.eligibleAt());
    verify(fixture.cache, never()).find(eq(10L), eq(path), any());
    // A denial must never be remembered as "not found": the file matures later.
    verify(fixture.negativeCache, never()).rememberNotFound(any(), anyString());
  }

  @Test
  void packageDownloadIsServedOnceTheFileHasMatured() throws Exception {
    Fixture fixture = fixture();
    fixture.cachedIndex(link("demo-1.1.0.whl", NOW.minus(Duration.ofHours(49))));
    String path = "packages/demo/1.1.0/demo-1.1.0.whl";
    CachedAssetMetadata cachedFile = snapshot(path, Instant.now(), Map.of());
    when(fixture.cache.find(eq(10L), eq(path), any())).thenReturn(Optional.of(cachedFile));
    PypiResponse served = PypiResponse.ok(
        new ByteArrayInputStream("wheel".getBytes(StandardCharsets.UTF_8)), 5L,
        "application/zip", "etag", NOW);
    when(fixture.reader.serveSnapshot(cachedFile, false, path)).thenReturn(served);

    assertEquals("wheel", body(fixture.service.getPackage(
        runtime(FORTY_EIGHT_HOURS), path, false)));
  }

  @Test
  void packageMissingFromTheIndexIsNotFoundNotForbidden() {
    Fixture fixture = fixture();
    fixture.cachedIndex(link("demo-1.1.0.whl", NOW.minus(Duration.ofHours(49))));

    assertThrows(
        PypiExceptions.PypiNotFoundException.class,
        () -> fixture.service.getPackage(
            runtime(FORTY_EIGHT_HOURS), "packages/demo/9.9.9/demo-9.9.9.whl", false));
  }

  @Test
  void packageWithUnknownUploadTimeIsQuarantinedWithoutAnEligibleInstant() {
    Fixture fixture = fixture();
    fixture.cachedIndex(link("demo-1.1.0.whl", null));

    ReleaseAgeDeniedException denied = assertThrows(
        ReleaseAgeDeniedException.class,
        () -> fixture.service.getPackage(
            runtime(FORTY_EIGHT_HOURS), "packages/demo/1.1.0/demo-1.1.0.whl", false));

    assertNull(denied.eligibleAt());
  }

  @Test
  void maturityIsReevaluatedAsTimePasses() throws Exception {
    Instant uploaded = NOW.minus(Duration.ofHours(47));
    Fixture early = fixture(NOW);
    early.cachedIndex(link("demo-1.0.0.whl", uploaded));
    assertThrows(
        PypiExceptions.PypiNotFoundException.class,
        () -> early.service.getIndex(runtime(FORTY_EIGHT_HOURS), "demo", false));

    Fixture later = fixture(NOW.plus(Duration.ofHours(2)));
    later.cachedIndex(link("demo-1.0.0.whl", uploaded));
    assertTrue(body(later.service.getIndex(runtime(FORTY_EIGHT_HOURS), "demo", false))
        .contains("demo-1.0.0.whl"));
  }

  @Test
  void indexCachedBeforeThePolicyWasEnabledIsRefetchedAsJsonWithUploadTimes() throws Exception {
    Fixture fixture = fixture();
    // No "uploadTimes" marker: it was stored from an HTML upstream and has no upload times.
    when(fixture.cache.find(eq(10L), eq(INDEX), any())).thenReturn(Optional.of(
        snapshot(INDEX, Instant.now(), Map.of())));
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(mock(com.github.klboke.kkrepo.core.BlobStorage.class));
    AtomicReference<String> accept = new AtomicReference<>();
    AtomicReference<String> written = new AtomicReference<>();
    doAnswer(invocation -> {
      HttpRemoteFetcher.Request request = invocation.getArgument(0);
      accept.set(request.accept());
      @SuppressWarnings("unchecked")
      HttpRemoteFetcher.ResultHandler<PypiResponse> handler = invocation.getArgument(2);
      return handler.handle(new HttpRemoteFetcher.Result(
          200, Map.of("Content-Type", "application/vnd.pypi.simple.v1+json"),
          new ByteArrayInputStream("""
              {"meta":{"api-version":"1.1"},"name":"demo","files":[
                {"filename":"demo-1.0.0.whl","url":"https://files.example/demo-1.0.0.whl",
                 "hashes":{"sha256":"abc"},"requires-python":">=3.11",
                 "upload-time":"2026-01-01T00:00:00.000000Z"}]}
              """.getBytes(StandardCharsets.UTF_8))));
    }).when(fixture.fetcher).fetchWithBodyRetry(any(), anyString(), any());
    doAnswer(invocation -> {
      written.set(new String(
          ((java.io.InputStream) invocation.getArgument(4)).readAllBytes(),
          StandardCharsets.UTF_8));
      return stored(INDEX);
    }).when(fixture.writer).write(
        any(), any(), anyLong(), eq(INDEX), any(), any(), any(), any(), any(), any(), any(), any(),
        anyBoolean());

    fixture.service.getIndex(runtime(FORTY_EIGHT_HOURS), "demo", false);

    assertEquals("application/vnd.pypi.simple.v1+json", accept.get());
    assertTrue(written.get().contains("../../packages/demo/1.0.0/demo-1.0.0.whl#sha256=abc"));
    assertTrue(written.get().contains("data-upload-time=\"2026-01-01T00:00:00Z\""), written.get());
  }

  @Test
  void upstreamWithoutJsonIndexFailsClosedInsteadOfServingUnfilteredContent() throws Exception {
    Fixture fixture = fixture();
    when(fixture.cache.find(eq(10L), eq(INDEX), any())).thenReturn(Optional.empty());
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(mock(com.github.klboke.kkrepo.core.BlobStorage.class));
    doAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      HttpRemoteFetcher.ResultHandler<PypiResponse> handler = invocation.getArgument(2);
      return handler.handle(new HttpRemoteFetcher.Result(
          200, Map.of("Content-Type", "text/html"),
          new ByteArrayInputStream(
              "<a href=\"../../files/demo-1.0.0.whl\">demo-1.0.0.whl</a>"
                  .getBytes(StandardCharsets.UTF_8))));
    }).when(fixture.fetcher).fetchWithBodyRetry(any(), anyString(), any());

    PypiExceptions.BadUpstreamException failure = assertThrows(
        PypiExceptions.BadUpstreamException.class,
        () -> fixture.service.getIndex(runtime(FORTY_EIGHT_HOURS), "demo", false));

    assertTrue(failure.getMessage().contains("upload-time"));
    verify(fixture.writer, never()).write(
        any(), any(), anyLong(), anyString(), any(), any(), any(), any(), any(), any(), any(),
        any(), anyBoolean());
  }

  // ---------------------------------------------------------------------------------------------

  private static String link(String file, Instant uploaded) {
    String version = file.replaceAll("^demo-([0-9.]+)\\.whl$", "$1");
    return "<a href=\"../../packages/demo/" + version + "/" + file + "#sha256=abc\" rel=\"internal\""
        + (uploaded == null ? "" : " data-upload-time=\"" + uploaded + "\"")
        + ">" + file + "</a><br/>\n";
  }

  private static String body(PypiResponse response) throws IOException {
    return new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
  }

  private static RepositoryRuntime runtime(int minimumReleaseAgeMinutes) {
    return new RepositoryRuntime(
        10L, "pypi", RepositoryFormat.PYPI, RepositoryType.PROXY, "pypi", true, 7L,
        null, null, null, true, "https://pypi.example.test/",
        60, 60, true, null, List.of(), minimumReleaseAgeMinutes);
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

  private static PypiAssetWriter.Stored stored(String path) {
    AssetRecord asset = new AssetRecord(
        1L, 10L, null, 2L, RepositoryFormat.PYPI, path, null,
        PypiPaths.fileName(path), "index", "text/html", 5L, null, Instant.now(), Map.of());
    return new PypiAssetWriter.Stored(
        asset, blob(), new PypiAssetWriter.Digests("md5", "sha1", "sha256", "sha512", 5L),
        true, null);
  }

  private static AssetBlobRecord blob() {
    return new AssetBlobRecord(
        2L, 7L, "blob://bucket/object", null, "object", null,
        "sha1", "sha256", "md5", 5L, "text/html",
        "proxy", "upstream", Instant.EPOCH, Instant.EPOCH, Map.of());
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
    final PypiAssetReader reader = mock(PypiAssetReader.class);
    final PypiAssetWriter writer = mock(PypiAssetWriter.class);
    final BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    final PypiProxyService service;

    Fixture(Instant now) {
      PypiRepositorySettings settings = mock(PypiRepositorySettings.class);
      when(settings.get(any())).thenReturn(new PypiRepositorySettings.Settings("/simple"));
      service = new PypiProxyService(
          mock(AssetDao.class), registry, writer, reader, mock(ProxyStateDao.class), fetcher,
          negativeCache, cache, null, settings, Clock.fixed(now, ZoneOffset.UTC));
    }

    /** The project page as stored by a policy-enabled fetch: every file plus its upload time. */
    void cachedIndex(String links) {
      String html = "<html><body>\n" + links + "</body></html>\n";
      CachedAssetMetadata cached = snapshot(INDEX, Instant.now(), Map.of("uploadTimes", "true"));
      when(cache.find(eq(10L), eq(INDEX), any())).thenReturn(Optional.of(cached));
      when(reader.serveSnapshot(cached, false, INDEX)).thenReturn(PypiResponse.ok(
          new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)), html.length(),
          "text/html", "etag", NOW));
    }
  }
}
