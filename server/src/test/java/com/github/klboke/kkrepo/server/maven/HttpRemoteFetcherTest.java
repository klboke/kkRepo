package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.proxy.OutboundProxyConfig;
import com.github.klboke.kkrepo.server.proxy.ProxiedHttpClientFactory;
import com.github.klboke.kkrepo.server.security.OutboundRequestPolicy;
import com.github.klboke.kkrepo.server.security.SecurityValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class HttpRemoteFetcherTest {

  @Test
  void resultParsesContentLengthDefensively() {
    assertEquals(
        42L,
        new HttpRemoteFetcher.Result(
            200, Map.of("content-length", " 42 "), InputStream.nullInputStream())
            .contentLength());
    assertEquals(
        0L,
        new HttpRemoteFetcher.Result(
            200, Map.of("Content-Length", "-1"), InputStream.nullInputStream())
            .contentLength());
    assertEquals(
        0L,
        new HttpRemoteFetcher.Result(
            200, Map.of("Content-Length", "invalid"), InputStream.nullInputStream())
            .contentLength());
    assertEquals(
        0L,
        new HttpRemoteFetcher.Result(200, Map.of(), InputStream.nullInputStream())
            .contentLength());
  }

  @Test
  void httpVersionDefaultsToHttp11() {
    assertEquals(HttpClient.Version.HTTP_1_1, HttpRemoteFetcher.httpVersion(null));
    assertEquals(HttpClient.Version.HTTP_1_1, HttpRemoteFetcher.httpVersion(""));
    assertEquals(HttpClient.Version.HTTP_1_1, HttpRemoteFetcher.httpVersion("HTTP_1_1"));
    assertEquals(HttpClient.Version.HTTP_1_1, HttpRemoteFetcher.httpVersion("HTTP/1.1"));
  }

  @Test
  void httpVersionRecognizesHttp2ForDowngradeWarning() {
    assertEquals(HttpClient.Version.HTTP_2, HttpRemoteFetcher.httpVersion("HTTP_2"));
    assertEquals(HttpClient.Version.HTTP_2, HttpRemoteFetcher.httpVersion("http2"));
    assertEquals(HttpClient.Version.HTTP_2, HttpRemoteFetcher.httpVersion("2"));

    HttpRemoteFetcher fetcher = new HttpRemoteFetcher(
        null, null, null, "HTTP_2", 11, 22, 33, 7, 1);
    assertEquals(
        Duration.ofSeconds(33),
        fetcher.requestTimeout(HttpRemoteFetcher.Request.get("https://repo.example/artifact.jar")));
  }

  @Test
  void convenienceConstructorCannotExecuteWithoutPinnedTransportFactory() {
    HttpRemoteFetcher fetcher =
        new HttpRemoteFetcher(OutboundRequestPolicy.allowPrivateForTests());

    IllegalStateException error = assertThrows(
        IllegalStateException.class,
        () -> fetcher.fetch(HttpRemoteFetcher.Request.get("http://localhost/artifact.jar")));

    assertEquals("Outbound HTTP client factory is required", error.getMessage());
  }

  @Test
  void requestTimeoutUsesSharedProxyDefaults() {
    HttpRemoteFetcher fetcher = new HttpRemoteFetcher(null, null, null, "HTTP_1_1", 11, 22, 33, 7, 1);

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
    HttpRemoteFetcher.Request request = HttpRemoteFetcher.Request.get("https://localhost/artifact.jar");

    assertEquals("localhost",
        request.validatedUri(new OutboundRequestPolicy(false, "localhost"), "remote fetch").getHost());
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
        "https://localhost/maven2",
        1440,
        1440,
        List.of());
    HttpRemoteFetcher.Request trusted = HttpRemoteFetcher.Request
        .get("https://localhost/maven2/com/example/app.jar")
        .withRepository(runtime);
    HttpRemoteFetcher.Request tampered = new HttpRemoteFetcher.Request(
        "https://127.0.0.1/maven2/com/example/app.jar",
        trusted.etag(),
        trusted.lastModified(),
        trusted.timeout(),
        trusted.timeoutProfile(),
        trusted.headOnly(),
        trusted.repository(),
        trusted.format(),
        trusted.trustedHost());

    OutboundRequestPolicy policy = new OutboundRequestPolicy(false, "localhost,127.0.0.1");
    assertEquals("localhost", trusted.validatedUri(policy, "remote fetch").getHost());
    SecurityValidationException error = assertThrows(
        SecurityValidationException.class,
        () -> tampered.validatedUri(policy, "remote fetch"));
    assertEquals("remote fetch URL host must remain localhost", error.getMessage());
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

  @ParameterizedTest(name = "configured HTTP remote {0} trusts upgraded request {1}")
  @CsvSource({
      "http://repo.example.com/maven2, https://repo.example.com/maven2/app.jar",
      "http://repo.example.com:80/maven2, https://repo.example.com:443/maven2/app.jar",
      "http://repo.example.com:8080/maven2, https://repo.example.com:8080/maven2/app.jar"
  })
  void remoteAuthorizationRecognizesSafeSameHostHttpToHttpsUpgrade(
      String remoteUrl, String requestUrl) {
    HttpRemoteFetcher.Request request = HttpRemoteFetcher.Request
        .get(requestUrl)
        .withRepository(runtime(remoteUrl, "robot", "secret", null));

    assertEquals("repo.example.com", request.trustedHost());
    assertNotNull(request.authorizationHeader());
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
  void credentiallessRepositoryRequestAllowsSameHostHttpToHttpsUpgrade() {
    HttpRemoteFetcher.Request credentialless = HttpRemoteFetcher.Request
        .get("http://repo.example.com/maven2/com/example/app.jar")
        .withRepository(runtime("http://repo.example.com/maven2", null, null, null));
    URI defaultCurrent = URI.create("http://repo.example.com/maven2/com/example/app.jar");
    URI defaultUpgrade = URI.create("https://repo.example.com/maven2/redirect.jar");

    assertNull(credentialless.authorizationHeader());
    assertNull(credentialless.authorizationHeaderForRedirect(defaultCurrent, defaultUpgrade));
    assertEquals("repo.example.com", credentialless.trustedHostForRedirect(defaultCurrent, defaultUpgrade));
  }

  @ParameterizedTest(name = "safe upgrade {0} -> {1}")
  @CsvSource({
      "http://repo.example.com/artifact.jar, https://repo.example.com/artifact.jar",
      "http://repo.example.com:80/artifact.jar, https://repo.example.com:443/artifact.jar",
      "http://repo.example.com:80/artifact.jar, https://repo.example.com/artifact.jar",
      "http://repo.example.com/artifact.jar, https://repo.example.com:443/artifact.jar",
      "http://repo.example.com:8080/artifact.jar, https://repo.example.com:8080/artifact.jar",
      "http://repo.example.com:443/artifact.jar, https://repo.example.com/artifact.jar",
      "http://repo.example.com/artifact.jar, https://repo.example.com:80/artifact.jar",
      "http://REPO.example.com/artifact.jar, https://repo.EXAMPLE.com/artifact.jar"
  })
  void redirectAuthorizationAllowsSafeSameHostHttpToHttpsUpgrade(
      String currentUrl, String redirectedUrl) {
    HttpRemoteFetcher.Request request = HttpRemoteFetcher.Request
        .get(currentUrl)
        .withRepository(runtime(currentUrl, "robot", "secret", null));
    URI current = URI.create(currentUrl);
    URI redirected = URI.create(redirectedUrl);

    assertNotNull(request.authorizationHeader());
    assertEquals(
        request.authorizationHeader(),
        request.authorizationHeaderForRedirect(current, redirected));
    assertEquals("repo.example.com", request.trustedHostForRedirect(current, redirected));
  }

  @ParameterizedTest(name = "unsafe redirect {0} -> {1}")
  @CsvSource({
      "https://repo.example.com/artifact.jar, http://repo.example.com/artifact.jar",
      "http://repo.example.com/artifact.jar, https://cdn.example.com/artifact.jar",
      "http://repo.example.com:8080/artifact.jar, https://repo.example.com/artifact.jar",
      "http://repo.example.com/artifact.jar, https://repo.example.com:8443/artifact.jar",
      "http://repo.example.com:8080/artifact.jar, https://repo.example.com:8443/artifact.jar",
      "https://repo.example.com/artifact.jar, https://repo.example.com:8443/artifact.jar",
      "http://repo.example.com/artifact.jar, ftp://repo.example.com/artifact.jar"
  })
  void redirectAuthorizationRejectsDowngradesCrossHostAndPortChanges(
      String currentUrl, String redirectedUrl) {
    HttpRemoteFetcher.Request request = HttpRemoteFetcher.Request
        .get(currentUrl)
        .withRepository(runtime(currentUrl, "robot", "secret", null));
    URI current = URI.create(currentUrl);
    URI redirected = URI.create(redirectedUrl);

    assertNotNull(request.authorizationHeader());
    assertThrows(
        SecurityValidationException.class,
        () -> request.authorizationHeaderForRedirect(current, redirected));
    assertThrows(
        SecurityValidationException.class,
        () -> request.trustedHostForRedirect(current, redirected));
  }

  @ParameterizedTest(name = "HTTP {0} follows same-host HTTPS upgrade")
  @ValueSource(ints = {301, 302, 303, 307, 308})
  void fetchFollowsSameHostHttpToHttpsUpgradeAndPreservesAuthorization(int redirectStatus)
      throws Exception {
    ProxiedHttpClientFactory transport = mock(ProxiedHttpClientFactory.class);
    List<URI> targets = new ArrayList<>();
    List<String> authorizations = new ArrayList<>();
    when(transport.execute(
        anyString(),
        nullable(OutboundProxyConfig.class),
        eq("GET"),
        any(OutboundRequestPolicy.ResolvedHttpTarget.class),
        anyMap(),
        anyLong())).thenAnswer(invocation -> {
          OutboundRequestPolicy.ResolvedHttpTarget target = invocation.getArgument(3);
          Map<String, String> headers = invocation.getArgument(4);
          targets.add(target.uri());
          authorizations.add(headers.get("Authorization"));
          if ("http".equalsIgnoreCase(target.uri().getScheme())) {
            return response(
                redirectStatus,
                Map.of("Location", "https://localhost/artifact.jar"),
                "");
          }
          return response(200, Map.of(), "upgraded");
        });
    HttpRemoteFetcher fetcher = new HttpRemoteFetcher(
        OutboundRequestPolicy.allowPrivateForTests(), null, transport,
        "HTTP_1_1", 30, 60, 300, 2, 1);
    HttpRemoteFetcher.Request request = HttpRemoteFetcher.Request
        .get("http://localhost/artifact.jar")
        .withRepository(runtime("http://localhost", "robot", "secret", null));

    try (HttpRemoteFetcher.Result result = fetcher.fetch(request)) {
      assertEquals(200, result.status());
      assertEquals("upgraded", new String(result.body().readAllBytes(), StandardCharsets.UTF_8));
    }

    assertEquals(List.of("http", "https"),
        targets.stream().map(URI::getScheme).toList());
    assertEquals(List.of(request.authorizationHeader(), request.authorizationHeader()), authorizations);
  }

  @Test
  void fetchPreservesAuthorizationAcrossUpgradeAndSubsequentSameOriginRedirect() throws Exception {
    ProxiedHttpClientFactory transport = mock(ProxiedHttpClientFactory.class);
    List<URI> targets = new ArrayList<>();
    List<String> authorizations = new ArrayList<>();
    when(transport.execute(
        anyString(),
        nullable(OutboundProxyConfig.class),
        eq("GET"),
        any(OutboundRequestPolicy.ResolvedHttpTarget.class),
        anyMap(),
        anyLong())).thenAnswer(invocation -> {
          OutboundRequestPolicy.ResolvedHttpTarget target = invocation.getArgument(3);
          Map<String, String> headers = invocation.getArgument(4);
          URI uri = target.uri();
          targets.add(uri);
          authorizations.add(headers.get("Authorization"));
          if ("http".equalsIgnoreCase(uri.getScheme())) {
            return response(301, Map.of("Location", "https://localhost/secure/artifact.jar"), "");
          }
          if ("/secure/artifact.jar".equals(uri.getPath())) {
            return response(307, Map.of("Location", "/cdn/artifact.jar"), "");
          }
          return response(200, Map.of(), "redirected");
        });
    HttpRemoteFetcher fetcher = new HttpRemoteFetcher(
        OutboundRequestPolicy.allowPrivateForTests(), null, transport,
        "HTTP_1_1", 30, 60, 300, 3, 1);
    HttpRemoteFetcher.Request request = HttpRemoteFetcher.Request
        .get("http://localhost/artifact.jar")
        .withRepository(runtime("http://localhost", "robot", "secret", null));

    try (HttpRemoteFetcher.Result result = fetcher.fetch(request)) {
      assertEquals(200, result.status());
      assertEquals("redirected", new String(result.body().readAllBytes(), StandardCharsets.UTF_8));
    }

    assertEquals(
        List.of(
            URI.create("http://localhost/artifact.jar"),
            URI.create("https://localhost/secure/artifact.jar"),
            URI.create("https://localhost/cdn/artifact.jar")),
        targets);
    assertEquals(
        List.of(
            request.authorizationHeader(),
            request.authorizationHeader(),
            request.authorizationHeader()),
        authorizations);
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
  void repositoryRequestsCanFollowAllowlistedCrossOriginRedirectsWithoutAuthorization() {
    RepositoryRuntime runtime = runtime(null, null, null);
    HttpRemoteFetcher.Request request = HttpRemoteFetcher.Request
        .get("https://repo.example.com/maven2/com/example/app.jar")
        .withRepositoryAllowingUnsignedRedirects(runtime, true, Set.of("cdn.example.net"));
    URI current = URI.create("https://repo.example.com/maven2/com/example/app.jar");
    URI cdn = URI.create("https://cdn.example.net/app.jar");

    assertEquals("repo.example.com", request.trustedHost());
    assertNull(request.authorizationHeader());
    assertNull(request.authorizationHeaderForRedirect(current, cdn));
    assertEquals("cdn.example.net", request.trustedHostForRedirect(current, cdn));
  }

  @Test
  void integrityPinnedRepositoryDownloadsCanFollowAnyPolicyApprovedUnsignedOrigin() {
    RepositoryRuntime runtime = runtime("robot", "secret", null);
    HttpRemoteFetcher.Request request = HttpRemoteFetcher.Request
        .get("https://repo.example.com/artifacts/app.tar.gz")
        .withRepositoryAllowingUnsignedRedirects(runtime, true, Set.of("*"));
    URI current = URI.create("https://repo.example.com/artifacts/app.tar.gz");
    URI signedObject = URI.create("https://objects.example.net/signed/app.tar.gz?signature=redacted");

    assertNotNull(request.authorizationHeader());
    assertNull(request.authorizationHeaderForRedirect(current, signedObject));
    assertEquals(
        "objects.example.net",
        request.trustedHostForRedirect(current, signedObject),
        "cross-origin redirect remains DNS pinned but carries no repository credential");
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
  void repositoryRequestsRejectUnsignedCrossOriginRedirectsWithoutAllowlist() {
    RepositoryRuntime runtime = runtime(null, null, null);
    HttpRemoteFetcher.Request request = HttpRemoteFetcher.Request
        .get("https://repo.example.com/maven2/com/example/app.jar")
        .withRepository(runtime);
    URI current = URI.create("https://repo.example.com/maven2/com/example/app.jar");
    URI cdn = URI.create("https://cdn.example.net/app.jar");

    assertNull(request.authorizationHeader());
    SecurityValidationException error = assertThrows(
        SecurityValidationException.class,
        () -> request.trustedHostForRedirect(current, cdn));
    assertEquals("remote redirect URL host is not allowed: cdn.example.net", error.getMessage());
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

  private static ProxiedHttpClientFactory.ProxiedResponse response(
      int status, Map<String, String> headers, String body) throws IOException {
    ProxiedHttpClientFactory.ProxiedResponse response =
        mock(ProxiedHttpClientFactory.ProxiedResponse.class);
    when(response.status()).thenReturn(status);
    when(response.headers()).thenReturn(headers);
    when(response.header(anyString())).thenAnswer(invocation -> {
      String name = invocation.getArgument(0);
      return headers.entrySet().stream()
          .filter(entry -> entry.getKey().equalsIgnoreCase(name))
          .map(Map.Entry::getValue)
          .findFirst()
          .orElse(null);
    });
    when(response.body()).thenAnswer(ignored ->
        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    return response;
  }

  private static RepositoryRuntime runtime(String username, String password, String bearerToken) {
    return runtime("https://repo.example.com/maven2", username, password, bearerToken);
  }

  private static RepositoryRuntime runtime(
      String remoteUrl, String username, String password, String bearerToken) {
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
        remoteUrl,
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
        List.of(),
        null);
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
