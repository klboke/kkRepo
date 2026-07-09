package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.klboke.kkrepo.protocol.helm.HelmIndex;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;

class HelmRepositoryBlackBoxCompatibilityTest {
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();

  @Test
  void hostedAndProxyRoundTripMatchNexusWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run Helm black-box compatibility");

    if (config.setupEnabled()) {
      ensureNexusRepositories(config);
      ensureKkRepoRepositories(config);
    }

    ChartFixture fixture = ChartFixture.create();
    try {
      Exchange referencePush = push(config.nexusHosted(), fixture);
      Exchange candidatePush = push(config.nexusPlusHosted(), fixture);
      assertSameStatus("hosted push", referencePush, candidatePush);
      assertEquals(201, referencePush.status(), "reference hosted push status");
      assertEquals(201, candidatePush.status(), "kkrepo hosted push status");

      assertIndexContains("hosted index",
          waitForIndexContains(config.nexusHosted(), fixture),
          waitForIndexContains(config.nexusPlusHosted(), fixture),
          fixture.chartName(),
          fixture.version(),
          fixture.fileName());
      assertChartMatches("hosted chart",
          get(config.nexusHosted(), fixture.fileName()),
          get(config.nexusPlusHosted(), fixture.fileName()),
          fixture.bytes());
      assertSameStatus("hosted HEAD",
          head(config.nexusHosted(), fixture.fileName()),
          head(config.nexusPlusHosted(), fixture.fileName()));

      ChartFixture putFixture = ChartFixture.create();
      Exchange referencePut = put(config.nexusHosted(), putFixture.fileName(), putFixture.bytes(), "application/gzip");
      Exchange candidatePut = put(config.nexusPlusHosted(), putFixture.fileName(), putFixture.bytes(), "application/gzip");
      assertSameStatus("hosted direct PUT chart", referencePut, candidatePut);
      assertEquals(200, referencePut.status(), "reference hosted direct PUT chart status");
      assertEquals(200, candidatePut.status(), "kkrepo hosted direct PUT chart status");
      assertIndexContains("hosted direct PUT index",
          waitForIndexContains(config.nexusHosted(), putFixture),
          waitForIndexContains(config.nexusPlusHosted(), putFixture),
          putFixture.chartName(),
          putFixture.version(),
          putFixture.fileName());
      assertChartMatches("hosted direct PUT chart",
          get(config.nexusHosted(), putFixture.fileName()),
          get(config.nexusPlusHosted(), putFixture.fileName()),
          putFixture.bytes());

      byte[] provenance = putFixture.provenanceBytes();
      Exchange referenceProvenancePut = put(config.nexusHosted(), putFixture.provenanceFileName(),
          provenance, "application/octet-stream");
      Exchange candidateProvenancePut = put(config.nexusPlusHosted(), putFixture.provenanceFileName(),
          provenance, "application/octet-stream");
      assertSameStatus("hosted direct PUT provenance", referenceProvenancePut, candidateProvenancePut);
      assertEquals(200, referenceProvenancePut.status(), "reference hosted direct PUT provenance status");
      assertEquals(200, candidateProvenancePut.status(), "kkrepo hosted direct PUT provenance status");
      assertChartMatches("hosted provenance",
          get(config.nexusHosted(), putFixture.provenanceFileName()),
          get(config.nexusPlusHosted(), putFixture.provenanceFileName()),
          provenance);
      Exchange referenceProvenanceDelete = delete(config.nexusHosted(), putFixture.provenanceFileName());
      Exchange candidateProvenanceDelete = delete(config.nexusPlusHosted(), putFixture.provenanceFileName());
      assertSameStatus("hosted provenance DELETE unsupported",
          referenceProvenanceDelete, candidateProvenanceDelete);
      assertEquals(404, referenceProvenanceDelete.status(), "reference hosted provenance DELETE status");
      assertEquals(404, candidateProvenanceDelete.status(), "kkrepo hosted provenance DELETE status");
      Exchange referenceDirectDelete = delete(config.nexusHosted(), putFixture.fileName());
      Exchange candidateDirectDelete = delete(config.nexusPlusHosted(), putFixture.fileName());
      assertSameStatus("hosted direct DELETE chart", referenceDirectDelete, candidateDirectDelete);
      assertEquals(200, referenceDirectDelete.status(), "reference hosted direct DELETE chart status");
      assertEquals(200, candidateDirectDelete.status(), "kkrepo hosted direct DELETE chart status");

      Exchange referenceProxyIndex = get(config.nexusProxy(), "index.yaml");
      Exchange candidateProxyIndex = get(config.nexusPlusProxy(), "index.yaml");
      assertSameStatus("proxy index", referenceProxyIndex, candidateProxyIndex);
      assert2xx("reference proxy index", referenceProxyIndex);
      assert2xx("candidate proxy index", candidateProxyIndex);
      String proxyChartPath = firstCommonChartPath(referenceProxyIndex, candidateProxyIndex);
      assertFalse(proxyChartPath.isBlank(), "proxy index should expose at least one common chart URL");
      Exchange referenceProxyChart = get(config.nexusProxy(), proxyChartPath);
      Exchange candidateProxyChart = get(config.nexusPlusProxy(), proxyChartPath);
      assertSameStatus("proxy chart", referenceProxyChart, candidateProxyChart);
      if (referenceProxyChart.status() == 200 && candidateProxyChart.status() == 200) {
        assertArrayEquals(referenceProxyChart.body(), candidateProxyChart.body(), "proxy chart body");
      }
    } finally {
      delete(config.nexusHosted(), fixture.fileName());
      delete(config.nexusPlusHosted(), fixture.fileName());
    }
  }

  @Test
  void hostedRoundTripMatchesNexusWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run Helm black-box compatibility");

    if (config.setupEnabled()) {
      ensureNexusRepositories(config);
      ensureKkRepoRepositories(config);
    }

    ChartFixture fixture = ChartFixture.create();
    try {
      Exchange referencePush = push(config.nexusHosted(), fixture);
      Exchange candidatePush = push(config.nexusPlusHosted(), fixture);
      assertSameStatus("hosted push", referencePush, candidatePush);
      assertEquals(201, referencePush.status(), "reference hosted push status");
      assertEquals(201, candidatePush.status(), "kkrepo hosted push status");

      assertIndexContains("hosted index",
          waitForIndexContains(config.nexusHosted(), fixture),
          waitForIndexContains(config.nexusPlusHosted(), fixture),
          fixture.chartName(),
          fixture.version(),
          fixture.fileName());
      assertChartMatches("hosted chart",
          get(config.nexusHosted(), fixture.fileName()),
          get(config.nexusPlusHosted(), fixture.fileName()),
          fixture.bytes());
      assertSameStatus("hosted HEAD",
          head(config.nexusHosted(), fixture.fileName()),
          head(config.nexusPlusHosted(), fixture.fileName()));
    } finally {
      delete(config.nexusHosted(), fixture.fileName());
      delete(config.nexusPlusHosted(), fixture.fileName());
    }
  }

  private static Exchange push(Endpoint endpoint, ChartFixture fixture) throws Exception {
    String boundary = "kkrepo-helm-compat-" + System.nanoTime();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
    out.write(("Content-Disposition: form-data; name=\"chart\"; filename=\"" + fixture.fileName() + "\"\r\n")
        .getBytes(StandardCharsets.UTF_8));
    out.write("Content-Type: application/gzip\r\n\r\n".getBytes(StandardCharsets.UTF_8));
    out.write(fixture.bytes());
    out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return send(endpoint.request("api/charts")
        .timeout(Duration.ofSeconds(90))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())));
  }

  private static void assertIndexContains(String label, Exchange reference, Exchange candidate,
      String chartName, String version, String fileName) {
    assertSameStatus(label, reference, candidate);
    assert2xx(label + " reference", reference);
    assert2xx(label + " candidate", candidate);
    Set<String> referenceUrls = chartUrls(reference, chartName, version);
    Set<String> candidateUrls = chartUrls(candidate, chartName, version);
    assertTrue(referenceUrls.contains(fileName), label + " reference should contain " + fileName);
    assertTrue(candidateUrls.contains(fileName), label + " candidate should contain " + fileName);
  }

  private static void assertChartMatches(String label, Exchange reference, Exchange candidate, byte[] expected) {
    assertSameStatus(label, reference, candidate);
    assert2xx(label + " reference", reference);
    assert2xx(label + " candidate", candidate);
    assertArrayEquals(expected, reference.body(), label + " reference body");
    assertArrayEquals(expected, candidate.body(), label + " candidate body");
  }

  private static String firstCommonChartPath(Exchange referenceIndex, Exchange candidateIndex) {
    Set<String> reference = chartUrls(referenceIndex, null, null);
    for (String candidate : chartUrls(candidateIndex, null, null)) {
      if (candidate.endsWith(".tgz") && reference.contains(candidate)) {
        return candidate;
      }
    }
    return "";
  }

  private static Set<String> chartUrls(Exchange index, String chartName, String version) {
    Set<String> urls = new LinkedHashSet<>();
    for (HelmIndex.Entry entry : HelmIndex.entries(index.body())) {
      if (chartName != null && !chartName.equals(entry.name())) continue;
      if (version != null && !version.equals(entry.version())) continue;
      entry.urls().stream()
          .map(HelmRepositoryBlackBoxCompatibilityTest::localPath)
          .forEach(urls::add);
    }
    return urls;
  }

  private static String localPath(String url) {
    String value = url == null ? "" : url;
    int query = value.indexOf('?');
    if (query >= 0) value = value.substring(0, query);
    int hash = value.indexOf('#');
    if (hash >= 0) value = value.substring(0, hash);
    while (value.startsWith("./")) value = value.substring(2);
    while (value.startsWith("../")) value = value.substring(3);
    if (value.startsWith("/")) value = value.substring(1);
    int slash = value.lastIndexOf('/');
    return slash < 0 ? value : value.substring(slash + 1);
  }

  private static Exchange get(Endpoint endpoint, String path) throws Exception {
    return send(endpoint.request(path).GET());
  }

  private static Exchange waitForIndexContains(Endpoint endpoint, ChartFixture fixture) throws Exception {
    Exchange last = null;
    for (int attempt = 0; attempt < 10; attempt++) {
      last = get(endpoint, "index.yaml");
      if (last.status() >= 200 && last.status() < 300
          && chartUrls(last, fixture.chartName(), fixture.version()).contains(fixture.fileName())) {
        return last;
      }
      Thread.sleep(500L);
    }
    return last == null ? get(endpoint, "index.yaml") : last;
  }

  private static Exchange head(Endpoint endpoint, String path) throws Exception {
    return send(endpoint.request(path).method("HEAD", HttpRequest.BodyPublishers.noBody()));
  }

  private static Exchange put(Endpoint endpoint, String path, byte[] body, String contentType) throws Exception {
    return send(endpoint.request(path)
        .header("Content-Type", contentType)
        .PUT(HttpRequest.BodyPublishers.ofByteArray(body)));
  }

  private static Exchange delete(Endpoint endpoint, String path) throws Exception {
    return send(endpoint.request(path).DELETE());
  }

  private static Exchange send(HttpRequest.Builder builder) throws Exception {
    HttpResponse<byte[]> response = HTTP.send(
        builder.header("User-Agent", "kkrepo-helm-compat-test/1")
            .timeout(Duration.ofSeconds(120))
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
    return new Exchange(response.statusCode(), response.body(),
        response.headers().firstValue("content-length"),
        response.headers().firstValue("content-type"));
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
    String repositories = send(config.nexusAdmin("/service/rest/v1/repositories").GET()).bodyAsString();
    if (!repositories.contains("\"name\" : \"" + config.hostedRepository() + "\"")) {
      assert2xx("create Nexus Helm hosted", send(config.nexusAdmin(
          "/service/rest/v1/repositories/helm/hosted")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("""
              {"name":"%s","online":true,"storage":{"blobStoreName":"default","strictContentTypeValidation":true,"writePolicy":"allow"}}
              """.formatted(config.hostedRepository())))));
    }
    repositories = send(config.nexusAdmin("/service/rest/v1/repositories").GET()).bodyAsString();
    if (!repositories.contains("\"name\" : \"" + config.proxyRepository() + "\"")) {
      assert2xx("create Nexus Helm proxy", send(config.nexusAdmin(
          "/service/rest/v1/repositories/helm/proxy")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("""
              {"name":"%s","online":true,"storage":{"blobStoreName":"default","strictContentTypeValidation":true},"proxy":{"remoteUrl":"%s","contentMaxAge":1440,"metadataMaxAge":1440},"negativeCache":{"enabled":true,"timeToLive":1440},"httpClient":{"blocked":false,"autoBlock":true}}
              """.formatted(config.proxyRepository(), config.remoteUrl())))));
    }
  }

  private static void ensureKkRepoRepositories(CompatConfig config) throws Exception {
    String repositories = send(config.nexusPlusInternal("/internal/repositories").GET()).bodyAsString();
    if (!repositories.contains("\"name\":\"" + config.hostedRepository() + "\"")) {
      assert2xx("create kkrepo Helm hosted", send(config.nexusPlusInternal("/internal/repositories")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("""
              {"name":"%s","recipe":"helm-hosted","online":true,"blobStoreName":"default","strictContentTypeValidation":true,"hosted":{"writePolicy":"ALLOW","versionPolicy":null,"layoutPolicy":null}}
              """.formatted(config.hostedRepository())))));
    }
    repositories = send(config.nexusPlusInternal("/internal/repositories").GET()).bodyAsString();
    if (!repositories.contains("\"name\":\"" + config.proxyRepository() + "\"")) {
      assert2xx("create kkrepo Helm proxy", send(config.nexusPlusInternal("/internal/repositories")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("""
              {"name":"%s","recipe":"helm-proxy","online":true,"blobStoreName":"default","strictContentTypeValidation":true,"proxy":{"remoteUrl":"%s","contentMaxAgeMinutes":1440,"metadataMaxAgeMinutes":1440,"autoBlock":true}}
              """.formatted(config.proxyRepository(), config.remoteUrl())))));
    }
  }

  private record Exchange(int status, byte[] body, Optional<String> contentLength, Optional<String> contentType) {
    String bodyAsString() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }

  private record ChartFixture(String chartName, String version, String fileName, byte[] bytes) {
    static ChartFixture create() throws Exception {
      String name = "kkrepo-compat-helm";
      String version = "0.1." + System.currentTimeMillis();
      String fileName = name + "-" + version + ".tgz";
      ByteArrayOutputStream gzipBytes = new ByteArrayOutputStream();
      try (GZIPOutputStream gzip = new GZIPOutputStream(gzipBytes);
           TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        putTarEntry(tar, name + "/Chart.yaml", """
            apiVersion: v2
            name: %s
            description: kkrepo Helm compatibility fixture
            version: %s
            appVersion: "1.0.0"
            """.formatted(name, version));
        putTarEntry(tar, name + "/templates/configmap.yaml", """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: kkrepo-compat-helm
            data:
              message: hello
            """);
      }
      return new ChartFixture(name, version, fileName, gzipBytes.toByteArray());
    }

    String provenanceFileName() {
      return fileName + ".prov";
    }

    byte[] provenanceBytes() {
      return """
          apiVersion: v2
          name: %s
          description: kkrepo Helm compatibility fixture
          version: %s
          appVersion: "1.0.0"
          -----BEGIN PGP SIGNATURE-----
          kkrepo-compat
          -----END PGP SIGNATURE-----
          """.formatted(chartName, version).getBytes(StandardCharsets.UTF_8);
    }

    private static void putTarEntry(TarArchiveOutputStream tar, String name, String body) throws Exception {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      TarArchiveEntry entry = new TarArchiveEntry(name);
      entry.setSize(bytes.length);
      tar.putArchiveEntry(entry);
      tar.write(bytes);
      tar.closeArchiveEntry();
    }
  }

  private record CompatConfig(
      Endpoint nexus,
      Endpoint nexusPlus,
      boolean setupEnabled,
      String hostedRepository,
      String proxyRepository,
      String remoteUrl) {
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
          setting("compat.helm.setup.enabled", "COMPAT_HELM_SETUP_ENABLED").orElse("true"));
      return new CompatConfig(
          nexus,
          nexusPlus,
          setup,
          setting("compat.helm.hostedRepository", "COMPAT_HELM_HOSTED_REPOSITORY").orElse("helm-hosted"),
          setting("compat.helm.proxyRepository", "COMPAT_HELM_PROXY_REPOSITORY").orElse("helm-proxy"),
          stripTrailingSlash(setting("compat.helm.remoteUrl", "COMPAT_HELM_REMOTE_URL")
              .orElse("https://helm.github.io/examples")));
    }

    boolean configured() {
      return nexus.baseUrl().isPresent() && nexusPlus.baseUrl().isPresent();
    }

    Endpoint nexusHosted() { return nexus.withRepository(hostedRepository); }
    Endpoint nexusProxy() { return nexus.withRepository(proxyRepository); }
    Endpoint nexusPlusHosted() { return nexusPlus.withRepository(hostedRepository); }
    Endpoint nexusPlusProxy() { return nexusPlus.withRepository(proxyRepository); }

    HttpRequest.Builder nexusAdmin(String path) {
      return nexus.raw(path);
    }

    HttpRequest.Builder nexusPlusInternal(String path) {
      return nexusPlus.raw(path);
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
    return setting(property, env).map(HelmRepositoryBlackBoxCompatibilityTest::stripTrailingSlash);
  }

  private static String stripTrailingSlash(String value) {
    String result = value;
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
