package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Live black-box repository-format checks for Nexus formats that are not covered by the
 * security-admin compatibility suite. These tests compare the fixed Nexus 3.29 reference endpoint
 * with the local dev server using the same admin credentials.
 */
class NugetRubygemsYumRepositoryBlackBoxCompatibilityTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final String DEFAULT_YUM_FIXTURE_URL =
      "https://dl.fedoraproject.org/pub/epel/9/Everything/x86_64/Packages/6/6tunnel-0.13-1.el9.x86_64.rpm";
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();

  @Test
  void nugetHostedServiceIndexAndProxyReadsMatchNexusWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeLiveEndpoints(config);

    if (config.setupEnabled()) {
      ensureNugetRepositories(config);
    }

    assertNugetServiceIndexShape("hosted service index",
        get(config.nexus.repository(config.nugetHostedRepository(), "index.json")),
        get(config.nexusPlus.repository(config.nugetHostedRepository(), "index.json")),
        true);
    assertNugetProxyRead(config, config.nugetProxyRepository(), config.nugetProxyRepository());
    assertNugetProxyRead(config, config.nugetGroupRepository(), config.nugetGroupRepository());
  }

  @Test
  void nugetHostedMultipartPushMatchesNexusWhenWriteEnabled() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeLiveEndpoints(config);
    assumeTrue(config.writeEnabled(),
        "Set COMPAT_WRITE_ENABLED=true to run NuGet hosted multipart push compatibility");

    if (config.setupEnabled()) {
      ensureNugetRepositories(config);
    }

    NugetFixture fixture = NugetFixture.create();
    Exchange referencePut = putMultipart(config.nexus.repository(config.nugetHostedRepository(), ""), fixture);
    Exchange candidatePut = putMultipart(config.nexusPlus.repository(config.nugetHostedRepository(), ""), fixture);
    assertSameStatus("NuGet hosted multipart PUT", referencePut, candidatePut);
    assertEquals(201, referencePut.status(), "reference NuGet hosted multipart PUT status");
    assertEquals(201, candidatePut.status(), "kkrepo NuGet hosted multipart PUT status");

    String referenceBase = nugetResourceBase(getJson(
        config.nexus.repository(config.nugetHostedRepository(), "index.json")), "PackageBaseAddress");
    String candidateBase = nugetResourceBase(getJson(
        config.nexusPlus.repository(config.nugetHostedRepository(), "index.json")), "PackageBaseAddress");
    String versionIndexSuffix = fixture.normalizedId() + "/index.json";
    Map<String, Object> referenceVersions = getJson(config.nexus.absolute(appendPath(referenceBase, versionIndexSuffix)));
    Map<String, Object> candidateVersions = getJson(config.nexusPlus.absolute(appendPath(candidateBase, versionIndexSuffix)));
    assertVersionsContain("reference NuGet hosted version index", referenceVersions, fixture.version());
    assertVersionsContain("kkrepo NuGet hosted version index", candidateVersions, fixture.version());

    String packageSuffix = fixture.normalizedId() + "/" + fixture.normalizedVersion()
        + "/" + fixture.normalizedId() + "." + fixture.normalizedVersion() + ".nupkg";
    assertPackageHeadMatches("NuGet hosted package HEAD",
        head(config.nexus.absolute(appendPath(referenceBase, packageSuffix))),
        head(config.nexusPlus.absolute(appendPath(candidateBase, packageSuffix))));
  }

  @Test
  void rubygemsHostedPushAndGroupReadMatchNexusWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeLiveEndpoints(config);

    if (config.setupEnabled()) {
      ensureRubygemsRepositories(config);
    }

    GemFixture fixture = GemFixture.create();
    Exchange referencePush = post(config.nexus.repository(config.rubygemsHostedRepository(), "api/v1/gems"),
        fixture.bytes(), "application/octet-stream");
    Exchange candidatePush = post(config.nexusPlus.repository(config.rubygemsHostedRepository(), "api/v1/gems"),
        fixture.bytes(), "application/octet-stream");
    assertSameStatus("RubyGems hosted push", referencePush, candidatePush);
    assertEquals(201, referencePush.status(), "reference RubyGems hosted push status");
    assertEquals(201, candidatePush.status(), "kkrepo RubyGems hosted push status");

    assertBodyMatches("RubyGems hosted gem",
        get(config.nexus.repository(config.rubygemsHostedRepository(), fixture.gemPath())),
        get(config.nexusPlus.repository(config.rubygemsHostedRepository(), fixture.gemPath())),
        fixture.bytes());
    assertBodyMatches("RubyGems group gem",
        get(config.nexus.repository(config.rubygemsGroupRepository(), fixture.gemPath())),
        get(config.nexusPlus.repository(config.rubygemsGroupRepository(), fixture.gemPath())),
        fixture.bytes());

    assertRubySpecsContain("RubyGems hosted specs",
        waitForRubySpecs(config.nexus, config.rubygemsHostedRepository(), fixture),
        waitForRubySpecs(config.nexusPlus, config.rubygemsHostedRepository(), fixture),
        fixture);
    assertRubySpecsContain("RubyGems group specs",
        waitForRubySpecs(config.nexus, config.rubygemsGroupRepository(), fixture),
        waitForRubySpecs(config.nexusPlus, config.rubygemsGroupRepository(), fixture),
        fixture);
    assertRubyCompactInfoContainsChecksum("RubyGems hosted compact info",
        get(config.nexusPlus.repository(config.rubygemsHostedRepository(), fixture.infoPath())),
        fixture);
    assertRubyCompactInfoContainsChecksum("RubyGems group compact info",
        get(config.nexusPlus.repository(config.rubygemsGroupRepository(), fixture.infoPath())),
        fixture);
    assertRubyDependenciesMatch("RubyGems hosted dependency API",
        get(config.nexus.repository(config.rubygemsHostedRepository(), fixture.dependenciesPath())),
        get(config.nexusPlus.repository(config.rubygemsHostedRepository(), fixture.dependenciesPath())),
        fixture);
    assertRubyDependenciesMatch("RubyGems group dependency API",
        get(config.nexus.repository(config.rubygemsGroupRepository(), fixture.dependenciesPath())),
        get(config.nexusPlus.repository(config.rubygemsGroupRepository(), fixture.dependenciesPath())),
        fixture);

    assertRubyQuickSpecContains("RubyGems quick spec",
        get(config.nexus.repository(config.rubygemsHostedRepository(), fixture.quickSpecPath())),
        get(config.nexusPlus.repository(config.rubygemsHostedRepository(), fixture.quickSpecPath())),
        fixture);
    assertRubyQuickSpecContains("RubyGems group quick spec",
        get(config.nexus.repository(config.rubygemsGroupRepository(), fixture.quickSpecPath())),
        get(config.nexusPlus.repository(config.rubygemsGroupRepository(), fixture.quickSpecPath())),
        fixture);
  }

  @Test
  void yumHostedRootAndMissingPackageResponsesMatchNexusWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeLiveEndpoints(config);

    if (config.setupEnabled()) {
      ensureYumRepositories(config);
    }

    Exchange referenceRoot = get(config.nexus.repository(config.yumHostedRepository(), ""));
    Exchange candidateRoot = get(config.nexusPlus.repository(config.yumHostedRepository(), ""));
    assertSameStatus("Yum hosted root", referenceRoot, candidateRoot);
    assertEquals(200, candidateRoot.status(), "kkrepo Yum hosted root status");
    assertContentTypeMatches(referenceRoot, candidateRoot, "Yum hosted root content type");

    assertSameStatus("Yum hosted missing package HEAD",
        head(config.nexus.repository(config.yumHostedRepository(), "packages/not-present-0.0.1-1.noarch.rpm")),
        head(config.nexusPlus.repository(config.yumHostedRepository(), "packages/not-present-0.0.1-1.noarch.rpm")));
  }

  @Test
  void yumHostedRpmPutMatchesNexusWhenWriteEnabled() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeLiveEndpoints(config);
    assumeTrue(config.writeEnabled(),
        "Set COMPAT_WRITE_ENABLED=true to run Yum hosted RPM PUT compatibility");

    if (config.setupEnabled()) {
      ensureYumRepositories(config);
    }

    byte[] rpm = download(config.yumFixtureUrl());
    String rpmFileName = fileName(config.yumFixtureUrl());
    String path = "Packages/kkrepo-compat-yum-" + System.currentTimeMillis() + "/" + rpmFileName;

    Exchange referencePut = put(config.nexus.repository(config.yumHostedRepository(), path),
        rpm, "application/x-rpm");
    Exchange candidatePut = put(config.nexusPlus.repository(config.yumHostedRepository(), path),
        rpm, "application/x-rpm");
    assertSameStatus("Yum hosted RPM PUT", referencePut, candidatePut);
    assertEquals(200, referencePut.status(), "reference Yum hosted RPM PUT status");
    assertEquals(200, candidatePut.status(), "kkrepo Yum hosted RPM PUT status");

    assertPackageHeadMatches("Yum hosted RPM HEAD",
        head(config.nexus.repository(config.yumHostedRepository(), path)),
        head(config.nexusPlus.repository(config.yumHostedRepository(), path)));
    assertBodyMatches("Yum hosted RPM GET",
        get(config.nexus.repository(config.yumHostedRepository(), path)),
        get(config.nexusPlus.repository(config.yumHostedRepository(), path)),
        rpm);
  }

  private static void assumeLiveEndpoints(CompatConfig config) {
    assumeTrue(config.referenceReachable(),
        "Reference Nexus is not reachable; start the fixed Nexus endpoint or override NEXUS_COMPAT_BASE_URL");
    assumeTrue(config.candidateReachable(),
        "kkrepo dev is not reachable; start it with scripts/dev.sh or override KKREPO_COMPAT_BASE_URL");
  }

  private static void assertNugetProxyRead(
      CompatConfig config,
      String referenceRepository,
      String candidateRepository) throws Exception {
    Map<String, Object> referenceIndex = getJson(config.nexus.repository(referenceRepository, "index.json"));
    Map<String, Object> candidateIndex = getJson(config.nexusPlus.repository(candidateRepository, "index.json"));
    assertNugetServiceIndexShape(referenceRepository + " service index", referenceIndex, candidateIndex, false);

    String referencePackageBase = nugetResourceBase(referenceIndex, "PackageBaseAddress");
    String candidatePackageBase = nugetResourceBase(candidateIndex, "PackageBaseAddress");
    String versionIndexSuffix = config.nugetReadPackageLower() + "/index.json";
    Map<String, Object> referenceVersions = getJson(config.nexus.absolute(
        appendPath(referencePackageBase, versionIndexSuffix)));
    Map<String, Object> candidateVersions = getJson(config.nexusPlus.absolute(
        appendPath(candidatePackageBase, versionIndexSuffix)));
    assertVersionsContain("reference NuGet version index", referenceVersions, config.nugetReadVersion());
    assertVersionsContain("kkrepo NuGet version index", candidateVersions, config.nugetReadVersion());

    String packageSuffix = config.nugetReadPackageLower() + "/" + config.nugetReadVersionLower()
        + "/" + config.nugetReadPackageLower() + "." + config.nugetReadVersionLower() + ".nupkg";
    assertPackageHeadMatches("NuGet proxy package HEAD",
        head(config.nexus.absolute(appendPath(referencePackageBase, packageSuffix))),
        head(config.nexusPlus.absolute(appendPath(candidatePackageBase, packageSuffix))));
  }

  private static void assertNugetServiceIndexShape(
      String label, Exchange reference, Exchange candidate, boolean requirePublish)
      throws Exception {
    assertSameStatus(label, reference, candidate);
    assert2xx(label + " reference", reference);
    assert2xx(label + " candidate", candidate);
    assertNugetServiceIndexShape(label,
        MAPPER.readValue(reference.body(), MAP_TYPE),
        MAPPER.readValue(candidate.body(), MAP_TYPE),
        requirePublish);
  }

  private static void assertNugetServiceIndexShape(
      String label, Map<String, Object> reference, Map<String, Object> candidate, boolean requirePublish) {
    assertEquals(reference.get("version"), candidate.get("version"), label + " version");
    Set<String> required = requirePublish
        ? Set.of("PackageBaseAddress", "SearchQueryService", "RegistrationsBaseUrl", "PackagePublish")
        : Set.of("PackageBaseAddress", "SearchQueryService", "RegistrationsBaseUrl");
    for (String type : required) {
      assertTrue(nugetResourceTypes(reference).stream().anyMatch(value -> value.startsWith(type)),
          label + " reference missing " + type);
      assertTrue(nugetResourceTypes(candidate).stream().anyMatch(value -> value.startsWith(type)),
          label + " candidate missing " + type);
    }
  }

  @SuppressWarnings("unchecked")
  private static List<String> nugetResourceTypes(Map<String, Object> index) {
    Object raw = index.get("resources");
    assertTrue(raw instanceof List<?>, "NuGet service index resources must be a list");
    return ((List<Object>) raw).stream()
        .filter(Map.class::isInstance)
        .map(Map.class::cast)
        .map(resource -> String.valueOf(resource.get("@type")))
        .toList();
  }

  @SuppressWarnings("unchecked")
  private static String nugetResourceBase(Map<String, Object> index, String typePrefix) {
    Object raw = index.get("resources");
    assertTrue(raw instanceof List<?>, "NuGet service index resources must be a list");
    for (Object item : (List<Object>) raw) {
      if (item instanceof Map<?, ?> resource
          && String.valueOf(resource.get("@type")).startsWith(typePrefix)) {
        String id = String.valueOf(resource.get("@id"));
        assertFalse(id == null || id.isBlank(), "NuGet resource " + typePrefix + " must expose @id");
        return id;
      }
    }
    throw new AssertionError("NuGet service index missing resource " + typePrefix);
  }

  @SuppressWarnings("unchecked")
  private static void assertVersionsContain(String label, Map<String, Object> doc, String version) {
    Object raw = doc.get("versions");
    assertTrue(raw instanceof List<?>, label + " versions must be a list: " + doc);
    assertTrue(((List<Object>) raw).stream().map(String::valueOf).anyMatch(version::equalsIgnoreCase),
        label + " should contain " + version + ": " + raw);
  }

  private static void assertPackageHeadMatches(String label, Exchange reference, Exchange candidate) {
    assertSameStatus(label, reference, candidate);
    assertEquals(200, candidate.status(), label + " candidate status");
    assertContentTypeMatches(reference, candidate, label + " content type");
    if (reference.contentLength().isPresent() && candidate.contentLength().isPresent()) {
      assertEquals(reference.contentLength().get(), candidate.contentLength().get(),
          label + " content length");
    }
  }

  private static Exchange waitForRubySpecs(Endpoint endpoint, String repository, GemFixture fixture)
      throws Exception {
    Exchange last = null;
    AssertionError lastError = null;
    for (int attempt = 0; attempt < 12; attempt++) {
      last = get(endpoint.repository(repository, "specs.4.8.gz"));
      try {
        assertRubySpecsContain(endpoint.name() + " RubyGems specs", last, fixture);
        return last;
      } catch (AssertionError e) {
        lastError = e;
        Thread.sleep(500L);
      }
    }
    if (lastError != null) throw lastError;
    return last == null ? get(endpoint.repository(repository, "specs.4.8.gz")) : last;
  }

  private static void assertRubySpecsContain(
      String label, Exchange reference, Exchange candidate, GemFixture fixture) throws Exception {
    assertSameStatus(label, reference, candidate);
    assertRubySpecsContain(label + " reference", reference, fixture);
    assertRubySpecsContain(label + " candidate", candidate, fixture);
  }

  private static void assertRubySpecsContain(String label, Exchange exchange, GemFixture fixture) throws Exception {
    assert2xx(label, exchange);
    String body = new String(gunzip(exchange.body()), StandardCharsets.ISO_8859_1);
    assertTrue(body.contains(fixture.name()), label + " should contain " + fixture.name());
    assertTrue(body.contains(fixture.version()), label + " should contain " + fixture.version());
  }

  private static void assertRubyCompactInfoContainsChecksum(
      String label, Exchange exchange, GemFixture fixture) throws Exception {
    assert2xx(label, exchange);
    String body = exchange.bodyText();
    String expectedLine = fixture.version() + " " + fixture.dependencyName()
        + ":" + String.join("&", fixture.dependencyRequirements())
        + "|checksum:" + sha256(fixture.bytes());
    assertTrue(body.contains(expectedLine), label + " should contain " + expectedLine + ": " + body);
  }

  private static void assertRubyDependenciesMatch(
      String label, Exchange reference, Exchange candidate, GemFixture fixture) {
    assertSameStatus(label, reference, candidate);
    assert2xx(label + " reference", reference);
    assert2xx(label + " candidate", candidate);
    List<RubyDependency> referenceBody = rubyDependencyApi(reference.body());
    List<RubyDependency> candidateBody = rubyDependencyApi(candidate.body());
    assertEquals(referenceBody.toString(), candidateBody.toString(), label + " normalized Marshal body");
    RubyDependency expected = new RubyDependency(
        fixture.name(), fixture.version(), "ruby", List.of(fixture.dependency()));
    assertTrue(referenceBody.toString().contains(expected.toString()),
        label + " should include fixture dependency: " + referenceBody);
  }

  private static void assertRubyQuickSpecContains(
      String label, Exchange reference, Exchange candidate, GemFixture fixture) throws Exception {
    assertSameStatus(label, reference, candidate);
    assert2xx(label + " reference", reference);
    assert2xx(label + " candidate", candidate);
    RubyQuickSpec referenceSpec = rubyQuickSpec(label + " reference", reference.body());
    RubyQuickSpec candidateSpec = rubyQuickSpec(label + " candidate", candidate.body());
    assertEquals(referenceSpec.comparableFields(), candidateSpec.comparableFields(),
        label + " normalized Gem::Specification fields");
    assertQuickSpecPayload(label + " reference", referenceSpec, fixture);
    assertQuickSpecPayload(label + " candidate", candidateSpec, fixture);
  }

  private static RubyQuickSpec rubyQuickSpec(String label, byte[] deflated) throws Exception {
    Object root = new RubyMarshalReader(inflate(deflated)).read();
    assertTrue(root instanceof RubyUserDefined, label + " root should be user-defined Gem::Specification");
    RubyUserDefined spec = (RubyUserDefined) root;
    assertEquals("Gem::Specification", spec.rubyClass(), label + " root class");
    assertTrue(spec.value() instanceof List<?>, label + " Gem::Specification payload must be a field array");
    List<Object> fields = ((List<?>) spec.value()).stream()
        .map(NugetRubygemsYumRepositoryBlackBoxCompatibilityTest::normalizeRubyMarshalValue)
        .toList();
    assertEquals(19, fields.size(), label + " Gem::Specification field count");
    return new RubyQuickSpec(fields);
  }

  private static void assertQuickSpecPayload(String label, RubyQuickSpec spec, GemFixture fixture) {
    assertEquals(fixture.name(), spec.name(), label + " name");
    assertEquals(fixture.version(), spec.version(), label + " version");
    assertTrue(spec.dependencies().contains(fixture.dependencyName()),
        label + " should contain dependency " + fixture.dependencyName() + ": " + spec.dependencies());
    for (String requirement : fixture.dependencyRequirements()) {
      String[] parts = splitGemRequirement(requirement);
      assertTrue(spec.dependencies().contains(parts[0]), label + " should contain " + parts[0]);
      assertTrue(spec.dependencies().contains(parts[1]), label + " should contain " + parts[1]);
    }
  }

  private static String[] splitGemRequirement(String requirement) {
    int versionStart = 0;
    while (versionStart < requirement.length() && !Character.isDigit(requirement.charAt(versionStart))) {
      versionStart++;
    }
    assertTrue(versionStart > 0 && versionStart < requirement.length(), "invalid gem requirement: " + requirement);
    return new String[] {
        requirement.substring(0, versionStart).trim(),
        requirement.substring(versionStart).trim()
    };
  }

  private static void assertBodyMatches(String label, Exchange reference, Exchange candidate, byte[] expected) {
    assertSameStatus(label, reference, candidate);
    assert2xx(label + " reference", reference);
    assert2xx(label + " candidate", candidate);
    assertArrayEquals(expected, reference.body(), label + " reference body");
    assertArrayEquals(expected, candidate.body(), label + " candidate body");
  }

  private static void ensureNugetRepositories(CompatConfig config) throws Exception {
    saveNexusNugetHosted(config, config.nugetHostedRepository());
    saveNexusNugetProxy(config, config.nugetProxyRepository(), config.nugetRemoteUrl());
    saveNexusNugetGroup(config, config.nugetGroupRepository(),
        List.of(config.nugetHostedRepository(), config.nugetProxyRepository()));

    saveKkRepoHosted(config, config.nugetHostedRepository(), "nuget-hosted");
    saveKkRepoProxy(config, config.nugetProxyRepository(), "nuget-proxy", config.nugetRemoteUrl());
    saveKkRepoGroup(config, config.nugetGroupRepository(), "nuget-group",
        List.of(config.nugetHostedRepository(), config.nugetProxyRepository()));
  }

  private static void ensureRubygemsRepositories(CompatConfig config) throws Exception {
    saveNexusRubygemsHosted(config, config.rubygemsHostedRepository());
    saveNexusRubygemsGroup(config, config.rubygemsGroupRepository(), List.of(config.rubygemsHostedRepository()));

    saveKkRepoHosted(config, config.rubygemsHostedRepository(), "rubygems-hosted");
    saveKkRepoGroup(config, config.rubygemsGroupRepository(), "rubygems-group",
        List.of(config.rubygemsHostedRepository()));
  }

  private static void ensureYumRepositories(CompatConfig config) throws Exception {
    saveNexusYumHosted(config, config.yumHostedRepository());
    saveKkRepoHosted(config, config.yumHostedRepository(), "yum-hosted");
  }

  private static void saveNexusNugetHosted(CompatConfig config, String repository) throws Exception {
    String body = """
        {"name":"%s","online":true,"storage":{"blobStoreName":"default","strictContentTypeValidation":true,"writePolicy":"allow"}}
        """.formatted(repository);
    saveNexusRepository(config, "/service/rest/v1/repositories/nuget/hosted", repository, body);
  }

  private static void saveNexusNugetProxy(CompatConfig config, String repository, String remoteUrl) throws Exception {
    String body = """
        {"name":"%s","online":true,"storage":{"blobStoreName":"default","strictContentTypeValidation":true},"proxy":{"remoteUrl":"%s","contentMaxAge":1440,"metadataMaxAge":1440},"negativeCache":{"enabled":true,"timeToLive":1440},"httpClient":{"blocked":false,"autoBlock":false},"nugetProxy":{"queryCacheItemMaxAge":3600,"nugetVersion":"V3"}}
        """.formatted(repository, remoteUrl);
    saveNexusRepository(config, "/service/rest/v1/repositories/nuget/proxy", repository, body);
  }

  private static void saveNexusNugetGroup(CompatConfig config, String repository, List<String> members)
      throws Exception {
    String body = """
        {"name":"%s","online":true,"storage":{"blobStoreName":"default","strictContentTypeValidation":true},"group":{"memberNames":%s}}
        """.formatted(repository, jsonArray(members));
    saveNexusRepository(config, "/service/rest/v1/repositories/nuget/group", repository, body);
  }

  private static void saveNexusRubygemsHosted(CompatConfig config, String repository) throws Exception {
    String body = """
        {"name":"%s","online":true,"storage":{"blobStoreName":"default","strictContentTypeValidation":true,"writePolicy":"allow"}}
        """.formatted(repository);
    saveNexusRepository(config, "/service/rest/v1/repositories/rubygems/hosted", repository, body);
  }

  private static void saveNexusRubygemsGroup(CompatConfig config, String repository, List<String> members)
      throws Exception {
    String body = """
        {"name":"%s","online":true,"storage":{"blobStoreName":"default","strictContentTypeValidation":true},"group":{"memberNames":%s}}
        """.formatted(repository, jsonArray(members));
    saveNexusRepository(config, "/service/rest/v1/repositories/rubygems/group", repository, body);
  }

  private static void saveNexusYumHosted(CompatConfig config, String repository) throws Exception {
    String body = """
        {"name":"%s","online":true,"storage":{"blobStoreName":"default","strictContentTypeValidation":true,"writePolicy":"allow"},"yum":{"repodataDepth":0}}
        """.formatted(repository);
    saveNexusRepository(config, "/service/rest/v1/repositories/yum/hosted", repository, body);
  }

  private static void saveNexusRepository(
      CompatConfig config, String collectionPath, String repository, String body) throws Exception {
    Exchange existing = send(config.nexus.raw(collectionPath + "/" + repository).GET());
    assertTrue(existing.status() == 200 || existing.status() == 404,
        "lookup Nexus repository " + repository + " status=" + existing.status()
            + " body=" + existing.bodyText());
    if (existing.status() == 200) {
      return;
    }
    HttpRequest.Builder request = config.nexus.raw(existing.status() == 200
            ? collectionPath + "/" + repository
            : collectionPath)
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(60));
    Exchange saved = send(existing.status() == 200
        ? request.PUT(HttpRequest.BodyPublishers.ofString(body))
        : request.POST(HttpRequest.BodyPublishers.ofString(body)));
    assert2xx("save Nexus repository " + repository, saved);
  }

  private static void saveKkRepoHosted(CompatConfig config, String repository, String recipe)
      throws Exception {
    String body = """
        {"name":"%s","recipe":"%s","online":true,"blobStoreName":"default","strictContentTypeValidation":true,"hosted":{"writePolicy":"ALLOW","versionPolicy":null,"layoutPolicy":null}}
        """.formatted(repository, recipe);
    saveKkRepoRepository(config, repository, body);
  }

  private static void saveKkRepoProxy(
      CompatConfig config, String repository, String recipe, String remoteUrl) throws Exception {
    String body = """
        {"name":"%s","recipe":"%s","online":true,"blobStoreName":"default","strictContentTypeValidation":true,"proxy":{"remoteUrl":"%s","contentMaxAgeMinutes":1440,"metadataMaxAgeMinutes":1440,"autoBlock":false}}
        """.formatted(repository, recipe, remoteUrl);
    saveKkRepoRepository(config, repository, body);
  }

  private static void saveKkRepoGroup(
      CompatConfig config, String repository, String recipe, List<String> members) throws Exception {
    String body = """
        {"name":"%s","recipe":"%s","online":true,"blobStoreName":"default","strictContentTypeValidation":true,"group":{"memberNames":%s}}
        """.formatted(repository, recipe, jsonArray(members));
    saveKkRepoRepository(config, repository, body);
  }

  private static void saveKkRepoRepository(CompatConfig config, String repository, String body)
      throws Exception {
    Exchange existing = send(config.nexusPlus.raw("/internal/repositories/" + repository).GET());
    assertTrue(existing.status() == 200 || existing.status() == 404,
        "lookup kkrepo repository " + repository + " status=" + existing.status()
            + " body=" + existing.bodyText());
    HttpRequest.Builder request = config.nexusPlus.raw(existing.status() == 200
            ? "/internal/repositories/" + repository
            : "/internal/repositories")
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(60));
    Exchange saved = send(existing.status() == 200
        ? request.PUT(HttpRequest.BodyPublishers.ofString(body))
        : request.POST(HttpRequest.BodyPublishers.ofString(body)));
    assert2xx("save kkrepo repository " + repository, saved);
  }

  private static Map<String, Object> getJson(HttpRequest.Builder builder) throws Exception {
    Exchange exchange = get(builder);
    assert2xx("GET JSON " + exchange, exchange);
    return MAPPER.readValue(exchange.body(), MAP_TYPE);
  }

  private static Exchange get(HttpRequest.Builder builder) throws Exception {
    return send(builder.GET());
  }

  private static Exchange head(HttpRequest.Builder builder) throws Exception {
    return send(builder.method("HEAD", HttpRequest.BodyPublishers.noBody()));
  }

  private static Exchange post(HttpRequest.Builder builder, byte[] body, String contentType) throws Exception {
    return send(builder.header("Content-Type", contentType)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body)));
  }

  private static Exchange put(HttpRequest.Builder builder, byte[] body, String contentType) throws Exception {
    return send(builder.header("Content-Type", contentType)
        .PUT(HttpRequest.BodyPublishers.ofByteArray(body)));
  }

  private static Exchange putMultipart(HttpRequest.Builder builder, NugetFixture fixture) throws Exception {
    String boundary = "kkrepo-nuget-compat-" + System.nanoTime();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
    out.write(("Content-Disposition: form-data; name=\"package\"; filename=\"" + fixture.fileName() + "\"\r\n")
        .getBytes(StandardCharsets.UTF_8));
    out.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
    out.write(fixture.bytes());
    out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return send(builder.header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .PUT(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())));
  }

  private static Exchange send(HttpRequest.Builder builder) throws Exception {
    HttpResponse<byte[]> response = HTTP.send(
        builder.header("User-Agent", "kkrepo-nuget-rubygems-yum-compat-test/1")
            .timeout(Duration.ofSeconds(120))
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
    return new Exchange(
        response.statusCode(),
        response.body(),
        response.headers().firstValue("content-type"),
        response.headers().firstValue("content-length"));
  }

  private static void assertSameStatus(String label, Exchange reference, Exchange candidate) {
    assertEquals(reference.status(), candidate.status(), label + " status");
  }

  private static void assert2xx(String label, Exchange exchange) {
    assertTrue(exchange.status() >= 200 && exchange.status() < 300,
        label + " expected 2xx but got " + exchange.status()
            + " body=" + exchange.bodyText());
  }

  private static void assertContentTypeMatches(Exchange reference, Exchange candidate, String label) {
    assertEquals(normalizedContentType(reference), normalizedContentType(candidate), label);
  }

  private static String normalizedContentType(Exchange exchange) {
    return exchange.contentType()
        .map(value -> value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))
        .orElse("");
  }

  private static byte[] gunzip(byte[] bytes) throws Exception {
    try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
      return gzip.readAllBytes();
    }
  }

  private static byte[] inflate(byte[] bytes) throws Exception {
    try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(bytes))) {
      return inflater.readAllBytes();
    }
  }

  private static String appendPath(String base, String suffix) {
    String result = base == null ? "" : base;
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    String cleanSuffix = suffix == null ? "" : suffix;
    while (cleanSuffix.startsWith("/")) {
      cleanSuffix = cleanSuffix.substring(1);
    }
    return result + "/" + cleanSuffix;
  }

  private static String jsonArray(List<String> values) {
    return "[" + values.stream()
        .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        .reduce((left, right) -> left + "," + right)
        .orElse("") + "]";
  }

  private static byte[] gzip(byte[] bytes) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
      gzip.write(bytes);
    }
    return out.toByteArray();
  }

  private static void tarEntry(TarArchiveOutputStream tar, String name, byte[] bytes) throws Exception {
    TarArchiveEntry entry = new TarArchiveEntry(name);
    entry.setSize(bytes.length);
    tar.putArchiveEntry(entry);
    tar.write(bytes);
    tar.closeArchiveEntry();
  }

  private static byte[] download(String url) throws Exception {
    Exchange exchange = get(HttpRequest.newBuilder(URI.create(url)));
    assert2xx("download Yum RPM fixture " + url, exchange);
    assertTrue(exchange.body().length > 0, "downloaded Yum RPM fixture must not be empty");
    return exchange.body();
  }

  private static String fileName(String url) {
    String path = URI.create(url).getPath();
    String fileName = path.substring(path.lastIndexOf('/') + 1);
    assertTrue(fileName.endsWith(".rpm"), "Yum fixture URL must end with .rpm: " + url);
    return fileName;
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static List<RubyDependency> rubyDependencyApi(byte[] bytes) {
    Object value = new RubyMarshalReader(bytes).read();
    assertTrue(value instanceof List<?>, "RubyGems dependency API body must be a Ruby Marshal array");
    List<RubyDependency> normalized = new ArrayList<>();
    for (Object item : (List<?>) value) {
      assertTrue(item instanceof Map<?, ?>, "RubyGems dependency entry must be a hash: " + item);
      Map<String, Object> entry = new LinkedHashMap<>();
      for (Map.Entry<?, ?> raw : ((Map<?, ?>) item).entrySet()) {
        entry.put(String.valueOf(raw.getKey()), normalizeRubyMarshalValue(raw.getValue()));
      }
      normalized.add(new RubyDependency(
          String.valueOf(entry.get("name")),
          String.valueOf(entry.get("number")),
          String.valueOf(entry.get("platform")),
          rubyDependencyList(entry.get("dependencies"))));
    }
    return normalized;
  }

  private static List<List<String>> rubyDependencyList(Object value) {
    if (value instanceof Map<?, ?> map) {
      return map.entrySet().stream()
          .map(entry -> List.of(String.valueOf(entry.getKey()), String.valueOf(entry.getValue())))
          .toList();
    }
    assertTrue(value instanceof List<?>, "RubyGems dependencies must be a list: " + value);
    List<List<String>> dependencies = new ArrayList<>();
    for (Object item : (List<?>) value) {
      assertTrue(item instanceof List<?>, "RubyGems dependency must be a list: " + item);
      dependencies.add(((List<?>) item).stream().map(String::valueOf).toList());
    }
    return dependencies;
  }

  private static Object normalizeRubyMarshalValue(Object value) {
    return normalizeRubyMarshalValue(value, Collections.newSetFromMap(new IdentityHashMap<>()));
  }

  private static Object normalizeRubyMarshalValue(Object value, Set<Object> active) {
    if (value instanceof List<?> list) {
      if (!active.add(list)) {
        return "<cycle>";
      }
      try {
        return list.stream()
            .map(item -> normalizeRubyMarshalValue(item, active))
            .toList();
      } finally {
        active.remove(list);
      }
    }
    if (value instanceof RubyUserMarshaled userMarshaled) {
      if (!active.add(userMarshaled)) {
        return "<cycle>";
      }
      try {
        if ("Gem::Requirement".equals(userMarshaled.rubyClass())) {
          Object normalized = normalizeRubyMarshalValue(userMarshaled.value(), active);
          if (isDefaultRubyRequirement(normalized)) {
            normalized = List.of(List.of());
          }
          return Map.of("class", userMarshaled.rubyClass(), "value", normalized);
        }
        if ("Gem::Version".equals(userMarshaled.rubyClass())
            && userMarshaled.value() instanceof List<?> list
            && !list.isEmpty()) {
          return normalizeRubyMarshalValue(list.getFirst(), active);
        }
        return Map.of(
            "class", userMarshaled.rubyClass(),
            "value", normalizeRubyMarshalValue(userMarshaled.value(), active));
      } finally {
        active.remove(userMarshaled);
      }
    }
    if (value instanceof RubyUserDefined userDefined) {
      if (!active.add(userDefined)) {
        return "<cycle>";
      }
      try {
        return Map.of(
            "class", userDefined.rubyClass(),
            "value", normalizeRubyMarshalValue(userDefined.value(), active));
      } finally {
        active.remove(userDefined);
      }
    }
    if (value instanceof RubyObject object) {
      if (!active.add(object)) {
        return "<cycle>";
      }
      try {
        return Map.of(
            "class", object.rubyClass(),
            "attributes", normalizeRubyMarshalValue(object.attributes(), active));
      } finally {
        active.remove(object);
      }
    }
    if (value instanceof byte[] raw) {
      return HexFormat.of().formatHex(raw);
    }
    if (value instanceof Map<?, ?> map) {
      if (!active.add(map)) {
        return "<cycle>";
      }
      Map<String, Object> normalized = new LinkedHashMap<>();
      try {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          normalized.put(String.valueOf(entry.getKey()), normalizeRubyMarshalValue(entry.getValue(), active));
        }
      } finally {
        active.remove(map);
      }
      return normalized;
    }
    return value;
  }

  private static boolean isDefaultRubyRequirement(Object value) {
    if (!(value instanceof List<?> list) || list.size() != 1) {
      return false;
    }
    Object item = list.getFirst();
    if (item instanceof List<?> pair && pair.size() == 2) {
      return ">=".equals(String.valueOf(pair.get(0))) && "0".equals(String.valueOf(pair.get(1)));
    }
    if (item instanceof List<?>) {
      return isDefaultRubyRequirement(item);
    }
    return false;
  }

  private record RubyQuickSpec(List<Object> fields) {
    List<Object> comparableFields() {
      List<Object> comparable = new ArrayList<>(fields);
      comparable.set(4, "<time>");
      return comparable;
    }

    String name() {
      return String.valueOf(fields.get(2));
    }

    String version() {
      return String.valueOf(fields.get(3));
    }

    String dependencies() {
      return String.valueOf(fields.get(9));
    }
  }

  private record RubyUserDefined(String rubyClass, Object value) {
  }

  private static final class RubyUserMarshaled {
    private final String rubyClass;
    private Object value;

    private RubyUserMarshaled(String rubyClass) {
      this.rubyClass = rubyClass;
    }

    String rubyClass() {
      return rubyClass;
    }

    Object value() {
      return value;
    }
  }

  private record RubyObject(String rubyClass, Map<String, Object> attributes) {
  }

  private static final class RubyMarshalReader {
    private final byte[] bytes;
    private final List<String> symbols = new ArrayList<>();
    private final List<Object> objects = new ArrayList<>();
    private int pos;

    RubyMarshalReader(byte[] bytes) {
      this.bytes = bytes;
    }

    Object read() {
      int major = readByte();
      int minor = readByte();
      assertEquals(4, major, "Ruby Marshal major version");
      assertEquals(8, minor, "Ruby Marshal minor version");
      Object value = readObject();
      assertEquals(bytes.length, pos, "Ruby Marshal payload should be fully consumed");
      return value;
    }

    private Object readObject() {
      int type = readByte();
      return switch (type) {
        case '[' -> readArray();
        case '{' -> readHash();
        case 'I' -> readIvar();
        case '"' -> readString();
        case ':' -> readSymbol();
        case ';' -> symbols.get(readFixnum());
        case 'T' -> Boolean.TRUE;
        case 'F' -> Boolean.FALSE;
        case '0' -> null;
        case '@' -> readObjectLink();
        case 'i' -> readFixnum();
        case 'U' -> readUserMarshaled();
        case 'u' -> readUserDefined();
        case 'o' -> readObjectInstance();
        default -> throw new AssertionError("Unsupported Ruby Marshal type 0x"
            + Integer.toHexString(type) + " at offset " + (pos - 1));
      };
    }

    private List<Object> readArray() {
      int size = readFixnum();
      List<Object> values = new ArrayList<>(size);
      remember(values);
      for (int i = 0; i < size; i++) {
        values.add(readObject());
      }
      return values;
    }

    private Map<Object, Object> readHash() {
      int size = readFixnum();
      Map<Object, Object> values = new LinkedHashMap<>();
      remember(values);
      for (int i = 0; i < size; i++) {
        values.put(readObject(), readObject());
      }
      return values;
    }

    private Object readIvar() {
      Object value = readObject();
      int count = readFixnum();
      for (int i = 0; i < count; i++) {
        readObject();
        readObject();
      }
      return value;
    }

    private Object readUserMarshaled() {
      RubyUserMarshaled value = remember(new RubyUserMarshaled(String.valueOf(readObject())));
      value.value = readObject();
      return value;
    }

    private Object readUserDefined() {
      Object rubyClass = readObject();
      byte[] payload = readRawBytes();
      if ("Gem::Specification".equals(rubyClass)) {
        return remember(new RubyUserDefined(String.valueOf(rubyClass), new RubyMarshalReader(payload).read()));
      }
      return remember(new RubyUserDefined(String.valueOf(rubyClass), payload));
    }

    private String readString() {
      byte[] raw = readRawBytes();
      return remember(new String(raw, StandardCharsets.UTF_8));
    }

    private RubyObject readObjectInstance() {
      String rubyClass = String.valueOf(readObject());
      int count = readFixnum();
      Map<String, Object> attributes = new LinkedHashMap<>();
      RubyObject object = new RubyObject(rubyClass, attributes);
      remember(object);
      for (int i = 0; i < count; i++) {
        attributes.put(String.valueOf(readObject()), readObject());
      }
      return object;
    }

    private Object readObjectLink() {
      int index = readFixnum();
      if (index < 0 || index >= objects.size()) {
        throw new AssertionError("Invalid Ruby Marshal object link " + index + " at offset " + pos);
      }
      return objects.get(index);
    }

    private byte[] readRawBytes() {
      int size = readFixnum();
      if (size < 0 || pos + size > bytes.length) {
        throw new AssertionError("Invalid Ruby Marshal string size " + size + " at offset " + pos);
      }
      byte[] value = java.util.Arrays.copyOfRange(bytes, pos, pos + size);
      pos += size;
      return value;
    }

    private String readSymbol() {
      byte[] raw = readRawBytes();
      String symbol = new String(raw, StandardCharsets.UTF_8);
      symbols.add(symbol);
      return symbol;
    }

    private int readFixnum() {
      int raw = readByte();
      if (raw == 0) {
        return 0;
      }
      if (raw >= 5 && raw < 128) {
        return raw - 5;
      }
      if (raw >= 128 && raw <= 251) {
        return raw - 256 + 5;
      }
      if (raw > 0 && raw < 5) {
        int value = 0;
        for (int i = 0; i < raw; i++) {
          value |= readByte() << (8 * i);
        }
        return value;
      }
      int count = 256 - raw;
      int value = -1;
      for (int i = 0; i < count; i++) {
        value &= ~(0xff << (8 * i));
        value |= readByte() << (8 * i);
      }
      return value;
    }

    private int readByte() {
      if (pos >= bytes.length) {
        throw new AssertionError("Unexpected end of Ruby Marshal payload");
      }
      return bytes[pos++] & 0xff;
    }

    private <T> T remember(T value) {
      objects.add(value);
      return value;
    }
  }

  private record Exchange(
      int status,
      byte[] body,
      Optional<String> contentType,
      Optional<String> contentLength) {
    String bodyText() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }

  private record RubyDependency(
      String name,
      String number,
      String platform,
      List<List<String>> dependencies) {
  }

  private record NugetFixture(String id, String version, String fileName, byte[] bytes) {
    static NugetFixture create() throws Exception {
      String stamp = Long.toString(System.currentTimeMillis());
      String id = "KkRepo.Compat.NuGet." + stamp;
      String version = "1.0." + stamp;
      String fileName = id + "." + version + ".nupkg";
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (ZipOutputStream zip = new ZipOutputStream(out)) {
        putZip(zip, id + ".nuspec", """
            <?xml version="1.0"?>
            <package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">
              <metadata>
                <id>%s</id>
                <version>%s</version>
                <authors>codex</authors>
                <description>kkrepo NuGet compatibility fixture</description>
              </metadata>
            </package>
            """.formatted(id, version).getBytes(StandardCharsets.UTF_8));
        putZip(zip, "content/readme.txt", ("nuget fixture " + stamp + "\n").getBytes(StandardCharsets.UTF_8));
      }
      return new NugetFixture(id, version, fileName, out.toByteArray());
    }

    String normalizedId() {
      return id.toLowerCase(Locale.ROOT);
    }

    String normalizedVersion() {
      return version.toLowerCase(Locale.ROOT);
    }

    private static void putZip(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
      ZipEntry entry = new ZipEntry(name);
      zip.putNextEntry(entry);
      zip.write(bytes);
      zip.closeEntry();
    }
  }

  private record GemFixture(
      String name,
      String version,
      String dependencyName,
      List<String> dependencyRequirements,
      byte[] bytes) {
    static GemFixture create() throws Exception {
      String stamp = Long.toString(System.currentTimeMillis());
      String name = "kkrepo-compat-gem-" + stamp;
      String version = "0.0." + stamp;
      String dependencyName = "rack";
      List<String> dependencyRequirements = List.of(">= 2.0.0", "< 3.0.0");
      String metadata = """
          --- !ruby/object:Gem::Specification
          name: %s
          version: !ruby/object:Gem::Version
            version: %s
          platform: ruby
          summary: kkrepo RubyGems compatibility fixture
          authors:
          - codex
          files:
          - lib/%s.rb
          require_paths:
          - lib
          dependencies:
          - !ruby/object:Gem::Dependency
            name: %s
            requirement: !ruby/object:Gem::Requirement
              requirements:
              - - ">="
                - !ruby/object:Gem::Version
                  version: 2.0.0
              - - "<"
                - !ruby/object:Gem::Version
                  version: 3.0.0
          """.formatted(name, version, name.replace('-', '_'), dependencyName);
      ByteArrayOutputStream dataTarGz = new ByteArrayOutputStream();
      try (GZIPOutputStream gzip = new GZIPOutputStream(dataTarGz);
           TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
        tarEntry(tar, "lib/" + name.replace('-', '_') + ".rb",
            ("module " + camelName(name) + "; end\n").getBytes(StandardCharsets.UTF_8));
      }

      ByteArrayOutputStream gem = new ByteArrayOutputStream();
      try (TarArchiveOutputStream tar = new TarArchiveOutputStream(gem)) {
        tarEntry(tar, "metadata.gz", gzip(metadata.getBytes(StandardCharsets.UTF_8)));
        tarEntry(tar, "data.tar.gz", dataTarGz.toByteArray());
        tarEntry(tar, "checksums.yaml.gz", gzip("---\nSHA256:\n".getBytes(StandardCharsets.UTF_8)));
      }
      return new GemFixture(name, version, dependencyName, dependencyRequirements, gem.toByteArray());
    }

    String fileName() {
      return name + "-" + version + ".gem";
    }

    String gemPath() {
      return "gems/" + fileName();
    }

    String quickSpecPath() {
      return "quick/Marshal.4.8/" + fileName() + "spec.rz";
    }

    String infoPath() {
      return "info/" + name;
    }

    String dependenciesPath() {
      return "api/v1/dependencies?gems=" + name;
    }

    List<String> dependency() {
      List<String> dependency = new ArrayList<>();
      dependency.add(dependencyName);
      dependency.addAll(dependencyRequirements);
      return dependency;
    }

    private static String camelName(String value) {
      StringBuilder out = new StringBuilder();
      boolean nextUpper = true;
      for (char c : value.toCharArray()) {
        if (c == '-' || c == '_' || c == '.') {
          nextUpper = true;
        } else if (nextUpper) {
          out.append(Character.toUpperCase(c));
          nextUpper = false;
        } else {
          out.append(c);
        }
      }
      return out.toString();
    }
  }

  private record CompatConfig(
      Endpoint nexus,
      Endpoint nexusPlus,
      boolean setupEnabled,
      boolean writeEnabled,
      String nugetHostedRepository,
      String nugetProxyRepository,
      String nugetGroupRepository,
      String nugetRemoteUrl,
      String nugetReadPackage,
      String nugetReadVersion,
      String rubygemsHostedRepository,
      String rubygemsGroupRepository,
      String yumHostedRepository,
      String yumFixtureUrl) {
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
      return new CompatConfig(
          nexus,
          nexusPlus,
          Boolean.parseBoolean(setting("compat.extended.setup.enabled", "COMPAT_EXTENDED_SETUP_ENABLED")
              .orElse("true")),
          Boolean.parseBoolean(setting("compat.write.enabled", "COMPAT_WRITE_ENABLED").orElse("false")),
          setting("compat.nuget.hostedRepository", "COMPAT_NUGET_HOSTED_REPOSITORY")
              .orElse("nuget-hosted"),
          setting("compat.nuget.proxyRepository", "COMPAT_NUGET_PROXY_REPOSITORY")
              .orElse("nuget.org-proxy"),
          setting("compat.nuget.groupRepository", "COMPAT_NUGET_GROUP_REPOSITORY")
              .orElse("nuget-group"),
          stripTrailingSlash(setting("compat.nuget.remoteUrl", "COMPAT_NUGET_REMOTE_URL")
              .orElse("https://api.nuget.org/v3/index.json")),
          setting("compat.nuget.readPackage", "COMPAT_NUGET_READ_PACKAGE")
              .orElse("Newtonsoft.Json"),
          setting("compat.nuget.readVersion", "COMPAT_NUGET_READ_VERSION")
              .orElse("13.0.3"),
          setting("compat.rubygems.hostedRepository", "COMPAT_RUBYGEMS_HOSTED_REPOSITORY")
              .orElse("ruby-hosted"),
          setting("compat.rubygems.groupRepository", "COMPAT_RUBYGEMS_GROUP_REPOSITORY")
              .orElse("ruby-group"),
          setting("compat.yum.hostedRepository", "COMPAT_YUM_HOSTED_REPOSITORY")
              .orElse("yum-hosted"),
          setting("compat.yum.fixtureUrl", "COMPAT_YUM_FIXTURE_URL")
              .orElse(DEFAULT_YUM_FIXTURE_URL));
    }

    boolean referenceReachable() {
      return nexus.reachable();
    }

    boolean candidateReachable() {
      return nexusPlus.reachable();
    }

    String nugetReadPackageLower() {
      return nugetReadPackage.toLowerCase(Locale.ROOT);
    }

    String nugetReadVersionLower() {
      return nugetReadVersion.toLowerCase(Locale.ROOT);
    }
  }

  private record Endpoint(
      String name,
      Optional<String> baseUrl,
      Optional<String> username,
      Optional<String> password,
      String repository) {
    HttpRequest.Builder repository(String repository, String repositoryPath) {
      String suffix = repositoryPath == null || repositoryPath.isBlank() ? "" : repositoryPath;
      return raw("/repository/" + repository + "/" + suffix);
    }

    HttpRequest.Builder absolute(String url) {
      HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
      addAuth(builder);
      return builder;
    }

    HttpRequest.Builder raw(String path) {
      String cleanPath = path.startsWith("/") ? path : "/" + path;
      HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl.orElseThrow() + cleanPath));
      addAuth(builder);
      return builder;
    }

    boolean reachable() {
      if (baseUrl.isEmpty()) return false;
      try {
        Exchange exchange = send(raw("/").GET());
        return exchange.status() > 0 && exchange.status() < 500;
      } catch (Exception e) {
        return false;
      }
    }

    private void addAuth(HttpRequest.Builder builder) {
      if (username.isPresent() && password.isPresent()) {
        String token = Base64.getEncoder().encodeToString(
            (username.get() + ":" + password.get()).getBytes(StandardCharsets.UTF_8));
        builder.header("Authorization", "Basic " + token);
      }
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

  private static String stripTrailingSlash(String value) {
    String result = value;
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
