package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.docker.DockerConstants;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DockerRegistryBlackBoxCompatibilityTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build();

  @Test
  void registryV2EndpointsMatchNexusWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.enabled(),
        "Set COMPAT_DOCKER_ENABLED=true to run Docker Registry V2 black-box compatibility");
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and NEXUS_PLUS_COMPAT_BASE_URL to run Docker compatibility");

    Exchange referenceBase = get(config.nexus().v2Base());
    Exchange candidateBase = get(config.nexusPlus().v2Base());
    assertStatusFamily("GET /v2/", referenceBase, candidateBase);
    assertHeaderEquals("base API version", referenceBase, candidateBase, DockerConstants.API_VERSION_HEADER);

    Exchange referenceHeadBase = head(config.nexus().v2Base());
    Exchange candidateHeadBase = head(config.nexusPlus().v2Base());
    assertStatusFamily("HEAD /v2/", referenceHeadBase, candidateHeadBase);
    assertHeaderEquals("base HEAD API version", referenceHeadBase, candidateHeadBase,
        DockerConstants.API_VERSION_HEADER);

    if (!config.image().isBlank() && !config.reference().isBlank()) {
      String manifestPath = config.repositoryPath(config.image() + "/manifests/" + config.reference());
      Exchange referenceManifestHead = head(config.nexus().v2(manifestPath));
      Exchange candidateManifestHead = head(config.nexusPlus().v2(manifestPath));
      assertStatusFamily("HEAD manifest", referenceManifestHead, candidateManifestHead);
      if (referenceManifestHead.status() == 200 && candidateManifestHead.status() == 200) {
        assertHeaderPresent("manifest digest", referenceManifestHead, DockerConstants.CONTENT_DIGEST_HEADER);
        assertHeaderPresent("manifest digest", candidateManifestHead, DockerConstants.CONTENT_DIGEST_HEADER);
        assertHeaderPresent("manifest content type", referenceManifestHead, "Content-Type");
        assertHeaderPresent("manifest content type", candidateManifestHead, "Content-Type");
      }

      Exchange referenceManifest = get(config.nexus().v2(manifestPath), dockerAccept());
      Exchange candidateManifest = get(config.nexusPlus().v2(manifestPath), dockerAccept());
      assertStatusFamily("GET manifest", referenceManifest, candidateManifest);
      if (referenceManifest.status() == 200 && candidateManifest.status() == 200) {
        assertHeaderPresent("manifest digest", referenceManifest, DockerConstants.CONTENT_DIGEST_HEADER);
        assertHeaderPresent("manifest digest", candidateManifest, DockerConstants.CONTENT_DIGEST_HEADER);
      }

      Exchange referenceTags = get(config.nexus().v2(config.repositoryPath(config.image() + "/tags/list?n=1")));
      Exchange candidateTags = get(config.nexusPlus().v2(config.repositoryPath(config.image() + "/tags/list?n=1")));
      assertStatusFamily("GET tags/list", referenceTags, candidateTags);
      if (referenceTags.status() == 200 && candidateTags.status() == 200) {
        assertTrue(new String(referenceTags.body()).contains("\"tags\""), "reference tags body should be registry JSON");
        assertTrue(new String(candidateTags.body()).contains("\"tags\""), "kkrepo tags body should be registry JSON");
      }
    }

    String uploadStartPath = config.repositoryPath(config.uploadImage() + "/blobs/uploads/");
    Exchange referenceUploadStart = post(config.nexus().v2(uploadStartPath));
    Exchange candidateUploadStart = post(config.nexusPlus().v2(uploadStartPath));
    assertStatusFamily("POST blobs/uploads", referenceUploadStart, candidateUploadStart);
    if (isUploadAccepted(referenceUploadStart.status()) && isUploadAccepted(candidateUploadStart.status())) {
      assertHeaderPresent("upload location", referenceUploadStart, "Location");
      assertHeaderPresent("upload location", candidateUploadStart, "Location");
      assertHeaderPresent("upload uuid", candidateUploadStart, DockerConstants.UPLOAD_UUID_HEADER);
    }

    if (config.writeEnabled()) {
      String tag = "compat-" + System.currentTimeMillis();
      String image = config.uploadImage();
      byte[] blob = ("kkrepo docker compat blob " + tag).getBytes(StandardCharsets.UTF_8);
      String blobDigest = "sha256:" + sha256(blob);
      String configDigest = "sha256:" + sha256(new byte[0]);
      pushBlob(config.nexusPlus(), config.repositoryPath(image), configDigest, new byte[0], true);
      pushBlob(config.nexusPlus(), config.repositoryPath(image), blobDigest, blob, true);
      String manifest = singleLayerManifest(blobDigest, blob.length);
      String manifestDigest = "sha256:" + sha256(manifest.getBytes(StandardCharsets.UTF_8));
      Exchange putManifest = put(
          config.nexusPlus(),
          config.nexusPlus().v2(config.repositoryPath(image + "/manifests/" + tag)),
          DockerConstants.MEDIA_TYPE_OCI_MANIFEST,
          manifest.getBytes(StandardCharsets.UTF_8),
          true);
      assertEquals(201, putManifest.status(), "manifest PUT should create an image");
      assertHeaderPresent("manifest PUT digest", putManifest, DockerConstants.CONTENT_DIGEST_HEADER);

      Exchange pulled = get(config.nexusPlus(), config.nexusPlus().v2(config.repositoryPath(image + "/manifests/" + tag)), dockerAccept(), true);
      assertEquals(200, pulled.status(), "pushed manifest should be readable");
      assertEquals(manifestDigest, pulled.header(DockerConstants.CONTENT_DIGEST_HEADER).orElse(""));

      Exchange pulledBlob = get(config.nexusPlus(), config.nexusPlus().v2(config.repositoryPath(image + "/blobs/" + blobDigest)), "*/*", true);
      assertEquals(200, pulledBlob.status(), "pushed blob should be readable");
      assertEquals(blobDigest, pulledBlob.header(DockerConstants.CONTENT_DIGEST_HEADER).orElse(""));

      String referrer = referrerManifest(manifestDigest);
      String referrerDigest = "sha256:" + sha256(referrer.getBytes(StandardCharsets.UTF_8));
      Exchange putReferrer = put(
          config.nexusPlus(),
          config.nexusPlus().v2(config.repositoryPath(image + "/manifests/" + referrerDigest)),
          DockerConstants.MEDIA_TYPE_OCI_MANIFEST,
          referrer.getBytes(StandardCharsets.UTF_8),
          true);
      assertEquals(201, putReferrer.status(), "subject artifact should be accepted");
      Exchange referrers = get(config.nexusPlus(), config.nexusPlus().v2(config.repositoryPath(
          image + "/referrers/" + manifestDigest + "?artifactType=" + encode("application/vnd.kkrepo.compat"))),
          DockerConstants.MEDIA_TYPE_OCI_INDEX, true);
      assertEquals(200, referrers.status(), "referrers should be readable");
      String referrersBody = new String(referrers.body(), StandardCharsets.UTF_8);
      assertTrue(referrersBody.contains(referrerDigest), "referrers body should include pushed artifact");

      Exchange deleteTag = delete(config.nexusPlus(), config.nexusPlus().v2(config.repositoryPath(image + "/manifests/" + tag)), true);
      assertEquals(202, deleteTag.status(), "tag delete should match Registry V2 accepted semantics");
    }
  }

  @Test
  void browseDeletionUsesNexusAdministrativeAssetMappingWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.enabled() && config.configured() && config.writeEnabled(),
        "Configure writable Nexus and kkrepo Docker endpoints to compare administrative deletion");
    assumeTrue(CompatDefaults.nexusPlusBaseUrl().isPresent(), "Configure the kkrepo application URL");
    Endpoint nexusUi = new Endpoint("nexus-ui", CompatDefaults.nexusBaseUrl(),
        CompatDefaults.nexusUsername(), CompatDefaults.nexusPassword());
    Endpoint browse = new Endpoint("kkrepo-browse", CompatDefaults.nexusPlusBaseUrl(),
        CompatDefaults.nexusPlusUsername(), CompatDefaults.nexusPlusPassword());
    String image = config.uploadImage() + "/browse-delete-" + System.nanoTime();
    byte[] layer = image.getBytes(StandardCharsets.UTF_8);
    String layerDigest = "sha256:" + sha256(layer);
    byte[] manifest = singleLayerManifest(layerDigest, layer.length).getBytes(StandardCharsets.UTF_8);
    String digest = "sha256:" + sha256(manifest);
    String imagePath = config.repositoryPath(image);
    for (Endpoint endpoint : List.of(config.nexus(), config.nexusPlus())) {
      pushBlob(endpoint, imagePath, "sha256:" + sha256(new byte[0]), new byte[0], true);
      pushBlob(endpoint, imagePath, layerDigest, layer, true);
      for (String tag : List.of("latest", "1.0.0")) {
        assertEquals(201, put(endpoint, endpoint.v2(imagePath + "/manifests/" + tag),
            DockerConstants.MEDIA_TYPE_OCI_MANIFEST, manifest, true).status());
      }
    }

    // Follow Nexus Browse's node -> readAsset -> deleteAsset mapping, not Registry V2 DELETE.
    deleteNexusBrowseAsset(nexusUi, config.repository(), imagePath, "latest", false);
    String browseUrl = "/internal/browse/" + encode(config.repository()) + "?path=";
    Exchange deletedTag = delete(browse,
        browse.resolve(browseUrl + encode(image + "/manifests/latest")), true);
    assertEquals(200, deletedTag.status());
    JsonNode deleted = JSON.readTree(deletedTag.body());
    assertEquals(config.repository(), deleted.path("repository").asText());
    assertEquals(config.repository(), deleted.path("sourceRepository").asText());
    assertEquals(image + "/manifests/latest", deleted.path("path").asText());
    assertEquals(1, deleted.path("deletedAssets").asInt());
    for (String reference : List.of("latest", "1.0.0", digest)) {
      Exchange expected = get(config.nexus(), config.nexus().v2(imagePath + "/manifests/" + reference),
          dockerAccept(), true);
      Exchange actual = get(config.nexusPlus(), config.nexusPlus().v2(imagePath + "/manifests/" + reference),
          dockerAccept(), true);
      assertEquals(reference.equals("latest") ? 404 : 200, expected.status());
      assertEquals(expected.status(), actual.status(), "reference after tag deletion " + reference);
    }

    // Both administrative APIs delete the selected digest entry while preserving tag assets.
    deleteNexusBrowseAsset(nexusUi, config.repository(), imagePath, digest, true);
    assertEquals(200, delete(browse,
        browse.resolve(browseUrl + encode(image + "/manifests/" + digest)), true).status());
    for (String reference : List.of("latest", "1.0.0")) {
      Exchange expected = get(config.nexus(), config.nexus().v2(imagePath + "/manifests/" + reference),
          dockerAccept(), true);
      Exchange actual = get(config.nexusPlus(), config.nexusPlus().v2(imagePath + "/manifests/" + reference),
          dockerAccept(), true);
      assertEquals(reference.equals("latest") ? 404 : 200, expected.status());
      assertEquals(expected.status(), actual.status(), "reference after digest deletion " + reference);
      if (expected.status() == 200) {
        assertArrayEquals(expected.body(), actual.body(), "retained tag manifest bytes");
      }
    }
    Exchange remaining = get(browse, browse.resolve(browseUrl + encode(image + "/manifests")),
        "application/json", true);
    assertEquals(200, remaining.status());
    assertTrue(new String(remaining.body(), StandardCharsets.UTF_8).contains(image + "/manifests/1.0.0"));
    assertFalse(new String(remaining.body(), StandardCharsets.UTF_8).contains(digest));
    assertEquals(404, get(browse, browse.resolve("/internal/browse/" + encode(config.repository())
        + "/attributes?path=" + encode(image + "/manifests/" + digest)), "application/json", true).status());
    assertEquals(404, delete(browse,
        browse.resolve(browseUrl + encode(image + "/manifests/" + digest)), true).status());
    deleteNexusBrowseAsset(nexusUi, config.repository(), imagePath, "1.0.0", false);
    assertEquals(200, delete(browse,
        browse.resolve(browseUrl + encode(image + "/manifests/1.0.0")), true).status());
    for (Endpoint endpoint : List.of(config.nexus(), config.nexusPlus())) {
      assertEquals(404, get(endpoint, endpoint.v2(imagePath + "/manifests/1.0.0"),
          dockerAccept(), true).status());
    }
  }

  // Captured from Nexus 3.94's ComponentAssetTree and Browse controllers; see
  // compat-test/docker-browse-delete-mapping.md for the source bundle and request contracts.
  private static void deleteNexusBrowseAsset(Endpoint endpoint, String repository,
      String imagePath, String reference, boolean digest) throws Exception {
    String nodePath = "v2/" + imagePath + (digest ? "/manifests" : "/tags");
    JsonNode selected = null;
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    do {
      JsonNode rows = nexusUi(endpoint, "coreui_Browse", "read",
          List.of(Map.of("repositoryName", repository, "node", nodePath))).path("data");
      for (JsonNode row : rows) {
        if (reference.equals(row.path("text").asText())) {
          selected = row;
          break;
        }
      }
      if (selected == null) Thread.sleep(100);
    } while (selected == null && System.nanoTime() < deadline);
    assertTrue(selected != null, "Nexus Browse did not expose " + nodePath + "/" + reference);
    assertEquals("asset", selected.path("type").asText());
    JsonNode asset = nexusUi(endpoint, "coreui_Component", "readAsset",
        List.of(selected.path("assetId").asText(), repository)).path("data");
    String assetPath = "/v2/" + imagePath + "/manifests/" + reference;
    assertEquals(assetPath, asset.path("name").asText());
    assertEquals(repository, asset.path("repositoryName").asText());
    JsonNode result = nexusUi(endpoint, "coreui_Component", "deleteAsset",
        List.of(asset.path("id").asText(), asset.path("repositoryName").asText()));
    assertEquals(JSON.valueToTree(List.of(assetPath)), result.path("data"),
        "Nexus UI response must identify the selected deleted asset");
    JsonNode missing = nexusUi(endpoint, "coreui_Component", "readAsset",
        List.of(asset.path("id").asText(), repository), false);
    assertEquals("HTTP 404 Not Found", missing.path("message").asText(),
        "The selected Nexus asset must be absent after administrative deletion");
  }

  private static JsonNode nexusUi(Endpoint endpoint, String action, String method, List<?> data)
      throws Exception {
    return nexusUi(endpoint, action, method, data, true);
  }

  private static JsonNode nexusUi(Endpoint endpoint, String action, String method, List<?> data,
      boolean expectedSuccess)
      throws Exception {
    byte[] payload = JSON.writeValueAsBytes(Map.of(
        "action", action, "method", method, "type", "rpc", "tid", 1, "data", data));
    Exchange response = send(auth(endpoint, true,
        HttpRequest.newBuilder(endpoint.resolve("/service/extdirect"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))));
    assertEquals(200, response.status(), "Nexus UI " + action + "." + method);
    JsonNode envelope = JSON.readTree(response.body());
    JsonNode result = (envelope.isArray() ? envelope.path(0) : envelope).path("result");
    assertEquals(expectedSuccess, result.path("success").asBoolean(), result.toString());
    return result;
  }

  @Test
  void pathBasedRoutingMatchesConnectorWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.enabled(),
        "Set COMPAT_DOCKER_ENABLED=true to run Docker Registry V2 black-box compatibility");
    assumeTrue(config.pathBasedConfigured(),
        "Set connector and path-based Docker endpoints to compare routing forms");

    String tag = "path-" + System.currentTimeMillis();
    String image = config.uploadImage();
    byte[] blob = ("kkrepo docker path compat blob " + tag).getBytes(StandardCharsets.UTF_8);
    String blobDigest = "sha256:" + sha256(blob);
    pushBlob(config.nexusPlus(), image, "sha256:" + sha256(new byte[0]), new byte[0], true);
    pushBlob(config.nexusPlus(), image, blobDigest, blob, true);
    String manifest = singleLayerManifest(blobDigest, blob.length);
    Exchange putManifest = put(
        config.nexusPlus(),
        config.nexusPlus().v2(image + "/manifests/" + tag),
        DockerConstants.MEDIA_TYPE_OCI_MANIFEST,
        manifest.getBytes(StandardCharsets.UTF_8),
        true);
    assertEquals(201, putManifest.status(), "connector manifest PUT should create an image");

    String pathBased = config.repository() + "/" + image + "/manifests/" + tag;
    Exchange viaConnector = get(config.nexusPlus(), config.nexusPlus().v2(image + "/manifests/" + tag),
        dockerAccept(), true);
    Exchange viaPathBased = get(config.nexusPlusPathBased(), config.nexusPlusPathBased().v2(pathBased),
        dockerAccept(), true);
    assertEquals(viaConnector.status(), viaPathBased.status(), "path-based manifest status");
    assertEquals(viaConnector.header(DockerConstants.CONTENT_DIGEST_HEADER).orElse(""),
        viaPathBased.header(DockerConstants.CONTENT_DIGEST_HEADER).orElse(""),
        "path-based manifest digest");
  }

  @Test
  void proxyAndGroupReadCompatibilityWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.enabled(),
        "Set COMPAT_DOCKER_ENABLED=true to run Docker Registry V2 black-box compatibility");
    assumeTrue(config.proxyGroupConfigured(),
        "Set Docker proxy/group endpoints to compare read compatibility");

    assertReadPathMatches("proxy manifest", config.nexusProxy(), config.nexusPlusProxy(),
        config.proxyImage() + "/manifests/" + config.proxyReference(), dockerAccept());
    assertReadPathMatches("proxy tags", config.nexusProxy(), config.nexusPlusProxy(),
        config.proxyImage() + "/tags/list?n=1", "*/*");

    if (!config.groupImage().isBlank() && !config.groupReference().isBlank()) {
      Exchange referenceManifest = get(config.nexusGroup().v2(
          config.groupImage() + "/manifests/" + config.groupReference()), dockerAccept());
      Exchange candidateManifest = get(config.nexusPlusGroup().v2(
          config.groupImage() + "/manifests/" + config.groupReference()), dockerAccept());
      assertStatusFamily("group manifest", referenceManifest, candidateManifest);
      if (referenceManifest.status() == 200 && candidateManifest.status() == 200) {
        assertHeaderPresent("group manifest digest", referenceManifest, DockerConstants.CONTENT_DIGEST_HEADER);
        assertHeaderPresent("group manifest digest", candidateManifest, DockerConstants.CONTENT_DIGEST_HEADER);
      }
    }
  }

  @Test
  void hostedWritePolicyCompatibilityWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.enabled(),
        "Set COMPAT_DOCKER_ENABLED=true to run Docker Registry V2 black-box compatibility");
    assumeTrue(config.writePolicyConfigured(),
        "Set Docker write-policy endpoints to compare ALLOW_ONCE and DENY behavior");

    String image = config.writePolicyImage();
    byte[] blob = ("kkrepo docker write-policy compat " + System.currentTimeMillis())
        .getBytes(StandardCharsets.UTF_8);
    String blobDigest = "sha256:" + sha256(blob);
    String configDigest = "sha256:" + sha256(new byte[0]);
    pushBlob(config.nexusAllowOnce(), image, configDigest, new byte[0], true);
    pushBlob(config.nexusAllowOnce(), image, blobDigest, blob, true);
    pushBlob(config.nexusPlusAllowOnce(), image, configDigest, new byte[0], true);
    pushBlob(config.nexusPlusAllowOnce(), image, blobDigest, blob, true);
    String manifest = singleLayerManifest(blobDigest, blob.length);
    String tag = "once-" + System.currentTimeMillis();

    Exchange referenceFirst = put(config.nexusAllowOnce(), config.nexusAllowOnce().v2(image + "/manifests/" + tag),
        DockerConstants.MEDIA_TYPE_OCI_MANIFEST, manifest.getBytes(StandardCharsets.UTF_8), true);
    Exchange candidateFirst = put(config.nexusPlusAllowOnce(), config.nexusPlusAllowOnce().v2(image + "/manifests/" + tag),
        DockerConstants.MEDIA_TYPE_OCI_MANIFEST, manifest.getBytes(StandardCharsets.UTF_8), true);
    assertStatusFamily("ALLOW_ONCE first manifest PUT", referenceFirst, candidateFirst);
    assertTrue(referenceFirst.status() / 100 == 2, "reference first PUT should succeed");
    assertTrue(candidateFirst.status() / 100 == 2, "kkrepo first PUT should succeed");

    Exchange referenceSecond = put(config.nexusAllowOnce(), config.nexusAllowOnce().v2(image + "/manifests/" + tag),
        DockerConstants.MEDIA_TYPE_OCI_MANIFEST, manifest.getBytes(StandardCharsets.UTF_8), true);
    Exchange candidateSecond = put(config.nexusPlusAllowOnce(), config.nexusPlusAllowOnce().v2(image + "/manifests/" + tag),
        DockerConstants.MEDIA_TYPE_OCI_MANIFEST, manifest.getBytes(StandardCharsets.UTF_8), true);
    assertStatusFamily("ALLOW_ONCE second manifest PUT", referenceSecond, candidateSecond);
    assertTrue(referenceSecond.status() / 100 == 4, "reference second PUT should be denied");
    assertTrue(candidateSecond.status() / 100 == 4, "kkrepo second PUT should be denied");

    Exchange referenceDeny = post(config.nexusDeny(), config.nexusDeny().v2(image + "/blobs/uploads/"), true);
    Exchange candidateDeny = post(config.nexusPlusDeny(), config.nexusPlusDeny().v2(image + "/blobs/uploads/"), true);
    assertStatusFamily("DENY upload start", referenceDeny, candidateDeny);
    assertTrue(referenceDeny.status() / 100 == 4, "reference DENY upload should be rejected");
    assertTrue(candidateDeny.status() / 100 == 4, "kkrepo DENY upload should be rejected");
  }

  private static void assertReadPathMatches(
      String label, Endpoint reference, Endpoint candidate, String path, String accept) throws Exception {
    Exchange referenceExchange = get(reference.v2(path), accept);
    Exchange candidateExchange = get(candidate.v2(path), accept);
    assertStatusFamily(label, referenceExchange, candidateExchange);
    if (referenceExchange.status() == 200 && candidateExchange.status() == 200) {
      assertHeaderEquals(label + " API version", referenceExchange, candidateExchange,
          DockerConstants.API_VERSION_HEADER);
    }
  }

  private static Exchange get(URI uri) throws Exception {
    return get(uri, "*/*");
  }

  private static Exchange get(URI uri, String accept) throws Exception {
    return get(null, uri, accept, false);
  }

  private static Exchange get(Endpoint endpoint, URI uri, String accept, boolean authenticated) throws Exception {
    return send(auth(endpoint, authenticated, HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(60))
        .header("Accept", accept)
        .header("User-Agent", "kkrepo-docker-compat-test/1")
        .GET()));
  }

  private static Exchange head(URI uri) throws Exception {
    return send(HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(60))
        .header("Accept", dockerAccept())
        .header("User-Agent", "kkrepo-docker-compat-test/1")
        .method("HEAD", HttpRequest.BodyPublishers.noBody()));
  }

  private static Exchange post(URI uri) throws Exception {
    return post(null, uri, false);
  }

  private static Exchange post(Endpoint endpoint, URI uri, boolean authenticated) throws Exception {
    return send(auth(endpoint, authenticated, HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(60))
        .header("User-Agent", "kkrepo-docker-compat-test/1")
        .POST(HttpRequest.BodyPublishers.noBody())));
  }

  private static Exchange patch(URI uri, String contentType, byte[] body) throws Exception {
    return patch(null, uri, contentType, body, false);
  }

  private static Exchange patch(Endpoint endpoint, URI uri, String contentType, byte[] body, boolean authenticated) throws Exception {
    return send(auth(endpoint, authenticated, HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", contentType)
        .header("User-Agent", "kkrepo-docker-compat-test/1")
        .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(body))));
  }

  private static Exchange put(URI uri, String contentType, byte[] body) throws Exception {
    return put(null, uri, contentType, body, false);
  }

  private static Exchange put(Endpoint endpoint, URI uri, String contentType, byte[] body, boolean authenticated) throws Exception {
    return send(auth(endpoint, authenticated, HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", contentType)
        .header("User-Agent", "kkrepo-docker-compat-test/1")
        .PUT(HttpRequest.BodyPublishers.ofByteArray(body))));
  }

  private static Exchange delete(URI uri) throws Exception {
    return delete(null, uri, false);
  }

  private static Exchange delete(Endpoint endpoint, URI uri, boolean authenticated) throws Exception {
    return send(auth(endpoint, authenticated, HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(60))
        .header("User-Agent", "kkrepo-docker-compat-test/1")
        .DELETE()));
  }

  private static void pushBlob(Endpoint endpoint, String imagePath, String digest, byte[] body) throws Exception {
    pushBlob(endpoint, imagePath, digest, body, false);
  }

  private static void pushBlob(
      Endpoint endpoint, String imagePath, String digest, byte[] body, boolean authenticated) throws Exception {
    Exchange start = post(endpoint, endpoint.v2(imagePath + "/blobs/uploads/"), authenticated);
    assertTrue(isUploadAccepted(start.status()), "blob upload should start");
    String location = start.header("Location").orElseThrow();
    URI patchUri = endpoint.resolve(location);
    String completeLocation = location;
    if (body.length > 0) {
      Exchange append = patch(endpoint, patchUri, "application/octet-stream", body, authenticated);
      assertTrue(isUploadAccepted(append.status()), "blob upload chunk should be accepted");
      completeLocation = append.header("Location").orElse(location);
    }
    String separator = completeLocation.contains("?") ? "&" : "?";
    Exchange complete = put(
        endpoint,
        endpoint.resolve(completeLocation + separator + "digest=" + encode(digest)),
        "application/octet-stream",
        body.length == 0 ? body : new byte[0],
        authenticated);
    assertEquals(201, complete.status(), "blob upload should complete");
  }

  private static HttpRequest.Builder auth(
      Endpoint endpoint, boolean authenticated, HttpRequest.Builder builder) {
    if (!authenticated || endpoint == null || endpoint.username().isEmpty() || endpoint.password().isEmpty()) {
      return builder;
    }
    String raw = endpoint.username().get() + ":" + endpoint.password().get();
    return builder.header("Authorization", "Basic "
        + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
  }

  private static Exchange send(HttpRequest.Builder builder) throws Exception {
    HttpResponse<byte[]> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    Map<String, String> headers = new LinkedHashMap<>();
    response.headers().map().forEach((key, values) -> {
      if (!values.isEmpty()) {
        headers.put(key.toLowerCase(Locale.ROOT), values.get(0));
      }
    });
    return new Exchange(response.statusCode(), headers, response.body());
  }

  private static void assertStatusFamily(String label, Exchange reference, Exchange candidate) {
    if (reference.status() == 401 || candidate.status() == 401) {
      assertEquals(reference.status(), candidate.status(), label + " auth challenge status");
      assertHeaderPresent(label + " auth challenge", reference, "WWW-Authenticate");
      assertHeaderPresent(label + " auth challenge", candidate, "WWW-Authenticate");
      return;
    }
    assertEquals(reference.status() / 100, candidate.status() / 100,
        label + " status family: reference=" + reference.status() + " candidate=" + candidate.status());
  }

  private static void assertHeaderEquals(String label, Exchange reference, Exchange candidate, String name) {
    String referenceValue = reference.header(name).orElse("");
    String candidateValue = candidate.header(name).orElse("");
    assertEquals(referenceValue, candidateValue, label);
  }

  private static void assertHeaderPresent(String label, Exchange exchange, String name) {
    assertFalse(exchange.header(name).orElse("").isBlank(), label + " missing " + name);
  }

  private static boolean isUploadAccepted(int status) {
    return status == 202 || status == 201;
  }

  private static String dockerAccept() {
    return String.join(", ",
        DockerConstants.MEDIA_TYPE_SCHEMA2_MANIFEST,
        DockerConstants.MEDIA_TYPE_SCHEMA2_MANIFEST_LIST,
        DockerConstants.MEDIA_TYPE_OCI_MANIFEST,
        DockerConstants.MEDIA_TYPE_OCI_INDEX,
        DockerConstants.MEDIA_TYPE_OCI_ARTIFACT);
  }

  private static String singleLayerManifest(String layerDigest, int layerSize) {
    return "{"
        + "\"schemaVersion\":2,"
        + "\"mediaType\":\"" + DockerConstants.MEDIA_TYPE_OCI_MANIFEST + "\","
        + "\"config\":{\"mediaType\":\"application/vnd.oci.empty.v1+json\","
        + "\"digest\":\"sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\","
        + "\"size\":0},"
        + "\"layers\":[{\"mediaType\":\"application/vnd.oci.image.layer.v1.tar\","
        + "\"digest\":\"" + layerDigest + "\","
        + "\"size\":" + layerSize + "}]"
        + "}";
  }

  private static String referrerManifest(String subjectDigest) {
    return "{"
        + "\"schemaVersion\":2,"
        + "\"mediaType\":\"" + DockerConstants.MEDIA_TYPE_OCI_MANIFEST + "\","
        + "\"artifactType\":\"application/vnd.kkrepo.compat\","
        + "\"config\":{\"mediaType\":\"application/vnd.oci.empty.v1+json\","
        + "\"digest\":\"sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\","
        + "\"size\":0},"
        + "\"subject\":{\"mediaType\":\"" + DockerConstants.MEDIA_TYPE_OCI_MANIFEST + "\","
        + "\"digest\":\"" + subjectDigest + "\","
        + "\"size\":1},"
        + "\"layers\":[]"
        + "}";
  }

  private static String sha256(byte[] body) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static Optional<String> setting(String property, String env) {
    return CompatDefaults.setting(property, env);
  }

  private static String stripTrailingSlash(String value) {
    return CompatDefaults.stripTrailingSlash(value);
  }

  private record CompatConfig(
      boolean enabled,
      Endpoint nexus,
      Endpoint nexusPlus,
      Optional<Endpoint> nexusPlusPathBasedEndpoint,
      Optional<Endpoint> nexusProxyEndpoint,
      Optional<Endpoint> nexusPlusProxyEndpoint,
      Optional<Endpoint> nexusGroupEndpoint,
      Optional<Endpoint> nexusPlusGroupEndpoint,
      Optional<Endpoint> nexusAllowOnceEndpoint,
      Optional<Endpoint> nexusPlusAllowOnceEndpoint,
      Optional<Endpoint> nexusDenyEndpoint,
      Optional<Endpoint> nexusPlusDenyEndpoint,
      String repository,
      String image,
      String reference,
      String uploadImage,
      String proxyImage,
      String proxyReference,
      String groupImage,
      String groupReference,
      String writePolicyImage,
      boolean writeEnabled,
      boolean pathBased) {
    static CompatConfig load() {
      boolean enabled = Boolean.parseBoolean(
          setting("compat.docker.enabled", "COMPAT_DOCKER_ENABLED").orElse("false"));
      boolean pathBased = Boolean.parseBoolean(
          setting("compat.docker.pathBased", "COMPAT_DOCKER_PATH_BASED").orElse("true"));
      String repository = setting("compat.docker.repository", "COMPAT_DOCKER_REPOSITORY")
          .orElse("docker-hosted");
      return new CompatConfig(
          enabled,
          new Endpoint("nexus",
              setting("compat.docker.nexus.baseUrl", "DOCKER_NEXUS_COMPAT_BASE_URL")
                  .or(() -> CompatDefaults.nexusBaseUrl()),
              CompatDefaults.nexusUsername(),
              CompatDefaults.nexusPassword()),
          new Endpoint("kkrepo",
              setting("compat.docker.nexusPlus.baseUrl", "DOCKER_NEXUS_PLUS_COMPAT_BASE_URL")
                  .or(() -> CompatDefaults.nexusPlusBaseUrl()),
              CompatDefaults.nexusPlusUsername(),
              CompatDefaults.nexusPlusPassword()),
          endpoint("kkrepo-path",
              setting("compat.docker.nexusPlus.pathBasedBaseUrl", "DOCKER_NEXUS_PLUS_PATH_BASED_COMPAT_BASE_URL"),
              CompatDefaults.nexusPlusUsername(),
              CompatDefaults.nexusPlusPassword()),
          endpoint("nexus-proxy",
              setting("compat.docker.nexus.proxyBaseUrl", "DOCKER_NEXUS_PROXY_COMPAT_BASE_URL"),
              CompatDefaults.nexusUsername(),
              CompatDefaults.nexusPassword()),
          endpoint("kkrepo-proxy",
              setting("compat.docker.nexusPlus.proxyBaseUrl", "DOCKER_NEXUS_PLUS_PROXY_COMPAT_BASE_URL"),
              CompatDefaults.nexusPlusUsername(),
              CompatDefaults.nexusPlusPassword()),
          endpoint("nexus-group",
              setting("compat.docker.nexus.groupBaseUrl", "DOCKER_NEXUS_GROUP_COMPAT_BASE_URL"),
              CompatDefaults.nexusUsername(),
              CompatDefaults.nexusPassword()),
          endpoint("kkrepo-group",
              setting("compat.docker.nexusPlus.groupBaseUrl", "DOCKER_NEXUS_PLUS_GROUP_COMPAT_BASE_URL"),
              CompatDefaults.nexusPlusUsername(),
              CompatDefaults.nexusPlusPassword()),
          endpoint("nexus-allow-once",
              setting("compat.docker.nexus.allowOnceBaseUrl", "DOCKER_NEXUS_ALLOW_ONCE_COMPAT_BASE_URL"),
              CompatDefaults.nexusUsername(),
              CompatDefaults.nexusPassword()),
          endpoint("kkrepo-allow-once",
              setting("compat.docker.nexusPlus.allowOnceBaseUrl", "DOCKER_NEXUS_PLUS_ALLOW_ONCE_COMPAT_BASE_URL"),
              CompatDefaults.nexusPlusUsername(),
              CompatDefaults.nexusPlusPassword()),
          endpoint("nexus-deny",
              setting("compat.docker.nexus.denyBaseUrl", "DOCKER_NEXUS_DENY_COMPAT_BASE_URL"),
              CompatDefaults.nexusUsername(),
              CompatDefaults.nexusPassword()),
          endpoint("kkrepo-deny",
              setting("compat.docker.nexusPlus.denyBaseUrl", "DOCKER_NEXUS_PLUS_DENY_COMPAT_BASE_URL"),
              CompatDefaults.nexusPlusUsername(),
              CompatDefaults.nexusPlusPassword()),
          repository,
          setting("compat.docker.image", "COMPAT_DOCKER_IMAGE").orElse("library/alpine"),
          setting("compat.docker.reference", "COMPAT_DOCKER_REFERENCE").orElse("latest"),
          setting("compat.docker.uploadImage", "COMPAT_DOCKER_UPLOAD_IMAGE")
              .orElse("kkrepo-compat/upload-probe"),
          setting("compat.docker.proxyImage", "COMPAT_DOCKER_PROXY_IMAGE").orElse("library/alpine"),
          setting("compat.docker.proxyReference", "COMPAT_DOCKER_PROXY_REFERENCE").orElse("latest"),
          setting("compat.docker.groupImage", "COMPAT_DOCKER_GROUP_IMAGE").orElse(""),
          setting("compat.docker.groupReference", "COMPAT_DOCKER_GROUP_REFERENCE").orElse(""),
          setting("compat.docker.writePolicyImage", "COMPAT_DOCKER_WRITE_POLICY_IMAGE")
              .orElse("kkrepo-compat/write-policy"),
          Boolean.parseBoolean(setting("compat.docker.writeEnabled", "COMPAT_DOCKER_WRITE_ENABLED").orElse("false")),
          pathBased);
    }

    private static Optional<Endpoint> endpoint(
        String name, Optional<String> baseUrl, Optional<String> username, Optional<String> password) {
      return baseUrl.map(value -> new Endpoint(name, Optional.of(value), username, password));
    }

    boolean configured() {
      return nexus.baseUrl().isPresent() && nexusPlus.baseUrl().isPresent();
    }

    boolean pathBasedConfigured() {
      return configured() && nexusPlusPathBasedEndpoint.isPresent() && !pathBased;
    }

    boolean proxyGroupConfigured() {
      return nexusProxyEndpoint.isPresent() && nexusPlusProxyEndpoint.isPresent()
          && nexusGroupEndpoint.isPresent() && nexusPlusGroupEndpoint.isPresent();
    }

    boolean writePolicyConfigured() {
      return nexusAllowOnceEndpoint.isPresent() && nexusPlusAllowOnceEndpoint.isPresent()
          && nexusDenyEndpoint.isPresent() && nexusPlusDenyEndpoint.isPresent();
    }

    public Endpoint nexusPlusPathBased() {
      return nexusPlusPathBasedEndpoint.orElseThrow();
    }

    public Endpoint nexusProxy() {
      return nexusProxyEndpoint.orElseThrow();
    }

    public Endpoint nexusPlusProxy() {
      return nexusPlusProxyEndpoint.orElseThrow();
    }

    public Endpoint nexusGroup() {
      return nexusGroupEndpoint.orElseThrow();
    }

    public Endpoint nexusPlusGroup() {
      return nexusPlusGroupEndpoint.orElseThrow();
    }

    public Endpoint nexusAllowOnce() {
      return nexusAllowOnceEndpoint.orElseThrow();
    }

    public Endpoint nexusPlusAllowOnce() {
      return nexusPlusAllowOnceEndpoint.orElseThrow();
    }

    public Endpoint nexusDeny() {
      return nexusDenyEndpoint.orElseThrow();
    }

    public Endpoint nexusPlusDeny() {
      return nexusPlusDenyEndpoint.orElseThrow();
    }

    String repositoryPath(String path) {
      if (!pathBased) {
        return path;
      }
      return repository + "/" + path;
    }
  }

  private record Endpoint(
      String name,
      Optional<String> baseUrl,
      Optional<String> username,
      Optional<String> password) {
    URI v2Base() {
      return URI.create(baseUrl.orElseThrow() + "/v2/");
    }

    URI v2(String path) {
      String normalized = path == null ? "" : path;
      while (normalized.startsWith("/")) {
        normalized = normalized.substring(1);
      }
      String suffix = normalized.isBlank() ? "" : "/" + normalized;
      return URI.create(baseUrl.orElseThrow() + "/v2" + suffix);
    }

    URI resolve(String location) {
      if (location.startsWith("http://") || location.startsWith("https://")) {
        return URI.create(location);
      }
      String normalized = location.startsWith("/") ? location : "/" + location;
      return URI.create(baseUrl.orElseThrow() + normalized);
    }
  }

  private record Exchange(int status, Map<String, String> headers, byte[] body) {
    Optional<String> header(String name) {
      return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
    }
  }
}
