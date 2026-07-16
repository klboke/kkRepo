package com.github.klboke.kkrepo.server.swift;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.swift.SwiftVersions;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.UpstreamBodyReadException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Bounded GitHub source adapter used by Nexus-compatible Swift proxy repositories. */
@Component
final class SwiftGitHubClient {
  private static final String API = "https://api.github.com/repos/";
  private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})");
  private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._-]{0,99})");
  private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{40,64}");
  private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS =
      new TypeReference<>() {};
  /** Bounds one refresh to 10,000 upstream tags while still supporting large Swift repositories. */
  private static final int MAX_TAG_PAGES = 100;

  private final HttpRemoteFetcher fetcher;
  private final ObjectMapper mapper;
  private final SwiftArchiveInspector inspector;

  SwiftGitHubClient(
      HttpRemoteFetcher fetcher, ObjectMapper mapper, SwiftArchiveInspector inspector) {
    this.fetcher = fetcher;
    this.mapper = mapper;
    this.inspector = inspector;
  }

  List<Tag> tags(RepositoryRuntime runtime, Coordinates coordinates) {
    requireGitHubProxy(runtime);
    List<Tag> tags = new ArrayList<>();
    Map<String, Tag> byVersion = new LinkedHashMap<>();
    boolean exhausted = false;
    for (int page = 1; page <= MAX_TAG_PAGES; page++) {
      String url = API + coordinates.owner() + "/" + coordinates.repository()
          + "/tags?per_page=100&page=" + page;
      List<Map<String, Object>> rows = getJsonList(runtime, url, "GitHub tags");
      for (Map<String, Object> row : rows) {
        String tagName = text(row.get("name"));
        String version = normalizeTag(tagName).orElse(null);
        Object commitRaw = row.get("commit");
        String commit = commitRaw instanceof Map<?, ?> commitMap
            ? text(commitMap.get("sha")) : null;
        if (version == null || commit == null || !SHA.matcher(commit).matches()) {
          continue;
        }
        byVersion.putIfAbsent(
            version,
            new Tag(version, tagName, commit.toLowerCase(Locale.ROOT)));
      }
      if (rows.size() < 100) {
        exhausted = true;
        break;
      }
    }
    if (!exhausted) {
      throw new SwiftExceptions.BadUpstream(
          "GitHub tag listing exceeded the configured pagination safety limit");
    }
    tags.addAll(byVersion.values());
    return List.copyOf(tags);
  }

  SwiftArchiveInspector.InspectedArchive archive(
      RepositoryRuntime runtime, Coordinates coordinates, String commitSha) {
    requireGitHubProxy(runtime);
    if (commitSha == null || !SHA.matcher(commitSha).matches()) {
      throw new SwiftExceptions.BadUpstream("GitHub returned an invalid commit SHA");
    }
    String url = API + coordinates.owner() + "/" + coordinates.repository()
        + "/zipball/" + commitSha;
    HttpRemoteFetcher.Request request = request(runtime, url, true);
    try {
      return fetcher.fetchWithBodyRetry(request, coordinates.identity() + "/" + commitSha, result -> {
        if (result.status() == 404 || result.status() == 410) {
          throw new SwiftExceptions.NotFound("GitHub release source was not found");
        }
        if (result.status() == 403 || result.status() == 429) {
          throw rateLimited(result);
        }
        if (result.status() < 200 || result.status() >= 300) {
          throw new SwiftExceptions.BadUpstream(
              "GitHub archive request returned HTTP " + result.status());
        }
        return inspector.inspect(UpstreamBodyReadException.wrap(result.body()));
      });
    } catch (IOException e) {
      throw new SwiftExceptions.BadUpstream("Unable to download GitHub source archive", e);
    }
  }

  private List<Map<String, Object>> getJsonList(
      RepositoryRuntime runtime, String url, String resource) {
    try {
      return fetcher.fetchWithBodyRetry(request(runtime, url, false), resource, result -> {
        if (result.status() == 404 || result.status() == 410) {
          throw new SwiftExceptions.NotFound(resource + " not found");
        }
        if (result.status() == 403 || result.status() == 429) {
          throw rateLimited(result);
        }
        if (result.status() < 200 || result.status() >= 300) {
          throw new SwiftExceptions.BadUpstream(
              resource + " request returned HTTP " + result.status());
        }
        return mapper.readValue(result.body(), LIST_OF_MAPS);
      });
    } catch (IOException e) {
      throw new SwiftExceptions.BadUpstream("Unable to fetch " + resource, e);
    }
  }

  private static SwiftExceptions.UpstreamRateLimited rateLimited(
      HttpRemoteFetcher.Result result) {
    Instant now = Instant.now();
    Instant retryAfter = parseRetryAfter(result.header("Retry-After"), now)
        .or(() -> parseEpoch(result.header("X-RateLimit-Reset")))
        .filter(candidate -> candidate.isAfter(now))
        .orElse(now.plusSeconds(60));
    return new SwiftExceptions.UpstreamRateLimited(
        "GitHub rate limit or authorization rejected the request", retryAfter);
  }

  private static Optional<Instant> parseRetryAfter(String value, Instant now) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(now.plusSeconds(Math.max(1L, Long.parseLong(value.trim()))));
    } catch (NumberFormatException ignored) {
      try {
        return Optional.of(ZonedDateTime.parse(
            value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
      } catch (RuntimeException invalidDate) {
        return Optional.empty();
      }
    }
  }

  private static Optional<Instant> parseEpoch(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Instant.ofEpochSecond(Long.parseLong(value.trim())));
    } catch (RuntimeException ignored) {
      return Optional.empty();
    }
  }

  private static HttpRemoteFetcher.Request request(
      RepositoryRuntime runtime, String url, boolean allowCodeloadRedirect) {
    return new HttpRemoteFetcher.Request(
        url,
        null,
        null,
        null,
        allowCodeloadRedirect
            ? HttpRemoteFetcher.TimeoutProfile.CONTENT
            : HttpRemoteFetcher.TimeoutProfile.METADATA,
        false,
        runtime.name(),
        runtime.format().name(),
        "api.github.com",
        authorization(runtime),
        allowCodeloadRedirect ? Set.of("codeload.github.com") : Set.of());
  }

  static Coordinates coordinates(String owner, String repository) {
    if (owner == null || repository == null || !OWNER.matcher(owner).matches()
        || !REPOSITORY.matcher(repository).matches()) {
      throw new SwiftExceptions.BadRequest("Invalid GitHub owner or repository name");
    }
    return new Coordinates(owner, repository);
  }

  static Optional<Coordinates> coordinatesFromUrl(String rawUrl) {
    if (rawUrl == null || rawUrl.isBlank() || rawUrl.length() > 4096
        || rawUrl.indexOf('\r') >= 0 || rawUrl.indexOf('\n') >= 0) {
      return Optional.empty();
    }
    String value = rawUrl.trim();
    if (value.startsWith("git@github.com:")) {
      value = "https://github.com/" + value.substring("git@github.com:".length());
    } else if (value.startsWith("ssh://git@github.com/")) {
      value = "https://github.com/" + value.substring("ssh://git@github.com/".length());
    }
    URI uri;
    try {
      uri = URI.create(value);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
    if (uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null
        || uri.getHost() == null || !"github.com".equalsIgnoreCase(uri.getHost())
        || uri.getScheme() == null
        || !("https".equalsIgnoreCase(uri.getScheme()) || "ssh".equalsIgnoreCase(uri.getScheme()))) {
      return Optional.empty();
    }
    String path = uri.getPath() == null ? "" : uri.getPath();
    while (path.startsWith("/")) path = path.substring(1);
    while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
    if (path.endsWith(".git")) path = path.substring(0, path.length() - 4);
    String[] segments = path.split("/", -1);
    if (segments.length != 2) {
      return Optional.empty();
    }
    try {
      return Optional.of(coordinates(segments[0], segments[1]));
    } catch (SwiftExceptions.BadRequest ignored) {
      return Optional.empty();
    }
  }

  static Optional<String> normalizeTag(String rawTag) {
    if (rawTag == null || rawTag.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(SwiftVersions.normalizeGitTag(rawTag));
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
  }

  static void requireGitHubProxy(RepositoryRuntime runtime) {
    if (runtime == null || !runtime.isProxy()) {
      throw new SwiftExceptions.MethodNotAllowed("Swift GitHub source access requires a proxy repository");
    }
    URI remote;
    try {
      remote = URI.create(runtime.proxyRemoteUrl());
    } catch (RuntimeException e) {
      throw new SwiftExceptions.BadRequest("Swift proxy remote must be https://github.com/");
    }
    String path = remote.getPath() == null ? "" : remote.getPath();
    if (!"https".equalsIgnoreCase(remote.getScheme())
        || !"github.com".equalsIgnoreCase(remote.getHost())
        || remote.getUserInfo() != null || remote.getQuery() != null || remote.getFragment() != null
        || !(path.isBlank() || "/".equals(path))) {
      throw new SwiftExceptions.BadRequest("Swift proxy remote must be https://github.com/");
    }
  }

  private static String authorization(RepositoryRuntime runtime) {
    if (runtime.proxyRemoteBearerToken() != null
        && !runtime.proxyRemoteBearerToken().isBlank()) {
      return "Bearer " + runtime.proxyRemoteBearerToken().trim();
    }
    if (runtime.proxyRemoteUsername() == null || runtime.proxyRemoteUsername().isBlank()) {
      return null;
    }
    String value = runtime.proxyRemoteUsername().trim() + ":"
        + (runtime.proxyRemotePassword() == null ? "" : runtime.proxyRemotePassword());
    return "Basic " + Base64.getEncoder().encodeToString(
        value.getBytes(StandardCharsets.UTF_8));
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }

  record Coordinates(String owner, String repository) {
    String identity() {
      return owner + "." + repository;
    }

    String repositoryUrl() {
      return "https://github.com/" + owner + "/" + repository;
    }
  }

  record Tag(String version, String tag, String commitSha) {}
}
