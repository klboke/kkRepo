package com.github.klboke.kkrepo.server.config;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.server.browse.BrowseDirectoryDeletionService;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.CreateCommand;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.HostedSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real dual-replica preview, authorization, exact path identity and rollback on both SQL engines. */
final class BrowseDirectoryDeletionSmokeChecks {
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final ObjectMapper JSON = new ObjectMapper();

  static void exercise(ConfigurableApplicationContext first, ConfigurableApplicationContext second) throws Exception {
    first.getBean(RepositoryService.class).create(new CreateCommand(
        "directory-raw", "raw-hosted", true, "smoke-file", true,
        new HostedSettings("ALLOW", null, null), null, null, null, null, null));
    second.getBean(BlobStorageRegistry.class).refreshAll();
    for (String directory : List.of("Releases", "releases", "releases-old", "literalXX")) {
      assertEquals(201, request(first, "PUT", content(directory + "/nested/a.txt"), "original", true).statusCode());
    }
    var runtime = first.getBean(com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry.class)
        .resolve("directory-raw").orElseThrow();
    // Seed exact storage identities through the real writer; Raw HTTP retains URI escapes.
    for (String directory : List.of("réleases", "literal%_")) {
      first.getBean(com.github.klboke.kkrepo.server.raw.RawHostedService.class).put(runtime,
          directory + "/nested/a.txt", new java.io.ByteArrayInputStream("original".getBytes(StandardCharsets.UTF_8)),
          "application/octet-stream", "admin", null);
    }
    String endpoint = "/internal/browse/directory-raw/directory-deletion";
    assertEquals(401, request(second, "GET", endpoint + "?path=releases", null, false).statusCode());
    JsonNode old = preview(first, endpoint, "releases");
    assertEquals(1, old.get("assetCount").asInt());
    assertEquals(201, request(second, "PUT", content("releases/nested/b.txt"), "new", true).statusCode());
    assertEquals(409, request(second, "POST", endpoint, command(old), true).statusCode());
    assertEquals(200, request(first, "GET", content("releases/nested/a.txt"), null, true).statusCode());
    JsonNode current = preview(first, endpoint, "releases");
    assertEquals(2, current.get("assetCount").asInt());
    var wrong = JSON.createObjectNode().put("path", "releases").put("confirmationPath", "Releases")
        .put("selectionToken", current.get("selectionToken").asText());
    assertEquals(400, request(second, "POST", endpoint, wrong.toString(), true).statusCode());
    var deleted = request(second, "POST", endpoint, command(current), true);
    assertEquals(200, deleted.statusCode(), deleted.body());
    assertEquals(2, JSON.readTree(deleted.body()).get("deletedAssets").asInt());
    long repositoryId = second.getBean(RepositoryDao.class).findByName("directory-raw").orElseThrow().id();
    assertTrue(second.getBean(AssetDao.class).findAssetByPath(repositoryId, "releases/nested/a.txt").isEmpty());
    // Download snapshots observe the shared watermark on the existing two-second polling cadence.
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (request(first, "GET", content("releases/nested/a.txt"), null, true).statusCode() != 404) {
      assertTrue(System.nanoTime() < deadline, "deletion must become visible on the sibling replica");
      Thread.sleep(50);
    }
    for (String directory : List.of("Releases", "releases-old", "literalXX")) {
      assertEquals("original", request(first, "GET", content(directory + "/nested/a.txt"), null, true).body());
    }
    assertTrue(second.getBean(AssetDao.class).findAssetByPath(repositoryId, "réleases/nested/a.txt").isPresent());
    // % and _ are path characters, not SQL wildcard selectors.
    var literal = preview(first, endpoint, "literal%_");
    assertEquals(1, literal.get("assetCount").asInt());
    assertEquals(200, request(second, "POST", endpoint, command(literal), true).statusCode());
    assertEquals("original", request(first, "GET", content("literalXX/nested/a.txt"), null, true).body());
    // A surrounding failure rolls back asset rows, Browse nodes and blob tombstones together.
    var service = second.getBean(BrowseDirectoryDeletionService.class);
    var rollback = service.preview("directory-raw", "Releases");
    var dao = second.getBean(AssetDao.class);
    var asset = dao.findAssetByPath(repositoryId, "Releases/nested/a.txt").orElseThrow();
    var transaction = new TransactionTemplate(second.getBean(PlatformTransactionManager.class));
    assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
      service.delete("directory-raw", new BrowseDirectoryDeletionService.Command(
          rollback.path(), rollback.path(), rollback.selectionToken()), "admin");
      throw new IllegalStateException("simulated failure after deletion");
    }));
    assertTrue(dao.findAssetById(asset.id()).isPresent());
    assertEquals(Boolean.TRUE, transaction.execute(status -> dao.lockLiveBlobById(asset.assetBlobId()).isPresent()));
    assertEquals(1, service.preview("directory-raw", "Releases").assetCount());
    assertEquals("original", request(first, "GET", content("Releases/nested/a.txt"), null, true).body());
    exerciseNpm(first, second);
  }

  private static void exerciseNpm(ConfigurableApplicationContext first, ConfigurableApplicationContext second) throws Exception {
    first.getBean(RepositoryService.class).create(new CreateCommand(
        "directory-npm", "npm-hosted", true, "smoke-file", false,
        new HostedSettings("ALLOW", null, null), null, null, null, null, null));
    var runtime = first.getBean(com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry.class)
        .resolve("directory-npm").orElseThrow();
    var npm = first.getBean(com.github.klboke.kkrepo.server.npm.NpmHostedService.class);
    for (String name : List.of("@scope/alpha", "@scope/bravo", "@other/keep")) {
      var versions = new java.util.LinkedHashMap<String, Object>();
      var attachments = new java.util.LinkedHashMap<String, Object>();
      for (String version : List.of("1.0.0", "2.0.0")) {
        String filename = name.substring(name.indexOf('/') + 1) + "-" + version + ".tgz";
        versions.put(version, Map.of("name", name, "version", version, "dist", Map.of("tarball",
            "http://localhost/repository/directory-npm/" + name + "/-/" + filename)));
        attachments.put(filename, Map.of("content_type", "application/octet-stream", "data",
            Base64.getEncoder().encodeToString((name + version).getBytes(StandardCharsets.UTF_8))));
      }
      byte[] payload = JSON.writeValueAsBytes(Map.of("name", name, "versions", versions,
          "dist-tags", Map.of("latest", "2.0.0"), "_attachments", attachments));
      npm.putPackage(runtime, com.github.klboke.kkrepo.protocol.npm.NpmPackageId.parse(name), null,
          new java.io.ByteArrayInputStream(payload), "admin", null);
    }
    var storedAssets = first.getBean(AssetDao.class);
    var alpha = storedAssets.findAssetByPath(runtime.id(), "@scope/alpha/-/alpha-1.0.0.tgz").orElseThrow();
    assertFalse(storedAssets.hasComponentAssetsOutside(alpha.componentId(), List.of(alpha.id())));
    assertTrue(storedAssets.hasComponentAssetsOutside(alpha.componentId(), List.of()));
    assertTrue(storedAssets.hasComponentAssetsOutside(alpha.componentId(), List.of(Long.MAX_VALUE)));
    String route = "/internal/browse/directory-npm/directory-deletion";
    var preview = preview(first, route, "@scope");
    assertEquals(4, preview.get("assetCount").asInt());
    assertEquals(2, preview.get("metadataAssetCount").asInt());
    var result = request(second, "POST", route, command(preview), true);
    assertEquals(200, result.statusCode(), result.body());
    assertEquals(4, JSON.readTree(result.body()).get("deletedAssets").asInt());
    var assets = second.getBean(AssetDao.class);
    assertTrue(assets.findAssetByPath(runtime.id(), "@scope/alpha/-/alpha-1.0.0.tgz").isEmpty());
    assertTrue(assets.findAssetByPath(runtime.id(), "@scope/bravo/-/bravo-2.0.0.tgz").isEmpty());
    assertTrue(assets.findAssetByPath(runtime.id(), "@other/keep/-/keep-1.0.0.tgz").isPresent());
    var root = request(second, "GET", "/repository/directory-npm/@scope/alpha", null, true);
    assertTrue(root.statusCode() == 404 || (root.statusCode() == 200
        && JSON.readTree(root.body()).path("versions").isEmpty()), root.body());
  }

  static JsonNode preview(ConfigurableApplicationContext context, String endpoint, String path) throws Exception {
    var result = request(context, "GET", endpoint + "?path=" + encode(path), null, true);
    assertEquals(200, result.statusCode(), result.body());
    return JSON.readTree(result.body());
  }

  static String command(JsonNode preview) throws Exception {
    return JSON.writeValueAsString(Map.of("path", preview.get("path").asText(),
        "confirmationPath", preview.get("path").asText(), "selectionToken", preview.get("selectionToken").asText()));
  }

  static String content(String path) {
    return "/repository/directory-raw/" + java.util.Arrays.stream(path.split("/"))
        .map(BrowseDirectoryDeletionSmokeChecks::encode).collect(java.util.stream.Collectors.joining("/"));
  }

  static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }

  static HttpResponse<String> request(ConfigurableApplicationContext context, String method,
      String path, String body, boolean authenticated) throws Exception {
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(30)).header("Accept", "application/json")
        .header("Content-Type", "POST".equals(method) ? "application/json" : "application/octet-stream");
    if (authenticated) builder.header("Authorization", "Basic " + Base64.getEncoder()
        .encodeToString("admin:SmokeAdmin1234!".getBytes(StandardCharsets.UTF_8)));
    return HTTP.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }
}
