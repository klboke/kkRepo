package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.cargo.CargoIndexPath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
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
import java.util.Map;
import java.util.Optional;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;

class CargoRepositoryBlackBoxCompatibilityTest {
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
  };

  @Test
  void hostedPublishDownloadYankAndUnyankWhenConfigured() throws Exception {
    CargoCompatConfig config = CargoCompatConfig.load();
    assumeTrue(config.enabled(),
        "Set CARGO_COMPAT_ENABLED=true to run Cargo hosted compatibility against Nexus 3.77+");
    assumeTrue(config.referenceReachable(),
        "Reference Nexus is not reachable; start Nexus or override CARGO_NEXUS_COMPAT_BASE_URL");
    assumeTrue(config.candidateReachable(),
        "kkrepo is not reachable; start it or override CARGO_KKREPO_COMPAT_BASE_URL");
    assumeTrue(config.candidateManagementConfigured(),
        "kkrepo repository setup requires CARGO_KKREPO_COMPAT_USERNAME and CARGO_KKREPO_COMPAT_PASSWORD");

    ensureReferenceHostedRepository(config);
    ensureCandidateBlobStore(config);
    ensureCandidateHostedRepository(config);

    Endpoint reference = config.referenceHostedEndpoint();
    Endpoint candidate = config.candidateHostedEndpoint();
    Map<String, Object> referenceConfig = json(send(reference.request("config.json", "GET")));
    Map<String, Object> candidateConfig = json(send(candidate.request("config.json", "GET")));
    assertEquals(Boolean.TRUE, referenceConfig.get("auth-required"), "Nexus hosted auth-required");
    assertEquals(Boolean.TRUE, candidateConfig.get("auth-required"), "kkrepo hosted auth-required");
    assertConfigDownloadEndpoint("Nexus hosted", referenceConfig, reference);
    assertConfigDownloadEndpoint("kkrepo hosted", candidateConfig, candidate);

    CargoFixture fixture = CargoFixture.create();
    assertSuccessfulWrite("hosted publish Nexus", publish(reference, fixture));
    assertSuccessfulWrite("hosted publish kkrepo", publish(candidate, fixture));

    Exchange referenceIndex = eventuallyIndex(reference, fixture.name(), fixture.version(), false);
    Exchange candidateIndex = eventuallyIndex(candidate, fixture.name(), fixture.version(), false);
    assertSameHostedIndexSemantics("hosted index", referenceIndex, candidateIndex, fixture);

    Exchange referenceCrate = send(reference.absoluteRequest(
        downloadUrl(referenceConfig, fixture.name(), fixture.version()), "GET"));
    Exchange candidateCrate = send(candidate.absoluteRequest(
        downloadUrl(candidateConfig, fixture.name(), fixture.version()), "GET"));
    assertSameBody("hosted crate", referenceCrate, candidateCrate);
    assertEquals(fixture.crateSha256(), sha256Hex(candidateCrate.body()), "hosted crate sha256");

    assertSuccessfulWrite("hosted yank Nexus", yank(reference, fixture));
    assertSuccessfulWrite("hosted yank kkrepo", yank(candidate, fixture));
    assertSameHostedIndexSemantics(
        "hosted yanked index",
        eventuallyIndex(reference, fixture.name(), fixture.version(), true),
        eventuallyIndex(candidate, fixture.name(), fixture.version(), true),
        fixture);

    assertSuccessfulWrite("hosted unyank Nexus", unyank(reference, fixture));
    assertSuccessfulWrite("hosted unyank kkrepo", unyank(candidate, fixture));
    assertSameHostedIndexSemantics(
        "hosted unyanked index",
        eventuallyIndex(reference, fixture.name(), fixture.version(), false),
        eventuallyIndex(candidate, fixture.name(), fixture.version(), false),
        fixture);
  }

  @Test
  void proxyReadsUseSameUpstreamAndPackageWhenConfigured() throws Exception {
    CargoCompatConfig config = CargoCompatConfig.load();
    assumeTrue(config.enabled(),
        "Set CARGO_COMPAT_ENABLED=true to run Cargo proxy compatibility against Nexus 3.77+");
    assumeTrue(config.referenceReachable(),
        "Reference Nexus is not reachable; start Nexus or override CARGO_NEXUS_COMPAT_BASE_URL");
    assumeTrue(config.candidateReachable(),
        "kkrepo is not reachable; start it or override CARGO_KKREPO_COMPAT_BASE_URL");
    assumeTrue(config.candidateManagementConfigured(),
        "kkrepo repository setup requires CARGO_KKREPO_COMPAT_USERNAME and CARGO_KKREPO_COMPAT_PASSWORD");

    ensureReferenceProxyRepository(config);
    ensureCandidateBlobStore(config);
    ensureCandidateProxyRepository(config);
    assertRepositoryRemoteUrl("Nexus", config.referenceRepositoryConfig(), config.remoteUrl());
    assertRepositoryRemoteUrl("kkrepo", config.candidateRepositoryConfig(), config.remoteUrl());

    Endpoint reference = config.referenceEndpoint();
    Endpoint candidate = config.candidateEndpoint();
    Exchange referenceConfig = send(reference.request("config.json", "GET"));
    Exchange candidateConfig = send(candidate.request("config.json", "GET"));
    assertEquals(200, referenceConfig.status(), "Nexus config.json status");
    assertEquals(200, candidateConfig.status(), "kkrepo config.json status");

    Map<String, Object> referenceConfigJson = json(referenceConfig);
    Map<String, Object> candidateConfigJson = json(candidateConfig);
    assertConfigDownloadEndpoint("Nexus", referenceConfigJson, reference);
    assertConfigDownloadEndpoint("kkrepo", candidateConfigJson, candidate);

    String indexPath = CargoIndexPath.forCrate(config.crateName());
    Exchange referenceIndex = send(reference.request(indexPath, "GET"));
    Exchange candidateIndex = send(candidate.request(indexPath, "GET"));
    assertSameIndexEntry(
        "proxy index " + config.crateName(),
        referenceIndex,
        candidateIndex,
        config.crateName(),
        config.version());

    String referenceDownloadUrl = downloadUrl(referenceConfigJson, config.crateName(), config.version());
    String candidateDownloadUrl = downloadUrl(candidateConfigJson, config.crateName(), config.version());
    Exchange referenceCrate = send(reference.absoluteRequest(referenceDownloadUrl, "GET"));
    Exchange candidateCrate = send(candidate.absoluteRequest(candidateDownloadUrl, "GET"));
    assertSameBody("proxy crate " + config.crateName() + " " + config.version(), referenceCrate, candidateCrate);
    assertEquals(indexChecksum(referenceIndex, config.version()), sha256Hex(candidateCrate.body()),
        "downloaded crate checksum must match the shared upstream index entry");

    Exchange referenceHead = send(reference.absoluteRequest(referenceDownloadUrl, "HEAD"));
    Exchange candidateHead = send(candidate.absoluteRequest(candidateDownloadUrl, "HEAD"));
    assertSameStatusAndLength("proxy crate HEAD", referenceHead, candidateHead);
  }

  @Test
  void proxyMissingIndexAndConditionalRequestsWhenConfigured() throws Exception {
    CargoCompatConfig config = CargoCompatConfig.load();
    assumeTrue(config.enabled(),
        "Set CARGO_COMPAT_ENABLED=true to run Cargo proxy compatibility against Nexus 3.77+");
    assumeTrue(config.referenceReachable(),
        "Reference Nexus is not reachable; start Nexus or override CARGO_NEXUS_COMPAT_BASE_URL");
    assumeTrue(config.candidateReachable(),
        "kkrepo is not reachable; start it or override CARGO_KKREPO_COMPAT_BASE_URL");
    assumeTrue(config.candidateManagementConfigured(),
        "kkrepo repository setup requires CARGO_KKREPO_COMPAT_USERNAME and CARGO_KKREPO_COMPAT_PASSWORD");

    ensureReferenceProxyRepository(config);
    ensureCandidateBlobStore(config);
    ensureCandidateProxyRepository(config);

    Endpoint reference = config.referenceEndpoint();
    Endpoint candidate = config.candidateEndpoint();
    String missingCrate = "kkrepo_missing_" + Long.toUnsignedString(System.nanoTime(), 36).toLowerCase();
    String missingPath = CargoIndexPath.forCrate(missingCrate);
    Exchange referenceMissing = send(reference.request(missingPath, "GET"));
    Exchange candidateMissing = send(candidate.request(missingPath, "GET"));
    assertEquals(referenceMissing.status(), candidateMissing.status(), "missing sparse index status");
    assertEquals(404, candidateMissing.status(), "missing sparse index should be not found");

    String indexPath = CargoIndexPath.forCrate(config.crateName());
    Exchange referenceIndex = send(reference.request(indexPath, "GET"));
    Exchange candidateIndex = send(candidate.request(indexPath, "GET"));
    assertEquals(200, referenceIndex.status(), "Nexus index status");
    assertEquals(200, candidateIndex.status(), "kkrepo index status");
    Exchange referenceConditional = conditionalGet(reference, indexPath, referenceIndex);
    Exchange candidateConditional = conditionalGet(candidate, indexPath, candidateIndex);
    assertEquals(referenceConditional.status(), candidateConditional.status(), "conditional index status");
    assertEquals(304, candidateConditional.status(), "conditional index should be not modified");
    assertEquals(0, referenceConditional.body().length, "Nexus 304 body");
    assertEquals(0, candidateConditional.body().length, "kkrepo 304 body");
  }

  @Test
  void groupReadsHostedAndProxyMembersWhenConfigured() throws Exception {
    CargoCompatConfig config = CargoCompatConfig.load();
    assumeTrue(config.enabled(),
        "Set CARGO_COMPAT_ENABLED=true to run Cargo group compatibility against Nexus 3.77+");
    assumeTrue(config.referenceReachable(),
        "Reference Nexus is not reachable; start Nexus or override CARGO_NEXUS_COMPAT_BASE_URL");
    assumeTrue(config.candidateReachable(),
        "kkrepo is not reachable; start it or override CARGO_KKREPO_COMPAT_BASE_URL");
    assumeTrue(config.candidateManagementConfigured(),
        "kkrepo repository setup requires CARGO_KKREPO_COMPAT_USERNAME and CARGO_KKREPO_COMPAT_PASSWORD");

    ensureReferenceHostedRepository(config);
    ensureReferenceProxyRepository(config);
    ensureReferenceGroupRepository(config);
    ensureCandidateBlobStore(config);
    ensureCandidateHostedRepository(config);
    ensureCandidateProxyRepository(config);
    ensureCandidateGroupRepository(config);

    CargoFixture fixture = CargoFixture.create();
    assertSuccessfulWrite("group fixture publish Nexus", publish(config.referenceHostedEndpoint(), fixture));
    assertSuccessfulWrite("group fixture publish kkrepo", publish(config.candidateHostedEndpoint(), fixture));
    eventuallyIndex(config.referenceHostedEndpoint(), fixture.name(), fixture.version(), false);
    eventuallyIndex(config.candidateHostedEndpoint(), fixture.name(), fixture.version(), false);

    Endpoint reference = config.referenceGroupEndpoint();
    Endpoint candidate = config.candidateGroupEndpoint();
    Map<String, Object> referenceConfig = json(send(reference.request("config.json", "GET")));
    Map<String, Object> candidateConfig = json(send(candidate.request("config.json", "GET")));
    assertConfigDownloadEndpoint("Nexus group", referenceConfig, reference);
    assertConfigDownloadEndpoint("kkrepo group", candidateConfig, candidate);

    Exchange referenceHostedIndex = eventuallyIndex(reference, fixture.name(), fixture.version(), false);
    Exchange candidateHostedIndex = eventuallyIndex(candidate, fixture.name(), fixture.version(), false);
    assertSameHostedIndexSemantics("group hosted member index", referenceHostedIndex, candidateHostedIndex, fixture);
    assertSameBody(
        "group hosted member crate",
        send(reference.absoluteRequest(downloadUrl(referenceConfig, fixture.name(), fixture.version()), "GET")),
        send(candidate.absoluteRequest(downloadUrl(candidateConfig, fixture.name(), fixture.version()), "GET")));

    String proxyIndexPath = CargoIndexPath.forCrate(config.crateName());
    Exchange referenceProxyIndex = send(reference.request(proxyIndexPath, "GET"));
    Exchange candidateProxyIndex = send(candidate.request(proxyIndexPath, "GET"));
    assertSameRemoteIndexSemantics(
        "group proxy member index " + config.crateName(),
        referenceProxyIndex,
        candidateProxyIndex,
        config.crateName(),
        config.version());
    assertSameBody(
        "group proxy member crate",
        send(reference.absoluteRequest(downloadUrl(referenceConfig, config.crateName(), config.version()), "GET")),
        send(candidate.absoluteRequest(downloadUrl(candidateConfig, config.crateName(), config.version()), "GET")));
  }

  private static void ensureReferenceProxyRepository(CargoCompatConfig config) throws Exception {
    URI getUri = URI.create(config.nexusBaseUrl()
        + "/service/rest/v1/repositories/cargo/proxy/" + config.nexusRepository());
    Exchange get = send(config.nexusAdminRequest(getUri).GET());
    assertTrue(get.status() == 200 || get.status() == 404,
        "reference cargo proxy repository lookup status=" + get.status()
            + " body=" + bodyText(get));
    String body = """
        {
          "name": "%s",
          "online": true,
          "storage": {
            "blobStoreName": "default",
            "strictContentTypeValidation": true
          },
          "proxy": {
            "remoteUrl": "%s",
            "contentMaxAge": 1440,
            "metadataMaxAge": 1440
          },
          "negativeCache": {
            "enabled": true,
            "timeToLive": 1440
          },
          "httpClient": {
            "blocked": false,
            "autoBlock": true
          },
          "cargo": {
            "requireAuthentication": false
          }
        }
        """.formatted(config.nexusRepository(), config.remoteUrl());
    String path = get.status() == 200
        ? "/service/rest/v1/repositories/cargo/proxy/" + config.nexusRepository()
        : "/service/rest/v1/repositories/cargo/proxy";
    HttpRequest.Builder request = config.nexusAdminRequest(URI.create(config.nexusBaseUrl() + path))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(30));
    Exchange saved = send(get.status() == 200
        ? request.PUT(HttpRequest.BodyPublishers.ofString(body))
        : request.POST(HttpRequest.BodyPublishers.ofString(body)));
    assertTrue(saved.status() >= 200 && saved.status() < 300,
        "save reference cargo proxy repository status=" + saved.status()
            + " body=" + bodyText(saved));
  }

  private static void ensureReferenceHostedRepository(CargoCompatConfig config) throws Exception {
    URI getUri = URI.create(config.nexusBaseUrl()
        + "/service/rest/v1/repositories/cargo/hosted/" + config.nexusHostedRepository());
    Exchange get = send(config.nexusAdminRequest(getUri).GET());
    assertTrue(get.status() == 200 || get.status() == 404,
        "reference cargo hosted repository lookup status=" + get.status()
            + " body=" + bodyText(get));
    String body = """
        {
          "name": "%s",
          "online": true,
          "storage": {
            "blobStoreName": "default",
            "strictContentTypeValidation": true,
            "writePolicy": "ALLOW"
          }
        }
        """.formatted(config.nexusHostedRepository());
    String path = get.status() == 200
        ? "/service/rest/v1/repositories/cargo/hosted/" + config.nexusHostedRepository()
        : "/service/rest/v1/repositories/cargo/hosted";
    HttpRequest.Builder request = config.nexusAdminRequest(URI.create(config.nexusBaseUrl() + path))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(30));
    Exchange saved = send(get.status() == 200
        ? request.PUT(HttpRequest.BodyPublishers.ofString(body))
        : request.POST(HttpRequest.BodyPublishers.ofString(body)));
    assertTrue(saved.status() >= 200 && saved.status() < 300,
        "save reference cargo hosted repository status=" + saved.status()
            + " body=" + bodyText(saved));
  }

  private static void ensureReferenceGroupRepository(CargoCompatConfig config) throws Exception {
    URI getUri = URI.create(config.nexusBaseUrl()
        + "/service/rest/v1/repositories/cargo/group/" + config.nexusGroupRepository());
    Exchange get = send(config.nexusAdminRequest(getUri).GET());
    assertTrue(get.status() == 200 || get.status() == 404,
        "reference cargo group repository lookup status=" + get.status()
            + " body=" + bodyText(get));
    String body = """
        {
          "name": "%s",
          "online": true,
          "storage": {
            "blobStoreName": "default",
            "strictContentTypeValidation": true
          },
          "group": {
            "memberNames": ["%s", "%s"]
          },
          "cargo": {
            "requireAuthentication": false
          }
        }
        """.formatted(
        config.nexusGroupRepository(),
        config.nexusHostedRepository(),
        config.nexusRepository());
    String path = get.status() == 200
        ? "/service/rest/v1/repositories/cargo/group/" + config.nexusGroupRepository()
        : "/service/rest/v1/repositories/cargo/group";
    HttpRequest.Builder request = config.nexusAdminRequest(URI.create(config.nexusBaseUrl() + path))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(30));
    Exchange saved = send(get.status() == 200
        ? request.PUT(HttpRequest.BodyPublishers.ofString(body))
        : request.POST(HttpRequest.BodyPublishers.ofString(body)));
    assertTrue(saved.status() >= 200 && saved.status() < 300,
        "save reference cargo group repository status=" + saved.status()
            + " body=" + bodyText(saved));
  }

  private static void ensureCandidateBlobStore(CargoCompatConfig config) throws Exception {
    String body = """
        {
          "name": "%s",
          "type": "file",
          "path": "%s"
        }
        """.formatted(config.blobStoreName(), config.blobStorePath());
    Exchange created = send(config.nexusPlusAdminRequest(URI.create(
            config.nexusPlusBaseUrl() + "/internal/blob-stores"))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)));
    assertTrue(created.status() == 200 || created.status() == 409,
        "ensure kkrepo blob store status=" + created.status()
            + " body=" + bodyText(created));
  }

  private static void ensureCandidateProxyRepository(CargoCompatConfig config) throws Exception {
    Exchange get = send(config.nexusPlusAdminRequest(URI.create(
            config.nexusPlusBaseUrl() + "/internal/repositories/" + config.nexusPlusRepository()))
        .timeout(Duration.ofSeconds(30))
        .GET());
    assertTrue(get.status() == 200 || get.status() == 404,
        "kkrepo cargo proxy repository lookup status=" + get.status()
            + " body=" + bodyText(get));
    String body = """
        {
          "name": "%s",
          "recipe": "cargo-proxy",
          "online": true,
          "blobStoreName": "%s",
          "strictContentTypeValidation": true,
          "proxy": {
            "remoteUrl": "%s",
            "contentMaxAgeMinutes": 1440,
            "metadataMaxAgeMinutes": 1440,
            "autoBlock": true
          },
          "cargo": {
            "requireAuthentication": false
          }
        }
        """.formatted(config.nexusPlusRepository(), config.blobStoreName(), config.remoteUrl());
    String path = get.status() == 200
        ? "/internal/repositories/" + config.nexusPlusRepository()
        : "/internal/repositories";
    HttpRequest.Builder request = config.nexusPlusAdminRequest(URI.create(config.nexusPlusBaseUrl() + path))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json");
    Exchange saved = send(get.status() == 200
        ? request.PUT(HttpRequest.BodyPublishers.ofString(body))
        : request.POST(HttpRequest.BodyPublishers.ofString(body)));
    assertTrue(saved.status() >= 200 && saved.status() < 300,
        "save kkrepo cargo proxy repository status=" + saved.status()
            + " body=" + bodyText(saved));
  }

  private static void ensureCandidateHostedRepository(CargoCompatConfig config) throws Exception {
    Exchange get = send(config.nexusPlusAdminRequest(URI.create(
            config.nexusPlusBaseUrl() + "/internal/repositories/" + config.nexusPlusHostedRepository()))
        .timeout(Duration.ofSeconds(30))
        .GET());
    assertTrue(get.status() == 200 || get.status() == 404,
        "kkrepo cargo hosted repository lookup status=" + get.status()
            + " body=" + bodyText(get));
    String body = """
        {
          "name": "%s",
          "recipe": "cargo-hosted",
          "online": true,
          "blobStoreName": "%s",
          "strictContentTypeValidation": true,
          "hosted": {
            "writePolicy": "ALLOW"
          }
        }
        """.formatted(config.nexusPlusHostedRepository(), config.blobStoreName());
    String path = get.status() == 200
        ? "/internal/repositories/" + config.nexusPlusHostedRepository()
        : "/internal/repositories";
    HttpRequest.Builder request = config.nexusPlusAdminRequest(URI.create(config.nexusPlusBaseUrl() + path))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json");
    Exchange saved = send(get.status() == 200
        ? request.PUT(HttpRequest.BodyPublishers.ofString(body))
        : request.POST(HttpRequest.BodyPublishers.ofString(body)));
    assertTrue(saved.status() >= 200 && saved.status() < 300,
        "save kkrepo cargo hosted repository status=" + saved.status()
            + " body=" + bodyText(saved));
  }

  private static void ensureCandidateGroupRepository(CargoCompatConfig config) throws Exception {
    Exchange get = send(config.nexusPlusAdminRequest(URI.create(
            config.nexusPlusBaseUrl() + "/internal/repositories/" + config.nexusPlusGroupRepository()))
        .timeout(Duration.ofSeconds(30))
        .GET());
    assertTrue(get.status() == 200 || get.status() == 404,
        "kkrepo cargo group repository lookup status=" + get.status()
            + " body=" + bodyText(get));
    String body = """
        {
          "name": "%s",
          "recipe": "cargo-group",
          "online": true,
          "blobStoreName": "%s",
          "strictContentTypeValidation": true,
          "group": {
            "memberNames": ["%s", "%s"]
          },
          "cargo": {
            "requireAuthentication": false
          }
        }
        """.formatted(
        config.nexusPlusGroupRepository(),
        config.blobStoreName(),
        config.nexusPlusHostedRepository(),
        config.nexusPlusRepository());
    String path = get.status() == 200
        ? "/internal/repositories/" + config.nexusPlusGroupRepository()
        : "/internal/repositories";
    HttpRequest.Builder request = config.nexusPlusAdminRequest(URI.create(config.nexusPlusBaseUrl() + path))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json");
    Exchange saved = send(get.status() == 200
        ? request.PUT(HttpRequest.BodyPublishers.ofString(body))
        : request.POST(HttpRequest.BodyPublishers.ofString(body)));
    assertTrue(saved.status() >= 200 && saved.status() < 300,
        "save kkrepo cargo group repository status=" + saved.status()
            + " body=" + bodyText(saved));
  }

  private static Exchange publish(Endpoint endpoint, CargoFixture fixture) throws Exception {
    return send(endpoint.repositoryRequest("api/v1/crates/new")
        .header("Content-Type", "application/octet-stream")
        .PUT(HttpRequest.BodyPublishers.ofByteArray(fixture.publishBody())));
  }

  private static Exchange yank(Endpoint endpoint, CargoFixture fixture) throws Exception {
    return send(endpoint.repositoryRequest(
            "api/v1/crates/" + fixture.name() + "/" + fixture.version() + "/yank")
        .DELETE());
  }

  private static Exchange unyank(Endpoint endpoint, CargoFixture fixture) throws Exception {
    return send(endpoint.repositoryRequest(
            "api/v1/crates/" + fixture.name() + "/" + fixture.version() + "/unyank")
        .PUT(HttpRequest.BodyPublishers.noBody()));
  }

  private static void assertSuccessfulWrite(String label, Exchange exchange) {
    assertEquals(200, exchange.status(), label + " status body=" + bodyText(exchange));
  }

  private static void assertRepositoryRemoteUrl(String label, Map<String, Object> repository, String expected) {
    @SuppressWarnings("unchecked")
    Map<String, Object> proxy = (Map<String, Object>) repository.get("proxy");
    assertEquals(expected, String.valueOf(proxy.get("remoteUrl")), label + " proxy remoteUrl");
  }

  private static void assertConfigDownloadEndpoint(String label, Map<String, Object> config, Endpoint endpoint) {
    String dl = text(config.get("dl"));
    assertFalse(dl.isBlank(), label + " config.json dl");
    assertTrue(dl.startsWith(endpoint.baseUrl() + "/repository/" + endpoint.repository()),
        label + " config.json dl should point at the tested repository: " + dl);
    assertEquals(endpoint.baseUrl() + "/repository/" + endpoint.repository() + "/crates",
        dl, label + " config.json dl");
  }

  private static void assertSameBody(String label, Exchange reference, Exchange candidate) {
    assertEquals(reference.status(), candidate.status(), label + " status");
    assertEquals(200, candidate.status(), label + " should be OK");
    assertArrayEquals(reference.body(), candidate.body(), label + " body");
    assertEquals(sha256Hex(reference.body()), sha256Hex(candidate.body()), label + " sha256");
    if (reference.contentLength().isPresent() && candidate.contentLength().isPresent()) {
      assertEquals(reference.contentLength().get(), candidate.contentLength().get(), label + " Content-Length");
    }
  }

  private static void assertSameIndexEntry(
      String label,
      Exchange reference,
      Exchange candidate,
      String crateName,
      String version) throws Exception {
    assertEquals(reference.status(), candidate.status(), label + " status");
    assertEquals(200, candidate.status(), label + " should be OK");
    assertIndexContainsVersion("Nexus", reference, crateName, version);
    assertIndexContainsVersion("kkrepo", candidate, crateName, version);
    assertEquals(indexEntry(reference, version), indexEntry(candidate, version), label + " selected version entry");
  }

  private static void assertSameHostedIndexSemantics(
      String label,
      Exchange reference,
      Exchange candidate,
      CargoFixture fixture) throws Exception {
    assertEquals(reference.status(), candidate.status(), label + " status");
    assertEquals(200, candidate.status(), label + " should be OK");
    Map<String, Object> referenceEntry = indexEntry(reference, fixture.version());
    Map<String, Object> candidateEntry = indexEntry(candidate, fixture.version());
    assertEquals(fixture.name(), text(referenceEntry.get("name")), label + " Nexus name");
    assertEquals(fixture.name(), text(candidateEntry.get("name")), label + " kkrepo name");
    assertEquals(fixture.version(), text(referenceEntry.get("vers")), label + " Nexus version");
    assertEquals(fixture.version(), text(candidateEntry.get("vers")), label + " kkrepo version");
    assertEquals(referenceEntry.get("yanked"), candidateEntry.get("yanked"), label + " yanked");
    assertEquals(fixture.crateSha256(), text(referenceEntry.get("cksum")), label + " Nexus checksum");
    assertEquals(fixture.crateSha256(), text(candidateEntry.get("cksum")), label + " kkrepo checksum");
    assertEquals(indexResolutionFields(referenceEntry), indexResolutionFields(candidateEntry),
        label + " Cargo resolution fields");
  }

  private static Map<String, Object> indexResolutionFields(Map<String, Object> entry) {
    Map<String, Object> fields = new LinkedHashMap<>();
    for (String key : List.of("deps", "features", "features2", "v", "links", "rust_version")) {
      if (entry.containsKey(key)) {
        fields.put(key, entry.get(key));
      }
    }
    return fields;
  }

  private static void assertSameRemoteIndexSemantics(
      String label,
      Exchange reference,
      Exchange candidate,
      String crateName,
      String version) throws Exception {
    assertEquals(reference.status(), candidate.status(), label + " status");
    assertEquals(200, candidate.status(), label + " should be OK");
    Map<String, Object> referenceEntry = indexEntry(reference, version);
    Map<String, Object> candidateEntry = indexEntry(candidate, version);
    assertEquals(crateName, text(referenceEntry.get("name")), label + " Nexus name");
    assertEquals(crateName, text(candidateEntry.get("name")), label + " kkrepo name");
    assertEquals(version, text(referenceEntry.get("vers")), label + " Nexus version");
    assertEquals(version, text(candidateEntry.get("vers")), label + " kkrepo version");
    assertEquals(referenceEntry.get("yanked"), candidateEntry.get("yanked"), label + " yanked");
    assertEquals(text(referenceEntry.get("cksum")), text(candidateEntry.get("cksum")), label + " checksum");
  }

  private static void assertSameStatusAndLength(String label, Exchange reference, Exchange candidate) {
    assertEquals(reference.status(), candidate.status(), label + " status");
    assertEquals(200, candidate.status(), label + " should be OK");
    if (reference.contentLength().isPresent() && candidate.contentLength().isPresent()) {
      assertEquals(reference.contentLength().get(), candidate.contentLength().get(), label + " Content-Length");
    }
  }

  private static void assertIndexContainsVersion(
      String label, Exchange index, String crateName, String version) {
    String body = bodyText(index);
    assertTrue(body.contains("\"name\":\"" + crateName + "\""), label + " index crate name");
    assertTrue(body.contains("\"vers\":\"" + version + "\""), label + " index version " + version);
  }

  private static Exchange eventuallyIndex(
      Endpoint endpoint,
      String crateName,
      String version,
      boolean yanked) throws Exception {
    String indexPath = CargoIndexPath.forCrate(crateName);
    Exchange last = null;
    for (int attempt = 0; attempt < 30; attempt++) {
      last = send(endpoint.request(indexPath, "GET"));
      if (last.status() == 200) {
        try {
          Map<String, Object> entry = indexEntry(last, version);
          if (Boolean.TRUE.equals(entry.get("yanked")) == yanked) {
            return last;
          }
        } catch (AssertionError ignored) {
          // Keep polling until the just-written version appears.
        }
      }
      Thread.sleep(500);
    }
    throw new AssertionError(
        "Cargo index did not expose " + crateName + " " + version + " yanked=" + yanked
            + "; last status=" + (last == null ? "<none>" : last.status())
            + " body=" + (last == null ? "" : bodyText(last)));
  }

  private static String indexChecksum(Exchange index, String version) throws Exception {
    return text(indexEntry(index, version).get("cksum"));
  }

  private static Map<String, Object> indexEntry(Exchange index, String version) throws Exception {
    for (String line : bodyText(index).split("\\R")) {
      if (line.contains("\"vers\":\"" + version + "\"")) {
        return JSON.readValue(line, JSON_MAP);
      }
    }
    throw new AssertionError("index entry not found for version " + version);
  }

  private static String downloadUrl(Map<String, Object> config, String crateName, String version) {
    String dl = text(config.get("dl"));
    if (dl.contains("{crate}") || dl.contains("{version}")) {
      return dl.replace("{crate}", crateName).replace("{version}", version);
    }
    return stripTrailingSlash(dl) + "/" + crateName + "/" + version + "/download";
  }

  private static Map<String, Object> json(Exchange exchange) throws Exception {
    return JSON.readValue(exchange.body(), JSON_MAP);
  }

  private static Exchange send(HttpRequest.Builder builder) throws Exception {
    HttpResponse<byte[]> response = HTTP.send(
        builder.header("User-Agent", "kkrepo-cargo-compat-test/1")
            .timeout(Duration.ofSeconds(90))
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
    return new Exchange(
        response.statusCode(),
        response.body(),
        response.headers().map());
  }

  private static Exchange conditionalGet(Endpoint endpoint, String path, Exchange original) throws Exception {
    Optional<String> etag = original.etag();
    Optional<String> lastModified = original.lastModified();
    assertTrue(etag.isPresent() || lastModified.isPresent(),
        endpoint.repository() + " index response should expose ETag or Last-Modified");
    HttpRequest.Builder request = endpoint.repositoryRequest(path);
    if (etag.isPresent()) {
      request.header("If-None-Match", etag.get());
    } else {
      request.header("If-Modified-Since", lastModified.get());
    }
    return send(request.GET());
  }

  private static String bodyText(Exchange exchange) {
    return new String(exchange.body(), StandardCharsets.UTF_8);
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 digest is unavailable", e);
    }
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static String ensureTrailingSlash(String value) {
    return stripTrailingSlash(value) + "/";
  }

  private static String stripTrailingSlash(String value) {
    String result = value;
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }

  private record Exchange(
      int status,
      byte[] body,
      Map<String, List<String>> headers) {

    Optional<String> contentType() {
      return firstHeader("content-type");
    }

    Optional<String> contentLength() {
      return firstHeader("content-length");
    }

    Optional<String> etag() {
      return firstHeader("etag");
    }

    Optional<String> lastModified() {
      return firstHeader("last-modified");
    }

    private Optional<String> firstHeader(String name) {
      return headers.entrySet().stream()
          .filter(entry -> entry.getKey().equalsIgnoreCase(name))
          .flatMap(entry -> entry.getValue().stream())
          .findFirst();
    }
  }

  private record Endpoint(
      String baseUrl,
      String repository,
      Optional<String> username,
      Optional<String> password) {
    HttpRequest.Builder request(String path, String method) {
      HttpRequest.Builder builder = repositoryRequest(path);
      return "HEAD".equals(method)
          ? builder.method("HEAD", HttpRequest.BodyPublishers.noBody())
          : builder.GET();
    }

    HttpRequest.Builder repositoryRequest(String path) {
      return authenticatedBuilder(baseUrl + "/repository/" + repository + "/" + path);
    }

    HttpRequest.Builder absoluteRequest(String url, String method) {
      HttpRequest.Builder builder = authenticatedBuilder(url);
      return "HEAD".equals(method)
          ? builder.method("HEAD", HttpRequest.BodyPublishers.noBody())
          : builder.GET();
    }

    private HttpRequest.Builder authenticatedBuilder(String url) {
      HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
      if (username.isPresent() && password.isPresent()) {
        String token = Base64.getEncoder().encodeToString(
            (username.get() + ":" + password.get()).getBytes(StandardCharsets.UTF_8));
        builder.header("Authorization", "Basic " + token);
      }
      return builder;
    }
  }

  private record CargoFixture(
      String name,
      String version,
      byte[] crate,
      byte[] publishBody,
      String crateSha256) {
    static CargoFixture create() throws IOException {
      String name = "kkrepo_compat_" + Long.toUnsignedString(System.nanoTime(), 36).toLowerCase();
      String version = "0.1.0";
      byte[] crate = crateArchive(name, version);
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("name", name);
      metadata.put("vers", version);
      metadata.put("deps", List.of());
      metadata.put("features", Map.of());
      // Keep the live publish fixture limited to fields Nexus 3.77 accepts.
      // Optional index fields are still compared when Nexus returns them and are
      // covered by CargoPublishPayloadTest for kkrepo-generated entries.
      metadata.put("description", "kkrepo Cargo compatibility fixture");
      byte[] publishBody = publishBody(metadata, crate);
      return new CargoFixture(name, version, crate, publishBody, sha256Hex(crate));
    }

    private static byte[] publishBody(Map<String, Object> metadata, byte[] crate) throws IOException {
      byte[] json = JSON.writeValueAsBytes(metadata);
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      writeU32Le(body, json.length);
      body.write(json);
      writeU32Le(body, crate.length);
      body.write(crate);
      return body.toByteArray();
    }

    private static byte[] crateArchive(String name, String version) throws IOException {
      String dir = name + "-" + version + "/";
      byte[] manifest = ("[package]\n"
          + "name = \"" + name + "\"\n"
          + "version = \"" + version + "\"\n"
          + "edition = \"2021\"\n").getBytes(StandardCharsets.UTF_8);
      byte[] lib = "pub fn answer() -> u32 { 42 }\n".getBytes(StandardCharsets.UTF_8);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes);
          TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
        addTarEntry(tar, dir + "Cargo.toml", manifest);
        addTarEntry(tar, dir + "src/lib.rs", lib);
        tar.finish();
      }
      return bytes.toByteArray();
    }

    private static void addTarEntry(TarArchiveOutputStream tar, String path, byte[] content) throws IOException {
      TarArchiveEntry entry = new TarArchiveEntry(path);
      entry.setSize(content.length);
      tar.putArchiveEntry(entry);
      tar.write(content);
      tar.closeArchiveEntry();
    }

    private static void writeU32Le(ByteArrayOutputStream out, int value) {
      out.write(value & 0xff);
      out.write((value >>> 8) & 0xff);
      out.write((value >>> 16) & 0xff);
      out.write((value >>> 24) & 0xff);
    }
  }

  private record CargoCompatConfig(
      boolean enabled,
      String nexusBaseUrl,
      String nexusRepository,
      String nexusUsername,
      String nexusPassword,
      String nexusPlusBaseUrl,
      Optional<String> nexusPlusUsername,
      Optional<String> nexusPlusPassword,
      String nexusPlusRepository,
      String nexusHostedRepository,
      String nexusPlusHostedRepository,
      String nexusGroupRepository,
      String nexusPlusGroupRepository,
      String remoteUrl,
      String crateName,
      String version,
      String blobStoreName,
      String blobStorePath) {
    static CargoCompatConfig load() {
      return new CargoCompatConfig(
          Boolean.parseBoolean(setting("compat.cargo.enabled", "CARGO_COMPAT_ENABLED").orElse("false")),
          stripTrailingSlash(setting("compat.cargo.nexus.baseUrl", "CARGO_NEXUS_COMPAT_BASE_URL")
              .or(() -> setting("compat.nexus.baseUrl", "NEXUS_COMPAT_BASE_URL"))
              .orElse(CompatDefaults.NEXUS_BASE_URL)),
          setting("compat.cargo.nexus.repository", "CARGO_NEXUS_COMPAT_REPOSITORY")
              .orElse("cargo-proxy-compat"),
          setting("compat.cargo.nexus.username", "CARGO_NEXUS_COMPAT_USERNAME")
              .or(() -> setting("compat.nexus.username", "NEXUS_COMPAT_USERNAME"))
              .orElse(CompatDefaults.NEXUS_USERNAME),
          setting("compat.cargo.nexus.password", "CARGO_NEXUS_COMPAT_PASSWORD")
              .or(() -> setting("compat.nexus.password", "NEXUS_COMPAT_PASSWORD"))
              .orElse(CompatDefaults.NEXUS_PASSWORD),
          stripTrailingSlash(setting("compat.cargo.nexusPlus.baseUrl", "CARGO_KKREPO_COMPAT_BASE_URL")
              .or(() -> setting("compat.nexusPlus.baseUrl", "KKREPO_COMPAT_BASE_URL"))
              .orElse(CompatDefaults.KKREPO_BASE_URL)),
          setting("compat.cargo.nexusPlus.username", "CARGO_KKREPO_COMPAT_USERNAME")
              .or(() -> setting("compat.nexusPlus.username", "KKREPO_COMPAT_USERNAME"))
              .or(CompatDefaults::nexusPlusUsername),
          setting("compat.cargo.nexusPlus.password", "CARGO_KKREPO_COMPAT_PASSWORD")
              .or(() -> setting("compat.nexusPlus.password", "KKREPO_COMPAT_PASSWORD"))
              .or(CompatDefaults::nexusPlusPassword),
          setting("compat.cargo.nexusPlus.repository", "CARGO_KKREPO_COMPAT_REPOSITORY")
              .orElse("cargo-proxy-compat"),
          setting("compat.cargo.nexus.hostedRepository", "CARGO_NEXUS_HOSTED_REPOSITORY")
              .orElse("cargo-hosted-compat"),
          setting("compat.cargo.nexusPlus.hostedRepository", "CARGO_KKREPO_HOSTED_REPOSITORY")
              .orElse("cargo-hosted-compat"),
          setting("compat.cargo.nexus.groupRepository", "CARGO_NEXUS_GROUP_REPOSITORY")
              .orElse("cargo-group-compat"),
          setting("compat.cargo.nexusPlus.groupRepository", "CARGO_KKREPO_GROUP_REPOSITORY")
              .orElse("cargo-group-compat"),
          ensureTrailingSlash(setting("compat.cargo.remoteUrl", "CARGO_COMPAT_REMOTE_URL")
              .orElse("https://index.crates.io/")),
          setting("compat.cargo.crate", "CARGO_COMPAT_CRATE").orElse("itoa"),
          setting("compat.cargo.version", "CARGO_COMPAT_VERSION").orElse("1.0.15"),
          setting("compat.cargo.nexusPlus.blobStoreName", "CARGO_KKREPO_BLOB_STORE")
              .orElse("default"),
          setting("compat.cargo.nexusPlus.blobStorePath", "CARGO_KKREPO_BLOB_PATH")
              .orElse("/tmp/kkrepo-blobs/default"));
    }

    Endpoint referenceEndpoint() {
      return new Endpoint(nexusBaseUrl, nexusRepository,
          Optional.of(nexusUsername), Optional.of(nexusPassword));
    }

    Endpoint candidateEndpoint() {
      return new Endpoint(nexusPlusBaseUrl, nexusPlusRepository, nexusPlusUsername, nexusPlusPassword);
    }

    Endpoint referenceHostedEndpoint() {
      return new Endpoint(nexusBaseUrl, nexusHostedRepository,
          Optional.of(nexusUsername), Optional.of(nexusPassword));
    }

    Endpoint candidateHostedEndpoint() {
      return new Endpoint(nexusPlusBaseUrl, nexusPlusHostedRepository, nexusPlusUsername, nexusPlusPassword);
    }

    Endpoint referenceGroupEndpoint() {
      return new Endpoint(nexusBaseUrl, nexusGroupRepository,
          Optional.of(nexusUsername), Optional.of(nexusPassword));
    }

    Endpoint candidateGroupEndpoint() {
      return new Endpoint(nexusPlusBaseUrl, nexusPlusGroupRepository, nexusPlusUsername, nexusPlusPassword);
    }

    Map<String, Object> referenceRepositoryConfig() throws Exception {
      return json(send(nexusAdminRequest(URI.create(
          nexusBaseUrl + "/service/rest/v1/repositories/cargo/proxy/" + nexusRepository)).GET()));
    }

    Map<String, Object> candidateRepositoryConfig() throws Exception {
      return json(send(nexusPlusAdminRequest(URI.create(
          nexusPlusBaseUrl + "/internal/repositories/" + nexusPlusRepository)).GET()));
    }

    boolean candidateManagementConfigured() {
      return nexusPlusUsername.isPresent() && nexusPlusPassword.isPresent();
    }

    HttpRequest.Builder nexusAdminRequest(URI uri) {
      String token = Base64.getEncoder().encodeToString(
          (nexusUsername + ":" + nexusPassword).getBytes(StandardCharsets.UTF_8));
      return HttpRequest.newBuilder(uri)
          .timeout(Duration.ofSeconds(30))
          .header("Authorization", "Basic " + token);
    }

    HttpRequest.Builder nexusPlusAdminRequest(URI uri) {
      HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30));
      if (nexusPlusUsername.isPresent() && nexusPlusPassword.isPresent()) {
        String token = Base64.getEncoder().encodeToString(
            (nexusPlusUsername.get() + ":" + nexusPlusPassword.get()).getBytes(StandardCharsets.UTF_8));
        builder.header("Authorization", "Basic " + token);
      }
      return builder;
    }

    boolean referenceReachable() {
      try {
        Exchange status = send(nexusAdminRequest(URI.create(nexusBaseUrl + "/service/rest/v1/status")).GET());
        return status.status() >= 200 && status.status() < 300;
      } catch (Exception e) {
        return false;
      }
    }

    boolean candidateReachable() {
      try {
        Exchange status = send(HttpRequest.newBuilder(URI.create(nexusPlusBaseUrl + "/")).GET());
        return status.status() >= 200 && status.status() < 400;
      } catch (Exception e) {
        return false;
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
}
