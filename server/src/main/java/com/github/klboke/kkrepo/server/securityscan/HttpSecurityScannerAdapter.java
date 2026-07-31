package com.github.klboke.kkrepo.server.securityscan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
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
import com.github.klboke.kkrepo.security.scan.ScannerContract.Observation;
import com.github.klboke.kkrepo.security.scan.ScannerContract.OciScanRequest;
import com.github.klboke.kkrepo.security.scan.ScannerContract.OciScanResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Readiness;
import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import com.github.klboke.kkrepo.security.scan.ScannerContract.SnapshotExpectation;
import jakarta.annotation.PostConstruct;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Streaming HTTP implementation of the versioned scanner contract. */
@Component
public class HttpSecurityScannerAdapter implements Adapter {
  private static final long TRANSPORT_GRACE_SECONDS = 5;
  private static final Duration OBSERVATION_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration CANCELLATION_BROADCAST_TIMEOUT = Duration.ofSeconds(5);
  private static final long MAX_ERROR_RESPONSE_BYTES = 64L * 1024;
  private static final int MAX_RESPONSE_NESTING_DEPTH = 64;
  private static final int MAX_RESPONSE_NUMBER_LENGTH = 128;
  private static final int MAX_RESPONSE_NAME_LENGTH = 1_024;
  private static final int MAX_NESTED_LIST_VALUES = 256;
  private static final int MAX_COMPONENT_PROPERTIES = 128;
  private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
  private static final Pattern ERROR_CODE = Pattern.compile("[A-Z0-9_]{1,128}");
  private static final ExecutorService CANCELLATION_EXECUTOR =
      Executors.newThreadPerTaskExecutor(
          Thread.ofVirtual().name("security-scan-cancel-", 0).factory());
  private final ObjectMapper objectMapper;
  private final ObjectMapper responseObjectMapper;
  private final SecurityScanningProperties properties;
  private final HttpClient client;
  private final List<URI> baseUris;

  public HttpSecurityScannerAdapter(
      ObjectMapper objectMapper, SecurityScanningProperties properties) {
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.responseObjectMapper = objectMapper.copy();
    this.responseObjectMapper.getFactory().setStreamReadConstraints(
        StreamReadConstraints.builder()
            .maxDocumentLength(properties.getMaxResponseBytes())
            .maxTokenCount(properties.getMaxResponseTokens())
            .maxNestingDepth(MAX_RESPONSE_NESTING_DEPTH)
            .maxNumberLength(MAX_RESPONSE_NUMBER_LENGTH)
            // The two raw documents are Base64 string values, so their string limit is the
            // independently bounded response envelope rather than a small metadata-field limit.
            .maxStringLength(responseStringLimit(properties.getMaxResponseBytes()))
            .maxNameLength(MAX_RESPONSE_NAME_LENGTH)
            .build());
    // Summary is advisory adapter telemetry. Runtime decisions use typed response fields (and the
    // OCI platform lists), so do not materialize an arbitrary nested object graph supplied by an
    // adapter replica.
    this.responseObjectMapper.addMixIn(CatalogResponse.class, IgnoreSummaryMixin.class);
    this.responseObjectMapper.addMixIn(MatchResponse.class, IgnoreSummaryMixin.class);
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
  public Observation observation() {
    return observation(OBSERVATION_TIMEOUT);
  }

  Observation observation(Duration totalTimeout) {
    TransportDeadline deadline = new TransportDeadline(totalTimeout);
    ScannerAdapterException firstFailure = null;
    Observation firstNotReady = null;
    for (URI baseUri : baseUris) {
      try {
        Observation observation = new Observation(
            get(
                baseUri,
                "/v1/capabilities",
                Capabilities.class,
                deadline.nextRequestTimeout()),
            get(
                baseUri,
                "/v1/readiness",
                Readiness.class,
                deadline.nextRequestTimeout()));
        if (observation.readiness().ready()) {
          return observation;
        }
        if (firstNotReady == null) {
          firstNotReady = observation;
        }
      } catch (ScannerAdapterException failure) {
        if (firstFailure == null) {
          firstFailure = failure;
        } else {
          firstFailure.addSuppressed(failure);
        }
      }
      if (deadline.expired()) break;
    }
    if (firstNotReady != null) {
      return firstNotReady;
    }
    if (deadline.expired()) {
      throw deadline.timeout(firstFailure);
    }
    throw firstFailure == null
        ? new ScannerAdapterException(
            "SCANNER_UNAVAILABLE", "No security scanner adapter endpoint is available", true)
        : firstFailure;
  }

  @Override
  public Capabilities capabilities() {
    return getWithFailover(
        "/v1/capabilities", Capabilities.class, new TransportDeadline(OBSERVATION_TIMEOUT));
  }

  @Override
  public Readiness readiness() {
    TransportDeadline deadline = new TransportDeadline(OBSERVATION_TIMEOUT);
    ScannerAdapterException firstFailure = null;
    Readiness firstNotReady = null;
    for (URI baseUri : baseUris) {
      try {
        Readiness readiness = get(
            baseUri,
            "/v1/readiness",
            Readiness.class,
            deadline.nextRequestTimeout());
        if (readiness.ready()) {
          return readiness;
        }
        if (firstNotReady == null) {
          firstNotReady = readiness;
        }
      } catch (ScannerAdapterException failure) {
        if (firstFailure == null) {
          firstFailure = failure;
        } else {
          firstFailure.addSuppressed(failure);
        }
      }
      if (deadline.expired()) break;
    }
    if (firstNotReady != null) {
      return firstNotReady;
    }
    if (deadline.expired()) {
      throw deadline.timeout(firstFailure);
    }
    throw firstFailure == null
        ? new ScannerAdapterException(
            "SCANNER_UNAVAILABLE", "No security scanner adapter endpoint is available", true)
        : firstFailure;
  }

  @Override
  public CatalogResponse catalog(CatalogRequest request, InputStreamSource input)
      throws IOException {
    return executeWithFailover(
        request.runId(), request.limits().timeoutSeconds(), (baseUri, budget) -> {
      HttpRequest.Builder builder =
          binaryRequest(
                  baseUri,
                  "/v1/catalog",
                  budget.transportTimeout(),
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
                  Integer.toString(budget.scannerTimeoutSeconds()));
      ScannerArtifactType artifactType = artifactType(request.subject());
      builder.header("X-KKRepo-Artifact-Type", artifactType.wireValue());
      return send(builder.build(), CatalogResponse.class);
    });
  }

  @Override
  public MatchResponse match(MatchRequest request, InputStreamSource sbom)
      throws IOException {
    return executeWithFailover(
        request.runId(), request.limits().timeoutSeconds(), (baseUri, budget) -> {
      HttpRequest httpRequest = binaryRequest(
              baseUri,
              "/v1/match",
              budget.transportTimeout(),
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
              Integer.toString(budget.scannerTimeoutSeconds()))
          .build();
      MatchResponse response = send(httpRequest, MatchResponse.class);
      requireExpectedSnapshot(request.expectedSnapshot(), response);
      return response;
    });
  }

  @Override
  public OciScanResponse scanOci(OciScanRequest request) throws IOException {
    return executeWithFailover(
        request.runId(), request.limits().timeoutSeconds(), (baseUri, budget) -> {
      byte[] body = objectMapper.writeValueAsBytes(
          withTimeout(request, budget.scannerTimeoutSeconds()));
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(resolve(baseUri, "/v1/oci/scan"))
              .timeout(budget.transportTimeout())
              .header("Content-Type", "application/json")
              .header("X-KKRepo-API-Version", request.apiVersion())
              .header("X-KKRepo-Run-ID", request.runId())
              .header("Idempotency-Key", request.idempotencyKey())
              .POST(HttpRequest.BodyPublishers.ofByteArray(body));
      withServiceCredential(builder);
      OciScanResponse response = send(builder.build(), OciScanResponse.class);
      requireExpectedSnapshot(request.expectedSnapshot(), response.match());
      return response;
    });
  }

  @Override
  public CancellationResponse cancel(String runId) {
    return cancel(runId, CANCELLATION_BROADCAST_TIMEOUT);
  }

  CancellationResponse cancel(String runId, Duration totalTimeout) {
    requireRunId(runId);
    if (totalTimeout == null || totalTimeout.isZero() || totalTimeout.isNegative()) {
      throw new IllegalArgumentException("Scanner cancellation deadline must be positive");
    }
    List<Callable<CancellationResponse>> calls = baseUris.stream()
        .<Callable<CancellationResponse>>map(baseUri ->
            () -> cancel(baseUri, runId, totalTimeout))
        .toList();
    List<Future<CancellationResponse>> attempts;
    try {
      attempts = CANCELLATION_EXECUTOR.invokeAll(
          calls,
          totalTimeout.toNanos(),
          TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ScannerAdapterException(
          "SCANNER_INTERRUPTED", "Scanner cancellation was interrupted", true, e);
    }

    ScannerAdapterException firstFailure = null;
    boolean cancelled = false;
    boolean timedOut = false;
    for (Future<CancellationResponse> attempt : attempts) {
      if (attempt.isCancelled()) {
        timedOut = true;
        continue;
      }
      try {
        cancelled |= attempt.get().cancelled();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ScannerAdapterException(
            "SCANNER_INTERRUPTED", "Scanner cancellation was interrupted", true, e);
      } catch (ExecutionException e) {
        ScannerAdapterException failure = cancellationFailure(e.getCause());
        if (firstFailure == null) {
          firstFailure = failure;
        } else {
          firstFailure.addSuppressed(failure);
        }
      }
    }
    if (cancelled) {
      return new CancellationResponse(runId, true);
    }
    if (timedOut) {
      ScannerAdapterException timeout = new ScannerAdapterException(
          "SCANNER_TIMEOUT",
          "Scanner cancellation exhausted its end-to-end broadcast deadline",
          true);
      if (firstFailure != null) timeout.addSuppressed(firstFailure);
      throw timeout;
    }
    if (firstFailure != null) {
      throw firstFailure;
    }
    return new CancellationResponse(runId, false);
  }

  private static ScannerAdapterException cancellationFailure(Throwable failure) {
    if (failure instanceof ScannerAdapterException scannerFailure) {
      return scannerFailure;
    }
    return new ScannerAdapterException(
        "SCANNER_IO", "Scanner cancellation failed", true, failure);
  }

  private <T> T get(URI baseUri, String path, Class<T> type, Duration timeout) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(baseUri, path))
        .timeout(timeout)
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
  private <T> T getWithFailover(
      String path, Class<T> type, TransportDeadline deadline) {
    ScannerAdapterException firstFailure = null;
    for (URI baseUri : baseUris) {
      try {
        return get(baseUri, path, type, deadline.nextRequestTimeout());
      } catch (ScannerAdapterException failure) {
        if (firstFailure == null) {
          firstFailure = failure;
        } else {
          firstFailure.addSuppressed(failure);
        }
      }
      if (deadline.expired()) break;
    }
    if (deadline.expired()) {
      throw deadline.timeout(firstFailure);
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
  private <T> T executeWithFailover(
      String runId, int timeoutSeconds, EndpointCall<T> call) throws IOException {
    requireRunId(runId);
    FailoverDeadline deadline = new FailoverDeadline(timeoutSeconds);
    int preferred = routeIndex(runId, baseUris.size());
    ScannerAdapterException primaryFailure = null;
    for (int offset = 0; offset < baseUris.size(); offset++) {
      URI baseUri = baseUris.get((preferred + offset) % baseUris.size());
      AttemptBudget budget;
      try {
        budget = deadline.nextAttempt();
      } catch (ScannerAdapterException timeout) {
        if (primaryFailure != null) timeout.addSuppressed(primaryFailure);
        throw timeout;
      }
      try {
        return call.execute(baseUri, budget);
      } catch (ScannerAdapterException failure) {
        if (!failure.retryable() || "SCANNER_INTERRUPTED".equals(failure.code())) {
          throw failure;
        }
        cancelAfterAmbiguousFailure(baseUri, runId, failure, deadline);
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
      URI baseUri,
      String runId,
      ScannerAdapterException executionFailure,
      FailoverDeadline deadline) {
    Duration timeout = deadline.remainingCancellationTimeout();
    if (timeout == null) return;
    try {
      cancel(baseUri, runId, timeout);
    } catch (ScannerAdapterException cancellationFailure) {
      executionFailure.addSuppressed(cancellationFailure);
    }
  }

  private CancellationResponse cancel(URI baseUri, String runId, Duration timeout) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(
            resolve(baseUri, "/v1/runs/" + runId + "/cancel"))
        .timeout(timeout)
        .header("Accept", "application/json")
        .header("X-KKRepo-API-Version", ScannerContract.API_VERSION)
        .POST(HttpRequest.BodyPublishers.noBody());
    withServiceCredential(builder);
    return send(builder.build(), CancellationResponse.class);
  }

  private HttpRequest.Builder binaryRequest(
      URI baseUri, String path, Duration timeout, InputStreamSource source) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(baseUri, path))
        .timeout(timeout)
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

  private static OciScanRequest withTimeout(OciScanRequest request, int timeoutSeconds) {
    ResourceLimits limits = request.limits();
    ResourceLimits boundedLimits = new ResourceLimits(
        limits.maxInputBytes(),
        limits.maxArchiveEntries(),
        limits.maxUncompressedBytes(),
        limits.maxSingleFileBytes(),
        limits.maxNestedDepth(),
        timeoutSeconds);
    return new OciScanRequest(
        request.apiVersion(),
        request.runId(),
        request.idempotencyKey(),
        request.registryUrl(),
        request.repository(),
        request.manifestDigest(),
        request.requiredPlatforms(),
        request.scopedBearerToken(),
        request.profileConfigurationDigest(),
        boundedLimits,
        request.expectedSnapshot());
  }

  private <T> T send(HttpRequest request, Class<T> type) {
    ResponseReadDeadline responseDeadline = new ResponseReadDeadline(
        request.timeout().orElseThrow(
            () -> new IllegalArgumentException("Scanner HTTP request timeout is required")));
    try {
      HttpResponse<InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = responseDeadline.watch(response.body())) {
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
        long maxBytes = properties.getMaxResponseBytes();
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (contentLength > maxBytes) {
          throw responseTooLarge();
        }
        return readJsonBounded(body, maxBytes, type);
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
      return readBounded(
          body, Math.min(properties.getMaxResponseBytes(), MAX_ERROR_RESPONSE_BYTES));
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

  private <T> T readJsonBounded(InputStream body, long maxBytes, Class<T> type) {
    try (BoundedResponseInputStream bounded =
            new BoundedResponseInputStream(body, maxBytes);
        JsonParser parser = responseObjectMapper.getFactory().createParser(bounded)) {
      parser.disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);
      T value = responseObjectMapper.readValue(parser, type);
      if (parser.nextToken() != null) {
        throw new ScannerAdapterException(
            "SCANNER_INVALID_JSON",
            "Scanner adapter returned more than one JSON document",
            false);
      }
      bounded.drain();
      return validateDecodedProjection(value);
    } catch (IOException e) {
      if (causedByResponseLimit(e)) {
        throw responseTooLarge();
      }
      if (causedByComplexityLimit(e)) {
        throw new ScannerAdapterException(
            "SCANNER_RESPONSE_COMPLEXITY_LIMIT",
            "Scanner response exceeded configured JSON complexity limits",
            false,
            e);
      }
      throw new ScannerAdapterException(
          "SCANNER_INVALID_JSON", "Scanner adapter returned invalid JSON", false, e);
    }
  }

  private static boolean causedByResponseLimit(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof ResponseTooLargeIOException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static boolean causedByComplexityLimit(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof StreamConstraintsException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static <T> T validateDecodedProjection(T value) {
    if (value instanceof CatalogResponse catalog) {
      validateCatalogProjection(catalog);
    } else if (value instanceof MatchResponse match) {
      validateMatchProjection(match);
    } else if (value instanceof OciScanResponse oci) {
      validateCatalogProjection(oci.catalog());
      validateMatchProjection(oci.match());
      requireListLimit(oci.scannedPlatforms(), MAX_NESTED_LIST_VALUES, "scanned platforms");
      requireListLimit(oci.missingPlatforms(), MAX_NESTED_LIST_VALUES, "missing platforms");
    } else if (value instanceof Capabilities capabilities) {
      requireListLimit(capabilities.operations(), MAX_NESTED_LIST_VALUES, "operations");
      requireListLimit(
          capabilities.targetClassifications(),
          MAX_NESTED_LIST_VALUES,
          "target classifications");
    }
    return value;
  }

  private static void validateCatalogProjection(CatalogResponse response) {
    if (response == null) {
      return;
    }
    requireListLimit(
        response.components(),
        ScannerContract.MAX_COMPONENT_PROJECTION_COUNT,
        "component projection");
    for (ScannerContract.Component component : response.components()) {
      requireListLimit(component.locations(), MAX_NESTED_LIST_VALUES, "component locations");
      requireListLimit(component.licenses(), MAX_NESTED_LIST_VALUES, "component licenses");
      if (component.properties().size() > MAX_COMPONENT_PROPERTIES
          || component.properties().values().stream().anyMatch(
              property -> property != null
                  && !(property instanceof String)
                  && !(property instanceof Number)
                  && !(property instanceof Boolean))) {
        throw projectionLimit("component properties");
      }
    }
  }

  private static void validateMatchProjection(MatchResponse response) {
    if (response == null) {
      return;
    }
    requireListLimit(
        response.findings(),
        ScannerContract.MAX_FINDING_PROJECTION_COUNT,
        "finding projection");
    for (ScannerContract.Finding finding : response.findings()) {
      requireListLimit(finding.aliases(), MAX_NESTED_LIST_VALUES, "finding aliases");
      requireListLimit(finding.fixedVersions(), MAX_NESTED_LIST_VALUES, "fixed versions");
      requireListLimit(finding.locations(), MAX_NESTED_LIST_VALUES, "finding locations");
    }
  }

  private static void requireListLimit(List<?> values, int limit, String field) {
    if (values != null && values.size() > limit) {
      throw projectionLimit(field);
    }
  }

  private static ScannerAdapterException projectionLimit(String field) {
    return new ScannerAdapterException(
        "SCANNER_RESPONSE_PROJECTION_LIMIT",
        "Scanner response exceeded the " + field + " limit",
        false);
  }

  private static int responseStringLimit(long maxResponseBytes) {
    return (int) Math.min(Integer.MAX_VALUE - 1L, Math.max(1L, maxResponseBytes));
  }

  private static ScannerAdapterException responseTooLarge() {
    return new ScannerAdapterException(
        "SCANNER_REPORT_TOO_LARGE", "Scanner response exceeded configured limit", false);
  }

  private static void requireExpectedSnapshot(
      SnapshotExpectation expected, MatchResponse response) {
    if (expected == null) {
      return;
    }
    if (response != null
        && Objects.equals(expected.adapterName(), response.adapterName())
        && Objects.equals(expected.engineName(), response.engineName())
        && Objects.equals(expected.engineVersion(), response.engineVersion())
        && Objects.equals(
            expected.vulnerabilityDatabaseRevision(),
            response.vulnerabilityDatabaseRevision())
        && Objects.equals(
            ScannerContract.canonicalDatabaseTimestamp(
                expected.vulnerabilityDatabaseUpdatedAt()),
            ScannerContract.canonicalDatabaseTimestamp(
                response.vulnerabilityDatabaseUpdatedAt()))
        && Objects.equals(expected.capabilityDigest(), response.capabilityDigest())) {
      return;
    }
    throw new ScannerAdapterException(
        "SCANNER_SNAPSHOT_MISMATCH",
        "Scanner replica does not expose the task's requested vulnerability snapshot",
        true);
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
    T execute(URI baseUri, AttemptBudget budget) throws IOException;
  }

  record AttemptBudget(Duration transportTimeout, int scannerTimeoutSeconds) {}

  /** One monotonic transport budget shared by every endpoint in an observation operation. */
  static final class TransportDeadline {
    private final long timeoutNanos;
    private final LongSupplier nanoTime;
    private final long startedNanos;

    private TransportDeadline(Duration timeout) {
      this(timeout, System::nanoTime);
    }

    TransportDeadline(Duration timeout, LongSupplier nanoTime) {
      if (timeout == null || timeout.isZero() || timeout.isNegative()) {
        throw new IllegalArgumentException("Scanner transport deadline must be positive");
      }
      this.timeoutNanos = timeout.toNanos();
      this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
      this.startedNanos = nanoTime.getAsLong();
    }

    Duration nextRequestTimeout() {
      long remainingNanos = remainingNanos();
      if (remainingNanos <= 0) throw timeout(null);
      return Duration.ofNanos(remainingNanos);
    }

    boolean expired() {
      return remainingNanos() <= 0;
    }

    ScannerAdapterException timeout(ScannerAdapterException earlierFailure) {
      ScannerAdapterException timeout = new ScannerAdapterException(
          "SCANNER_TIMEOUT",
          "Scanner observation exhausted its end-to-end failover deadline",
          true);
      if (earlierFailure != null) timeout.addSuppressed(earlierFailure);
      return timeout;
    }

    private long remainingNanos() {
      return timeoutNanos - (nanoTime.getAsLong() - startedNanos);
    }
  }

  /**
   * One monotonic budget shared by all adapter replicas for a single operation.
   *
   * <p>The HTTP envelope retains one transport grace period, while every fallback receives only
   * the scanner time and transport time still remaining from the original request.
   */
  static final class FailoverDeadline {
    private static final long NANOS_PER_SECOND = Duration.ofSeconds(1).toNanos();
    private static final long MAX_CANCELLATION_NANOS = Duration.ofSeconds(5).toNanos();
    private final int requestedScannerTimeoutSeconds;
    private final long timeoutNanos;
    private final LongSupplier nanoTime;
    private final long startedNanos;

    private FailoverDeadline(int timeoutSeconds) {
      this(timeoutSeconds, System::nanoTime);
    }

    FailoverDeadline(int timeoutSeconds, LongSupplier nanoTime) {
      this.requestedScannerTimeoutSeconds = Math.max(1, timeoutSeconds);
      this.timeoutNanos = requestTimeout(timeoutSeconds).toNanos();
      this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
      this.startedNanos = nanoTime.getAsLong();
    }

    AttemptBudget nextAttempt() {
      long remainingNanos = remainingNanos();
      if (remainingNanos <= 0) throw timeout();
      long scannerNanos = Math.max(
          1L,
          remainingNanos - Duration.ofSeconds(TRANSPORT_GRACE_SECONDS).toNanos());
      long roundedSeconds = 1L + ((scannerNanos - 1L) / NANOS_PER_SECOND);
      int scannerSeconds = (int) Math.max(
          1L, Math.min((long) requestedScannerTimeoutSeconds, roundedSeconds));
      return new AttemptBudget(Duration.ofNanos(remainingNanos), scannerSeconds);
    }

    Duration remainingCancellationTimeout() {
      long remainingNanos = remainingNanos();
      return remainingNanos <= 0
          ? null
          : Duration.ofNanos(Math.min(remainingNanos, MAX_CANCELLATION_NANOS));
    }

    private long remainingNanos() {
      return timeoutNanos - (nanoTime.getAsLong() - startedNanos);
    }

    private static ScannerAdapterException timeout() {
      return new ScannerAdapterException(
          "SCANNER_TIMEOUT",
          "Scanner request exhausted its end-to-end failover deadline",
          true);
    }
  }

  /**
   * Keeps response-body reads inside the same absolute timeout that started before the request.
   *
   * <p>{@link HttpResponse.BodyHandlers#ofInputStream()} completes when headers arrive. The JDK
   * request timeout therefore cannot by itself stop a peer that stalls during a later body read.
   */
  private static final class ResponseReadDeadline {
    private final long expiresAtNanos;

    private ResponseReadDeadline(Duration timeout) {
      long now = System.nanoTime();
      long timeoutNanos;
      try {
        timeoutNanos = Math.max(1L, timeout.toNanos());
      } catch (ArithmeticException overflow) {
        timeoutNanos = Long.MAX_VALUE;
      }
      this.expiresAtNanos = timeoutNanos > Long.MAX_VALUE - now
          ? Long.MAX_VALUE
          : now + timeoutNanos;
    }

    private InputStream watch(InputStream body) {
      long remaining = expiresAtNanos - System.nanoTime();
      if (remaining <= 0) {
        try {
          body.close();
        } catch (IOException ignored) {
          // The absolute timeout remains authoritative.
        }
        throw timeout(null);
      }
      return new DeadlineInputStream(body, Duration.ofNanos(remaining));
    }
  }

  private static final class DeadlineInputStream extends InputStream {
    private final InputStream delegate;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean expired = new AtomicBoolean();
    private final Thread deadlineWatcher;

    private DeadlineInputStream(InputStream delegate, Duration remaining) {
      this.delegate = Objects.requireNonNull(delegate, "delegate");
      this.deadlineWatcher = Thread.ofVirtual()
          .name("security-scan-response-deadline")
          .start(() -> expireAfter(remaining));
    }

    @Override
    public int read() throws IOException {
      try {
        int value = delegate.read();
        requireWithinDeadline();
        return value;
      } catch (IOException failure) {
        throw translate(failure);
      }
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      try {
        int count = delegate.read(bytes, offset, length);
        requireWithinDeadline();
        return count;
      } catch (IOException failure) {
        throw translate(failure);
      }
    }

    @Override
    public void close() throws IOException {
      deadlineWatcher.interrupt();
      if (closed.compareAndSet(false, true)) delegate.close();
    }

    private void expireAfter(Duration remaining) {
      try {
        Thread.sleep(remaining);
      } catch (InterruptedException stopped) {
        return;
      }
      expired.set(true);
      if (closed.compareAndSet(false, true)) {
        try {
          delegate.close();
        } catch (IOException ignored) {
          // The blocked reader translates the absolute timeout.
        }
      }
    }

    private void requireWithinDeadline() {
      if (expired.get()) throw timeout(null);
    }

    private RuntimeException translate(IOException failure) throws IOException {
      if (expired.get()) return timeout(failure);
      throw failure;
    }
  }

  private static ScannerAdapterException timeout(Throwable cause) {
    return cause == null
        ? new ScannerAdapterException(
            "SCANNER_TIMEOUT", "Scanner response body exceeded its absolute deadline", true)
        : new ScannerAdapterException(
            "SCANNER_TIMEOUT",
            "Scanner response body exceeded its absolute deadline",
            true,
            cause);
  }

  private static final class BoundedResponseInputStream extends FilterInputStream {
    private final long limit;
    private long count;

    private BoundedResponseInputStream(InputStream delegate, long limit) {
      super(delegate);
      this.limit = Math.max(1L, limit);
    }

    @Override
    public int read() throws IOException {
      if (count >= limit) {
        int extra = super.read();
        if (extra < 0) return -1;
        throw new ResponseTooLargeIOException();
      }
      int value = super.read();
      if (value >= 0) count++;
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      Objects.checkFromIndexSize(offset, length, bytes.length);
      if (length == 0) return 0;
      if (count >= limit) {
        return read() < 0 ? -1 : 1;
      }
      int allowed = (int) Math.min((long) length, limit - count);
      int read = super.read(bytes, offset, allowed);
      if (read > 0) count += read;
      return read;
    }

    @Override
    public long skip(long length) throws IOException {
      if (length <= 0) return 0;
      if (count >= limit) {
        return read() < 0 ? 0 : 1;
      }
      long skipped = super.skip(Math.min(length, limit - count));
      count += skipped;
      return skipped;
    }

    @Override
    public boolean markSupported() {
      return false;
    }

    @Override
    public synchronized void mark(int readLimit) {
      // Resetting would make the byte accounting ambiguous.
    }

    @Override
    public synchronized void reset() throws IOException {
      throw new IOException("mark/reset is not supported");
    }

    private void drain() throws IOException {
      byte[] buffer = new byte[8192];
      while (read(buffer) >= 0) {
        // Enforce the byte limit even if Jackson stops at the first complete JSON value.
      }
    }
  }

  private static final class ResponseTooLargeIOException extends IOException {}

  @JsonIgnoreProperties("summary")
  private abstract static class IgnoreSummaryMixin {}

  private record ScannerErrorPayload(String code, String message, boolean retryable) {}
}
