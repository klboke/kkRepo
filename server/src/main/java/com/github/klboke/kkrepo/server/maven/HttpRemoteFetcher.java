package com.github.klboke.kkrepo.server.maven;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.github.klboke.kkrepo.server.proxy.OutboundProxyConfig;
import com.github.klboke.kkrepo.server.proxy.ProxiedHttpClientFactory;
import com.github.klboke.kkrepo.server.security.OutboundRequestPolicy;
import com.github.klboke.kkrepo.server.security.SecurityValidationException;
import com.github.klboke.kkrepo.server.metrics.KkRepoMetrics;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Outbound HTTP client used by repository proxy facets. It validates and pins DNS answers before
 * delegating GET/HEAD streaming to the shared Apache client factory.
 */
@Component
public class HttpRemoteFetcher {
  private static final Logger log = LoggerFactory.getLogger(HttpRemoteFetcher.class);

  private static final int MAX_REDIRECTS = 5;
  private static final Duration DEFAULT_SEARCH_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration DEFAULT_METADATA_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration DEFAULT_CONTENT_TIMEOUT = Duration.ofSeconds(120);
  private static final Duration DEFAULT_HEAD_TIMEOUT = Duration.ofSeconds(5);
  private static final int DEFAULT_BODY_READ_RETRY_ATTEMPTS = 1;
  private static final DateTimeFormatter RFC1123 =
      DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

  private final ProxiedHttpClientFactory proxyFactory;
  private final OutboundRequestPolicy outboundPolicy;
  private final KkRepoMetrics metrics;
  private final Duration searchTimeout;
  private final Duration metadataTimeout;
  private final Duration contentTimeout;
  private final Duration headTimeout;
  private final int bodyReadRetryAttempts;

  public HttpRemoteFetcher(OutboundRequestPolicy outboundPolicy) {
    this(outboundPolicy, null);
  }

  public HttpRemoteFetcher(OutboundRequestPolicy outboundPolicy, KkRepoMetrics metrics) {
    this(
        outboundPolicy,
        metrics,
        null,
        "HTTP_1_1",
        DEFAULT_SEARCH_TIMEOUT.toSeconds(),
        DEFAULT_METADATA_TIMEOUT.toSeconds(),
        DEFAULT_CONTENT_TIMEOUT.toSeconds(),
        DEFAULT_HEAD_TIMEOUT.toSeconds(),
        DEFAULT_BODY_READ_RETRY_ATTEMPTS);
  }

  @Autowired
  public HttpRemoteFetcher(
      OutboundRequestPolicy outboundPolicy,
      KkRepoMetrics metrics,
      ProxiedHttpClientFactory proxyFactory,
      @Value("${kkrepo.proxy.remote-http-version:HTTP_1_1}") String remoteHttpVersion,
      @Value("${kkrepo.proxy.search-timeout-seconds:30}") long searchTimeoutSeconds,
      @Value("${kkrepo.proxy.metadata-timeout-seconds:60}") long metadataTimeoutSeconds,
      @Value("${kkrepo.proxy.content-timeout-seconds:${kkrepo.proxy.get-timeout-seconds:120}}") long contentTimeoutSeconds,
      @Value("${kkrepo.proxy.head-timeout-seconds:5}") long headTimeoutSeconds,
      @Value("${kkrepo.proxy.body-read-retry-attempts:1}") int bodyReadRetryAttempts) {
    this.outboundPolicy = outboundPolicy;
    this.metrics = metrics;
    this.proxyFactory = proxyFactory;
    this.searchTimeout = Duration.ofSeconds(Math.max(1L, searchTimeoutSeconds));
    this.metadataTimeout = Duration.ofSeconds(Math.max(1L, metadataTimeoutSeconds));
    this.contentTimeout = Duration.ofSeconds(Math.max(1L, contentTimeoutSeconds));
    this.headTimeout = Duration.ofSeconds(Math.max(1L, headTimeoutSeconds));
    this.bodyReadRetryAttempts = Math.max(0, bodyReadRetryAttempts);
    if (httpVersion(remoteHttpVersion) == HttpClient.Version.HTTP_2) {
      log.warn(
          "kkrepo.proxy.remote-http-version=HTTP_2 is ignored because DNS-pinned outbound "
              + "connections currently use Apache HttpClient 5 classic (HTTP/1.1)");
    }
  }

  /** Issues a conditional GET (or HEAD) and returns the streaming body for the caller to drain. */
  public Result fetch(Request req) throws IOException {
    if (metrics == null) {
      return fetchInternal(req, 0);
    }
    Timer.Sample sample = metrics.startTimer();
    try {
      Result result = fetchInternal(req, 0);
      metrics.recordProxyRemote(
          req.repository(),
          req.format(),
          req.method(),
          remoteHost(req.url()),
          result.status(),
          null,
          sample);
      return result;
    } catch (IOException | RuntimeException e) {
      metrics.recordProxyRemote(
          req.repository(),
          req.format(),
          req.method(),
          remoteHost(req.url()),
          0,
          e,
          sample);
      throw e;
    }
  }

  public <T> T fetchWithBodyRetry(Request req, String logicalPath, ResultHandler<T> handler)
      throws IOException {
    int attempt = 1;
    int attempts = bodyReadRetryAttempts + 1;
    while (true) {
      Result result = null;
      try {
        result = fetch(req);
        try (Result closeable = result) {
          return handler.handle(closeable);
        }
      } catch (UpstreamBodyReadException e) {
        IOException bodyFailure = e.ioCause();
        if (attempt >= attempts) {
          throw new IOException(
              "Upstream IO error while reading body: " + exceptionMessage(bodyFailure),
              bodyFailure);
        }
        logBodyRetry(req, logicalPath, attempt, attempts, bodyFailure);
        attempt++;
      }
    }
  }

  private Result fetchInternal(Request req, int redirects) throws IOException {
    if (proxyFactory == null) {
      throw new IllegalStateException("Outbound HTTP client factory is required");
    }
    return fetchInternal(req, redirects, req.resolvedTarget(outboundPolicy, "remote fetch"));
  }

  private Result fetchInternal(
      Request req,
      int redirects,
      OutboundRequestPolicy.ResolvedHttpTarget target)
      throws IOException {
    URI uri = target.uri();
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Accept", req.accept() == null || req.accept().isBlank() ? "*/*" : req.accept());
    headers.put("User-Agent", "kkrepo/0.1");
    if (req.etag() != null && !req.etag().isBlank()) {
      headers.put("If-None-Match", "\"" + req.etag() + "\"");
    }
    if (req.lastModified() != null) {
      headers.put("If-Modified-Since", RFC1123.format(req.lastModified()));
    }
    if (req.authorizationHeader() != null && !req.authorizationHeader().isBlank()) {
      headers.put("Authorization", req.authorizationHeader());
    }
    Duration timeout = requestTimeout(req);
    ProxiedHttpClientFactory.ProxiedResponse response = null;
    try {
      response = proxyFactory.execute(
          req.repository(),
          req.outboundProxy(),
          req.headOnly() ? "HEAD" : "GET",
          target,
          headers,
          timeout.toMillis());
      String location = redirectLocation(response);
      if (location != null) {
        response.close();
        if (redirects >= MAX_REDIRECTS) {
          throw new IOException("Too many redirects fetching " + req.url());
        }
        URI redirected = uri.resolve(location);
        String redirectAuthorization = req.authorizationHeaderForRedirect(uri, redirected);
        String redirectTrustedHost = req.trustedHostForRedirect(uri, redirected);
        Request redirectedRequest = new Request(
            redirected.toString(),
            req.etag(),
            req.lastModified(),
            req.timeout(),
            req.timeoutProfile(),
            req.headOnly(),
            req.repository(),
            req.format(),
            redirectTrustedHost,
            redirectAuthorization,
            req.allowedUnsignedRedirectHosts(),
            req.outboundProxy(),
            req.accept());
        return fetchInternal(
            redirectedRequest,
            redirects + 1,
            redirectedRequest.resolvedTarget(outboundPolicy, "remote redirect"));
      }
      return new Result(response.status(), response.headers(), releaseOnClose(response));
    } catch (IOException | RuntimeException e) {
      if (response != null) {
        response.close();
      }
      throw e;
    }
  }

  /**
   * Wraps the proxied response body so that draining/closing the {@link Result} also closes the
   * underlying Apache response, guaranteeing the pooled connection is released even for entity-less
   * responses (HEAD, 304, errors).
   */
  private static InputStream releaseOnClose(ProxiedHttpClientFactory.ProxiedResponse response) throws IOException {
    InputStream body = response.body();
    return new java.io.FilterInputStream(body) {
      @Override
      public void close() throws java.io.IOException {
        try {
          super.close();
        } finally {
          response.close();
        }
      }
    };
  }

  private static String redirectLocation(ProxiedHttpClientFactory.ProxiedResponse response) {
    int status = response.status();
    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
      String location = response.header("Location");
      return location == null || location.isBlank() ? null : location;
    }
    return null;
  }

  Duration requestTimeout(Request req) {
    if (req.timeout() != null) {
      return req.timeout();
    }
    if (req.headOnly()) {
      return headTimeout;
    }
    return switch (req.timeoutProfile()) {
      case SEARCH -> searchTimeout;
      case METADATA -> metadataTimeout;
      case CONTENT, DEFAULT -> contentTimeout;
    };
  }

  private static String remoteHost(String url) {
    try {
      String host = URI.create(url).getHost();
      return host == null || host.isBlank() ? "unknown" : host;
    } catch (RuntimeException ignored) {
      return "unknown";
    }
  }

  static HttpClient.Version httpVersion(String configured) {
    String value = configured == null ? "" : configured.trim().toUpperCase(Locale.ROOT);
    value = value.replace('-', '_').replace('.', '_').replace('/', '_');
    return switch (value) {
      case "HTTP_2", "HTTP2", "2" -> HttpClient.Version.HTTP_2;
      default -> HttpClient.Version.HTTP_1_1;
    };
  }

  private static void logBodyRetry(
      Request req, String logicalPath, int attempt, int attempts, Throwable failure) {
    log.warn(
        "Proxy upstream body read failed for repository={} path={} attempt={}/{} cause={}: {}; retrying upstream GET",
        req.repository(),
        logicalPath,
        attempt,
        attempts,
        failure.getClass().getSimpleName(),
        exceptionMessage(failure));
  }

  private static String exceptionMessage(Throwable error) {
    String message = error.getMessage();
    return message == null || message.isBlank()
        ? error.getClass().getSimpleName()
        : message;
  }

  @FunctionalInterface
  public interface ResultHandler<T> {
    T handle(Result result) throws IOException;
  }

  public enum TimeoutProfile {
    DEFAULT,
    SEARCH,
    METADATA,
    CONTENT
  }

  public record Request(
      String url,
      String etag,
      Instant lastModified,
      Duration timeout,
      TimeoutProfile timeoutProfile,
      boolean headOnly,
      String repository,
      String format,
      String trustedHost,
      String authorizationHeader,
      Set<String> allowedUnsignedRedirectHosts,
      OutboundProxyConfig outboundProxy,
      String accept) {
    /** Compatibility constructor for callers that do not need a custom Accept header. */
    public Request(
        String url,
        String etag,
        Instant lastModified,
        Duration timeout,
        TimeoutProfile timeoutProfile,
        boolean headOnly,
        String repository,
        String format,
        String trustedHost,
        String authorizationHeader,
        Set<String> allowedUnsignedRedirectHosts,
        OutboundProxyConfig outboundProxy) {
      this(url, etag, lastModified, timeout, timeoutProfile, headOnly, repository, format,
          trustedHost, authorizationHeader, allowedUnsignedRedirectHosts, outboundProxy, null);
    }

    public Request(String url, String etag, Instant lastModified, Duration timeout, boolean headOnly) {
      this(url, etag, lastModified, timeout, TimeoutProfile.DEFAULT, headOnly, null, null, null, null, Set.of(), null);
    }

    public Request(
        String url,
        String etag,
        Instant lastModified,
        Duration timeout,
        TimeoutProfile timeoutProfile,
        boolean headOnly,
        String repository,
        String format) {
      this(url, etag, lastModified, timeout, timeoutProfile, headOnly, repository, format, null, null, Set.of(), null);
    }

    public Request(
        String url,
        String etag,
        Instant lastModified,
        Duration timeout,
        TimeoutProfile timeoutProfile,
        boolean headOnly,
        String repository,
        String format,
        String trustedHost) {
      this(url, etag, lastModified, timeout, timeoutProfile, headOnly, repository, format, trustedHost, null, Set.of(), null);
    }

    public Request {
      timeoutProfile = timeoutProfile == null ? TimeoutProfile.DEFAULT : timeoutProfile;
      String normalizedTrustedHost = normalizeHost(trustedHost);
      trustedHost = normalizedTrustedHost.isBlank() ? null : normalizedTrustedHost;
      allowedUnsignedRedirectHosts = allowedUnsignedRedirectHosts == null
          ? Set.of()
          : allowedUnsignedRedirectHosts.stream()
              .map(Request::normalizeHost)
              .filter(host -> !host.isBlank())
              .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static Request get(String url) {
      return new Request(url, null, null, null, false);
    }

    public Request withConditional(String etag, Instant lastModified) {
      return new Request(url, etag, lastModified, timeout, timeoutProfile, headOnly,
          repository, format, trustedHost, authorizationHeader, allowedUnsignedRedirectHosts, outboundProxy, accept);
    }

    public Request withTimeoutProfile(TimeoutProfile timeoutProfile) {
      return new Request(url, etag, lastModified, timeout, timeoutProfile, headOnly,
          repository, format, trustedHost, authorizationHeader, allowedUnsignedRedirectHosts, outboundProxy, accept);
    }

    public Request withAccept(String accept) {
      return new Request(url, etag, lastModified, timeout, timeoutProfile, headOnly,
          repository, format, trustedHost, authorizationHeader, allowedUnsignedRedirectHosts, outboundProxy, accept);
    }

    public Request withRepository(RepositoryRuntime runtime) {
      return withRepository(runtime, true);
    }

    public Request withRepository(RepositoryRuntime runtime, boolean includeAuthorization) {
      String trusted = trustedRemoteHost(url, runtime);
      return new Request(
          url,
          etag,
          lastModified,
          timeout,
          timeoutProfile,
          headOnly,
          runtime == null ? null : runtime.name(),
          runtime == null || runtime.format() == null ? null : runtime.format().name(),
          trusted,
          includeAuthorization && trusted != null ? remoteAuthorizationHeader(runtime) : null,
          Set.of(),
          runtime == null ? null : runtime.outboundProxy(),
          accept);
    }

    public Request withRepositoryAllowingUnsignedRedirects(
        RepositoryRuntime runtime,
        boolean includeAuthorization) {
      return withRepositoryAllowingUnsignedRedirects(runtime, includeAuthorization, Set.of());
    }

    public Request withRepositoryAllowingUnsignedRedirects(
        RepositoryRuntime runtime,
        boolean includeAuthorization,
        Set<String> allowedUnsignedRedirectHosts) {
      String trusted = trustedRemoteHost(url, runtime);
      return new Request(
          url,
          etag,
          lastModified,
          timeout,
          timeoutProfile,
          headOnly,
          runtime == null ? null : runtime.name(),
          runtime == null || runtime.format() == null ? null : runtime.format().name(),
          trusted,
          includeAuthorization && trusted != null ? remoteAuthorizationHeader(runtime) : null,
          allowedUnsignedRedirectHosts,
          runtime == null ? null : runtime.outboundProxy(),
          accept);
    }

    public String method() {
      return headOnly ? "HEAD" : "GET";
    }

    OutboundRequestPolicy.ResolvedHttpTarget resolvedTarget(
        OutboundRequestPolicy policy, String purpose) {
      OutboundRequestPolicy.ResolvedHttpTarget target = policy.resolveHttpTarget(url, purpose);
      if (trustedHost != null && !normalizeHost(target.uri().getHost()).equals(trustedHost)) {
        throw new SecurityValidationException(purpose + " URL host must remain " + trustedHost);
      }
      return target;
    }

    java.net.URI validatedUri(OutboundRequestPolicy policy, String purpose) {
      return resolvedTarget(policy, purpose).uri();
    }

    String authorizationHeaderForRedirect(URI current, URI redirected) {
      if (sameOrigin(current, redirected)) {
        return authorizationHeader;
      }
      if (trustedHost != null || (authorizationHeader != null && !authorizationHeader.isBlank())) {
        ensureUnsignedRedirectAllowed(redirected);
      }
      return null;
    }

    String trustedHostForRedirect(URI current, URI redirected) {
      if (sameOrigin(current, redirected)) {
        return trustedHost;
      }
      if (trustedHost == null) {
        return null;
      }
      ensureUnsignedRedirectAllowed(redirected);
      return normalizeHost(redirected.getHost());
    }

    private void ensureUnsignedRedirectAllowed(URI redirected) {
      String host = normalizeHost(redirected == null ? null : redirected.getHost());
      if (!host.isBlank() && allowedUnsignedRedirectHosts.contains(host)) {
        return;
      }
      throw new SecurityValidationException("remote redirect URL host is not allowed: " + host);
    }

    private static String trustedRemoteHost(String url, RepositoryRuntime runtime) {
      if (runtime == null || runtime.proxyRemoteUrl() == null || runtime.proxyRemoteUrl().isBlank()) {
        return null;
      }
      try {
        URI base = URI.create(runtime.proxyRemoteUrl());
        URI request = URI.create(url);
        if (sameOrigin(base, request)) {
          return base.getHost();
        }
      } catch (RuntimeException ignored) {
        return null;
      }
      return null;
    }

    private static String normalizeHost(String host) {
      String value = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
      if (value.startsWith("[") && value.endsWith("]")) {
        value = value.substring(1, value.length() - 1);
      }
      return value;
    }

    private static boolean sameOrigin(URI a, URI b) {
      return a != null
          && b != null
          && a.getScheme() != null
          && b.getScheme() != null
          && a.getHost() != null
          && b.getHost() != null
          && a.getScheme().equalsIgnoreCase(b.getScheme())
          && a.getHost().equalsIgnoreCase(b.getHost())
          && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI uri) {
      if (uri.getPort() >= 0) {
        return uri.getPort();
      }
      String scheme = uri.getScheme();
      if ("http".equalsIgnoreCase(scheme)) {
        return 80;
      }
      if ("https".equalsIgnoreCase(scheme)) {
        return 443;
      }
      return -1;
    }

    private static String origin(URI uri) {
      return uri.getScheme() + "://" + uri.getHost() + ":" + effectivePort(uri);
    }

    private static String remoteAuthorizationHeader(RepositoryRuntime runtime) {
      if (runtime == null) {
        return null;
      }
      if (runtime.proxyRemoteBearerToken() != null && !runtime.proxyRemoteBearerToken().isBlank()) {
        return "Bearer " + runtime.proxyRemoteBearerToken().trim();
      }
      if (runtime.proxyRemoteUsername() == null || runtime.proxyRemoteUsername().isBlank()) {
        return null;
      }
      String credentials = runtime.proxyRemoteUsername().trim() + ":"
          + (runtime.proxyRemotePassword() == null ? "" : runtime.proxyRemotePassword());
      return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
  }

  public record Result(int status, Map<String, String> headers, InputStream body) implements AutoCloseable {
    public String header(String name) {
      for (Map.Entry<String, String> e : headers.entrySet()) {
        if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
      }
      return null;
    }

    public String etag() {
      String raw = header("ETag");
      if (raw == null) return null;
      String stripped = raw.startsWith("W/") ? raw.substring(2) : raw;
      if (stripped.startsWith("\"") && stripped.endsWith("\"") && stripped.length() >= 2) {
        return stripped.substring(1, stripped.length() - 1);
      }
      return stripped;
    }

    public Instant lastModified() {
      String raw = header("Last-Modified");
      if (raw == null) return null;
      try {
        return Instant.from(RFC1123.parse(raw));
      } catch (RuntimeException ignored) {
        return null;
      }
    }

    public String contentType() {
      return header("Content-Type");
    }

    @Override
    public void close() {
      if (body != null) {
        try { body.close(); } catch (IOException ignored) {}
      }
    }
  }
}
