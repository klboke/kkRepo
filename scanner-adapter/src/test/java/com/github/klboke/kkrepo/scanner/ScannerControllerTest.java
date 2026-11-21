package com.github.klboke.kkrepo.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.http.HttpStatus;

class ScannerControllerTest {
  private static final long MIB = 1024L * 1024;
  private ScannerEngineService engine;
  private ScannerAdapterProperties properties;
  private ScannerController controller;
  private HttpServletRequest request;
  private ScannerExecutionRegistry executions;
  private SimpleMeterRegistry metrics;

  @BeforeEach
  void setUp() throws Exception {
    engine = mock(ScannerEngineService.class);
    properties = new ScannerAdapterProperties();
    properties.setServiceCredential("secret");
    executions = new ScannerExecutionRegistry();
    metrics = new SimpleMeterRegistry();
    controller = new ScannerController(
        engine,
        properties,
        new ScannerCapacityLimiter(properties, metrics),
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

    when(request.getContentType()).thenReturn("multipart/form-data; boundary=conan-boundary");
    when(engine.catalogConan(
        any(), eq("multipart/form-data; boundary=conan-boundary"), eq("c".repeat(64)),
        eq(7L), eq("d".repeat(64)), eq(8L), any())).thenReturn(catalog);
    assertEquals(catalog, controller.catalogConan(
        request, "v1", "run-conan", "c".repeat(64), 7L, "d".repeat(64), 8L));
    verify(engine).catalogConan(
        any(), eq("multipart/form-data; boundary=conan-boundary"), eq("c".repeat(64)),
        eq(7L), eq("d".repeat(64)), eq(8L), any());

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
  void admitsTwoSmallCatalogRequestsWithTheDefaultProfileAndScratchBudget() throws Exception {
    useDefaultProfileLimits();
    long artifactBytes = 7237;
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CatalogResponse result = catalog();
    String firstSha = "a".repeat(64);
    String secondSha = "b".repeat(64);
    when(engine.catalog(any(), eq(firstSha), eq(artifactBytes), eq(ScannerArtifactType.JAR), any()))
        .thenAnswer(invocation -> {
          firstEntered.countDown();
          assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
          return result;
        });
    when(engine.catalog(any(), eq(secondSha), eq(artifactBytes), eq(ScannerArtifactType.JAR), any()))
        .thenAnswer(invocation -> {
          assertEquals(2.0, metrics.get("kkrepo_scanner_active").gauge().value());
          assertEquals(6242.0 * MIB, scratchReservedBytes());
          return result;
        });
    try (var executor = Executors.newSingleThreadExecutor()) {
      var first = executor.submit(() -> controller.catalog(
          request, "v1", "first", firstSha, artifactBytes, "jar"));
      try {
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
        assertEquals(result, controller.catalog(
            request, "v1", "second", secondSha, artifactBytes, "jar"));
      } finally {
        releaseFirst.countDown();
      }
      assertEquals(result, first.get(5, TimeUnit.SECONDS));
    }
    assertEquals(0.0, scratchReservedBytes());
  }

  @Test
  void reservesProfileSpaceForPreparedCatalogsAndSbomSpaceForMatch() throws Exception {
    useDefaultProfileLimits();
    var catalog = catalog();
    when(engine.catalog(any(), any(), eq(7237L), eq(ScannerArtifactType.APK), any()))
        .thenAnswer(invocation -> {
          assertEquals(4145.0 * MIB, scratchReservedBytes());
          return catalog;
        });
    assertEquals(catalog, controller.catalog(
        request, "v1", "apk", "a".repeat(64), 7237, "apk"));

    when(engine.catalogConan(any(), any(), any(), eq(7237L), any(), eq(MIB), any()))
        .thenAnswer(invocation -> {
          assertEquals(4145.0 * MIB, scratchReservedBytes());
          return catalog;
        });
    when(request.getContentType()).thenReturn("multipart/form-data; boundary=conan-boundary");
    assertEquals(catalog, controller.catalogConan(
        request, "v1", "conan", "a".repeat(64), 7237, "b".repeat(64), MIB));

    var match = match();
    when(engine.match(any(), any(), any())).thenAnswer(invocation -> {
      assertEquals(65.0 * MIB, scratchReservedBytes());
      return match;
    });
    assertEquals(match, controller.match(request, "v1", "match", "b".repeat(64)));
    assertEquals(0.0, scratchReservedBytes());
  }

  @Test
  void rejectsInvalidCatalogDeclarationsBeforeReadingTheBody() throws Exception {
    useDefaultProfileLimits();
    assertEquals(413, assertCode("INPUT_SIZE_INVALID", () -> controller.catalog(
        request, "v1", "catalog", "a".repeat(64), 1024 * MIB + 1, "jar")).status());
    verify(request, never()).getInputStream();
  }

  private void useDefaultProfileLimits() {
    when(request.getHeader("X-KKRepo-Max-Input-Bytes")).thenReturn(Long.toString(1024 * MIB));
    when(request.getHeader("X-KKRepo-Max-Single-File-Bytes")).thenReturn(Long.toString(1024 * MIB));
    when(request.getHeader("X-KKRepo-Max-Uncompressed-Bytes")).thenReturn(Long.toString(10240 * MIB));
    when(request.getHeader("X-KKRepo-Max-Nested-Depth")).thenReturn("3");
  }

  private double scratchReservedBytes() {
    return metrics.get("kkrepo_scanner_scratch_reserved_bytes").gauge().value();
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
