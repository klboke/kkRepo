package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PubRepositoryBlackBoxCompatibilityTest {
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
  };
  private static final String PUB_CONTENT_TYPE = "application/vnd.pub.v2+json";

  @Test
  void hostedMetadataAndArchiveMatchNexusWhenConfigured() throws Exception {
    PubCompatConfig config = PubCompatConfig.load();
    assumeTrue(config.enabled(), "Set PUB_COMPAT_ENABLED=true to run Pub compatibility");
    assumeTrue(config.hostedPackageConfigured(),
        "Set PUB_COMPAT_HOSTED_PACKAGE and PUB_COMPAT_HOSTED_VERSION to compare a package present in both Pub hosted repos");

    Endpoint reference = config.referenceHosted();
    Endpoint candidate = config.candidateHosted();
    Exchange referenceMetadata = send(reference.request("api/packages/" + config.hostedPackageName()).GET());
    Exchange candidateMetadata = send(candidate.request("api/packages/" + config.hostedPackageName()).GET());
    assertSameStatus("hosted metadata", referenceMetadata, candidateMetadata);
    assertEquals(200, candidateMetadata.status(), "kkrepo hosted metadata status");
    assertPubJson("Nexus metadata", referenceMetadata);
    assertPubJson("kkrepo metadata", candidateMetadata);

    Map<String, Object> referenceVersion = version(referenceMetadata, config.hostedVersion());
    Map<String, Object> candidateVersion = version(candidateMetadata, config.hostedVersion());
    assertEquals(referenceVersion.get("version"), candidateVersion.get("version"), "version");
    assertEquals(referenceVersion.get("archive_sha256"), candidateVersion.get("archive_sha256"), "archive_sha256");

    Exchange referenceArchive = send(absolute(referenceVersion.get("archive_url")).GET());
    Exchange candidateArchive = send(absolute(candidateVersion.get("archive_url")).GET());
    assertSameStatus("hosted archive", referenceArchive, candidateArchive);
    assertArrayEquals(referenceArchive.body(), candidateArchive.body(), "archive body");
    assertVersionJsonAndChecksumSidecars("hosted", reference, candidate,
        config.hostedPackageName(), config.hostedVersion(), candidateVersion.get("archive_sha256"));
  }

  @Test
  void kkrepoHostedPublishInitChallengesWithoutToken() throws Exception {
    PubCompatConfig config = PubCompatConfig.load();
    assumeTrue(config.enabled(), "Set PUB_COMPAT_ENABLED=true to run Pub compatibility");

    Exchange candidateChallenge = send(config.candidateHosted().request("api/packages/versions/new").GET());
    assertEquals(401, candidateChallenge.status(), "kkrepo publish init should challenge without token");
    assertTrue(candidateChallenge.header("www-authenticate").toLowerCase().contains("bearer"),
        "kkrepo Pub challenge should be Bearer");
  }

  @Test
  void proxyAndGroupReadCompatibilityMatchesNexusWhenConfigured() throws Exception {
    PubCompatConfig config = PubCompatConfig.load();
    assumeTrue(config.enabled(), "Set PUB_COMPAT_ENABLED=true to run Pub compatibility");
    assumeTrue(config.proxyPackageConfigured(),
        "Set PUB_COMPAT_PROXY_PACKAGE and PUB_COMPAT_PROXY_VERSION to compare a package available through proxy/group");

    assertMetadataVersionAndArchive("proxy", config.referenceProxy(), config.candidateProxy(), config);
    assertMetadataVersionAndArchive("group", config.referenceGroup(), config.candidateGroup(), config);
    assertMissingPackageStatus("proxy", config.referenceProxy(), config.candidateProxy(), config);
    assertMissingPackageStatus("group", config.referenceGroup(), config.candidateGroup(), config);
    assertMetadataAvailable("proxy leading underscore package",
        config.referenceProxy(), config.candidateProxy(), config.flutterPackageName());
    assertMetadataAvailable("group leading underscore package",
        config.referenceGroup(), config.candidateGroup(), config.flutterPackageName());
  }

  @Test
  void proxyAndGroupPublishInitAreNotFoundLikeNexus() throws Exception {
    PubCompatConfig config = PubCompatConfig.load();
    assumeTrue(config.enabled(), "Set PUB_COMPAT_ENABLED=true to run Pub compatibility");

    assertPublishInitNotFound("proxy", config.referenceProxy(), config.candidateProxy());
    assertPublishInitNotFound("group", config.referenceGroup(), config.candidateGroup());
  }

  @Test
  void proxyAndGroupInvalidPathStatusesMatchNexus() throws Exception {
    PubCompatConfig config = PubCompatConfig.load();
    assumeTrue(config.enabled(), "Set PUB_COMPAT_ENABLED=true to run Pub compatibility");
    assumeTrue(config.proxyPackageConfigured(),
        "Set PUB_COMPAT_PROXY_PACKAGE and PUB_COMPAT_PROXY_VERSION to compare invalid version paths");

    assertStatusOnly("proxy invalid package name",
        config.referenceProxy(), config.candidateProxy(), "api/packages/InvalidName");
    assertStatusOnly("group invalid package name",
        config.referenceGroup(), config.candidateGroup(), "api/packages/InvalidName");
    assertStatusOnly("proxy invalid version",
        config.referenceProxy(), config.candidateProxy(),
        "api/packages/" + config.proxyPackageName() + "/versions/not-a-version");
    assertStatusOnly("group invalid version",
        config.referenceGroup(), config.candidateGroup(),
        "api/packages/" + config.proxyPackageName() + "/versions/not-a-version");
  }

  private static void assertMetadataVersionAndArchive(
      String label,
      Endpoint reference,
      Endpoint candidate,
      PubCompatConfig config) throws Exception {
    Exchange referenceMetadata = send(reference.request("api/packages/" + config.proxyPackageName()).GET());
    Exchange candidateMetadata = send(candidate.request("api/packages/" + config.proxyPackageName()).GET());
    assertSameStatus(label + " metadata", referenceMetadata, candidateMetadata);
    assertEquals(200, candidateMetadata.status(), "kkrepo " + label + " metadata status");
    Map<String, Object> referenceVersion = version(referenceMetadata, config.proxyVersion());
    Map<String, Object> candidateVersion = version(candidateMetadata, config.proxyVersion());
    assertEquals(referenceVersion.get("version"), candidateVersion.get("version"), label + " version");
    assertEquals(referenceVersion.get("archive_sha256"), candidateVersion.get("archive_sha256"),
        label + " archive_sha256");
    assertTrue(String.valueOf(candidateVersion.get("archive_url"))
            .startsWith(candidate.baseUrl() + "/api/archives/" + config.proxyPackageName() + "-"),
        "kkrepo " + label + " archive_url should route through the visible repository");

    Exchange referenceVersionEndpoint = send(reference.request(
        "api/packages/" + config.proxyPackageName() + "/versions/" + config.proxyVersion()).GET());
    Exchange candidateVersionEndpoint = send(candidate.request(
        "api/packages/" + config.proxyPackageName() + "/versions/" + config.proxyVersion()).GET());
    assertSameStatus(label + " version endpoint", referenceVersionEndpoint, candidateVersionEndpoint);
    assertEquals(200, candidateVersionEndpoint.status(), "kkrepo " + label + " version endpoint status");
    assertPubJson("Nexus " + label + " version endpoint", referenceVersionEndpoint);
    assertPubJson("kkrepo " + label + " version endpoint", candidateVersionEndpoint);
    Map<String, Object> referenceVersionBody = json(referenceVersionEndpoint);
    Map<String, Object> candidateVersionBody = json(candidateVersionEndpoint);
    assertEquals(referenceVersionBody.get("version"), candidateVersionBody.get("version"),
        label + " version endpoint version");
    assertEquals(referenceVersionBody.get("archive_sha256"), candidateVersionBody.get("archive_sha256"),
        label + " version endpoint archive_sha256");

    Exchange referenceArchive = send(absolute(referenceVersion.get("archive_url")).GET());
    Exchange candidateArchive = send(absolute(candidateVersion.get("archive_url")).GET());
    assertSameStatus(label + " archive", referenceArchive, candidateArchive);
    assertEquals(200, candidateArchive.status(), "kkrepo " + label + " archive status");
    assertArrayEquals(referenceArchive.body(), candidateArchive.body(), label + " archive body");
    assertEquals(candidateVersion.get("archive_sha256"), sha256(candidateArchive.body()),
        "kkrepo " + label + " archive body should match archive_sha256");
    Exchange candidateNexusArchiveAlias = send(candidate.request(
        "api/archives/" + config.proxyPackageName() + "-" + config.proxyVersion() + ".tar.gz").GET());
    assertEquals(200, candidateNexusArchiveAlias.status(), "kkrepo " + label + " Nexus archive alias status");
    assertArrayEquals(candidateArchive.body(), candidateNexusArchiveAlias.body(),
        "kkrepo " + label + " Nexus archive alias body");
    assertVersionJsonAndChecksumSidecars(label, reference, candidate,
        config.proxyPackageName(), config.proxyVersion(), candidateVersion.get("archive_sha256"));
  }

  private static void assertVersionJsonAndChecksumSidecars(
      String label,
      Endpoint reference,
      Endpoint candidate,
      String packageName,
      String version,
      Object expectedArchiveSha256) throws Exception {
    String versionJsonPath = packageName + "/" + version + "/version.json";
    Exchange referenceVersionJson = send(reference.request(versionJsonPath).GET());
    Exchange candidateVersionJson = send(candidate.request(versionJsonPath).GET());
    assertSameStatus(label + " version.json", referenceVersionJson, candidateVersionJson);
    assertEquals(200, candidateVersionJson.status(), "kkrepo " + label + " version.json status");
    assertJson("Nexus " + label + " version.json", referenceVersionJson);
    assertJson("kkrepo " + label + " version.json", candidateVersionJson);
    Map<String, Object> referenceBody = json(referenceVersionJson);
    Map<String, Object> candidateBody = json(candidateVersionJson);
    assertEquals(referenceBody.get("version"), candidateBody.get("version"),
        label + " version.json version");
    assertEquals(referenceBody.get("archive_sha256"), candidateBody.get("archive_sha256"),
        label + " version.json archive_sha256");
    assertEquals(expectedArchiveSha256, candidateBody.get("archive_sha256"),
        "kkrepo " + label + " version.json should match metadata archive_sha256");

    String archiveAssetPath = packageName + "/" + version + "/" + packageName + "-" + version + ".tar.gz";
    for (String suffix : List.of(".sha1", ".sha256", ".sha512", ".md5")) {
      Exchange referenceChecksum = send(reference.request(archiveAssetPath + suffix).GET());
      Exchange candidateChecksum = send(candidate.request(archiveAssetPath + suffix).GET());
      assertSameStatus(label + " checksum sidecar " + suffix, referenceChecksum, candidateChecksum);
      assertEquals(404, candidateChecksum.status(),
          "kkrepo " + label + " checksum sidecar " + suffix + " should match Nexus 404");
    }
  }

  private static void assertMissingPackageStatus(
      String label,
      Endpoint reference,
      Endpoint candidate,
      PubCompatConfig config) throws Exception {
    Exchange referenceMissing = send(reference.request("api/packages/" + config.missingPackageName()).GET());
    Exchange candidateMissing = send(candidate.request("api/packages/" + config.missingPackageName()).GET());
    assertSameStatus(label + " missing package", referenceMissing, candidateMissing);
    assertEquals(404, candidateMissing.status(), "kkrepo " + label + " missing package status");
  }

  private static void assertPublishInitNotFound(String label, Endpoint reference, Endpoint candidate) throws Exception {
    Exchange referenceInit = send(reference.request("api/packages/versions/new").GET());
    Exchange candidateInit = send(candidate.request("api/packages/versions/new").GET());
    assertSameStatus(label + " publish init", referenceInit, candidateInit);
    assertEquals(404, candidateInit.status(), "kkrepo " + label + " publish init status");
  }

  private static void assertStatusOnly(
      String label,
      Endpoint reference,
      Endpoint candidate,
      String path) throws Exception {
    Exchange referenceExchange = send(reference.request(path).GET());
    Exchange candidateExchange = send(candidate.request(path).GET());
    assertSameStatus(label, referenceExchange, candidateExchange);
  }

  private static void assertMetadataAvailable(
      String label,
      Endpoint reference,
      Endpoint candidate,
      String packageName) throws Exception {
    Exchange referenceMetadata = send(reference.request("api/packages/" + packageName).GET());
    Exchange candidateMetadata = send(candidate.request("api/packages/" + packageName).GET());
    assertSameStatus(label, referenceMetadata, candidateMetadata);
    assertEquals(200, candidateMetadata.status(), "kkrepo " + label + " metadata status");
    assertPubJson("Nexus " + label, referenceMetadata);
    assertPubJson("kkrepo " + label, candidateMetadata);
    assertEquals(json(referenceMetadata).get("name"), json(candidateMetadata).get("name"),
        label + " package name");
  }

  private static Map<String, Object> version(Exchange exchange, String version) throws Exception {
    Map<String, Object> body = json(exchange);
    Object versions = body.get("versions");
    if (versions instanceof List<?> list) {
      for (Object entry : list) {
        if (entry instanceof Map<?, ?> map && version.equals(String.valueOf(map.get("version")))) {
          @SuppressWarnings("unchecked")
          Map<String, Object> typed = (Map<String, Object>) map;
          return typed;
        }
      }
    }
    throw new AssertionError("Pub metadata did not contain version " + version);
  }

  private static Map<String, Object> json(Exchange exchange) throws Exception {
    return JSON.readValue(exchange.body(), JSON_MAP);
  }

  private static HttpRequest.Builder absolute(Object url) {
    return HttpRequest.newBuilder(URI.create(String.valueOf(url)))
        .timeout(Duration.ofSeconds(90));
  }

  private static Exchange send(HttpRequest.Builder builder) throws Exception {
    HttpResponse<byte[]> response = HTTP.send(
        builder.header("User-Agent", "kkrepo-pub-compat-test/1").build(),
        HttpResponse.BodyHandlers.ofByteArray());
    return new Exchange(response.statusCode(), response.body(), response.headers().map());
  }

  private static void assertSameStatus(String label, Exchange reference, Exchange candidate) {
    assertEquals(reference.status(), candidate.status(), label + " status");
  }

  private static void assertPubJson(String label, Exchange exchange) {
    assertTrue(exchange.header("content-type").startsWith(PUB_CONTENT_TYPE),
        label + " content-type should be " + PUB_CONTENT_TYPE + " but was " + exchange.header("content-type"));
  }

  private static void assertJson(String label, Exchange exchange) {
    assertTrue(exchange.header("content-type").startsWith("application/json"),
        label + " content-type should be application/json but was " + exchange.header("content-type"));
  }

  private static String sha256(byte[] body) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
  }

  private record Exchange(int status, byte[] body, Map<String, List<String>> headers) {
    String header(String name) {
      return headers.getOrDefault(name, List.of()).stream().findFirst().orElse("");
    }
  }

  private record Endpoint(String baseUrl) {
    HttpRequest.Builder request(String path) {
      return HttpRequest.newBuilder(URI.create(baseUrl + "/" + path))
          .timeout(Duration.ofSeconds(60));
    }
  }

  private record PubCompatConfig(
      boolean enabled,
      String hostedPackageName,
      String hostedVersion,
      String proxyPackageName,
      String proxyVersion,
      Endpoint referenceHosted,
      Endpoint referenceProxy,
      Endpoint referenceGroup,
      Endpoint candidateHosted,
      Endpoint candidateProxy,
      Endpoint candidateGroup,
      String missingPackageName,
      String flutterPackageName) {

    static PubCompatConfig load() {
      String nexusBase = CompatDefaults.nexusBaseUrl().orElse(CompatDefaults.NEXUS_BASE_URL);
      String kkrepoBase = CompatDefaults.nexusPlusBaseUrl().orElse(CompatDefaults.KKREPO_BASE_URL);
      String referenceHosted = setting("PUB_NEXUS_HOSTED_URL", nexusBase + "/repository/pub-hosted");
      String referenceProxy = setting("PUB_NEXUS_PROXY_URL", nexusBase + "/repository/pub-proxy");
      String referenceGroup = setting("PUB_NEXUS_GROUP_URL", nexusBase + "/repository/pub-group");
      String candidateHosted = setting("PUB_KKREPO_HOSTED_URL", kkrepoBase + "/repository/pub-hosted");
      String candidateProxy = setting("PUB_KKREPO_PROXY_URL", kkrepoBase + "/repository/pub-proxy");
      String candidateGroup = setting("PUB_KKREPO_GROUP_URL", kkrepoBase + "/repository/pub-group");
      String packageName = setting("PUB_COMPAT_PACKAGE", "");
      String version = setting("PUB_COMPAT_VERSION", "");
      return new PubCompatConfig(
          Boolean.parseBoolean(setting("PUB_COMPAT_ENABLED", "false")),
          setting("PUB_COMPAT_HOSTED_PACKAGE", packageName),
          setting("PUB_COMPAT_HOSTED_VERSION", version),
          setting("PUB_COMPAT_PROXY_PACKAGE", packageName.isBlank() ? "path" : packageName),
          setting("PUB_COMPAT_PROXY_VERSION", version.isBlank() ? "1.9.0" : version),
          new Endpoint(CompatDefaults.stripTrailingSlash(referenceHosted)),
          new Endpoint(CompatDefaults.stripTrailingSlash(referenceProxy)),
          new Endpoint(CompatDefaults.stripTrailingSlash(referenceGroup)),
          new Endpoint(CompatDefaults.stripTrailingSlash(candidateHosted)),
          new Endpoint(CompatDefaults.stripTrailingSlash(candidateProxy)),
          new Endpoint(CompatDefaults.stripTrailingSlash(candidateGroup)),
          setting("PUB_COMPAT_MISSING_PACKAGE", "kkrepo_missing_package_zzzzzz"),
          setting("PUB_COMPAT_FLUTTER_PACKAGE", "_fe_analyzer_shared"));
    }

    boolean hostedPackageConfigured() {
      return hostedPackageName != null && !hostedPackageName.isBlank()
          && hostedVersion != null && !hostedVersion.isBlank();
    }

    boolean proxyPackageConfigured() {
      return proxyPackageName != null && !proxyPackageName.isBlank()
          && proxyVersion != null && !proxyVersion.isBlank();
    }

    private static String setting(String env, String fallback) {
      return CompatDefaults.setting("compat.pub." + env.toLowerCase().replace('_', '.'), env)
          .orElse(fallback);
    }
  }
}
