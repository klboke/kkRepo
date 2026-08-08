package com.github.klboke.kkrepo.scanner;

import com.github.klboke.kkrepo.scanner.ScannerDocumentMapper.DatabaseProvenance;
import com.github.klboke.kkrepo.scanner.ScannerDocumentMapper.EngineVersion;
import com.github.klboke.kkrepo.scanner.ScannerDocumentMapper.PlatformSbom;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness;
import com.github.klboke.kkrepo.security.scan.ScannerArtifactType;
import com.github.klboke.kkrepo.security.scan.ScannerContract;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Capabilities;
import com.github.klboke.kkrepo.security.scan.ScannerContract.CatalogResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.MatchResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.OciScanRequest;
import com.github.klboke.kkrepo.security.scan.ScannerContract.OciScanResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Readiness;
import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Stateless Syft catalog and Grype match adapter. Durable workflow ownership remains in kkRepo. */
@Service
public class ScannerEngineService {
  static final int MAX_OCI_PLATFORMS = 16;
  private static final Pattern DIGEST = Pattern.compile("^sha256:[0-9a-f]{64}$");
  private static final Pattern PLATFORM =
      Pattern.compile("^[a-z0-9][a-z0-9._-]*/[a-z0-9][a-z0-9._-]*(?:/[a-z0-9][a-z0-9._-]*)?$");
  private static final Pattern REPOSITORY =
      Pattern.compile("^[a-z0-9][a-z0-9._/-]{0,511}$");

  private final ScannerAdapterProperties properties;
  private final BoundedProcessRunner processes;
  private final ScannerInput scannerInput;
  private final ArchiveGuard archiveGuard;
  private final CondaPackageCataloger condaPackages;
  private final OciRegistryStager ociRegistryStager;
  private final ScannerDocumentMapper documents;
  private final ScannerDatabaseCoordinator database;

  private volatile CachedReadiness cachedReadiness;

  public ScannerEngineService(
      ScannerAdapterProperties properties,
      BoundedProcessRunner processes,
      ScannerInput scannerInput,
      ArchiveGuard archiveGuard,
      CondaPackageCataloger condaPackages,
      OciRegistryStager ociRegistryStager,
      ScannerDocumentMapper documents,
      ScannerDatabaseCoordinator database) {
    this.properties = properties;
    this.processes = processes;
    this.scannerInput = scannerInput;
    this.archiveGuard = archiveGuard;
    this.condaPackages = condaPackages;
    this.ociRegistryStager = ociRegistryStager;
    this.documents = documents;
    this.database = database;
  }

  public Capabilities capabilities() {
    String digest = ScannerDocumentMapper.sha256(String.join("\0",
        ScannerContract.API_VERSION,
        "syft-grype-v1",
        Long.toString(properties.getMaxInputBytes()),
        Long.toString(properties.getMaxOutputBytes()),
        "catalog", "match", "oci-scan", "cancel", "conda-meta-v1"));
    return new Capabilities(
        ScannerContract.API_VERSION,
        "syft-grype-v1",
        "1",
        List.of("CATALOG", "MATCH", "OCI_SCAN", "CANCEL"),
        List.of("ARCHIVE", "PACKAGE", "MANIFEST", "RAW_FILE", "OCI_IMAGE"),
        properties.getMaxInputBytes(),
        properties.getMaxOutputBytes(),
        digest);
  }

  public Readiness readiness() {
    return readiness(null);
  }

  private Readiness readiness(ScanDeadline deadline) {
    check(deadline);
    Instant now = Instant.now();
    long databaseGeneration = database.generation();
    CachedReadiness cached = cachedReadiness;
    if (cached != null
        && cached.databaseGeneration() == databaseGeneration
        && cached.expiresAt().isAfter(now)) {
      check(deadline);
      return cached.value();
    }
    synchronized (this) {
      check(deadline);
      cached = cachedReadiness;
      if (cached != null
          && cached.databaseGeneration() == databaseGeneration
          && cached.expiresAt().isAfter(now)) {
        check(deadline);
        return cached.value();
      }
      Readiness value;
      try {
        value = database.withRead(() -> inspectReadiness(now, deadline));
      } catch (ScannerRequestException e) {
        check(deadline);
        value = degradedReadiness(now, e.code());
      }
      cachedReadiness = new CachedReadiness(
          value, now.plus(properties.getReadinessCache()), databaseGeneration);
      check(deadline);
      return value;
    }
  }

  void invalidateReadiness() {
    cachedReadiness = null;
  }

  public CatalogResponse catalog(
      InputStream input,
      String expectedSha256,
      long expectedSize,
      ScannerArtifactType artifactType,
      ResourceLimits limits) {
    ResourceLimits effectiveLimits = effective(limits);
    ScanDeadline deadline = new ScanDeadline(effectiveLimits.timeoutSeconds());
    Path workspace = workspace("catalog-");
    try {
      deadline.check();
      ScannerArtifactType safeType =
          artifactType == null ? ScannerArtifactType.UNKNOWN : artifactType;
      Path artifact = workspace.resolve(safeType.safeFilename());
      ScannerInput.Verified verified =
          scannerInput.copy(
              input, artifact, expectedSha256, expectedSize, effectiveLimits, deadline);
      boolean conda = safeType == ScannerArtifactType.CONDA;
      ArchiveGuard.Inspection inspection = conda
          ? archiveGuard.inspectConda(artifact, effectiveLimits, workspace, deadline)
          : archiveGuard.inspect(artifact, effectiveLimits, workspace, deadline);
      CondaPackageCataloger.Prepared condaPackage = conda
          ? condaPackages.prepare(
              artifact, safeType, effectiveLimits, workspace, deadline, inspection.condaIndex())
          : null;
      deadline.check();
      Readiness ready = requireReady(deadline);
      deadline.check();
      Path sbom = workspace.resolve("sbom.cdx.json");
      processes.run(
          List.of(
              properties.getSyftExecutable(),
              "scan",
              conda ? "dir:" + condaPackage.scanRoot() : artifact.toString(),
              "--output",
              "cyclonedx-json"),
          workspace,
          sbom,
          deadline.remaining(),
          Map.of("SYFT_LOG_QUIET", "true"));
      deadline.check();
      byte[] cyclonedx = BoundedProcessRunner.readBounded(sbom, properties.getMaxOutputBytes());
      deadline.check();
      Map<String, Object> summary = new LinkedHashMap<>();
      summary.put("inputBytes", verified.size());
      summary.put("archiveEntries", inspection.entries());
      summary.put("expandedBytes", inspection.expandedBytes());
      summary.put("nestedArchives", inspection.nestedArchives());
      if (condaPackage != null) {
        summary.put("condaName", condaPackage.name());
        summary.put("condaVersion", condaPackage.version());
        summary.put("condaBuild", condaPackage.build());
        summary.put("condaSubdir", condaPackage.subdir());
      }
      CatalogResponse response = documents.catalog(
          cyclonedx,
          verified.sha256(),
          string(ready.details().get("catalogEngineVersion"), ready.engineVersion()),
          capabilities().capabilityDigest(),
          summary);
      if (condaPackage != null) {
        requireCondaComponent(response, condaPackage);
      }
      deadline.check();
      return response;
    } catch (IOException e) {
      throw new ScannerRequestException(
          "CATALOG_IO", "Unable to read scanner output", 503, true, e);
    } finally {
      TempDirectories.deleteRecursively(workspace);
    }
  }

  private static void requireCondaComponent(
      CatalogResponse response, CondaPackageCataloger.Prepared expected) {
    boolean found = response.components().stream().anyMatch(component ->
        expected.name().equals(component.name())
            && expected.version().equals(component.version())
            && "conda".equalsIgnoreCase(String.valueOf(
                component.properties().get("syft:package:type"))));
    if (!found) {
      throw new ScannerRequestException(
          "CONDA_CATALOG_EMPTY",
          "Syft did not identify the expected Conda package metadata",
          422,
          false);
    }
  }

  public MatchResponse match(
      InputStream input,
      String expectedSha256,
      ResourceLimits limits) {
    ResourceLimits effectiveLimits = effective(limits);
    ScanDeadline deadline = new ScanDeadline(effectiveLimits.timeoutSeconds());
    Path workspace = workspace("match-");
    try {
      deadline.check();
      Path sbom = workspace.resolve("sbom.cdx.json");
      ScannerInput.Verified verified = scannerInput.copy(
          input, sbom, expectedSha256, null, effectiveLimits, deadline);
      if (verified.size() > properties.getMaxOutputBytes()) {
        throw new ScannerRequestException(
            "SBOM_TOO_LARGE", "CycloneDX input exceeded the output limit", 413, false);
      }
      deadline.check();
      return matchFile(sbom, workspace, deadline);
    } finally {
      TempDirectories.deleteRecursively(workspace);
    }
  }

  public OciScanResponse scanOci(OciScanRequest request) {
    validateOci(request);
    ResourceLimits limits = effective(request.limits());
    ScanDeadline deadline = new ScanDeadline(limits.timeoutSeconds());
    Path workspace = workspace("oci-");
    try {
      Readiness ready = requireReady(deadline);
      deadline.check();
      OciRegistryStager.StagedImage staged =
          ociRegistryStager.stage(request, limits, workspace, deadline);
      Map<String, String> environment = new LinkedHashMap<>();
      environment.put("SYFT_LOG_QUIET", "true");
      environment.put(
          "SYFT_SOURCE_IMAGE_MAX_LAYER_SIZE",
          limits.maxSingleFileBytes() + "B");

      List<PlatformSbom> platformSboms = new ArrayList<>();
      List<String> scanned = new ArrayList<>();
      List<String> missing = new ArrayList<>(staged.missingPlatforms());
      long aggregateDocumentBytes = 0;
      for (int index = 0; index < staged.availablePlatforms().size(); index++) {
        String platform = staged.availablePlatforms().get(index);
        Path output = workspace.resolve("platform-" + index + ".cdx.json");
        try {
          processes.run(
              List.of(
                  properties.getSyftExecutable(),
                  "scan",
                  "oci-dir:" + staged.layout(),
                  "--platform",
                  platform,
                  "--output",
                  "cyclonedx-json"),
              workspace,
              output,
              deadline.remaining(),
              environment);
          byte[] value =
              BoundedProcessRunner.readBounded(output, properties.getMaxOutputBytes());
          deadline.check();
          if (value.length > properties.getMaxOutputBytes() - aggregateDocumentBytes) {
            throw new ScannerRequestException(
                "SCANNER_OUTPUT_TOO_LARGE",
                "Combined OCI platform inventories exceeded the configured output limit",
                413,
                false);
          }
          aggregateDocumentBytes += value.length;
          platformSboms.add(new PlatformSbom(platform, value));
          scanned.add(platform);
        } catch (ScannerRequestException e) {
          if ("SCANNER_PLATFORM_NOT_FOUND".equals(e.code())) {
            if (!missing.contains(platform)) missing.add(platform);
          } else {
            throw e;
          }
        }
      }
      if (platformSboms.isEmpty()) {
        throw new ScannerRequestException(
            "OCI_SCAN_FAILED", "No requested OCI platform could be scanned", 422, false);
      }
      byte[] merged =
          documents.mergeCycloneDx(platformSboms, properties.getMaxOutputBytes());
      deadline.check();
      if (merged.length > properties.getMaxOutputBytes()) {
        throw new ScannerRequestException(
            "SCANNER_OUTPUT_TOO_LARGE",
            "Merged OCI inventory exceeded the configured output limit",
            413,
            false);
      }
      deadline.check();
      Path mergedPath = workspace.resolve("merged.cdx.json");
      Files.write(mergedPath, merged);
      deadline.check();
      CatalogResponse catalog = documents.catalog(
          merged,
          request.manifestDigest().substring("sha256:".length()),
          string(ready.details().get("catalogEngineVersion"), ready.engineVersion()),
          capabilities().capabilityDigest(),
          Map.of(
              "scannedPlatforms", scanned,
              "missingPlatforms", missing,
              "inputBytes", staged.transferredBytes(),
              "archiveEntries", staged.archiveEntries(),
              "expandedBytes", staged.expandedBytes(),
              "nestedArchives", staged.nestedArchives()));
      deadline.check();
      if (!missing.isEmpty()) {
        catalog = new CatalogResponse(
            catalog.adapterName(),
            catalog.adapterVersion(),
            catalog.engineName(),
            catalog.engineVersion(),
            catalog.capabilityDigest(),
            catalog.actualInputSha256(),
            ScanCompleteness.PARTIAL,
            catalog.specName(),
            catalog.specVersion(),
            catalog.componentCount(),
            catalog.dependencyCount(),
            catalog.cyclonedxJson(),
            catalog.components(),
            catalog.summary());
      }
      MatchResponse match = matchFile(mergedPath, workspace, deadline);
      if (!missing.isEmpty()) {
        match = new MatchResponse(
            match.adapterName(),
            match.adapterVersion(),
            match.engineName(),
            match.engineVersion(),
            match.vulnerabilityDatabaseRevision(),
            match.vulnerabilityDatabaseUpdatedAt(),
            match.capabilityDigest(),
            ScanCompleteness.PARTIAL,
            match.reportJson(),
            match.findings(),
            match.summary());
      }
      return new OciScanResponse(catalog, match, scanned, missing);
    } catch (IOException e) {
      throw new ScannerRequestException(
          "OCI_SCAN_IO", "Unable to aggregate OCI scan output", 503, true, e);
    } finally {
      TempDirectories.deleteRecursively(workspace);
    }
  }

  private MatchResponse matchFile(
      Path sbom, Path workspace, ScanDeadline deadline) {
    deadline.check();
    MatchResponse response =
        database.withRead(() -> matchFileLocked(sbom, workspace, deadline));
    deadline.check();
    return response;
  }

  private MatchResponse matchFileLocked(
      Path sbom, Path workspace, ScanDeadline deadline) {
    try {
      deadline.check();
      Readiness ready = inspectReadiness(Instant.now(), deadline);
      requireReady(ready);
      deadline.check();
      Path report = workspace.resolve("grype-report.json");
      processes.run(
          List.of(
              properties.getGrypeExecutable(),
              "sbom:" + sbom,
              "--output",
              "json"),
          workspace,
          report,
          deadline.remaining(),
          Map.of());
      deadline.check();
      byte[] reportJson =
          BoundedProcessRunner.readBounded(report, properties.getMaxOutputBytes());
      deadline.check();
      DatabaseProvenance database = new DatabaseProvenance(
          ready.vulnerabilityDatabaseRevision(),
          ready.vulnerabilityDatabaseUpdatedAt());
      MatchResponse response = documents.match(
          reportJson,
          string(ready.details().get("matcherEngineVersion"), ready.engineVersion()),
          database,
          capabilities().capabilityDigest());
      deadline.check();
      return response;
    } catch (IOException e) {
      throw new ScannerRequestException(
          "MATCH_IO", "Unable to read matcher output", 503, true, e);
    }
  }

  private Readiness inspectReadiness(Instant observedAt, ScanDeadline deadline) {
    try {
      EngineVersion syft = documents.engineVersion(
          versionOutput(
              properties.getSyftExecutable(),
              List.of("version", "--output", "json"),
              deadline),
          "syft");
      EngineVersion grype = documents.engineVersion(
          versionOutput(
              properties.getGrypeExecutable(),
              List.of("version", "--output", "json"),
              deadline),
          "grype");
      DatabaseProvenance database = documents.database(
          versionOutput(
              properties.getGrypeExecutable(),
              List.of("db", "status", "--output", "json"),
              deadline));
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("catalogEngine", syft.name());
      details.put("catalogEngineVersion", syft.version());
      details.put("matcherEngine", grype.name());
      details.put("matcherEngineVersion", grype.version());
      details.put("capabilityDigest", capabilities().capabilityDigest());
      return new Readiness(
          true,
          "READY",
          "grype",
          grype.version(),
          database.revision(),
          database.updatedAt(),
          observedAt,
          details);
    } catch (ScannerRequestException e) {
      check(deadline);
      return degradedReadiness(observedAt, e.code());
    }
  }

  private byte[] versionOutput(
      String executable, List<String> arguments, ScanDeadline deadline) {
    return deadline == null
        ? processes.versionOutput(executable, arguments)
        : processes.versionOutput(executable, arguments, deadline.remaining());
  }

  private Readiness requireReady(ScanDeadline deadline) {
    Readiness readiness = readiness(deadline);
    requireReady(readiness);
    return readiness;
  }

  private static void requireReady(Readiness readiness) {
    if (!readiness.ready()) {
      throw new ScannerRequestException(
          "SCANNER_NOT_READY", "Scanner engine or vulnerability database is unavailable", 503, true);
    }
    if (readiness.vulnerabilityDatabaseRevision() == null
        || readiness.vulnerabilityDatabaseRevision().isBlank()
        || readiness.vulnerabilityDatabaseUpdatedAt() == null) {
      throw new ScannerRequestException(
          "SCANNER_DATABASE_PROVENANCE_MISSING",
          "Scanner vulnerability database provenance is unavailable",
          503,
          true);
    }
  }

  private static Readiness degradedReadiness(Instant observedAt, String reasonCode) {
    return new Readiness(
        false,
        "DEGRADED",
        "syft-grype",
        "unavailable",
        "unavailable",
        Instant.EPOCH,
        observedAt,
        Map.of("reasonCode", reasonCode));
  }

  private ResourceLimits effective(ResourceLimits requested) {
    if (requested == null) {
      throw new ScannerRequestException(
          "RESOURCE_LIMITS_REQUIRED", "Scanner resource limits are required", 400, false);
    }
    return new ResourceLimits(
        Math.min(requested.maxInputBytes(), properties.getMaxInputBytes()),
        Math.min(requested.maxArchiveEntries(), 1_000_000),
        Math.min(requested.maxUncompressedBytes(), properties.getMaxInputBytes() * 4),
        Math.min(requested.maxSingleFileBytes(), properties.getMaxInputBytes()),
        Math.min(requested.maxNestedDepth(), 10),
        Math.min(
            requested.timeoutSeconds(),
            ScannerContract.MAX_REQUEST_TIMEOUT_SECONDS));
  }

  private void validateOci(OciScanRequest request) {
    if (request == null
        || !ScannerContract.API_VERSION.equals(request.apiVersion())
        || request.registryUrl() == null
        || request.repository() == null
        || request.manifestDigest() == null
        || request.scopedBearerToken() == null
        || request.scopedBearerToken().isBlank()) {
      throw new ScannerRequestException(
          "OCI_REQUEST_INVALID", "OCI scan request is incomplete", 400, false);
    }
    URI registry;
    try {
      registry = URI.create(request.registryUrl());
    } catch (RuntimeException e) {
      throw new ScannerRequestException(
          "OCI_REGISTRY_INVALID", "OCI registry URL is invalid", 400, false);
    }
    String scheme = registry.getScheme() == null
        ? "" : registry.getScheme().toLowerCase(Locale.ROOT);
    if (!("http".equals(scheme) || "https".equals(scheme))
        || registry.getHost() == null
        || registry.getUserInfo() != null
        || registry.getQuery() != null
        || registry.getFragment() != null
        || !REPOSITORY.matcher(request.repository()).matches()
        || !DIGEST.matcher(request.manifestDigest()).matches()
        || request.requiredPlatforms().size() > MAX_OCI_PLATFORMS
        || request.requiredPlatforms().stream().anyMatch(
            platform -> platform == null || !PLATFORM.matcher(platform).matches())) {
      throw new ScannerRequestException(
          "OCI_REQUEST_INVALID", "OCI scan target is invalid", 400, false);
    }
  }

  private Path workspace(String prefix) {
    try {
      Files.createDirectories(properties.getWorkDirectory());
      return Files.createTempDirectory(properties.getWorkDirectory(), prefix);
    } catch (IOException e) {
      throw new ScannerRequestException(
          "WORKSPACE_IO", "Unable to create isolated scanner workspace", 503, true, e);
    }
  }

  private static String string(Object value, String fallback) {
    return value == null || value.toString().isBlank() ? fallback : value.toString();
  }

  private static void check(ScanDeadline deadline) {
    if (deadline != null) deadline.check();
  }

  private record CachedReadiness(
      Readiness value, Instant expiresAt, long databaseGeneration) {}

}
