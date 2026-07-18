package com.github.klboke.kkrepo.server.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.metrics.KkRepoMetrics;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.proxy.OutboundProxyConfig;
import com.github.klboke.kkrepo.server.proxy.ProxiedHttpClientFactory;
import com.github.klboke.kkrepo.server.security.OutboundRequestPolicy;
import com.github.klboke.kkrepo.server.support.FakeHttpProxyServer;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

class DockerRemoteRegistryClientTest {
  @Test
  void remoteFetchUsesConfiguredBasicCredentials() throws Exception {
    try (TestRegistry registry = TestRegistry.start()) {
      List<String> authorizations = new ArrayList<>();
      registry.server.createContext("/v2/library/alpine/manifests/latest", exchange -> {
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      });
      DockerRemoteRegistryClient client = client();

      try (HttpRemoteFetcher.Result result = client.get(
          runtime(registry.baseUrl, "robot", "secret"),
          "library/alpine/manifests/latest",
          "application/json")) {
        assertEquals(200, result.status());
      }

      assertEquals(List.of(basic("robot", "secret")), authorizations);
    }
  }

  @Test
  void bearerTokenRequestUsesBasicCredentialsThenRetriesWithBearerToken() throws Exception {
    try (TestRegistry registry = TestRegistry.start()) {
      List<String> tokenAuthorizations = new ArrayList<>();
      List<String> manifestAuthorizations = new ArrayList<>();
      AtomicInteger manifestCalls = new AtomicInteger();
      registry.server.createContext("/token", exchange -> {
        tokenAuthorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] body = "{\"token\":\"remote-token\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      });
      registry.server.createContext("/v2/library/alpine/manifests/latest", exchange -> {
        manifestAuthorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        int call = manifestCalls.incrementAndGet();
        if (call == 1) {
          exchange.getResponseHeaders().add(
              "WWW-Authenticate",
              "Bearer realm=\"" + registry.baseUrl + "/token\",service=\"registry.local\",scope=\"repository:library/alpine:pull\"");
          exchange.sendResponseHeaders(401, -1);
        } else {
          byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
        }
        exchange.close();
      });
      DockerRemoteRegistryClient client = client();

      try (HttpRemoteFetcher.Result result = client.get(
          runtime(registry.baseUrl, "robot", "secret"),
          "library/alpine/manifests/latest",
          "application/json")) {
        assertEquals(200, result.status());
      }

      assertEquals(List.of(basic("robot", "secret")), tokenAuthorizations);
      assertEquals(List.of(basic("robot", "secret"), "Bearer remote-token"), manifestAuthorizations);
    }
  }

  @Test
  void bearerTokenRequestWorksWithoutConfiguredBasicCredentials() throws Exception {
    try (TestRegistry registry = TestRegistry.start()) {
      List<String> tokenAuthorizations = new ArrayList<>();
      List<String> manifestAuthorizations = new ArrayList<>();
      AtomicInteger manifestCalls = new AtomicInteger();
      registry.server.createContext("/token", exchange -> {
        tokenAuthorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] body = "{\"token\":\"anonymous-token\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      });
      registry.server.createContext("/v2/library/alpine/manifests/latest", exchange -> {
        manifestAuthorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        int call = manifestCalls.incrementAndGet();
        if (call == 1) {
          exchange.getResponseHeaders().add(
              "WWW-Authenticate",
              "Bearer realm=\"" + registry.baseUrl + "/token\",service=\"registry.local\",scope=\"repository:library/alpine:pull\"");
          exchange.sendResponseHeaders(401, -1);
        } else {
          byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
        }
        exchange.close();
      });
      DockerRemoteRegistryClient client = client();

      try (HttpRemoteFetcher.Result result = client.get(
          runtime(registry.baseUrl, null, null),
          "library/alpine/manifests/latest",
          "application/json")) {
        assertEquals(200, result.status());
      }

      assertEquals(Collections.singletonList(null), tokenAuthorizations);
      assertEquals(java.util.Arrays.asList(null, "Bearer anonymous-token"), manifestAuthorizations);
    }
  }

  @Test
  void bearerChallengeParserKeepsCommaSeparatedScopeActionsInsideQuotes() throws Exception {
    try (TestRegistry registry = TestRegistry.start()) {
      List<String> tokenQueries = new ArrayList<>();
      AtomicInteger manifestCalls = new AtomicInteger();
      registry.server.createContext("/token", exchange -> {
        tokenQueries.add(exchange.getRequestURI().getRawQuery());
        byte[] body = "{\"token\":\"push-scope-token\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      });
      registry.server.createContext("/v2/team/app/manifests/latest", exchange -> {
        int call = manifestCalls.incrementAndGet();
        if (call == 1) {
          exchange.getResponseHeaders().add(
              "WWW-Authenticate",
              "Bearer realm=\"" + registry.baseUrl + "/token\",service=\"registry.local\",scope=\"repository:team/app:pull,push\"");
          exchange.sendResponseHeaders(401, -1);
        } else {
          byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
        }
        exchange.close();
      });
      DockerRemoteRegistryClient client = client();

      try (HttpRemoteFetcher.Result result = client.get(
          runtime(registry.baseUrl, null, null),
          "team/app/manifests/latest",
          "application/json")) {
        assertEquals(200, result.status());
      }

      assertEquals(1, tokenQueries.size());
      assertEquals(
          "service=registry.local&scope=repository:team/app:pull,push",
          URLDecoder.decode(tokenQueries.get(0), StandardCharsets.UTF_8));
    }
  }

  @Test
  void fullRemoteRepositoryUrlWithV2PrefixIsUsedAsProxyBase() throws Exception {
    try (TestRegistry registry = TestRegistry.start()) {
      List<String> requestPaths = new ArrayList<>();
      registry.server.createContext("/v2/docker-hosted/library/alpine/manifests/latest", exchange -> {
        requestPaths.add(exchange.getRequestURI().getPath());
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      });
      DockerRemoteRegistryClient client = client();

      try (HttpRemoteFetcher.Result result = client.get(
          runtime(registry.baseUrl + "/v2/docker-hosted", null, null),
          "library/alpine/manifests/latest",
          "application/json")) {
        assertEquals(200, result.status());
      }

      assertEquals(List.of("/v2/docker-hosted/library/alpine/manifests/latest"), requestPaths);
    }
  }

  @Test
  void remoteBlobFetchFollowsRegistryRedirects() throws Exception {
    try (TestRegistry registry = TestRegistry.start()) {
      List<String> requestPaths = new ArrayList<>();
      registry.server.createContext("/v2/library/nginx/blobs/sha256:abc", exchange -> {
        requestPaths.add(exchange.getRequestURI().getPath());
        exchange.getResponseHeaders().add("Location", registry.baseUrl + "/cdn/layers/abc");
        exchange.sendResponseHeaders(307, -1);
        exchange.close();
      });
      registry.server.createContext("/cdn/layers/abc", exchange -> {
        requestPaths.add(exchange.getRequestURI().getPath());
        byte[] body = "layer".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      });
      DockerRemoteRegistryClient client = client();

      try (HttpRemoteFetcher.Result result = client.get(
          runtime(registry.baseUrl, null, null),
          "library/nginx/blobs/sha256:abc",
          "application/octet-stream")) {
        assertEquals(200, result.status());
      }

      assertEquals(List.of("/v2/library/nginx/blobs/sha256:abc", "/cdn/layers/abc"), requestPaths);
    }
  }

  @Test
  void sameOriginTreatsSameHostHttpToHttpsUpgradeAsOriginPreserving() {
    assertTrue(DockerRemoteRegistryClient.sameOrigin(
        java.net.URI.create("http://registry.example.com/v2/a/b"),
        java.net.URI.create("https://registry.example.com/v2/a/b")));
    assertTrue(DockerRemoteRegistryClient.sameOrigin(
        java.net.URI.create("http://registry.example.com:8080/v2/a/b"),
        java.net.URI.create("https://registry.example.com:8080/v2/a/b")));
    assertTrue(DockerRemoteRegistryClient.sameOrigin(
        java.net.URI.create("http://registry.example.com:80/v2/a/b"),
        java.net.URI.create("https://registry.example.com:443/v2/a/b")));
    assertFalse(DockerRemoteRegistryClient.sameOrigin(
        java.net.URI.create("https://registry.example.com/v2/a/b"),
        java.net.URI.create("http://registry.example.com/v2/a/b")));
    assertFalse(DockerRemoteRegistryClient.sameOrigin(
        java.net.URI.create("http://registry.example.com/v2/a/b"),
        java.net.URI.create("https://other.example.com/v2/a/b")));
    assertFalse(DockerRemoteRegistryClient.sameOrigin(
        java.net.URI.create("http://registry.example.com:8080/v2/a/b"),
        java.net.URI.create("https://registry.example.com/v2/a/b")));
    assertTrue(DockerRemoteRegistryClient.sameOrigin(
        java.net.URI.create("https://registry.example.com/v2/a/b"),
        java.net.URI.create("https://registry.example.com:443/v2/a/b")));
  }

  @Test
  void httpToHttpsUpgradePreservesBasicAndBearerCredentialsOverTls() throws Exception {
    List<String> manifestAuthorizations = new ArrayList<>();
    List<String> tokenAuthorizations = new ArrayList<>();
    // Keep the Docker URLs on their real default ports so the redirect exercises the production
    // 80-to-443 origin rule. This loopback router only maps those connections to an ephemeral TLS
    // listener; the RepositoryRuntime itself has no outbound proxy, so the direct client path runs.
    try (TestTlsRegistry registry = TestTlsRegistry.start();
        FakeHttpProxyServer router = FakeHttpProxyServer.startTunneling(request -> {
          URI target = URI.create(request.target());
          String location = "https://localhost" + target.getRawPath()
              + (target.getRawQuery() == null ? "" : "?" + target.getRawQuery());
          return FakeHttpProxyServer.FakeResponse.status(307, Map.of("Location", location));
        }, request -> new InetSocketAddress("127.0.0.1", registry.port()))) {
      registry.server.createContext("/token", exchange -> {
        tokenAuthorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] body = "{\"token\":\"tls-token\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Connection", "close");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      });
      registry.server.createContext("/v2/library/alpine/manifests/latest", exchange -> {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        manifestAuthorizations.add(authorization);
        exchange.getResponseHeaders().add("Connection", "close");
        if (!"Bearer tls-token".equals(authorization)) {
          exchange.getResponseHeaders().add(
              "WWW-Authenticate",
              "Bearer realm=\"https://localhost/token\",service=\"registry.local\",scope=\"repository:library/alpine:pull\"");
          exchange.sendResponseHeaders(401, -1);
        } else {
          byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
        }
        exchange.close();
      });
      HttpClient tlsClient = HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NEVER)
          .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", router.port())))
          .sslContext(registry.clientSslContext)
          .build();
      DockerRemoteRegistryClient client = new DockerRemoteRegistryClient(
          null,
          OutboundRequestPolicy.allowPrivateForTests(),
          null,
          true,
          300,
          null,
          null,
          tlsClient);

      try (HttpRemoteFetcher.Result result = client.get(
          runtime("http://localhost", "robot", "secret"),
          "library/alpine/manifests/latest",
          "application/json")) {
        assertEquals(200, result.status());
      }

      List<String> plainHttpAuthorizations = router.requests().stream()
          .filter(request -> "GET".equals(request.method()))
          .map(request -> request.header("Authorization"))
          .toList();
      assertEquals(List.of(basic("robot", "secret")), plainHttpAuthorizations,
          "the bearer token must retry directly on the upgraded HTTPS URL");
      assertEquals(List.of(basic("robot", "secret"), "Bearer tls-token"), manifestAuthorizations);
      assertEquals(List.of(basic("robot", "secret")), tokenAuthorizations);
      List<FakeHttpProxyServer.RecordedRequest> connectRequests = router.requests().stream()
          .filter(request -> "CONNECT".equals(request.method()))
          .toList();
      assertFalse(connectRequests.isEmpty());
      assertTrue(connectRequests.stream()
          .allMatch(request -> "localhost:443".equals(request.target())));
    }
  }

  @Test
  void sameOriginRedirectPreservesRegistryCredentials() throws Exception {
    try (TestRegistry registry = TestRegistry.start()) {
      List<String> authorizations = new ArrayList<>();
      registry.server.createContext("/v2/library/nginx/blobs/sha256:abc", exchange -> {
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        exchange.getResponseHeaders().add("Location", registry.baseUrl + "/cdn/layers/abc");
        exchange.sendResponseHeaders(307, -1);
        exchange.close();
      });
      registry.server.createContext("/cdn/layers/abc", exchange -> {
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] body = "layer".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      });
      DockerRemoteRegistryClient client = client();

      try (HttpRemoteFetcher.Result result = client.get(
          runtime(registry.baseUrl, "robot", "secret"),
          "library/nginx/blobs/sha256:abc",
          "application/octet-stream")) {
        assertEquals(200, result.status());
      }

      assertEquals(List.of(basic("robot", "secret"), basic("robot", "secret")), authorizations);
    }
  }

  @Test
  void crossOriginRedirectStripsRegistryCredentials() throws Exception {
    try (TestRegistry registry = TestRegistry.start(); TestRegistry storage = TestRegistry.start()) {
      List<String> registryAuthorizations = new ArrayList<>();
      List<String> storageAuthorizations = new ArrayList<>();
      registry.server.createContext("/v2/library/nginx/blobs/sha256:abc", exchange -> {
        registryAuthorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        exchange.getResponseHeaders().add("Location", storage.baseUrl + "/layers/abc");
        exchange.sendResponseHeaders(307, -1);
        exchange.close();
      });
      storage.server.createContext("/layers/abc", exchange -> {
        storageAuthorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] body = "layer".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      });
      DockerRemoteRegistryClient client = client();

      try (HttpRemoteFetcher.Result result = client.get(
          runtime(registry.baseUrl, "robot", "secret"),
          "library/nginx/blobs/sha256:abc",
          "application/octet-stream")) {
        assertEquals(200, result.status());
      }

      assertEquals(List.of(basic("robot", "secret")), registryAuthorizations);
      assertEquals(Collections.singletonList(null), storageAuthorizations);
    }
  }

  @Test
  void crossOriginRedirectDoesNotHonorStorageBearerChallenge() throws Exception {
    try (TestRegistry registry = TestRegistry.start(); TestRegistry storage = TestRegistry.start()) {
      List<String> storageAuthorizations = new ArrayList<>();
      AtomicInteger storageTokenCalls = new AtomicInteger();
      registry.server.createContext("/v2/library/nginx/blobs/sha256:abc", exchange -> {
        exchange.getResponseHeaders().add("Location", storage.baseUrl + "/layers/abc");
        exchange.sendResponseHeaders(307, -1);
        exchange.close();
      });
      storage.server.createContext("/layers/abc", exchange -> {
        storageAuthorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        exchange.getResponseHeaders().add(
            "WWW-Authenticate",
            "Bearer realm=\"" + storage.baseUrl + "/token\",service=\"storage.local\"");
        exchange.sendResponseHeaders(401, -1);
        exchange.close();
      });
      storage.server.createContext("/token", exchange -> {
        storageTokenCalls.incrementAndGet();
        byte[] body = "{\"token\":\"storage-token\"}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      });
      DockerRemoteRegistryClient client = client();

      try (HttpRemoteFetcher.Result result = client.get(
          runtime(registry.baseUrl, "robot", "secret"),
          "library/nginx/blobs/sha256:abc",
          "application/octet-stream")) {
        assertEquals(401, result.status());
      }

      assertEquals(Collections.singletonList(null), storageAuthorizations);
      assertEquals(0, storageTokenCalls.get(),
          "a cross-origin storage challenge must not trigger a Registry credential exchange");
    }
  }

  @Test
  void crossOriginRedirectStripsRegistryBearerToken() throws Exception {
    try (TestRegistry registry = TestRegistry.start(); TestRegistry storage = TestRegistry.start()) {
      List<String> storageAuthorizations = new ArrayList<>();
      AtomicInteger manifestCalls = new AtomicInteger();
      registry.server.createContext("/token", exchange -> {
        byte[] body = "{\"token\":\"remote-token\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      });
      registry.server.createContext("/v2/library/nginx/blobs/sha256:abc", exchange -> {
        int call = manifestCalls.incrementAndGet();
        if (call == 1) {
          exchange.getResponseHeaders().add(
              "WWW-Authenticate",
              "Bearer realm=\"" + registry.baseUrl + "/token\",service=\"registry.local\",scope=\"repository:library/nginx:pull\"");
          exchange.sendResponseHeaders(401, -1);
        } else {
          exchange.getResponseHeaders().add("Location", storage.baseUrl + "/layers/abc");
          exchange.sendResponseHeaders(307, -1);
        }
        exchange.close();
      });
      storage.server.createContext("/layers/abc", exchange -> {
        storageAuthorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] body = "layer".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      });
      DockerRemoteRegistryClient client = client();

      try (HttpRemoteFetcher.Result result = client.get(
          runtime(registry.baseUrl, "robot", "secret"),
          "library/nginx/blobs/sha256:abc",
          "application/octet-stream")) {
        assertEquals(200, result.status());
      }

      assertEquals(Collections.singletonList(null), storageAuthorizations);
    }
  }

  @Test
  void bearerTokenIsCachedAcrossRemoteChallenges() throws Exception {
    try (TestRegistry registry = TestRegistry.start()) {
      AtomicInteger tokenCalls = new AtomicInteger();
      List<String> manifestAuthorizations = new ArrayList<>();
      registry.server.createContext("/token", exchange -> {
        tokenCalls.incrementAndGet();
        byte[] body = "{\"token\":\"cached-remote-token\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      });
      registry.server.createContext("/v2/library/alpine/manifests/latest", exchange -> {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        manifestAuthorizations.add(authorization);
        if (!"Bearer cached-remote-token".equals(authorization)) {
          exchange.getResponseHeaders().add(
              "WWW-Authenticate",
              "Bearer realm=\"" + registry.baseUrl + "/token\",service=\"registry.local\",scope=\"repository:library/alpine:pull\"");
          exchange.sendResponseHeaders(401, -1);
        } else {
          byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
        }
        exchange.close();
      });
      DockerRemoteRegistryClient client = new DockerRemoteRegistryClient(
          null,
          OutboundRequestPolicy.allowPrivateForTests(),
          new InMemorySharedCache(),
          true,
          300);
      RepositoryRuntime runtime = runtime(registry.baseUrl, null, null);

      for (int i = 0; i < 2; i++) {
        try (HttpRemoteFetcher.Result result = client.get(
            runtime,
            "library/alpine/manifests/latest",
            "application/json")) {
          assertEquals(200, result.status());
        }
      }

      assertEquals(1, tokenCalls.get());
      assertEquals(java.util.Arrays.asList(null, "Bearer cached-remote-token", null, "Bearer cached-remote-token"),
          manifestAuthorizations);
    }
  }

  @Test
  void cachedBearerTokenDoesNotHonorCrossOriginStorageChallenge() throws Exception {
    try (TestRegistry registry = TestRegistry.start(); TestRegistry storage = TestRegistry.start()) {
      AtomicInteger tokenCalls = new AtomicInteger();
      AtomicInteger bearerCalls = new AtomicInteger();
      List<String> storageAuthorizations = new ArrayList<>();
      registry.server.createContext("/token", exchange -> {
        tokenCalls.incrementAndGet();
        byte[] body = "{\"token\":\"cached-remote-token\",\"expires_in\":3600}"
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      });
      registry.server.createContext("/v2/library/alpine/manifests/latest", exchange -> {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (!"Bearer cached-remote-token".equals(authorization)) {
          exchange.getResponseHeaders().add(
              "WWW-Authenticate",
              "Bearer realm=\"" + registry.baseUrl
                  + "/token\",service=\"registry.local\",scope=\"repository:library/alpine:pull\"");
          exchange.sendResponseHeaders(401, -1);
        } else if (bearerCalls.incrementAndGet() == 1) {
          byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
        } else {
          exchange.getResponseHeaders().add("Location", storage.baseUrl + "/layers/abc");
          exchange.sendResponseHeaders(307, -1);
        }
        exchange.close();
      });
      storage.server.createContext("/layers/abc", exchange -> {
        storageAuthorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        exchange.getResponseHeaders().add(
            "WWW-Authenticate",
            "Bearer realm=\"" + storage.baseUrl + "/token\",service=\"storage.local\"");
        exchange.sendResponseHeaders(401, -1);
        exchange.close();
      });
      storage.server.createContext("/token", exchange -> {
        tokenCalls.incrementAndGet();
        byte[] body = "{\"token\":\"storage-token\"}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      });
      DockerRemoteRegistryClient client = new DockerRemoteRegistryClient(
          null,
          OutboundRequestPolicy.allowPrivateForTests(),
          new InMemorySharedCache(),
          true,
          300);
      RepositoryRuntime runtime = runtime(registry.baseUrl, "robot", "secret");

      try (HttpRemoteFetcher.Result result = client.get(
          runtime,
          "library/alpine/manifests/latest",
          "application/json")) {
        assertEquals(200, result.status());
      }
      try (HttpRemoteFetcher.Result result = client.get(
          runtime,
          "library/alpine/manifests/latest",
          "application/json")) {
        assertEquals(401, result.status());
      }

      assertEquals(Collections.singletonList(null), storageAuthorizations);
      assertEquals(1, tokenCalls.get(),
          "a cached Registry token must not let storage refresh credentials cross-origin");
    }
  }

  @Test
  void remoteFetchRecordsDockerProxyRemoteMetrics() throws Exception {
    try (TestRegistry registry = TestRegistry.start()) {
      registry.server.createContext("/v2/library/alpine/manifests/latest", exchange -> {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      });
      SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
      DockerRemoteRegistryClient client = new DockerRemoteRegistryClient(
          null,
          OutboundRequestPolicy.allowPrivateForTests(),
          null,
          true,
          300,
          new KkRepoMetrics(meterRegistry),
          null);

      try (HttpRemoteFetcher.Result result = client.get(
          runtime(registry.baseUrl, null, null),
          "library/alpine/manifests/latest",
          "application/json")) {
        assertEquals(200, result.status());
      }

      assertEquals(1.0, meterRegistry.counter(
          "kkrepo_proxy_remote_requests_total",
          "repo", "docker-proxy",
          "format", "docker",
          "method", "get",
          "remote_host", "127.0.0.1",
          "status", "200",
          "outcome", "success").count());
    }
  }

  @Test
  void proxiedRemoteFetchGoesThroughProxyWithBasicCredentials() throws Exception {
    try (FakeHttpProxyServer proxy = FakeHttpProxyServer.start(request ->
            FakeHttpProxyServer.FakeResponse.bytes(200,
                Map.of("Content-Type", "application/vnd.docker.distribution.manifest.v2+json"),
                "{}".getBytes(StandardCharsets.UTF_8)));
        ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      DockerRemoteRegistryClient client = proxiedClient(factory);

      try (HttpRemoteFetcher.Result result = client.get(
          proxiedRuntime("http://localhost", "robot", "secret",
              outboundProxy(proxy.port())),
          "library/alpine/manifests/latest",
          "application/vnd.docker.distribution.manifest.v2+json")) {
        assertEquals(200, result.status());
      }

      assertEquals(1, proxy.requests().size());
      FakeHttpProxyServer.RecordedRequest recorded = proxy.requests().get(0);
      assertEquals("http://localhost/v2/library/alpine/manifests/latest",
          recorded.target());
      assertEquals(basic("robot", "secret"), recorded.header("Authorization"));
      assertEquals("application/vnd.docker.distribution.manifest.v2+json", recorded.header("Accept"));
    }
  }

  @Test
  void proxiedCrossOriginRedirectStripsRegistryCredentials() throws Exception {
    try (FakeHttpProxyServer proxy = FakeHttpProxyServer.start(request -> {
          if (request.target().endsWith("/v2/library/nginx/blobs/sha256:abc")) {
            return FakeHttpProxyServer.FakeResponse.status(307,
                Map.of("Location", "http://127.0.0.1/layers/abc"));
          }
          return FakeHttpProxyServer.FakeResponse.bytes(200,
              Map.of("Content-Type", "application/octet-stream"),
              "layer".getBytes(StandardCharsets.UTF_8));
        });
        ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      DockerRemoteRegistryClient client = proxiedClient(factory);

      try (HttpRemoteFetcher.Result result = client.get(
          proxiedRuntime("http://localhost", "robot", "secret",
              outboundProxy(proxy.port())),
          "library/nginx/blobs/sha256:abc",
          "application/octet-stream")) {
        assertEquals(200, result.status());
        assertEquals("layer", new String(result.body().readAllBytes(), StandardCharsets.UTF_8));
      }

      assertEquals(2, proxy.requests().size());
      assertEquals(basic("robot", "secret"), proxy.requests().get(0).header("Authorization"));
      assertEquals("http://127.0.0.1/layers/abc", proxy.requests().get(1).target());
      assertNull(proxy.requests().get(1).header("Authorization"),
          "registry credentials must not leak to the cross-origin storage URL");
    }
  }

  @Test
  void proxiedSameOriginRedirectPreservesRegistryCredentials() throws Exception {
    try (FakeHttpProxyServer proxy = FakeHttpProxyServer.start(request -> {
          if (request.target().endsWith("/v2/library/nginx/blobs/sha256:abc")) {
            return FakeHttpProxyServer.FakeResponse.status(307,
                Map.of("Location", "http://localhost/cdn/layers/abc"));
          }
          return FakeHttpProxyServer.FakeResponse.bytes(200, Map.of(),
              "layer".getBytes(StandardCharsets.UTF_8));
        });
        ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      DockerRemoteRegistryClient client = proxiedClient(factory);

      try (HttpRemoteFetcher.Result result = client.get(
          proxiedRuntime("http://localhost", "robot", "secret",
              outboundProxy(proxy.port())),
          "library/nginx/blobs/sha256:abc",
          "application/octet-stream")) {
        assertEquals(200, result.status());
      }

      assertEquals(2, proxy.requests().size());
      assertEquals(basic("robot", "secret"), proxy.requests().get(0).header("Authorization"));
      assertEquals(basic("robot", "secret"), proxy.requests().get(1).header("Authorization"));
    }
  }

  @Test
  void proxiedBearerTokenFlowFetchesTokenThroughProxy() throws Exception {
    AtomicInteger manifestCalls = new AtomicInteger();
    try (FakeHttpProxyServer proxy = FakeHttpProxyServer.start(request -> {
          if (request.target().startsWith("http://127.0.0.1/token")) {
            return FakeHttpProxyServer.FakeResponse.bytes(200,
                Map.of("Content-Type", "application/json"),
                "{\"token\":\"proxied-remote-token\"}".getBytes(StandardCharsets.UTF_8));
          }
          int call = manifestCalls.incrementAndGet();
          if (call == 1) {
            return FakeHttpProxyServer.FakeResponse.status(401,
                Map.of("WWW-Authenticate",
                    "Bearer realm=\"http://127.0.0.1/token\",service=\"registry.local\",scope=\"repository:library/alpine:pull\""));
          }
          return FakeHttpProxyServer.FakeResponse.bytes(200,
              Map.of("Content-Type", "application/json"),
              "{}".getBytes(StandardCharsets.UTF_8));
        });
        ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      DockerRemoteRegistryClient client = proxiedClient(factory);

      try (HttpRemoteFetcher.Result result = client.get(
          proxiedRuntime("http://localhost", "robot", "secret",
              outboundProxy(proxy.port())),
          "library/alpine/manifests/latest",
          "application/json")) {
        assertEquals(200, result.status());
      }

      FakeHttpProxyServer.RecordedRequest tokenRequest = proxy.requests().stream()
          .filter(request -> request.target().startsWith("http://127.0.0.1/token"))
          .findFirst()
          .orElseThrow();
      assertEquals(basic("robot", "secret"), tokenRequest.header("Authorization"));
      assertEquals(2, manifestCalls.get());
      assertEquals("Bearer proxied-remote-token",
          proxy.requests().get(proxy.requests().size() - 1).header("Authorization"));
    }
  }

  @Test
  void proxiedRemoteFetchRecordsDockerProxyRemoteMetrics() throws Exception {
    try (FakeHttpProxyServer proxy = FakeHttpProxyServer.start(request ->
            FakeHttpProxyServer.FakeResponse.bytes(200,
                Map.of("Content-Type", "application/json"),
                "{}".getBytes(StandardCharsets.UTF_8)));
        ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
      DockerRemoteRegistryClient client = new DockerRemoteRegistryClient(
          null,
          OutboundRequestPolicy.allowPrivateForTests(),
          null,
          true,
          300,
          new KkRepoMetrics(meterRegistry),
          factory);

      try (HttpRemoteFetcher.Result result = client.get(
          proxiedRuntime("http://localhost", null, null, outboundProxy(proxy.port())),
          "library/alpine/manifests/latest",
          "application/json")) {
        assertEquals(200, result.status());
      }

      assertEquals(1.0, meterRegistry.counter(
          "kkrepo_proxy_remote_requests_total",
          "repo", "docker-proxy",
          "format", "docker",
          "method", "get",
          "remote_host", "localhost",
          "status", "200",
          "outcome", "success").count());
    }
  }

  private static DockerRemoteRegistryClient proxiedClient(ProxiedHttpClientFactory factory) {
    return new DockerRemoteRegistryClient(
        null, OutboundRequestPolicy.allowPrivateForTests(), null, true, 300, null, factory);
  }

  private static OutboundProxyConfig outboundProxy(int port) {
    return new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "127.0.0.1", port, null, null);
  }

  private static RepositoryRuntime proxiedRuntime(
      String remoteUrl, String username, String password, OutboundProxyConfig outboundProxy) {
    return new RepositoryRuntime(
        10L,
        "docker-proxy",
        RepositoryFormat.DOCKER,
        RepositoryType.PROXY,
        "docker-proxy",
        true,
        1L,
        null,
        null,
        null,
        true,
        remoteUrl,
        1440,
        1440,
        true,
        username,
        password,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        outboundProxy);
  }

  private static DockerRemoteRegistryClient client() {
    return new DockerRemoteRegistryClient(null, OutboundRequestPolicy.allowPrivateForTests());
  }

  private static RepositoryRuntime runtime(String remoteUrl, String username, String password) {
    return new RepositoryRuntime(
        10L,
        "docker-proxy",
        RepositoryFormat.DOCKER,
        RepositoryType.PROXY,
        "docker-proxy",
        true,
        1L,
        null,
        null,
        null,
        true,
        remoteUrl,
        1440,
        1440,
        true,
        username,
        password,
        null,
        false,
        null,
        null,
        List.of());
  }

  private static String basic(String username, String password) {
    return "Basic " + Base64.getEncoder()
        .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  private static final class TestRegistry implements AutoCloseable {
    private final HttpServer server;
    private final String baseUrl;

    private TestRegistry(HttpServer server) {
      this.server = server;
      this.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static TestRegistry start() throws IOException {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.start();
      return new TestRegistry(server);
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  private static final class TestTlsRegistry implements AutoCloseable {
    private static final char[] KEY_PASSWORD = "test-password".toCharArray();

    private final HttpsServer server;
    private final SSLContext clientSslContext;

    private TestTlsRegistry(HttpsServer server, SSLContext clientSslContext) {
      this.server = server;
      this.clientSslContext = clientSslContext;
    }

    static TestTlsRegistry start() throws Exception {
      SecureRandom random = new SecureRandom();
      KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
      keyPairGenerator.initialize(2048, random);
      KeyPair keyPair = keyPairGenerator.generateKeyPair();

      Instant now = Instant.now();
      X500Name subject = new X500Name("CN=localhost");
      JcaX509v3CertificateBuilder certificateBuilder = new JcaX509v3CertificateBuilder(
          subject,
          new BigInteger(128, random).setBit(127),
          Date.from(now.minusSeconds(60)),
          Date.from(now.plusSeconds(86400)),
          subject,
          keyPair.getPublic());
      certificateBuilder.addExtension(
          Extension.subjectAlternativeName,
          false,
          new GeneralNames(new GeneralName(GeneralName.dNSName, "localhost")));
      ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
          .build(keyPair.getPrivate());
      X509Certificate certificate = new JcaX509CertificateConverter()
          .getCertificate(certificateBuilder.build(signer));
      certificate.verify(keyPair.getPublic());

      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      keyStore.load(null, KEY_PASSWORD);
      keyStore.setKeyEntry(
          "server",
          keyPair.getPrivate(),
          KEY_PASSWORD,
          new Certificate[] {certificate});
      KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keyManagers.init(keyStore, KEY_PASSWORD);

      SSLContext serverContext = SSLContext.getInstance("TLS");
      serverContext.init(keyManagers.getKeyManagers(), null, random);
      HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
      server.start();

      KeyStore trustStore = KeyStore.getInstance("PKCS12");
      trustStore.load(null, null);
      trustStore.setCertificateEntry("server", certificate);
      TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustManagers.init(trustStore);
      SSLContext clientContext = SSLContext.getInstance("TLS");
      clientContext.init(null, trustManagers.getTrustManagers(), random);
      return new TestTlsRegistry(server, clientContext);
    }

    int port() {
      return server.getAddress().getPort();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
