package com.github.klboke.kkrepo.server.swift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
  @Timeout(5)
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

  private static RepositoryRuntime runtime(RepositoryType type, String remoteUrl) {
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
        List.of());
  }
}
