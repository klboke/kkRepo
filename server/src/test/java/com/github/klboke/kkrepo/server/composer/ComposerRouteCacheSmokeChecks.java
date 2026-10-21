package com.github.klboke.kkrepo.server.composer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CacheVersionDao;
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
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** Real HTTP and shared SQL/blob state: warm reads on either replica must be read-only. */
public final class ComposerRouteCacheSmokeChecks {
  private static final String REPOSITORY = "smoke-composer-cache-proxy";
  private static final String METADATA = "/p2/company/example.json";

  private ComposerRouteCacheSmokeChecks() {}

  public static void verify(ConfigurableApplicationContext first, ConfigurableApplicationContext second)
      throws Exception {
    var revision = new AtomicInteger(1);
    var metadataRequests = new AtomicInteger();
    HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String upstreamUrl = "http://127.0.0.1:" + upstream.getAddress().getPort();
    upstream.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      byte[] bytes;
      if (path.equals("/packages.json")) {
        bytes = "{\"metadata-url\":\"/p2/%package%.json\"}".getBytes(StandardCharsets.UTF_8);
      } else if (path.equals(METADATA)) {
        metadataRequests.incrementAndGet();
        var packageVersions = List.of(
            Map.of("version", "1.0.0", "dist", Map.of("type", "zip", "url",
                upstreamUrl + "/dist/" + revision.get() + ".zip", "shasum", "")),
            Map.of("version", "2.0.0", "dist", Map.of("type", "zip", "url",
                upstreamUrl + "/dist/unchanged.zip", "shasum", "")));
        bytes = new ObjectMapper().writeValueAsBytes(Map.of("packages", Map.of("company/example", packageVersions)));
      } else if (path.startsWith("/dist/")) {
        bytes = path.getBytes(StandardCharsets.UTF_8);
      } else {
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
        return;
      }
      exchange.getResponseHeaders().set("Content-Type",
          path.startsWith("/dist/") ? "application/zip" : "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      try (var output = exchange.getResponseBody()) { output.write(bytes); }
    });
    upstream.start();
    try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      var repositories = first.getBean(RepositoryService.class);
      repositories.create(new CreateCommand(REPOSITORY, "composer-proxy", true, "smoke-file", false,
          null, new ProxySettings(upstreamUrl, 1440, 1440, false), null, null, null, null));
      repositories.create(new CreateCommand("smoke-composer-cache-group", "composer-group", true,
          "smoke-file", false, null, null, null, null, null, new GroupSettings(List.of(REPOSITORY))));
      var runtime = first.getBean(RepositoryRuntimeRegistry.class).resolve(REPOSITORY).orElseThrow();
      AssetDao dao = first.getBean(AssetDao.class);
      CacheVersionDao versions = first.getBean(CacheVersionDao.class);
      String watermark = "asset-metadata:repo:" + runtime.id();

      var cold = request(http, first, REPOSITORY, METADATA, "GET", null);
      assertEquals(200, cold.statusCode());
      var initialRoutes = dao.listAssetsByPrefix(runtime.id(), "_composer/routes/");
      assertEquals(2, initialRoutes.size());
      long initialVersion = versions.current(watermark);
      var warm = request(http, second, REPOSITORY, METADATA, "GET", null);
      assertEquals(200, warm.statusCode());
      var conditional = request(http, second, REPOSITORY, METADATA, "GET",
          warm.headers().firstValue("last-modified").orElseThrow());
      assertEquals(304, conditional.statusCode());
      assertEquals(0, conditional.body().length);
      assertEquals(200, request(http, first, REPOSITORY, METADATA, "HEAD", null).statusCode());
      assertEquals(200, request(http, second, "smoke-composer-cache-group", METADATA, "GET", null).statusCode());
      assertEquals(1, metadataRequests.get(), "all warm reads must use the persisted p2 document");
      assertEquals(initialVersion, versions.current(watermark), "no route write may advance the watermark");
      var warmRoutes = dao.listAssetsByPrefix(runtime.id(), "_composer/routes/");
      for (var original : initialRoutes) {
        var retained = warmRoutes.stream().filter(row -> row.path().equals(original.path())).findFirst().orElseThrow();
        assertEquals(original.assetBlobId(), retained.assetBlobId());
        assertEquals(original.lastUpdatedAt(), retained.lastUpdatedAt());
      }

      revision.set(2);
      dao.touchAssetLastUpdated(dao.findAssetByPath(runtime.id(), METADATA.substring(1)).orElseThrow().id(), Instant.EPOCH);
      assertEquals(200, request(http, second, REPOSITORY, METADATA, "GET", null).statusCode());
      assertEquals(2, metadataRequests.get());
      assertEquals(initialVersion + 2, versions.current(watermark), "only p2 and the changed route are written");
      var download = request(http, first, REPOSITORY,
          "/company/example/1.0.0/company-example-1.0.0.zip", "GET", null);
      assertEquals(200, download.statusCode());
      assertArrayEquals("/dist/2.zip".getBytes(StandardCharsets.UTF_8), download.body());
      assertTrue(dao.findAssetByPath(runtime.id(), "company/example/1.0.0/company-example-1.0.0.zip").isPresent());
    } finally {
      upstream.stop(0);
    }
  }

  private static HttpResponse<byte[]> request(HttpClient http, ConfigurableApplicationContext context,
      String repository, String path, String method, String lastModified) throws Exception {
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
        + "/repository/" + repository + path))
        .timeout(Duration.ofSeconds(20))
        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
            "admin:SmokeAdmin1234!".getBytes(StandardCharsets.UTF_8)));
    if (lastModified != null) builder.header("If-Modified-Since", lastModified);
    return http.send(builder.method(method, HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }
}
