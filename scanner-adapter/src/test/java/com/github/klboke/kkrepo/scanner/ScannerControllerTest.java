package com.github.klboke.kkrepo.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness;
import com.github.klboke.kkrepo.security.scan.ScannerArtifactType;
import com.github.klboke.kkrepo.security.scan.ScannerContract.CatalogResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.MatchResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.OciScanRequest;
import com.github.klboke.kkrepo.security.scan.ScannerContract.OciScanResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.http.HttpStatus;

class ScannerControllerTest {
  private ScannerEngineService engine;
  private ScannerAdapterProperties properties;
  private ScannerController controller;
  private HttpServletRequest request;
  private ScannerExecutionRegistry executions;

  @BeforeEach
  void setUp() throws Exception {
    engine = mock(ScannerEngineService.class);
    properties = new ScannerAdapterProperties();
    properties.setServiceCredential("secret");
    executions = new ScannerExecutionRegistry();
    controller = new ScannerController(
        engine,
        properties,
        new ScannerCapacityLimiter(properties, new SimpleMeterRegistry()),
        executions);
    request = mock(HttpServletRequest.class);
    when(request.getInputStream()).thenReturn(new TestServletInputStream("body".getBytes()));
  }

  @Test
  void delegatesCapabilitiesReadinessCatalogMatchAndOci() throws Exception {
    properties.setServiceCredential("secret");
    controller.capabilities();
    controller.readiness();

    when(request.getHeader("X-KKRepo-Max-Input-Bytes")).thenReturn("2048");
    when(request.getHeader("X-KKRepo-Max-Archive-Entries")).thenReturn("200");
    when(request.getHeader("X-KKRepo-Max-Uncompressed-Bytes")).thenReturn("4096");
    when(request.getHeader("X-KKRepo-Max-Single-File-Bytes")).thenReturn("1024");
    when(request.getHeader("X-KKRepo-Max-Nested-Depth")).thenReturn("3");
    when(request.getHeader("X-KKRepo-Timeout-Seconds")).thenReturn("30");
    CatalogResponse catalog = catalog();
    when(engine.catalog(any(), eq("a".repeat(64)), eq(4L), eq(ScannerArtifactType.JAR), any()))
        .thenReturn(catalog);
    assertEquals(catalog, controller.catalog(
        request, "v1", "run-catalog", "a".repeat(64), 4, "jar"));
    ArgumentCaptor<ResourceLimits> catalogLimits =
        ArgumentCaptor.forClass(ResourceLimits.class);
    verify(engine).catalog(
        any(), eq("a".repeat(64)), eq(4L), eq(ScannerArtifactType.JAR),
        catalogLimits.capture());
    assertEquals(200, catalogLimits.getValue().maxArchiveEntries());

    MatchResponse match = match();
    when(engine.match(any(), eq("b".repeat(64)), any())).thenReturn(match);
    assertEquals(
        match,
        controller.match(request, "v1", "run-match", "b".repeat(64)));

    OciScanRequest ociRequest = mock(OciScanRequest.class);
    when(ociRequest.runId()).thenReturn("run-oci");
    when(ociRequest.limits()).thenReturn(new ResourceLimits(
        2048, 200, 4096, 1024, 3, 30));
    OciScanResponse ociResponse =
        new OciScanResponse(catalog, match, List.of(), List.of());
    when(engine.scanOci(ociRequest)).thenReturn(ociResponse);
    assertEquals(ociResponse, controller.oci(ociRequest));
    assertFalse(controller.cancel("idle-run", "v1").cancelled());
  }

  @Test
  void appliesFallbackLimitsAndRejectsInvalidHeadersAndApi() throws Exception {
    properties.setMaxInputBytes(8192);
    when(engine.match(any(), eq("b".repeat(64)), any())).thenReturn(match());
    controller.match(request, "v1", "run-defaults", "b".repeat(64));
    ArgumentCaptor<ResourceLimits> limits = ArgumentCaptor.forClass(ResourceLimits.class);
    verify(engine).match(any(), eq("b".repeat(64)), limits.capture());
    assertEquals(8192, limits.getValue().maxInputBytes());
    assertEquals(100_000, limits.getValue().maxArchiveEntries());

    assertCode(
        "SCANNER_API_UNSUPPORTED",
        () -> controller.match(
            request, "v2", "run-api", "b".repeat(64)));
    when(request.getHeader("X-KKRepo-Max-Archive-Entries")).thenReturn("not-a-number");
    assertCode(
        "SCANNER_HEADER_INVALID",
        () -> controller.match(
            request, "v1", "run-invalid-number", "b".repeat(64)));
    when(request.getHeader("X-KKRepo-Max-Archive-Entries"))
        .thenReturn(Long.toString((long) Integer.MAX_VALUE + 1));
    assertCode(
        "SCANNER_HEADER_INVALID",
        () -> controller.match(
            request, "v1", "run-overflow", "b".repeat(64)));
  }

  @Test
  void mapsClassifiedAndGenericFailuresToBoundedResponses() {
    var classified = controller.scannerError(
        new ScannerRequestException("DOWN", "scanner down", 503, true));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, classified.getStatusCode());
    assertEquals("DOWN", classified.getBody().get("code"));
    assertEquals(true, classified.getBody().get("retryable"));

    properties.setRetryAfterSeconds(17);
    var capacity = controller.scannerError(new ScannerRequestException(
        "SCANNER_CAPACITY_EXHAUSTED", "busy", 429, true));
    assertEquals(HttpStatus.TOO_MANY_REQUESTS, capacity.getStatusCode());
    assertEquals("17", capacity.getHeaders().getFirst("Retry-After"));

    var generic = controller.badRequest(new IllegalArgumentException("secret detail"));
    assertEquals(HttpStatus.BAD_REQUEST, generic.getStatusCode());
    assertEquals("SCANNER_REQUEST_INVALID", generic.getBody().get("code"));
    assertFalse((Boolean) generic.getBody().get("retryable"));
  }

  private static ScannerRequestException assertCode(String code, ThrowingRunnable invocation) {
    ScannerRequestException exception =
        assertThrows(ScannerRequestException.class, invocation::run);
    assertEquals(code, exception.code());
    return exception;
  }

  private static CatalogResponse catalog() {
    return new CatalogResponse(
        "adapter", "1", "syft", "1", "cap", "a".repeat(64),
        ScanCompleteness.COMPLETE, "CycloneDX", "1.5", 0, 0,
        "{}".getBytes(), List.of(), Map.of());
  }

  private static MatchResponse match() {
    return new MatchResponse(
        "adapter", "1", "grype", "1", "db", Instant.EPOCH, "cap",
        ScanCompleteness.COMPLETE, "{}".getBytes(), List.of(), Map.of());
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static final class TestServletInputStream
      extends jakarta.servlet.ServletInputStream {
    private final ByteArrayInputStream delegate;

    private TestServletInputStream(byte[] value) {
      this.delegate = new ByteArrayInputStream(value);
    }

    @Override
    public int read() {
      return delegate.read();
    }

    @Override
    public boolean isFinished() {
      return delegate.available() == 0;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(jakarta.servlet.ReadListener readListener) {}
  }
}
