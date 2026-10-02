package com.github.klboke.kkrepo.server.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.proxy.OutboundProxyConfig;
import com.github.klboke.kkrepo.server.proxy.ProxiedHttpClientFactory;
import com.github.klboke.kkrepo.server.security.OutboundRequestPolicy;
import com.github.klboke.kkrepo.server.security.OutboundRequestPolicy.ResolvedHttpTarget;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(30)
class DockerRemoteRegistryClientPoolTest {
  @Test
  void oversizedChallengesAreBoundedAndStillReleaseTheirConnections() throws Exception {
    try (Registry registry = new Registry();
        ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 5000, 2000)) {
      registry.challengeBody = "x".repeat(64 * 1024 + 1);
      DockerRemoteRegistryClient client = client(factory);
      RepositoryRuntime runtime = runtime(registry.baseUrl());
      for (int i = 0; i < 25; i++) {
        IOException error = assertThrows(IOException.class, () -> assertFetch(client, runtime, "oversized"));
        assertEquals("Docker authentication challenge body exceeds 64 KiB", error.getMessage());
      }
      registry.challengeBody = "{}";
      assertFetch(client, runtime, "after-oversized");
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 403, 500})
  void unavailableTokenPreservesTheOriginalChallengeBodyAndHeaders(int tokenStatus) throws Exception {
    try (Registry registry = new Registry();
        ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 5000, 2000)) {
      registry.tokenStatus = tokenStatus;
      registry.tokenBody = "{}";
      DockerRemoteRegistryClient client = client(factory);
      try (var result = client.get(runtime(registry.baseUrl()), "private/manifests/v1", "application/json")) {
        assertEquals(401, result.status());
        assertTrue(result.header("WWW-Authenticate").contains(registry.baseUrl() + "/token"));
        assertEquals("{\"errors\":[{\"code\":\"UNAUTHORIZED\"}]}",
            new String(result.body().readAllBytes(), StandardCharsets.UTF_8));
      }
      registry.tokenStatus = 200;
      registry.tokenBody = null;
      assertFetch(client, runtime(registry.baseUrl()), "after-denial");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sameRouteColdAndExpiredTokenBurstsReleaseChallengeConnections(boolean expiredToken) throws Exception {
    try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();
        Registry registry = new Registry();
        ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 5000, 2000)) {
      DockerRemoteRegistryClient client = client(factory);
      RepositoryRuntime runtime = runtime(registry.baseUrl());
      if (expiredToken) {
        for (int i = 0; i < 25; i++) {
          assertFetch(client, runtime, "image" + i);
        }
        registry.token = "renewed-token";
      }
      registry.synchronizeChallenges = true;
      registry.synchronizeExpired = expiredToken;
      List<Future<?>> requests = new ArrayList<>();
      for (int i = 0; i < 25; i++) {
        String image = "image" + i;
        requests.add(callers.submit(() -> {
          assertFetch(client, runtime, image);
          return null;
        }));
      }
      for (Future<?> request : requests) {
        request.get(15, TimeUnit.SECONDS);
      }
      assertEquals(0, registry.challenges.getCount(), "all route slots must have held a challenge");
      assertFetch(client, runtime, "after-burst");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void tokenFailuresDoNotLeakInitialOrExpiredTokenChallenges(boolean expiredToken) throws Exception {
    AtomicBoolean failToken = new AtomicBoolean();
    try (Registry registry = new Registry();
        ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 5000, 2000) {
          @Override
          public ProxiedResponse execute(String owner, OutboundProxyConfig proxy, String method,
              ResolvedHttpTarget target, Map<String, String> headers, long timeout) throws IOException {
            if (failToken.get() && target.uri().getPath().equals("/token")) {
              throw new IOException("token service unavailable");
            }
            return super.execute(owner, proxy, method, target, headers, timeout);
          }
        }) {
      DockerRemoteRegistryClient client = client(factory);
      RepositoryRuntime runtime = runtime(registry.baseUrl());
      if (expiredToken) {
        for (int i = 0; i < 25; i++) {
          assertFetch(client, runtime, "image" + i);
        }
        registry.token = "renewed-token";
      }
      failToken.set(true);
      for (int i = 0; i < 25; i++) {
        String image = "image" + i;
        IOException error = assertThrows(IOException.class, () -> assertFetch(client, runtime, image));
        assertEquals("token service unavailable", error.getMessage(),
            "a previous token failure must not exhaust the registry route");
      }
      failToken.set(false);
      assertFetch(client, runtime, "after-failure");
    }
  }

  private static DockerRemoteRegistryClient client(ProxiedHttpClientFactory factory) {
    return new DockerRemoteRegistryClient(null, OutboundRequestPolicy.allowPrivateForTests(),
        new InMemorySharedCache(), true, 300, null, factory);
  }

  private static RepositoryRuntime runtime(String baseUrl) {
    RepositoryRuntime runtime = mock(RepositoryRuntime.class);
    when(runtime.name()).thenReturn("pool-regression");
    when(runtime.proxyRemoteUrl()).thenReturn(baseUrl);
    return runtime;
  }

  private static void assertFetch(DockerRemoteRegistryClient client, RepositoryRuntime runtime,
      String image) throws IOException {
    try (HttpRemoteFetcher.Result result = client.get(runtime, image + "/manifests/v1", "application/json")) {
      assertEquals(200, result.status());
      assertEquals("{}", new String(result.body().readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  private static final class Registry implements AutoCloseable {
    private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    private final CountDownLatch challenges = new CountDownLatch(20);
    private final AtomicInteger challengeCount = new AtomicInteger();
    private volatile boolean synchronizeChallenges;
    private volatile boolean synchronizeExpired;
    private volatile String token = "initial-token";
    private volatile int tokenStatus = 200;
    private volatile String tokenBody;
    private volatile String challengeBody = "{\"errors\":[{\"code\":\"UNAUTHORIZED\"}]}";

    Registry() throws IOException {
      server.setExecutor(handlers);
      server.createContext("/token", exchange -> respond(exchange, tokenStatus,
          tokenBody == null ? "{\"token\":\"" + token + "\",\"expires_in\":3600}" : tokenBody));
      server.createContext("/v2/", exchange -> {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (("Bearer " + token).equals(authorization)) {
          respond(exchange, 200, "{}");
          return;
        }
        if (synchronizeChallenges && (synchronizeExpired == "Bearer initial-token".equals(authorization))
            && challengeCount.incrementAndGet() <= 20) {
          challenges.countDown();
          try {
            assertTrue(challenges.await(10, TimeUnit.SECONDS), "20 concurrent registry requests required");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
          }
        }
        String image = exchange.getRequestURI().getPath().split("/")[2];
        exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"" + baseUrl()
            + "/token\",service=\"registry\",scope=\"repository:" + image + ":pull\"");
        // A non-empty, unread entity keeps the Apache connection leased after the headers arrive.
        respond(exchange, 401, challengeBody);
      });
      server.start();
    }

    String baseUrl() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
      try (exchange) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    }

    @Override
    public void close() {
      server.stop(0);
      handlers.shutdownNow();
    }
  }
}
