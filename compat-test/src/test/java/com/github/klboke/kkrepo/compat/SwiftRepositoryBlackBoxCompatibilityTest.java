package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.klboke.kkrepo.protocol.swift.SwiftVersions;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Security;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.util.CollectionStore;
import org.bouncycastle.util.Store;
import org.junit.jupiter.api.Test;

/**
 * Black-box contract for the Swift Package Registry v1 API.
 *
 * <p>The suite is intentionally opt-in because it creates repositories and immutable package
 * releases in both Nexus 3.94+ and kkrepo. Enable it with {@code SWIFT_COMPAT_ENABLED=true}. Tests
 * that need GitHub or a second kkrepo replica have their own explicit switches so an unavailable
 * external dependency is reported as skipped rather than mistaken for a successful assertion.
 */
class SwiftRepositoryBlackBoxCompatibilityTest {
  private static final String JSON_ACCEPT = "application/vnd.swift.registry.v1+json";
  private static final String SWIFT_ACCEPT = "application/vnd.swift.registry.v1+swift";
  private static final String ZIP_ACCEPT = "application/vnd.swift.registry.v1+zip";
  private static final Pattern LINK_RELATION = Pattern.compile(
      "(?i)(?:^|;)\\s*rel\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s,]+))");
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void generatedFixturesContainDefaultAndVersionedManifestsAndAValidDetachedCmsSignature()
      throws Exception {
    Fixture fixture = Fixture.signed("fixture-validation", "1.2.3", "fixture-validation");
    List<String> entries = new ArrayList<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(fixture.archive()))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        entries.add(entry.getName());
      }
    }
    assertTrue(entries.stream().anyMatch(value -> value.endsWith("/Package.swift")));
    assertTrue(entries.stream().anyMatch(value -> value.endsWith("/Package@swift-5.9.swift")));
    assertTrue(entries.stream().anyMatch(value -> value.contains("/Sources/")));
    CMSSignedData signedData = new CMSSignedData(
        new CMSProcessableByteArray(fixture.archive()), fixture.signature());
    assertEquals(1, signedData.getSignerInfos().size(), "detached CMS signer count");
    assertFalse(signedData.getCertificates().getMatches(null).isEmpty(), "CMS certificate chain");
  }

  @Test
  void successJsonComparisonNormalizesOnlyRegistryOriginAndPublishedAt() throws Exception {
    Exchange reference = successJsonExchange(
        """
        {"id":"kkrepo.fixture","version":"1.2.3",
         "url":"/kkrepo/fixture/1.2.3",
         "metadata":{"repositoryURLs":["https://github.com/kkrepo/fixture.git"]},
         "publishedAt":"2026-07-16T01:02:03Z"}
        """,
        "<https://nexus.example/nexus/repository/swift-hosted/kkrepo/fixture/1.2.3>; "
            + "rel=\"latest-version\", <https://github.com/kkrepo/fixture.git>; rel=canonical");
    Exchange candidate = successJsonExchange(
        """
        {"version":"1.2.3","id":"kkrepo.fixture",
         "url":"http://kkrepo:8080/repository/swift-hosted/kkrepo/fixture/1.2.3",
         "publishedAt":"2026-07-16T01:02:04.123456Z",
         "metadata":{"repositoryURLs":["https://github.com/kkrepo/fixture.git"]}}
        """,
        "<https://github.com/kkrepo/fixture.git>; rel=\"canonical\", "
            + "<http://kkrepo:8080/repository/swift-hosted/kkrepo/fixture/1.2.3>; "
            + "rel=latest-version");

    assertSameSuccessJson("canonical success", reference, candidate);
  }

  @Test
  void successJsonComparisonIgnoresInvalidNexusGitRefPseudoVersions() throws Exception {
    Exchange reference = successJsonExchange(
        """
        {"releases":{"1.0.0":{"url":"/apple/swift-log/1.0.0"},
         "1.0.0^{}":{"url":"/apple/swift-log/1.0.0^{}"}}}
        """,
        "");
    Exchange candidate = successJsonExchange(
        """
        {"releases":{"1.0.0":{"url":
         "http://kkrepo:8080/repository/swift-proxy/apple/swift-log/1.0.0"}}}
        """,
        "");

    assertSameSuccessJson("invalid Nexus Git pseudo-version", reference, candidate);
  }

  @Test
  void successJsonComparisonAllowsOfficialReleaseMetadataFieldsMissingFromNexus() throws Exception {
    Exchange reference = successJsonExchange(
        """
        {"id":"kkrepo.fixture","version":"1.2.3","resources":[]}
        """,
        "");
    Exchange candidate = successJsonExchange(
        """
        {"id":"kkrepo.fixture","version":"1.2.3","resources":[],
         "metadata":{"description":"fixture"},"publishedAt":"2026-07-16T01:02:03Z"}
        """,
        "<https://github.com/kkrepo/fixture>; rel=canonical, "
            + "<http://kkrepo:8080/repository/swift-hosted/kkrepo/fixture/1.2.3>; "
            + "rel=latest-version");

    assertSameSuccessJson("Nexus release metadata extensions", reference, candidate);
  }

  @Test
  void successJsonComparisonStillRejectsSemanticJsonAndLinkDifferences() throws Exception {
    Exchange reference = successJsonExchange(
        """
        {"releases":{"1.2.3":{"url":
         "https://nexus.example/repository/swift-hosted/kkrepo/fixture/1.2.3"}}}
        """,
        "<https://nexus.example/repository/swift-hosted/kkrepo/fixture/1.2.3>; "
            + "rel=\"latest-version\"");
    Exchange wrongJson = successJsonExchange(
        """
        {"releases":{"1.2.4":{"url":
         "http://kkrepo:8080/repository/swift-hosted/kkrepo/fixture/1.2.4"}}}
        """,
        "<http://kkrepo:8080/repository/swift-hosted/kkrepo/fixture/1.2.3>; "
            + "rel=\"latest-version\"");
    Exchange wrongLink = successJsonExchange(
        """
        {"releases":{"1.2.3":{"url":
         "http://kkrepo:8080/repository/swift-hosted/kkrepo/fixture/1.2.3"}}}
        """,
        "<http://kkrepo:8080/repository/swift-hosted/kkrepo/fixture/1.2.3>; "
            + "rel=\"predecessor-version\"");

    assertThrows(AssertionError.class,
        () -> assertSameSuccessJson("semantic JSON", reference, wrongJson));
    assertThrows(AssertionError.class,
        () -> assertSameSuccessJson("semantic Link", reference, wrongLink));
  }

  @Test
  void hostedPublishReadHeadCacheRangeAndIdentifierContractsMatchNexusWhenConfigured()
      throws Exception {
    Config config = configured();
    ensureRepositories(config);
    Fixture fixture = Fixture.unsigned(uniqueName("hosted"), "1.2.3", "primary");

    Exchange nexusPublish = publish(config.nexusHosted(), fixture, false);
    Exchange candidatePublish = publish(config.candidateHosted(), fixture, false);
    assertEquals(201, nexusPublish.status(), "Nexus synchronous publish status");
    assertEquals(nexusPublish.status(), candidatePublish.status(), "hosted publish status");
    assertEquals("1", candidatePublish.header("content-version"), "publish Content-Version");
    assertTrue(candidatePublish.header("location").contains(
        "/repository/" + config.hosted() + "/" + fixture.coordinatePath()),
        "publish Location should stay on the hosted repository base");

    assertPackageReads(config.nexusHosted(), config.candidateHosted(), fixture);
    assertPackageReads(config.nexusGroup(), config.candidateGroup(), fixture);
    assertHeadContracts(config.nexusHosted(), config.candidateHosted(), fixture);
    assertArchiveHttpSemantics(config.nexusHosted(), config.candidateHosted(), fixture);
    assertIdentifierVariants(config.nexusHosted(), config.candidateHosted(), fixture);

    Exchange duplicateReference = publish(config.nexusHosted(), fixture, false);
    Exchange duplicateCandidate = publish(config.candidateHosted(), fixture, false);
    assertEquals(409, duplicateReference.status(), "Nexus immutable release conflict");
    assertEquals(duplicateReference.status(), duplicateCandidate.status(), "duplicate publish status");
    assertProblem("candidate duplicate publish", duplicateCandidate);

    Exchange groupPublish = publish(config.candidateGroup(),
        Fixture.unsigned(uniqueName("groupput"), "1.0.0", "readonly"), false);
    Exchange proxyPublish = publish(config.candidateProxy(),
        Fixture.unsigned(uniqueName("proxyput"), "1.0.0", "readonly"), false);
    assertEquals(405, groupPublish.status(), "Swift group must remain read-only");
    assertEquals(405, proxyPublish.status(), "Swift proxy must remain read-only");
    assertProblem("candidate group publish", groupPublish);
    assertProblem("candidate proxy publish", proxyPublish);
  }

  @Test
  void signedHostedReleaseReturnsTheSameCmsSignatureInMetadataAndArchiveWhenConfigured()
      throws Exception {
    Config config = configured();
    ensureRepositories(config);
    Fixture fixture = Fixture.signed(uniqueName("signed"), "2.0.0", "signed");

    Exchange nexusPublish = publish(config.nexusHosted(), fixture, false);
    Exchange candidatePublish = publish(config.candidateHosted(), fixture, false);
    assertEquals(201, nexusPublish.status(), "Nexus signed publish status");
    assertEquals(nexusPublish.status(), candidatePublish.status(), "signed publish status");

    String path = fixture.coordinatePath();
    Exchange nexusMetadata = get(config.nexusHosted(), path, JSON_ACCEPT);
    Exchange candidateMetadata = get(config.candidateHosted(), path, JSON_ACCEPT);
    assertEquals(nexusMetadata.status(), candidateMetadata.status(), "signed metadata status");
    assertEquals(200, candidateMetadata.status(), "candidate signed metadata status");
    JsonNode candidateResource = sourceArchiveResource(json(candidateMetadata));
    assertEquals("cms-1.0.0", candidateResource.path("signing").path("signatureFormat").asText());
    assertEquals(Base64.getEncoder().encodeToString(fixture.signature()),
        candidateResource.path("signing").path("signatureBase64Encoded").asText());

    Exchange nexusArchive = get(config.nexusHosted(), fixture.archivePath(), ZIP_ACCEPT);
    Exchange candidateArchive = get(config.candidateHosted(), fixture.archivePath(), ZIP_ACCEPT);
    assertArrayEquals(fixture.archive(), nexusArchive.body(), "Nexus signed archive bytes");
    assertArrayEquals(fixture.archive(), candidateArchive.body(), "candidate signed archive bytes");
    assertEquals("cms-1.0.0", candidateArchive.header("x-swift-package-signature-format"));
    assertEquals(Base64.getEncoder().encodeToString(fixture.signature()),
        candidateArchive.header("x-swift-package-signature"));
  }

  @Test
  void hostedPublishDecodesBase64ArchiveAndQuotedPrintableMetadataWhenConfigured()
      throws Exception {
    Config config = configured();
    ensureRepositories(config);
    Fixture fixture = Fixture.unsigned(uniqueName("transferencoding"), "1.0.0", "encoded");

    Exchange published = publish(
        config.candidateHosted(), fixture, false, "base64", "quoted-printable");

    assertEquals(201, published.status(), "encoded multipart publish status");
    Exchange archive = get(config.candidateHosted(), fixture.archivePath(), ZIP_ACCEPT);
    assertEquals(200, archive.status());
    assertArrayEquals(fixture.archive(), archive.body(),
        "Content-Transfer-Encoding must be decoded before checksum and persistence");
    Exchange metadata = get(
        config.candidateHosted(), fixture.coordinatePath(), JSON_ACCEPT);
    assertEquals(sha256Hex(fixture.archive()), sourceChecksum(json(metadata)));
  }

  @Test
  void administrativelyDeletedReleaseRemainsListedAsUnavailableWhenConfigured()
      throws Exception {
    Config config = configured();
    ensureRepositories(config);
    Fixture fixture = Fixture.unsigned(uniqueName("unavailable"), "3.0.0", "deleted");
    assertEquals(201, publish(config.candidateHosted(), fixture, false).status());

    String deleteUrl = config.candidateBase() + "/internal/browse/" + encode(config.hosted())
        + "?path=" + encode(fixture.archivePath());
    Exchange deleted = send(HttpRequest.newBuilder(URI.create(deleteUrl))
        .header("Authorization", config.candidateAuth())
        .header("Accept", "application/json")
        .DELETE());
    assert2xx("delete candidate Swift release", deleted);

    Exchange releaseList = get(
        config.candidateHosted(), fixture.scope() + "/" + fixture.name(), JSON_ACCEPT);
    assertEquals(200, releaseList.status());
    JsonNode listed = json(releaseList).path("releases").path(fixture.version());
    assertTrue(listed.has("problem"), "deleted release must remain visible as unavailable");
    assertEquals(410, listed.path("problem").path("status").asInt());
    assertFalse(listed.has("url"), "unavailable release must not advertise a download URL");
    assertEquals(404,
        get(config.candidateHosted(), fixture.coordinatePath(), JSON_ACCEPT).status(),
        "deleted release metadata must not remain readable");
  }

  @Test
  void mediaNegotiationIdentityValidationAndProblemDetailsMatchNexusWhenConfigured()
      throws Exception {
    Config config = configured();
    ensureRepositories(config);
    Endpoint reference = config.nexusHosted();
    Endpoint candidate = config.candidateHosted();
    Fixture semanticVersion = Fixture.unsigned(
        uniqueName("semver"), "1.0.0-beta.1+build.5", "semantic-version");
    assertEquals(201, publish(reference, semanticVersion, false).status());
    Exchange candidatePublish = publish(candidate, semanticVersion, false);
    assertEquals(
        201,
        candidatePublish.status(),
        () -> "candidate semantic-version publish response: " + candidatePublish.text());

    Exchange nexusNoAccept = getWithoutAccept(reference, semanticVersion.coordinatePath());
    Exchange candidateNoAccept = getWithoutAccept(candidate, semanticVersion.coordinatePath());
    assertEquals(nexusNoAccept.status(), candidateNoAccept.status(), "missing Accept status");
    if (candidateNoAccept.status() == 200) {
      assertContentType(candidateNoAccept, "application/json", "missing Accept Content-Type");
      assertEquals("1", candidateNoAccept.header("content-version"));
    } else {
      assertEquals(400, candidateNoAccept.status(), "missing Accept is either v1 or bad request");
      assertProblem("candidate missing Accept", candidateNoAccept);
    }
    Exchange nexusUnversioned = get(
        reference, semanticVersion.coordinatePath(), "application/vnd.swift.registry+json");
    Exchange candidateUnversioned = get(
        candidate, semanticVersion.coordinatePath(), "application/vnd.swift.registry+json");
    assertSameSuccessJson("unversioned v1 media type", nexusUnversioned, candidateUnversioned);
    Exchange candidateCaseInsensitive = get(candidate,
        semanticVersion.scope().toUpperCase(Locale.ROOT) + "/"
            + semanticVersion.name().toUpperCase(Locale.ROOT) + "/" + semanticVersion.version(),
        JSON_ACCEPT);
    assertEquals(200, candidateCaseInsensitive.status(), "scope and name are case-insensitive");
    assertEquals(sourceChecksum(json(candidateUnversioned)),
        sourceChecksum(json(candidateCaseInsensitive)));

    List<ErrorRequestCase> cases = List.of(
        new ErrorRequestCase("missing package", "missing/package", JSON_ACCEPT, 404, 404),
        // Nexus resolves these requests before applying the corresponding v1 validation rules.
        new ErrorRequestCase("missing identifiers URL", "identifiers", JSON_ACCEPT, 404, 400),
        new ErrorRequestCase("invalid scope", "-invalid/package", JSON_ACCEPT, 404, 404),
        new ErrorRequestCase("invalid package", "valid/_invalid", JSON_ACCEPT, 404, 404),
        new ErrorRequestCase("encoded slash", "valid/package%2Fescape", JSON_ACCEPT, 404, 404),
        new ErrorRequestCase("dot segment", "valid/%2e%2e", JSON_ACCEPT, 200, 404),
        new ErrorRequestCase(
            "invalid SemVer", "valid/package/not-a-version", JSON_ACCEPT, 404, 404),
        new ErrorRequestCase("invalid API version", "missing/package",
            "application/vnd.swift.registry.vbogus+json", 404, 400),
        new ErrorRequestCase("unsupported API version", "missing/package",
            "application/vnd.swift.registry.v2+json", 404, 415),
        new ErrorRequestCase(
            "wrong representation", "missing/package", "application/xml", 404, 415));

    for (ErrorRequestCase requestCase : cases) {
      Exchange nexus = get(reference, requestCase.path(), requestCase.accept());
      Exchange kkrepo = get(candidate, requestCase.path(), requestCase.accept());
      assertEquals(requestCase.nexusStatus(), nexus.status(),
          requestCase.label() + " Nexus reference status");
      assertEquals(requestCase.candidateStatus(), kkrepo.status(),
          requestCase.label() + " candidate protocol status");
      assertProblem("candidate " + requestCase.label(), kkrepo);
    }

    Fixture invalid = Fixture.withArchive(uniqueName("invalidzip"), "1.0.0",
        "not a ZIP".getBytes(StandardCharsets.UTF_8), null, "{}");
    Exchange nexusInvalid = publish(reference, invalid, true);
    Exchange candidateInvalid = publish(candidate, invalid, true);
    assertEquals(400, nexusInvalid.status(), "invalid archive Nexus reference status");
    assertEquals(422, candidateInvalid.status(), "invalid archive candidate protocol status");
    assertProblem("candidate invalid archive", candidateInvalid);
  }

  @Test
  void oversizedMultipartPublishReturnsSwiftProblemDetailsWhenConfigured() throws Exception {
    Config config = configuredCandidate();
    String configuredBytes = CompatDefaults.setting(
        "compat.swift.oversizeBytes", "SWIFT_COMPAT_OVERSIZE_BYTES").orElse("");
    assumeTrue(!configuredBytes.isBlank(),
        "Set SWIFT_COMPAT_OVERSIZE_BYTES above the candidate request limit to run this check");
    long requestedBytes = Long.parseLong(configuredBytes);
    assertTrue(requestedBytes > 0 && requestedBytes <= Integer.MAX_VALUE - 65536L,
        "SWIFT_COMPAT_OVERSIZE_BYTES must fit in one multipart test fixture");
    ensureCandidateRepositories(config);
    Fixture fixture = Fixture.withArchive(
        uniqueName("oversize"), "1.0.0", new byte[(int) requestedBytes], null, "{}");

    Exchange response = publish(config.candidateHosted(), fixture, false);

    assertEquals(413, response.status(), "oversized Swift multipart publish status");
    assertEquals("1", response.header("content-version"), "oversized publish Content-Version");
    assertProblem("oversized candidate publish", response);
    assertEquals(413, json(response).path("status").asInt(), "problem-details status");
    assertEquals(404, get(config.candidateHosted(), fixture.coordinatePath(), JSON_ACCEPT).status(),
        "rejected oversized release must not be persisted");
  }

  @Test
  void basicBearerAndAnonymousReadAuthenticationContractsWhenConfigured() throws Exception {
    Config config = configured();
    ensureRepositories(config);
    Fixture fixture = Fixture.unsigned(uniqueName("auth"), "1.0.0", "auth");
    assertEquals(201, publish(config.nexusHosted(), fixture, false).status());
    assertEquals(201, publish(config.candidateHosted(), fixture, false).status());

    Exchange nexusLogin = post(config.nexusHosted(), "login", config.nexusAuth());
    Exchange candidateLogin = post(config.candidateHosted(), "login", config.candidateAuth());
    assertEquals(nexusLogin.status(), candidateLogin.status(), "valid Basic login status");
    assertEquals(200, candidateLogin.status(), "valid Basic login");

    String bad = basic("invalid-user", "invalid-password");
    Exchange nexusBadLogin = post(config.nexusHosted(), "login", bad);
    Exchange candidateBadLogin = post(config.candidateHosted(), "login", bad);
    assertTrue(nexusBadLogin.status() == 401 || nexusBadLogin.status() == 429,
        "Nexus invalid Basic login is rejected or rate limited");
    assertEquals(401, candidateBadLogin.status(), "invalid Basic login");

    CandidateGenericToken activeToken = null;
    CandidateGenericToken expiredToken = null;
    try {
      activeToken = createCandidateGenericToken(config, null);
      String activeAuthorization = "Bearer " + activeToken.token();
      Exchange bearerLogin = post(config.candidateHosted(), "login", activeAuthorization);
      assertEquals(200, bearerLogin.status(), "active GenericToken Bearer login");
      Exchange bearerRead = get(
          config.candidateHosted().withAuthorization(activeAuthorization),
          fixture.coordinatePath(),
          JSON_ACCEPT);
      assertEquals(200, bearerRead.status(), "active GenericToken Bearer read");

      String revokedAuthorization = activeAuthorization;
      deleteCandidateGenericToken(config, activeToken.id(), "revoke active GenericToken");
      activeToken = null;
      assertEquals(401, post(config.candidateHosted(), "login", revokedAuthorization).status(),
          "revoked GenericToken login");
      assertEquals(401, get(config.candidateHosted().withAuthorization(revokedAuthorization),
          fixture.coordinatePath(), JSON_ACCEPT).status(), "revoked GenericToken read");

      expiredToken = createCandidateGenericToken(config, "2000-01-01T00:00:00");
      String expiredAuthorization = "Bearer " + expiredToken.token();
      assertEquals(401, post(config.candidateHosted(), "login", expiredAuthorization).status(),
          "expired GenericToken login");
      assertEquals(401, get(config.candidateHosted().withAuthorization(expiredAuthorization),
          fixture.coordinatePath(), JSON_ACCEPT).status(), "expired GenericToken read");
      deleteCandidateGenericToken(config, expiredToken.id(), "clean up expired GenericToken");
      expiredToken = null;
    } finally {
      deleteCandidateGenericTokenQuietly(config, activeToken);
      deleteCandidateGenericTokenQuietly(config, expiredToken);
    }

    Exchange nexusAnonymous = get(config.nexusHosted().anonymous(), fixture.coordinatePath(), JSON_ACCEPT);
    Exchange candidateAnonymous = get(
        config.candidateHosted().anonymous(), fixture.coordinatePath(), JSON_ACCEPT);
    assertEquals(nexusAnonymous.status(), candidateAnonymous.status(), "anonymous read status");
    assertTrue(candidateAnonymous.status() == 200 || candidateAnonymous.status() == 401
        || candidateAnonymous.status() == 403 || candidateAnonymous.status() == 404,
        "anonymous behavior must be an explicit access decision");
  }

  @Test
  void thirtyTwoConcurrentPublishesHaveExactlyOneWinnerWhenConfigured() throws Exception {
    Config config = configured();
    ensureRepositories(config);
    Fixture fixture = Fixture.unsigned(uniqueName("race"), "3.0.0", "concurrent");
    int attempts = intSetting("compat.swift.concurrentPublishes",
        "SWIFT_COMPAT_CONCURRENT_PUBLISHES", 32);
    int threads = Math.min(attempts, intSetting("compat.swift.concurrentThreads",
        "SWIFT_COMPAT_CONCURRENT_THREADS", 16));
    List<Endpoint> replicas = new ArrayList<>();
    replicas.add(config.candidateHosted());
    if (!config.candidateSecondaryBase().isBlank()) {
      replicas.add(config.candidateSecondaryReplica(config.hosted()));
    }
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    List<Future<Exchange>> futures = new ArrayList<>();
    try {
      for (int index = 0; index < attempts; index++) {
        Endpoint replica = replicas.get(index % replicas.size());
        futures.add(executor.submit(() -> publish(replica, fixture, false)));
      }
      int created = 0;
      int conflicts = 0;
      for (Future<Exchange> future : futures) {
        Exchange exchange = future.get();
        if (exchange.status() == 201) {
          created++;
        } else if (exchange.status() == 409) {
          conflicts++;
        } else {
          throw new AssertionError("unexpected concurrent publish status=" + exchange.status()
              + " body=" + exchange.text());
        }
      }
      assertEquals(1, created, "database uniqueness must select one publish winner");
      assertEquals(attempts - 1, conflicts, "all losing publishes must be immutable conflicts");
    } finally {
      executor.shutdownNow();
    }
    assertPackageReads(config.candidateHosted(), config.candidateHosted(), fixture);
    if (replicas.size() > 1) {
      assertPackageReads(config.candidateHosted(), replicas.get(1), fixture);
    }
  }

  @Test
  void groupConflictKeepsMetadataManifestArchiveAndChecksumOnTheFirstMemberWhenConfigured()
      throws Exception {
    Config config = configured();
    ensureRepositories(config);
    String name = uniqueName("groupbinding");
    Fixture first = Fixture.unsigned(name, "4.0.0", "first-member");
    Fixture second = Fixture.unsigned(name, "4.0.0", "second-member");
    assertNotEquals(sha256Hex(first.archive()), sha256Hex(second.archive()));

    assertEquals(201, publish(config.nexusHosted(), first, false).status());
    assertEquals(201, publish(config.nexusSecondaryHosted(), second, false).status());
    assertEquals(201, publish(config.candidateHosted(), first, false).status());
    assertEquals(201, publish(config.candidateSecondaryHosted(), second, false).status());

    assertGroupBinding("Nexus", config.nexusGroup(), first);
    assertGroupBinding("kkrepo", config.candidateGroup(), first);
  }

  @Test
  void candidateGroupReorderAndNestedPrioritySwitchAcrossReplicasWhenConfigured()
      throws Exception {
    Config config = configuredCandidate();
    assumeTrue(!config.candidateSecondaryBase().isBlank(),
        "Set SWIFT_KKREPO_SECONDARY_BASE_URL to verify group updates across replicas");
    ensureCandidateRepositories(config);
    List<String> originalMembers = candidateGroupMembers(config, config.group());
    List<String> hostedFirst = prioritizedMembers(
        config.hosted(), config.secondaryHosted(), originalMembers);
    List<String> secondaryFirst = prioritizedMembers(
        config.secondaryHosted(), config.hosted(), originalMembers);
    String name = uniqueName("group-reorder");
    Fixture first = Fixture.unsigned(name, "4.1.0", "group-hosted-first");
    Fixture second = Fixture.unsigned(name, "4.1.0", "group-secondary-first");
    assertNotEquals(sha256Hex(first.archive()), sha256Hex(second.archive()));
    assertEquals(201, publish(config.candidateHosted(), first, false).status());
    assertEquals(201, publish(config.candidateSecondaryHosted(), second, false).status());
    String nestedGroup = uniqueName("nested-group");
    boolean nestedCreated = false;

    try {
      replaceCandidateGroupMembers(config, config.group(), hostedFirst,
          "set hosted-first Swift group order");
      assertGroupBindingAcrossCandidateReplicas(
          "hosted-first group", config, config.group(), first);

      replaceCandidateGroupMembers(config, config.group(), secondaryFirst,
          "set secondary-first Swift group order");
      assertGroupBindingAcrossCandidateReplicas(
          "secondary-first group", config, config.group(), second);

      createCandidateGroup(config, nestedGroup, List.of(config.hosted(), config.group()));
      nestedCreated = true;
      assertGroupBindingAcrossCandidateReplicas(
          "nested direct-member priority", config, nestedGroup, first);

      replaceCandidateGroupMembers(config, nestedGroup, List.of(config.group(), config.hosted()),
          "set nested-group-first Swift order");
      assertGroupBindingAcrossCandidateReplicas(
          "nested group-member priority", config, nestedGroup, second);
    } finally {
      try {
        if (nestedCreated) {
          deleteCandidateRepository(config, nestedGroup, "delete nested Swift group");
        }
      } finally {
        replaceCandidateGroupMembers(config, config.group(), originalMembers,
            "restore original Swift group order");
      }
    }
  }

  @Test
  void renamedGitHubRepositoryIdentifiersAndReleasesMatchNexusWhenConfigured()
      throws Exception {
    Config config = configured();
    assumeTrue(config.proxyEnabled(),
        "Set SWIFT_COMPAT_PROXY_ENABLED=true to exercise renamed GitHub repositories");
    String configuredCase = CompatDefaults.setting(
        "compat.swift.renamedRepositoryCase", "SWIFT_COMPAT_RENAMED_REPOSITORY_CASE").orElse("");
    assumeTrue(!configuredCase.isBlank(),
        "Set SWIFT_COMPAT_RENAMED_REPOSITORY_CASE=oldScope/oldName/newScope/newName/version");
    RenamedRepositoryCase renamed = RenamedRepositoryCase.parse(configuredCase);
    ensureRepositories(config);

    int successfulIdentifiers = 0;
    for (String repositoryUrl : List.of(renamed.oldRepositoryUrl(), renamed.newRepositoryUrl())) {
      Exchange reference = get(config.nexusProxy(), "identifiers?url=" + encode(repositoryUrl),
          JSON_ACCEPT);
      Exchange candidate = get(config.candidateProxy(), "identifiers?url=" + encode(repositoryUrl),
          JSON_ACCEPT);
      assertEquals(reference.status(), candidate.status(),
          "renamed repository identifier status for " + repositoryUrl);
      if (reference.status() == 200) {
        successfulIdentifiers++;
        assertEquals(identifierSet(json(reference)), identifierSet(json(candidate)),
            "renamed repository identifiers for " + repositoryUrl);
      } else {
        assertProblem("candidate renamed repository identifier for " + repositoryUrl, candidate);
      }
    }
    assertTrue(successfulIdentifiers > 0,
        "Nexus must resolve at least one URL for the configured renamed repository fixture");

    int availableCoordinates = 0;
    availableCoordinates += assertRenamedRepositoryCoordinate(
        "old GitHub coordinate",
        config.nexusProxy(),
        config.candidateProxy(),
        renamed.oldScope(),
        renamed.oldName(),
        renamed.version()) ? 1 : 0;
    availableCoordinates += assertRenamedRepositoryCoordinate(
        "new GitHub coordinate",
        config.nexusProxy(),
        config.candidateProxy(),
        renamed.newScope(),
        renamed.newName(),
        renamed.version()) ? 1 : 0;
    assertTrue(availableCoordinates > 0,
        "Nexus must expose the configured version through at least one renamed coordinate");
  }

  @Test
  void githubProxyAndConcurrentColdReadContractsMatchNexusWhenConfigured() throws Exception {
    Config config = configured();
    assumeTrue(config.proxyEnabled(),
        "Set SWIFT_COMPAT_PROXY_ENABLED=true to exercise the live GitHub-backed proxy");
    ensureRepositories(config);
    String coordinate = config.proxyScope() + "/" + config.proxyName();

    for (String url : List.of(
        "https://github.com/" + config.proxyScope() + "/" + config.proxyName(),
        "https://github.com/" + config.proxyScope() + "/" + config.proxyName() + ".git",
        "git@github.com:" + config.proxyScope() + "/" + config.proxyName() + ".git")) {
      String path = "identifiers?url=" + encode(url);
      Exchange nexus = get(config.nexusProxy(), path, JSON_ACCEPT);
      Exchange candidate = get(config.candidateProxy(), path, JSON_ACCEPT);
      assertEquals(nexus.status(), candidate.status(), "proxy identifiers status for " + url);
      assertEquals(200, candidate.status(), "proxy identifier lookup for " + url);
      assertEquals(identifierSet(json(nexus)), identifierSet(json(candidate)),
          "proxy identifier set for " + url);
    }

    Exchange nexusList = get(config.nexusProxy(), coordinate, JSON_ACCEPT);
    Exchange candidateList = get(config.candidateProxy(), coordinate, JSON_ACCEPT);
    assertEquals(nexusList.status(), candidateList.status(), "proxy release list status");
    assertEquals(200, candidateList.status(), "proxy release list");
    assertTrue(json(candidateList).path("releases").has(config.proxyVersion()),
        "configured proxy version must be present");

    String archivePath = coordinate + "/" + config.proxyVersion() + ".zip";
    List<Endpoint> replicas = new ArrayList<>();
    replicas.add(config.candidateProxy());
    if (!config.candidateSecondaryBase().isBlank()) {
      replicas.add(config.candidateSecondaryReplica(config.proxy()));
    }
    ExecutorService executor = Executors.newFixedThreadPool(16);
    List<Future<Exchange>> futures = new ArrayList<>();
    try {
      for (int index = 0; index < 32; index++) {
        Endpoint replica = replicas.get(index % replicas.size());
        futures.add(executor.submit(() -> get(replica, archivePath, ZIP_ACCEPT)));
      }
      String expectedChecksum = null;
      for (Future<Exchange> future : futures) {
        Exchange exchange = future.get();
        assertEquals(200, exchange.status(), "concurrent proxy archive status");
        String checksum = sha256Hex(exchange.body());
        if (expectedChecksum == null) {
          expectedChecksum = checksum;
        } else {
          assertEquals(expectedChecksum, checksum,
              "all concurrent readers on every replica must see the persisted archive snapshot");
        }
      }
      assertNotNull(expectedChecksum, "concurrent proxy requests return an archive");

      String release = coordinate + "/" + config.proxyVersion();
      Exchange nexusMetadata = get(config.nexusProxy(), release, JSON_ACCEPT);
      Exchange candidateMetadata = get(config.candidateProxy(), release, JSON_ACCEPT);
      assertEquals(nexusMetadata.status(), candidateMetadata.status(), "proxy metadata status");
      Exchange nexusArchive = get(config.nexusProxy(), archivePath, ZIP_ACCEPT);
      assertEquals(sourceChecksum(json(nexusMetadata)), sha256Hex(nexusArchive.body()),
          "Nexus metadata must advertise its own generated archive checksum");
      assertEquals(expectedChecksum, sourceChecksum(json(candidateMetadata)),
          "concurrent cold download must persist the checksum advertised by metadata");
    } finally {
      executor.shutdownNow();
    }

    Exchange nexusArchive = get(config.nexusProxy(), archivePath, ZIP_ACCEPT);
    Exchange candidateArchive = get(config.candidateProxy(), archivePath, ZIP_ACCEPT);
    assertEquals(nexusArchive.status(), candidateArchive.status(), "proxy archive status");
    assertEquals(archiveEntryDigests(nexusArchive.body()), archiveEntryDigests(candidateArchive.body()),
        "GitHub proxy archives must contain the same normalized source tree");
  }

  @Test
  void configuredGitHubTagsNormalizeBothLowercaseAndUppercaseVWhenConfigured() throws Exception {
    Config config = configured();
    assumeTrue(config.proxyEnabled(),
        "Set SWIFT_COMPAT_PROXY_ENABLED=true to exercise the live GitHub-backed proxy");
    if (config.requireProxyTagCases()) {
      assertFalse(config.proxyTagCases().isBlank(),
          "strict Swift proxy compatibility requires SWIFT_COMPAT_PROXY_TAG_CASES");
    } else {
      assumeTrue(!config.proxyTagCases().isBlank(),
          "Set SWIFT_COMPAT_PROXY_TAG_CASES to controlled v/V GitHub tag fixtures");
    }
    ensureRepositories(config);
    List<TagCase> cases = TagCase.parse(config.proxyTagCases());
    assertTrue(cases.stream().anyMatch(value -> value.rawTag().startsWith("v")),
        "tag fixtures must include a lowercase v tag");
    assertTrue(cases.stream().anyMatch(value -> value.rawTag().startsWith("V")),
        "tag fixtures must include an uppercase V tag");
    for (TagCase tagCase : cases) {
      String path = tagCase.scope() + "/" + tagCase.name();
      Exchange nexus = get(config.nexusProxy(), path, JSON_ACCEPT);
      Exchange candidate = get(config.candidateProxy(), path, JSON_ACCEPT);
      assertEquals(nexus.status(), candidate.status(), "tag normalization list status " + tagCase);
      assertEquals(200, candidate.status(), "tag normalization list " + tagCase);
      assertTrue(json(nexus).path("releases").has(tagCase.normalizedVersion()),
          "Nexus normalizes " + tagCase.rawTag());
      assertTrue(json(candidate).path("releases").has(tagCase.normalizedVersion()),
          "kkrepo normalizes " + tagCase.rawTag());
    }
  }

  @Test
  void publishedReleaseIsImmediatelyReadableFromAnotherReplicaWhenConfigured() throws Exception {
    Config config = configured();
    assumeTrue(!config.candidateSecondaryBase().isBlank(),
        "Set SWIFT_KKREPO_SECONDARY_BASE_URL to run the cross-replica publish/read check");
    ensureRepositories(config);
    Fixture fixture = Fixture.unsigned(uniqueName("replica"), "5.0.0", "cross-replica");
    assertEquals(201, publish(config.candidateHosted(), fixture, false).status());

    Endpoint secondary = new Endpoint(
        config.candidateSecondaryBase() + "/repository/" + config.hosted(), config.candidateAuth());
    Exchange metadata = eventuallyGet(secondary, fixture.coordinatePath(), JSON_ACCEPT);
    Exchange archive = eventuallyGet(secondary, fixture.archivePath(), ZIP_ACCEPT);
    assertEquals(200, metadata.status(), "secondary replica metadata status");
    assertEquals(sha256Hex(fixture.archive()), sourceChecksum(json(metadata)));
    assertArrayEquals(fixture.archive(), archive.body(), "secondary replica archive bytes");
  }

  private static Config configured() throws Exception {
    Config config = Config.load();
    assumeTrue(config.enabled(),
        "Set SWIFT_COMPAT_ENABLED=true to run Swift compatibility against Nexus 3.94+");
    assumeTrue(reachable(config.nexusBase(), config.nexusAuth()),
        "Nexus Swift reference is not reachable at " + config.nexusBase());
    assumeTrue(reachable(config.candidateBase(), config.candidateAuth()),
        "kkrepo candidate is not reachable at " + config.candidateBase());
    return config;
  }

  private static Config configuredCandidate() throws Exception {
    Config config = Config.load();
    assumeTrue(config.enabled(),
        "Set SWIFT_COMPAT_ENABLED=true to run Swift candidate compatibility checks");
    assumeTrue(reachable(config.candidateBase(), config.candidateAuth()),
        "kkrepo candidate is not reachable at " + config.candidateBase());
    return config;
  }

  private static boolean reachable(String baseUrl, String authorization) {
    try {
      Exchange exchange = send(HttpRequest.newBuilder(URI.create(baseUrl + "/service/rest/v1/status"))
          .header("Authorization", authorization).GET());
      return exchange.status() > 0;
    } catch (Exception ignored) {
      return false;
    }
  }

  private static void ensureRepositories(Config config) throws Exception {
    ensureNexusRepositories(config);
    ensureCandidateRepositories(config);
  }

  private static void ensureNexusRepositories(Config config) throws Exception {
    JsonNode repositories = json(send(config.nexusAdmin("/service/rest/v1/repositories").GET()));
    if (!containsRepository(repositories, config.hosted())) {
      assert2xx("create Nexus Swift hosted", send(config.nexusAdmin(
          "/service/rest/v1/repositories/swift/hosted")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(hostedBody(config.hosted())))));
    }
    repositories = json(send(config.nexusAdmin("/service/rest/v1/repositories").GET()));
    if (!containsRepository(repositories, config.secondaryHosted())) {
      assert2xx("create Nexus secondary Swift hosted", send(config.nexusAdmin(
          "/service/rest/v1/repositories/swift/hosted")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(hostedBody(config.secondaryHosted())))));
    }
    repositories = json(send(config.nexusAdmin("/service/rest/v1/repositories").GET()));
    if (!containsRepository(repositories, config.proxy())) {
      String body = """
          {"name":"%s","online":true,
           "storage":{"blobStoreName":"default","strictContentTypeValidation":true},
           "proxy":{"remoteUrl":"https://github.com/","contentMaxAge":1440,"metadataMaxAge":1440},
           "negativeCache":{"enabled":true,"timeToLive":60},
           "httpClient":{"blocked":false,"autoBlock":true}}
          """.formatted(config.proxy());
      assert2xx("create Nexus Swift proxy", send(config.nexusAdmin(
          "/service/rest/v1/repositories/swift/proxy")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))));
    }
    repositories = json(send(config.nexusAdmin("/service/rest/v1/repositories").GET()));
    if (!containsRepository(repositories, config.group())) {
      String body = """
          {"name":"%s","online":true,
           "storage":{"blobStoreName":"default","strictContentTypeValidation":true},
           "group":{"memberNames":["%s","%s","%s"]}}
          """.formatted(config.group(), config.hosted(), config.secondaryHosted(), config.proxy());
      assert2xx("create Nexus Swift group", send(config.nexusAdmin(
          "/service/rest/v1/repositories/swift/group")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))));
    }
  }

  private static void ensureCandidateRepositories(Config config) throws Exception {
    JsonNode repositories = json(send(config.candidateAdmin("/internal/repositories").GET()));
    if (!containsRepository(repositories, config.hosted())) {
      assert2xx("create kkrepo Swift hosted", send(config.candidateAdmin("/internal/repositories")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(candidateHostedBody(config.hosted())))));
    }
    repositories = json(send(config.candidateAdmin("/internal/repositories").GET()));
    if (!containsRepository(repositories, config.secondaryHosted())) {
      assert2xx("create kkrepo secondary Swift hosted", send(
          config.candidateAdmin("/internal/repositories")
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(
                  candidateHostedBody(config.secondaryHosted())))));
    }
    repositories = json(send(config.candidateAdmin("/internal/repositories").GET()));
    if (!containsRepository(repositories, config.proxy())) {
      String body = """
          {"name":"%s","recipe":"swift-proxy","online":true,"blobStoreName":"default",
           "strictContentTypeValidation":true,
           "proxy":{"remoteUrl":"https://github.com/","contentMaxAgeMinutes":1440,
                    "metadataMaxAgeMinutes":1440,"negativeCacheEnabled":true,
                    "negativeCacheTtlMinutes":1,"autoBlock":true}}
          """.formatted(config.proxy());
      assert2xx("create kkrepo Swift proxy", send(config.candidateAdmin("/internal/repositories")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))));
    }
    repositories = json(send(config.candidateAdmin("/internal/repositories").GET()));
    if (!containsRepository(repositories, config.group())) {
      String body = """
          {"name":"%s","recipe":"swift-group","online":true,"blobStoreName":"default",
           "strictContentTypeValidation":true,"group":{"memberNames":["%s","%s","%s"]}}
          """.formatted(config.group(), config.hosted(), config.secondaryHosted(), config.proxy());
      assert2xx("create kkrepo Swift group", send(config.candidateAdmin("/internal/repositories")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))));
    }
  }

  private static String hostedBody(String name) {
    return """
        {"name":"%s","online":true,
         "storage":{"blobStoreName":"default","strictContentTypeValidation":true,
                    "writePolicy":"ALLOW_ONCE"}}
        """.formatted(name);
  }

  private static String candidateHostedBody(String name) {
    return """
        {"name":"%s","recipe":"swift-hosted","online":true,"blobStoreName":"default",
         "strictContentTypeValidation":true,"hosted":{"writePolicy":"ALLOW_ONCE"}}
        """.formatted(name);
  }

  private static boolean containsRepository(JsonNode repositories, String name) {
    JsonNode values = repositories.isArray() ? repositories : repositories.path("items");
    if (!values.isArray()) {
      values = repositories.path("repositories");
    }
    for (JsonNode repository : values) {
      if (name.equals(repository.path("name").asText())) {
        return true;
      }
    }
    return false;
  }

  private static void assertPackageReads(Endpoint reference, Endpoint candidate, Fixture fixture)
      throws Exception {
    String packagePath = fixture.scope() + "/" + fixture.name();
    Exchange referenceList = get(reference, packagePath, JSON_ACCEPT);
    Exchange candidateList = get(candidate, packagePath, JSON_ACCEPT);
    assertSameSuccessJson("release list", referenceList, candidateList);
    assertTrue(json(candidateList).path("releases").has(fixture.version()),
        "release list contains published version");
    assertTrue(candidateList.header("link").contains("latest-version"),
        "release list advertises the latest version");

    Exchange referenceListAlias = get(reference, packagePath + ".json", JSON_ACCEPT);
    Exchange candidateListAlias = get(candidate, packagePath + ".json", JSON_ACCEPT);
    assertEquals(referenceListAlias.status(), candidateListAlias.status(),
        "release list .json non-protocol path status");
    assertEquals(404, candidateListAlias.status(), "release list .json must not be an alias");

    Exchange referenceMetadata = get(reference, fixture.coordinatePath(), JSON_ACCEPT);
    Exchange candidateMetadata = get(candidate, fixture.coordinatePath(), JSON_ACCEPT);
    assertSameSuccessJson("release metadata", referenceMetadata, candidateMetadata);
    JsonNode referenceBody = json(referenceMetadata);
    JsonNode candidateBody = json(candidateMetadata);
    assertEquals(referenceBody.path("id").asText().toLowerCase(Locale.ROOT),
        candidateBody.path("id").asText().toLowerCase(Locale.ROOT), "release identity");
    assertEquals(fixture.version(), candidateBody.path("version").asText(), "release version");
    assertEquals(sha256Hex(fixture.archive()), sourceChecksum(referenceBody),
        "Nexus release checksum");
    assertEquals(sourceChecksum(referenceBody), sourceChecksum(candidateBody),
        "candidate release checksum");

    Exchange referenceMetadataAlias = get(reference, fixture.coordinatePath() + ".json", JSON_ACCEPT);
    Exchange candidateMetadataAlias = get(candidate, fixture.coordinatePath() + ".json", JSON_ACCEPT);
    assertEquals(referenceMetadataAlias.status(), candidateMetadataAlias.status(),
        "release metadata .json non-protocol path status");
    assertEquals(404, candidateMetadataAlias.status(), "release metadata .json must not be an alias");

    Exchange referenceManifest = get(reference, fixture.manifestPath(), SWIFT_ACCEPT);
    Exchange candidateManifest = get(candidate, fixture.manifestPath(), SWIFT_ACCEPT);
    assertSameBody("default manifest", referenceManifest, candidateManifest, "text/x-swift");
    assertArrayEquals(fixture.manifest(), candidateManifest.body(), "default manifest bytes");
    assertTrue(candidateManifest.header("content-disposition").contains("Package.swift"));
    assertTrue(candidateManifest.header("link").contains("Package@swift-5.9.swift"));
    assertTrue(candidateManifest.header("link").contains("swift-tools-version=\"5.9\"")
        || candidateManifest.header("link").contains("swift-tools-version=5.9"));

    Exchange referenceVersioned = get(reference, fixture.manifestPath() + "?swift-version=5.9", SWIFT_ACCEPT);
    Exchange candidateVersioned = get(candidate, fixture.manifestPath() + "?swift-version=5.9", SWIFT_ACCEPT);
    assertEquals(referenceVersioned.status(), candidateVersioned.status(), "versioned manifest status");
    assertEquals(200, candidateVersioned.status(), "candidate versioned manifest status");
    assertContentType(referenceVersioned, "text/x-swift", "versioned manifest Nexus Content-Type");
    assertContentType(candidateVersioned, "text/x-swift", "versioned manifest candidate Content-Type");
    assertTrue(MessageDigest.isEqual(referenceVersioned.body(), fixture.versionedManifest())
            || MessageDigest.isEqual(referenceVersioned.body(), fixture.manifest()),
        "Nexus returns either the requested versioned manifest or its known default-manifest fallback");
    assertArrayEquals(fixture.versionedManifest(), candidateVersioned.body(),
        "versioned manifest bytes");
    assertTrue(candidateVersioned.header("content-disposition").contains("Package@swift-5.9.swift"));

    assertManifestFallback(reference, candidate, fixture);

    Exchange referenceArchive = get(reference, fixture.archivePath(), ZIP_ACCEPT);
    Exchange candidateArchive = get(candidate, fixture.archivePath(), ZIP_ACCEPT);
    assertSameBody("source archive", referenceArchive, candidateArchive, "application/zip");
    assertArrayEquals(fixture.archive(), candidateArchive.body(), "source archive bytes");
    assertTrue(candidateArchive.header("content-disposition").contains(
        fixture.name() + "-" + fixture.version() + ".zip"));
  }

  private static void assertManifestFallback(Endpoint reference, Endpoint candidate, Fixture fixture)
      throws Exception {
    Endpoint noFollowReference = reference.withNoRedirects();
    Endpoint noFollowCandidate = candidate.withNoRedirects();
    String path = fixture.manifestPath() + "?swift-version=5.8";
    Exchange nexus = get(noFollowReference, path, SWIFT_ACCEPT);
    Exchange kkrepo = get(noFollowCandidate, path, SWIFT_ACCEPT);
    assertTrue(nexus.status() == 200 || nexus.status() == 303,
        "Nexus missing versioned manifest uses its known body or redirect fallback");
    if (nexus.status() == 200) {
      assertArrayEquals(fixture.manifest(), nexus.body(),
          "Nexus 200 fallback must return the default manifest");
    }
    assertEquals(303, kkrepo.status(), "missing exact versioned manifest uses 303");
    assertTrue(kkrepo.header("location").endsWith("/" + fixture.manifestPath()),
        "manifest fallback Location");
  }

  private static void assertArchiveHttpSemantics(
      Endpoint reference, Endpoint candidate, Fixture fixture) throws Exception {
    Exchange referenceHead = head(reference, fixture.archivePath(), ZIP_ACCEPT, Map.of());
    Exchange candidateHead = head(candidate, fixture.archivePath(), ZIP_ACCEPT, Map.of());
    assertTrue(referenceHead.status() == 200 || referenceHead.status() == 405,
        "Nexus archive HEAD is supported or uses its known method fallback");
    assertEquals(200, candidateHead.status(), "archive HEAD");
    if (referenceHead.status() == 200) {
      assertEquals(0, referenceHead.body().length, "Nexus HEAD body");
    }
    assertEquals(0, candidateHead.body().length, "candidate HEAD body");
    assertEquals(Integer.toString(fixture.archive().length), candidateHead.header("content-length"));
    assertFalse(candidateHead.header("etag").isBlank(), "archive ETag");
    assertFalse(candidateHead.header("last-modified").isBlank(), "archive Last-Modified");

    Exchange referenceValidator = referenceHead.status() == 200
        ? referenceHead
        : get(reference, fixture.archivePath(), ZIP_ACCEPT);
    assertEquals(200, referenceValidator.status(), "Nexus archive validator source");
    if (!referenceValidator.header("etag").isBlank()) {
      Exchange referenceConditional = get(reference, fixture.archivePath(), ZIP_ACCEPT,
          Map.of("If-None-Match", referenceValidator.header("etag")));
      assertEquals(304, referenceConditional.status(), "Nexus archive conditional GET");
    }
    Exchange candidateConditional = get(candidate, fixture.archivePath(), ZIP_ACCEPT,
        Map.of("If-None-Match", candidateHead.header("etag")));
    assertEquals(304, candidateConditional.status(), "archive conditional GET");
    assertEquals(0, candidateConditional.body().length, "304 response body");

    Exchange referenceRange = get(reference, fixture.archivePath(), ZIP_ACCEPT,
        Map.of("Range", "bytes=0-15"));
    Exchange candidateRange = get(candidate, fixture.archivePath(), ZIP_ACCEPT,
        Map.of("Range", "bytes=0-15"));
    assertEquals(referenceRange.status(), candidateRange.status(), "archive Range status");
    if (candidateRange.status() == 206) {
      assertEquals(16, candidateRange.body().length, "archive Range length");
      assertArrayEquals(java.util.Arrays.copyOfRange(fixture.archive(), 0, 16),
          candidateRange.body(), "archive Range bytes");
      assertTrue(candidateRange.header("content-range").startsWith("bytes 0-15/"));
    } else {
      assertEquals(200, candidateRange.status(), "server either honors or explicitly ignores Range");
      assertArrayEquals(fixture.archive(), candidateRange.body(), "ignored Range returns full archive");
    }

    Exchange referenceInvalidRange = get(reference, fixture.archivePath(), ZIP_ACCEPT,
        Map.of("Range", "bytes=999999999-"));
    Exchange candidateInvalidRange = get(candidate, fixture.archivePath(), ZIP_ACCEPT,
        Map.of("Range", "bytes=999999999-"));
    assertEquals(referenceInvalidRange.status(), candidateInvalidRange.status(),
        "invalid archive Range status");

    Exchange candidateArchive = get(candidate, fixture.archivePath(), ZIP_ACCEPT);
    String digest = candidateArchive.header("digest");
    if (!digest.isBlank()) {
      assertEquals("sha-256=" + Base64.getEncoder().encodeToString(sha256(fixture.archive())), digest);
    }
  }

  private static void assertHeadContracts(
      Endpoint reference, Endpoint candidate, Fixture fixture) throws Exception {
    List<RequestCase> resources = List.of(
        new RequestCase("release list HEAD", fixture.scope() + "/" + fixture.name(), JSON_ACCEPT),
        new RequestCase("release metadata HEAD", fixture.coordinatePath(), JSON_ACCEPT),
        new RequestCase("manifest HEAD", fixture.manifestPath(), SWIFT_ACCEPT),
        new RequestCase("versioned manifest HEAD",
            fixture.manifestPath() + "?swift-version=5.9", SWIFT_ACCEPT),
        new RequestCase("identifiers HEAD",
            "identifiers?url=" + encode(fixture.repositoryUrl()), JSON_ACCEPT));
    for (RequestCase resource : resources) {
      Exchange nexus = head(reference, resource.path(), resource.accept(), Map.of());
      Exchange kkrepo = head(candidate, resource.path(), resource.accept(), Map.of());
      assertTrue(nexus.status() == 200 || nexus.status() == 404 || nexus.status() == 405,
          resource.label() + " Nexus status is supported or its known HEAD fallback: "
              + nexus.status());
      assertEquals(200, kkrepo.status(), resource.label());
      if (nexus.status() == 200) {
        assertEquals(0, nexus.body().length, resource.label() + " Nexus body");
      }
      assertEquals(0, kkrepo.body().length, resource.label() + " candidate body");
      assertFalse(kkrepo.header("content-type").isBlank(), resource.label() + " Content-Type");
    }
  }

  private static void assertIdentifierVariants(
      Endpoint reference, Endpoint candidate, Fixture fixture) throws Exception {
    for (String url : List.of(
        fixture.repositoryUrl(),
        fixture.repositoryUrl().substring(0, fixture.repositoryUrl().length() - 4),
        fixture.repositoryUrl().toUpperCase(Locale.ROOT))) {
      String path = "identifiers?url=" + encode(url);
      Exchange nexus = get(reference, path, JSON_ACCEPT);
      Exchange kkrepo = get(candidate, path, JSON_ACCEPT);
      if (nexus.status() == 200) {
        assertEquals(200, kkrepo.status(), "candidate identifier status for " + url);
        assertEquals(identifierSet(json(nexus)), identifierSet(json(kkrepo)),
            "identifier values for " + url);
      } else {
        assertEquals(404, nexus.status(), "Nexus unknown normalized URL variant");
        assertTrue(kkrepo.status() == 200 || kkrepo.status() == 404,
            "candidate normalized URL variant is either mapped or unknown: " + url);
      }
      if (kkrepo.status() == 200) {
        assertTrue(identifierSet(json(kkrepo)).stream()
            .anyMatch(value -> value.equalsIgnoreCase(fixture.scope() + "." + fixture.name())),
            "candidate normalized identifier value for " + url);
      }
    }
  }

  private static void assertGroupBinding(String label, Endpoint group, Fixture expected)
      throws Exception {
    Exchange metadata = get(group, expected.coordinatePath(), JSON_ACCEPT);
    Exchange manifest = get(group, expected.manifestPath(), SWIFT_ACCEPT);
    Exchange archive = get(group, expected.archivePath(), ZIP_ACCEPT);
    assertEquals(200, metadata.status(), label + " group metadata");
    assertEquals(200, manifest.status(), label + " group manifest");
    assertEquals(200, archive.status(), label + " group archive");
    assertEquals(sha256Hex(expected.archive()), sourceChecksum(json(metadata)),
        label + " group metadata checksum must select first member");
    assertArrayEquals(expected.manifest(), manifest.body(),
        label + " group manifest must select first member");
    assertArrayEquals(expected.archive(), archive.body(),
        label + " group archive must select first member");
  }

  private static void assertGroupBindingAcrossCandidateReplicas(
      String label, Config config, String group, Fixture expected) throws Exception {
    assertGroupBindingEventually(label + " primary", config.candidateRepository(group), expected);
    assertGroupBindingEventually(
        label + " secondary", config.candidateSecondaryReplica(group), expected);
  }

  private static void assertGroupBindingEventually(
      String label, Endpoint group, Fixture expected) throws Exception {
    AssertionError last = null;
    for (int attempt = 0; attempt < 40; attempt++) {
      try {
        assertGroupBinding(label, group, expected);
        return;
      } catch (AssertionError mismatch) {
        last = mismatch;
        Thread.sleep(250);
      }
    }
    throw last == null ? new AssertionError(label + " group binding was not observed") : last;
  }

  private static List<String> candidateGroupMembers(Config config, String group) throws Exception {
    Exchange response = send(config.candidateAdmin("/internal/repositories/" + encode(group)).GET());
    assert2xx("read candidate Swift group " + group, response);
    List<String> members = new ArrayList<>();
    json(response).path("group").path("memberNames")
        .forEach(member -> members.add(member.asText()));
    assertFalse(members.isEmpty(), "candidate Swift group must have members: " + group);
    return List.copyOf(members);
  }

  private static List<String> prioritizedMembers(
      String first, String second, List<String> existing) {
    List<String> result = new ArrayList<>();
    for (String member : List.of(first, second)) {
      if (!result.contains(member)) {
        result.add(member);
      }
    }
    for (String member : existing) {
      if (!result.contains(member)) {
        result.add(member);
      }
    }
    return List.copyOf(result);
  }

  private static void createCandidateGroup(
      Config config, String name, List<String> members) throws Exception {
    Map<String, Object> group = Map.of("memberNames", members);
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("name", name);
    command.put("recipe", "swift-group");
    command.put("online", true);
    command.put("blobStoreName", "default");
    command.put("strictContentTypeValidation", true);
    command.put("group", group);
    Exchange response = send(config.candidateAdmin("/internal/repositories")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(command))));
    assert2xx("create nested candidate Swift group " + name, response);
  }

  private static void replaceCandidateGroupMembers(
      Config config, String group, List<String> members, String label) throws Exception {
    String body = JSON.writeValueAsString(Map.of("memberNames", members));
    Exchange response = send(config.candidateAdmin(
        "/internal/repositories/" + encode(group) + "/members")
        .header("Content-Type", "application/json")
        .PUT(HttpRequest.BodyPublishers.ofString(body)));
    assert2xx(label, response);
  }

  private static void deleteCandidateRepository(Config config, String name, String label)
      throws Exception {
    Exchange response = send(config.candidateAdmin("/internal/repositories/" + encode(name))
        .DELETE());
    assert2xx(label, response);
  }

  private static boolean assertRenamedRepositoryCoordinate(
      String label,
      Endpoint reference,
      Endpoint candidate,
      String scope,
      String name,
      String version) throws Exception {
    String coordinate = scope + "/" + name;
    Exchange referenceList = get(reference, coordinate, JSON_ACCEPT);
    Exchange candidateList = get(candidate, coordinate, JSON_ACCEPT);
    assertEquals(referenceList.status(), candidateList.status(), label + " release list status");
    if (referenceList.status() != 200) {
      assertProblem(label + " candidate release list", candidateList);
      return false;
    }
    assertSameSuccessJson(label + " release list", referenceList, candidateList);
    boolean referenceHasVersion = json(referenceList).path("releases").has(version);
    assertEquals(referenceHasVersion, json(candidateList).path("releases").has(version),
        label + " configured version availability");

    String release = coordinate + "/" + version;
    Exchange referenceMetadata = get(reference, release, JSON_ACCEPT);
    Exchange candidateMetadata = get(candidate, release, JSON_ACCEPT);
    assertEquals(referenceMetadata.status(), candidateMetadata.status(),
        label + " release metadata status");
    String archive = release + ".zip";
    Exchange referenceArchive = get(reference, archive, ZIP_ACCEPT);
    Exchange candidateArchive = get(candidate, archive, ZIP_ACCEPT);
    assertEquals(referenceArchive.status(), candidateArchive.status(), label + " archive status");
    if (!referenceHasVersion) {
      assertTrue(referenceMetadata.status() >= 400, label + " unavailable metadata status");
      assertTrue(referenceArchive.status() >= 400, label + " unavailable archive status");
      assertProblem(label + " candidate metadata", candidateMetadata);
      assertProblem(label + " candidate archive", candidateArchive);
      return false;
    }

    assertSameProxyReleaseMetadata(label + " release metadata", referenceMetadata, candidateMetadata);
    assertEquals(200, referenceArchive.status(), label + " Nexus archive status");
    assertEquals(200, candidateArchive.status(), label + " candidate archive status");
    assertContentType(referenceArchive, "application/zip", label + " Nexus archive Content-Type");
    assertContentType(candidateArchive, "application/zip", label + " candidate archive Content-Type");
    assertEquals(sourceChecksum(json(referenceMetadata)), sha256Hex(referenceArchive.body()),
        label + " Nexus metadata/archive checksum");
    assertEquals(sourceChecksum(json(candidateMetadata)), sha256Hex(candidateArchive.body()),
        label + " candidate metadata/archive checksum");
    assertEquals(archiveEntryDigests(referenceArchive.body()),
        archiveEntryDigests(candidateArchive.body()),
        label + " generated archives must contain the same normalized source tree");
    return true;
  }

  private static CandidateGenericToken createCandidateGenericToken(
      Config config, String expiresAt) throws Exception {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("domain", "GenericToken");
    command.put("displayName",
        "Swift compatibility " + Long.toUnsignedString(System.nanoTime(), 36));
    if (expiresAt != null) {
      command.put("expiresAt", expiresAt);
    }
    Exchange response = send(config.candidateAdmin("/internal/security/api-keys/current")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(command))));
    assert2xx("create candidate GenericToken", response);
    JsonNode created = json(response);
    long id = created.path("apiKey").path("id").asLong();
    String token = created.path("token").asText();
    assertTrue(id > 0, "GenericToken response API key id");
    assertFalse(token.isBlank(), "GenericToken response token");
    return new CandidateGenericToken(id, token);
  }

  private static void deleteCandidateGenericToken(Config config, long id, String label)
      throws Exception {
    Exchange response = send(config.candidateAdmin("/internal/security/api-keys/current/" + id)
        .DELETE());
    assert2xx(label, response);
  }

  private static void deleteCandidateGenericTokenQuietly(
      Config config, CandidateGenericToken token) {
    if (token == null) {
      return;
    }
    try {
      send(config.candidateAdmin("/internal/security/api-keys/current/" + token.id()).DELETE());
    } catch (Exception ignored) {
    }
  }

  private static Exchange publish(Endpoint endpoint, Fixture fixture, boolean expectContinue)
      throws Exception {
    Multipart multipart = Multipart.forFixture(fixture);
    return publish(endpoint, fixture, expectContinue, multipart);
  }

  private static Exchange publish(
      Endpoint endpoint,
      Fixture fixture,
      boolean expectContinue,
      String archiveTransferEncoding,
      String metadataTransferEncoding) throws Exception {
    return publish(
        endpoint,
        fixture,
        expectContinue,
        Multipart.forFixture(fixture, archiveTransferEncoding, metadataTransferEncoding));
  }

  private static Exchange publish(
      Endpoint endpoint, Fixture fixture, boolean expectContinue, Multipart multipart)
      throws Exception {
    HttpRequest.Builder request = endpoint.request(fixture.coordinatePath())
        .header("Accept", JSON_ACCEPT)
        .header("Content-Type", "multipart/form-data; boundary=" + multipart.boundary())
        .expectContinue(expectContinue);
    if (fixture.signature() != null) {
      request.header("X-Swift-Package-Signature-Format", "cms-1.0.0");
    }
    return send(endpoint.client(), request.PUT(HttpRequest.BodyPublishers.ofByteArray(multipart.body())));
  }

  private static Exchange get(Endpoint endpoint, String path, String accept) throws Exception {
    return get(endpoint, path, accept, Map.of());
  }

  private static Exchange getWithoutAccept(Endpoint endpoint, String path) throws Exception {
    return send(endpoint.client(), endpoint.request(path).GET());
  }

  private static Exchange get(
      Endpoint endpoint, String path, String accept, Map<String, String> headers) throws Exception {
    HttpRequest.Builder request = endpoint.request(path).header("Accept", accept);
    headers.forEach(request::header);
    return send(endpoint.client(), request.GET());
  }

  private static Exchange head(
      Endpoint endpoint, String path, String accept, Map<String, String> headers) throws Exception {
    HttpRequest.Builder request = endpoint.request(path).header("Accept", accept);
    headers.forEach(request::header);
    return send(endpoint.client(), request.method("HEAD", HttpRequest.BodyPublishers.noBody()));
  }

  private static Exchange post(Endpoint endpoint, String path, String authorization)
      throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint.url(path)))
        .timeout(Duration.ofSeconds(120))
        .header("Accept", JSON_ACCEPT)
        .header("Authorization", authorization)
        .POST(HttpRequest.BodyPublishers.noBody());
    return send(endpoint.client(), request);
  }

  private static Exchange eventuallyGet(Endpoint endpoint, String path, String accept)
      throws Exception {
    Exchange last = null;
    for (int attempt = 0; attempt < 30; attempt++) {
      last = get(endpoint, path, accept);
      if (last.status() == 200) {
        return last;
      }
      Thread.sleep(200);
    }
    return last;
  }

  private static Exchange send(HttpRequest.Builder request) throws Exception {
    return send(HTTP, request);
  }

  private static Exchange send(HttpClient client, HttpRequest.Builder request) throws Exception {
    HttpResponse<byte[]> response = client.send(
        request.timeout(Duration.ofSeconds(180)).build(), HttpResponse.BodyHandlers.ofByteArray());
    return new Exchange(response.statusCode(), response.body(), response.headers().map());
  }

  private static void assertSameSuccessJson(String label, Exchange reference, Exchange candidate)
      throws Exception {
    assertEquals(reference.status(), candidate.status(), label + " status");
    assertEquals(200, candidate.status(), label + " candidate status");
    assertContentType(reference, "application/json", label + " Nexus Content-Type");
    assertContentType(candidate, "application/json", label + " candidate Content-Type");
    assertEquals("1", reference.header("content-version"), label + " Nexus Content-Version");
    assertEquals("1", candidate.header("content-version"), label + " candidate Content-Version");
    JsonNode referenceBody = json(reference);
    JsonNode candidateBody = json(candidate);
    JsonNode comparableReference = referenceForNexusComparison(referenceBody, candidateBody, label);
    assertEquals(canonicalJson(comparableReference),
        canonicalJson(candidateForNexusComparison(comparableReference, candidateBody, label)),
        label + " canonical JSON body");
    Set<String> referenceLinks = canonicalLinks(reference);
    Set<String> candidateLinks = canonicalLinks(candidate);
    assertTrue(candidateLinks.containsAll(referenceLinks),
        () -> label + " missing Nexus Link relations/targets: "
            + referenceLinks.stream().filter(link -> !candidateLinks.contains(link)).toList());
  }

  private static void assertSameProxyReleaseMetadata(
      String label, Exchange reference, Exchange candidate) throws Exception {
    assertEquals(reference.status(), candidate.status(), label + " status");
    assertEquals(200, candidate.status(), label + " candidate status");
    assertContentType(reference, "application/json", label + " Nexus Content-Type");
    assertContentType(candidate, "application/json", label + " candidate Content-Type");
    assertEquals("1", reference.header("content-version"), label + " Nexus Content-Version");
    assertEquals("1", candidate.header("content-version"), label + " candidate Content-Version");

    ObjectNode referenceBody = ((ObjectNode) json(reference)).deepCopy();
    ObjectNode candidateBody = ((ObjectNode) json(candidate)).deepCopy();
    normalizeGeneratedProxyMetadata(referenceBody, label + " Nexus");
    normalizeGeneratedProxyMetadata(candidateBody, label + " candidate");
    assertEquals(canonicalJson(referenceBody), canonicalJson(candidateBody),
        label + " canonical JSON body excluding generated archive checksum and optional metadata");

    Set<String> referenceLinks = canonicalLinks(reference);
    Set<String> candidateLinks = canonicalLinks(candidate);
    assertTrue(candidateLinks.containsAll(referenceLinks),
        () -> label + " missing Nexus Link relations/targets: "
            + referenceLinks.stream().filter(link -> !candidateLinks.contains(link)).toList());
  }

  private static void normalizeGeneratedProxyMetadata(ObjectNode body, String label) {
    if (body.has("publishedAt")) {
      requireDateTime(body.get("publishedAt"), label + " publishedAt");
      body.remove("publishedAt");
    }
    if (body.has("metadata")) {
      assertTrue(body.path("metadata").isObject(), label + " metadata object");
      body.remove("metadata");
    }
    JsonNode resources = body.path("resources");
    assertTrue(resources.isArray() && resources.size() == 1,
        label + " must advertise one source archive resource");
    JsonNode resource = resources.get(0);
    assertEquals("source-archive", resource.path("name").asText(),
        label + " source archive name");
    assertEquals("application/zip", resource.path("type").asText(),
        label + " source archive type");
    assertTrue(resource.path("checksum").isTextual()
            && resource.path("checksum").asText().matches("[0-9a-f]{64}"),
        label + " source archive checksum");
    ((ObjectNode) resource).remove("checksum");
  }

  private static JsonNode referenceForNexusComparison(
      JsonNode reference, JsonNode candidate, String label) {
    if (!isReleaseList(reference) || !reference.path("releases").isObject()) {
      return reference;
    }
    ObjectNode comparable = reference.deepCopy();
    ObjectNode releases = (ObjectNode) comparable.path("releases");
    List<String> invalidVersions = new ArrayList<>();
    releases.fieldNames().forEachRemaining(version -> {
      if (!SwiftVersions.isValid(version)) {
        invalidVersions.add(version);
      }
    });
    for (String invalidVersion : invalidVersions) {
      releases.remove(invalidVersion);
      assertFalse(candidate.path("releases").has(invalidVersion),
          label + " candidate must not expose Nexus's invalid Git ref pseudo-version "
              + invalidVersion);
    }
    return comparable;
  }

  private static JsonNode candidateForNexusComparison(
      JsonNode reference, JsonNode candidate, String label) {
    JsonNode comparable = candidate.deepCopy();
    if (!isReleaseMetadata(reference) || !comparable.isObject()) {
      return comparable;
    }
    if (!reference.has("metadata")) {
      assertTrue(comparable.path("metadata").isObject(),
          label + " candidate release metadata must include the official metadata object");
      ((ObjectNode) comparable).remove("metadata");
    }
    if (!reference.has("publishedAt") && comparable.has("publishedAt")) {
      requireDateTime(comparable.get("publishedAt"), label + " publishedAt");
      ((ObjectNode) comparable).remove("publishedAt");
    }
    return comparable;
  }

  private static boolean isReleaseMetadata(JsonNode value) {
    return value.isObject()
        && value.path("id").isTextual()
        && value.path("version").isTextual()
        && value.path("resources").isArray();
  }

  private static boolean isReleaseList(JsonNode value) {
    return value.isObject() && value.path("releases").isObject();
  }

  private static JsonNode canonicalJson(JsonNode value) {
    return canonicalJson(value, true);
  }

  private static JsonNode canonicalJson(JsonNode value, boolean root) {
    if (value.isObject()) {
      var result = JSON.createObjectNode();
      value.fields().forEachRemaining(entry -> {
        if (root && "publishedAt".equals(entry.getKey())) {
          requireDateTime(entry.getValue(), "publishedAt");
          result.put(entry.getKey(), "$DATE_TIME");
        } else {
          result.set(entry.getKey(), canonicalJson(entry.getValue(), false));
        }
      });
      return result;
    }
    if (value.isArray()) {
      var result = JSON.createArrayNode();
      value.forEach(element -> result.add(canonicalJson(element, false)));
      return result;
    }
    if (value.isTextual()) {
      return JSON.getNodeFactory().textNode(canonicalRegistryTarget(value.textValue()));
    }
    return value.deepCopy();
  }

  private static void requireDateTime(JsonNode value, String field) {
    if (!value.isTextual()) {
      throw new AssertionError(field + " must be an RFC 3339 date-time string: " + value);
    }
    try {
      Instant.parse(value.textValue());
    } catch (RuntimeException e) {
      throw new AssertionError(field + " must be an RFC 3339 date-time string: " + value, e);
    }
  }

  private static Set<String> canonicalLinks(Exchange exchange) {
    TreeSet<String> result = new TreeSet<>();
    for (String header : exchange.headers("link")) {
      for (String link : splitLinkValues(header)) {
        int targetEnd = link.indexOf('>');
        if (!link.startsWith("<") || targetEnd < 2) {
          throw new AssertionError("Malformed Link value: " + link);
        }
        String target = link.substring(1, targetEnd);
        Matcher relation = LINK_RELATION.matcher(link.substring(targetEnd + 1));
        if (!relation.find()) {
          throw new AssertionError("Link value is missing rel: " + link);
        }
        String relations = relation.group(1) == null ? relation.group(2) : relation.group(1);
        for (String relationName : relations.trim().split("\\s+")) {
          if (!relationName.isBlank()) {
            String normalizedRelation = relationName.toLowerCase(Locale.ROOT);
            result.add(normalizedRelation + " "
                + canonicalLinkTarget(normalizedRelation, target));
          }
        }
      }
    }
    return result;
  }

  private static List<String> splitLinkValues(String header) {
    List<String> result = new ArrayList<>();
    int start = 0;
    boolean inTarget = false;
    boolean inQuote = false;
    boolean escaped = false;
    for (int index = 0; index < header.length(); index++) {
      char current = header.charAt(index);
      if (escaped) {
        escaped = false;
      } else if (inQuote && current == '\\') {
        escaped = true;
      } else if (!inQuote && current == '<') {
        inTarget = true;
      } else if (!inQuote && current == '>') {
        inTarget = false;
      } else if (!inTarget && current == '"') {
        inQuote = !inQuote;
      } else if (!inTarget && !inQuote && current == ',') {
        addLinkValue(result, header.substring(start, index));
        start = index + 1;
      }
    }
    if (inTarget || inQuote || escaped) {
      throw new AssertionError("Malformed Link header: " + header);
    }
    addLinkValue(result, header.substring(start));
    return result;
  }

  private static void addLinkValue(List<String> values, String value) {
    if (!value.isBlank()) {
      values.add(value.trim());
    }
  }

  private static String canonicalRegistryTarget(String value) {
    final URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException ignored) {
      return value;
    }
    String scheme = uri.getScheme();
    String path = uri.getRawPath();
    if (scheme == null || path == null
        || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
      return value;
    }
    int repository = path.indexOf("/repository/");
    if (repository < 0) {
      return value;
    }
    int repositoryNameStart = repository + "/repository/".length();
    int coordinateStart = path.indexOf('/', repositoryNameStart);
    if (coordinateStart < 0) {
      return value;
    }
    StringBuilder canonical = new StringBuilder(path.substring(coordinateStart));
    if (uri.getRawQuery() != null) {
      canonical.append('?').append(uri.getRawQuery());
    }
    if (uri.getRawFragment() != null) {
      canonical.append('#').append(uri.getRawFragment());
    }
    return canonical.toString();
  }

  private static Map<String, String> archiveEntryDigests(byte[] archive) throws Exception {
    Map<String, String> entries = new LinkedHashMap<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        String path = entry.getName().replace('\\', '/');
        int rootEnd = path.indexOf('/');
        String normalized = rootEnd < 0 ? path : path.substring(rootEnd + 1);
        if (normalized.isBlank()) {
          continue;
        }
        String previous = entries.putIfAbsent(normalized, sha256Hex(zip.readAllBytes()));
        if (previous != null) {
          throw new AssertionError("Duplicate normalized Swift archive entry: " + normalized);
        }
      }
    }
    assertFalse(entries.isEmpty(), "Swift source archive must contain files");
    return entries;
  }

  private static String canonicalLinkTarget(String relation, String value) {
    if (!Set.of("latest-version", "predecessor-version", "successor-version")
        .contains(relation)) {
      return canonicalRegistryTarget(value);
    }
    final URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException ignored) {
      return value;
    }
    String path = uri.getPath();
    if (path == null) {
      return value;
    }
    List<String> segments = java.util.Arrays.stream(path.split("/"))
        .filter(segment -> !segment.isBlank())
        .toList();
    if (segments.size() < 3) {
      return canonicalRegistryTarget(value);
    }
    return "$RELEASE/" + String.join("/", segments.subList(segments.size() - 3, segments.size()));
  }

  private static Exchange successJsonExchange(String body, String link) {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    headers.put("content-type", List.of("application/json"));
    headers.put("content-version", List.of("1"));
    if (link != null && !link.isBlank()) {
      headers.put("link", List.of(link));
    }
    return new Exchange(200, body.getBytes(StandardCharsets.UTF_8), headers);
  }

  private static void assertSameBody(
      String label, Exchange reference, Exchange candidate, String contentType) {
    assertEquals(reference.status(), candidate.status(), label + " status");
    assertEquals(200, candidate.status(), label + " candidate status");
    assertContentType(reference, contentType, label + " Nexus Content-Type");
    assertContentType(candidate, contentType, label + " candidate Content-Type");
    assertArrayEquals(reference.body(), candidate.body(), label + " body");
  }

  private static void assertContentType(Exchange exchange, String expectedPrefix, String label) {
    assertTrue(exchange.header("content-type").toLowerCase(Locale.ROOT).startsWith(expectedPrefix),
        label + " expected " + expectedPrefix + " but got " + exchange.header("content-type"));
  }

  private static void assertProblem(String label, Exchange exchange) throws Exception {
    assertContentType(exchange, "application/problem+json", label + " Content-Type");
    JsonNode problem = json(exchange);
    assertTrue(problem.isObject(), label + " problem body");
    assertTrue(problem.hasNonNull("detail") || problem.hasNonNull("title"),
        label + " problem body must explain the failure");
    String contentVersion = exchange.header("content-version");
    assertTrue(contentVersion.isBlank() || "1".equals(contentVersion),
        label + " Content-Version must be v1 when present");
  }

  private static void assert2xx(String label, Exchange exchange) {
    assertTrue(exchange.status() >= 200 && exchange.status() < 300,
        label + " expected 2xx but got " + exchange.status() + " body=" + exchange.text());
  }

  private static JsonNode json(Exchange exchange) throws Exception {
    return JSON.readTree(exchange.body());
  }

  private static JsonNode sourceArchiveResource(JsonNode metadata) {
    for (JsonNode resource : metadata.path("resources")) {
      if ("source-archive".equals(resource.path("name").asText())
          && "application/zip".equals(resource.path("type").asText())) {
        return resource;
      }
    }
    throw new AssertionError("metadata does not contain the source-archive resource: " + metadata);
  }

  private static String sourceChecksum(JsonNode metadata) {
    return sourceArchiveResource(metadata).path("checksum").asText();
  }

  private static List<String> identifierSet(JsonNode body) {
    List<String> result = new ArrayList<>();
    body.path("identifiers").forEach(value -> result.add(value.asText().toLowerCase(Locale.ROOT)));
    return result.stream().sorted().toList();
  }

  private static String uniqueName(String prefix) {
    return ("compat-" + prefix + "-" + Long.toUnsignedString(System.nanoTime(), 36))
        .toLowerCase(Locale.ROOT);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static byte[] sha256(byte[] value) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(value);
  }

  private static String sha256Hex(byte[] value) throws Exception {
    return HexFormat.of().formatHex(sha256(value));
  }

  private static String basic(String username, String password) {
    return "Basic " + Base64.getEncoder().encodeToString(
        (username + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  private static boolean boolSetting(String property, String environment, boolean fallback) {
    return CompatDefaults.setting(property, environment)
        .map(Boolean::parseBoolean)
        .orElse(fallback);
  }

  private static int intSetting(String property, String environment, int fallback) {
    return CompatDefaults.setting(property, environment).map(Integer::parseInt).orElse(fallback);
  }

  private record RequestCase(String label, String path, String accept) {
  }

  private record ErrorRequestCase(
      String label, String path, String accept, int nexusStatus, int candidateStatus) {
  }

  private record CandidateGenericToken(long id, String token) {
  }

  private record RenamedRepositoryCase(
      String oldScope, String oldName, String newScope, String newName, String version) {
    static RenamedRepositoryCase parse(String value) {
      String[] parts = value.trim().split("/", -1);
      if (parts.length != 5 || java.util.Arrays.stream(parts).anyMatch(String::isBlank)) {
        throw new IllegalArgumentException(
            "SWIFT_COMPAT_RENAMED_REPOSITORY_CASE must be "
                + "oldScope/oldName/newScope/newName/version");
      }
      return new RenamedRepositoryCase(parts[0], parts[1], parts[2], parts[3], parts[4]);
    }

    String oldRepositoryUrl() {
      return "https://github.com/" + oldScope + "/" + oldName;
    }

    String newRepositoryUrl() {
      return "https://github.com/" + newScope + "/" + newName;
    }
  }

  private record TagCase(String scope, String name, String rawTag, String normalizedVersion) {
    static List<TagCase> parse(String value) {
      List<TagCase> result = new ArrayList<>();
      for (String item : value.split(",")) {
        String[] parts = item.trim().split("/", -1);
        if (parts.length != 4 || java.util.Arrays.stream(parts).anyMatch(String::isBlank)) {
          throw new IllegalArgumentException(
              "SWIFT_COMPAT_PROXY_TAG_CASES entries must be scope/name/rawTag/normalizedVersion");
        }
        result.add(new TagCase(parts[0], parts[1], parts[2], parts[3]));
      }
      return List.copyOf(result);
    }
  }

  private record Exchange(int status, byte[] body, Map<String, List<String>> headers) {
    String text() {
      return new String(body, StandardCharsets.UTF_8);
    }

    String header(String name) {
      return headers(name).stream().findFirst().orElse("");
    }

    List<String> headers(String name) {
      return headers.entrySet().stream()
          .filter(entry -> entry.getKey().equalsIgnoreCase(name))
          .flatMap(entry -> entry.getValue().stream())
          .toList();
    }
  }

  private record Endpoint(String baseUrl, String authorization, HttpClient client) {
    Endpoint(String baseUrl, String authorization) {
      this(baseUrl, authorization, HTTP);
    }

    HttpRequest.Builder request(String path) {
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url(path)));
      if (!authorization.isBlank()) {
        request.header("Authorization", authorization);
      }
      return request;
    }

    String url(String path) {
      return baseUrl + (path.isBlank() ? "" : "/" + path);
    }

    Endpoint anonymous() {
      return new Endpoint(baseUrl, "", client);
    }

    Endpoint withAuthorization(String value) {
      return new Endpoint(baseUrl, value, client);
    }

    Endpoint withNoRedirects() {
      HttpClient noRedirects = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(20))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
      return new Endpoint(baseUrl, authorization, noRedirects);
    }
  }

  private record Config(
      boolean enabled,
      boolean proxyEnabled,
      boolean requireProxyTagCases,
      String nexusBase,
      String candidateBase,
      String candidateSecondaryBase,
      String nexusAuth,
      String candidateAuth,
      String hosted,
      String secondaryHosted,
      String proxy,
      String group,
      String proxyScope,
      String proxyName,
      String proxyVersion,
      String proxyTagCases) {
    static Config load() {
      return new Config(
          boolSetting("compat.swift.enabled", "SWIFT_COMPAT_ENABLED", false),
          boolSetting("compat.swift.proxyEnabled", "SWIFT_COMPAT_PROXY_ENABLED", false),
          boolSetting(
              "compat.swift.requireProxyTagCases", "SWIFT_COMPAT_REQUIRE_PROXY_TAG_CASES", false),
          CompatDefaults.stripTrailingSlash(CompatDefaults.setting(
              "compat.swift.nexus.baseUrl", "SWIFT_NEXUS_COMPAT_BASE_URL")
              .orElseGet(() -> CompatDefaults.nexusBaseUrl().orElse(""))),
          CompatDefaults.stripTrailingSlash(CompatDefaults.setting(
              "compat.swift.kkrepo.baseUrl", "SWIFT_KKREPO_COMPAT_BASE_URL")
              .orElseGet(() -> CompatDefaults.nexusPlusBaseUrl().orElse(""))),
          CompatDefaults.setting(
              "compat.swift.kkrepo.secondaryBaseUrl", "SWIFT_KKREPO_SECONDARY_BASE_URL")
              .map(CompatDefaults::stripTrailingSlash).orElse(""),
          basic(CompatDefaults.setting("compat.swift.nexus.username", "SWIFT_NEXUS_COMPAT_USERNAME")
                  .orElseGet(() -> CompatDefaults.nexusUsername().orElse("")),
              CompatDefaults.setting("compat.swift.nexus.password", "SWIFT_NEXUS_COMPAT_PASSWORD")
                  .orElseGet(() -> CompatDefaults.nexusPassword().orElse(""))),
          basic(CompatDefaults.setting("compat.swift.kkrepo.username", "SWIFT_KKREPO_COMPAT_USERNAME")
                  .orElseGet(() -> CompatDefaults.nexusPlusUsername().orElse("")),
              CompatDefaults.setting("compat.swift.kkrepo.password", "SWIFT_KKREPO_COMPAT_PASSWORD")
                  .orElseGet(() -> CompatDefaults.nexusPlusPassword().orElse(""))),
          CompatDefaults.setting("compat.swift.hostedRepository", "SWIFT_COMPAT_HOSTED_REPOSITORY")
              .orElse("swift-compat-hosted"),
          CompatDefaults.setting(
              "compat.swift.secondaryHostedRepository", "SWIFT_COMPAT_SECONDARY_HOSTED_REPOSITORY")
              .orElse("swift-compat-hosted-secondary"),
          CompatDefaults.setting("compat.swift.proxyRepository", "SWIFT_COMPAT_PROXY_REPOSITORY")
              .orElse("swift-compat-proxy"),
          CompatDefaults.setting("compat.swift.groupRepository", "SWIFT_COMPAT_GROUP_REPOSITORY")
              .orElse("swift-compat-group"),
          CompatDefaults.setting("compat.swift.proxyScope", "SWIFT_COMPAT_PROXY_SCOPE")
              .orElse("apple"),
          CompatDefaults.setting("compat.swift.proxyName", "SWIFT_COMPAT_PROXY_NAME")
              .orElse("swift-log"),
          CompatDefaults.setting("compat.swift.proxyVersion", "SWIFT_COMPAT_PROXY_VERSION")
              .orElse("1.6.3"),
          CompatDefaults.setting("compat.swift.proxyTagCases", "SWIFT_COMPAT_PROXY_TAG_CASES")
              .orElse(""));
    }

    Endpoint nexusHosted() {
      return nexusRepository(hosted);
    }

    Endpoint nexusSecondaryHosted() {
      return nexusRepository(secondaryHosted);
    }

    Endpoint nexusProxy() {
      return nexusRepository(proxy);
    }

    Endpoint nexusGroup() {
      return nexusRepository(group);
    }

    Endpoint candidateHosted() {
      return candidateRepository(hosted);
    }

    Endpoint candidateSecondaryHosted() {
      return candidateRepository(secondaryHosted);
    }

    Endpoint candidateProxy() {
      return candidateRepository(proxy);
    }

    Endpoint candidateGroup() {
      return candidateRepository(group);
    }

    private Endpoint nexusRepository(String repository) {
      return new Endpoint(nexusBase + "/repository/" + repository, nexusAuth);
    }

    private Endpoint candidateRepository(String repository) {
      return new Endpoint(candidateBase + "/repository/" + repository, candidateAuth);
    }

    Endpoint candidateSecondaryReplica(String repository) {
      if (candidateSecondaryBase.isBlank()) {
        throw new IllegalStateException("candidate secondary base URL is not configured");
      }
      return new Endpoint(candidateSecondaryBase + "/repository/" + repository, candidateAuth);
    }

    HttpRequest.Builder nexusAdmin(String path) {
      return HttpRequest.newBuilder(URI.create(nexusBase + path)).header("Authorization", nexusAuth);
    }

    HttpRequest.Builder candidateAdmin(String path) {
      return HttpRequest.newBuilder(URI.create(candidateBase + path))
          .header("Authorization", candidateAuth);
    }
  }

  private record Fixture(
      String scope,
      String name,
      String version,
      byte[] archive,
      byte[] manifest,
      byte[] versionedManifest,
      byte[] signature,
      String metadata,
      String repositoryUrl) {
    static Fixture unsigned(String name, String version, String marker) throws Exception {
      return create(name, version, marker, false);
    }

    static Fixture signed(String name, String version, String marker) throws Exception {
      return create(name, version, marker, true);
    }

    static Fixture withArchive(
        String name, String version, byte[] archive, byte[] signature, String metadata) {
      return new Fixture("kkrepo", name, version, archive, new byte[0], new byte[0], signature,
          metadata, "https://github.com/kkrepo-fixtures/" + name + ".git");
    }

    private static Fixture create(String name, String version, String marker, boolean signed)
        throws Exception {
      String module = moduleName(name);
      byte[] manifest = manifest(module, "5.7", marker).getBytes(StandardCharsets.UTF_8);
      byte[] versioned = manifest(module, "5.9", marker + "-swift-5.9")
          .getBytes(StandardCharsets.UTF_8);
      byte[] archive = archive(name, version, module, marker, manifest, versioned);
      byte[] signature = signed ? cmsSignature(archive) : null;
      String repositoryUrl = "https://github.com/kkrepo-fixtures/" + name + ".git";
      String metadata = """
          {"description":"kkrepo Swift compatibility fixture %s",
           "repositoryURLs":["%s"],
           "licenseURL":"https://www.apache.org/licenses/LICENSE-2.0",
           "author":{"name":"kkrepo compatibility"}}
          """.formatted(marker, repositoryUrl);
      return new Fixture("kkrepo", name, version, archive, manifest, versioned, signature,
          metadata, repositoryUrl);
    }

    String coordinatePath() {
      return scope + "/" + name + "/" + version;
    }

    String archivePath() {
      return scope + "/" + name + "/" + version + ".zip";
    }

    String manifestPath() {
      return coordinatePath() + "/Package.swift";
    }

    private static String moduleName(String name) {
      StringBuilder value = new StringBuilder("Compat");
      boolean uppercase = true;
      for (char character : name.toCharArray()) {
        if (Character.isLetterOrDigit(character)) {
          value.append(uppercase ? Character.toUpperCase(character) : character);
          uppercase = false;
        } else {
          uppercase = true;
        }
      }
      return value.toString();
    }

    private static String manifest(String module, String toolsVersion, String marker) {
      return """
          // swift-tools-version:%s
          import PackageDescription
          let package = Package(
              name: "%s",
              products: [.library(name: "%s", targets: ["%s"])],
              targets: [.target(name: "%s")]
          )
          // %s
          """.formatted(toolsVersion, module, module, module, module, marker);
    }

    private static byte[] archive(
        String name, String version, String module, String marker,
        byte[] manifest, byte[] versionedManifest) throws Exception {
      String root = name + "-" + version + "/";
      Map<String, byte[]> entries = new LinkedHashMap<>();
      entries.put(root + "Package.swift", manifest);
      entries.put(root + "Package@swift-5.9.swift", versionedManifest);
      entries.put(root + "Sources/" + module + "/" + module + ".swift",
          ("public enum " + module + " { public static let marker = \"" + marker + "\" }\n")
              .getBytes(StandardCharsets.UTF_8));
      entries.put(root + "README.md", ("# " + module + "\n").getBytes(StandardCharsets.UTF_8));
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (ZipOutputStream zip = new ZipOutputStream(output)) {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
          ZipEntry zipEntry = new ZipEntry(entry.getKey());
          zipEntry.setTime(315532800000L);
          zip.putNextEntry(zipEntry);
          zip.write(entry.getValue());
          zip.closeEntry();
        }
      }
      return output.toByteArray();
    }

    private static byte[] cmsSignature(byte[] content) throws Exception {
      if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
        Security.addProvider(new BouncyCastleProvider());
      }
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KeyPair keyPair = generator.generateKeyPair();
      org.bouncycastle.asn1.x500.X500Name subject =
          new org.bouncycastle.asn1.x500.X500Name("CN=kkrepo Swift compatibility");
      Instant now = Instant.now();
      ContentSigner certificateSigner = new JcaContentSignerBuilder("SHA256withRSA")
          .setProvider(BouncyCastleProvider.PROVIDER_NAME)
          .build(keyPair.getPrivate());
      X509CertificateHolder certificate = new JcaX509v3CertificateBuilder(
          subject,
          BigInteger.valueOf(System.nanoTime()).abs().add(BigInteger.ONE),
          Date.from(now.minus(Duration.ofMinutes(1))),
          Date.from(now.plus(Duration.ofDays(1))),
          subject,
          keyPair.getPublic()).build(certificateSigner);
      CMSSignedDataGenerator cms = new CMSSignedDataGenerator();
      cms.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
          new JcaDigestCalculatorProviderBuilder()
              .setProvider(BouncyCastleProvider.PROVIDER_NAME).build())
          .build(new JcaContentSignerBuilder("SHA256withRSA")
              .setProvider(BouncyCastleProvider.PROVIDER_NAME)
              .build(keyPair.getPrivate()), certificate));
      Store<X509CertificateHolder> certificates = new CollectionStore<>(List.of(certificate));
      cms.addCertificates(certificates);
      return cms.generate(new CMSProcessableByteArray(content), false).getEncoded();
    }
  }

  private record Multipart(String boundary, byte[] body) {
    static Multipart forFixture(Fixture fixture) throws Exception {
      return forFixture(fixture, "binary", "binary");
    }

    static Multipart forFixture(
        Fixture fixture, String archiveTransferEncoding, String metadataTransferEncoding)
        throws Exception {
      String boundary = "kkrepo-swift-" + Long.toUnsignedString(System.nanoTime(), 36);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      part(output, boundary, "source-archive", fixture.name() + "-" + fixture.version() + ".zip",
          "application/zip", archiveTransferEncoding, fixture.archive());
      if (fixture.signature() != null) {
        part(output, boundary, "source-archive-signature", "source-archive.cms",
            "application/octet-stream", "binary", fixture.signature());
      }
      part(output, boundary, "metadata", null, "application/json",
          metadataTransferEncoding, fixture.metadata().getBytes(StandardCharsets.UTF_8));
      output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
      return new Multipart(boundary, output.toByteArray());
    }

    private static void part(
        ByteArrayOutputStream output,
        String boundary,
        String name,
        String filename,
        String contentType,
        String transferEncoding,
        byte[] content) throws Exception {
      byte[] encoded = encodeTransfer(content, transferEncoding);
      output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.US_ASCII));
      String disposition = "Content-Disposition: form-data; name=\"" + name + "\""
          + (filename == null ? "" : "; filename=\"" + filename + "\"") + "\r\n";
      output.write(disposition.getBytes(StandardCharsets.US_ASCII));
      output.write(("Content-Type: " + contentType + "\r\n")
          .getBytes(StandardCharsets.US_ASCII));
      output.write(("Content-Length: " + encoded.length + "\r\n")
          .getBytes(StandardCharsets.US_ASCII));
      output.write(("Content-Transfer-Encoding: " + transferEncoding + "\r\n\r\n")
          .getBytes(StandardCharsets.US_ASCII));
      output.write(encoded);
      output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] encodeTransfer(byte[] content, String transferEncoding) {
      return switch (transferEncoding) {
        case "binary", "7bit", "8bit" -> content;
        case "base64" -> Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
            .encode(content);
        case "quoted-printable" -> {
          StringBuilder encoded = new StringBuilder(content.length * 3);
          for (byte value : content) {
            encoded.append('=').append(String.format(Locale.ROOT, "%02X", value & 0xff));
          }
          yield encoded.toString().getBytes(StandardCharsets.US_ASCII);
        }
        default -> throw new IllegalArgumentException(
            "Unsupported fixture transfer encoding: " + transferEncoding);
      };
    }
  }
}
