package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Black-box Maven compatibility harness.
 *
 * <p>It is skipped by default and runs when both endpoints are supplied:
 * {@code NEXUS_COMPAT_BASE_URL}, {@code KKREPO_COMPAT_BASE_URL}, and
 * {@code COMPAT_WRITE_ENABLED=true}. Release writes default to {@code maven-releases}, snapshot
 * writes default to {@code maven-snapshots}, and both can be overridden through the
 * {@link CompatConfig} settings.
 */
class MavenRepositoryBlackBoxCompatibilityTest {
  private static final String GRADLE_PLUGIN_PORTAL_ARTIFACT =
      "com/bmuschko/gradle-docker-plugin/3.6.1/gradle-docker-plugin-3.6.1.pom";
  private static final DateTimeFormatter DOTTED =
      DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter DOTLESS =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();
  private static final HttpClient NO_REDIRECT_HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build();

  @Test
  void proxyReadRoundTripMatchesNexusWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run black-box compatibility");

    Endpoint nexus = config.nexusRead();
    Endpoint nexusPlus = config.nexusPlusRead();
    String path = config.readPath();

    Exchange referenceGet = get(nexus, path);
    Exchange candidateGet = get(nexusPlus, path);
    Exchange referenceHead = head(nexus, path);
    Exchange candidateHead = head(nexusPlus, path);
    Exchange referenceSha1 = get(nexus, path + ".sha1");
    Exchange candidateSha1 = get(nexusPlus, path + ".sha1");

    assertSameExchange("proxy GET", referenceGet, candidateGet, true);
    assertSameExchange("proxy HEAD", referenceHead, candidateHead, false);
    assertSameExchange("proxy checksum", referenceSha1, candidateSha1, true);
  }

  @Test
  void gradlePluginPortalCrossHostRedirectMatchesNexusWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run black-box compatibility");
    assumeTrue(Boolean.parseBoolean(setting(
        "compat.gradlePluginPortalRedirect.enabled",
        "COMPAT_GRADLE_PLUGIN_PORTAL_REDIRECT_ENABLED").orElse("false")),
        "Set COMPAT_GRADLE_PLUGIN_PORTAL_REDIRECT_ENABLED=true after configuring the candidate "
            + "proxy with allowedRedirectHosts=[plugins-artifacts.gradle.org]");

    Endpoint nexus = config.nexus().withRepository(setting(
        "compat.nexus.gradlePluginPortalRepository",
        "NEXUS_COMPAT_GRADLE_PLUGIN_PORTAL_REPOSITORY").orElse("gradle-plugins"));
    Endpoint candidate = config.nexusPlus().withRepository(setting(
        "compat.nexusPlus.gradlePluginPortalRepository",
        "KKREPO_COMPAT_GRADLE_PLUGIN_PORTAL_REPOSITORY").orElse("gradle-plugins"));

    Exchange reference = get(nexus, GRADLE_PLUGIN_PORTAL_ARTIFACT);
    Exchange actual = get(candidate, GRADLE_PLUGIN_PORTAL_ARTIFACT);

    assertSameExchange("Gradle Plugin Portal redirected artifact", reference, actual, true);
    assertEquals(200, actual.status(),
        "the redirected Gradle Plugin Portal artifact must be cached and served");
  }

  @Test
  void repositoryRootAndHtmlBrowseMatchNexusWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run black-box compatibility");

    Endpoint nexus = config.nexusRead();
    Endpoint nexusPlus = config.nexusPlusRead();
    Exchange referenceRoot = get(nexus, "");
    Exchange candidateRoot = get(nexusPlus, "");
    assertEquals(referenceRoot.status(), candidateRoot.status(), "repository root status");
    assertEquals(200, candidateRoot.status(), "repository root should be OK");
    String candidateRootBody = new String(candidateRoot.body(), StandardCharsets.UTF_8);
    assertTrue(candidateRootBody.contains("not directly browseable"),
        "repository root should explain direct browsing");

    Exchange referenceBrowseRoot = send(nexus.browseRequest("").GET());
    Exchange candidateBrowseRoot = send(nexusPlus.browseRequest("").GET());
    assertEquals(referenceBrowseRoot.status(), candidateBrowseRoot.status(), "HTML browse root status");
    assertEquals(200, candidateBrowseRoot.status(), "HTML browse root should be OK");
    String candidateBrowseBody = new String(candidateBrowseRoot.body(), StandardCharsets.UTF_8);
    assertTrue(candidateBrowseBody.contains("Index of /"),
        "HTML browse root should use Nexus index path");

    String readPath = config.readPath();
    int fileNameIndex = readPath.lastIndexOf('/') + 1;
    String parentPath = readPath.substring(0, fileNameIndex);
    String fileName = readPath.substring(fileNameIndex);
    Exchange referenceBrowseDirectory = send(nexus.browseRequest(parentPath).GET());
    Exchange candidateBrowseDirectory = send(nexusPlus.browseRequest(parentPath).GET());
    assertEquals(referenceBrowseDirectory.status(), candidateBrowseDirectory.status(),
        "HTML browse directory status");
    assertEquals(200, candidateBrowseDirectory.status(), "HTML browse directory should be OK");
    String candidateBrowseDirectoryBody =
        new String(candidateBrowseDirectory.body(), StandardCharsets.UTF_8);
    assertTrue(candidateBrowseDirectoryBody.contains(
            "href=\"/repository/" + nexusPlus.repository() + "/" + readPath + "\""),
        "HTML browse file link should open the direct repository file");
    assertTrue(candidateBrowseDirectoryBody.contains(">" + fileName + "</a>"),
        "HTML browse directory should render the file name");

    Exchange referenceBrowseFile = sendNoRedirect(nexus.browseRequest(readPath).GET());
    Exchange candidateBrowseFile = sendNoRedirect(nexusPlus.browseRequest(readPath).GET());
    assertEquals(referenceBrowseFile.status(), candidateBrowseFile.status(), "HTML browse file status");
    assertEquals(303, candidateBrowseFile.status(), "HTML browse file should redirect to slash path");

    Exchange referenceBrowseFileSlash = send(nexus.browseRequest(readPath + "/").GET());
    Exchange candidateBrowseFileSlash = send(nexusPlus.browseRequest(readPath + "/").GET());
    assertEquals(referenceBrowseFileSlash.status(), candidateBrowseFileSlash.status(),
        "HTML browse slash file status");
  }

  @Test
  void hostedReleaseDeployRoundTripMatchesNexusWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run black-box compatibility");
    assumeTrue(config.writeEnabled(),
        "Set COMPAT_WRITE_ENABLED=true to run write/delete compatibility against disposable repos");

    ArtifactFixture fixture = ArtifactFixture.create();
    try {
      DeployResult reference = deploy(config.nexusRelease(), fixture);
      DeployResult candidate = deploy(config.nexusPlusRelease(), fixture);

      assertSameExchange("PUT pom", reference.putPom(), candidate.putPom(), false);
      assertSameExchangeList("PUT pom sidecars", reference.putPomSidecars(),
          candidate.putPomSidecars(), false);
      assertSameExchange("PUT jar", reference.putJar(), candidate.putJar(), false);
      assertSameExchangeList("PUT jar sidecars", reference.putJarSidecars(),
          candidate.putJarSidecars(), false);
      assertSameExchange("PUT metadata", reference.putMetadata(), candidate.putMetadata(), false);
      assertSameExchangeList("PUT metadata sidecars", reference.putMetadataSidecars(),
          candidate.putMetadataSidecars(), false);
      assertSameExchange("GET pom", reference.getPom(), candidate.getPom(), true);
      assertSameExchange("GET jar", reference.getJar(), candidate.getJar(), true);
      assertSameExchange("HEAD jar", reference.headJar(), candidate.headJar(), false);
      assertSameExchange("GET metadata", reference.getMetadata(), candidate.getMetadata(), true);

      assertChecksum(reference.getJarSha1(), fixture.jarSha1());
      assertChecksum(candidate.getJarSha1(), fixture.jarSha1());
      assertChecksum(reference.getPomSha256(), fixture.pomSha256());
      assertChecksum(candidate.getPomSha256(), fixture.pomSha256());
    } finally {
      cleanup(config.nexusRelease(), fixture);
      cleanup(config.nexusPlusRelease(), fixture);
    }
  }

  @Test
  void hostedPlainPutDoesNotGenerateSidecarsOrMetadataLikeNexusWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run black-box compatibility");
    assumeTrue(config.writeEnabled(),
        "Set COMPAT_WRITE_ENABLED=true to run write/delete compatibility against disposable repos");

    ArtifactFixture fixture = ArtifactFixture.create();
    try {
      Exchange referencePut = put(config.nexusRelease(), fixture.jarPath(), fixture.jar(),
          "application/java-archive");
      Exchange candidatePut = put(config.nexusPlusRelease(), fixture.jarPath(), fixture.jar(),
          "application/java-archive");
      assertSameExchange("plain PUT jar", referencePut, candidatePut, false);

      Thread.sleep(1500);

      assertSameExchange("plain PUT jar checksum miss",
          get(config.nexusRelease(), fixture.jarPath() + ".sha1"),
          get(config.nexusPlusRelease(), fixture.jarPath() + ".sha1"),
          false);
      assertSameExchange("plain PUT metadata miss",
          get(config.nexusRelease(), fixture.metadataPath()),
          get(config.nexusPlusRelease(), fixture.metadataPath()),
          false);
    } finally {
      cleanup(config.nexusRelease(), fixture);
      cleanup(config.nexusPlusRelease(), fixture);
    }
  }

  @Test
  void hostedSnapshotDeployRoundTripMatchesNexusWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run black-box compatibility");
    assumeTrue(config.writeEnabled(),
        "Set COMPAT_WRITE_ENABLED=true to run write/delete compatibility against disposable repos");

    SnapshotFixture fixture = SnapshotFixture.create();
    try {
      SnapshotDeployResult reference = deploySnapshot(config.nexusSnapshot(), fixture);
      SnapshotDeployResult candidate = deploySnapshot(config.nexusPlusSnapshot(), fixture);

      assertSameExchange("PUT snapshot pom", reference.putPom(), candidate.putPom(), false);
      assertSameExchangeList("PUT snapshot pom sidecars", reference.putPomSidecars(),
          candidate.putPomSidecars(), false);
      assertSameExchange("PUT snapshot jar", reference.putJar(), candidate.putJar(), false);
      assertSameExchangeList("PUT snapshot jar sidecars", reference.putJarSidecars(),
          candidate.putJarSidecars(), false);
      assertSameExchange("PUT snapshot metadata", reference.putSnapshotMetadata(),
          candidate.putSnapshotMetadata(), false);
      assertSameExchangeList("PUT snapshot metadata sidecars",
          reference.putSnapshotMetadataSidecars(), candidate.putSnapshotMetadataSidecars(), false);
      assertSameExchange("PUT GA metadata", reference.putArtifactMetadata(),
          candidate.putArtifactMetadata(), false);
      assertSameExchangeList("PUT GA metadata sidecars", reference.putArtifactMetadataSidecars(),
          candidate.putArtifactMetadataSidecars(), false);
      assertSameExchange("GET snapshot pom", reference.getPom(), candidate.getPom(), true);
      assertSameExchange("GET snapshot jar", reference.getJar(), candidate.getJar(), true);
      assertSameExchange("HEAD snapshot jar", reference.headJar(), candidate.headJar(), false);
      assertSameExchange("GET snapshot metadata", reference.getSnapshotMetadata(),
          candidate.getSnapshotMetadata(), true);
      assertSameExchange("GET GA metadata", reference.getArtifactMetadata(),
          candidate.getArtifactMetadata(), true);

      assertChecksum(reference.getJarSha1(), fixture.jarSha1());
      assertChecksum(candidate.getJarSha1(), fixture.jarSha1());
      assertChecksum(reference.getSnapshotMetadataSha256(), fixture.snapshotMetadataSha256());
      assertChecksum(candidate.getSnapshotMetadataSha256(), fixture.snapshotMetadataSha256());
    } finally {
      cleanup(config.nexusSnapshot(), fixture);
      cleanup(config.nexusPlusSnapshot(), fixture);
    }
  }

  private static DeployResult deploy(Endpoint endpoint, ArtifactFixture fixture) throws Exception {
    Exchange putPom = put(endpoint, fixture.pomPath(), fixture.pom(), "application/xml");
    List<Exchange> putPomSidecars = putSidecars(endpoint, fixture.pomPath(), fixture.pom());
    Exchange putJar = put(endpoint, fixture.jarPath(), fixture.jar(), "application/java-archive");
    List<Exchange> putJarSidecars = putSidecars(endpoint, fixture.jarPath(), fixture.jar());
    Exchange putMetadata = put(endpoint, fixture.metadataPath(), fixture.metadata(), "application/xml");
    List<Exchange> putMetadataSidecars = putSidecars(endpoint, fixture.metadataPath(), fixture.metadata());

    assert2xx(endpoint.name() + " PUT pom", putPom);
    assertAll2xx(endpoint.name() + " PUT pom sidecars", putPomSidecars);
    assert2xx(endpoint.name() + " PUT jar", putJar);
    assertAll2xx(endpoint.name() + " PUT jar sidecars", putJarSidecars);
    assert2xx(endpoint.name() + " PUT metadata", putMetadata);
    assertAll2xx(endpoint.name() + " PUT metadata sidecars", putMetadataSidecars);

    return new DeployResult(
        putPom,
        putPomSidecars,
        putJar,
        putJarSidecars,
        putMetadata,
        putMetadataSidecars,
        get(endpoint, fixture.pomPath()),
        get(endpoint, fixture.jarPath()),
        head(endpoint, fixture.jarPath()),
        get(endpoint, fixture.metadataPath()),
        get(endpoint, fixture.jarPath() + ".sha1"),
        get(endpoint, fixture.pomPath() + ".sha256"));
  }

  static ReleaseDeployment deployReleaseFixture(
      String repository,
      String referenceBaseUrl,
      String referenceUsername,
      String referencePassword,
      String candidateBaseUrl,
      String candidateUsername,
      String candidatePassword) throws Exception {
    ArtifactFixture fixture = ArtifactFixture.create();
    Endpoint reference = releaseEndpoint(
        "nexus", referenceBaseUrl, repository, referenceUsername, referencePassword);
    Endpoint candidate = releaseEndpoint(
        "kkrepo", candidateBaseUrl, repository, candidateUsername, candidatePassword);
    deploy(reference, fixture);
    deploy(candidate, fixture);
    return new ReleaseDeployment(reference, candidate, fixture);
  }

  private SnapshotDeployResult deploySnapshot(Endpoint endpoint, SnapshotFixture fixture) throws Exception {
    Exchange putPom = put(endpoint, fixture.pomPath(), fixture.pom(), "application/xml");
    List<Exchange> putPomSidecars = putSidecars(endpoint, fixture.pomPath(), fixture.pom());
    Exchange putJar = put(endpoint, fixture.jarPath(), fixture.jar(), "application/java-archive");
    List<Exchange> putJarSidecars = putSidecars(endpoint, fixture.jarPath(), fixture.jar());
    Exchange putSnapshotMetadata =
        put(endpoint, fixture.snapshotMetadataPath(), fixture.snapshotMetadata(), "application/xml");
    List<Exchange> putSnapshotMetadataSidecars =
        putSidecars(endpoint, fixture.snapshotMetadataPath(), fixture.snapshotMetadata());
    Exchange putArtifactMetadata =
        put(endpoint, fixture.artifactMetadataPath(), fixture.artifactMetadata(), "application/xml");
    List<Exchange> putArtifactMetadataSidecars =
        putSidecars(endpoint, fixture.artifactMetadataPath(), fixture.artifactMetadata());

    assert2xx(endpoint.name() + " PUT snapshot pom", putPom);
    assertAll2xx(endpoint.name() + " PUT snapshot pom sidecars", putPomSidecars);
    assert2xx(endpoint.name() + " PUT snapshot jar", putJar);
    assertAll2xx(endpoint.name() + " PUT snapshot jar sidecars", putJarSidecars);
    assert2xx(endpoint.name() + " PUT snapshot metadata", putSnapshotMetadata);
    assertAll2xx(endpoint.name() + " PUT snapshot metadata sidecars", putSnapshotMetadataSidecars);
    assert2xx(endpoint.name() + " PUT artifact metadata", putArtifactMetadata);
    assertAll2xx(endpoint.name() + " PUT artifact metadata sidecars", putArtifactMetadataSidecars);

    return new SnapshotDeployResult(
        putPom,
        putPomSidecars,
        putJar,
        putJarSidecars,
        putSnapshotMetadata,
        putSnapshotMetadataSidecars,
        putArtifactMetadata,
        putArtifactMetadataSidecars,
        get(endpoint, fixture.pomPath()),
        get(endpoint, fixture.jarPath()),
        head(endpoint, fixture.jarPath()),
        get(endpoint, fixture.snapshotMetadataPath()),
        get(endpoint, fixture.artifactMetadataPath()),
        get(endpoint, fixture.jarPath() + ".sha1"),
        get(endpoint, fixture.snapshotMetadataPath() + ".sha256"));
  }

  private static List<Exchange> putSidecars(Endpoint endpoint, String path, byte[] body)
      throws Exception {
    return List.of(
        put(endpoint, path + ".md5", checksum(body, "MD5"), "text/plain"),
        put(endpoint, path + ".sha1", checksum(body, "SHA-1"), "text/plain"),
        put(endpoint, path + ".sha256", checksum(body, "SHA-256"), "text/plain"),
        put(endpoint, path + ".sha512", checksum(body, "SHA-512"), "text/plain"));
  }

  private static void cleanup(Endpoint endpoint, MavenFixture fixture) throws Exception {
    for (String path : fixture.allPathsForCleanup()) {
      delete(endpoint, path);
    }
  }

  private static Exchange put(Endpoint endpoint, String path, byte[] body, String contentType) throws Exception {
    HttpRequest.Builder builder = endpoint.request(path)
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", contentType)
        .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
    return send(builder);
  }

  private static Exchange get(Endpoint endpoint, String path) throws Exception {
    return send(endpoint.request(path).GET());
  }

  private static Exchange head(Endpoint endpoint, String path) throws Exception {
    return send(endpoint.request(path).method("HEAD", HttpRequest.BodyPublishers.noBody()));
  }

  private static void delete(Endpoint endpoint, String path) throws Exception {
    send(endpoint.request(path).DELETE());
  }

  private static Exchange send(HttpRequest.Builder builder) throws Exception {
    return send(HTTP, builder);
  }

  private static Exchange sendNoRedirect(HttpRequest.Builder builder) throws Exception {
    return send(NO_REDIRECT_HTTP, builder);
  }

  private static Exchange send(HttpClient client, HttpRequest.Builder builder) throws Exception {
    HttpResponse<byte[]> response = client.send(
        builder.header("User-Agent", "kkrepo-compat-test/1")
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
    return new Exchange(response.statusCode(), response.body(),
        response.headers().firstValue("content-length"),
        response.headers().firstValue("etag"),
        response.headers().firstValue("last-modified"));
  }

  private static void assertSameExchange(
      String label, Exchange reference, Exchange candidate, boolean compareBody) {
    assertEquals(reference.status(), candidate.status(), label + " status");
    if (compareBody) {
      assertArrayEquals(reference.body(), candidate.body(), label + " body");
    }
    assertEquals(reference.etag().isPresent(), candidate.etag().isPresent(), label + " ETag presence");
    assertEquals(reference.lastModified().isPresent(), candidate.lastModified().isPresent(),
        label + " Last-Modified presence");
    if (reference.contentLength().isPresent() && candidate.contentLength().isPresent()) {
      assertEquals(reference.contentLength().get(), candidate.contentLength().get(),
          label + " Content-Length");
    }
  }

  private static void assertSameExchangeList(
      String label, List<Exchange> reference, List<Exchange> candidate, boolean compareBody) {
    assertEquals(reference.size(), candidate.size(), label + " count");
    for (int i = 0; i < reference.size(); i++) {
      assertSameExchange(label + "[" + i + "]", reference.get(i), candidate.get(i), compareBody);
    }
  }

  private static void assert2xx(String label, Exchange exchange) {
    assertTrue(exchange.status() >= 200 && exchange.status() < 300,
        label + " expected 2xx but got " + exchange.status());
  }

  private static void assertAll2xx(String label, List<Exchange> exchanges) {
    for (int i = 0; i < exchanges.size(); i++) {
      assert2xx(label + "[" + i + "]", exchanges.get(i));
    }
  }

  private static void assertChecksum(Exchange exchange, byte[] expected) {
    assert2xx("checksum GET", exchange);
    assertArrayEquals(expected, exchange.body());
  }

  private static byte[] checksum(byte[] body, String algorithm) throws Exception {
    return (HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(body)) + "\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private record Exchange(
      int status,
      byte[] body,
      Optional<String> contentLength,
      Optional<String> etag,
      Optional<String> lastModified) {}

  private record DeployResult(
      Exchange putPom,
      List<Exchange> putPomSidecars,
      Exchange putJar,
      List<Exchange> putJarSidecars,
      Exchange putMetadata,
      List<Exchange> putMetadataSidecars,
      Exchange getPom,
      Exchange getJar,
      Exchange headJar,
      Exchange getMetadata,
      Exchange getJarSha1,
      Exchange getPomSha256) {}

  static final class ReleaseDeployment implements AutoCloseable {
    private final Endpoint reference;
    private final Endpoint candidate;
    private final ArtifactFixture fixture;

    private ReleaseDeployment(
        Endpoint reference, Endpoint candidate, ArtifactFixture fixture) {
      this.reference = reference;
      this.candidate = candidate;
      this.fixture = fixture;
    }

    String artifactId() {
      return fixture.artifactId();
    }

    String jarPath() {
      return fixture.jarPath();
    }

    @Override
    public void close() throws Exception {
      Exception failure = null;
      try {
        cleanup(reference, fixture);
      } catch (Exception exception) {
        failure = exception;
      }
      try {
        cleanup(candidate, fixture);
      } catch (Exception exception) {
        if (failure == null) {
          failure = exception;
        } else {
          failure.addSuppressed(exception);
        }
      }
      if (failure != null) {
        throw failure;
      }
    }
  }

  private record SnapshotDeployResult(
      Exchange putPom,
      List<Exchange> putPomSidecars,
      Exchange putJar,
      List<Exchange> putJarSidecars,
      Exchange putSnapshotMetadata,
      List<Exchange> putSnapshotMetadataSidecars,
      Exchange putArtifactMetadata,
      List<Exchange> putArtifactMetadataSidecars,
      Exchange getPom,
      Exchange getJar,
      Exchange headJar,
      Exchange getSnapshotMetadata,
      Exchange getArtifactMetadata,
      Exchange getJarSha1,
      Exchange getSnapshotMetadataSha256) {}

  private interface MavenFixture {
    List<String> allPathsForCleanup();
  }

  private record ArtifactFixture(
      String artifactId,
      String basePath,
      String version,
      byte[] pom,
      byte[] jar,
      byte[] metadata,
      byte[] jarSha1,
      byte[] pomSha256) implements MavenFixture {
    static ArtifactFixture create() throws Exception {
      long millis = System.currentTimeMillis();
      String version = "1.0." + millis;
      String artifactId = "kkrepo-compat-" + millis;
      String groupPath = "com/github/klboke/compat";
      String basePath = groupPath + "/" + artifactId;
      byte[] pom = ("""
          <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.github.klboke.compat</groupId>
            <artifactId>%s</artifactId>
            <version>%s</version>
          </project>
          """.formatted(artifactId, version)).getBytes(StandardCharsets.UTF_8);
      byte[] jar = ("kkrepo compatibility fixture " + version + "\n")
          .getBytes(StandardCharsets.UTF_8);
      byte[] metadata = ("""
          <metadata>
            <groupId>com.github.klboke.compat</groupId>
            <artifactId>%s</artifactId>
            <versioning>
              <latest>%s</latest>
              <release>%s</release>
              <versions>
                <version>%s</version>
              </versions>
              <lastUpdated>20260101000000</lastUpdated>
            </versioning>
          </metadata>
          """.formatted(artifactId, version, version, version)).getBytes(StandardCharsets.UTF_8);
      return new ArtifactFixture(
          artifactId, basePath, version, pom, jar, metadata, checksum(jar, "SHA-1"),
          checksum(pom, "SHA-256"));
    }

    String versionPath() {
      return basePath + "/" + version;
    }

    String pomPath() {
      return versionPath() + "/" + artifactId + "-" + version + ".pom";
    }

    String jarPath() {
      return versionPath() + "/" + artifactId + "-" + version + ".jar";
    }

    String metadataPath() {
      return basePath + "/maven-metadata.xml";
    }

    @Override
    public List<String> allPathsForCleanup() {
      List<String> paths = new ArrayList<>();
      for (String path : List.of(pomPath(), jarPath(), metadataPath())) {
        addPathAndSidecars(paths, path);
      }
      return paths;
    }
  }

  private record SnapshotFixture(
      String artifactId,
      String basePath,
      String version,
      String timestamp,
      int buildNumber,
      String dotlessUpdated,
      byte[] pom,
      byte[] jar,
      byte[] snapshotMetadata,
      byte[] artifactMetadata,
      byte[] jarSha1,
      byte[] snapshotMetadataSha256) implements MavenFixture {
    static SnapshotFixture create() throws Exception {
      long millis = System.currentTimeMillis();
      Instant instant = Instant.ofEpochMilli(millis);
      String timestamp = DOTTED.format(instant);
      String dotless = DOTLESS.format(instant);
      int buildNumber = (int) (millis % 100_000L) + 1;
      String artifactId = "kkrepo-compat-snapshot-" + millis;
      String version = "1.0-SNAPSHOT";
      String timestampedVersion = "1.0-" + timestamp + "-" + buildNumber;
      String groupPath = "com/github/klboke/compat";
      String basePath = groupPath + "/" + artifactId;
      byte[] pom = ("""
          <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.github.klboke.compat</groupId>
            <artifactId>%s</artifactId>
            <version>%s</version>
          </project>
          """.formatted(artifactId, version)).getBytes(StandardCharsets.UTF_8);
      byte[] jar = ("kkrepo snapshot compatibility fixture " + timestampedVersion + "\n")
          .getBytes(StandardCharsets.UTF_8);
      byte[] snapshotMetadata = ("""
          <metadata>
            <groupId>com.github.klboke.compat</groupId>
            <artifactId>%s</artifactId>
            <version>%s</version>
            <versioning>
              <snapshot>
                <timestamp>%s</timestamp>
                <buildNumber>%d</buildNumber>
              </snapshot>
              <lastUpdated>%s</lastUpdated>
              <snapshotVersions>
                <snapshotVersion>
                  <extension>pom</extension>
                  <value>%s</value>
                  <updated>%s</updated>
                </snapshotVersion>
                <snapshotVersion>
                  <extension>jar</extension>
                  <value>%s</value>
                  <updated>%s</updated>
                </snapshotVersion>
              </snapshotVersions>
            </versioning>
          </metadata>
          """.formatted(artifactId, version, timestamp, buildNumber, dotless,
              timestampedVersion, dotless, timestampedVersion, dotless)).getBytes(StandardCharsets.UTF_8);
      byte[] artifactMetadata = ("""
          <metadata>
            <groupId>com.github.klboke.compat</groupId>
            <artifactId>%s</artifactId>
            <versioning>
              <latest>%s</latest>
              <versions>
                <version>%s</version>
              </versions>
              <lastUpdated>%s</lastUpdated>
            </versioning>
          </metadata>
          """.formatted(artifactId, version, version, dotless)).getBytes(StandardCharsets.UTF_8);
      return new SnapshotFixture(
          artifactId,
          basePath,
          version,
          timestamp,
          buildNumber,
          dotless,
          pom,
          jar,
          snapshotMetadata,
          artifactMetadata,
          checksum(jar, "SHA-1"),
          checksum(snapshotMetadata, "SHA-256"));
    }

    String versionPath() {
      return basePath + "/" + version;
    }

    String timestampedPrefix() {
      return artifactId + "-1.0-" + timestamp + "-" + buildNumber;
    }

    String pomPath() {
      return versionPath() + "/" + timestampedPrefix() + ".pom";
    }

    String jarPath() {
      return versionPath() + "/" + timestampedPrefix() + ".jar";
    }

    String snapshotMetadataPath() {
      return versionPath() + "/maven-metadata.xml";
    }

    String artifactMetadataPath() {
      return basePath + "/maven-metadata.xml";
    }

    @Override
    public List<String> allPathsForCleanup() {
      List<String> paths = new ArrayList<>();
      for (String path : List.of(pomPath(), jarPath(), snapshotMetadataPath(), artifactMetadataPath())) {
        addPathAndSidecars(paths, path);
      }
      return paths;
    }
  }

  private static void addPathAndSidecars(List<String> paths, String path) {
    paths.add(path);
    paths.add(path + ".md5");
    paths.add(path + ".sha1");
    paths.add(path + ".sha256");
    paths.add(path + ".sha512");
  }

  private record CompatConfig(Endpoint nexus, Endpoint nexusPlus, boolean writeEnabled, String readPath) {
    static CompatConfig load() {
      Endpoint nexus = new Endpoint(
          "nexus",
          CompatDefaults.nexusBaseUrl(),
          setting("compat.nexus.repository", "NEXUS_COMPAT_REPOSITORY").orElse("maven-releases"),
          CompatDefaults.nexusUsername(),
          CompatDefaults.nexusPassword());
      Endpoint nexusPlus = new Endpoint(
          "kkrepo",
          CompatDefaults.nexusPlusBaseUrl(),
          setting("compat.nexusPlus.repository", "KKREPO_COMPAT_REPOSITORY").orElse("maven-releases"),
          CompatDefaults.nexusPlusUsername(),
          CompatDefaults.nexusPlusPassword());
      boolean writeEnabled = Boolean.parseBoolean(
          setting("compat.write.enabled", "COMPAT_WRITE_ENABLED").orElse("false"));
      String readPath = setting("compat.read.path", "COMPAT_READ_PATH")
          .orElse("junit/junit/4.13.2/junit-4.13.2.pom");
      return new CompatConfig(nexus, nexusPlus, writeEnabled, readPath);
    }

    boolean configured() {
      return nexus.baseUrl().isPresent() && nexusPlus.baseUrl().isPresent();
    }

    Endpoint nexusRead() {
      return nexus.withRepository(
          setting("compat.nexus.readRepository", "NEXUS_COMPAT_READ_REPOSITORY").orElse("maven-public"));
    }

    Endpoint nexusPlusRead() {
      return nexusPlus.withRepository(
          setting("compat.nexusPlus.readRepository", "KKREPO_COMPAT_READ_REPOSITORY").orElse("maven-public"));
    }

    Endpoint nexusRelease() {
      return nexus.withRepository(setting("compat.nexus.releaseRepository", "NEXUS_COMPAT_RELEASE_REPOSITORY")
          .orElse(nexus.repository()));
    }

    Endpoint nexusPlusRelease() {
      return nexusPlus.withRepository(
          setting("compat.nexusPlus.releaseRepository", "KKREPO_COMPAT_RELEASE_REPOSITORY")
              .orElse(nexusPlus.repository()));
    }

    Endpoint nexusSnapshot() {
      return nexus.withRepository(setting("compat.nexus.snapshotRepository", "NEXUS_COMPAT_SNAPSHOT_REPOSITORY")
          .orElse("maven-snapshots"));
    }

    Endpoint nexusPlusSnapshot() {
      return nexusPlus.withRepository(
          setting("compat.nexusPlus.snapshotRepository", "KKREPO_COMPAT_SNAPSHOT_REPOSITORY")
              .orElse("maven-snapshots"));
    }
  }

  private record Endpoint(
      String name,
      Optional<String> baseUrl,
      String repository,
      Optional<String> username,
      Optional<String> password) {
    HttpRequest.Builder request(String repositoryPath) {
      URI uri = URI.create(baseUrl.orElseThrow() + "/repository/" + repository + "/" + repositoryPath);
      HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
      addAuth(builder);
      return builder;
    }

    HttpRequest.Builder browseRequest(String repositoryPath) {
      URI uri = URI.create(baseUrl.orElseThrow() + "/service/rest/repository/browse/"
          + repository + "/" + repositoryPath);
      HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
      addAuth(builder);
      return builder;
    }

    private void addAuth(HttpRequest.Builder builder) {
      if (username.isPresent() && password.isPresent()) {
        String token = Base64.getEncoder().encodeToString(
            (username.get() + ":" + password.get()).getBytes(StandardCharsets.UTF_8));
        builder.header("Authorization", "Basic " + token);
      }
    }

    Endpoint withRepository(String repository) {
      return new Endpoint(name, baseUrl, repository, username, password);
    }
  }

  private static Endpoint releaseEndpoint(
      String name, String baseUrl, String repository, String username, String password) {
    return new Endpoint(
        name,
        Optional.of(stripTrailingSlash(baseUrl)),
        repository,
        optionalSetting(username),
        optionalSetting(password));
  }

  private static Optional<String> optionalSetting(String value) {
    return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
  }

  private static Optional<String> setting(String property, String env) {
    String value = System.getProperty(property);
    if (value == null || value.isBlank()) {
      value = System.getenv(env);
    }
    return value == null || value.isBlank()
        ? Optional.empty()
        : Optional.of(value.trim());
  }

  private static Optional<String> urlSetting(String property, String env) {
    return setting(property, env).map(MavenRepositoryBlackBoxCompatibilityTest::stripTrailingSlash);
  }

  private static String stripTrailingSlash(String value) {
    String result = value;
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
