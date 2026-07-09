package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Live npm compatibility checks against a reference Nexus and kkrepo.
 *
 * <p>Skipped unless {@code NEXUS_COMPAT_BASE_URL} and {@code KKREPO_COMPAT_BASE_URL}
 * are supplied. Write-side hosted checks also require {@code COMPAT_WRITE_ENABLED=true}.
 */
class NpmRepositoryBlackBoxCompatibilityTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();

  @Test
  void hostedPublishTarballAndDistTagsMatchNexusWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run npm compatibility");
    assumeTrue(config.writeEnabled(),
        "Set COMPAT_WRITE_ENABLED=true to run npm write/delete compatibility against disposable repos");

    NpmFixture fixture = NpmFixture.create();
    Endpoint nexus = config.nexusHosted();
    Endpoint nexusPlus = config.nexusPlusHosted();
    try {
      Exchange referencePut = putJson(nexus, fixture.packageName(), fixture.publishJson());
      Exchange candidatePut = putJson(nexusPlus, fixture.packageName(), fixture.publishJson());
      assert2xx("Nexus npm publish", referencePut);
      assert2xx("kkrepo npm publish", candidatePut);

      Map<String, Object> referencePackage = getJson(nexus, fixture.packageName());
      Map<String, Object> candidatePackage = getJson(nexusPlus, fixture.packageName());
      assertPackageShape(fixture, referencePackage);
      assertPackageShape(fixture, candidatePackage);

      Exchange referenceTarball = get(nexus, tarballPath(nexus, referencePackage, fixture.version()));
      Exchange candidateTarball = get(nexusPlus, tarballPath(nexusPlus, candidatePackage, fixture.version()));
      assertEquals(referenceTarball.status(), candidateTarball.status(), "tarball status");
      assertContentTypeMatches(referenceTarball, candidateTarball, "tarball content type");
      assertArrayEquals(referenceTarball.body(), candidateTarball.body(), "tarball body");
      assertArrayEquals(fixture.tarball(), candidateTarball.body(), "published tarball bytes");

      Exchange candidateHead = head(nexusPlus, tarballPath(nexusPlus, candidatePackage, fixture.version()));
      assertEquals(referenceTarball.status(), candidateHead.status(), "candidate HEAD tarball status");
      assertEquals(normalizedContentType(referenceTarball), normalizedContentType(candidateHead),
          "candidate HEAD tarball content type");

      assertDistTags(nexus, nexusPlus, fixture);
      assertSearchContains(nexus, fixture.packageName());
      assertSearchContains(nexusPlus, fixture.packageName());
    } finally {
      delete(nexus, fixture.packageName());
      delete(nexusPlus, fixture.packageName());
    }
  }

  @Test
  void proxyPackageMetadataAndTarballMatchNexusWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run npm compatibility");

    assertReadRepositoryMatches(config.nexusProxy(), config.nexusPlusProxy(), config.readPackage());
  }

  @Test
  void groupPackageMetadataAndTarballMatchNexusWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run npm compatibility");

    assertReadRepositoryMatches(config.nexusGroup(), config.nexusPlusGroup(), config.readPackage());
  }

  @Test
  void whoamiMatchesNexusWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run npm compatibility");

    assertWhoamiMatches(config.nexusHosted(), config.nexusPlusHosted());
    assertWhoamiMatches(config.nexusProxy(), config.nexusPlusProxy());
    assertWhoamiMatches(config.nexusGroup(), config.nexusPlusGroup());
  }

  @Test
  void proxyConcurrentTarballFetchesDoNotReturnServerErrorsWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run npm compatibility");

    Endpoint candidate = config.nexusPlusProxy();
    String packageName = setting("compat.npm.concurrentPackage", "COMPAT_NPM_CONCURRENT_PACKAGE")
        .orElse(config.readPackage());
    int requestCount = positiveIntSetting("compat.npm.concurrentRequests",
        "COMPAT_NPM_CONCURRENT_REQUESTS", 50);
    int concurrency = positiveIntSetting("compat.npm.concurrentThreads",
        "COMPAT_NPM_CONCURRENT_THREADS", 16);
    Map<String, Object> packageRoot = getJson(candidate, packageName);
    List<String> uniqueTarballs = tarballPaths(candidate, packageRoot, requestCount);
    assumeTrue(!uniqueTarballs.isEmpty(), "Package " + packageName + " has no tarballs to fetch");

    List<String> requests = new ArrayList<>(requestCount);
    for (int i = 0; i < requestCount; i++) {
      requests.add(uniqueTarballs.get(i % uniqueTarballs.size()));
    }

    ExecutorService executor = Executors.newFixedThreadPool(Math.min(concurrency, requests.size()));
    try {
      List<Future<Exchange>> futures = new ArrayList<>(requests.size());
      for (String tarballPath : requests) {
        futures.add(executor.submit(() -> get(candidate, tarballPath)));
      }
      for (int i = 0; i < futures.size(); i++) {
        Exchange exchange = futures.get(i).get(120, TimeUnit.SECONDS);
        assertTrue(exchange.status() < 500,
            "concurrent npm tarball fetch should not return server error for " + requests.get(i)
                + ", got " + exchange.status());
        assert2xx("concurrent npm tarball fetch " + requests.get(i), exchange);
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void hostedConcurrentPublishesDoNotReturnServerErrorsWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run npm compatibility");
    assumeTrue(config.writeEnabled(),
        "Set COMPAT_WRITE_ENABLED=true to run npm write/delete compatibility against disposable repos");

    Endpoint candidate = config.nexusPlusHosted();
    int publishCount = positiveIntSetting("compat.npm.concurrentPublishes",
        "COMPAT_NPM_CONCURRENT_PUBLISHES", 24);
    int concurrency = positiveIntSetting("compat.npm.concurrentPublishThreads",
        "COMPAT_NPM_CONCURRENT_PUBLISH_THREADS", 12);
    long batchId = System.currentTimeMillis();
    List<NpmFixture> fixtures = new ArrayList<>(publishCount);
    for (int i = 0; i < publishCount; i++) {
      fixtures.add(NpmFixture.create(
          "kkrepo-npm-compat-publish-" + batchId + "-" + i, "1.0." + i));
    }

    ExecutorService executor = Executors.newFixedThreadPool(Math.min(concurrency, publishCount));
    try {
      List<Future<Exchange>> futures = new ArrayList<>(publishCount);
      for (NpmFixture fixture : fixtures) {
        futures.add(executor.submit(
            () -> putJson(candidate, fixture.packageName(), fixture.publishJson())));
      }
      for (int i = 0; i < futures.size(); i++) {
        Exchange exchange = futures.get(i).get(120, TimeUnit.SECONDS);
        String packageName = fixtures.get(i).packageName();
        assertTrue(exchange.status() < 500,
            "concurrent npm publish should not return server error for " + packageName
                + ", got " + exchange.status());
        assert2xx("concurrent npm publish " + packageName, exchange);
      }
    } finally {
      executor.shutdownNow();
      for (NpmFixture fixture : fixtures) {
        try {
          delete(candidate, fixture.packageName());
        } catch (Exception ignored) {
          // best-effort cleanup; the test repository is disposable
        }
      }
    }
  }

  @Test
  void repositoryRootInfoPagesMatchNexusWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run npm compatibility");

    assertRepositoryRootInfo(config.nexusHosted(), config.nexusPlusHosted());
    assertRepositoryRootInfo(config.nexusProxy(), config.nexusPlusProxy());
    assertRepositoryRootInfo(config.nexusGroup(), config.nexusPlusGroup());
  }

  @Test
  void htmlBrowsePackageIndexHidesNpmTarballDashDirectoryWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run npm compatibility");

    Endpoint reference = config.nexusGroup();
    Endpoint candidate = config.nexusPlusGroup();
    String packagePath = config.readPackage() + "/";
    Exchange referenceIndex = send(reference.browseRequest(packagePath).GET());
    Exchange candidateIndex = send(candidate.browseRequest(packagePath).GET());
    assertEquals(referenceIndex.status(), candidateIndex.status(), "npm browse package index status");
    assertEquals(200, candidateIndex.status(), "candidate npm browse package index should be OK");

    String candidateBody = new String(candidateIndex.body(), StandardCharsets.UTF_8);
    assertFalse(candidateBody.contains(">-/<"),
        "candidate npm package browse index should not expose the internal - directory");
    assertTrue(candidateBody.contains(".tgz"),
        "candidate npm package browse index should show tarballs directly");

    Exchange referenceDash = send(reference.browseRequest(packagePath + "-/").GET());
    Exchange candidateDash = send(candidate.browseRequest(packagePath + "-/").GET());
    assertEquals(referenceDash.status(), candidateDash.status(), "npm internal dash browse status");
  }

  @Test
  void browseUiTreeHidesNpmTarballDashDirectoryWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run npm compatibility");

    Endpoint candidate = config.nexusPlusGroup();
    Exchange candidateTree = send(candidate.internalBrowseRequest(config.readPackage()).GET());
    assert2xx("candidate npm internal browse tree", candidateTree);

    String body = new String(candidateTree.body(), StandardCharsets.UTF_8);
    assertFalse(body.contains("\"name\":\"-\""),
        "candidate npm browse tree should not expose the internal - directory: " + body);
    assertTrue(body.contains(".tgz"),
        "candidate npm browse tree should show tarballs directly: " + body);
    assertTrue(body.contains("\"downloadUrl\":\"/repository/" + candidate.repository()
            + "/" + config.readPackage() + "/-/"),
        "candidate npm browse tree should keep npm tarball download URL: " + body);
  }

  private void assertReadRepositoryMatches(Endpoint reference, Endpoint candidate, String packageName) throws Exception {
    Map<String, Object> referencePackage = getJson(reference, packageName);
    Map<String, Object> candidatePackage = getJson(candidate, packageName);
    String referenceLatest = latest(referencePackage);
    String candidateLatest = latest(candidatePackage);
    assertEquals(referencePackage.get("name"), candidatePackage.get("name"), "package name");
    assertEquals(referenceLatest, candidateLatest, "latest dist-tag");

    Exchange referenceTarball = get(reference, tarballPath(reference, referencePackage, referenceLatest));
    Exchange candidateTarball = get(candidate, tarballPath(candidate, candidatePackage, candidateLatest));
    assertEquals(referenceTarball.status(), candidateTarball.status(), "tarball status");
    assertContentTypeMatches(referenceTarball, candidateTarball, "tarball content type");
    assertArrayEquals(referenceTarball.body(), candidateTarball.body(), "tarball body");
    assertSearchContains(reference, packageName);
    assertSearchContains(candidate, packageName);
  }

  private void assertRepositoryRootInfo(Endpoint reference, Endpoint candidate) throws Exception {
    Exchange referenceRoot = get(reference, "");
    Exchange candidateRoot = get(candidate, "");
    assertEquals(referenceRoot.status(), candidateRoot.status(),
        candidate.repository() + " repository root status");
    assertEquals(200, candidateRoot.status(), candidate.repository() + " repository root should be OK");
    assertTrue(candidateRoot.contentType().orElse("").contains("text/html"),
        candidate.repository() + " repository root should be HTML");
    String referenceBody = new String(referenceRoot.body(), StandardCharsets.UTF_8);
    String candidateBody = new String(candidateRoot.body(), StandardCharsets.UTF_8);
    assertTrue(referenceBody.contains("not directly browseable"),
        reference.repository() + " Nexus repository root should explain direct browsing");
    assertTrue(candidateBody.contains("not directly browseable"),
        candidate.repository() + " repository root should explain direct browsing");
  }

  private void assertWhoamiMatches(Endpoint reference, Endpoint candidate) throws Exception {
    Exchange referenceWhoami = get(reference, "-/whoami");
    Exchange candidateWhoami = get(candidate, "-/whoami");
    assertEquals(referenceWhoami.status(), candidateWhoami.status(), candidate.repository() + " whoami status");
    assert2xx(candidate.name() + " whoami", candidateWhoami);
    Map<String, Object> referenceBody = MAPPER.readValue(referenceWhoami.body(), MAP_TYPE);
    Map<String, Object> candidateBody = MAPPER.readValue(candidateWhoami.body(), MAP_TYPE);
    assertWhoamiUsername(reference, referenceBody);
    assertWhoamiUsername(candidate, candidateBody);
  }

  private void assertWhoamiUsername(Endpoint endpoint, Map<String, Object> body) {
    assertEquals(endpoint.username().orElse("anonymous"), body.get("username"),
        endpoint.repository() + " whoami username");
  }

  private void assertDistTags(Endpoint nexus, Endpoint nexusPlus, NpmFixture fixture) throws Exception {
    Map<String, Object> referenceTags = getJson(nexus, distTagsPath(fixture.packageName()));
    Map<String, Object> candidateTags = getJson(nexusPlus, distTagsPath(fixture.packageName()));
    assertEquals(referenceTags.get("latest"), candidateTags.get("latest"), "latest dist-tag");
    assertEquals(fixture.version(), candidateTags.get("latest"), "candidate latest dist-tag");

    assert2xx("Nexus dist-tag put", putJson(nexus, distTagPath(fixture.packageName(), "beta"),
        MAPPER.writeValueAsBytes(fixture.version())));
    assert2xx("kkrepo dist-tag put", putJson(nexusPlus, distTagPath(fixture.packageName(), "beta"),
        MAPPER.writeValueAsBytes(fixture.version())));

    Map<String, Object> candidateAfterPut = getJson(nexusPlus, distTagsPath(fixture.packageName()));
    assertEquals(fixture.version(), candidateAfterPut.get("beta"), "candidate beta dist-tag");

    delete(nexus, distTagPath(fixture.packageName(), "beta"));
    delete(nexusPlus, distTagPath(fixture.packageName(), "beta"));
    Map<String, Object> candidateAfterDelete = getJson(nexusPlus, distTagsPath(fixture.packageName()));
    assertFalse(candidateAfterDelete.containsKey("beta"), "candidate beta dist-tag removed");
  }

  private static void assertPackageShape(NpmFixture fixture, Map<String, Object> doc) {
    assertEquals(fixture.packageName(), doc.get("name"));
    assertEquals(fixture.version(), latest(doc));
    assertTrue(versions(doc).containsKey(fixture.version()));
    assertTrue(doc.containsKey("_rev"), "Nexus-compatible package root revision");
  }

  private static Exchange putJson(Endpoint endpoint, String path, byte[] body) throws Exception {
    return send(endpoint.request(path)
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "application/json")
        .PUT(HttpRequest.BodyPublishers.ofByteArray(body)));
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

  private static Map<String, Object> getJson(Endpoint endpoint, String path) throws Exception {
    Exchange exchange = get(endpoint, path);
    assert2xx(endpoint.name() + " GET " + path, exchange);
    return MAPPER.readValue(exchange.body(), MAP_TYPE);
  }

  private static Exchange send(HttpRequest.Builder builder) throws Exception {
    HttpResponse<byte[]> response = HTTP.send(
        builder.header("User-Agent", "kkrepo-npm-compat-test/1").build(),
        HttpResponse.BodyHandlers.ofByteArray());
    return new Exchange(response.statusCode(), response.body(),
        response.headers().firstValue("content-length"),
        response.headers().firstValue("content-type"));
  }

  private static void assert2xx(String label, Exchange exchange) {
    assertTrue(exchange.status() >= 200 && exchange.status() < 300,
        label + " expected 2xx but got " + exchange.status());
  }

  private static void assertContentTypeMatches(Exchange reference, Exchange candidate, String label) {
    assertEquals(normalizedContentType(reference), normalizedContentType(candidate), label);
  }

  private static String normalizedContentType(Exchange exchange) {
    String type = exchange.contentType()
        .map(value -> value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))
        .orElse("");
    if ("application/x-tgz".equals(type)) {
      return "application/octet-stream";
    }
    return type;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> versions(Map<String, Object> doc) {
    return (Map<String, Object>) doc.get("versions");
  }

  @SuppressWarnings("unchecked")
  private static String latest(Map<String, Object> doc) {
    return ((Map<String, Object>) doc.get("dist-tags")).get("latest").toString();
  }

  @SuppressWarnings("unchecked")
  private static String tarballPath(Endpoint endpoint, Map<String, Object> doc, String version) {
    Map<String, Object> versionDoc = (Map<String, Object>) versions(doc).get(version);
    Map<String, Object> dist = (Map<String, Object>) versionDoc.get("dist");
    return repositoryPathFromTarballUrl(endpoint, dist.get("tarball").toString());
  }

  @SuppressWarnings("unchecked")
  private static List<String> tarballPaths(Endpoint endpoint, Map<String, Object> doc, int maxItems) {
    Set<String> paths = new LinkedHashSet<>();
    for (Object versionRaw : versions(doc).values()) {
      if (!(versionRaw instanceof Map<?, ?> versionDoc)) {
        continue;
      }
      Object distRaw = versionDoc.get("dist");
      if (!(distRaw instanceof Map<?, ?> dist)) {
        continue;
      }
      Object tarball = dist.get("tarball");
      if (tarball != null) {
        paths.add(repositoryPathFromTarballUrl(endpoint, tarball.toString()));
      }
      if (paths.size() >= maxItems) {
        break;
      }
    }
    return new ArrayList<>(paths);
  }

  private static String repositoryPathFromTarballUrl(Endpoint endpoint, String tarball) {
    String marker = "/repository/" + endpoint.repository() + "/";
    int idx = tarball.indexOf(marker);
    if (idx >= 0) {
      return tarball.substring(idx + marker.length());
    }
    URI uri = URI.create(tarball);
    String path = uri.getPath();
    idx = path.indexOf(marker);
    return idx >= 0 ? path.substring(idx + marker.length()) : path.replaceFirst("^/", "");
  }

  private static String distTagsPath(String packageName) {
    return "-/package/" + packageName + "/dist-tags";
  }

  private static String distTagPath(String packageName, String tag) {
    return distTagsPath(packageName) + "/" + tag;
  }

  @SuppressWarnings("unchecked")
  private static void assertSearchContains(Endpoint endpoint, String packageName) throws Exception {
    Instant deadline = Instant.now().plusSeconds(15);
    AssertionError last = null;
    while (Instant.now().isBefore(deadline)) {
      Map<String, Object> search = getJson(endpoint, "-/v1/search?text=" + packageName + "&size=20");
      Object objectsRaw = search.get("objects");
      assertTrue(objectsRaw instanceof Iterable<?>, endpoint.name() + " search objects must be iterable");
      for (Object item : (Iterable<Object>) objectsRaw) {
        if (item instanceof Map<?, ?> itemMap && itemMap.get("package") instanceof Map<?, ?> packageMap
            && packageName.equals(packageMap.get("name"))) {
          return;
        }
      }
      last = new AssertionError(endpoint.name() + " npm search should contain " + packageName);
      Thread.sleep(1_000);
    }
    throw last == null
        ? new AssertionError(endpoint.name() + " npm search should contain " + packageName)
        : last;
  }

  private record Exchange(
      int status,
      byte[] body,
      Optional<String> contentLength,
      Optional<String> contentType) {}

  private record NpmFixture(String packageName, String version, byte[] tarball, byte[] publishJson) {
    static NpmFixture create() throws Exception {
      long stamp = System.currentTimeMillis();
      return create("kkrepo-npm-compat-" + stamp, "1.0." + stamp);
    }

    static NpmFixture create(String packageName, String version) throws Exception {
      String tarballName = packageName + "-" + version + ".tgz";
      byte[] tarball = tarGz("package/package.json",
          ("{\"name\":\"" + packageName + "\",\"version\":\"" + version + "\"}\n")
              .getBytes(StandardCharsets.UTF_8));
      String sha1 = hex(MessageDigest.getInstance("SHA-1").digest(tarball));
      String integrity = "sha512-" + Base64.getEncoder().encodeToString(
          MessageDigest.getInstance("SHA-512").digest(tarball));

      Map<String, Object> root = new LinkedHashMap<>();
      root.put("_id", packageName);
      root.put("name", packageName);
      root.put("description", "kkrepo npm compatibility fixture");
      root.put("dist-tags", Map.of("latest", version));

      Map<String, Object> versionDoc = new LinkedHashMap<>();
      versionDoc.put("name", packageName);
      versionDoc.put("version", version);
      versionDoc.put("description", "kkrepo npm compatibility fixture");
      versionDoc.put("dist", Map.of(
          "shasum", sha1,
          "integrity", integrity,
          "tarball", "http://example.invalid/" + packageName + "/-/" + tarballName));
      root.put("versions", Map.of(version, versionDoc));
      root.put("_attachments", Map.of(tarballName, Map.of(
          "content_type", "application/octet-stream",
          "data", Base64.getEncoder().encodeToString(tarball),
          "length", tarball.length)));
      return new NpmFixture(packageName, version, tarball, MAPPER.writeValueAsBytes(root));
    }
  }

  private static byte[] tarGz(String entryName, byte[] content) throws IOException {
    ByteArrayOutputStream tar = new ByteArrayOutputStream();
    byte[] header = new byte[512];
    ascii(header, 0, 100, entryName);
    octal(header, 100, 8, 0644);
    octal(header, 108, 8, 0);
    octal(header, 116, 8, 0);
    octal(header, 124, 12, content.length);
    octal(header, 136, 12, System.currentTimeMillis() / 1000);
    for (int i = 148; i < 156; i++) {
      header[i] = (byte) ' ';
    }
    header[156] = '0';
    ascii(header, 257, 6, "ustar");
    ascii(header, 263, 2, "00");
    long sum = 0;
    for (byte b : header) {
      sum += b & 0xff;
    }
    octal(header, 148, 8, sum);
    tar.write(header);
    tar.write(content);
    int padding = (int) ((512 - (content.length % 512)) % 512);
    tar.write(new byte[padding]);
    tar.write(new byte[1024]);

    ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(gzipped)) {
      gzip.write(tar.toByteArray());
    }
    return gzipped.toByteArray();
  }

  private static void ascii(byte[] target, int offset, int length, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(bytes, 0, target, offset, Math.min(bytes.length, length));
  }

  private static void octal(byte[] target, int offset, int length, long value) {
    String text = Long.toOctalString(value);
    int digits = length - 2;
    String padded = "0".repeat(Math.max(0, digits - text.length())) + text;
    ascii(target, offset, digits, padded);
    target[offset + length - 2] = 0;
    target[offset + length - 1] = (byte) ' ';
  }

  private record CompatConfig(Endpoint nexus, Endpoint nexusPlus, boolean writeEnabled, String readPackage) {
    static CompatConfig load() {
      Endpoint nexus = new Endpoint(
          "nexus",
          CompatDefaults.nexusBaseUrl(),
          setting("compat.nexus.npm.repository", "NEXUS_COMPAT_NPM_REPOSITORY").orElse("npm-hosted"),
          CompatDefaults.nexusUsername(),
          CompatDefaults.nexusPassword());
      Endpoint nexusPlus = new Endpoint(
          "kkrepo",
          CompatDefaults.nexusPlusBaseUrl(),
          setting("compat.nexusPlus.npm.repository", "KKREPO_COMPAT_NPM_REPOSITORY").orElse("npm-hosted"),
          CompatDefaults.nexusPlusUsername(),
          CompatDefaults.nexusPlusPassword());
      boolean writeEnabled = Boolean.parseBoolean(
          setting("compat.write.enabled", "COMPAT_WRITE_ENABLED").orElse("false"));
      String readPackage = setting("compat.npm.readPackage", "COMPAT_NPM_READ_PACKAGE").orElse("is-number");
      return new CompatConfig(nexus, nexusPlus, writeEnabled, readPackage);
    }

    boolean configured() {
      return nexus.baseUrl().isPresent() && nexusPlus.baseUrl().isPresent();
    }

    Endpoint nexusHosted() {
      return nexus.withRepository(setting("compat.nexus.npm.hostedRepository", "NEXUS_COMPAT_NPM_HOSTED_REPOSITORY")
          .orElse(nexus.repository()));
    }

    Endpoint nexusPlusHosted() {
      return nexusPlus.withRepository(
          setting("compat.nexusPlus.npm.hostedRepository", "KKREPO_COMPAT_NPM_HOSTED_REPOSITORY")
              .orElse(nexusPlus.repository()));
    }

    Endpoint nexusProxy() {
      return nexus.withRepository(setting("compat.nexus.npm.proxyRepository", "NEXUS_COMPAT_NPM_PROXY_REPOSITORY")
          .orElse("npm-proxy"));
    }

    Endpoint nexusPlusProxy() {
      return nexusPlus.withRepository(
          setting("compat.nexusPlus.npm.proxyRepository", "KKREPO_COMPAT_NPM_PROXY_REPOSITORY")
              .orElse("npm-proxy"));
    }

    Endpoint nexusGroup() {
      return nexus.withRepository(setting("compat.nexus.npm.groupRepository", "NEXUS_COMPAT_NPM_GROUP_REPOSITORY")
          .orElse("npm-group"));
    }

    Endpoint nexusPlusGroup() {
      return nexusPlus.withRepository(
          setting("compat.nexusPlus.npm.groupRepository", "KKREPO_COMPAT_NPM_GROUP_REPOSITORY")
              .orElse("npm-group"));
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

    HttpRequest.Builder internalBrowseRequest(String repositoryPath) {
      String query = repositoryPath == null || repositoryPath.isBlank()
          ? ""
          : "?path=" + URLEncoder.encode(repositoryPath, StandardCharsets.UTF_8);
      URI uri = URI.create(baseUrl.orElseThrow() + "/internal/browse/" + repository + query);
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

  private static Optional<String> setting(String property, String env) {
    String value = System.getProperty(property);
    if (value == null || value.isBlank()) {
      value = System.getenv(env);
    }
    return value == null || value.isBlank()
        ? Optional.empty()
        : Optional.of(value.trim());
  }

  private static int positiveIntSetting(String property, String env, int fallback) {
    return setting(property, env)
        .map(value -> {
          try {
            return Math.max(1, Integer.parseInt(value));
          } catch (NumberFormatException e) {
            return fallback;
          }
        })
        .orElse(fallback);
  }

  private static Optional<String> urlSetting(String property, String env) {
    return setting(property, env).map(NpmRepositoryBlackBoxCompatibilityTest::stripTrailingSlash);
  }

  private static String stripTrailingSlash(String value) {
    String result = value;
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }

  private static String hex(byte[] bytes) {
    StringBuilder builder = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      builder.append(String.format("%02x", b));
    }
    return builder.toString();
  }
}
