package com.github.klboke.kkrepo.server.version;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import com.github.klboke.kkrepo.cache.SharedCache;
import com.github.klboke.kkrepo.server.version.LatestReleaseSource.LatestRelease;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads the latest public kkRepo release with a disposable node-local hot cache.
 *
 * <p>Each replica refreshes independently after the TTL. Losing the cache only causes the next
 * request on that replica to reload from GitHub; no correctness or durable state depends on it.
 */
@Component
final class GitHubLatestReleaseClient implements LatestReleaseSource {
  private static final URI LATEST_RELEASE_URI =
      URI.create("https://api.github.com/repos/klboke/kkRepo/releases/latest");
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(5);
  private static final String RELEASE_PATH_PREFIX = "/klboke/kkrepo/releases/tag/";
  private static final String CACHE_KEY = "latest";
  private static final String BACKOFF_NAMESPACE = "version-update";
  private static final String BACKOFF_KEY = "github-refresh-failed";

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final URI latestReleaseUri;
  private final LocalCache<String, LatestRelease> releases;
  private final SharedCache sharedCache;
  private final Duration cacheTtl;

  @Autowired
  GitHubLatestReleaseClient(
      ObjectMapper objectMapper,
      SharedCache sharedCache,
      @Value("${kkrepo.cache.version-update.ttl-seconds:300}") long cacheTtlSeconds) {
    this(
        HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(),
        objectMapper,
        LATEST_RELEASE_URI,
        Duration.ofSeconds(Math.max(1, cacheTtlSeconds)),
        sharedCache);
  }

  GitHubLatestReleaseClient(
      HttpClient httpClient,
      ObjectMapper objectMapper,
      URI latestReleaseUri,
      Duration cacheTtl,
      SharedCache sharedCache) {
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.latestReleaseUri = latestReleaseUri;
    this.sharedCache = sharedCache;
    this.cacheTtl = cacheTtl == null ? DEFAULT_CACHE_TTL : cacheTtl;
    this.releases = LocalCacheFactory.standard()
        .<String, LatestRelease>builder("version-update-latest-release")
        .maximumSize(1)
        .expireAfterWrite(this.cacheTtl)
        .build();
  }

  @Override
  public LatestRelease fetch() throws IOException {
    LatestRelease cached = releases.getIfPresent(CACHE_KEY);
    if (cached != null) {
      return cached;
    }
    if (refreshBackedOff()) {
      throw new IOException("GitHub latest release refresh is temporarily backed off");
    }
    try {
      return releases.get(CACHE_KEY, ignored -> fetchUnchecked());
    } catch (LatestReleaseLoadException exception) {
      throw exception.ioException();
    }
  }

  private LatestRelease fetchUnchecked() {
    try {
      LatestRelease release = fetchFromGitHub();
      clearRefreshBackoff();
      return release;
    } catch (IOException exception) {
      recordRefreshFailure();
      throw new LatestReleaseLoadException(exception);
    }
  }

  private boolean refreshBackedOff() {
    try {
      return sharedCache.getString(BACKOFF_NAMESPACE, BACKOFF_KEY).isPresent();
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private void recordRefreshFailure() {
    try {
      sharedCache.putString(BACKOFF_NAMESPACE, BACKOFF_KEY, "1", cacheTtl);
    } catch (RuntimeException ignored) {
      // Cache loss is harmless; the next request can retry GitHub directly.
    }
  }

  private void clearRefreshBackoff() {
    try {
      sharedCache.evict(BACKOFF_NAMESPACE, BACKOFF_KEY);
    } catch (RuntimeException ignored) {
      // A stale marker expires by TTL and never affects a cached successful result.
    }
  }

  private LatestRelease fetchFromGitHub() throws IOException {
    HttpRequest request = HttpRequest.newBuilder(latestReleaseUri)
        .timeout(REQUEST_TIMEOUT)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "kkRepo-version-update-check")
        .GET()
        .build();
    HttpResponse<String> response;
    try {
      response = httpClient.send(
          request,
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("GitHub release request was interrupted", exception);
    }
    if (response.statusCode() != 200) {
      throw new IOException("GitHub latest release returned HTTP " + response.statusCode());
    }

    JsonNode release = objectMapper.readTree(response.body());
    String version = requiredText(release, "tag_name");
    URI releaseUrl = releaseUrl(requiredText(release, "html_url"));
    return new LatestRelease(version, releaseUrl);
  }

  private String requiredText(JsonNode release, String field) throws IOException {
    String value = release.path(field).asText("").trim();
    if (value.isEmpty() || value.length() > 256) {
      throw new IOException("GitHub latest release is missing " + field);
    }
    return value;
  }

  private URI releaseUrl(String value) throws IOException {
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException exception) {
      throw new IOException("GitHub latest release has an invalid html_url", exception);
    }
    String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || !"github.com".equalsIgnoreCase(uri.getHost())
        || !path.startsWith(RELEASE_PATH_PREFIX)) {
      throw new IOException("GitHub latest release has an unexpected html_url");
    }
    return uri;
  }

  private static final class LatestReleaseLoadException extends RuntimeException {
    private final IOException ioException;

    private LatestReleaseLoadException(IOException ioException) {
      super(ioException);
      this.ioException = ioException;
    }

    private IOException ioException() {
      return ioException;
    }
  }
}
