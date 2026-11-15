package com.github.klboke.kkrepo.server.proxy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.securityscan.ArtifactDownloadPolicy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class ProgressiveProxyServiceTest {
  @TempDir Path directory;
  private final RepositoryDao repositories = mock(RepositoryDao.class);
  private final AssetDao assets = mock(AssetDao.class);
  private final BlobStorageRegistry stores = mock(BlobStorageRegistry.class);
  private final ArtifactDownloadPolicy policy = mock(ArtifactDownloadPolicy.class);
  private final RepositoryRuntime runtime = mock(RepositoryRuntime.class);
  private final HttpRemoteFetcher fetcher = mock(HttpRemoteFetcher.class);
  private ProgressiveProxyService service;
  private final byte[] content = new byte[200_000];
  private final HttpRemoteFetcher.Request request = HttpRemoteFetcher.Request.get("https://example.org/pkg.bin");

  @BeforeEach void setUp() throws Exception {
    Arrays.fill(content, (byte) 42);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
        new MockHttpServletRequest("GET", "/repository/proxy/pkg.bin")));
    when(runtime.id()).thenReturn(1L);
    when(runtime.name()).thenReturn("proxy");
    when(runtime.isProxy()).thenReturn(true);
    when(runtime.streamingEnabled()).thenReturn(true);
    when(runtime.format()).thenReturn(RepositoryFormat.RAW);
    when(runtime.blobStoreId()).thenReturn(2L);
    when(runtime.proxyRemoteUrl()).thenReturn("https://example.org/");
    when(policy.allowsProgressiveRead(1L)).thenReturn(true);
    when(repositories.findUpdatedAt(1L)).thenReturn(Optional.of(Instant.EPOCH));
    when(repositories.findById(1L)).thenReturn(Optional.of(new RepositoryRecord(1L, "proxy",
        RepositoryFormat.RAW, RepositoryType.PROXY, "raw-proxy", true, 2L, null,
        "https://example.org/", null, null, null, false,
        Map.of("proxy", Map.of("streamingEnabled", true)))));
    BlobStorage store = mock(BlobStorage.class);
    when(stores.forBlobStoreId(2L)).thenReturn(store);
    when(store.stagingDirectory()).thenReturn(Optional.of(directory));
    when(fetcher.fetch(any())).thenAnswer(call -> upstream(content, content.length));
    when(fetcher.fetchWithBodyRetry(any(), anyString(), any())).thenAnswer(call -> {
      HttpRemoteFetcher.ResultHandler<?> handler = call.getArgument(2);
      try (var result = upstream(content, content.length)) { return handler.handle(result); }
    });
    service = new ProgressiveProxyService(repositories, assets, stores, policy, 2, 2_000_000);
  }

  @AfterEach void cleanUp() { service.stop(); RequestContextHolder.resetRequestAttributes(); }

  @Test void returnsBytesBeforeEofAndCompletesWithoutWaitingForCacheUpload() throws Exception {
    CountDownLatch publishing = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
    var response = fetch(null, null, result -> {
      publishing.countDown();
      try {
        assertArrayEquals(content, result.body().readAllBytes());
        assertTrue(release.await(5, TimeUnit.SECONDS));
      } catch (InterruptedException e) { throw new IOException(e); }
      finally { done.countDown(); }
      return MavenResponse.noBody(200, content.length, "application/octet-stream", null, null);
    });
    try (var body = response.body()) {
      byte[] first = body.readNBytes(1024);
      assertEquals(1024, first.length);
      assertEquals(1, publishing.getCount());
      assertEquals(content.length - 1024, body.readAllBytes().length);
      assertTrue(publishing.await(5, TimeUnit.SECONDS));
      assertEquals(1, done.getCount(), "client completed while the cache writer was still blocked");
    } finally { release.countDown(); }
    assertTrue(done.await(5, TimeUnit.SECONDS));
  }

  @Test void checksumFailureWithholdsFinalBytesAndNeverPublishes() throws Exception {
    AtomicBoolean published = new AtomicBoolean();
    var response = fetch("00".repeat(32), null, result -> { published.set(true); return null; });
    int received = 0;
    try (var body = response.body()) {
      byte[] bytes = new byte[8192];
      try { for (int n; (n = body.read(bytes)) >= 0;) received += n; fail("checksum accepted"); }
      catch (IOException expected) { assertTrue(expected.getMessage().contains("SHA-256")); }
    }
    assertTrue(received > 0 && received < content.length);
    assertFalse(published.get());
    assertNoStagedFiles();
  }

  @Test void disconnectAndTruncatedUpstreamReleaseStaging() throws Exception {
    AtomicBoolean published = new AtomicBoolean();
    var response = fetch(null, null, result -> { published.set(true); return null; });
    response.body().readNBytes(1024);
    response.body().close();
    assertNoStagedFiles();
    when(fetcher.fetch(any())).thenReturn(upstream(new byte[100], 200));
    var truncated = fetch(null, null, result -> { published.set(true); return null; });
    assertThrows(IOException.class, () -> truncated.body().readAllBytes());
    assertFalse(published.get());
    assertNoStagedFiles();
  }

  @Test void protocolValidationRunsBeforeLastBlockAndCachePublication() throws Exception {
    AtomicBoolean published = new AtomicBoolean();
    var response = fetch(null, path -> { throw new IOException("invalid archive"); }, result -> {
      published.set(true); return null;
    });
    assertThrows(IOException.class, () -> response.body().readAllBytes());
    assertFalse(published.get());
    assertNoStagedFiles();
  }

  @Test void rangeIsForwardedAndPartialBytesAreNotCached() throws Exception {
    MockHttpServletRequest client = new MockHttpServletRequest("GET", "/repository/proxy/pkg.bin");
    client.addHeader("Range", "bytes=100000-");
    client.addHeader("If-Range", "\"version-one\"");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(client));
    byte[] suffix = Arrays.copyOfRange(content, 100000, content.length);
    when(fetcher.fetch(any())).thenReturn(new HttpRemoteFetcher.Result(206,
        Map.of("Content-Length", "100000", "Content-Range", "bytes 100000-199999/200000"),
        new ByteArrayInputStream(suffix)));
    AtomicBoolean cached = new AtomicBoolean();
    var response = fetch(null, null, result -> { cached.set(true); return null; });
    assertEquals(206, response.status());
    assertArrayEquals(suffix, response.body().readAllBytes());
    assertFalse(cached.get());
    assertTrue(ProgressiveProxyService.rangeHandled(client));
    var argument = ArgumentCaptor.forClass(HttpRemoteFetcher.Request.class);
    verify(fetcher).fetch(argument.capture());
    assertEquals("bytes=100000-", argument.getValue().byteRange().range());
    assertEquals("\"version-one\"", argument.getValue().byteRange().ifRange());
    assertNoStagedFiles();
  }

  @Test void malformedRangeResponseFailsBeforeDelivery() throws Exception {
    MockHttpServletRequest client = new MockHttpServletRequest("GET", "/repository/proxy/pkg.bin");
    client.addHeader("Range", "bytes=100000-");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(client));
    when(fetcher.fetch(any())).thenReturn(new HttpRemoteFetcher.Result(206,
        Map.of("Content-Length", "100000", "Content-Range", "bytes 0-99999/200000"),
        new ByteArrayInputStream(new byte[100000])));
    assertThrows(IOException.class, () -> fetch(null, null, result -> null));
  }

  @Test void disabledOrScanEnforcedRequestsUseBufferedPath() throws Exception {
    when(runtime.streamingEnabled()).thenReturn(false);
    fetch(null, null, result -> MavenResponse.noBody(200, 0, null, null, null));
    when(runtime.streamingEnabled()).thenReturn(true);
    when(policy.allowsProgressiveRead(1L)).thenReturn(false);
    fetch(null, null, result -> MavenResponse.noBody(200, 0, null, null, null));
    verify(fetcher, never()).fetch(any());
    verify(fetcher, times(2)).fetchWithBodyRetry(any(), eq("pkg.bin"), any());
  }

  @Test void internalMetadataPrefetchKeepsSynchronousPublication() throws Exception {
    MockHttpServletRequest client = new MockHttpServletRequest("GET", "/repository/proxy/index.json");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(client));
    fetch(null, null, result -> MavenResponse.noBody(200, 0, null, null, null));
    verify(fetcher, never()).fetch(any());
  }

  @Test void backgroundAndScannerReadsStayBuffered() throws Exception {
    RequestContextHolder.resetRequestAttributes();
    fetch(null, null, result -> MavenResponse.noBody(200));
    var scanner = new MockHttpServletRequest("GET", "/repository/proxy/pkg.bin");
    scanner.setAttribute(ArtifactDownloadPolicy.INTERNAL_SCANNER_REQUEST_ATTRIBUTE, true);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(scanner));
    fetch(null, null, result -> MavenResponse.noBody(200));
    verify(fetcher, never()).fetch(any());
    verify(fetcher, times(2)).fetchWithBodyRetry(any(), anyString(), any());
  }

  @Test void unsatisfiableRangeForwards416WithoutFetchingTheWholeArtifact() throws Exception {
    MockHttpServletRequest client = new MockHttpServletRequest("GET", "/repository/proxy/pkg.bin");
    client.addHeader("Range", "bytes=999999-");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(client));
    when(fetcher.fetch(any())).thenReturn(new HttpRemoteFetcher.Result(416,
        Map.of("Content-Range", "bytes */200000"), InputStream.nullInputStream()));
    var response = fetch(null, null, result -> { fail("must not cache 416"); return null; });
    assertEquals(416, response.status());
    assertEquals("bytes */200000", response.headers().get("Content-Range"));
    verify(fetcher, never()).fetchWithBodyRetry(any(), anyString(), any());
    assertNoStagedFiles();
  }

  @Test void changedIfRangeKeepsFull200AndDoesNotSliceNewBytes() throws Exception {
    MockHttpServletRequest client = new MockHttpServletRequest("GET", "/repository/proxy/pkg.bin");
    client.addHeader("Range", "bytes=100000-");
    client.addHeader("If-Range", "\"obsolete\"");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(client));
    var response = fetch(null, null, result -> MavenResponse.noBody(200));
    assertEquals(200, response.status());
    assertEquals(content.length, response.contentLength());
    assertSame(response, new com.github.klboke.kkrepo.server.maven.MavenPartialFetchSupport().apply(client, response));
    response.body().close();
    assertNoStagedFiles();
  }

  @Test void cacheUploadFailureDoesNotFailCompletedDeliveryAndReleasesStaging() throws Exception {
    CountDownLatch attempted = new CountDownLatch(1);
    var response = fetch(null, null, result -> { attempted.countDown(); throw new IOException("store unavailable"); });
    try (var body = response.body()) { assertArrayEquals(content, body.readAllBytes()); }
    assertTrue(attempted.await(5, TimeUnit.SECONDS));
    awaitCleanup();
  }

  @Test void observerValidationRunsBeforeCompletionAndObserversRunAfterPublication() throws Exception {
    AtomicBoolean validated = new AtomicBoolean(), published = new AtomicBoolean();
    CountDownLatch observed = new CountDownLatch(1);
    var response = fetch(null, null, result -> {
      assertTrue(validated.get()); published.set(true); return MavenResponse.noBody(200);
    });
    assertTrue(ProgressiveProxyService.onCached(response,
        file -> { assertEquals(content.length, Files.size(file)); validated.set(true); },
        () -> { assertTrue(published.get()); observed.countDown(); }));
    try (var body = response.body()) { assertArrayEquals(content, body.readAllBytes()); }
    assertTrue(observed.await(5, TimeUnit.SECONDS));
    awaitCleanup();
  }

  @Test void concurrentLimitFallsBackAndCancellationMakesCapacityAvailable() throws Exception {
    var first = fetch(null, null, result -> MavenResponse.noBody(200));
    var second = fetch(null, null, result -> MavenResponse.noBody(200));
    fetch(null, null, result -> MavenResponse.noBody(200));
    verify(fetcher, times(1)).fetchWithBodyRetry(any(), anyString(), any());
    first.body().close(); second.body().close();
    var next = fetch(null, null, result -> MavenResponse.noBody(200));
    next.body().close();
    verify(fetcher, times(1)).fetchWithBodyRetry(any(), anyString(), any());
    assertNoStagedFiles();
  }

  @Test void repositoryRevisionFenceRejectsStaleAndConcurrentFills() throws Exception {
    var snapshot = repositories.findById(1L).orElseThrow();
    when(repositories.lockByName("proxy")).thenReturn(Optional.of(snapshot));
    org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
    try (var publication = new ProxyCachePublication(repositories, assets, snapshot, Instant.EPOCH, "pkg.bin", "127.0.0.1")) {
      assertDoesNotThrow(() -> ProxyCachePublication.check(runtime, "pkg.bin"));
      when(repositories.findUpdatedAt(1L)).thenReturn(Optional.of(Instant.EPOCH.plusSeconds(1)));
      assertThrows(ProxyCachePublication.Superseded.class, () -> ProxyCachePublication.check(runtime, "pkg.bin"));
      when(repositories.findUpdatedAt(1L)).thenReturn(Optional.of(Instant.EPOCH));
      when(assets.findAssetByPath(1L, "pkg.bin")).thenReturn(Optional.of(mock(com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord.class)));
      assertThrows(ProxyCachePublication.Superseded.class, () -> ProxyCachePublication.check(runtime, "pkg.bin"));
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager.clear();
    }
  }

  @Test void existingAssetUsesSynchronousReplacementInsteadOfAnUnpublishableFill() throws Exception {
    when(assets.findAssetByPath(1L, "pkg.bin")).thenReturn(Optional.of(
        mock(com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord.class)));
    AtomicBoolean replaced = new AtomicBoolean();
    var response = fetch(null, null, result -> {
      assertArrayEquals(content, result.body().readAllBytes());
      replaced.set(true);
      return MavenResponse.noBody(200);
    });
    assertTrue(replaced.get(), "replacement must finish before returning to the caller");
    assertFalse(response.hasBody());
    verify(fetcher, never()).fetch(any());
    assertNoStagedFiles();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(value = RepositoryFormat.class, names = {"MAVEN2", "NPM"})
  void htmlArtifactsKeepSynchronousRejectionAndGroupFallback(RepositoryFormat format) throws Exception {
    when(runtime.format()).thenReturn(format);
    var upstreamBody = spy(new ByteArrayInputStream(content));
    when(fetcher.fetch(any())).thenReturn(new HttpRemoteFetcher.Result(200,
        Map.of("Content-Length", "200000", "Content-Type", "text/html; charset=utf-8"), upstreamBody));
    assertThrows(com.github.klboke.kkrepo.server.maven.MavenExceptions.MavenNotFoundException.class,
        () -> fetch(null, null, result -> {
          throw new com.github.klboke.kkrepo.server.maven.MavenExceptions.MavenNotFoundException("pkg.bin");
        }));
    verify(upstreamBody, atLeastOnce()).close();
    verify(fetcher).fetchWithBodyRetry(any(), eq("pkg.bin"), any());
    assertNoStagedFiles();
  }

  @Test void responseValidatorsSupportNotModifiedAndClosing304ReleasesCapacity() throws Exception {
    when(runtime.format()).thenReturn(RepositoryFormat.GO);
    var client = new MockHttpServletRequest("GET", "/repository/proxy/pkg.bin");
    client.addHeader("If-None-Match", "\"upstream-version\"");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(client));
    when(fetcher.fetch(any())).thenAnswer(call -> new HttpRemoteFetcher.Result(200,
        Map.of("Content-Length", "200000", "ETag", "\"upstream-version\""), new ByteArrayInputStream(content)));
    for (int i = 0; i < 3; i++) {
      var response = fetch(null, null, result -> { fail("304 must not publish a cache entry"); return null; });
      assertEquals("\"upstream-version\"", response.etag());
      assertTrue(com.github.klboke.kkrepo.server.http.ConditionalResponses.shouldReturnNotModified(
          client, response.status(), response.etag(), response.lastModified()));
      response.closeBodyIfOpen();
      assertNoStagedFiles();
    }
    verify(fetcher, never()).fetchWithBodyRetry(any(), anyString(), any());
  }

  @Test void diskBudgetIncludesDockerWriterAndStorageSpools() throws Exception {
    service.stop();
    service = new ProgressiveProxyService(repositories, assets, stores, policy, 4, 500_000);
    when(runtime.format()).thenReturn(RepositoryFormat.DOCKER);
    var buffered = ProgressiveProxyService.fetchDocker(service, fetcher::fetch, request, "pkg.bin",
        runtime, true, "00".repeat(32), result -> {
          assertArrayEquals(content, result.body().readAllBytes());
          return com.github.klboke.kkrepo.server.docker.DockerResponse.noBody(200);
        });
    assertNull(buffered.body(), "600000 bytes of staging do not fit in 500000 bytes");
    assertNoStagedFiles();
    when(runtime.format()).thenReturn(RepositoryFormat.RAW);
    var first = fetch(null, null, result -> MavenResponse.noBody(200));
    var second = fetch(null, null, result -> MavenResponse.noBody(200));
    assertFalse(second.hasBody(), "two 400000-byte reservations exceed the shared budget");
    first.closeBodyIfOpen();
    var replacement = fetch(null, null, result -> MavenResponse.noBody(200));
    assertTrue(replacement.hasBody());
    replacement.closeBodyIfOpen();
    assertNoStagedFiles();
  }

  @Test void pypiAndDockerResponsesExposeValidatorsAndCompleteTheirCacheWrites() throws Exception {
    when(fetcher.fetch(any())).thenAnswer(call -> new HttpRemoteFetcher.Result(200,
        Map.of("Content-Length", "200000", "ETag", "\"version\"",
            "Content-Disposition", "attachment", "Last-Modified", "Sun, 15 Nov 2026 12:00:00 GMT"),
        new ByteArrayInputStream(content)));
    CountDownLatch pypiCached = new CountDownLatch(1);
    var pypi = ProgressiveProxyService.fetchPypi(service, fetcher, request, "pkg.bin", runtime,
        true, null, null, result -> {
          pypiCached.countDown();
          return com.github.klboke.kkrepo.server.pypi.PypiResponse.ok(
              InputStream.nullInputStream(), 0, null, null, null);
        });
    assertEquals("\"version\"", pypi.etag());
    assertNotNull(pypi.lastModified());
    assertEquals("attachment", pypi.headers().get("Content-Disposition"));
    try (var body = pypi.body()) { assertArrayEquals(content, body.readAllBytes()); }
    assertTrue(pypiCached.await(5, TimeUnit.SECONDS));
    awaitCleanup();
    when(runtime.format()).thenReturn(RepositoryFormat.DOCKER);
    String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
    CountDownLatch dockerCached = new CountDownLatch(1);
    var docker = ProgressiveProxyService.fetchDocker(service, fetcher::fetch, request, "pkg.bin", runtime,
        true, sha, result -> {
          dockerCached.countDown();
          return com.github.klboke.kkrepo.server.docker.DockerResponse.body(200,
              InputStream::nullInputStream, 0, null, null);
        });
    assertEquals("sha256:" + sha, docker.headers().get("Docker-Content-Digest"));
    try (var body = docker.body()) { assertArrayEquals(content, body.readAllBytes()); }
    assertTrue(dockerCached.await(5, TimeUnit.SECONDS));
    awaitCleanup();
  }

  @Test void invalidatedRepositoryAndUnsupportedResponsesStayBuffered() throws Exception {
    var snapshot = repositories.findById(1L);
    when(repositories.findById(1L)).thenReturn(Optional.empty());
    assertFalse(fetch(null, null, result -> MavenResponse.noBody(200)).hasBody());
    when(repositories.findById(1L)).thenReturn(snapshot);
    for (var headers : java.util.List.of(Map.of("Content-Length", "-1"),
        Map.of("Content-Length", "200000", "Content-Encoding", "gzip"))) {
      when(fetcher.fetch(any())).thenReturn(new HttpRemoteFetcher.Result(200, headers, new ByteArrayInputStream(content)));
      assertFalse(fetch(null, null, result -> MavenResponse.noBody(200)).hasBody());
    }
    var range = new MockHttpServletRequest("GET", "/repository/proxy/pkg.bin");
    range.addHeader("Range", "bytes=0-1,4-5");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(range));
    assertFalse(fetch(null, null, result -> MavenResponse.noBody(200)).hasBody());
    assertNoStagedFiles();
  }

  @Test void rejectedPublicationAndSupersededFillReleaseAllStaging() throws Exception {
    service.stop();
    var rejected = fetch(null, null, result -> { fail("stopped publisher"); return null; });
    try (var body = rejected.body()) { assertArrayEquals(content, body.readAllBytes()); }
    assertNoStagedFiles();
    service = new ProgressiveProxyService(repositories, assets, stores, policy, 2, 2_000_000);
    var superseded = fetch(null, null, result -> { throw new ProxyCachePublication.Superseded(); });
    try (var body = superseded.body()) { assertArrayEquals(content, body.readAllBytes()); }
    awaitCleanup();
  }

  @Test void stagingCreationFailureClosesUpstreamAndReleasesAdmission() throws Exception {
    var broken = directory.resolve("not-a-directory");
    Files.writeString(broken, "file");
    when(stores.forBlobStoreId(2L).stagingDirectory()).thenReturn(Optional.of(broken));
    assertThrows(IOException.class, () -> fetch(null, null, result -> MavenResponse.noBody(200)));
    Files.delete(broken);
    when(stores.forBlobStoreId(2L).stagingDirectory()).thenReturn(Optional.of(directory));
    var response = fetch(null, null, result -> MavenResponse.noBody(200));
    response.closeBodyIfOpen();
    assertNoStagedFiles();
  }

  @Test void oversizedBodyAndReadsAfterCloseFailWithoutPublishing() throws Exception {
    when(fetcher.fetch(any())).thenReturn(upstream(content, content.length - 1));
    var oversized = fetch(null, null, result -> { fail("oversized response"); return null; });
    assertThrows(IOException.class, () -> oversized.body().readAllBytes());
    assertThrows(IOException.class, () -> oversized.body().read());
    assertEquals(0, oversized.body().read(new byte[0]));
    assertNoStagedFiles();
  }

  @Test void publicationContextRequiresItsOwnTargetAndTransactionAndIsCleared() {
    RequestContextHolder.resetRequestAttributes(); // Cache publication runs outside the client thread.
    var snapshot = repositories.findById(1L).orElseThrow();
    try (var publication = new ProxyCachePublication(repositories, assets, snapshot, Instant.EPOCH, "pkg.bin", "127.0.0.9")) {
      assertEquals("127.0.0.9", ProxyRequestAudit.currentClientIp());
      assertThrows(IllegalStateException.class, () -> ProxyCachePublication.check(runtime, "other.bin"));
      assertThrows(IllegalStateException.class, () -> ProxyCachePublication.check(runtime, "pkg.bin"));
      assertThrows(IllegalStateException.class, () -> new ProxyCachePublication(
          repositories, assets, snapshot, Instant.EPOCH, "pkg.bin", null));
    }
    assertNull(ProxyCachePublication.clientIp());
    assertDoesNotThrow(() -> ProxyCachePublication.check(runtime, "other.bin"));
  }

  @Test void contentRangesValidateSuffixBoundsAndNumericOverflow() {
    for (String range : java.util.List.of("bytes=-100", "bytes=100-", "bytes=100-999")) {
      assertTrue(ProgressiveProxyService.validContentRange(range, new HttpRemoteFetcher.Result(206,
          Map.of("Content-Length", "100", "Content-Range", "bytes 100-199/200"), InputStream.nullInputStream())));
    }
    for (String value : java.util.List.of("bytes 0-200/200", "bytes 5-4/200", "bytes 0-2/*", "garbage",
        "bytes 999999999999999999999-999999999999999999999/999999999999999999999")) {
      assertFalse(ProgressiveProxyService.validContentRange("bytes=0-", new HttpRemoteFetcher.Result(206,
          Map.of("Content-Length", "200", "Content-Range", value), InputStream.nullInputStream())));
    }
    assertFalse(ProgressiveProxyService.validContentRange("bytes=0-", upstream(content, content.length)));
  }

  private void awaitCleanup() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      try (var files = Files.list(directory)) { if (files.findAny().isEmpty()) return; }
      Thread.sleep(10);
    }
    assertNoStagedFiles();
  }

  private MavenResponse fetch(String checksum, ProgressiveProxyService.Validator validator,
      HttpRemoteFetcher.ResultHandler<MavenResponse> handler) throws IOException {
    return ProgressiveProxyService.fetchMaven(service, fetcher, request, "pkg.bin", runtime,
        true, checksum, validator, handler);
  }

  private static HttpRemoteFetcher.Result upstream(byte[] bytes, long length) {
    return new HttpRemoteFetcher.Result(200, Map.of("Content-Length", Long.toString(length),
        "Content-Type", "application/octet-stream"), new ByteArrayInputStream(bytes));
  }

  private void assertNoStagedFiles() throws IOException {
    try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
  }
}
