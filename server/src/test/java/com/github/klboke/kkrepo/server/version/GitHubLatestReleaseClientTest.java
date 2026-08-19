package com.github.klboke.kkrepo.server.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.cache.SharedCache;
import com.github.klboke.kkrepo.server.version.LatestReleaseSource.LatestRelease;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GitHubLatestReleaseClientTest {
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void readsLatestTagAndExactReleaseUrl() throws Exception {
    AtomicReference<String> accept = new AtomicReference<>();
    AtomicReference<String> userAgent = new AtomicReference<>();
    URI endpoint = serve(200, """
        {
          "tag_name": "v0.10.0",
          "html_url": "https://github.com/klboke/kkRepo/releases/tag/v0.10.0"
        }
        """, exchange -> {
          accept.set(exchange.getRequestHeaders().getFirst("Accept"));
          userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
        });
    GitHubLatestReleaseClient client = client(endpoint);

    LatestRelease release = client.fetch();

    assertEquals("v0.10.0", release.version());
    assertEquals(
        URI.create("https://github.com/klboke/kkRepo/releases/tag/v0.10.0"),
        release.url());
    assertEquals("application/vnd.github+json", accept.get());
    assertEquals("kkRepo-version-update-check", userAgent.get());
  }

  @Test
  void rejectsUnexpectedReleaseLinks() throws Exception {
    URI endpoint = serve(200, """
        {
          "tag_name": "v0.10.0",
          "html_url": "https://example.com/releases/tag/v0.10.0"
        }
        """, exchange -> {});

    assertThrows(IOException.class, () -> client(endpoint).fetch());
  }

  @Test
  void rejectsMissingReleaseFields() throws Exception {
    URI endpoint = serve(200, "{\"tag_name\":\"v0.10.0\"}", exchange -> {});

    IOException exception = assertThrows(IOException.class, () -> client(endpoint).fetch());

    assertEquals("GitHub latest release is missing html_url", exception.getMessage());
  }

  @Test
  void rejectsMalformedReleaseUrls() throws Exception {
    URI endpoint = serve(200, """
        {
          "tag_name": "v0.10.0",
          "html_url": "https://github.com/%"
        }
        """, exchange -> {});

    IOException exception = assertThrows(IOException.class, () -> client(endpoint).fetch());

    assertEquals("GitHub latest release has an invalid html_url", exception.getMessage());
  }

  @Test
  void rejectsUpstreamErrors() throws Exception {
    URI endpoint = serve(403, "{\"message\":\"rate limit exceeded\"}", exchange -> {});

    IOException exception = assertThrows(IOException.class, () -> client(endpoint).fetch());

    assertEquals("GitHub latest release returned HTTP 403", exception.getMessage());
  }

  @Test
  void reusesTheCachedReleaseWithinTheTtl() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    URI endpoint = serve(200, """
        {
          "tag_name": "v0.10.0",
          "html_url": "https://github.com/klboke/kkRepo/releases/tag/v0.10.0"
        }
        """, exchange -> requests.incrementAndGet());
    GitHubLatestReleaseClient client = client(endpoint);

    client.fetch();
    client.fetch();

    assertEquals(1, requests.get());
  }

  @Test
  void reloadsTheReleaseAfterTheTtl() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    URI endpoint = serve(200, """
        {
          "tag_name": "v0.10.0",
          "html_url": "https://github.com/klboke/kkRepo/releases/tag/v0.10.0"
        }
        """, exchange -> requests.incrementAndGet());
    GitHubLatestReleaseClient client = client(endpoint, Duration.ofMillis(20));

    client.fetch();
    Thread.sleep(50);
    client.fetch();

    assertEquals(2, requests.get());
  }

  @Test
  void backsOffAfterAnUpstreamFailure() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    URI endpoint = serve(403, "{\"message\":\"rate limit exceeded\"}",
        exchange -> requests.incrementAndGet());
    GitHubLatestReleaseClient client = client(endpoint);

    assertThrows(IOException.class, client::fetch);
    IOException backedOff = assertThrows(IOException.class, client::fetch);

    assertEquals(1, requests.get());
    assertEquals(
        "GitHub latest release refresh is temporarily backed off",
        backedOff.getMessage());
  }

  @Test
  void continuesWhenTheBackoffCacheReadFails() throws Exception {
    URI endpoint = serve(200, """
        {
          "tag_name": "v0.10.0",
          "html_url": "https://github.com/klboke/kkRepo/releases/tag/v0.10.0"
        }
        """, exchange -> {});
    SharedCache cache = new InMemorySharedCache() {
      @Override
      public Optional<String> getString(String namespace, String key) {
        throw new IllegalStateException("cache unavailable");
      }
    };

    LatestRelease release = client(endpoint, Duration.ofMinutes(5), cache).fetch();

    assertEquals("v0.10.0", release.version());
  }

  @Test
  void preservesTheUpstreamFailureWhenTheBackoffCacheWriteFails() throws Exception {
    URI endpoint = serve(403, "{\"message\":\"rate limit exceeded\"}", exchange -> {});
    SharedCache cache = new InMemorySharedCache() {
      @Override
      public void putString(String namespace, String key, String value, Duration ttl) {
        throw new IllegalStateException("cache unavailable");
      }
    };

    IOException exception = assertThrows(
        IOException.class,
        () -> client(endpoint, Duration.ofMinutes(5), cache).fetch());

    assertEquals("GitHub latest release returned HTTP 403", exception.getMessage());
  }

  @Test
  void returnsTheReleaseWhenTheBackoffCacheClearFails() throws Exception {
    URI endpoint = serve(200, """
        {
          "tag_name": "v0.10.0",
          "html_url": "https://github.com/klboke/kkRepo/releases/tag/v0.10.0"
        }
        """, exchange -> {});
    SharedCache cache = new InMemorySharedCache() {
      @Override
      public void evict(String namespace, String key) {
        throw new IllegalStateException("cache unavailable");
      }
    };

    LatestRelease release = client(endpoint, Duration.ofMinutes(5), cache).fetch();

    assertEquals("v0.10.0", release.version());
  }

  @Test
  void restoresTheInterruptFlagWhenTheGitHubRequestIsInterrupted() throws Exception {
    HttpClient httpClient = mock(HttpClient.class);
    when(httpClient.send(
        any(HttpRequest.class),
        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenThrow(new InterruptedException("interrupted"));
    GitHubLatestReleaseClient client = client(
        httpClient,
        URI.create("https://api.github.com/repos/klboke/kkRepo/releases/latest"),
        Duration.ofMinutes(5),
        new InMemorySharedCache());

    try {
      IOException exception = assertThrows(IOException.class, client::fetch);

      assertEquals("GitHub release request was interrupted", exception.getMessage());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  private GitHubLatestReleaseClient client(URI endpoint) {
    return client(endpoint, Duration.ofMinutes(5));
  }

  private GitHubLatestReleaseClient client(URI endpoint, Duration cacheTtl) {
    return client(endpoint, cacheTtl, new InMemorySharedCache());
  }

  private GitHubLatestReleaseClient client(
      URI endpoint,
      Duration cacheTtl,
      SharedCache sharedCache) {
    return client(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
        endpoint,
        cacheTtl,
        sharedCache);
  }

  private GitHubLatestReleaseClient client(
      HttpClient httpClient,
      URI endpoint,
      Duration cacheTtl,
      SharedCache sharedCache) {
    return new GitHubLatestReleaseClient(
        httpClient,
        new ObjectMapper(),
        endpoint,
        cacheTtl,
        sharedCache);
  }

  private URI serve(int status, String body, ExchangeAssertion assertion) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/latest", exchange -> respond(exchange, status, body, assertion));
    server.start();
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/latest");
  }

  private void respond(
      HttpExchange exchange,
      int status,
      String body,
      ExchangeAssertion assertion) throws IOException {
    assertion.accept(exchange);
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  @FunctionalInterface
  private interface ExchangeAssertion {
    void accept(HttpExchange exchange) throws IOException;
  }
}
