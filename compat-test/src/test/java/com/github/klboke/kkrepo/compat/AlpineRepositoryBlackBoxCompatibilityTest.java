package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.alpine.AlpineChecksums;
import com.github.klboke.kkrepo.protocol.alpine.AlpineIndex;
import com.github.klboke.kkrepo.protocol.alpine.AlpineIndexRecord;
import com.github.klboke.kkrepo.protocol.alpine.AlpinePackageInfo;
import com.github.klboke.kkrepo.protocol.alpine.AlpineSignature;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.junit.jupiter.api.Test;

/** Opt-in Alpine hosted contract against a live Nexus reference and kkRepo candidate. */
class AlpineRepositoryBlackBoxCompatibilityTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();

  @Test
  void generatedFixtureUsesOfficialApkV2MemberDigests() throws Exception {
    AlpineCompatibilityFixtures.ApkPackage fixture = AlpineCompatibilityFixtures.apk(
        "kkrepo-alpine-fixture", "1.2.3_rc1-r2", "fixture");
    List<byte[]> members = gzipMembers(fixture.bytes());

    assertEquals(2, members.size());
    assertEquals(fixture.identity(), AlpineChecksums.v2Identity(members.getFirst()));
    assertEquals(fixture.dataSha256(), sha256(members.getLast()));
    String pkgInfo = new String(
        archiveEntries(members.getFirst()).get(".PKGINFO"), StandardCharsets.UTF_8);
    AlpinePackageInfo parsed = AlpinePackageInfo.parse(pkgInfo);
    assertEquals(fixture.name(), parsed.name());
    assertEquals(fixture.version(), parsed.version());
    assertEquals("noarch", parsed.architecture());
    assertEquals(fixture.dataSha256(), parsed.dataSha256());
    assertTrue(archiveEntries(members.getLast()).containsKey(
        "usr/share/kkrepo-alpine-compat/" + fixture.name() + ".txt"));
  }

  @Test
  void hostedSignedIndexAndPackageHttpMatchNexusWhenConfigured() throws Exception {
    Config config = configured();
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    String repository = "alpine-compat-" + suffix;
    AlpineCompatibilityFixtures.SigningKey key = AlpineCompatibilityFixtures.signingKey();
    AlpineCompatibilityFixtures.ApkPackage fixture = AlpineCompatibilityFixtures.apk(
        "kkrepo-alpine-" + suffix, "1.0.0-r0", "live compatibility");
    String path = "v3.23/main/x86_64/" + fixture.filename();

    try {
      createNexusHosted(config, repository, key);
      createCandidateHosted(config, repository, key);

      Exchange nexusUpload = put(config.nexus(repository), path, fixture.bytes());
      Exchange candidateUpload = put(config.candidate(repository), path, fixture.bytes());
      assertEquals(200, nexusUpload.status(), () -> "Nexus upload: " + nexusUpload.text());
      assertEquals(nexusUpload.status(), candidateUpload.status(),
          () -> "kkRepo upload: " + candidateUpload.text());

      IndexShape nexus = waitForIndex(config.nexus(repository), fixture.name());
      IndexShape candidate = waitForIndex(config.candidate(repository), fixture.name());
      AlpineIndexRecord nexusRecord = record(nexus, fixture.name());
      AlpineIndexRecord candidateRecord = record(candidate, fixture.name());
      assertEquals(fixture.version(), nexusRecord.version());
      assertEquals(nexusRecord.version(), candidateRecord.version());
      assertEquals(Long.toString(fixture.bytes().length), nexusRecord.require('S'));
      assertEquals(nexusRecord.require('S'), candidateRecord.require('S'));
      assertEquals("x86_64", candidateRecord.architecture());
      assertEquals(nexusRecord.architecture(), candidateRecord.architecture());
      assertEquals(fixture.identity(), candidateRecord.identity(),
          "kkRepo follows apk-tools' Q1 digest over the compressed control member");
      // Nexus 3.94 may publish a data-member Q1 for unsigned uploads. It is intentionally not
      // copied because that index fails apk-tools v2 package-integrity verification.
      AlpineChecksums.requireV2Identity(nexusRecord.identity());

      assertTrue(verifyIndex(nexus, key));
      assertTrue(verifyIndex(candidate, key));
      assertPackageHttp(config.nexus(repository), path, fixture.bytes());
      assertPackageHttp(config.candidate(repository), path, fixture.bytes());
      assertBrowseAndSearch(config, repository, fixture);
    } finally {
      deleteAsset(config.candidate(repository), path);
      deleteRepository(config.nexusAdmin(), repository);
      deleteRepository(config.candidateAdmin(), repository);
    }
  }

  private static IndexShape waitForIndex(Endpoint endpoint, String packageName) throws Exception {
    Exchange last = null;
    for (int attempt = 0; attempt < 120; attempt++) {
      last = get(endpoint, "v3.23/main/x86_64/APKINDEX.tar.gz");
      if (last.status() == 200) {
        try {
          IndexShape shape = signedIndex(last.body());
          if (shape.records().stream().anyMatch(row -> packageName.equals(row.packageName()))) {
            assertEquals("application/gzip", mediaType(last.header("content-type")));
            return shape;
          }
        } catch (IllegalArgumentException ignored) {
          // Publication may still be replacing the initial empty snapshot.
        }
      }
      Thread.sleep(250L);
    }
    throw new AssertionError("APKINDEX did not expose " + packageName + ": "
        + (last == null ? "<none>" : last.status() + " " + last.text()));
  }

  private static void assertPackageHttp(Endpoint endpoint, String path, byte[] expected)
      throws Exception {
    Exchange get = get(endpoint, path);
    assertEquals(200, get.status());
    assertArrayEquals(expected, get.body());
    assertEquals("application/gzip", mediaType(get.header("content-type")));

    Exchange head = send(endpoint.request(path)
        .method("HEAD", HttpRequest.BodyPublishers.noBody()));
    assertEquals(200, head.status());
    assertEquals(Integer.toString(expected.length), head.header("content-length"));
    assertEquals(0, head.body().length);

    Exchange range = send(endpoint.request(path).header("Range", "bytes=0-63").GET());
    assertEquals(206, range.status());
    assertArrayEquals(Arrays.copyOf(expected, 64), range.body());
    assertEquals("bytes 0-63/" + expected.length, range.header("content-range"));

    assertFalse(get.header("etag").isBlank());
    Exchange conditional = send(endpoint.request(path)
        .header("If-None-Match", get.header("etag")).GET());
    assertEquals(304, conditional.status());
  }

  private static void assertBrowseAndSearch(
      Config config,
      String repository,
      AlpineCompatibilityFixtures.ApkPackage fixture) throws Exception {
    Exchange search = send(config.candidateAdmin().request(
        "/internal/search/components?format=alpine&q=" + fixture.name()).GET());
    assertEquals(200, search.status());
    JsonNode match = JSON.readTree(search.body()).path("items");
    assertTrue(match.isArray());
    boolean found = false;
    for (JsonNode item : match) {
      if (repository.equals(item.path("repository").asText())
          && fixture.name().equals(item.path("name").asText())) {
        assertEquals(fixture.version(), item.path("version").asText());
        assertEquals(fixture.sha256(), item.path("details").path("sha256").asText());
        assertEquals(fixture.identity(), item.path("details").path("identity").asText());
        found = true;
      }
    }
    assertTrue(found, "Alpine package must be discoverable through component search");

    String browsePath = "v3.23/main/x86_64/" + fixture.name() + "/" + fixture.version();
    Exchange browse = send(config.candidateAdmin().request(
        "/internal/browse/" + repository + "?path=" + encode(browsePath)).GET());
    assertEquals(200, browse.status());
    assertTrue(browse.text().contains(fixture.filename()));
  }

  private static void createNexusHosted(
      Config config, String repository, AlpineCompatibilityFixtures.SigningKey key)
      throws Exception {
    var body = JSON.createObjectNode();
    body.put("name", repository);
    body.put("online", true);
    var storage = body.putObject("storage");
    storage.put("blobStoreName", config.nexusBlobStore());
    storage.put("strictContentTypeValidation", true);
    storage.put("writePolicy", "ALLOW");
    body.putObject("alpineSigning").put("keypair", key.privatePem());
    body.putObject("component").put("proprietaryComponents", false);
    Exchange response = send(config.nexusAdmin().request(
            "/service/rest/v1/repositories/alpine/hosted")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    assertEquals(201, response.status(), () -> "Nexus Alpine create: " + response.text());
  }

  private static void createCandidateHosted(
      Config config, String repository, AlpineCompatibilityFixtures.SigningKey key)
      throws Exception {
    var body = JSON.createObjectNode();
    body.put("name", repository);
    body.put("recipe", "alpine-hosted");
    body.put("online", true);
    body.put("blobStoreName", config.candidateBlobStore());
    body.put("strictContentTypeValidation", true);
    body.putObject("hosted").put("writePolicy", "ALLOW");
    var alpine = body.putObject("alpine");
    alpine.putArray("distributions").add("v3.23");
    alpine.putArray("channels").add("main");
    alpine.putArray("architectures").add("x86_64");
    alpine.put("metadataMode", "RESIGN");
    alpine.put("verifyUpstreamSignatures", true);
    alpine.put("keyFilename", "kkrepo-compat.rsa.pub");
    alpine.put("signatureType", "RSA");
    Exchange created = send(config.candidateAdmin().request("/internal/repositories")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    assertEquals(201, created.status(), () -> "kkRepo Alpine create: " + created.text());

    var signing = JSON.createObjectNode();
    signing.put("privateKey", key.privatePem());
    signing.put("keyFilename", "kkrepo-compat.rsa.pub");
    signing.put("signatureType", "RSA");
    Exchange imported = send(config.candidateAdmin().request(
            "/internal/repositories/" + repository + "/alpine/signing-key")
        .header("Content-Type", "application/json")
        .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(signing))));
    assertEquals(200, imported.status(), () -> "kkRepo Alpine key import: " + imported.text());
  }

  private static boolean verifyIndex(
      IndexShape index, AlpineCompatibilityFixtures.SigningKey key) throws Exception {
    Signature verifier = Signature.getInstance(index.signatureType().jcaAlgorithm());
    verifier.initVerify(key.publicKey());
    verifier.update(index.unsignedMember());
    return verifier.verify(index.signature());
  }

  private static IndexShape signedIndex(byte[] bytes) {
    for (int offset = 2; offset + 2 < bytes.length; offset++) {
      if ((bytes[offset] & 0xff) != 0x1f || (bytes[offset + 1] & 0xff) != 0x8b) continue;
      try {
        byte[] signatureMember = Arrays.copyOfRange(bytes, 0, offset);
        byte[] unsignedMember = Arrays.copyOfRange(bytes, offset, bytes.length);
        Map<String, byte[]> signatures = archiveEntries(signatureMember);
        Map<String, byte[]> index = archiveEntries(unsignedMember);
        Map.Entry<String, byte[]> signature = signatures.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(".SIGN."))
            .findFirst().orElse(null);
        byte[] text = index.get("APKINDEX");
        if (signature == null || text == null) continue;
        AlpineSignature.ParsedEntry parsed = AlpineSignature.parseEntryName(signature.getKey());
        return new IndexShape(
            AlpineIndex.parse(text), unsignedMember, signature.getValue(), parsed.type());
      } catch (RuntimeException ignored) {
        // A gzip magic sequence can occur inside compressed data; keep searching.
      }
    }
    throw new IllegalArgumentException("Invalid signed APKINDEX archive");
  }

  private static List<byte[]> gzipMembers(byte[] bytes) {
    ArrayList<byte[]> result = new ArrayList<>();
    int start = 0;
    while (start < bytes.length) {
      int boundary = -1;
      for (int offset = start + 2; offset + 2 < bytes.length; offset++) {
        if ((bytes[offset] & 0xff) != 0x1f || (bytes[offset + 1] & 0xff) != 0x8b) continue;
        try {
          archiveEntries(Arrays.copyOfRange(bytes, start, offset));
          archiveEntries(Arrays.copyOfRange(bytes, offset, bytes.length));
          boundary = offset;
          break;
        } catch (RuntimeException ignored) {
          // Not a real gzip member boundary.
        }
      }
      if (boundary < 0) {
        result.add(Arrays.copyOfRange(bytes, start, bytes.length));
        break;
      }
      result.add(Arrays.copyOfRange(bytes, start, boundary));
      start = boundary;
    }
    return List.copyOf(result);
  }

  private static Map<String, byte[]> archiveEntries(byte[] compressed) {
    LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
    try (var gzip = new GzipCompressorInputStream(new ByteArrayInputStream(compressed));
         var tar = new TarArchiveInputStream(gzip)) {
      for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null;) {
        if (entry.isFile()) entries.put(entry.getName(), tar.readNBytes((int) entry.getSize()));
      }
      if (entries.isEmpty()) throw new IllegalArgumentException("Empty tar member");
      return Map.copyOf(entries);
    } catch (Exception error) {
      throw new IllegalArgumentException("Invalid gzip/tar member", error);
    }
  }

  private static AlpineIndexRecord record(IndexShape shape, String packageName) {
    return shape.records().stream()
        .filter(row -> packageName.equals(row.packageName()))
        .findFirst().orElseThrow();
  }

  private static Exchange put(Endpoint endpoint, String path, byte[] body) throws Exception {
    return send(endpoint.request(path)
        .header("Content-Type", "application/vnd.alpine.apk")
        .PUT(HttpRequest.BodyPublishers.ofByteArray(body)));
  }

  private static Exchange get(Endpoint endpoint, String path) throws Exception {
    return send(endpoint.request(path).GET());
  }

  private static Exchange send(HttpRequest.Builder request) throws Exception {
    HttpResponse<byte[]> response = HTTP.send(
        request.header("User-Agent", "kkrepo-alpine-compat-test/1")
            .timeout(Duration.ofSeconds(180)).build(),
        HttpResponse.BodyHandlers.ofByteArray());
    return new Exchange(response.statusCode(), response.body(), response.headers().map());
  }

  private static Config configured() throws Exception {
    Config config = Config.load();
    assumeTrue(config.enabled(), "Set ALPINE_COMPAT_ENABLED=true to run Alpine compatibility");
    assumeTrue(reachable(config.nexusAdmin()),
        "Nexus Alpine reference is not reachable at " + config.nexusBase());
    assumeTrue(reachable(config.candidateAdmin()),
        "kkRepo candidate is not reachable at " + config.candidateBase());
    return config;
  }

  private static boolean reachable(AdminEndpoint endpoint) {
    try {
      return send(endpoint.request("/service/rest/v1/status").GET()).status() > 0;
    } catch (Exception ignored) {
      return false;
    }
  }

  private static void deleteRepository(AdminEndpoint endpoint, String repository) {
    try {
      Exchange response = send(endpoint.request(
          "/service/rest/v1/repositories/" + encode(repository)).DELETE());
      if (response.status() < 200 || response.status() >= 300) {
        send(endpoint.request("/internal/repositories/" + encode(repository)).DELETE());
      }
    } catch (Exception ignored) {
      // Best-effort cleanup for opt-in live fixtures.
    }
  }

  private static void deleteAsset(Endpoint endpoint, String path) {
    try {
      send(endpoint.request(path).DELETE());
    } catch (Exception ignored) {
      // Best-effort cleanup so the candidate repository can be removed after live tests.
    }
  }

  private static String sha256(byte[] bytes) throws Exception {
    return java.util.HexFormat.of().formatHex(
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static String basic(String username, String password) {
    return "Basic " + Base64.getEncoder().encodeToString(
        (username + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  private static String encode(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String mediaType(String value) {
    int separator = value.indexOf(';');
    return (separator < 0 ? value : value.substring(0, separator)).trim().toLowerCase();
  }

  private record IndexShape(
      List<AlpineIndexRecord> records,
      byte[] unsignedMember,
      byte[] signature,
      AlpineSignature.Type signatureType) {
  }

  private record Exchange(int status, byte[] body, Map<String, List<String>> headers) {
    String text() {
      return new String(body, StandardCharsets.UTF_8);
    }

    String header(String name) {
      return headers.entrySet().stream()
          .filter(entry -> entry.getKey().equalsIgnoreCase(name))
          .flatMap(entry -> entry.getValue().stream())
          .findFirst().orElse("");
    }
  }

  private record Endpoint(String base, String authorization) {
    HttpRequest.Builder request(String path) {
      return HttpRequest.newBuilder(URI.create(base + "/" + path))
          .header("Authorization", authorization);
    }
  }

  private record AdminEndpoint(String base, String authorization) {
    HttpRequest.Builder request(String path) {
      return HttpRequest.newBuilder(URI.create(base + path))
          .header("Authorization", authorization);
    }
  }

  private record Config(
      boolean enabled,
      String nexusBase,
      String candidateBase,
      String nexusAuthorization,
      String candidateAuthorization,
      String nexusBlobStore,
      String candidateBlobStore) {
    static Config load() {
      String nexusUser = CompatDefaults.setting(
          "compat.alpine.nexus.username", "ALPINE_NEXUS_COMPAT_USERNAME")
          .orElseGet(() -> CompatDefaults.nexusUsername().orElse(""));
      String nexusPassword = CompatDefaults.setting(
          "compat.alpine.nexus.password", "ALPINE_NEXUS_COMPAT_PASSWORD")
          .orElseGet(() -> CompatDefaults.nexusPassword().orElse(""));
      String candidateUser = CompatDefaults.setting(
          "compat.alpine.kkrepo.username", "ALPINE_KKREPO_COMPAT_USERNAME")
          .orElseGet(() -> CompatDefaults.nexusPlusUsername().orElse(""));
      String candidatePassword = CompatDefaults.setting(
          "compat.alpine.kkrepo.password", "ALPINE_KKREPO_COMPAT_PASSWORD")
          .orElseGet(() -> CompatDefaults.nexusPlusPassword().orElse(""));
      return new Config(
          CompatDefaults.setting("compat.alpine.enabled", "ALPINE_COMPAT_ENABLED")
              .map(Boolean::parseBoolean).orElse(false),
          CompatDefaults.setting("compat.alpine.nexus.baseUrl", "ALPINE_NEXUS_COMPAT_BASE_URL")
              .map(CompatDefaults::stripTrailingSlash)
              .orElseGet(() -> CompatDefaults.nexusBaseUrl().orElse("")),
          CompatDefaults.setting("compat.alpine.kkrepo.baseUrl", "ALPINE_KKREPO_COMPAT_BASE_URL")
              .map(CompatDefaults::stripTrailingSlash)
              .orElseGet(() -> CompatDefaults.nexusPlusBaseUrl().orElse("")),
          basic(nexusUser, nexusPassword),
          basic(candidateUser, candidatePassword),
          CompatDefaults.setting(
              "compat.alpine.nexus.blobStore", "ALPINE_NEXUS_COMPAT_BLOB_STORE")
              .orElse("default"),
          CompatDefaults.setting(
              "compat.alpine.kkrepo.blobStore", "ALPINE_KKREPO_COMPAT_BLOB_STORE")
              .orElse("default"));
    }

    Endpoint nexus(String repository) {
      return new Endpoint(nexusBase + "/repository/" + repository, nexusAuthorization);
    }

    Endpoint candidate(String repository) {
      return new Endpoint(candidateBase + "/repository/" + repository,
          candidateAuthorization);
    }

    AdminEndpoint nexusAdmin() {
      return new AdminEndpoint(nexusBase, nexusAuthorization);
    }

    AdminEndpoint candidateAdmin() {
      return new AdminEndpoint(candidateBase, candidateAuthorization);
    }
  }
}
