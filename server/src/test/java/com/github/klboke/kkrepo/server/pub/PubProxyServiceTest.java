package com.github.klboke.kkrepo.server.pub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.protocol.pub.PubContentTypes;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.IOException;
import java.io.InputStream;
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

  private static final class TestPubProxyService extends PubProxyService {
    private final Map<String, Object> body;

    TestPubProxyService(Map<String, Object> body) {
      super(null, null, null, null, null, null, null, null, MAPPER);
      this.body = body;
    }

    @Override
    CachedMetadata cachedOrFetchedMetadata(RepositoryRuntime runtime, String packageName, Instant now) {
      return new CachedMetadata(body, "etag", Instant.parse("2026-07-08T00:00:00Z"));
    }
  }
}
