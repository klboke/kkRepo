package com.github.klboke.kkrepo.server.securityscan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.security.scan.ScanSubject;
import com.github.klboke.kkrepo.security.scan.ScannerArtifactType;
import com.github.klboke.kkrepo.security.scan.ScannerContract;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Adapter;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Capabilities;
import com.github.klboke.kkrepo.security.scan.ScannerContract.CatalogRequest;
import com.github.klboke.kkrepo.security.scan.ScannerContract.CatalogResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.CancellationResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.InputStreamSource;
import com.github.klboke.kkrepo.security.scan.ScannerContract.MatchRequest;
import com.github.klboke.kkrepo.security.scan.ScannerContract.MatchResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.OciScanRequest;
import com.github.klboke.kkrepo.security.scan.ScannerContract.OciScanResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Readiness;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Streaming HTTP implementation of the versioned scanner contract. */
@Component
public class HttpSecurityScannerAdapter implements Adapter {
  private static final long TRANSPORT_GRACE_SECONDS = 5;
  private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
  private static final Pattern ERROR_CODE = Pattern.compile("[A-Z0-9_]{1,128}");
  private final ObjectMapper objectMapper;
  private final SecurityScanningProperties properties;
  private final HttpClient client;
  private final List<URI> baseUris;

  public HttpSecurityScannerAdapter(
      ObjectMapper objectMapper, SecurityScanningProperties properties) {
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.baseUris = properties.getAdapter().configuredBaseUrls().stream()
        .filter(value -> value != null && !value.isBlank())
        .map(HttpSecurityScannerAdapter::validateBaseUri)
        .distinct()
        .toList();
    if (baseUris.isEmpty()) {
      throw new IllegalArgumentException("At least one security scanner adapter URL is required");
    }
    this.client = HttpClient.newBuilder()
        .connectTimeout(properties.getAdapter().getConnectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  @PostConstruct
  void validateConfiguration() {
    if (properties.isEnabled()
        && (properties.getAdapter().getServiceCredential() == null
            || properties.getAdapter().getServiceCredential().isBlank())) {
      throw new IllegalStateException(
          "kkrepo.security-scanning.adapter.service-credential must be configured "
              + "when security scanning is enabled");
    }
  }

  @Override
  public Capabilities capabilities() {
    return getWithFailover("/v1/capabilities", Capabilities.class);
  }

  @Override
  public Readiness readiness() {
    ScannerAdapterException firstFailure = null;
    Readiness firstNotReady = null;
    for (URI baseUri : baseUris) {
      try {
        Readiness readiness = get(baseUri, "/v1/readiness", Readiness.class);
        if (readiness.ready()) {
          return readiness;
        }
        if (firstNotReady == null) {
          firstNotReady = readiness;
        }
      } catch (ScannerAdapterException failure) {
        if (firstFailure == null) {
          firstFailure = failure;
        }
      }
    }
    if (firstNotReady != null) {
      return firstNotReady;
    }
    throw firstFailure == null
        ? new ScannerAdapterException(
            "SCANNER_UNAVAILABLE", "No security scanner adapter endpoint is available", true)
        : firstFailure;
  }

  @Override
  public CatalogResponse catalog(CatalogRequest request, InputStreamSource input)
      throws IOException {
    return executeWithFailover(request.runId(), baseUri -> {
      HttpRequest.Builder builder =
          binaryRequest(
                  baseUri,
                  "/v1/catalog",
                  request.limits().timeoutSeconds(),
                  input)
              .header("Content-Type", contentType(request.subject().mediaType()))
              .header("X-KKRepo-API-Version", request.apiVersion())
              .header("X-KKRepo-Run-ID", request.runId())
              .header("Idempotency-Key", request.idempotencyKey())
              .header("X-KKRepo-Target", request.subject().classification().name())
              .header("X-KKRepo-Expected-SHA256", request.subject().sha256())
              .header("X-KKRepo-Expected-Size", Long.toString(request.subject().size()))
              .header("X-KKRepo-Profile-Digest", request.profileConfigurationDigest())
              .header("X-KKRepo-Max-Archive-Entries",
                  Integer.toString(request.limits().maxArchiveEntries()))
              .header("X-KKRepo-Max-Uncompressed-Bytes",
                  Long.toString(request.limits().maxUncompressedBytes()))
              .header("X-KKRepo-Max-Single-File-Bytes",
                  Long.toString(request.limits().maxSingleFileBytes()))
              .header("X-KKRepo-Max-Nested-Depth",
                  Integer.toString(request.limits().maxNestedDepth()))
              .header("X-KKRepo-Max-Input-Bytes",
                  Long.toString(request.limits().maxInputBytes()))
              .header("X-KKRepo-Timeout-Seconds",
                  Integer.toString(request.limits().timeoutSeconds()));
      ScannerArtifactType artifactType = artifactType(request.subject());
      builder.header("X-KKRepo-Artifact-Type", artifactType.wireValue());
      return send(builder.build(), CatalogResponse.class);
    });
  }

  @Override
  public MatchResponse match(MatchRequest request, InputStreamSource sbom)
      throws IOException {
    return executeWithFailover(request.runId(), baseUri -> {
      HttpRequest httpRequest = binaryRequest(
              baseUri,
              "/v1/match",
              request.limits().timeoutSeconds(),
              sbom)
          .header("Content-Type", "application/vnd.cyclonedx+json")
          .header("X-KKRepo-API-Version", request.apiVersion())
          .header("X-KKRepo-Run-ID", request.runId())
          .header("Idempotency-Key", request.idempotencyKey())
          .header("X-KKRepo-SBOM-SHA256", request.sbomSha256())
          .header("X-KKRepo-Profile-Digest", request.profileConfigurationDigest())
          .header("X-KKRepo-Max-Input-Bytes",
              Long.toString(request.limits().maxInputBytes()))
          .header("X-KKRepo-Max-Archive-Entries",
              Integer.toString(request.limits().maxArchiveEntries()))
          .header("X-KKRepo-Max-Uncompressed-Bytes",
              Long.toString(request.limits().maxUncompressedBytes()))
          .header("X-KKRepo-Max-Single-File-Bytes",
              Long.toString(request.limits().maxSingleFileBytes()))
          .header("X-KKRepo-Max-Nested-Depth",
              Integer.toString(request.limits().maxNestedDepth()))
          .header("X-KKRepo-Timeout-Seconds",
              Integer.toString(request.limits().timeoutSeconds()))
          .build();
      return send(httpRequest, MatchResponse.class);
    });
  }

  @Override
  public OciScanResponse scanOci(OciScanRequest request) throws IOException {
    byte[] body = objectMapper.writeValueAsBytes(request);
    return executeWithFailover(request.runId(), baseUri -> {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(resolve(baseUri, "/v1/oci/scan"))
              .timeout(requestTimeout(request.limits().timeoutSeconds()))
              .header("Content-Type", "application/json")
              .header("X-KKRepo-API-Version", request.apiVersion())
              .header("X-KKRepo-Run-ID", request.runId())
              .header("Idempotency-Key", request.idempotencyKey())
              .POST(HttpRequest.BodyPublishers.ofByteArray(body));
      withServiceCredential(builder);
      return send(builder.build(), OciScanResponse.class);
    });
  }

  @Override
  public CancellationResponse cancel(String runId) {
    requireRunId(runId);
    ScannerAdapterException firstFailure = null;
    boolean cancelled = false;
    for (URI baseUri : baseUris) {
      try {
        cancelled |= cancel(baseUri, runId).cancelled();
      } catch (ScannerAdapterException e) {
        if (firstFailure == null) {
          firstFailure = e;
        } else {
          firstFailure.addSuppressed(e);
        }
      }
    }
    if (!cancelled && firstFailure != null) {
      throw firstFailure;
    }
    return new CancellationResponse(runId, cancelled);
  }

  private <T> T get(URI baseUri, String path, Class<T> type) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(baseUri, path))
        .timeout(Duration.ofSeconds(15))
        .header("Accept", "application/json")
        .GET();
    withServiceCredential(builder);
    HttpRequest request = builder.build();
    return send(request, type);
  }

  /**
   * Readiness and capability observations describe the shared scanner deployment rather than one
   * run owner. A StatefulSet ordinal can be unavailable during a rollout, so accept the first
   * healthy replica instead of making scanner-0 a cluster-wide availability authority.
   */
  private <T> T getWithFailover(String path, Class<T> type) {
    ScannerAdapterException firstFailure = null;
    for (URI baseUri : baseUris) {
      try {
        return get(baseUri, path, type);
      } catch (ScannerAdapterException failure) {
        if (firstFailure == null) {
          firstFailure = failure;
        }
      }
    }
    throw firstFailure == null
        ? new ScannerAdapterException(
            "SCANNER_UNAVAILABLE", "No security scanner adapter endpoint is available", true)
        : firstFailure;
  }

  /**
   * Uses the run hash as a load-distributing preference, then walks every other configured
   * endpoint on retryable transport, capacity, or availability failures. Scanner operations are
   * self-contained and carry stable idempotency/run identities, while cancellation is broadcast
   * because a timed-out primary execution may still be winding down.
   */
  private <T> T executeWithFailover(String runId, EndpointCall<T> call) {
    requireRunId(runId);
    int preferred = routeIndex(runId, baseUris.size());
    ScannerAdapterException primaryFailure = null;
    for (int offset = 0; offset < baseUris.size(); offset++) {
      URI baseUri = baseUris.get((preferred + offset) % baseUris.size());
      try {
        return call.execute(baseUri);
      } catch (ScannerAdapterException failure) {
        if (!failure.retryable() || "SCANNER_INTERRUPTED".equals(failure.code())) {
          throw failure;
        }
        cancelAfterAmbiguousFailure(baseUri, runId, failure);
        if (primaryFailure == null) {
          primaryFailure = failure;
        } else {
          primaryFailure.addSuppressed(failure);
        }
      }
    }
    throw primaryFailure == null
        ? new ScannerAdapterException(
            "SCANNER_UNAVAILABLE", "No security scanner adapter endpoint is available", true)
        : primaryFailure;
  }

  /**
   * A retryable response can arrive after the remote scanner accepted the run but its response was
   * lost. Stop that replica's process before consuming capacity on the next ordinal. Cancellation
   * is best effort: the durable task lease and result fence remain authoritative if the endpoint
   * is already unavailable.
   */
  private void cancelAfterAmbiguousFailure(
      URI baseUri, String runId, ScannerAdapterException executionFailure) {
    try {
      cancel(baseUri, runId);
    } catch (ScannerAdapterException cancellationFailure) {
      executionFailure.addSuppressed(cancellationFailure);
    }
  }

  private CancellationResponse cancel(URI baseUri, String runId) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(
            resolve(baseUri, "/v1/runs/" + runId + "/cancel"))
        .timeout(Duration.ofSeconds(5))
        .header("Accept", "application/json")
        .header("X-KKRepo-API-Version", ScannerContract.API_VERSION)
        .POST(HttpRequest.BodyPublishers.noBody());
    withServiceCredential(builder);
    return send(builder.build(), CancellationResponse.class);
  }

  private HttpRequest.Builder binaryRequest(
      URI baseUri, String path, int timeoutSeconds, InputStreamSource source) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(baseUri, path))
        .timeout(requestTimeout(timeoutSeconds))
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofInputStream(() -> {
          try {
            return source.open();
          } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
        }));
    withServiceCredential(builder);
    return builder;
  }

  static Duration requestTimeout(int scannerTimeoutSeconds) {
    long bounded = Math.max(1L, scannerTimeoutSeconds);
    return Duration.ofSeconds(bounded + TRANSPORT_GRACE_SECONDS);
  }

  private <T> T send(HttpRequest request, Class<T> type) {
    try {
      HttpResponse<InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
          ScannerErrorPayload payload = scannerError(readErrorBody(body));
          String code = payload != null
                  && payload.code() != null
                  && ERROR_CODE.matcher(payload.code()).matches()
              ? payload.code()
              : "SCANNER_HTTP_" + status;
          boolean retryable = status == 429 || status == 502 || status == 503 || status == 504
              || (payload != null && payload.retryable());
          throw new ScannerAdapterException(
              code,
              "Scanner adapter returned HTTP " + status,
              retryable);
        }
        byte[] bytes = readBounded(body, properties.getMaxResponseBytes());
        try {
          return objectMapper.readValue(bytes, type);
        } catch (IOException e) {
          throw new ScannerAdapterException(
              "SCANNER_INVALID_JSON", "Scanner adapter returned invalid JSON", false, e);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ScannerAdapterException(
          "SCANNER_INTERRUPTED", "Scanner request was interrupted", true, e);
    } catch (java.net.http.HttpTimeoutException e) {
      throw new ScannerAdapterException(
          "SCANNER_TIMEOUT", "Scanner request timed out", true, e);
    } catch (IOException e) {
      throw new ScannerAdapterException(
          "SCANNER_IO", "Scanner adapter is unavailable", true, e);
    }
  }

  private byte[] readErrorBody(InputStream body) throws IOException {
    try {
      return readBounded(body, properties.getMaxResponseBytes());
    } catch (ScannerAdapterException failure) {
      if ("SCANNER_REPORT_TOO_LARGE".equals(failure.code())) {
        return new byte[0];
      }
      throw failure;
    }
  }

  private ScannerErrorPayload scannerError(byte[] body) {
    if (body == null || body.length == 0) return null;
    try {
      return objectMapper.readValue(body, ScannerErrorPayload.class);
    } catch (IOException ignored) {
      return null;
    }
  }

  static byte[] readBounded(InputStream input, long maxBytes) throws IOException {
    int limit = (int) Math.min(Integer.MAX_VALUE - 1L, Math.max(1, maxBytes));
    byte[] bytes = input.readNBytes(limit + 1);
    if (bytes.length > limit) {
      throw new ScannerAdapterException(
          "SCANNER_REPORT_TOO_LARGE", "Scanner response exceeded configured limit", false);
    }
    return bytes;
  }

  URI baseUriForRun(String runId) {
    return baseUris.get(routeIndex(runId, baseUris.size()));
  }

  static int routeIndex(String runId, int endpointCount) {
    if (runId == null || runId.isBlank()) {
      throw new IllegalArgumentException("Security scanner run ID is required");
    }
    if (endpointCount < 1) {
      throw new IllegalArgumentException("At least one scanner endpoint is required");
    }
    return Math.floorMod(runId.hashCode(), endpointCount);
  }

  private static void requireRunId(String runId) {
    if (runId == null || !RUN_ID.matcher(runId).matches()) {
      throw new IllegalArgumentException("Invalid security scanner run ID");
    }
  }

  private URI resolve(URI baseUri, String path) {
    return baseUri.resolve(path.startsWith("/") ? path.substring(1) : path);
  }

  private static URI validateBaseUri(String raw) {
    String value = raw == null ? "" : raw.trim();
    URI uri = URI.create(value.endsWith("/") ? value : value + "/");
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!("http".equals(scheme) || "https".equals(scheme))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new IllegalArgumentException("Invalid security scanner adapter base URL");
    }
    return uri;
  }

  private static String contentType(String value) {
    return value == null || value.isBlank() ? "application/octet-stream" : value;
  }

  static ScannerArtifactType artifactType(ScanSubject subject) {
    Object pathValue = subject == null || subject.attributes() == null
        ? null : subject.attributes().get("path");
    return pathValue instanceof String path
        ? ScannerArtifactType.fromPath(path) : ScannerArtifactType.UNKNOWN;
  }

  private void withServiceCredential(HttpRequest.Builder builder) {
    String value = properties.getAdapter().getServiceCredential();
    if (value != null && !value.isBlank()) {
      builder.header("X-KKRepo-Scanner-Credential", value);
    }
  }

  @FunctionalInterface
  private interface EndpointCall<T> {
    T execute(URI baseUri);
  }

  private record ScannerErrorPayload(String code, String message, boolean retryable) {}
}
