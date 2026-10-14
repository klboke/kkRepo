package com.github.klboke.kkrepo.server.pypi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheInfo;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.CreateCommand;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.GroupSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.ProxySettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** Exercises the reported HTTP -> group -> proxy -> index writer path on two replicas. */
public final class PypiCacheInvalidationSmokeChecks {
  private PypiCacheInvalidationSmokeChecks() {}

  public static void verify(ConfigurableApplicationContext first, ConfigurableApplicationContext second)
      throws Exception {
    var version = new AtomicReference<>("1.0.0");
    HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext("/simple/", exchange -> {
      String[] segments = exchange.getRequestURI().getPath().split("/");
      String project = segments[segments.length - 1];
      String filename = project + "-" + version.get() + ".whl";
      byte[] html = ("<html><body><a href=\"../../files/" + filename + "\">"
          + filename + "</a></body></html>").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
      exchange.sendResponseHeaders(200, html.length);
      try (var body = exchange.getResponseBody()) { body.write(html); }
    });
    upstream.start();
    try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      var repositories = first.getBean(RepositoryService.class);
      repositories.create(new CreateCommand(
          "smoke-pypi-cache-proxy", "pypi-proxy", true, "smoke-file", false, null,
          new ProxySettings("http://127.0.0.1:" + upstream.getAddress().getPort(), 1440, 1440, false),
          null, null, null, null));
      repositories.create(new CreateCommand(
          "smoke-pypi-cache-group", "pypi-group", true, "smoke-file", false,
          null, null, null, null, null, new GroupSettings(List.of("smoke-pypi-cache-proxy"))));
      assertTrue(get(http, second, "demo").contains("demo-1.0.0.whl"));
      // Warm both replicas' metadata and token caches before refreshing the member.
      assertTrue(get(http, first, "demo").contains("demo-1.0.0.whl"));
      version.set("2.0.0");
      var proxy = first.getBean(RepositoryRuntimeRegistry.class)
          .resolve("smoke-pypi-cache-proxy").orElseThrow();
      first.getBean(NexusLikeCacheController.class).invalidate(proxy.id(), NexusCacheType.METADATA);
      PypiResponse refreshed = first.getBean(PypiProxyService.class).getIndex(proxy, "demo", false);
      try (var body = refreshed.body()) {
        assertTrue(new String(body.readAllBytes(), StandardCharsets.UTF_8).contains("demo-2.0.0.whl"));
      }
      var group = first.getBean(RepositoryRuntimeRegistry.class)
          .resolve("smoke-pypi-cache-group").orElseThrow();
      var invalidated = second.getBean(AssetDao.class).findAssetByPath(group.id(), "simple/demo/")
          .orElseThrow();
      assertTrue(NexusLikeCacheInfo.fromAttributes(invalidated.attributes()).orElseThrow().invalidated(),
          "member refresh must commit the group invalidation before returning");
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      String updated;
      do {
        updated = get(http, second, "demo");
        if (updated.contains("demo-2.0.0.whl")) break;
        Thread.sleep(100);
      } while (System.nanoTime() < deadline);
      assertTrue(updated.contains("demo-2.0.0.whl"), updated);

      // Distinct cold projects still share repository watermarks; the small pools must progress.
      try (var executor = Executors.newFixedThreadPool(8)) {
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
          String project = "cold" + i;
          var replica = i % 2 == 0 ? first : second;
          results.add(executor.submit(() -> get(http, replica, project)));
        }
        for (int i = 0; i < results.size(); i++) {
          assertTrue(results.get(i).get(30, TimeUnit.SECONDS).contains("cold" + i + "-2.0.0.whl"));
        }
      }
    } finally {
      upstream.stop(0);
    }
  }

  private static String get(HttpClient client, ConfigurableApplicationContext context, String project)
      throws Exception {
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
        + "/repository/smoke-pypi-cache-group/simple/" + project + "/"))
        .timeout(Duration.ofSeconds(20))
        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
            "admin:SmokeAdmin1234!".getBytes(StandardCharsets.UTF_8)))
        .GET().build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    return response.body();
  }
}
