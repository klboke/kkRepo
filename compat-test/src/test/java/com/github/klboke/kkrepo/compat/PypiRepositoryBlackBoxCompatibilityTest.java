package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class PypiRepositoryBlackBoxCompatibilityTest {
  private static final Pattern LINK = Pattern.compile(
      "<a\\b([^>]*)>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern HREF = Pattern.compile(
      "\\bhref\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
      Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();
  private static final Duration INDEX_REBUILD_TIMEOUT = Duration.ofSeconds(20);

  @Test
  void hostedProxyAndGroupRoundTripMatchNexusWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run PyPI black-box compatibility");

    if (config.setupEnabled()) {
      ensureNexusRepositories(config);
      ensureKkRepoRepositories(config);
    }

    WheelFixture fixture = WheelFixture.create();
    Exchange referenceUpload = upload(config.nexusHosted(), fixture);
    Exchange candidateUpload = upload(config.nexusPlusHosted(), fixture);
    assertSameStatus("hosted upload", referenceUpload, candidateUpload);
    assert2xx("nexus hosted upload", referenceUpload);
    assert2xx("kkrepo hosted upload", candidateUpload);

    assertProjectIndexMatches("hosted index",
        getEventually(config.nexusHosted(), "simple/" + fixture.normalizedName() + "/", fixture.filename()),
        getEventually(config.nexusPlusHosted(), "simple/" + fixture.normalizedName() + "/", fixture.filename()),
        fixture.filename());
    assertRootContains("hosted root",
        getEventually(config.nexusHosted(), "simple/", fixture.normalizedName()),
        getEventually(config.nexusPlusHosted(), "simple/", fixture.normalizedName()),
        fixture.normalizedName());
    assertPackageMatches("hosted package",
        get(config.nexusHosted(), fixture.packagePath()),
        get(config.nexusPlusHosted(), fixture.packagePath()),
        fixture.bytes());

    assertProjectIndexMatches("group index",
        getEventually(config.nexusGroup(), "simple/" + fixture.normalizedName() + "/", fixture.filename()),
        getEventually(config.nexusPlusGroup(), "simple/" + fixture.normalizedName() + "/", fixture.filename()),
        fixture.filename());
    assertPackageMatches("group package",
        get(config.nexusGroup(), fixture.packagePath()),
        get(config.nexusPlusGroup(), fixture.packagePath()),
        fixture.bytes());

    Exchange referenceProxyIndex = get(config.nexusProxy(), "simple/sampleproject/");
    Exchange candidateProxyIndex = get(config.nexusPlusProxy(), "simple/sampleproject/");
    assertProjectIndexMatches("proxy index", referenceProxyIndex, candidateProxyIndex, null);
    String proxyPackagePath = firstPackagePath(candidateProxyIndex);
    assertFalse(proxyPackagePath.isBlank(), "proxy index should expose at least one package link");
    Exchange referenceProxyPackage = get(config.nexusProxy(), proxyPackagePath);
    Exchange candidateProxyPackage = get(config.nexusPlusProxy(), proxyPackagePath);
    assertSameStatus("proxy package", referenceProxyPackage, candidateProxyPackage);
    if (referenceProxyPackage.status() == 200 && candidateProxyPackage.status() == 200) {
      assertArrayEquals(referenceProxyPackage.body(), candidateProxyPackage.body(), "proxy package body");
    }

    assertPypiBrowseRoot(config, config.proxyRepository(), "sampleproject");
    assertPypiBrowseRoot(config, config.groupRepository(), fixture.normalizedName());
    assertPypiBrowseHtmlRoot(config, config.groupRepository(), fixture.normalizedName());
    Exchange groupSimpleBrowse = send(config.nexusPlusInternal(
        "/internal/browse/" + config.groupRepository() + "?path=simple").GET());
    assert2xx("kkrepo group simple browse", groupSimpleBrowse);
    assertTrue(groupSimpleBrowse.bodyAsString().contains("\"entries\":[{"),
        "group simple directory should expose project index children");
  }

  private static Exchange upload(Endpoint endpoint, WheelFixture fixture) throws Exception {
    String boundary = "kkrepo-pypi-compat-" + System.nanoTime();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    formField(out, boundary, ":action", "file_upload");
    formField(out, boundary, "protocol_version", "1");
    formField(out, boundary, "metadata_version", "2.1");
    formField(out, boundary, "name", fixture.name());
    formField(out, boundary, "version", fixture.version());
    formField(out, boundary, "summary", "kkrepo PyPI compatibility fixture");
    formField(out, boundary, "requires_python", ">=3.8");
    formField(out, boundary, "md5_digest", hex(MessageDigest.getInstance("MD5").digest(fixture.bytes())));
    fileField(out, boundary, "content", fixture.filename(), "application/octet-stream", fixture.bytes());
    out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

    HttpRequest.Builder builder = endpoint.request("")
        .timeout(Duration.ofSeconds(90))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
    return send(builder);
  }

  private static void formField(ByteArrayOutputStream out, String boundary, String name, String value) throws Exception {
    out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
    out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    out.write(value.getBytes(StandardCharsets.UTF_8));
    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
  }

  private static void fileField(ByteArrayOutputStream out, String boundary, String name,
      String filename, String contentType, byte[] body) throws Exception {
    out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
    out.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n")
        .getBytes(StandardCharsets.UTF_8));
    out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    out.write(body);
    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
  }

  private static void assertProjectIndexMatches(
      String label, Exchange reference, Exchange candidate, String expectedFile) {
    assertSameStatus(label, reference, candidate);
    assert2xx(label + " reference", reference);
    assert2xx(label + " candidate", candidate);
    Map<String, String> referenceLinks = linkMap(reference.body());
    Map<String, String> candidateLinks = linkMap(candidate.body());
    assertEquals(referenceLinks, candidateLinks, label + " links");
    if (expectedFile != null) {
      assertTrue(candidateLinks.containsKey(expectedFile), label + " should contain uploaded file");
    }
  }

  private static void assertRootContains(String label, Exchange reference, Exchange candidate, String project) {
    assertSameStatus(label, reference, candidate);
    assert2xx(label + " reference", reference);
    assert2xx(label + " candidate", candidate);
    assertTrue(linkMap(reference.body()).containsKey(project), label + " reference should contain project");
    assertTrue(linkMap(candidate.body()).containsKey(project), label + " candidate should contain project");
  }

  private static void assertPackageMatches(
      String label, Exchange reference, Exchange candidate, byte[] expectedBody) {
    assertSameStatus(label, reference, candidate);
    assert2xx(label + " reference", reference);
    assert2xx(label + " candidate", candidate);
    assertArrayEquals(expectedBody, reference.body(), label + " reference body");
    assertArrayEquals(expectedBody, candidate.body(), label + " candidate body");
  }

  private static String firstPackagePath(Exchange index) {
    return linkMap(index.body()).values().stream()
        .map(PypiRepositoryBlackBoxCompatibilityTest::localPath)
        .filter(path -> path.startsWith("packages/"))
        .findFirst()
        .orElse("");
  }

  private static Map<String, String> linkMap(byte[] htmlBytes) {
    String html = new String(htmlBytes, StandardCharsets.UTF_8);
    Map<String, String> links = new LinkedHashMap<>();
    Matcher matcher = LINK.matcher(html);
    while (matcher.find()) {
      String href = attr(matcher.group(1), HREF);
      if (href == null) continue;
      String file = stripTags(matcher.group(2)).trim();
      links.put(unescape(file), localPath(unescape(href)));
    }
    return links;
  }

  private static String localPath(String href) {
    String result = href == null ? "" : href;
    int hash = result.indexOf('#');
    if (hash >= 0) result = result.substring(0, hash);
    int query = result.indexOf('?');
    if (query >= 0) result = result.substring(0, query);
    while (result.startsWith("../")) result = result.substring(3);
    while (result.startsWith("./")) result = result.substring(2);
    if (result.startsWith("/")) result = result.substring(1);
    return result;
  }

  private static String attr(String attrs, Pattern pattern) {
    Matcher matcher = pattern.matcher(attrs == null ? "" : attrs);
    if (!matcher.find()) return null;
    for (int i = 2; i <= matcher.groupCount(); i++) {
      if (matcher.group(i) != null) return matcher.group(i);
    }
    return "";
  }

  private static String stripTags(String html) {
    return (html == null ? "" : html).replaceAll("<[^>]*>", "");
  }

  private static String unescape(String value) {
    if (value == null) return "";
    return value
        .replace("&quot;", "\"")
        .replace("&#34;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&");
  }

  private static Exchange get(Endpoint endpoint, String path) throws Exception {
    return send(endpoint.request(path).GET());
  }

  private static Exchange getEventually(Endpoint endpoint, String path, String expectedLink) throws Exception {
    long deadline = System.nanoTime() + INDEX_REBUILD_TIMEOUT.toNanos();
    Exchange last;
    do {
      last = get(endpoint, path);
      if (last.status() >= 200 && last.status() < 300 && linkMap(last.body()).containsKey(expectedLink)) {
        return last;
      }
      Thread.sleep(250);
    } while (System.nanoTime() < deadline);
    return last;
  }

  private static Exchange send(HttpRequest.Builder builder) throws Exception {
    HttpResponse<byte[]> response = HTTP.send(
        builder.header("User-Agent", "kkrepo-compat-test/1").build(),
        HttpResponse.BodyHandlers.ofByteArray());
    return new Exchange(response.statusCode(), response.body());
  }

  private static void assertSameStatus(String label, Exchange reference, Exchange candidate) {
    assertEquals(reference.status(), candidate.status(), label + " status");
  }

  private static void assert2xx(String label, Exchange exchange) {
    assertTrue(exchange.status() >= 200 && exchange.status() < 300,
        label + " expected 2xx but got " + exchange.status()
            + " body=" + new String(exchange.body(), StandardCharsets.UTF_8));
  }

  private static void ensureNexusRepositories(CompatConfig config) throws Exception {
    String repositories = send(config.nexusAdmin("/service/rest/v1/repositories").GET())
        .bodyAsString();
    if (!repositories.contains("\"name\" : \"" + config.hostedRepository() + "\"")) {
      assert2xx("create Nexus PyPI hosted", send(config.nexusAdmin(
          "/service/rest/v1/repositories/pypi/hosted")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("""
              {"name":"%s","online":true,"storage":{"blobStoreName":"default","strictContentTypeValidation":true,"writePolicy":"allow"}}
              """.formatted(config.hostedRepository())))));
    }
    repositories = send(config.nexusAdmin("/service/rest/v1/repositories").GET()).bodyAsString();
    if (!repositories.contains("\"name\" : \"" + config.proxyRepository() + "\"")) {
      assert2xx("create Nexus PyPI proxy", send(config.nexusAdmin(
          "/service/rest/v1/repositories/pypi/proxy")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("""
              {"name":"%s","online":true,"storage":{"blobStoreName":"default","strictContentTypeValidation":true},"proxy":{"remoteUrl":"https://pypi.org/","contentMaxAge":1440,"metadataMaxAge":1440},"negativeCache":{"enabled":true,"timeToLive":1440},"httpClient":{"blocked":false,"autoBlock":true}}
              """.formatted(config.proxyRepository())))));
    }
    repositories = send(config.nexusAdmin("/service/rest/v1/repositories").GET()).bodyAsString();
    if (!repositories.contains("\"name\" : \"" + config.groupRepository() + "\"")) {
      assert2xx("create Nexus PyPI group", send(config.nexusAdmin(
          "/service/rest/v1/repositories/pypi/group")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("""
              {"name":"%s","online":true,"storage":{"blobStoreName":"default","strictContentTypeValidation":true},"group":{"memberNames":["%s","%s"]}}
              """.formatted(config.groupRepository(), config.hostedRepository(), config.proxyRepository())))));
    }
  }

  private static void assertPypiBrowseRoot(CompatConfig config, String repository, String packageName) throws Exception {
    Exchange browse = sendInternalBrowseEventually(config, repository, packageName);
    assert2xx("kkrepo browse " + repository, browse);
    String body = browse.bodyAsString();
    Pattern entry = Pattern.compile("\\{\"name\":\"" + Pattern.quote(packageName)
        + "\",\"path\":\"" + Pattern.quote(packageName) + "\"[^}]*\"leaf\":false");
    assertTrue(entry.matcher(body).find(),
        repository + " browse root should expose " + packageName + " without the packages prefix: " + body);
    assertFalse(body.contains("\"name\":\"packages\""),
        repository + " browse root should not expose PyPI storage prefix packages: " + body);
    assertFalse(body.contains("\"name\":\"simple\""),
        repository + " browse root should match Nexus and not expose simple in the root tree: " + body);
  }

  private static void assertPypiBrowseHtmlRoot(
      CompatConfig config,
      String repository,
      String packageName) throws Exception {
    Exchange reference = send(config.nexusBrowse(repository, "").GET());
    Exchange candidate = sendBrowseHtmlEventually(config, repository, packageName);
    assert2xx("Nexus browse html " + repository, reference);
    assert2xx("kkrepo browse html " + repository, candidate);
    Map<String, String> candidateLinks = linkMap(candidate.body());
    assertTrue(candidateLinks.containsKey(packageName),
        "kkrepo browse root should contain uploaded package " + packageName);
    assertFalse(candidateLinks.containsKey("packages"),
        "kkrepo browse root should not expose packages prefix");
    assertFalse(candidateLinks.containsKey("simple"),
        "kkrepo browse root should not expose simple in the root tree");
  }

  private static Exchange sendInternalBrowseEventually(
      CompatConfig config,
      String repository,
      String packageName) throws Exception {
    long deadline = System.nanoTime() + INDEX_REBUILD_TIMEOUT.toNanos();
    Exchange last;
    do {
      last = send(config.nexusPlusInternal("/internal/browse/" + repository).GET());
      if (last.status() >= 200 && last.status() < 300 && last.bodyAsString().contains("\"name\":\"" + packageName)) {
        return last;
      }
      Thread.sleep(250);
    } while (System.nanoTime() < deadline);
    return last;
  }

  private static Exchange sendBrowseHtmlEventually(
      CompatConfig config,
      String repository,
      String packageName) throws Exception {
    long deadline = System.nanoTime() + INDEX_REBUILD_TIMEOUT.toNanos();
    Exchange last;
    do {
      last = send(config.nexusPlusBrowse(repository, "").GET());
      if (last.status() >= 200 && last.status() < 300 && linkMap(last.body()).containsKey(packageName)) {
        return last;
      }
      Thread.sleep(250);
    } while (System.nanoTime() < deadline);
    return last;
  }

  private static void ensureKkRepoRepositories(CompatConfig config) throws Exception {
    String repositories = send(config.nexusPlusInternal("/internal/repositories").GET()).bodyAsString();
    if (!repositories.contains("\"name\":\"" + config.hostedRepository() + "\"")) {
      assert2xx("create kkrepo PyPI hosted", send(config.nexusPlusInternal("/internal/repositories")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("""
              {"name":"%s","recipe":"pypi-hosted","online":true,"blobStoreName":"default","strictContentTypeValidation":true,"hosted":{"writePolicy":"ALLOW","versionPolicy":null,"layoutPolicy":null}}
              """.formatted(config.hostedRepository())))));
    }
    repositories = send(config.nexusPlusInternal("/internal/repositories").GET()).bodyAsString();
    if (!repositories.contains("\"name\":\"" + config.proxyRepository() + "\"")) {
      assert2xx("create kkrepo PyPI proxy", send(config.nexusPlusInternal("/internal/repositories")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("""
              {"name":"%s","recipe":"pypi-proxy","online":true,"blobStoreName":"default","strictContentTypeValidation":true,"proxy":{"remoteUrl":"https://pypi.org/","contentMaxAgeMinutes":1440,"metadataMaxAgeMinutes":1440,"autoBlock":true}}
              """.formatted(config.proxyRepository())))));
    }
    String groupPayload = """
        {"name":"%s","recipe":"pypi-group","online":true,"strictContentTypeValidation":true,"group":{"memberNames":["%s","%s"]}}
        """.formatted(config.groupRepository(), config.hostedRepository(), config.proxyRepository());
    repositories = send(config.nexusPlusInternal("/internal/repositories").GET()).bodyAsString();
    if (!repositories.contains("\"name\":\"" + config.groupRepository() + "\"")) {
      assert2xx("create kkrepo PyPI group", send(config.nexusPlusInternal("/internal/repositories")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(groupPayload))));
    } else {
      assert2xx("update kkrepo PyPI group", send(config.nexusPlusInternal(
          "/internal/repositories/" + config.groupRepository())
          .header("Content-Type", "application/json")
          .PUT(HttpRequest.BodyPublishers.ofString(groupPayload))));
    }
  }

  private static String hex(byte[] bytes) {
    char[] chars = new char[bytes.length * 2];
    char[] alphabet = "0123456789abcdef".toCharArray();
    for (int i = 0; i < bytes.length; i++) {
      int v = bytes[i] & 0xff;
      chars[i * 2] = alphabet[v >>> 4];
      chars[i * 2 + 1] = alphabet[v & 0x0f];
    }
    return new String(chars);
  }

  private record Exchange(int status, byte[] body) {
    String bodyAsString() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }

  private record WheelFixture(String name, String normalizedName, String version, String filename, byte[] bytes) {
    static WheelFixture create() throws Exception {
      String name = "kkrepo-compat-pypi-" + System.currentTimeMillis();
      String normalized = normalizeName(name);
      String version = "0.1." + System.currentTimeMillis();
      String distribution = normalized.replace('-', '_');
      String filename = distribution + "-" + version + "-py3-none-any.whl";
      String distInfo = distribution + "-" + version + ".dist-info/";
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
        zip(zip, distInfo + "METADATA", """
            Metadata-Version: 2.1
            Name: %s
            Version: %s
            Summary: kkrepo PyPI compatibility fixture
            Requires-Python: >=3.8

            """.formatted(name, version));
        zip(zip, distInfo + "WHEEL", """
            Wheel-Version: 1.0
            Generator: kkrepo-compat-test
            Root-Is-Purelib: true
            Tag: py3-none-any

            """);
        zip(zip, distInfo + "RECORD", "");
      }
      return new WheelFixture(name, normalized, version, filename, out.toByteArray());
    }

    String packagePath() {
      return "packages/" + normalizedName + "/" + version + "/" + filename;
    }
  }

  private static void zip(ZipOutputStream zip, String name, String body) throws Exception {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(body.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private record CompatConfig(
      Endpoint nexus,
      Endpoint nexusPlus,
      boolean setupEnabled,
      String hostedRepository,
      String proxyRepository,
      String groupRepository) {
    static CompatConfig load() {
      Endpoint nexus = new Endpoint(
          "nexus",
          CompatDefaults.nexusBaseUrl(),
          CompatDefaults.nexusUsername(),
          CompatDefaults.nexusPassword(),
          "");
      Endpoint nexusPlus = new Endpoint(
          "kkrepo",
          CompatDefaults.nexusPlusBaseUrl(),
          CompatDefaults.nexusPlusUsername(),
          CompatDefaults.nexusPlusPassword(),
          "");
      boolean setup = Boolean.parseBoolean(
          setting("compat.pypi.setup.enabled", "COMPAT_PYPI_SETUP_ENABLED").orElse("true"));
      return new CompatConfig(
          nexus,
          nexusPlus,
          setup,
          setting("compat.pypi.hostedRepository", "COMPAT_PYPI_HOSTED_REPOSITORY").orElse("pypi-hosted"),
          setting("compat.pypi.proxyRepository", "COMPAT_PYPI_PROXY_REPOSITORY").orElse("pypi-proxy"),
          setting("compat.pypi.groupRepository", "COMPAT_PYPI_GROUP_REPOSITORY").orElse("pypi-group"));
    }

    boolean configured() {
      return nexus.baseUrl().isPresent() && nexusPlus.baseUrl().isPresent();
    }

    Endpoint nexusHosted() { return nexus.withRepository(hostedRepository); }
    Endpoint nexusProxy() { return nexus.withRepository(proxyRepository); }
    Endpoint nexusGroup() { return nexus.withRepository(groupRepository); }
    Endpoint nexusPlusHosted() { return nexusPlus.withRepository(hostedRepository); }
    Endpoint nexusPlusProxy() { return nexusPlus.withRepository(proxyRepository); }
    Endpoint nexusPlusGroup() { return nexusPlus.withRepository(groupRepository); }

    HttpRequest.Builder nexusAdmin(String path) {
      return nexus.raw(path);
    }

    HttpRequest.Builder nexusPlusInternal(String path) {
      return nexusPlus.raw(path);
    }

    HttpRequest.Builder nexusBrowse(String repository, String path) {
      return nexus.raw(browsePath(repository, path));
    }

    HttpRequest.Builder nexusPlusBrowse(String repository, String path) {
      return nexusPlus.raw(browsePath(repository, path));
    }

    private static String browsePath(String repository, String path) {
      String suffix = path == null || path.isBlank() ? "" : stripLeadingSlash(path);
      return "/service/rest/repository/browse/" + repository + "/" + suffix;
    }

    private static String stripLeadingSlash(String path) {
      String result = path;
      while (result.startsWith("/")) result = result.substring(1);
      return result;
    }
  }

  private record Endpoint(
      String name,
      Optional<String> baseUrl,
      Optional<String> username,
      Optional<String> password,
      String repository) {
    HttpRequest.Builder request(String repositoryPath) {
      String suffix = repositoryPath == null || repositoryPath.isBlank()
          ? ""
          : repositoryPath;
      return raw("/repository/" + repository + "/" + suffix);
    }

    HttpRequest.Builder raw(String path) {
      URI uri = URI.create(baseUrl.orElseThrow() + path);
      HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
      if (username.isPresent() && password.isPresent()) {
        String token = Base64.getEncoder().encodeToString(
            (username.get() + ":" + password.get()).getBytes(StandardCharsets.UTF_8));
        builder.header("Authorization", "Basic " + token);
      }
      return builder;
    }

    Endpoint withRepository(String repository) {
      return new Endpoint(name, baseUrl, username, password, repository);
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

  private static Optional<String> urlSetting(String property, String env) {
    return setting(property, env).map(PypiRepositoryBlackBoxCompatibilityTest::stripTrailingSlash);
  }

  private static String stripTrailingSlash(String value) {
    String result = value;
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }

  private static String normalizeName(String value) {
    return value.replaceAll("[-_.]+", "-").toLowerCase(Locale.ENGLISH);
  }
}
