package com.github.klboke.kkrepo.server.swift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SwiftGitHubClientTest {

  @Test
  void normalizesSupportedGitHubRepositoryUrlForms() {
    assertCoordinates("https://github.com/Apple/swift-log", "Apple", "swift-log");
    assertCoordinates("https://github.com/Apple/swift-log.git/", "Apple", "swift-log");
    assertCoordinates("git@github.com:Apple/swift-log.git", "Apple", "swift-log");
    assertCoordinates("ssh://git@github.com/Apple/swift-log.git", "Apple", "swift-log");
  }

  @Test
  void rejectsCredentialsAmbiguousPathsAndNonGitHubHosts() {
    for (String url : List.of(
        "https://token@github.com/apple/swift-log",
        "https://github.com/apple/swift-log/tree/main",
        "https://github.com/apple/swift-log?ref=main",
        "https://github.com/apple/swift-log#readme",
        "https://github.example/apple/swift-log",
        "http://github.com/apple/swift-log",
        "https://github.com/-apple/swift-log",
        "https://github.com/apple/-swift-log")) {
      assertTrue(SwiftGitHubClient.coordinatesFromUrl(url).isEmpty(), url);
    }
  }

  @Test
  void acceptsOnlySemverTagsWithOneOptionalVPrefix() {
    assertEquals("1.2.3", SwiftGitHubClient.normalizeTag("v1.2.3").orElseThrow());
    assertEquals("1.2.3-beta.1+build.7",
        SwiftGitHubClient.normalizeTag("V1.2.3-beta.1+build.7").orElseThrow());
    assertEquals("1.2.3", SwiftGitHubClient.normalizeTag("1.2.3").orElseThrow());
    for (String tag : List.of(
        "1.2",
        "v01.2.3",
        "vv1.2.3",
        "release-1.2.3",
        "1.2.3-",
        "1.2.3-01",
        "1.2.3-alpha..1",
        " 1.2.3",
        "1.2.3 ")) {
      assertFalse(SwiftGitHubClient.normalizeTag(tag).isPresent(), tag);
    }
  }

  @Test
  void restrictsProxyRemoteToTheOfficialGithubOriginRoot() {
    SwiftGitHubClient.requireGitHubProxy(proxy("https://github.com/"));
    SwiftGitHubClient.requireGitHubProxy(proxy("https://GITHUB.COM"));

    for (RepositoryRuntime runtime : List.of(
        hosted(),
        proxy("http://github.com/"),
        proxy("https://github.com/apple"),
        proxy("https://user@github.com/"),
        proxy("https://github.com/?page=1"))) {
      assertThrows(SwiftExceptions.SwiftException.class,
          () -> SwiftGitHubClient.requireGitHubProxy(runtime));
    }
  }

  @Test
  @Timeout(15)
  void listsMoreThanOneThousandTagsWithinTheBoundedPaginationBudget() throws Exception {
    HttpRemoteFetcher fetcher = mock(HttpRemoteFetcher.class);
    ObjectMapper mapper = new ObjectMapper();
    AtomicInteger requests = new AtomicInteger();
    doAnswer(invocation -> {
      HttpRemoteFetcher.Request request = invocation.getArgument(0);
      int page = Integer.parseInt(request.url().replaceFirst("^.*[?&]page=", ""));
      List<Map<String, Object>> rows = new ArrayList<>();
      if (page <= 12) {
        for (int index = 0; index < 100; index++) {
          int ordinal = ((page - 1) * 100) + index + 1;
          rows.add(Map.of(
              "name", "1." + (ordinal / 100) + "." + (ordinal % 100),
              "commit", Map.of("sha", "%040x".formatted(ordinal))));
        }
      }
      requests.incrementAndGet();
      byte[] body = mapper.writeValueAsString(rows).getBytes(StandardCharsets.UTF_8);
      HttpRemoteFetcher.Result result = new HttpRemoteFetcher.Result(
          200, Map.of("Content-Type", "application/json"), new ByteArrayInputStream(body));
      @SuppressWarnings("unchecked")
      HttpRemoteFetcher.ResultHandler<Object> handler = invocation.getArgument(2);
      return handler.handle(result);
    }).when(fetcher).fetchWithBodyRetry(any(), anyString(), any());

    SwiftGitHubClient client = new SwiftGitHubClient(
        fetcher, mapper, mock(SwiftArchiveInspector.class));
    List<SwiftGitHubClient.Tag> tags = client.tags(
        proxy("https://github.com/"), SwiftGitHubClient.coordinates("apple", "swift-syntax"));

    assertEquals(1_200, tags.size());
    assertEquals(13, requests.get(), "the first short page terminates pagination");
  }

  @Test
  void retriesBoundedTransientGitHubStatusesAndHonorsRetryAfter() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    byte[] tags = mapper.writeValueAsBytes(List.of(Map.of(
        "name", "1.2.3",
        "commit", Map.of("sha", "a".repeat(40)))));
    SequencedFetcher fetcher = new SequencedFetcher(
        result(503, Map.of("Retry-After", "2"), new byte[0]),
        result(502, Map.of(), new byte[0]),
        result(200, Map.of("Content-Type", "application/json"), tags));
    List<Duration> delays = new ArrayList<>();
    SwiftGitHubClient client = new SwiftGitHubClient(
        fetcher,
        mapper,
        mock(SwiftArchiveInspector.class),
        delays::add);

    List<SwiftGitHubClient.Tag> result = client.tags(
        proxy("https://github.com/"), SwiftGitHubClient.coordinates("apple", "swift-log"));

    assertEquals(List.of(new SwiftGitHubClient.Tag("1.2.3", "1.2.3", "a".repeat(40))), result);
    assertEquals(3, fetcher.calls);
    assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(2)), delays);
  }

  @Test
  void stopsAfterTheBoundedTransientGitHubRetryBudget() {
    SequencedFetcher fetcher = new SequencedFetcher(
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]));
    List<Duration> delays = new ArrayList<>();
    SwiftGitHubClient client = new SwiftGitHubClient(
        fetcher,
        new ObjectMapper(),
        mock(SwiftArchiveInspector.class),
        delays::add);

    SwiftExceptions.BadUpstream failure = assertThrows(
        SwiftExceptions.BadUpstream.class,
        () -> client.tags(
            proxy("https://github.com/"),
            SwiftGitHubClient.coordinates("apple", "swift-log")));

    assertEquals(5, fetcher.calls);
    assertTrue(SwiftGitHubClient.isTransientTagAvailabilityFailure(failure));
    assertEquals(
        List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)),
        delays);
  }

  @Test
  void fallsBackToBoundedGitSmartHttpTagDiscoveryAfterApiOutage() throws Exception {
    byte[] advertisement = gitAdvertisement(
        "a".repeat(40) + " refs/tags/v1.2.3\n",
        "b".repeat(40) + " refs/tags/V2.0.0\n",
        "c".repeat(40) + " refs/tags/V2.0.0^{}\n",
        "d".repeat(40) + " refs/tags/not-semver\n");
    SequencedFetcher fetcher = new SequencedFetcher(
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]),
        result(
            200,
            Map.of("Content-Type", "application/x-git-upload-pack-advertisement"),
            advertisement));
    List<Duration> delays = new ArrayList<>();
    SwiftGitHubClient client = new SwiftGitHubClient(
        fetcher,
        new ObjectMapper(),
        mock(SwiftArchiveInspector.class),
        delays::add);

    List<SwiftGitHubClient.Tag> tags = client.tags(
        proxy("https://github.com/"), SwiftGitHubClient.coordinates("apple", "swift-log"));

    assertEquals(List.of(
        new SwiftGitHubClient.Tag("1.2.3", "v1.2.3", "a".repeat(40)),
        new SwiftGitHubClient.Tag("2.0.0", "V2.0.0", "c".repeat(40))), tags);
    assertEquals(5, fetcher.calls);
    assertEquals(
        "https://github.com/apple/swift-log.git/info/refs?service=git-upload-pack",
        fetcher.requests.get(4).url());
    assertNull(fetcher.requests.get(4).authorizationHeader());
    assertEquals(
        List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)),
        delays);
  }

  @Test
  void fallsBackToGithubCommitArchiveAfterArchiveApiOutage() throws Exception {
    byte[] archive = swiftArchive();
    SequencedFetcher fetcher = new SequencedFetcher(
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]),
        result(200, Map.of("Content-Type", "application/zip"), archive));
    List<Duration> delays = new ArrayList<>();
    SwiftGitHubClient client = new SwiftGitHubClient(
        fetcher,
        new ObjectMapper(),
        new SwiftArchiveInspector(1024 * 1024, 1024 * 1024, 20),
        delays::add);

    SwiftArchiveInspector.InspectedArchive inspected = client.archive(
        proxy("https://github.com/"),
        SwiftGitHubClient.coordinates("apple", "swift-log"),
        "a".repeat(40));

    try {
      assertEquals(archive.length, inspected.size());
      assertEquals(5, fetcher.calls);
      assertEquals(
          "https://github.com/apple/swift-log/archive/" + "a".repeat(40) + ".zip",
          fetcher.requests.get(4).url());
      assertNull(fetcher.requests.get(4).authorizationHeader());
      assertTrue(fetcher.requests.get(4).allowedUnsignedRedirectHosts()
          .contains("codeload.github.com"));
      assertEquals(
          List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)),
          delays);
    } finally {
      Files.deleteIfExists(inspected.file());
    }
  }

  @Test
  void keepsUsingAnonymousAcrossTagPagesAfterAuthenticatedTransientFailures()
      throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    List<Map<String, Object>> firstPage = new ArrayList<>();
    for (int index = 0; index < 100; index++) {
      firstPage.add(Map.of(
          "name", "1.2." + index,
          "commit", Map.of("sha", "%040x".formatted(index + 1))));
    }
    SequencedFetcher fetcher = new SequencedFetcher(
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]),
        result(
            200,
            Map.of("Content-Type", "application/json"),
            mapper.writeValueAsBytes(firstPage)),
        result(
            200,
            Map.of("Content-Type", "application/json"),
            "[]".getBytes(StandardCharsets.UTF_8)));
    List<Duration> delays = new ArrayList<>();
    SwiftGitHubClient client = new SwiftGitHubClient(
        fetcher,
        mapper,
        mock(SwiftArchiveInspector.class),
        delays::add);

    List<SwiftGitHubClient.Tag> result = client.tags(
        proxy("https://github.com/", "workflow-token"),
        SwiftGitHubClient.coordinates("apple", "swift-log"));

    assertEquals(100, result.size());
    assertEquals(6, fetcher.calls);
    for (int index = 0; index < 4; index++) {
      assertEquals(
          "Bearer workflow-token", fetcher.requests.get(index).authorizationHeader());
    }
    assertNull(fetcher.requests.get(4).authorizationHeader());
    assertNull(fetcher.requests.get(5).authorizationHeader());
    assertEquals(
        List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)),
        delays);
  }

  @Test
  void preservesTheAuthenticatedFailureWhenAnonymousCannotSeeTheRepository() {
    SequencedFetcher fetcher = new SequencedFetcher(
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]),
        result(404, Map.of(), new byte[0]),
        result(404, Map.of(), new byte[0]));
    SwiftGitHubClient client = new SwiftGitHubClient(
        fetcher,
        new ObjectMapper(),
        mock(SwiftArchiveInspector.class),
        ignored -> {});

    SwiftExceptions.BadUpstream failure = assertThrows(
        SwiftExceptions.BadUpstream.class,
        () -> client.tags(
            proxy("https://github.com/", "private-token"),
            SwiftGitHubClient.coordinates("private", "repository")));

    assertEquals(502, failure.status());
    assertTrue(failure.getMessage().contains("HTTP 503"));
    assertFalse(SwiftGitHubClient.isTransientTagAvailabilityFailure(failure));
    assertEquals(6, fetcher.calls);
    assertNull(fetcher.requests.get(4).authorizationHeader());
    assertNull(fetcher.requests.get(5).authorizationHeader());
  }

  @Test
  void doesNotDoubleTheRetryDelayWhenAnonymousGitHubIsAlsoUnavailable() {
    SequencedFetcher fetcher = new SequencedFetcher(
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]));
    List<Duration> delays = new ArrayList<>();
    SwiftGitHubClient client = new SwiftGitHubClient(
        fetcher,
        new ObjectMapper(),
        mock(SwiftArchiveInspector.class),
        delays::add);

    SwiftExceptions.BadUpstream failure = assertThrows(
        SwiftExceptions.BadUpstream.class,
        () -> client.tags(
            proxy("https://github.com/", "workflow-token"),
            SwiftGitHubClient.coordinates("apple", "swift-log")));

    assertEquals(6, fetcher.calls);
    assertNull(fetcher.requests.get(4).authorizationHeader());
    assertNull(fetcher.requests.get(5).authorizationHeader());
    assertTrue(SwiftGitHubClient.isTransientTagAvailabilityFailure(failure));
    assertEquals(
        List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)),
        delays);
  }

  @Test
  void doesNotTreatAnonymousRestFailureAsSmartHttpOutage() {
    SequencedFetcher fetcher = new SequencedFetcher(
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]),
        result(503, Map.of(), new byte[0]),
        result(404, Map.of(), new byte[0]));
    SwiftGitHubClient client = new SwiftGitHubClient(
        fetcher,
        new ObjectMapper(),
        mock(SwiftArchiveInspector.class),
        ignored -> {});

    SwiftExceptions.BadUpstream failure = assertThrows(
        SwiftExceptions.BadUpstream.class,
        () -> client.tags(
            proxy("https://github.com/", "private-token"),
            SwiftGitHubClient.coordinates("private", "repository")));

    assertEquals(6, fetcher.calls);
    assertFalse(SwiftGitHubClient.isTransientTagAvailabilityFailure(failure));
  }

  @Test
  void doesNotRetryBeforeALongGitHubRetryAfterWindow() {
    SequencedFetcher fetcher = new SequencedFetcher(
        result(503, Map.of("Retry-After", "30"), new byte[0]));
    List<Duration> delays = new ArrayList<>();
    SwiftGitHubClient client = new SwiftGitHubClient(
        fetcher,
        new ObjectMapper(),
        mock(SwiftArchiveInspector.class),
        delays::add);

    SwiftExceptions.BadUpstream failure = assertThrows(
        SwiftExceptions.BadUpstream.class,
        () -> client.tags(
            proxy("https://github.com/", "workflow-token"),
            SwiftGitHubClient.coordinates("apple", "swift-log")));

    assertEquals(1, fetcher.calls);
    assertEquals("Bearer workflow-token", fetcher.requests.get(0).authorizationHeader());
    assertTrue(delays.isEmpty());
    assertFalse(SwiftGitHubClient.isTransientTagAvailabilityFailure(failure));
  }

  @Test
  void retriesAnInterruptedArchiveBodyWithAFreshUpstreamRequest() throws Exception {
    byte[] archive = swiftArchive();
    SequencedFetcher fetcher = new SequencedFetcher(
        new HttpRemoteFetcher.Result(200, Map.of(), interruptedBody()),
        new HttpRemoteFetcher.Result(200, Map.of(), new ByteArrayInputStream(archive)));
    SwiftGitHubClient client = new SwiftGitHubClient(
        fetcher,
        new ObjectMapper(),
        new SwiftArchiveInspector(1024 * 1024, 1024 * 1024, 20));

    SwiftArchiveInspector.InspectedArchive inspected = client.archive(
        proxy("https://github.com/"),
        SwiftGitHubClient.coordinates("apple", "swift-syntax"),
        "a".repeat(40));

    try {
      assertEquals(archive.length, inspected.size());
      assertEquals(2, fetcher.calls);
    } finally {
      Files.deleteIfExists(inspected.file());
    }
  }

  private static InputStream interruptedBody() {
    return new InputStream() {
      @Override
      public int read() throws IOException {
        throw new EOFException("truncated GitHub archive");
      }
    };
  }

  private static HttpRemoteFetcher.Result result(
      int status, Map<String, String> headers, byte[] body) {
    return new HttpRemoteFetcher.Result(status, headers, new ByteArrayInputStream(body));
  }

  private static byte[] swiftArchive() throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      zip.putNextEntry(new ZipEntry("swift-syntax/Package.swift"));
      zip.write(("// swift-tools-version: 5.9\n"
          + "import PackageDescription\n"
          + "let package = Package(name: \"swift-syntax\")\n")
          .getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return output.toByteArray();
  }

  private static byte[] gitAdvertisement(String... references) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    writeGitPacket(output, "# service=git-upload-pack\n");
    output.write("0000".getBytes(StandardCharsets.US_ASCII));
    for (String reference : references) {
      writeGitPacket(output, reference);
    }
    output.write("0000".getBytes(StandardCharsets.US_ASCII));
    return output.toByteArray();
  }

  private static void writeGitPacket(ByteArrayOutputStream output, String payload)
      throws IOException {
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    output.write("%04x".formatted(bytes.length + 4).getBytes(StandardCharsets.US_ASCII));
    output.write(bytes);
  }

  private static void assertCoordinates(String url, String owner, String repository) {
    SwiftGitHubClient.Coordinates coordinates =
        SwiftGitHubClient.coordinatesFromUrl(url).orElseThrow();
    assertEquals(owner, coordinates.owner());
    assertEquals(repository, coordinates.repository());
    assertEquals("https://github.com/" + owner + "/" + repository,
        coordinates.repositoryUrl());
  }

  private static RepositoryRuntime hosted() {
    return runtime(RepositoryType.HOSTED, null);
  }

  private static RepositoryRuntime proxy(String remoteUrl) {
    return runtime(RepositoryType.PROXY, remoteUrl);
  }

  private static RepositoryRuntime proxy(String remoteUrl, String bearerToken) {
    return runtime(RepositoryType.PROXY, remoteUrl, bearerToken);
  }

  private static RepositoryRuntime runtime(RepositoryType type, String remoteUrl) {
    return runtime(type, remoteUrl, null);
  }

  private static RepositoryRuntime runtime(
      RepositoryType type, String remoteUrl, String bearerToken) {
    return new RepositoryRuntime(
        1L,
        "swift",
        RepositoryFormat.SWIFT,
        type,
        "swift-" + type.name().toLowerCase(),
        true,
        1L,
        "ALLOW_ONCE",
        null,
        null,
        true,
        remoteUrl,
        null,
        null,
        true,
        null,
        null,
        bearerToken,
        null,
        null,
        null,
        null,
        null,
        List.of());
  }

  private static final class SequencedFetcher extends HttpRemoteFetcher {
    private final Queue<HttpRemoteFetcher.Result> results = new ArrayDeque<>();
    private final List<HttpRemoteFetcher.Request> requests = new ArrayList<>();
    private int calls;

    private SequencedFetcher(HttpRemoteFetcher.Result... results) {
      super(null);
      this.results.addAll(List.of(results));
    }

    @Override
    public HttpRemoteFetcher.Result fetch(HttpRemoteFetcher.Request request) {
      calls++;
      requests.add(request);
      return results.remove();
    }
  }
}
