package com.github.klboke.kkrepo.server.pub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.pub.PubContentTypes;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PubProxyServiceTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
  };

  @Test
  void metadataResponseRewritesUpstreamArchiveUrlsToProxyRepositoryUrls() throws Exception {
    TestPubProxyService service = new TestPubProxyService(Map.of(
        "name", "example_package",
        "advisoriesUpdated", "2026-07-08T00:00:00Z",
        "latest", Map.of(
            "version", "1.0.0",
            "archive_url", "https://pub.dev/packages/example_package/versions/1.0.0.tar.gz"),
        "versions", List.of(Map.of(
            "version", "1.0.0",
            "archive_url", "https://pub.dev/packages/example_package/versions/1.0.0.tar.gz",
            "archive_sha256", "abc"))));

    MavenResponse response =
        service.packageMetadata(runtime(), "example_package", "https://repo.test/repository/pub-proxy", false);

    Map<String, Object> body = readJson(response);
    String serialized = MAPPER.writeValueAsString(body);
    assertFalse(serialized.contains("https://pub.dev/packages"));
    assertFalse(body.containsKey("advisoriesUpdated"));
    Map<?, ?> latest = (Map<?, ?>) body.get("latest");
    assertEquals("https://repo.test/repository/pub-proxy/api/archives/example_package-1.0.0.tar.gz",
        latest.get("archive_url"));
    Map<?, ?> version = (Map<?, ?>) ((List<?>) body.get("versions")).get(0);
    assertEquals("abc", version.get("archive_sha256"));
  }

  @Test
  void versionJsonKeepsUpstreamArchiveUrlAndUsesGenericJsonContentType() throws Exception {
    TestPubProxyService service = new TestPubProxyService(Map.of(
        "name", "example_package",
        "versions", List.of(Map.of(
            "version", "1.0.0",
            "archive_url", "https://pub.dev/api/archives/example_package-1.0.0.tar.gz",
            "archive_sha256", "abc"))));

    MavenResponse response = service.versionJson(runtime(), "example_package", "1.0.0", false);

    assertEquals(PubContentTypes.VERSION_JSON, response.contentType());
    Map<String, Object> body = readJson(response);
    assertEquals("https://pub.dev/api/archives/example_package-1.0.0.tar.gz", body.get("archive_url"));
    assertEquals("abc", body.get("archive_sha256"));
  }

  @Test
  void metadataFallbackLatestPrefersStableVersion() throws Exception {
    TestPubProxyService service = new TestPubProxyService(Map.of(
        "name", "example_package",
        "versions", List.of(
            Map.of(
                "version", "1.0.0",
                "archive_url", "https://pub.dev/api/archives/example_package-1.0.0.tar.gz"),
            Map.of(
                "version", "2.0.0-dev.1",
                "archive_url", "https://pub.dev/api/archives/example_package-2.0.0-dev.1.tar.gz"))));

    MavenResponse response =
        service.packageMetadata(runtime(), "example_package", "https://repo.test/repository/pub-proxy", false);

    Map<String, Object> body = readJson(response);
    assertEquals("1.0.0", ((Map<?, ?>) body.get("latest")).get("version"));
  }

  @Test
  void archiveRemoteAttrsRecordChecksumSource() {
    TestPubProxyService service = new TestPubProxyService(Map.of());

    Map<String, String> verified = service.archiveRemoteAttrs(
        new HttpRemoteFetcher.Result(200, Map.of(
            "ETag", "\"etag-1\"",
            "Content-Type", "application/octet-stream"),
            InputStream.nullInputStream()),
        "ABCDEF");
    assertEquals("archive_sha256", verified.get("pubChecksumSource"));
    assertEquals("abcdef", verified.get("remoteArchiveSha256"));
    assertEquals("etag-1", verified.get("remoteEtag"));

    Map<String, String> computedOnly = service.archiveRemoteAttrs(
        new HttpRemoteFetcher.Result(200, Map.of(), InputStream.nullInputStream()),
        null);
    assertEquals("computed-only", computedOnly.get("pubChecksumSource"));
    assertEquals("true", computedOnly.get("remoteArchiveSha256Missing"));
  }

  @Test
  void freshProxyArchiveHeadIsPolicyCheckedBeforeResponse() throws Exception {
    AssetDao assetDao = mock(AssetDao.class);
    BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    PubAssetWriter writer = mock(PubAssetWriter.class);
    PubAssetReader reader = mock(PubAssetReader.class);
    ProxyStateDao proxyStateDao = mock(ProxyStateDao.class);
    HttpRemoteFetcher fetcher = mock(HttpRemoteFetcher.class);
    ProxyNegativeCache negativeCache = mock(ProxyNegativeCache.class);
    AssetMetadataCache cache = mock(AssetMetadataCache.class);
    BlobStorage storage = mock(BlobStorage.class);
    RepositoryRuntime runtime = runtime();
    TestPubProxyService service = new TestPubProxyService(
        Map.of(
            "name", "example_package",
            "versions", List.of(Map.of(
                "version", "1.0.0",
                "archive_url", "https://pub.dev/api/archives/example_package-1.0.0.tar.gz"))),
        assetDao,
        registry,
        writer,
        reader,
        proxyStateDao,
        fetcher,
        negativeCache,
        cache);
    PubAssetWriter.Stored stored = storedArchive();
    when(registry.forBlobStoreId(1L)).thenReturn(storage);
    when(writer.writeArchive(
        eq(runtime),
        eq(storage),
        eq(1L),
        any(InputStream.class),
        eq("example_package"),
        eq("1.0.0"),
        isNull(),
        any(),
        any(),
        eq("proxy"),
        eq(runtime.proxyRemoteUrl()),
        eq(true),
        eq(false)))
        .thenReturn(stored);
    doAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      HttpRemoteFetcher.ResultHandler<MavenResponse> handler = invocation.getArgument(2);
      return handler.handle(new HttpRemoteFetcher.Result(
          200,
          Map.of("Content-Type", PubContentTypes.ARCHIVE),
          new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8))));
    }).when(fetcher).fetchWithBodyRetry(any(), anyString(), any());

    MavenResponse response = service.download(runtime, "example_package", "1.0.0", true);

    assertEquals(200, response.status());
    verify(reader).beforeRead(
        stored.asset().id(), stored.blob().id(), stored.asset().repositoryId());
  }

  private static Map<String, Object> readJson(MavenResponse response) throws IOException {
    try (var body = response.body()) {
      return MAPPER.readValue(body, JSON_MAP);
    }
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        2L,
        "pub-proxy",
        RepositoryFormat.PUB,
        RepositoryType.PROXY,
        "pub-proxy",
        true,
        1L,
        "ALLOW_ONCE",
        null,
        null,
        true,
        "https://pub.dev/",
        1440,
        1440,
        List.of());
  }

  private static PubAssetWriter.Stored storedArchive() {
    AssetRecord asset = new AssetRecord(
        1L,
        2L,
        null,
        2L,
        RepositoryFormat.PUB,
        "api/archives/example_package-1.0.0.tar.gz",
        null,
        "example_package-1.0.0.tar.gz",
        "archive",
        PubContentTypes.ARCHIVE,
        4L,
        null,
        Instant.EPOCH,
        Map.of());
    AssetBlobRecord blob = new AssetBlobRecord(
        2L,
        1L,
        "blob://bucket/object",
        null,
        "object",
        null,
        "sha1",
        "sha256",
        "md5",
        4L,
        PubContentTypes.ARCHIVE,
        "proxy",
        "upstream",
        Instant.EPOCH,
        Instant.EPOCH,
        Map.of());
    return new PubAssetWriter.Stored(
        asset,
        blob,
        new PubAssetWriter.Digests("md5", "sha1", "sha256", "sha512", 4L),
        true,
        null);
  }

  private static final class TestPubProxyService extends PubProxyService {
    private final Map<String, Object> body;

    TestPubProxyService(Map<String, Object> body) {
      super(null, null, null, null, null, null, null, null, MAPPER);
      this.body = body;
    }

    TestPubProxyService(
        Map<String, Object> body,
        AssetDao assetDao,
        BlobStorageRegistry registry,
        PubAssetWriter writer,
        PubAssetReader reader,
        ProxyStateDao proxyStateDao,
        HttpRemoteFetcher fetcher,
        ProxyNegativeCache negativeCache,
        AssetMetadataCache cache) {
      super(
          assetDao,
          registry,
          writer,
          reader,
          proxyStateDao,
          fetcher,
          negativeCache,
          cache,
          MAPPER);
      this.body = body;
    }

    @Override
    CachedMetadata cachedOrFetchedMetadata(RepositoryRuntime runtime, String packageName, Instant now) {
      return new CachedMetadata(body, "etag", Instant.parse("2026-07-08T00:00:00Z"));
    }
  }
}
