package com.github.klboke.kkrepo.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.scanner.ScannerDocumentMapper.DatabaseProvenance;
import com.github.klboke.kkrepo.scanner.ScannerDocumentMapper.EngineVersion;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness;
import com.github.klboke.kkrepo.security.scan.ScannerArtifactType;
import com.github.klboke.kkrepo.security.scan.ScannerContract.CatalogResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.MatchResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.OciScanRequest;
import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class ScannerEngineServiceBehaviorTest {
  private static final String SHA256 = "a".repeat(64);
  private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");

  @TempDir Path temporaryDirectory;

  @Test
  void reportsCapabilitiesCachesReadinessAndCatalogsWithSafeFilename() throws Exception {
    Fixture fixture = new Fixture(temporaryDirectory);
    when(fixture.scannerInput.copy(any(), any(), eq(SHA256), eq(8L), any(), any()))
        .thenAnswer(invocation -> {
          Path path = invocation.getArgument(1);
          Files.write(path, "artifact".getBytes());
          return new ScannerInput.Verified(path, SHA256, 8);
        });
    when(fixture.archiveGuard.inspect(any(), any(), any(), any()))
        .thenReturn(new ArchiveGuard.Inspection(2, 8, 0));

    var capabilities = fixture.engine.capabilities();
    var first = fixture.engine.readiness();
    var cached = fixture.engine.readiness();
    CatalogResponse response = fixture.engine.catalog(
        new ByteArrayInputStream("artifact".getBytes()),
        SHA256,
        8,
        ScannerArtifactType.JAR,
        limits());

    assertEquals("syft-grype-v1", capabilities.adapterName());
    assertTrue(first.ready());
    assertEquals(first, cached);
    assertEquals(ScanCompleteness.COMPLETE, response.completeness());
    ArgumentCaptor<Path> target = ArgumentCaptor.forClass(Path.class);
    verify(fixture.scannerInput).copy(
        any(), target.capture(), eq(SHA256), eq(8L), any(), any());
    assertEquals("artifact.jar", target.getValue().getFileName().toString());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> command =
        (ArgumentCaptor<List<String>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<Path> scannerOutput = ArgumentCaptor.forClass(Path.class);
    verify(fixture.processes).run(
        command.capture(), any(), scannerOutput.capture(), any(), any());
    assertTrue(command.getValue().contains("cyclonedx-json"));
    assertFalse(command.getValue().stream()
        .anyMatch(value -> value.startsWith("cyclonedx-json=")));
    assertEquals("sbom.cdx.json", scannerOutput.getValue().getFileName().toString());
  }

  @Test
  void matchesCycloneDxAndRejectsOversizedOrMissingLimits() throws Exception {
    Fixture fixture = new Fixture(temporaryDirectory);
    when(fixture.scannerInput.copy(any(), any(), eq(SHA256), eq(null), any(), any()))
        .thenAnswer(invocation -> {
          Path path = invocation.getArgument(1);
          Files.write(path, "{}".getBytes());
          return new ScannerInput.Verified(path, SHA256, 2);
        });

    MatchResponse response = fixture.engine.match(
        new ByteArrayInputStream("{}".getBytes()), SHA256, limits());

    assertEquals("db-1", response.vulnerabilityDatabaseRevision());
    ArgumentCaptor<Duration> readinessTimeouts = ArgumentCaptor.forClass(Duration.class);
    verify(fixture.processes, times(3))
        .versionOutput(anyString(), anyList(), readinessTimeouts.capture());
    assertTrue(readinessTimeouts.getAllValues().stream()
        .allMatch(timeout -> timeout.compareTo(Duration.ofSeconds(30)) < 0));
    assertCode(
        "RESOURCE_LIMITS_REQUIRED",
        () -> fixture.engine.match(new ByteArrayInputStream(new byte[0]), SHA256, null));

    Fixture oversized = new Fixture(temporaryDirectory.resolve("large"));
    when(oversized.scannerInput.copy(any(), any(), eq(SHA256), eq(null), any(), any()))
        .thenAnswer(invocation -> new ScannerInput.Verified(
            invocation.getArgument(1), SHA256, 2048));
    assertCode(
        "SBOM_TOO_LARGE",
        () -> oversized.engine.match(
            new ByteArrayInputStream(new byte[0]), SHA256, limits()));
  }

  @Test
  void scansOciPlatformsAndReturnsPartialWhenOnePlatformIsMissing() throws Exception {
    Fixture fixture = new Fixture(temporaryDirectory);
    fixture.failPlatform = "linux/arm64";
    OciScanRequest request = ociRequest(
        "http://registry:5000/prefix", List.of("linux/amd64", "linux/arm64"));

    var response = fixture.engine.scanOci(request);

    assertEquals(List.of("linux/amd64"), response.scannedPlatforms());
    assertEquals(List.of("linux/arm64"), response.missingPlatforms());
    assertEquals(ScanCompleteness.PARTIAL, response.catalog().completeness());
    assertEquals(ScanCompleteness.PARTIAL, response.match().completeness());
    ArgumentCaptor<Duration> timeouts = ArgumentCaptor.forClass(Duration.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> commands = ArgumentCaptor.forClass(List.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> environments = ArgumentCaptor.forClass(Map.class);
    verify(fixture.processes, times(3))
        .run(commands.capture(), any(), any(), timeouts.capture(), environments.capture());
    assertTrue(
        timeouts.getAllValues().getLast().compareTo(timeouts.getAllValues().getFirst()) < 0,
        "OCI stages must consume one shared end-to-end deadline");
    assertTrue(commands.getAllValues().getFirst().stream()
        .anyMatch(value -> value.startsWith("oci-dir:")));
    assertFalse(environments.getAllValues().getFirst().keySet().stream()
        .anyMatch(value -> value.startsWith("SYFT_REGISTRY_AUTH_")));
    assertEquals(
        "4096B",
        environments.getAllValues().getFirst().get("SYFT_SOURCE_IMAGE_MAX_LAYER_SIZE"));
  }

  @Test
  void keepsTransientOciRegistryFailuresRetryableInsteadOfPublishingPartialResults() {
    Fixture fixture = new Fixture(temporaryDirectory);
    fixture.transientPlatform = "linux/arm64";

    ScannerRequestException failure = assertCode(
        "OCI_REGISTRY_SCAN_FAILED",
        () -> fixture.engine.scanOci(ociRequest(
            "https://registry.example.test",
            List.of("linux/amd64", "linux/arm64"))));

    assertEquals(503, failure.status());
    assertTrue(failure.retryable());
    verify(fixture.documents, never()).mergeCycloneDx(any(), anyLong());
  }

  @Test
  void reportsDegradedReadinessAndFailsWhenNoOciPlatformCanBeScanned() throws Exception {
    Fixture degraded = new Fixture(temporaryDirectory.resolve("degraded"));
    when(degraded.documents.engineVersion(any(), eq("syft")))
        .thenThrow(new ScannerRequestException("VERSION", "bad", 503, true));
    assertFalse(degraded.engine.readiness().ready());
    degraded.engine.invalidateReadiness();
    assertCode(
        "SCANNER_NOT_READY",
        () -> degraded.engine.catalog(
            new ByteArrayInputStream(new byte[0]), SHA256, 0,
            ScannerArtifactType.UNKNOWN, limits()));

    Fixture unavailable = new Fixture(temporaryDirectory.resolve("unavailable"));
    unavailable.failPlatform = "*";
    assertCode(
        "OCI_SCAN_FAILED",
        () -> unavailable.engine.scanOci(
            ociRequest("https://registry.example.test", List.of("linux/amd64"))));
  }

  @Test
  void rejectsOciPlatformFanoutAndAggregateDocumentsBeforeMerging() {
    Fixture tooMany = new Fixture(temporaryDirectory.resolve("too-many"));
    List<String> platforms = java.util.stream.IntStream
        .rangeClosed(0, ScannerEngineService.MAX_OCI_PLATFORMS)
        .mapToObj(index -> "linux/variant" + index)
        .toList();
    assertCode(
        "OCI_REQUEST_INVALID",
        () -> tooMany.engine.scanOci(
            ociRequest("https://registry.example.test", platforms)));
    verify(tooMany.processes, never()).run(anyList(), any(), any(), any(), any());

    Fixture aggregate = new Fixture(temporaryDirectory.resolve("aggregate"));
    aggregate.syftOutput = new byte[600];
    assertCode(
        "SCANNER_OUTPUT_TOO_LARGE",
        () -> aggregate.engine.scanOci(ociRequest(
            "https://registry.example.test",
            List.of("linux/amd64", "linux/arm64"))));
    verify(aggregate.documents, never()).mergeCycloneDx(any(), anyLong());
  }

  @Test
  void rejectsOversizedMergedOciDocument() {
    Fixture fixture = new Fixture(temporaryDirectory.resolve("merged"));
    when(fixture.documents.mergeCycloneDx(any(), anyLong())).thenReturn(new byte[1025]);

    assertCode(
        "SCANNER_OUTPUT_TOO_LARGE",
        () -> fixture.engine.scanOci(
            ociRequest("https://registry.example.test", List.of("linux/amd64"))));
  }

  @Test
  void validatesEveryOciBoundaryBeforeStartingAProcess() {
    Fixture fixture = new Fixture(temporaryDirectory);
    assertCode("OCI_REQUEST_INVALID", () -> fixture.engine.scanOci(null));
    assertCode(
        "OCI_REGISTRY_INVALID",
        () -> fixture.engine.scanOci(ociRequest("http://[", List.of("linux/amd64"))));
    assertCode(
        "OCI_REQUEST_INVALID",
        () -> fixture.engine.scanOci(ociRequest("file:///tmp/registry", List.of("linux/amd64"))));
    assertCode(
        "OCI_REQUEST_INVALID",
        () -> fixture.engine.scanOci(ociRequest(
            "https://user@example.test", List.of("linux/amd64"))));
    assertCode(
        "OCI_REQUEST_INVALID",
        () -> fixture.engine.scanOci(ociRequest(
            "https://registry.example.test?query=1", List.of("linux/amd64"))));
    assertCode(
        "OCI_REQUEST_INVALID",
        () -> fixture.engine.scanOci(ociRequest(
            "https://registry.example.test", List.of("../invalid"))));
  }

  private static ScannerRequestException assertCode(String code, Runnable invocation) {
    ScannerRequestException exception =
        assertThrows(ScannerRequestException.class, invocation::run);
    assertEquals(code, exception.code());
    return exception;
  }

  private static ResourceLimits limits() {
    return new ResourceLimits(4096, 100, 8192, 4096, 2, 30);
  }

  private static OciScanRequest ociRequest(String registryUrl, List<String> platforms) {
    return new OciScanRequest(
        "v1", "run", "key", registryUrl, "repo/image",
        "sha256:" + SHA256, platforms, "token", "config", limits());
  }

  private static CatalogResponse catalog() {
    return new CatalogResponse(
        "adapter", "1", "syft", "1.2", "cap", SHA256,
        ScanCompleteness.COMPLETE, "CycloneDX", "1.5", 0, 0,
        "{\"bomFormat\":\"CycloneDX\"}".getBytes(), List.of(), Map.of());
  }

  private static MatchResponse match() {
    return new MatchResponse(
        "adapter", "1", "grype", "2.3", "db-1", NOW, "cap",
        ScanCompleteness.COMPLETE, "{}".getBytes(), List.of(), Map.of());
  }

  private static final class Fixture {
    final ScannerAdapterProperties properties = new ScannerAdapterProperties();
    final BoundedProcessRunner processes = mock(BoundedProcessRunner.class);
    final ScannerInput scannerInput = mock(ScannerInput.class);
    final ArchiveGuard archiveGuard = mock(ArchiveGuard.class);
    final OciRegistryStager ociRegistryStager = mock(OciRegistryStager.class);
    final ScannerDocumentMapper documents = mock(ScannerDocumentMapper.class);
    final ScannerEngineService engine;
    String failPlatform;
    String transientPlatform;
    byte[] syftOutput = "{\"bomFormat\":\"CycloneDX\"}".getBytes();

    Fixture(Path workDirectory) {
      properties.setWorkDirectory(workDirectory);
      properties.setVulnerabilityDatabaseDirectory(workDirectory.resolve("database"));
      properties.setMaxInputBytes(4096);
      properties.setMaxOutputBytes(1024);
      properties.setReadinessCache(Duration.ofMinutes(1));
      when(processes.versionOutput(anyString(), anyList())).thenReturn("{}".getBytes());
      when(processes.versionOutput(anyString(), anyList(), any()))
          .thenReturn("{}".getBytes());
      when(documents.engineVersion(any(), eq("syft")))
          .thenReturn(new EngineVersion("syft", "1.2"));
      when(documents.engineVersion(any(), eq("grype")))
          .thenReturn(new EngineVersion("grype", "2.3"));
      when(documents.database(any())).thenReturn(new DatabaseProvenance("db-1", NOW));
      when(documents.catalog(any(), anyString(), anyString(), anyString(), any()))
          .thenReturn(catalog());
      when(documents.match(any(), anyString(), any(), anyString())).thenReturn(match());
      when(documents.mergeCycloneDx(any(), anyLong())).thenReturn(
          "{\"bomFormat\":\"CycloneDX\"}".getBytes());
      when(ociRegistryStager.stage(any(), any(), any(), any())).thenAnswer(invocation -> {
        OciScanRequest request = invocation.getArgument(0);
        List<String> platforms = request.requiredPlatforms().isEmpty()
            ? List.of("linux/amd64") : request.requiredPlatforms();
        return new OciRegistryStager.StagedImage(
            workDirectory.resolve("layout"), platforms, List.of(), 64, 2, 32, 0);
      });
      doAnswer(invocation -> {
        @SuppressWarnings("unchecked")
        List<String> command = invocation.getArgument(0);
        Path stdout = invocation.getArgument(2);
        if (command.contains("--platform")) {
          String platform = command.get(command.indexOf("--platform") + 1);
          if ("*".equals(failPlatform) || platform.equals(failPlatform)) {
            throw new ScannerRequestException(
                "SCANNER_PLATFORM_NOT_FOUND", "missing platform", 422, false);
          }
          if (platform.equals(transientPlatform)) {
            throw new ScannerRequestException(
                "OCI_REGISTRY_SCAN_FAILED", "registry unavailable", 503, true);
          }
        }
        Path output = stdout;
        Files.createDirectories(output.getParent());
        Files.write(output, command.getFirst().equals("grype")
            ? "{}".getBytes() : syftOutput);
        return new BoundedProcessRunner.Result(0, Files.size(output), new byte[0]);
      }).when(processes).run(anyList(), any(), any(), any(), any());
      ScannerDatabaseCoordinator database = new ScannerDatabaseCoordinator(properties);
      assertEquals(
          ScannerDatabaseCoordinator.UpdateResult.UPDATED,
          database.updateIfDue(
              Duration.ZERO,
              Duration.ofSeconds(1),
              directory -> Files.writeString(directory.resolve("database"), "fixture")));
      engine = new ScannerEngineService(
          properties,
          processes,
          scannerInput,
          archiveGuard,
          ociRegistryStager,
          documents,
          database);
    }
  }
}
