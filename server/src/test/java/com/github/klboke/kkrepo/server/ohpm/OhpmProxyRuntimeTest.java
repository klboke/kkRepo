package com.github.klboke.kkrepo.server.ohpm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmIntegrity;
import com.github.klboke.kkrepo.server.BlobStoresController;
import com.github.klboke.kkrepo.server.KkRepoApplication;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.CreateCommand;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.ProxySettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.AdminBootstrapCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.ApiKeyCommand;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

class OhpmProxyRuntimeTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PACKAGE = "@scope/proxy-fixture";
  private static final byte[] HSP = new byte[] {8, 7, 6};
  private static final String REMOTE_TOKEN = "ohpm-upstream-test-token";
  @TempDir Path directory;

  @Test
  void postgresProxyWorksAcrossReplicas() throws Exception {
    try (var database = new PostgreSQLContainer("postgres:12")
        .withDatabaseName("kkrepo").withUsername("kkrepo").withPassword("kkrepo")) {
      database.start();
      verify("postgresql", database.getJdbcUrl());
    }
  }

  @Test
  void mysqlProxyWorksAcrossReplicas() throws Exception {
    try (var database = new MySQLContainer("mysql:8.0")
        .withDatabaseName("kkrepo").withUsername("kkrepo").withPassword("kkrepo")) {
      database.start();
      verify("mysql", database.getJdbcUrl() + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC");
    }
  }

  private void verify(String databaseType, String jdbcUrl) throws Exception {
    byte[] har = har();
    var metadataRequests = new AtomicInteger();
    var corrupt = new AtomicBoolean();
    var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String remote = "http://127.0.0.1:" + upstream.getAddress().getPort() + "/ohpm";
    upstream.createContext("/ohpm/", exchange -> {
      try (exchange) {
        if (!REMOTE_TOKEN.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
          exchange.sendResponseHeaders(401, -1);
          return;
        }
        String path = exchange.getRequestURI().getPath().substring("/ohpm/".length());
        byte[] body;
        if (path.equals(PACKAGE) || path.equals(PACKAGE + "-hsp")) {
          metadataRequests.incrementAndGet();
          var root = JSON.createObjectNode().put("name", path).put("_id", path);
          root.putObject("dist-tags").put("latest", "1.0.0");
          var manifest = root.putObject("versions").putObject("1.0.0").put("name", path).put("version", "1.0.0")
              .put("_ohpmVersion", "6.0.1").put("main", "Index.ets");
          var dist = manifest.putObject("dist")
              .put("integrity", OhpmIntegrity.compute(new ByteArrayInputStream(har)).value())
              .put("tarball", remote + "/" + path + "/-/proxy-fixture-1.0.0.har");
          if (path.endsWith("-hsp")) {
            root.put("packageType", "InterfaceHar").put("hspType", "bundle_app");
            manifest.put("types", "Index.d.ets");
            dist.put("integrity_hsp", OhpmIntegrity.compute(new ByteArrayInputStream(HSP)).value())
                .put("resolved_hsp", remote + "/" + path + "/-/binary.hsp");
          }
          body = JSON.writeValueAsBytes(root);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
        } else if (path.equals(PACKAGE + "/-/proxy-fixture-1.0.0.har")
            || path.equals(PACKAGE + "-hsp/-/proxy-fixture-1.0.0.har")) {
          body = corrupt.get() ? new byte[] {9, 8, 7} : har;
        } else if (path.equals(PACKAGE + "-hsp/-/binary.hsp")) {
          body = HSP;
        } else {
          exchange.sendResponseHeaders(404, -1);
          return;
        }
        exchange.getResponseHeaders().add("ETag", "\"fixture-v1\"");
        if (!corrupt.get() && "\"fixture-v1\"".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
          exchange.sendResponseHeaders(304, -1);
        } else {
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
        }
      }
    });
    upstream.start();
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("server.port", "0");
    properties.put("management.server.port", "0");
    properties.put("spring.datasource.url", jdbcUrl);
    properties.put("spring.datasource.username", "kkrepo");
    properties.put("spring.datasource.password", "kkrepo");
    properties.put("spring.datasource.hikari.maximum-pool-size", "8");
    properties.put("kkrepo.database.type", databaseType);
    properties.put("kkrepo.storage.file.base-dir", directory.toString());
    properties.put("kkrepo.security.encryption.credential-secret", "ohpm-runtime-credential-secret");
    properties.put("kkrepo.security.encryption.api-key-payload-secret", "ohpm-runtime-api-key-secret");
    properties.put("kkrepo.security.outbound.allow-private-addresses", "true");
    properties.put("kkrepo.catalog-cache.refresh-interval-ms", "3600000");
    properties.put("kkrepo.catalog-cache.initial-delay-ms", "3600000");
    properties.put("kkrepo.catalog-cache.jdbc.initial-delay-ms", "3600000");
    properties.put("kkrepo.blob-gc.enabled", "false");
    properties.put("kkrepo.repository-index-rebuild.enabled", "false");
    properties.put("kkrepo.maven.metadata-rebuild.enabled", "false");
    properties.put("kkrepo.storage.file.temp-cleanup-enabled", "false");
    try (var first = start(properties); var second = start(properties); var client = HttpClient.newHttpClient()) {
      var security = first.getBean(SecurityManagementService.class);
      security.initializeAdmin(new AdminBootstrapCommand("12345678", "12345678", false));
      first.getBean(BlobStoresController.class).create(new BlobStoresController.BlobStoreRequest(
          "default", "file", "file", null, null, null, null, directory.resolve("blobs").toString(),
          null, null, null));
      first.getBean(RepositoryService.class).create(new CreateCommand(
          "ohpm", "ohpm-proxy", true, "default", false, null,
          new ProxySettings(remote, 60, 5, false, null, null, false, REMOTE_TOKEN, true),
          null, null, null, null));
      second.getBean(BlobStorageRegistry.class).refreshAll();
      String token = security.createApiKey(new ApiKeyCommand("GenericToken", "Local", "admin",
          "OHPM proxy integration", "ACTIVE", List.of(), null, null, null)).token();
      String firstUrl = url(first) + "/repository/ohpm/";
      String secondUrl = url(second) + "/repository/ohpm/";
      assertEquals(200, response(client, firstUrl + "-/ping", null).statusCode());
      assertEquals(401, response(client, secondUrl + PACKAGE, null).statusCode());
      var metadata = response(client, firstUrl + PACKAGE, token);
      assertEquals(200, metadata.statusCode(), new String(metadata.body(), StandardCharsets.UTF_8));
      assertEquals(200, response(client, secondUrl + PACKAGE, token).statusCode());
      assertEquals(1, metadataRequests.get(), "Replica must reuse metadata from the shared database");
      String download = JSON.readTree(metadata.body()).path("versions").path("1.0.0").path("dist").path("tarball").asText();
      assertTrue(download.startsWith(firstUrl));
      var fromFirst = client.sendAsync(request(download, token), HttpResponse.BodyHandlers.ofByteArray());
      var fromSecond = client.sendAsync(request(download.replace(firstUrl, secondUrl), token), HttpResponse.BodyHandlers.ofByteArray());
      assertArrayEquals(har, fromFirst.get(30, TimeUnit.SECONDS).body());
      assertArrayEquals(har, fromSecond.get(30, TimeUnit.SECONDS).body());
      var runtime = first.getBean(RepositoryRuntimeRegistry.class).resolve("ohpm").orElseThrow();
      String filePath = PACKAGE + "/-/" + PACKAGE + "-1.0.0.har";
      assertTrue(first.getBean(AssetDao.class).findAssetByPath(runtime.id(), filePath).isPresent());
      first.getBean(NexusLikeCacheController.class).invalidate(runtime.id(), NexusCacheType.METADATA);
      assertEquals(200, response(client, secondUrl + PACKAGE, token).statusCode());
      assertEquals(2, metadataRequests.get());
      corrupt.set(true);
      first.getBean(NexusLikeCacheController.class).invalidate(runtime.id(), NexusCacheType.CONTENT);
      assertEquals(502, response(client, download.replace(firstUrl, secondUrl), token).statusCode());
      corrupt.set(false);
      assertArrayEquals(har, response(client, download, token).body());
      var hspMetadata = JSON.readTree(response(client, secondUrl + PACKAGE + "-hsp", token).body())
          .path("versions").path("1.0.0").path("dist");
      assertArrayEquals(har, response(client, hspMetadata.path("tarball").asText(), token).body());
      assertArrayEquals(HSP, response(client, hspMetadata.path("resolved_hsp").asText(), token).body());
      var assets = first.getBean(AssetDao.class);
      String hspPrefix = PACKAGE + "-hsp/-/" + PACKAGE + "-hsp-1.0.0";
      var interfaceHar = assets.findAssetByPath(runtime.id(), hspPrefix + ".har").orElseThrow();
      var binaryHsp = assets.findAssetByPath(runtime.id(), hspPrefix + ".hsp").orElseThrow();
      assertNotNull(interfaceHar.componentId());
      assertEquals(interfaceHar.componentId(), binaryHsp.componentId());
      for (String method : List.of("PUT", "POST", "DELETE")) {
        String target = method.equals("POST") ? firstUrl + "stream/" + PACKAGE : firstUrl + PACKAGE;
        assertEquals(405, client.send(HttpRequest.newBuilder(URI.create(target)).header("Authorization", token)
            .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
      }
      if (Boolean.getBoolean("kkrepo.ohpm.client")) install(firstUrl, token, PACKAGE + "@1.0.0", "controlled");
      if (Boolean.getBoolean("kkrepo.ohpm.publicProxy")) {
        first.getBean(RepositoryService.class).create(new CreateCommand(
            "ohpm-public", "ohpm-proxy", true, "default", false, null,
            new ProxySettings("https://ohpm.openharmony.cn/ohpm", 60, 5, false,
                null, null, null, null, null, null, null, null, null, null, null, null,
                List.of("contentcenter-drcn.dbankcdn.cn")), null, null, null, null));
        install(url(first) + "/repository/ohpm-public/", token, "@ohos/axios@2.2.15", "public");
        var publicRuntime = first.getBean(RepositoryRuntimeRegistry.class).resolve("ohpm-public").orElseThrow();
        assertTrue(assets.findAssetByPath(publicRuntime.id(), "@ohos/axios/-/@ohos/axios-2.2.15.har").isPresent(),
            "The public package must be downloaded through the kkrepo proxy");
      }
    } finally {
      upstream.stop(0);
    }
  }

  private void install(String registry, String token, String coordinate, String label) throws Exception {
    Path project = Files.createDirectory(directory.resolve("client-" + label));
    URI endpoint = URI.create(registry);
    Files.writeString(project.resolve(".ohpmrc"), """
        registry=%s
        //%s%s:_auth=%s
        cache=%s
        fetch_timeout=15000
        log_level=error
        retry_times=0
        http_proxy=
        https_proxy=
        no_proxy=127.0.0.1,localhost
        """.formatted(registry, endpoint.getRawAuthority(), endpoint.getPath(), token, project.resolve("cache")));
    Files.writeString(project.resolve("oh-package.json5"), "{\"name\":\"proxy-test\",\"version\":\"1.0.0\"}");
    String command = System.getProperty("kkrepo.ohpm.command", "ohpm");
    String expected = System.getProperty("kkrepo.ohpm.expectedVersion", "6.0.1");
    assertEquals(expected, client(project, token, command, "--version").trim());
    client(project, token, command, "install", coordinate);
    assertTrue(Files.isDirectory(project.resolve("oh_modules")));
    assertTrue(Files.isDirectory(project.resolve("oh_modules").resolve(coordinate.substring(0, coordinate.lastIndexOf('@')))));
    assertTrue(Files.isRegularFile(project.resolve("oh-package-lock.json5")));
  }

  private static String client(Path project, String token, String... arguments) throws Exception {
    Path log = Files.createTempFile(project, "client-", ".log");
    var process = new ProcessBuilder(arguments).directory(project.toFile()).redirectErrorStream(true)
        .redirectOutput(log.toFile()).start();
    boolean finished = process.waitFor(60, TimeUnit.SECONDS);
    if (!finished) process.destroyForcibly();
    assertTrue(finished, "OHPM client timed out");
    String output = Files.readString(log).replace(token, "<redacted>");
    assertEquals(0, process.exitValue(), output);
    return output;
  }

  private static byte[] har() throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes))) {
      for (var entry : Map.of(
          "package/oh-package.json5", "{\"name\":\"" + PACKAGE + "\",\"version\":\"1.0.0\",\"main\":\"Index.ets\"}",
          "package/Index.ets", "export const fixture = 'proxy';").entrySet()) {
        byte[] content = entry.getValue().getBytes(StandardCharsets.UTF_8);
        var header = new TarArchiveEntry(entry.getKey());
        header.setSize(content.length);
        tar.putArchiveEntry(header);
        tar.write(content);
        tar.closeArchiveEntry();
      }
    }
    return bytes.toByteArray();
  }

  private static HttpRequest request(String url, String token) {
    var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
    if (token != null) request.header("Authorization", token);
    return request.GET().build();
  }

  private static HttpResponse<byte[]> response(HttpClient client, String url, String token) throws Exception {
    return client.send(request(url, token), HttpResponse.BodyHandlers.ofByteArray());
  }

  private static ConfigurableApplicationContext start(Map<String, Object> properties) {
    return new SpringApplicationBuilder(KkRepoApplication.class).profiles("test").run(properties.entrySet().stream()
        .map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new));
  }

  private static String url(ConfigurableApplicationContext context) {
    return "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
  }
}
