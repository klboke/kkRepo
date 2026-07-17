package com.github.klboke.kkrepo.server.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.metrics.KkRepoMetrics;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.security.OutboundRequestPolicy;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
}
