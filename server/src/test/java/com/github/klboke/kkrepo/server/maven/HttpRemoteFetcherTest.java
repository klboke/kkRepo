package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.security.OutboundRequestPolicy;
import com.github.klboke.kkrepo.server.security.SecurityValidationException;
import org.junit.jupiter.api.Test;

class HttpRemoteFetcherTest {

  @Test
  void httpVersionDefaultsToHttp11() {
    assertEquals(HttpClient.Version.HTTP_1_1, HttpRemoteFetcher.httpVersion(null));
    assertEquals(HttpClient.Version.HTTP_1_1, HttpRemoteFetcher.httpVersion(""));
    assertEquals(HttpClient.Version.HTTP_1_1, HttpRemoteFetcher.httpVersion("HTTP_1_1"));
    assertEquals(HttpClient.Version.HTTP_1_1, HttpRemoteFetcher.httpVersion("HTTP/1.1"));
  }

  @Test
  void httpVersionAllowsHttp2OptIn() {
    assertEquals(HttpClient.Version.HTTP_2, HttpRemoteFetcher.httpVersion("HTTP_2"));
    assertEquals(HttpClient.Version.HTTP_2, HttpRemoteFetcher.httpVersion("http2"));
    assertEquals(HttpClient.Version.HTTP_2, HttpRemoteFetcher.httpVersion("2"));
  }

  @Test
  void requestTimeoutUsesSharedProxyDefaults() {
    HttpRemoteFetcher fetcher = new HttpRemoteFetcher(null, null, "HTTP_1_1", 11, 22, 33, 7, 1);

    assertEquals(
        Duration.ofSeconds(11),
        fetcher.requestTimeout(new HttpRemoteFetcher.Request(
            "https://repo.example/-/v1/search", null, null, null, false)
            .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.SEARCH)));
    assertEquals(
        Duration.ofSeconds(22),
        fetcher.requestTimeout(new HttpRemoteFetcher.Request(
            "https://repo.example/maven-metadata.xml", null, null, null, false)
            .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.METADATA)));
    assertEquals(
        Duration.ofSeconds(33),
        fetcher.requestTimeout(new HttpRemoteFetcher.Request(
            "https://repo.example/artifact.jar", null, null, null, false)
            .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.CONTENT)));
    assertEquals(
        Duration.ofSeconds(33),
        fetcher.requestTimeout(new HttpRemoteFetcher.Request(
            "https://repo.example/artifact.jar", null, null, null, false)));
    assertEquals(
        Duration.ofSeconds(7),
        fetcher.requestTimeout(new HttpRemoteFetcher.Request(
            "https://repo.example/artifact.jar", null, null, null, true)));
    assertEquals(
        Duration.ofSeconds(9),
        fetcher.requestTimeout(new HttpRemoteFetcher.Request(
            "https://repo.example/artifact.jar", null, null, Duration.ofSeconds(9), false)));
  }

  @Test
  void requestUriMustPassOutboundPolicyValidation() {
    HttpRemoteFetcher.Request request = HttpRemoteFetcher.Request.get("http://127.0.0.1/artifact.jar");

    assertThrows(
        SecurityValidationException.class,
        () -> request.validatedUri(new OutboundRequestPolicy(false, ""), "remote fetch"));
  }

  @Test
  void requestWithoutTrustedHostDoesNotPinOutboundHost() {
    HttpRemoteFetcher.Request request = HttpRemoteFetcher.Request.get("https://repo.example.com/artifact.jar");

    assertEquals("repo.example.com",
        request.validatedUri(new OutboundRequestPolicy(false, "repo.example.com"), "remote fetch").getHost());
  }

  @Test
  void requestUriMustRemainOnRepositoryRemoteHost() {
    RepositoryRuntime runtime = new RepositoryRuntime(
        1,
        "maven-proxy",
        RepositoryFormat.MAVEN2,
        RepositoryType.PROXY,
        "maven2-proxy",
        true,
        1L,
        null,
        "RELEASE",
        "STRICT",
        true,
        "https://repo.example.com/maven2",
        1440,
        1440,
        List.of());
    HttpRemoteFetcher.Request trusted = HttpRemoteFetcher.Request
        .get("https://repo.example.com/maven2/com/example/app.jar")
        .withRepository(runtime);
    HttpRemoteFetcher.Request tampered = new HttpRemoteFetcher.Request(
        "https://evil.example.com/maven2/com/example/app.jar",
        trusted.etag(),
        trusted.lastModified(),
        trusted.timeout(),
        trusted.timeoutProfile(),
        trusted.headOnly(),
        trusted.repository(),
        trusted.format(),
        trusted.trustedHost());

    OutboundRequestPolicy policy = new OutboundRequestPolicy(false, "repo.example.com,evil.example.com");
    assertEquals("repo.example.com", trusted.validatedUri(policy, "remote fetch").getHost());
    SecurityValidationException error = assertThrows(
        SecurityValidationException.class,
        () -> tampered.validatedUri(policy, "remote fetch"));
    assertEquals("remote fetch URL host must remain repo.example.com", error.getMessage());
  }

  @Test
  void remoteAuthorizationIsPinnedToRepositoryRemoteOrigin() {
    RepositoryRuntime runtime = runtime("robot", "secret", null);

    HttpRemoteFetcher.Request httpSameHost = HttpRemoteFetcher.Request
        .get("http://repo.example.com/maven2/com/example/app.jar")
        .withRepository(runtime);
    HttpRemoteFetcher.Request differentPort = HttpRemoteFetcher.Request
        .get("https://repo.example.com:8443/maven2/com/example/app.jar")
        .withRepository(runtime);
    HttpRemoteFetcher.Request sameOrigin = HttpRemoteFetcher.Request
        .get("https://repo.example.com/maven2/com/example/app.jar")
        .withRepository(runtime);

    assertNull(httpSameHost.authorizationHeader());
    assertNull(differentPort.authorizationHeader());
    assertNotNull(sameOrigin.authorizationHeader());
  }

  @Test
  void redirectAuthorizationRequiresSameOrigin() {
    HttpRemoteFetcher.Request request = HttpRemoteFetcher.Request
        .get("https://repo.example.com/maven2/com/example/app.jar")
        .withRepository(runtime("robot", "secret", null));
    URI current = URI.create("https://repo.example.com/maven2/com/example/app.jar");

    assertEquals(
        request.authorizationHeader(),
        request.authorizationHeaderForRedirect(current, URI.create("https://repo.example.com/maven2/redirect.jar")));
    assertThrows(
        SecurityValidationException.class,
        () -> request.authorizationHeaderForRedirect(current, URI.create("https://repo.example.com:8443/maven2/redirect.jar")));
    assertThrows(
        SecurityValidationException.class,
        () -> request.authorizationHeaderForRedirect(current, URI.create("http://repo.example.com/maven2/redirect.jar")));
  }

  @Test
  void requestWithRepositoryAddsBasicRemoteAuthorizationWhenConfigured() {
    RepositoryRuntime runtime = runtime("robot", "secret", null);

    HttpRemoteFetcher.Request request = HttpRemoteFetcher.Request
        .get("https://repo.example.com/maven2/com/example/app.jar")
        .withRepository(runtime);

    String encoded = Base64.getEncoder().encodeToString("robot:secret".getBytes(StandardCharsets.UTF_8));
    assertEquals("Basic " + encoded, request.authorizationHeader());
  }

  @Test
  void requestWithRepositoryPrefersBearerRemoteAuthorizationWhenConfigured() {
    RepositoryRuntime runtime = runtime("robot", "secret", "upstream-token");

    HttpRemoteFetcher.Request request = HttpRemoteFetcher.Request
        .get("https://repo.example.com/maven2/com/example/app.jar")
        .withRepository(runtime);

    assertEquals("Bearer upstream-token", request.authorizationHeader());
  }

  @Test
  void requestWithRepositoryOnlyAddsAuthorizationForPinnedRemoteHost() {
    RepositoryRuntime runtime = runtime("robot", "secret", null);

    HttpRemoteFetcher.Request differentHost = HttpRemoteFetcher.Request
        .get("https://static.example.com/crates/demo/1.0.0/download")
        .withRepository(runtime);
    HttpRemoteFetcher.Request suppressed = HttpRemoteFetcher.Request
        .get("https://repo.example.com/maven2/com/example/app.jar")
        .withRepository(runtime, false);

    assertNull(differentHost.trustedHost());
    assertNull(differentHost.authorizationHeader());
    assertEquals("repo.example.com", suppressed.trustedHost());
    assertNull(suppressed.authorizationHeader());
  }

  @Test
  void repositoryRequestsCanDropAuthorizationForAllowedUnsignedCrossOriginRedirects() {
    RepositoryRuntime runtime = runtime("robot", "secret", null);
    HttpRemoteFetcher.Request request = HttpRemoteFetcher.Request
        .get("https://repo.example.com/maven2/com/example/app.jar")
        .withRepositoryAllowingUnsignedRedirects(runtime, true, Set.of("storage.example.net"));
    URI current = URI.create("https://repo.example.com/maven2/com/example/app.jar");
    URI storage = URI.create("https://storage.example.net/app.jar");

    assertEquals("repo.example.com", request.trustedHost());
    assertNotNull(request.authorizationHeader());
    assertEquals(
        request.authorizationHeader(),
        request.authorizationHeaderForRedirect(current, URI.create("https://repo.example.com/maven2/redirect.jar")));
    assertNull(request.authorizationHeaderForRedirect(current, storage));
    assertEquals("storage.example.net", request.trustedHostForRedirect(current, storage));
  }

  @Test
  void repositoryRequestsRejectUnsignedCrossOriginRedirectsOutsideAllowlist() {
    RepositoryRuntime runtime = runtime("robot", "secret", null);
    HttpRemoteFetcher.Request request = HttpRemoteFetcher.Request
        .get("https://repo.example.com/maven2/com/example/app.jar")
        .withRepositoryAllowingUnsignedRedirects(runtime, true, Set.of("storage.example.net"));
    URI current = URI.create("https://repo.example.com/maven2/com/example/app.jar");

    SecurityValidationException error = assertThrows(
        SecurityValidationException.class,
        () -> request.authorizationHeaderForRedirect(current, URI.create("https://evil.example.net/app.jar")));
    assertEquals("remote redirect URL host is not allowed: evil.example.net", error.getMessage());
  }

  @Test
  void bodyReadFailureRetriesFreshGet() throws Exception {
    SequencedFetcher fetcher = new SequencedFetcher(
        result("first"),
        result("second"));

    String body = fetcher.fetchWithBodyRetry(
        HttpRemoteFetcher.Request.get("https://repo.example/artifact.jar"),
        "artifact.jar",
        result -> {
          if (fetcher.calls == 1) {
            throw new UpstreamBodyReadException(new EOFException("early EOF"));
          }
          return new String(result.body().readAllBytes(), StandardCharsets.UTF_8);
        });

    assertEquals("second", body);
    assertEquals(2, fetcher.calls);
  }

  @Test
  void handlerIoFailureIsNotBodyRetried() {
    SequencedFetcher fetcher = new SequencedFetcher(result("{bad-json"), result("{}"));
    IOException failure = new IOException("bad JSON");

    IOException thrown = assertThrows(IOException.class, () -> fetcher.fetchWithBodyRetry(
        HttpRemoteFetcher.Request.get("https://repo.example/-/v1/search"),
        "-/v1/search",
        result -> {
          throw failure;
        }));

    assertSame(failure, thrown);
    assertEquals(1, fetcher.calls);
  }

  @Test
  void uncheckedStorageFailureIsNotBodyRetried() {
    SequencedFetcher fetcher = new SequencedFetcher(result("artifact"), result("retry"));
    UncheckedIOException failure = new UncheckedIOException("Failed to upload file to S3", new IOException("s3"));

    UncheckedIOException thrown = assertThrows(UncheckedIOException.class, () -> fetcher.fetchWithBodyRetry(
        HttpRemoteFetcher.Request.get("https://repo.example/artifact.jar"),
        "artifact.jar",
        result -> {
          throw failure;
        }));

    assertSame(failure, thrown);
    assertEquals(1, fetcher.calls);
  }

  private static HttpRemoteFetcher.Result result(String body) {
    return new HttpRemoteFetcher.Result(
        200,
        Map.of(),
        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
  }

  private static RepositoryRuntime runtime(String username, String password, String bearerToken) {
    return new RepositoryRuntime(
        1,
        "maven-proxy",
        RepositoryFormat.MAVEN2,
        RepositoryType.PROXY,
        "maven2-proxy",
        true,
        1L,
        null,
        "RELEASE",
        "STRICT",
        true,
        "https://repo.example.com/maven2",
        1440,
        1440,
        null,
        username,
        password,
        bearerToken,
        null,
        null,
        null,
        null,
        null,
        List.of());
  }

  private static class SequencedFetcher extends HttpRemoteFetcher {
    private final Queue<HttpRemoteFetcher.Result> results = new ArrayDeque<>();
    private int calls;

    SequencedFetcher(HttpRemoteFetcher.Result... results) {
      super(null);
      for (HttpRemoteFetcher.Result result : results) {
        this.results.add(result);
      }
    }

    @Override
    public HttpRemoteFetcher.Result fetch(HttpRemoteFetcher.Request req) {
      calls++;
      return results.isEmpty()
          ? new HttpRemoteFetcher.Result(500, Map.of(), InputStream.nullInputStream())
          : results.remove();
    }
  }
}
