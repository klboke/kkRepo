package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.server.proxy.OutboundProxyConfig;
import com.github.klboke.kkrepo.server.proxy.ProxiedHttpClientFactory;
import com.github.klboke.kkrepo.server.security.OutboundRequestPolicy;
import com.github.klboke.kkrepo.server.support.FakeHttpProxyServer;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HttpRemoteFetcherProxyTest {

  @Test
  void proxiedFetchSendsConditionalAndAuthorizationHeadersThroughProxy() throws Exception {
    try (FakeHttpProxyServer proxy = FakeHttpProxyServer.start(request ->
            FakeHttpProxyServer.FakeResponse.bytes(200,
                Map.of("ETag", "\"fresh\"", "Content-Type", "application/xml"),
                "pom-body".getBytes(StandardCharsets.UTF_8)));
        ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      HttpRemoteFetcher fetcher = proxiedFetcher(factory);
      Instant lastModified = Instant.ofEpochMilli(1700000000000L);
      HttpRemoteFetcher.Request request = new HttpRemoteFetcher.Request(
          "http://localhost/com/acme/lib/1.0/lib-1.0.pom",
          "abc123",
          lastModified,
          null,
          HttpRemoteFetcher.TimeoutProfile.DEFAULT,
          false,
          "maven-proxy",
          "MAVEN2",
          "localhost",
          "Basic cm9ib3Q6c2VjcmV0",
          Set.of(),
          proxyConfig(proxy.port(), null, null));

      try (HttpRemoteFetcher.Result result = fetcher.fetch(request)) {
        assertEquals(200, result.status());
        assertEquals("fresh", result.etag());
        assertEquals("pom-body", new String(result.body().readAllBytes(), StandardCharsets.UTF_8));
      }

      assertEquals(1, proxy.requests().size());
      FakeHttpProxyServer.RecordedRequest recorded = proxy.requests().get(0);
      assertEquals("GET", recorded.method());
      assertPinnedLocalhostTarget(recorded, "/com/acme/lib/1.0/lib-1.0.pom");
      assertEquals("localhost", recorded.header("Host"));
      assertEquals("\"abc123\"", recorded.header("If-None-Match"));
      assertEquals(
          DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC).format(lastModified),
          recorded.header("If-Modified-Since"));
      assertEquals("Basic cm9ib3Q6c2VjcmV0", recorded.header("Authorization"));
      assertEquals("*/*", recorded.header("Accept"));
    }
  }

  @Test
  void proxiedFetchFollowsRedirectThroughProxyAndStripsAuthorizationCrossOrigin() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    try (FakeHttpProxyServer proxy = FakeHttpProxyServer.start(request -> {
          if (calls.incrementAndGet() == 1) {
            return FakeHttpProxyServer.FakeResponse.status(302,
                Map.of("Location", "http://127.0.0.1/files/lib-1.0.jar"));
          }
          return FakeHttpProxyServer.FakeResponse.bytes(200, Map.of(),
              "redirected".getBytes(StandardCharsets.UTF_8));
        });
        ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      HttpRemoteFetcher fetcher = proxiedFetcher(factory);
      HttpRemoteFetcher.Request request = new HttpRemoteFetcher.Request(
          "http://localhost/lib-1.0.jar",
          null, null, null,
          HttpRemoteFetcher.TimeoutProfile.DEFAULT,
          false,
          "maven-proxy",
          "MAVEN2",
          "localhost",
          "Basic cm9ib3Q6c2VjcmV0",
          Set.of("127.0.0.1"),
          proxyConfig(proxy.port(), null, null));

      try (HttpRemoteFetcher.Result result = fetcher.fetch(request)) {
        assertEquals(200, result.status());
        assertEquals("redirected", new String(result.body().readAllBytes(), StandardCharsets.UTF_8));
      }

      assertEquals(2, proxy.requests().size());
      assertEquals("Basic cm9ib3Q6c2VjcmV0", proxy.requests().get(0).header("Authorization"));
      URI redirectedTarget = URI.create(proxy.requests().get(1).target());
      assertEquals("127.0.0.1", redirectedTarget.getHost());
      assertEquals("/files/lib-1.0.jar", redirectedTarget.getRawPath());
      assertNull(proxy.requests().get(1).header("Authorization"),
          "credentials must not leak to the cross-origin redirect target");
    }
  }

  @Test
  void proxiedFetchKeepsAuthorizationOnSameOriginRedirect() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    try (FakeHttpProxyServer proxy = FakeHttpProxyServer.start(request -> {
          if (calls.incrementAndGet() == 1) {
            return FakeHttpProxyServer.FakeResponse.status(307,
                Map.of("Location", "/moved/lib-1.0.jar"));
          }
          return FakeHttpProxyServer.FakeResponse.bytes(200, Map.of(),
              "ok".getBytes(StandardCharsets.UTF_8));
        });
        ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      HttpRemoteFetcher fetcher = proxiedFetcher(factory);
      HttpRemoteFetcher.Request request = new HttpRemoteFetcher.Request(
          "http://localhost/lib-1.0.jar",
          null, null, null,
          HttpRemoteFetcher.TimeoutProfile.DEFAULT,
          false,
          "maven-proxy",
          "MAVEN2",
          "localhost",
          "Basic cm9ib3Q6c2VjcmV0",
          Set.of(),
          proxyConfig(proxy.port(), null, null))
          .withAccept("application/json");

      try (HttpRemoteFetcher.Result result = fetcher.fetch(request)) {
        assertEquals(200, result.status());
      }

      assertEquals(2, proxy.requests().size());
      assertPinnedLocalhostTarget(proxy.requests().get(1), "/moved/lib-1.0.jar");
      assertEquals("Basic cm9ib3Q6c2VjcmV0", proxy.requests().get(1).header("Authorization"));
      assertEquals("application/json", proxy.requests().get(0).header("Accept"));
      assertEquals("application/json", proxy.requests().get(1).header("Accept"));
    }
  }

  @Test
  void proxiedHeadFetchUsesHeadMethod() throws Exception {
    try (FakeHttpProxyServer proxy = FakeHttpProxyServer.start(request ->
            FakeHttpProxyServer.FakeResponse.status(200, Map.of("ETag", "\"head-etag\"")));
        ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      HttpRemoteFetcher fetcher = proxiedFetcher(factory);
      HttpRemoteFetcher.Request request = new HttpRemoteFetcher.Request(
          "http://localhost/lib-1.0.jar",
          null, null, null,
          HttpRemoteFetcher.TimeoutProfile.DEFAULT,
          true,
          "maven-proxy",
          "MAVEN2",
          null, null,
          Set.of(),
          proxyConfig(proxy.port(), null, null));

      try (HttpRemoteFetcher.Result result = fetcher.fetch(request)) {
        assertEquals(200, result.status());
        assertEquals("head-etag", result.etag());
      }
      assertEquals("HEAD", proxy.requests().get(0).method());
    }
  }

  @Test
  void proxiedFetchPropagatesProxyFailures() throws Exception {
    try (FakeHttpProxyServer proxy = FakeHttpProxyServer.start(request -> null);
        ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      HttpRemoteFetcher fetcher = proxiedFetcher(factory);
      HttpRemoteFetcher.Request request = new HttpRemoteFetcher.Request(
          "http://localhost/lib-1.0.jar",
          null, null, null,
          HttpRemoteFetcher.TimeoutProfile.DEFAULT,
          false,
          "maven-proxy",
          "MAVEN2",
          null, null,
          Set.of(),
          proxyConfig(proxy.port(), null, null));

      assertThrows(IOException.class, () -> fetcher.fetch(request));
    }
  }

  @Test
  void requestWithoutOutboundProxyBypassesTheProxyFactory() throws Exception {
    AtomicInteger proxyCalls = new AtomicInteger();
    com.sun.net.httpserver.HttpServer upstream = com.sun.net.httpserver.HttpServer.create(
        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    try (FakeHttpProxyServer proxy = FakeHttpProxyServer.start(request -> {
          proxyCalls.incrementAndGet();
          return FakeHttpProxyServer.FakeResponse.status(500, Map.of());
        });
        ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      upstream.createContext("/direct", exchange -> {
        byte[] body = "direct".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      });
      upstream.start();
      HttpRemoteFetcher fetcher = proxiedFetcher(factory);
      try (HttpRemoteFetcher.Result result = fetcher.fetch(HttpRemoteFetcher.Request.get(
          "http://127.0.0.1:" + upstream.getAddress().getPort() + "/direct"))) {
        assertEquals(200, result.status());
        assertEquals("direct", new String(result.body().readAllBytes(), StandardCharsets.UTF_8));
      }
      assertEquals(0, proxyCalls.get(), "requests without a proxy config must not hit the proxy");
    } finally {
      upstream.stop(0);
    }
  }

  private static HttpRemoteFetcher proxiedFetcher(ProxiedHttpClientFactory factory) {
    return new HttpRemoteFetcher(
        OutboundRequestPolicy.allowPrivateForTests(), null, factory, "HTTP_1_1", 30, 60, 300, 2, 1);
  }

  private static OutboundProxyConfig proxyConfig(int port, String username, String password) {
    return new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "127.0.0.1", port, username, password);
  }

  private static void assertPinnedLocalhostTarget(
      FakeHttpProxyServer.RecordedRequest recorded, String expectedPath)
      throws Exception {
    URI routed = URI.create(recorded.target());
    java.net.InetAddress routedAddress = java.net.InetAddress.getByName(routed.getHost());
    assertTrue(java.util.Arrays.stream(java.net.InetAddress.getAllByName("localhost"))
        .anyMatch(routedAddress::equals));
    assertEquals(expectedPath, routed.getRawPath());
  }
}
