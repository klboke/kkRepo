package com.github.klboke.kkrepo.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.protocol.docker.DockerConstants;
import com.github.klboke.kkrepo.protocol.docker.DockerDigest;
import com.github.klboke.kkrepo.server.docker.DockerBlobStore;
import com.github.klboke.kkrepo.server.docker.DockerManifestStore;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.CreateCommand;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.DockerSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.GroupSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.HostedSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** Runs through real HTTP, JDBC, transactions and sibling replicas in both database smoke jobs. */
final class DockerBrowseDeleteSmokeChecks {
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private DockerBrowseDeleteSmokeChecks() {}

  static void exercise(ConfigurableApplicationContext first, ConfigurableApplicationContext second)
      throws Exception {
    RepositoryService repositories = first.getBean(RepositoryService.class);
    repositories.create(new CreateCommand("smoke-docker", "docker-hosted", true, "smoke-file", true,
        new HostedSettings("ALLOW", null, null), null, null,
        new DockerSettings(false, null, null), null, null));
    repositories.create(new CreateCommand("smoke-docker-group", "docker-group", true, "smoke-file", true,
        null, null, null, new DockerSettings(false, null, null), null,
        new GroupSettings(List.of("smoke-docker"))));
    var runtime = first.getBean(RepositoryRuntimeRegistry.class).resolve("smoke-docker").orElseThrow();
    var blobs = first.getBean(DockerBlobStore.class);
    byte[] config = "{}".getBytes(StandardCharsets.UTF_8);
    byte[] layer = "shared-docker-layer".getBytes(StandardCharsets.UTF_8);
    DockerDigest configDigest = DockerDigest.sha256(config);
    DockerDigest layerDigest = DockerDigest.sha256(layer);
    for (byte[] bytes : List.of(config, layer)) {
      blobs.putBlob(runtime, DockerDigest.sha256(bytes), new ByteArrayInputStream(bytes), bytes.length,
          "application/octet-stream", "admin", "127.0.0.1");
    }
    byte[] body = ("""
        {"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json",
         "config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"%s","size":%d},
         "layers":[{"mediaType":"application/vnd.oci.image.layer.v1.tar","digest":"%s","size":%d}]}
        """).formatted(configDigest.value(), config.length, layerDigest.value(), layer.length)
        .getBytes(StandardCharsets.UTF_8);
    var manifests = first.getBean(DockerManifestStore.class);
    for (String image : List.of("team/app", "team/other", "team", "team/manifests")) {
      manifests.putManifest(runtime, image, "latest", body, DockerConstants.MEDIA_TYPE_OCI_MANIFEST,
          "admin", "127.0.0.1", true);
    }
    var app = manifests.putManifest(runtime, "team/app", "1.0.0", body,
        DockerConstants.MEDIA_TYPE_OCI_MANIFEST, "admin", "127.0.0.1", true);
    second.getBean(BlobStorageRegistry.class).refreshAll();

    HttpResponse<String> browse = request(second, "GET",
        "/internal/browse/smoke-docker?path=team/app/manifests");
    assertEquals(200, browse.statusCode(), browse.body());
    assertTrue(browse.body().contains("team/app/manifests/latest"), browse.body());
    assertManifest(second, "smoke-docker-group", "team/app", "latest", 200);
    assertManifest(second, "smoke-docker-group", "team/app", "1.0.0", 200);

    delete(first, "smoke-docker", "team/app/manifests/latest", "");
    assertManifest(second, "smoke-docker", "team/app", "latest", 404);
    assertManifest(second, "smoke-docker-group", "team/app", "latest", 404);
    assertManifest(second, "smoke-docker", "team/app", "1.0.0", 200);
    assertManifest(second, "smoke-docker", "team/app", app.manifest().digest(), 200);

    delete(first, "smoke-docker-group", "team/app/manifests/" + app.manifest().digest(),
        "&source=smoke-docker");
    assertManifest(second, "smoke-docker", "team/app", "1.0.0", 404);
    assertManifest(second, "smoke-docker-group", "team/app", "1.0.0", 404);
    assertManifest(second, "smoke-docker", "team/app", app.manifest().digest(), 404);
    assertManifest(second, "smoke-docker", "team/other", "latest", 200);
    assertTrue(second.getBean(AssetDao.class).findBlobById(app.blob().id()).isPresent(),
        "the manifest body is still shared by another image");
    assertEquals(200, request(second, "GET", "/v2/smoke-docker/team/other/blobs/" + layerDigest.value())
        .statusCode(), "shared layers remain downloadable");

    for (String directory : List.of("team", "team/manifests", "team/other/manifests")) {
      assertEquals(400, request(first, "DELETE",
          "/internal/browse/smoke-docker?path=" + directory).statusCode());
    }
    assertManifest(second, "smoke-docker", "team/other", "latest", 200);
    assertManifest(second, "smoke-docker", "team", "latest", 200);
    assertManifest(second, "smoke-docker", "team/manifests", "latest", 200);
    delete(first, "smoke-docker", "team/manifests/manifests/latest", "");
    assertManifest(second, "smoke-docker", "team/manifests", "latest", 404);
    assertManifest(second, "smoke-docker", "team", "latest", 200);
    assertEquals(404, request(first, "DELETE",
        "/internal/browse/smoke-docker?path=team/manifests/manifests/latest").statusCode());
  }

  private static void delete(ConfigurableApplicationContext context, String repository,
      String path, String source) throws Exception {
    HttpResponse<String> deleted = request(context, "DELETE", "/internal/browse/" + repository
        + "?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8) + source);
    assertEquals(200, deleted.statusCode(), deleted.body());
  }

  private static void assertManifest(ConfigurableApplicationContext context, String repository,
      String image, String reference, int status) throws Exception {
    HttpResponse<String> response = request(context, "GET",
        "/v2/" + repository + "/" + image + "/manifests/" + reference);
    assertEquals(status, response.statusCode(), response.body());
  }

  private static HttpResponse<String> request(ConfigurableApplicationContext context,
      String method, String path) throws Exception {
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(20))
        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
            "admin:SmokeAdmin1234!".getBytes(StandardCharsets.UTF_8)))
        .header("Accept", DockerConstants.MEDIA_TYPE_OCI_MANIFEST + ", application/json")
        .method(method, HttpRequest.BodyPublishers.noBody()).build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
