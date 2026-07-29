package com.github.klboke.kkrepo.scanner;

import com.github.klboke.kkrepo.security.scan.ScannerArtifactType;
import com.github.klboke.kkrepo.security.scan.ScannerContract;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Capabilities;
import com.github.klboke.kkrepo.security.scan.ScannerContract.CatalogResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.CancellationResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.MatchResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.OciScanRequest;
import com.github.klboke.kkrepo.security.scan.ScannerContract.OciScanResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Readiness;
import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ScannerController {
  private final ScannerEngineService engine;
  private final ScannerAdapterProperties properties;
  private final ScannerCapacityLimiter capacity;
  private final ScannerExecutionRegistry executions;

  public ScannerController(
      ScannerEngineService engine,
      ScannerAdapterProperties properties,
      ScannerCapacityLimiter capacity,
      ScannerExecutionRegistry executions) {
    this.engine = engine;
    this.properties = properties;
    this.capacity = capacity;
    this.executions = executions;
  }

  @PostConstruct
  void requireConfiguredCredential() {
    if (properties.getServiceCredential() == null
        || properties.getServiceCredential().isBlank()) {
      throw new IllegalStateException(
          "kkrepo.scanner.service-credential must be configured");
    }
  }

  @GetMapping("/v1/capabilities")
  public Capabilities capabilities(
      @RequestHeader(value = "X-KKRepo-Scanner-Credential", required = false)
          String credential) {
    authorize(credential);
    return engine.capabilities();
  }

  @GetMapping("/v1/readiness")
  public Readiness readiness(
      @RequestHeader(value = "X-KKRepo-Scanner-Credential", required = false)
          String credential) {
    authorize(credential);
    return engine.readiness();
  }

  @PostMapping("/v1/catalog")
  public CatalogResponse catalog(
      HttpServletRequest request,
      @RequestHeader("X-KKRepo-API-Version") String apiVersion,
      @RequestHeader("X-KKRepo-Run-ID") String runId,
      @RequestHeader("X-KKRepo-Expected-SHA256") String expectedSha256,
      @RequestHeader("X-KKRepo-Expected-Size") long expectedSize,
      @RequestHeader(value = "X-KKRepo-Artifact-Type", required = false)
          String artifactType,
      @RequestHeader(value = "X-KKRepo-Scanner-Credential", required = false)
          String credential)
      throws IOException {
    authorize(credential);
    requireApiVersion(apiVersion);
    ResourceLimits resourceLimits = limits(request);
    return executions.execute(
        runId,
        () -> capacity.execute(
            resourceLimits,
            () -> engine.catalog(
                request.getInputStream(),
                expectedSha256,
                expectedSize,
                ScannerArtifactType.fromWireValue(artifactType),
                resourceLimits)));
  }

  @PostMapping("/v1/match")
  public MatchResponse match(
      HttpServletRequest request,
      @RequestHeader("X-KKRepo-API-Version") String apiVersion,
      @RequestHeader("X-KKRepo-Run-ID") String runId,
      @RequestHeader("X-KKRepo-SBOM-SHA256") String expectedSha256,
      @RequestHeader(value = "X-KKRepo-Scanner-Credential", required = false)
          String credential)
      throws IOException {
    authorize(credential);
    requireApiVersion(apiVersion);
    ResourceLimits resourceLimits = limits(request);
    return executions.execute(
        runId,
        () -> capacity.execute(
            resourceLimits,
            () -> engine.match(request.getInputStream(), expectedSha256, resourceLimits)));
  }

  @PostMapping("/v1/oci/scan")
  public OciScanResponse oci(
      @RequestBody OciScanRequest request,
      @RequestHeader(value = "X-KKRepo-Scanner-Credential", required = false)
          String credential) throws IOException {
    authorize(credential);
    ResourceLimits resourceLimits = request == null ? null : request.limits();
    return executions.execute(
        request == null ? null : request.runId(),
        () -> capacity.execute(resourceLimits, () -> engine.scanOci(request)));
  }

  @PostMapping("/v1/runs/{runId}/cancel")
  public CancellationResponse cancel(
      @PathVariable("runId") String runId,
      @RequestHeader("X-KKRepo-API-Version") String apiVersion,
      @RequestHeader(value = "X-KKRepo-Scanner-Credential", required = false)
          String credential) {
    authorize(credential);
    requireApiVersion(apiVersion);
    return new CancellationResponse(runId, executions.cancel(runId));
  }

  @ExceptionHandler(ScannerRequestException.class)
  public ResponseEntity<Map<String, Object>> scannerError(ScannerRequestException failure) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(failure.status());
    if (failure.status() == 429) {
      response.header("Retry-After", Integer.toString(properties.getRetryAfterSeconds()));
    }
    return response.body(Map.of(
        "code", failure.code(),
        "message", failure.getMessage(),
        "retryable", failure.retryable()));
  }

  @ExceptionHandler({IllegalArgumentException.class, IOException.class})
  public ResponseEntity<Map<String, Object>> badRequest(Exception failure) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
        "code", "SCANNER_REQUEST_INVALID",
        "message", "Scanner request is invalid",
        "retryable", false));
  }

  private ResourceLimits limits(HttpServletRequest request) {
    return new ResourceLimits(
        longHeader(request, "X-KKRepo-Max-Input-Bytes", properties.getMaxInputBytes()),
        intHeader(request, "X-KKRepo-Max-Archive-Entries", 100_000),
        longHeader(
            request, "X-KKRepo-Max-Uncompressed-Bytes", properties.getMaxInputBytes() * 2),
        longHeader(request, "X-KKRepo-Max-Single-File-Bytes", properties.getMaxInputBytes()),
        intHeader(request, "X-KKRepo-Max-Nested-Depth", 2),
        intHeader(request, "X-KKRepo-Timeout-Seconds", 600));
  }

  private static long longHeader(HttpServletRequest request, String name, long fallback) {
    String value = request.getHeader(name);
    if (value == null || value.isBlank()) return fallback;
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new ScannerRequestException(
          "SCANNER_HEADER_INVALID", "Scanner limit header is invalid", 400, false);
    }
  }

  private static int intHeader(HttpServletRequest request, String name, int fallback) {
    long value = longHeader(request, name, fallback);
    if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
      throw new ScannerRequestException(
          "SCANNER_HEADER_INVALID", "Scanner limit header is invalid", 400, false);
    }
    return (int) value;
  }

  private void authorize(String actual) {
    String expected = properties.getServiceCredential();
    if (expected == null || expected.isBlank()) {
      throw new ScannerRequestException(
          "SCANNER_CREDENTIAL_NOT_CONFIGURED",
          "Scanner service credential is not configured",
          503,
          false);
    }
    byte[] left = expected.getBytes(StandardCharsets.UTF_8);
    byte[] right = actual == null ? new byte[0] : actual.getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(left, right)) {
      throw new ScannerRequestException(
          "SCANNER_UNAUTHORIZED", "Scanner service credential is invalid", 401, false);
    }
  }

  private static void requireApiVersion(String version) {
    if (!ScannerContract.API_VERSION.equals(version)) {
      throw new ScannerRequestException(
          "SCANNER_API_UNSUPPORTED", "Scanner API version is not supported", 400, false);
    }
  }
}
