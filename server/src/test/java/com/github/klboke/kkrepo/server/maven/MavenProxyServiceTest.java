package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPath;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import com.github.klboke.kkrepo.server.support.dao.AssetDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.ProxyStateDaoAdapter;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class MavenProxyServiceTest {
  private static final String REMOTE = "http://127.0.0.1:8080/repository/maven-public";
  private static final MavenPathParser PARSER = new MavenPathParser();

  @Test
  void headMissUsesRemoteHeadAndReturnsNotFound() {
    CapturingFetcher fetcher = new CapturingFetcher(new HttpRemoteFetcher.Result(
        404, Map.of(), InputStream.nullInputStream()));
    RecordingProxyStateDao proxyState = new RecordingProxyStateDao();
    MavenProxyService service = service(fetcher, proxyState);

    MavenExceptions.MavenNotFoundException error = assertThrows(
        MavenExceptions.MavenNotFoundException.class,
        () -> service.get(runtime(), path("org/gradle/kotlin/missing/missing-1.0.jar"), true));

    assertEquals("org/gradle/kotlin/missing/missing-1.0.jar", error.getMessage());
    assertNotNull(fetcher.request);
    assertTrue(fetcher.request.headOnly());
    assertNull(fetcher.request.timeout());
    assertEquals(
        REMOTE + "/org/gradle/kotlin/missing/missing-1.0.jar",
        fetcher.request.url());
    assertEquals(1, proxyState.successCount);
    assertEquals(0, proxyState.failureCount);
  }

  @Test
  void headHitReturnsRemoteHeadersWithoutWritingBlob() {
    CapturingFetcher fetcher = new CapturingFetcher(new HttpRemoteFetcher.Result(
        200,
        Map.of(
            "Content-Length", "47706",
            "Content-Type", "binary/octet-stream",
            "ETag", "\"fb9532624e7bf327a6c6249c59b64d377e0a2866\"",
            "Last-Modified", "Mon, 25 May 2026 07:24:48 GMT"),
        InputStream.nullInputStream()));
    RecordingProxyStateDao proxyState = new RecordingProxyStateDao();
    MavenProxyService service = service(fetcher, proxyState);

    MavenResponse response = service.get(
        runtime(),
        path("org/gradle/kotlin/gradle-kotlin-dsl-plugins/4.2.1/gradle-kotlin-dsl-plugins-4.2.1.jar"),
        true);

    assertNotNull(fetcher.request);
    assertTrue(fetcher.request.headOnly());
    assertNull(fetcher.request.timeout());
    assertEquals(200, response.status());
    assertEquals(47706, response.contentLength());
    assertEquals("binary/octet-stream", response.contentType());
    assertEquals("fb9532624e7bf327a6c6249c59b64d377e0a2866", response.etag());
    assertEquals(Instant.parse("2026-05-25T07:24:48Z"), response.lastModified());
    assertEquals(1, proxyState.successCount);
    assertEquals(0, proxyState.failureCount);
  }

  @Test
  void getMissServesPersistedTempFileWithoutReadingBlobStore() throws Exception {
    byte[] upstream = "artifact-bytes".getBytes(StandardCharsets.UTF_8);
    CapturingFetcher fetcher = new CapturingFetcher(new HttpRemoteFetcher.Result(
        200, Map.of("Content-Type", "application/java-archive"), new java.io.ByteArrayInputStream(upstream)));
    RecordingProxyStateDao proxyState = new RecordingProxyStateDao();
    CountingBlobStorage storage = new CountingBlobStorage();
    TempFileWriter writer = new TempFileWriter();
    MavenProxyService service = new MavenProxyService(
        new EmptyAssetDao(),
        new FixedBlobStorageRegistry(storage),
        writer,
        proxyState,
        fetcher,
        null,
        new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0));

    MavenResponse response = service.get(
        runtime(),
        path("com/acme/demo/1.0.0/demo-1.0.0.jar"),
        false);

    assertEquals(200, response.status());
    assertEquals("application/java-archive", response.contentType());
    try (InputStream body = response.body()) {
      assertArrayEquals(upstream, body.readAllBytes());
    }
    assertTrue(writer.keepResponseFile);
    assertEquals(0, storage.getCalls);
    assertEquals(1, proxyState.successCount);
    assertNotNull(fetcher.request);
    assertNull(fetcher.request.timeout());
  }

  @Test
  void getBodyReadFailureRetriesFreshUpstreamGet() throws Exception {
    byte[] upstream = "artifact-bytes".getBytes(StandardCharsets.UTF_8);
    SequencedFetcher fetcher = new SequencedFetcher(
        new HttpRemoteFetcher.Result(
            200,
            Map.of("Content-Type", "application/java-archive"),
            new java.io.ByteArrayInputStream("truncated".getBytes(StandardCharsets.UTF_8))),
        new HttpRemoteFetcher.Result(
            200,
            Map.of("Content-Type", "application/java-archive"),
            new java.io.ByteArrayInputStream(upstream)));
    RecordingProxyStateDao proxyState = new RecordingProxyStateDao();
    CountingBlobStorage storage = new CountingBlobStorage();
    FlakyBodyIoWriter writer = new FlakyBodyIoWriter(1);
    MavenProxyService service = new MavenProxyService(
        new EmptyAssetDao(),
        new FixedBlobStorageRegistry(storage),
        writer,
        proxyState,
        fetcher,
        null,
        new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0));

    MavenResponse response = service.get(
        runtime(),
        path("com/acme/demo/1.0.0/demo-1.0.0.jar"),
        false);

    assertEquals(200, response.status());
    try (InputStream body = response.body()) {
      assertArrayEquals(upstream, body.readAllBytes());
    }
    assertEquals(2, fetcher.calls);
    assertNull(fetcher.request.timeout());
    assertEquals(1, proxyState.successCount);
    assertEquals(0, proxyState.failureCount);
  }

  @Test
  void bodyReadFailureServesStaleCacheAfterRetryExhausted() throws Exception {
    MavenPath path = path("com/acme/legacy/1.0.0/legacy-1.0.0.jar");
    byte[] cachedBytes = "cached-artifact".getBytes(StandardCharsets.UTF_8);
    Instant old = Instant.parse("2020-01-01T00:00:00Z");
    AssetBlobRecord blob = new AssetBlobRecord(
        99L, 1L, "s3://bucket/key", new byte[32], "key", new byte[32],
        "sha1", "sha256", "md5", (long) cachedBytes.length, "application/java-archive",
        "proxy", REMOTE, old, old, Map.of());
    AssetRecord asset = new AssetRecord(
        88L, runtime().id(), null, blob.id(), RepositoryFormat.MAVEN2, path.path(), new byte[32],
        path.fileName(), "artifact", "application/java-archive", (long) cachedBytes.length, null, old, Map.of());
    SequencedFetcher fetcher = new SequencedFetcher(
        new HttpRemoteFetcher.Result(
            200,
            Map.of("Content-Type", "application/java-archive"),
            new java.io.ByteArrayInputStream("first".getBytes(StandardCharsets.UTF_8))),
        new HttpRemoteFetcher.Result(
            200,
            Map.of("Content-Type", "application/java-archive"),
            new java.io.ByteArrayInputStream("second".getBytes(StandardCharsets.UTF_8))));
    RecordingProxyStateDao proxyState = new RecordingProxyStateDao();
    ByteBlobStorage storage = new ByteBlobStorage(cachedBytes);
    MavenProxyService service = new MavenProxyService(
        new CachedAssetDao(asset, blob),
        new FixedBlobStorageRegistry(storage),
        new FlakyBodyIoWriter(2),
        proxyState,
        fetcher,
        null,
        new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0));

    MavenResponse response = service.get(runtime(), path, false);

    assertEquals(200, response.status());
    try (InputStream body = response.body()) {
      assertArrayEquals(cachedBytes, body.readAllBytes());
    }
    assertEquals(2, fetcher.calls);
    assertEquals(0, proxyState.successCount);
    assertEquals(1, proxyState.failureCount);
    assertEquals(1, storage.getCalls);
  }

  @Test
  void repeatedNotFoundUsesSharedNegativeCacheWithoutRemoteFetch() {
    CapturingFetcher fetcher = new CapturingFetcher(new HttpRemoteFetcher.Result(
        404, Map.of(), InputStream.nullInputStream()));
    RecordingProxyStateDao proxyState = new RecordingProxyStateDao();
    MavenProxyService service = new MavenProxyService(
        new EmptyAssetDao(),
        null,
        new FailingWriter(),
        proxyState,
        fetcher,
        new ProxyNegativeCache(new InMemorySharedCache(), true, 1440),
        new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0));
    MavenPath missing = path("org/example/missing/1.0.0/missing-1.0.0.module");

    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(runtime(), missing, false));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(runtime(), missing, false));

    assertEquals(1, fetcher.calls);
    assertEquals(1, proxyState.successCount);
  }

  @Test
  void staleCachedAssetIsReturnedWhenRemoteReturnsNotFound() throws Exception {
    MavenPath path = path("com/acme/legacy/1.0.0/legacy-1.0.0.jar");
    byte[] cachedBytes = "cached-artifact".getBytes(StandardCharsets.UTF_8);
    Instant old = Instant.parse("2020-01-01T00:00:00Z");
    AssetBlobRecord blob = new AssetBlobRecord(
        99L, 1L, "s3://bucket/key", new byte[32], "key", new byte[32],
        "sha1", "sha256", "md5", (long) cachedBytes.length, "application/java-archive",
        "proxy", REMOTE, old, old, Map.of());
    AssetRecord asset = new AssetRecord(
        88L, runtime().id(), null, blob.id(), RepositoryFormat.MAVEN2, path.path(), new byte[32],
        path.fileName(), "artifact", "application/java-archive", (long) cachedBytes.length, null, old, Map.of());
    CapturingFetcher fetcher = new CapturingFetcher(new HttpRemoteFetcher.Result(
        404, Map.of(), InputStream.nullInputStream()));
    RecordingProxyStateDao proxyState = new RecordingProxyStateDao();
    ByteBlobStorage storage = new ByteBlobStorage(cachedBytes);
    MavenProxyService service = new MavenProxyService(
        new CachedAssetDao(asset, blob),
        new FixedBlobStorageRegistry(storage),
        new FailingWriter(),
        proxyState,
        fetcher,
        null,
        new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0));

    MavenResponse response = service.get(runtime(), path, false);

    assertEquals(200, response.status());
    try (InputStream body = response.body()) {
      assertArrayEquals(cachedBytes, body.readAllBytes());
    }
    assertEquals(1, fetcher.calls);
    assertEquals(1, proxyState.successCount);
    assertEquals(0, proxyState.failureCount);
    assertEquals(1, storage.getCalls);
  }

  private static MavenProxyService service(CapturingFetcher fetcher, RecordingProxyStateDao proxyState) {
    return new MavenProxyService(
        new EmptyAssetDao(),
        null,
        new FailingWriter(),
        proxyState,
        fetcher,
        null,
        new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0));
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        10,
        "maven-public",
        RepositoryFormat.MAVEN2,
        RepositoryType.PROXY,
        "maven2-proxy",
        true,
        1L,
        null,
        "MIXED",
        "PERMISSIVE",
        true,
        REMOTE,
        1440,
        1440,
        true, null, List.of());
  }

  private static MavenPath path(String rawPath) {
    return PARSER.parsePath(rawPath);
  }

  private static class EmptyAssetDao extends AssetDaoAdapter {
    EmptyAssetDao() {
      super(null, null);
    }

    @Override
    public Optional<AssetRecord> findAssetByPath(long repositoryId, String path) {
      return Optional.empty();
    }
  }

  private static class CachedAssetDao extends EmptyAssetDao {
    private final AssetRecord asset;
    private final AssetBlobRecord blob;

    CachedAssetDao(AssetRecord asset, AssetBlobRecord blob) {
      this.asset = asset;
      this.blob = blob;
    }

    @Override
    public Optional<AssetRecord> findAssetByPath(long repositoryId, String path) {
      return asset.repositoryId() == repositoryId && asset.path().equals(path)
          ? Optional.of(asset)
          : Optional.empty();
    }

    @Override
    public Optional<AssetBlobRecord> findBlobById(long assetBlobId) {
      return blob.id() == assetBlobId ? Optional.of(blob) : Optional.empty();
    }
  }

  private static class RecordingProxyStateDao extends ProxyStateDaoAdapter {
    int successCount;
    int failureCount;

    RecordingProxyStateDao() {
      super(null, 0);
    }

    @Override
    public boolean isBlocked(long repositoryId, Instant now) {
      return false;
    }

    @Override
    public Optional<ProxyRemoteState> loadState(long repositoryId) {
      return Optional.empty();
    }

    @Override
    public void recordSuccess(long repositoryId, Instant now) {
      successCount++;
    }

    @Override
    public ProxyRemoteState recordFailure(long repositoryId, long blockSeconds, String error, Instant now) {
      failureCount++;
      return new ProxyRemoteState(repositoryId, null, failureCount, null, now, error);
    }
  }

  private static class CapturingFetcher extends HttpRemoteFetcher {
    private final HttpRemoteFetcher.Result result;
    private HttpRemoteFetcher.Request request;
    private int calls;

    CapturingFetcher(HttpRemoteFetcher.Result result) {
      super(null);
      this.result = result;
    }

    @Override
    public HttpRemoteFetcher.Result fetch(HttpRemoteFetcher.Request req) throws IOException {
      calls++;
      request = req;
      return result;
    }
  }

  private static class SequencedFetcher extends HttpRemoteFetcher {
    private final Queue<HttpRemoteFetcher.Result> results = new ArrayDeque<>();
    private HttpRemoteFetcher.Request request;
    private int calls;

    SequencedFetcher(HttpRemoteFetcher.Result... results) {
      super(null);
      for (HttpRemoteFetcher.Result result : results) {
        this.results.add(result);
      }
    }

    @Override
    public HttpRemoteFetcher.Result fetch(HttpRemoteFetcher.Request req) throws IOException {
      calls++;
      request = req;
      if (results.isEmpty()) {
        fail("unexpected upstream fetch");
      }
      return results.remove();
    }
  }

  private static class FixedBlobStorageRegistry extends BlobStorageRegistry {
    private final BlobStorage storage;

    FixedBlobStorageRegistry(BlobStorage storage) {
      super(null, null, null, null, 0L);
      this.storage = storage;
    }

    @Override
    public BlobStorage forBlobStoreId(long blobStoreId) {
      return storage;
    }
  }

  private static class CountingBlobStorage implements BlobStorage {
    int getCalls;

    @Override
    public BlobReference put(String repository, String logicalPath, InputStream content, long size, String sha256) {
      return new BlobReference("bucket", "key", sha256, size);
    }

    @Override
    public Optional<InputStream> get(BlobReference reference) {
      getCalls++;
      return Optional.empty();
    }

    @Override
    public boolean exists(BlobReference reference) {
      return false;
    }

    @Override
    public Optional<BlobObjectMetadata> stat(BlobReference reference) {
      return Optional.empty();
    }

    @Override
    public void delete(BlobReference reference) {
    }
  }

  private static class ByteBlobStorage extends CountingBlobStorage {
    private final byte[] bytes;

    ByteBlobStorage(byte[] bytes) {
      this.bytes = bytes;
    }

    @Override
    public Optional<InputStream> get(BlobReference reference) {
      getCalls++;
      return Optional.of(new java.io.ByteArrayInputStream(bytes));
    }
  }

  private static class TempFileWriter extends MavenAssetWriter {
    boolean keepResponseFile;

    TempFileWriter() {
      super(null, null, null, new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0), null);
    }

    @Override
    public Stored writePrimary(
        RepositoryRuntime runtime,
        BlobStorage storage,
        long blobStoreId,
        MavenPath path,
        InputStream body,
        String contentTypeHint,
        String createdBy,
        String createdByIp,
        Map<String, String> extraBlobAttributes,
        boolean writeChecksumSiblings,
        boolean keepResponseFile) {
      this.keepResponseFile = keepResponseFile;
      try {
        Path tmp = Files.createTempFile("kkrepo-test-", ".bin");
        long size = Files.copy(body, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Instant now = Instant.parse("2026-05-26T00:00:00Z");
        AssetBlobRecord blob = new AssetBlobRecord(
            99L, blobStoreId, "s3://bucket/key", new byte[32], "key", new byte[32],
            "sha1", "sha256", "md5", size, contentTypeHint, createdBy, createdByIp,
            now, now, Map.of());
        AssetRecord asset = new AssetRecord(
            88L, runtime.id(), null, blob.id(), RepositoryFormat.MAVEN2, path.path(), new byte[32],
            path.fileName(), "artifact", contentTypeHint, size, null, now, Map.of());
        return new Stored(asset, blob, new Digests("md5", "sha1", "sha256", "sha512", size),
            true, tmp);
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }
  }

  private static class FlakyBodyIoWriter extends TempFileWriter {
    private int remainingFailures;

    FlakyBodyIoWriter(int remainingFailures) {
      this.remainingFailures = remainingFailures;
    }

    @Override
    public Stored writePrimary(
        RepositoryRuntime runtime,
        BlobStorage storage,
        long blobStoreId,
        MavenPath path,
        InputStream body,
        String contentTypeHint,
        String createdBy,
        String createdByIp,
        Map<String, String> extraBlobAttributes,
        boolean writeChecksumSiblings,
        boolean keepResponseFile) {
      if (remainingFailures > 0) {
        remainingFailures--;
        throw new UpstreamBodyReadException(new EOFException("EOF reached while reading"));
      }
      return super.writePrimary(
          runtime,
          storage,
          blobStoreId,
          path,
          body,
          contentTypeHint,
          createdBy,
          createdByIp,
          extraBlobAttributes,
          writeChecksumSiblings,
          keepResponseFile);
    }
  }

  private static class FailingWriter extends MavenAssetWriter {
    FailingWriter() {
      super(null, null, null, new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0), null);
    }

    @Override
    public Stored writePrimary(
        RepositoryRuntime runtime,
        BlobStorage storage,
        long blobStoreId,
        MavenPath path,
        InputStream body,
        String contentTypeHint,
        String createdBy,
        String createdByIp,
        Map<String, String> extraBlobAttributes,
        boolean writeChecksumSiblings) {
      fail("HEAD proxy requests must not persist remote response bodies");
      return null;
    }
  }
}
