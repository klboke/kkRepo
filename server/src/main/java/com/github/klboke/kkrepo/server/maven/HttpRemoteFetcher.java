package com.github.klboke.kkrepo.server.maven;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 * Outbound HTTP client used by the Maven proxy facet. Wraps {@link HttpClient} with the bits
 * we actually need: conditional GET, configurable timeouts, simple HEAD/GET API. Keeps Spring
 * out of the proxy logic so it can be tested standalone.
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

  private final HttpClient client;
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
      @Value("${kkrepo.proxy.remote-http-version:HTTP_1_1}") String remoteHttpVersion,
      @Value("${kkrepo.proxy.search-timeout-seconds:30}") long searchTimeoutSeconds,
      @Value("${kkrepo.proxy.metadata-timeout-seconds:60}") long metadataTimeoutSeconds,
      @Value("${kkrepo.proxy.content-timeout-seconds:${kkrepo.proxy.get-timeout-seconds:120}}") long contentTimeoutSeconds,
      @Value("${kkrepo.proxy.head-timeout-seconds:5}") long headTimeoutSeconds,
      @Value("${kkrepo.proxy.body-read-retry-attempts:1}") int bodyReadRetryAttempts) {
    this.outboundPolicy = outboundPolicy;
    this.metrics = metrics;
    this.searchTimeout = Duration.ofSeconds(Math.max(1L, searchTimeoutSeconds));
    this.metadataTimeout = Duration.ofSeconds(Math.max(1L, metadataTimeoutSeconds));
    this.contentTimeout = Duration.ofSeconds(Math.max(1L, contentTimeoutSeconds));
    this.headTimeout = Duration.ofSeconds(Math.max(1L, headTimeoutSeconds));
    this.bodyReadRetryAttempts = Math.max(0, bodyReadRetryAttempts);
    this.client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .version(httpVersion(remoteHttpVersion))
        .build();
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
    var uri = req.validatedUri(outboundPolicy, "remote fetch");
    HttpRequest.Builder b = HttpRequest.newBuilder()
        .uri(uri)
        .timeout(requestTimeout(req))
        .header("Accept", "*/*")
        .header("User-Agent", "kkrepo/0.1");
    if (req.etag() != null && !req.etag().isBlank()) {
      b.header("If-None-Match", "\"" + req.etag() + "\"");
    }
    if (req.lastModified() != null) {
      b.header("If-Modified-Since", RFC1123.format(req.lastModified()));
    }
    HttpRequest request = req.headOnly()
        ? b.method("HEAD", HttpRequest.BodyPublishers.noBody()).build()
        : b.GET().build();
    try {
      HttpResponse<InputStream> response = client.send(request,
          HttpResponse.BodyHandlers.ofInputStream());
      Optional<String> redirect = redirectLocation(response);
      if (redirect.isPresent()) {
        response.body().close();
        if (redirects >= MAX_REDIRECTS) {
          throw new IOException("Too many redirects fetching " + req.url());
        }
        var redirected = outboundPolicy.validateHttpUri(uri.resolve(redirect.get()), "remote redirect");
        return fetchInternal(new Request(
            redirected.toString(),
            req.etag(),
            req.lastModified(),
            req.timeout(),
            req.timeoutProfile(),
            req.headOnly(),
            req.repository(),
            req.format()), redirects + 1);
      }
      Map<String, String> headers = new LinkedHashMap<>();
      response.headers().map().forEach((k, v) -> {
        if (!v.isEmpty()) headers.put(k, v.get(0));
      });
      return new Result(response.statusCode(), headers, response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted fetching " + req.url(), e);
    }
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

  private static Optional<String> redirectLocation(HttpResponse<?> response) {
    int status = response.statusCode();
    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
      return response.headers().firstValue("Location").filter(value -> !value.isBlank());
    }
    return Optional.empty();
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
      String trustedHost) {
    public Request(String url, String etag, Instant lastModified, Duration timeout, boolean headOnly) {
      this(url, etag, lastModified, timeout, TimeoutProfile.DEFAULT, headOnly, null, null, null);
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
      this(url, etag, lastModified, timeout, timeoutProfile, headOnly, repository, format, null);
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
      this.url = url;
      this.etag = etag;
      this.lastModified = lastModified;
      this.timeout = timeout;
      this.timeoutProfile = timeoutProfile == null ? TimeoutProfile.DEFAULT : timeoutProfile;
      this.headOnly = headOnly;
      this.repository = repository;
      this.format = format;
      this.trustedHost = trustedHost;
    }

    public static Request get(String url) {
      return new Request(url, null, null, null, false);
    }

    public Request withConditional(String etag, Instant lastModified) {
      return new Request(url, etag, lastModified, timeout, timeoutProfile, headOnly, repository, format, trustedHost);
    }

    public Request withTimeoutProfile(TimeoutProfile timeoutProfile) {
      return new Request(url, etag, lastModified, timeout, timeoutProfile, headOnly, repository, format, trustedHost);
    }

    public Request withRepository(RepositoryRuntime runtime) {
      return new Request(
          url,
          etag,
          lastModified,
          timeout,
          timeoutProfile,
          headOnly,
          runtime == null ? null : runtime.name(),
          runtime == null || runtime.format() == null ? null : runtime.format().name(),
          trustedRemoteHost(url, runtime));
    }

    public String method() {
      return headOnly ? "HEAD" : "GET";
    }

    java.net.URI validatedUri(OutboundRequestPolicy policy, String purpose) {
      URI uri = policy.validateHttpUri(url, purpose);
      if (trustedHost != null && !uri.getHost().equals(trustedHost)) {
        throw new SecurityValidationException(purpose + " URL host must remain " + trustedHost);
      }
      return uri;
    }

    private static String trustedRemoteHost(String url, RepositoryRuntime runtime) {
      if (runtime == null || runtime.proxyRemoteUrl() == null || runtime.proxyRemoteUrl().isBlank()) {
        return null;
      }
      try {
        String baseHost = URI.create(runtime.proxyRemoteUrl()).getHost();
        String requestHost = URI.create(url).getHost();
        if (baseHost != null && requestHost != null && requestHost.equals(baseHost)) {
          return baseHost;
        }
      } catch (RuntimeException ignored) {
        return null;
      }
      return null;
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
