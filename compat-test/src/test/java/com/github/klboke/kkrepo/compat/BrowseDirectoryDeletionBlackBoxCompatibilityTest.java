package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Follows the actual Nexus Browse folder action, not a guessed repository DELETE protocol. */
class BrowseDirectoryDeletionBlackBoxCompatibilityTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

  @Test void confirmedHostedFolderDeletionRetainsSiblingDirectories() throws Exception {
    String nexus = System.getenv("NEXUS_COMPAT_BASE_URL");
    String candidate = System.getenv("KKREPO_COMPAT_BASE_URL");
    assumeTrue(nexus != null && candidate != null
        && Boolean.parseBoolean(System.getenv("COMPAT_WRITE_ENABLED")),
        "Set writable Nexus/kkRepo compatibility endpoints");
    String repository = "compat-directory-" + UUID.randomUUID().toString().substring(0, 8);
    Endpoint reference = new Endpoint(nexus, env("NEXUS_COMPAT_USERNAME", "admin"), env("NEXUS_COMPAT_PASSWORD", "Admin1234"));
    Endpoint actual = new Endpoint(candidate, env("KKREPO_COMPAT_USERNAME", "admin"), env("KKREPO_COMPAT_PASSWORD", "12345678"));
    try {
      assertSuccess(reference.request("POST", "/service/rest/v1/repositories/raw/hosted", JSON.writeValueAsString(Map.of(
          "name", repository, "online", true, "storage", Map.of("blobStoreName", "default", "strictContentTypeValidation", false, "writePolicy", "ALLOW")))));
      assertSuccess(actual.request("POST", "/internal/repositories", JSON.writeValueAsString(Map.of(
          "name", repository, "recipe", "raw-hosted", "online", true, "blobStoreName", "default", "strictContentTypeValidation", false,
          "hosted", Map.of("writePolicy", "ALLOW")))));
      for (Endpoint endpoint : List.of(reference, actual)) {
        for (String path : List.of("releases/a.txt", "releases/nested/b.txt", "releases-old/keep.txt", "other/keep.txt")) {
          assertEquals(201, endpoint.request("PUT", "/repository/" + repository + "/" + path, "bytes:" + path).statusCode());
        }
      }
      String route = "/internal/browse/" + repository + "/directory-deletion";
      var previewResponse = actual.request("GET", route + "?path=releases", null);
      assertEquals(200, previewResponse.statusCode(), previewResponse.body());
      JsonNode preview = JSON.readTree(previewResponse.body());
      assertEquals(2, preview.path("assetCount").asInt());
      String token = preview.path("selectionToken").asText();
      var rejected = actual.request("POST", route, JSON.writeValueAsString(Map.of(
          "path", "releases", "confirmationPath", "wrong", "selectionToken", token)));
      assertEquals(400, rejected.statusCode());
      assertEquals(200, actual.request("GET", "/repository/" + repository + "/releases/a.txt", null).statusCode());
      awaitNexusFolder(reference, repository, "releases", List.of("a.txt", "b.txt"));
      var nexusDelete = reference.request("POST", "/service/extdirect", JSON.writeValueAsString(Map.of(
          "action", "coreui_Component", "method", "deleteFolder", "data", List.of("releases", repository), "type", "rpc", "tid", 1)));
      assertEquals(200, nexusDelete.statusCode(), nexusDelete.body());
      JsonNode rpc = JSON.readTree(nexusDelete.body());
      if (rpc.isArray()) rpc = rpc.get(0);
      assertTrue(rpc.path("result").path("success").asBoolean(), nexusDelete.body());
      assertEquals(200, actual.request("POST", route, JSON.writeValueAsString(Map.of(
          "path", "releases", "confirmationPath", "releases", "selectionToken", token))).statusCode());
      for (Endpoint endpoint : List.of(reference, actual)) {
        for (String path : List.of("releases/a.txt", "releases/nested/b.txt")) {
          awaitMissing(endpoint, "/repository/" + repository + "/" + path);
        }
        for (String path : List.of("releases-old/keep.txt", "other/keep.txt")) {
          var response = endpoint.request("GET", "/repository/" + repository + "/" + path, null);
          assertEquals(200, response.statusCode());
          assertEquals("bytes:" + path, response.body());
        }
      }
    } finally {
      reference.request("DELETE", "/service/rest/v1/repositories/" + repository, null);
      cleanupCandidate(actual, repository);
    }
  }

  @Test void mavenFolderDeletionRetainsSiblingArtifacts() throws Exception {
    withRepositories("maven2", pair -> {
      String root = "org/example/deleted";
      for (Endpoint endpoint : pair.endpoints()) {
        for (String version : List.of("1.0", "2.0")) {
          put(endpoint, pair.repo(), root + "/" + version + "/deleted-" + version + ".pom",
              ("<project><modelVersion>4.0.0</modelVersion><groupId>org.example</groupId>"
                  + "<artifactId>deleted</artifactId><version>" + version + "</version></project>").getBytes(StandardCharsets.UTF_8));
        }
        put(endpoint, pair.repo(), root + "/maven-metadata.xml",
            "<metadata><groupId>org.example</groupId><artifactId>deleted</artifactId><versioning><versions><version>1.0</version><version>2.0</version></versions></versioning></metadata>".getBytes(StandardCharsets.UTF_8));
        put(endpoint, pair.repo(), "org/example/deleted-old/keep.txt", "keep".getBytes(StandardCharsets.UTF_8));
      }
      deleteBoth(pair, root, List.of("deleted-1.0.pom", "deleted-2.0.pom"));
      for (Endpoint endpoint : pair.endpoints()) {
        for (String path : List.of(root + "/1.0/deleted-1.0.pom", root + "/2.0/deleted-2.0.pom", root + "/maven-metadata.xml"))
          awaitMissing(endpoint, pair.path(path));
        assertBytes(endpoint, pair.path("org/example/deleted-old/keep.txt"), "keep".getBytes(StandardCharsets.UTF_8));
      }
    });
  }

  @Test void npmFolderDeletionRepairsPackageMetadataAndRetainsSibling() throws Exception {
    withRepositories("npm", pair -> {
      var first = NpmRepositoryBlackBoxCompatibilityTest.NpmFixture.create("deleted", "1.0.0");
      var second = NpmRepositoryBlackBoxCompatibilityTest.NpmFixture.create("deleted", "2.0.0");
      var keep = NpmRepositoryBlackBoxCompatibilityTest.NpmFixture.create("deleted-old", "1.0.0");
      for (Endpoint endpoint : pair.endpoints()) {
        for (var fixture : List.of(first, second, keep)) {
          assertSuccess(send(endpoint, "PUT", pair.path(fixture.packageName()), fixture.publishJson(), "application/json"));
        }
        awaitText(endpoint, pair.path("deleted"), "2.0.0", true);
      }
      deleteBoth(pair, "deleted", List.of("deleted-1.0.0.tgz", "deleted-2.0.0.tgz"));
      for (Endpoint endpoint : pair.endpoints()) {
        awaitMissing(endpoint, pair.path("deleted/-/deleted-1.0.0.tgz"));
        awaitMissing(endpoint, pair.path("deleted/-/deleted-2.0.0.tgz"));
        // Existing npm cleanup keeps an empty packument; Nexus removes it. Both must
        // stop advertising every deleted version and dist-tag.
        var packument = endpoint.request("GET", pair.path("deleted"), null);
        if (endpoint.equals(pair.reference())) assertEquals(404, packument.statusCode());
        else {
          assertEquals(200, packument.statusCode());
          JsonNode empty = JSON.readTree(packument.body());
          assertTrue(empty.path("versions").isObject() && empty.path("versions").isEmpty(), packument.body());
          assertTrue(empty.path("dist-tags").isObject() && empty.path("dist-tags").isEmpty(), packument.body());
        }
        assertBytes(endpoint, pair.path("deleted-old/-/deleted-old-1.0.0.tgz"), keep.tarball());
        JsonNode document = JSON.readTree(endpoint.request("GET", pair.path("deleted-old"), null).body());
        assertTrue(document.path("versions").has("1.0.0"));
        var next = NpmRepositoryBlackBoxCompatibilityTest.NpmFixture.create("deleted", "3.0.0");
        assertSuccess(send(endpoint, "PUT", pair.path("deleted"), next.publishJson(), "application/json"));
        JsonNode updated = JSON.readTree(endpoint.request("GET", pair.path("deleted"), null).body());
        assertEquals(List.of("3.0.0"), updated.path("versions").propertyStream().map(Map.Entry::getKey).toList());
        assertBytes(endpoint, pair.path("deleted/-/deleted-3.0.0.tgz"), next.tarball());
      }
    });
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"deleted", "simple", "packages"})
  void pypiFolderDeletionRepairsSimpleIndexAndRetainsSibling(String project) throws Exception {
    withRepositories("pypi", pair -> {
      var first = PypiRepositoryBlackBoxCompatibilityTest.WheelFixture.create(project, "1.0.0");
      var second = PypiRepositoryBlackBoxCompatibilityTest.WheelFixture.create(project, "2.0.0");
      var keep = PypiRepositoryBlackBoxCompatibilityTest.WheelFixture.create("kept", "1.0.0");
      for (Endpoint endpoint : pair.endpoints()) {
        for (var fixture : List.of(first, second, keep)) {
          multipart(endpoint, "POST", pair.path(""), Map.of(":action", "file_upload", "protocol_version", "1",
              "metadata_version", "2.1", "name", fixture.name(), "version", fixture.version()),
              "content", fixture.filename(), fixture.bytes());
        }
        awaitText(endpoint, pair.path("simple/" + project + "/"), second.filename(), true);
      }
      var listing = pair.actual().request("GET", "/internal/browse/" + pair.repo() + "?path=" + project, null);
      assertEquals(200, listing.statusCode(), listing.body());
      assertTrue(JSON.readTree(listing.body()).path("entries").toString().contains(project + "/1.0.0"), listing.body());
      deleteBoth(pair, project, List.of(first.filename(), second.filename()));
      for (Endpoint endpoint : pair.endpoints()) {
        awaitMissing(endpoint, pair.path(first.packagePath()));
        awaitMissing(endpoint, pair.path(second.packagePath()));
        awaitText(endpoint, pair.path("simple/"), ">" + project + "<", false);
        awaitText(endpoint, pair.path("simple/"), "kept", true);
        assertBytes(endpoint, pair.path(keep.packagePath()), keep.bytes());
        awaitText(endpoint, pair.path("simple/kept/"), keep.filename(), true);
      }
    });
  }

  @Test void nugetFolderDeletionRepairsVersionIndexAndRetainsSibling() throws Exception {
    withRepositories("nuget", pair -> {
      var first = NugetRubygemsYumRepositoryBlackBoxCompatibilityTest.NugetFixture.create("Deleted", "1.0.0");
      var second = NugetRubygemsYumRepositoryBlackBoxCompatibilityTest.NugetFixture.create("Deleted", "2.0.0");
      var keep = NugetRubygemsYumRepositoryBlackBoxCompatibilityTest.NugetFixture.create("Kept", "1.0.0");
      Map<Endpoint, String> bases = new java.util.HashMap<>();
      for (Endpoint endpoint : pair.endpoints()) {
        for (var fixture : List.of(first, second, keep))
          multipart(endpoint, "PUT", pair.path(""), Map.of(), "package", fixture.fileName(), fixture.bytes());
        JsonNode service = JSON.readTree(endpoint.request("GET", pair.path("index.json"), null).body());
        for (JsonNode resource : service.path("resources")) {
          if (resource.path("@type").asText().startsWith("PackageBaseAddress"))
            bases.put(endpoint, URI.create(resource.path("@id").asText()).getRawPath().replaceAll("/$", "") + "/");
        }
        assertNotNull(bases.get(endpoint), service.toString());
        awaitText(endpoint, bases.get(endpoint) + "deleted/index.json", "2.0.0", true);
      }
      deleteBoth(pair, "v3-flatcontainer/deleted", List.of("Deleted-1.0.0.nupkg", "Deleted-2.0.0.nupkg"));
      for (Endpoint endpoint : pair.endpoints()) {
        String base = bases.get(endpoint);
        awaitMissing(endpoint, base + "deleted/1.0.0/deleted.1.0.0.nupkg");
        awaitMissing(endpoint, base + "deleted/2.0.0/deleted.2.0.0.nupkg");
        var versions = endpoint.request("GET", base + "deleted/index.json", null);
        assertEquals(endpoint.equals(pair.reference()) ? 404 : 200, versions.statusCode(), versions.body());
        if (versions.statusCode() == 200) {
          JsonNode remaining = JSON.readTree(versions.body()).path("versions");
          assertTrue(remaining.isArray() && remaining.isEmpty(), versions.body());
        }
        awaitMissing(endpoint, base + "deleted/1.0.0/deleted.nuspec");
        awaitMissing(endpoint, base + "deleted/2.0.0/deleted.nuspec");
        assertBytes(endpoint, base + "kept/1.0.0/kept.1.0.0.nupkg", keep.bytes());
        awaitText(endpoint, base + "kept/index.json", "1.0.0", true);
      }
    });
  }

  @Test void helmVirtualFoldersRemainUnavailableUntilBrowseMappingIsSupported() throws Exception {
    withRepositories("helm", pair -> {
      var first = HelmRepositoryBlackBoxCompatibilityTest.ChartFixture.create("deleted", "1.0.0");
      var second = HelmRepositoryBlackBoxCompatibilityTest.ChartFixture.create("deleted", "2.0.0");
      for (Endpoint endpoint : pair.endpoints()) {
        put(endpoint, pair.repo(), first.fileName(), first.bytes());
        put(endpoint, pair.repo(), second.fileName(), second.bytes());
      }
      // Nexus projects flat chart URLs as name/version folders. kkRepo has no matching
      // virtual Browse folder yet, so it must reject bulk deletion for this format.
      String folder = awaitNexusFolder(pair.reference(), pair.repo(), "deleted", List.of(first.fileName(), second.fileName()));
      assertEquals("deleted", folder);
      var preview = pair.actual().request("GET", "/internal/browse/" + pair.repo()
          + "/directory-deletion?path=deleted", null);
      assertEquals(400, preview.statusCode(), preview.body());
      assertBytes(pair.actual(), pair.path(first.fileName()), first.bytes());
      assertBytes(pair.actual(), pair.path(second.fileName()), second.bytes());
    });
  }

  @Test void rubygemsFolderDeletionRepairsSpecsAndCompactIndex() throws Exception {
    withRepositories("rubygems", pair -> {
      var first = NugetRubygemsYumRepositoryBlackBoxCompatibilityTest.GemFixture.create("deleted", "1.0.0");
      var second = NugetRubygemsYumRepositoryBlackBoxCompatibilityTest.GemFixture.create("deleted", "2.0.0");
      for (Endpoint endpoint : pair.endpoints()) {
        for (var fixture : List.of(first, second))
          assertSuccess(send(endpoint, "POST", pair.path("api/v1/gems"), fixture.bytes(), "application/octet-stream"));
        awaitText(endpoint, pair.path("versions"), "deleted", true);
      }
      deleteBoth(pair, "gems", List.of(first.fileName(), second.fileName()));
      for (Endpoint endpoint : pair.endpoints()) {
        awaitMissing(endpoint, pair.path(first.gemPath()));
        awaitMissing(endpoint, pair.path(second.gemPath()));
        awaitMissing(endpoint, pair.path(first.quickSpecPath()));
        awaitMissing(endpoint, pair.path(second.quickSpecPath()));
        awaitText(endpoint, pair.path("versions"), "deleted", false);
        if (endpoint.equals(pair.reference())) awaitMissing(endpoint, pair.path("info/deleted"));
        else {
          awaitText(endpoint, pair.path("info/deleted"), "1.0.0", false);
          awaitText(endpoint, pair.path("info/deleted"), "2.0.0", false);
        }
        byte[] compressed = bytes(endpoint, pair.path("specs.4.8.gz"));
        try (var gzip = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
          assertFalse(new String(gzip.readAllBytes(), StandardCharsets.ISO_8859_1).contains("deleted"));
        }
      }
    });
  }

  @Test void yumFolderDeletionRebuildsScopedRepodataAndRetainsSibling() throws Exception {
    withRepositories("yum", pair -> {
      String fixtureUrl = env("COMPAT_YUM_FIXTURE_URL", "https://kojipkgs.fedoraproject.org/packages/6tunnel/0.13/1.el9/x86_64/6tunnel-0.13-1.el9.x86_64.rpm");
      var rpmResponse = HTTP.send(HttpRequest.newBuilder(URI.create(fixtureUrl)).timeout(Duration.ofSeconds(90)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, rpmResponse.statusCode(), fixtureUrl);
      byte[] rpm = rpmResponse.body();
      String file = "6tunnel.rpm";
      Map<Endpoint, byte[]> retainedMetadata = new java.util.HashMap<>();
      for (Endpoint endpoint : pair.endpoints()) {
        put(endpoint, pair.repo(), "deleted/Packages/" + file, rpm);
        put(endpoint, pair.repo(), "kept/Packages/" + file, rpm);
        awaitText(endpoint, pair.path("deleted/repodata/repomd.xml"), "primary", true);
        awaitText(endpoint, pair.path("kept/repodata/repomd.xml"), "primary", true);
        retainedMetadata.put(endpoint, bytes(endpoint, pair.path("kept/repodata/repomd.xml")));
      }
      // Only select Packages: the sibling repodata directory must be repaired by the protocol.
      deleteBoth(pair, "deleted/Packages", List.of(file));
      for (Endpoint endpoint : pair.endpoints()) {
        awaitMissing(endpoint, pair.path("deleted/Packages/" + file));
        assertBytes(endpoint, pair.path("kept/Packages/" + file), rpm);
        assertBytes(endpoint, pair.path("kept/repodata/repomd.xml"), retainedMetadata.get(endpoint));
        boolean empty = false;
        for (int i = 0; i < 240 && !empty; i++) {
          var repomd = endpoint.request("GET", pair.path("deleted/repodata/repomd.xml"), null);
          if (repomd.statusCode() == 404) empty = true;
          else {
            assertEquals(200, repomd.statusCode(), repomd.body());
            var matcher = java.util.regex.Pattern.compile("<data type=\"primary\">.*?<location href=\"([^\"]+)\"", java.util.regex.Pattern.DOTALL).matcher(repomd.body());
            assertTrue(matcher.find(), repomd.body());
            byte[] primary = bytes(endpoint, pair.path("deleted/" + matcher.group(1)));
            try (var gzip = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(primary))) {
              empty = !new String(gzip.readAllBytes(), StandardCharsets.UTF_8).contains("<name>6tunnel</name>");
            }
          }
          if (!empty) Thread.sleep(500);
        }
        assertTrue(empty, endpoint.base() + " still advertises the deleted RPM");
      }
    });
  }

  private record Pair(Endpoint reference, Endpoint actual, String repo) {
    List<Endpoint> endpoints() { return List.of(reference, actual); }
    String path(String suffix) { return "/repository/" + repo + "/" + suffix; }
  }
  @FunctionalInterface private interface Scenario { void run(Pair pair) throws Exception; }

  private static void withRepositories(String format, Scenario scenario) throws Exception {
    assumeTrue(System.getenv("NEXUS_COMPAT_BASE_URL") != null && System.getenv("KKREPO_COMPAT_BASE_URL") != null
        && Boolean.parseBoolean(System.getenv("COMPAT_WRITE_ENABLED")), "Set writable Nexus/kkRepo compatibility endpoints");
    String repository = "compat-directory-" + format + "-" + UUID.randomUUID().toString().substring(0, 8);
    Endpoint reference = new Endpoint(System.getenv("NEXUS_COMPAT_BASE_URL"), env("NEXUS_COMPAT_USERNAME", "admin"), env("NEXUS_COMPAT_PASSWORD", "Admin1234"));
    Endpoint actual = new Endpoint(System.getenv("KKREPO_COMPAT_BASE_URL"), env("KKREPO_COMPAT_USERNAME", "admin"), env("KKREPO_COMPAT_PASSWORD", "12345678"));
    Map<String, Object> nexus = new java.util.LinkedHashMap<>(Map.of("name", repository, "online", true,
        "storage", Map.of("blobStoreName", "default", "strictContentTypeValidation", false, "writePolicy", "ALLOW")));
    Map<String, Object> candidate = new java.util.LinkedHashMap<>(Map.of("name", repository, "recipe", format + "-hosted",
        "online", true, "blobStoreName", "default", "strictContentTypeValidation", false, "hosted", Map.of("writePolicy", "ALLOW")));
    if (format.equals("maven2")) {
      nexus.put("maven", Map.of("versionPolicy", "RELEASE", "layoutPolicy", "PERMISSIVE"));
      candidate.put("hosted", Map.of("writePolicy", "ALLOW", "versionPolicy", "RELEASE", "layoutPolicy", "PERMISSIVE"));
    }
    if (format.equals("yum")) {
      nexus.put("yum", Map.of("repodataDepth", 1));
      candidate.put("yum", Map.of("repodataDepth", 1));
    }
    try {
      assertSuccess(reference.request("POST", "/service/rest/v1/repositories/" + (format.equals("maven2") ? "maven" : format) + "/hosted", JSON.writeValueAsString(nexus)));
      assertSuccess(actual.request("POST", "/internal/repositories", JSON.writeValueAsString(candidate)));
      scenario.run(new Pair(reference, actual, repository));
    } finally {
      reference.request("DELETE", "/service/rest/v1/repositories/" + repository, null);
      cleanupCandidate(actual, repository);
    }
  }

  private static void deleteBoth(Pair pair, String candidateFolder, List<String> filenames) throws Exception {
    String folder = awaitNexusFolder(pair.reference(), pair.repo(), candidateFolder, filenames);
    String route = "/internal/browse/" + pair.repo() + "/directory-deletion";
    var response = pair.actual().request("GET", route + "?path=" + candidateFolder, null);
    assertEquals(200, response.statusCode(), "Candidate preview " + candidateFolder + ": " + response.body());
    JsonNode preview = JSON.readTree(response.body());
    assertTrue(preview.path("assetCount").asInt() >= filenames.size(), preview.toString());
    var reference = pair.reference().request("POST", "/service/extdirect", JSON.writeValueAsString(Map.of(
        "action", "coreui_Component", "method", "deleteFolder", "data", List.of(folder, pair.repo()), "type", "rpc", "tid", 1)));
    assertEquals(200, reference.statusCode(), reference.body());
    JsonNode rpc = JSON.readTree(reference.body());
    if (rpc.isArray()) rpc = rpc.get(0);
    assertTrue(rpc.path("result").path("success").asBoolean(), reference.body());
    var deleted = pair.actual().request("POST", route, JSON.writeValueAsString(Map.of("path", candidateFolder,
        "confirmationPath", candidateFolder, "selectionToken", preview.path("selectionToken").asText())));
    assertEquals(200, deleted.statusCode(), deleted.body());
  }

  private static void cleanupCandidate(Endpoint endpoint, String repository) throws Exception {
    var listing = endpoint.request("GET", "/internal/browse/" + repository, null);
    if (listing.statusCode() == 404) return;
    assertEquals(200, listing.statusCode(), listing.body());
    // Only this test's UUID repository is touched. Existing management deletion removes
    // its remaining package components before the repository's empty check.
    for (JsonNode entry : JSON.readTree(listing.body()).path("entries")) {
      String path = java.net.URLEncoder.encode(entry.path("path").asText(), StandardCharsets.UTF_8);
      var removed = endpoint.request("DELETE", "/internal/browse/" + repository + "?path=" + path, null);
      assertTrue(removed.statusCode() == 200 || removed.statusCode() == 404, removed.body());
    }
    assertSuccess(endpoint.request("DELETE", "/internal/repositories/" + repository, null));
  }

  private static void put(Endpoint endpoint, String repository, String path, byte[] content) throws Exception {
    assertSuccess(send(endpoint, "PUT", "/repository/" + repository + "/" + path, content, "application/octet-stream"));
  }

  private static HttpRequest.Builder request(Endpoint endpoint, String path) {
    return HttpRequest.newBuilder(URI.create(endpoint.base().replaceAll("/$", "") + path))
        .timeout(Duration.ofSeconds(90)).header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
            (endpoint.username() + ":" + endpoint.password()).getBytes(StandardCharsets.UTF_8)));
  }

  private static HttpResponse<String> send(Endpoint endpoint, String method, String path, byte[] content, String type) throws Exception {
    return HTTP.send(request(endpoint, path).header("Content-Type", type).method(method,
        HttpRequest.BodyPublishers.ofByteArray(content)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private static byte[] bytes(Endpoint endpoint, String path) throws Exception {
    var response = HTTP.send(request(endpoint, path).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, response.statusCode(), endpoint.base() + path + ": " + new String(response.body(), StandardCharsets.UTF_8));
    return response.body();
  }

  private static void assertBytes(Endpoint endpoint, String path, byte[] expected) throws Exception {
    assertArrayEquals(expected, bytes(endpoint, path), endpoint.base() + path);
  }

  private static void awaitText(Endpoint endpoint, String path, String text, boolean present) throws Exception {
    String last = "";
    for (int i = 0; i < 240; i++) {
      var response = endpoint.request("GET", path, null);
      last = response.statusCode() + ": " + response.body();
      if (response.statusCode() == 200 && response.body().contains(text) == present) return;
      Thread.sleep(500);
    }
    fail(endpoint.base() + path + " expected " + text + " present=" + present + ": " + last);
  }

  private static void multipart(Endpoint endpoint, String method, String path, Map<String, String> fields,
      String field, String filename, byte[] content) throws Exception {
    String boundary = "directory-compat-" + UUID.randomUUID();
    var out = new java.io.ByteArrayOutputStream();
    for (var entry : fields.entrySet()) {
      out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + entry.getKey()
          + "\"\r\n\r\n" + entry.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
    out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename
        + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    out.write(content);
    out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    assertSuccess(send(endpoint, method, path, out.toByteArray(), "multipart/form-data; boundary=" + boundary));
  }

  /** Nexus indexes Browse asynchronously; deleteFolder can otherwise report success without deleting. */
  static String awaitNexusFolder(Endpoint endpoint, String repository, String preferred,
      List<String> filenames) throws Exception {
    for (int i = 0; i < 240; i++) {
      String folder = findFolder(endpoint, repository, "/", preferred, filenames);
      if (folder != null) return folder;
      Thread.sleep(500);
    }
    fail("Nexus Browse did not index " + repository + ": " + filenames);
    return null;
  }

  private static String findFolder(Endpoint endpoint, String repository, String node,
      String preferred, List<String> filenames) throws Exception {
    var response = endpoint.request("POST", "/service/extdirect", JSON.writeValueAsString(Map.of(
        "action", "coreui_Browse", "method", "read", "data", List.of(Map.of(
            "node", node, "repositoryName", repository, "filter", "")), "type", "rpc", "tid", 1)));
    assertEquals(200, response.statusCode(), response.body());
    JsonNode rpc = JSON.readTree(response.body());
    if (rpc.isArray()) rpc = rpc.get(0);
    assertTrue(rpc.path("result").path("success").asBoolean(), response.body());
    java.util.Set<String> leaves = new java.util.HashSet<>();
    collectLeaves(endpoint, repository, rpc.path("result").path("data"), leaves);
    if (!leaves.containsAll(filenames)) return null;
    if (node.equals(preferred)) return node;
    // Select the UI's actual folder identity, descending only while all targeted packages fit.
    for (JsonNode child : rpc.path("result").path("data")) {
      if (!child.path("leaf").asBoolean()) {
        String found = findFolder(endpoint, repository, child.path("id").asText(), preferred, filenames);
        if (found != null) return found;
      }
    }
    return node.equals("/") ? null : node;
  }

  private static void collectLeaves(Endpoint endpoint, String repository, JsonNode children,
      java.util.Set<String> leaves) throws Exception {
    for (JsonNode child : children) {
      if (child.path("leaf").asBoolean()) {
        leaves.add(child.path("text").asText());
      } else {
        var response = endpoint.request("POST", "/service/extdirect", JSON.writeValueAsString(Map.of(
            "action", "coreui_Browse", "method", "read", "data", List.of(Map.of(
                "node", child.path("id").asText(), "repositoryName", repository, "filter", "")), "type", "rpc", "tid", 1)));
        JsonNode rpc = JSON.readTree(response.body());
        if (rpc.isArray()) rpc = rpc.get(0);
        assertTrue(rpc.path("result").path("success").asBoolean(), response.body());
        collectLeaves(endpoint, repository, rpc.path("result").path("data"), leaves);
      }
    }
  }

  private static void awaitMissing(Endpoint endpoint, String path) throws Exception {
    for (int i = 0; i < 60; i++) {
      var response = endpoint.request("GET", path, null);
      if (response.statusCode() == 404) return;
      Thread.sleep(500);
    }
    fail("Deleted folder remains downloadable at " + endpoint.base() + ": " + path);
  }

  private static String env(String key, String fallback) {
    String value = System.getenv(key); return value == null || value.isBlank() ? fallback : value;
  }

  private static void assertSuccess(HttpResponse<String> response) {
    assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.uri() + " " + response.statusCode() + ": " + response.body());
  }

  record Endpoint(String base, String username, String password) {
    HttpResponse<String> request(String method, String path, String body) throws Exception {
      var request = HttpRequest.newBuilder(URI.create(base.replaceAll("/$", "") + path))
          .timeout(Duration.ofSeconds(30))
          .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8)))
          .header("Content-Type", "PUT".equals(method) ? "application/octet-stream" : "application/json")
          .header("Accept", "application/json")
          .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
      return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }
}
