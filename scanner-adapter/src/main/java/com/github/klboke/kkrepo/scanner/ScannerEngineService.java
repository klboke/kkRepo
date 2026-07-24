package com.github.klboke.kkrepo.scanner;

import com.github.klboke.kkrepo.scanner.ScannerDocumentMapper.DatabaseProvenance;
import com.github.klboke.kkrepo.scanner.ScannerDocumentMapper.EngineVersion;
import com.github.klboke.kkrepo.scanner.ScannerDocumentMapper.PlatformSbom;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness;
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
import java.time.Duration;
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
  private static final Pattern DIGEST = Pattern.compile("^sha256:[0-9a-f]{64}$");
  private static final Pattern PLATFORM =
      Pattern.compile("^[a-z0-9][a-z0-9._-]*/[a-z0-9][a-z0-9._-]*(?:/[a-z0-9][a-z0-9._-]*)?$");
  private static final Pattern REPOSITORY =
      Pattern.compile("^[a-z0-9][a-z0-9._/-]{0,511}$");

  private final ScannerAdapterProperties properties;
  private final BoundedProcessRunner processes;
  private final ScannerInput scannerInput;
  private final ArchiveGuard archiveGuard;
  private final ScannerDocumentMapper documents;

  private volatile CachedReadiness cachedReadiness;

  public ScannerEngineService(
      ScannerAdapterProperties properties,
      BoundedProcessRunner processes,
      ScannerInput scannerInput,
      ArchiveGuard archiveGuard,
      ScannerDocumentMapper documents) {
    this.properties = properties;
    this.processes = processes;
    this.scannerInput = scannerInput;
    this.archiveGuard = archiveGuard;
    this.documents = documents;
  }

  public Capabilities capabilities() {
    String digest = ScannerDocumentMapper.sha256(String.join("\0",
        ScannerContract.API_VERSION,
        "syft-grype-v1",
        Long.toString(properties.getMaxInputBytes()),
        Long.toString(properties.getMaxOutputBytes()),
        "catalog", "match", "oci-scan"));
    return new Capabilities(
        ScannerContract.API_VERSION,
        "syft-grype-v1",
        "1",
        List.of("CATALOG", "MATCH", "OCI_SCAN"),
        List.of("ARCHIVE", "PACKAGE", "MANIFEST", "RAW_FILE", "OCI_IMAGE"),
        properties.getMaxInputBytes(),
        properties.getMaxOutputBytes(),
        digest);
  }

  public Readiness readiness() {
    Instant now = Instant.now();
    CachedReadiness cached = cachedReadiness;
    if (cached != null && cached.expiresAt().isAfter(now)) return cached.value();
    synchronized (this) {
      cached = cachedReadiness;
      if (cached != null && cached.expiresAt().isAfter(now)) return cached.value();
      Readiness value = inspectReadiness(now);
      cachedReadiness = new CachedReadiness(
          value, now.plus(properties.getReadinessCache()));
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
      ResourceLimits limits) {
    Path workspace = workspace("catalog-");
    try {
      Path artifact = workspace.resolve("artifact");
      ScannerInput.Verified verified =
          scannerInput.copy(input, artifact, expectedSha256, expectedSize, effective(limits));
      ArchiveGuard.Inspection inspection = archiveGuard.inspect(
          artifact, effective(limits), workspace);
      Readiness ready = requireReady();
      Path sbom = workspace.resolve("sbom.cdx.json");
      processes.run(
          List.of(
              properties.getSyftExecutable(),
              "scan",
              artifact.toString(),
              "--output",
              "cyclonedx-json=" + sbom),
          workspace,
          workspace.resolve("syft.stdout"),
          Duration.ofSeconds(effective(limits).timeoutSeconds()),
          Map.of("SYFT_LOG_QUIET", "true"));
      byte[] cyclonedx = BoundedProcessRunner.readBounded(sbom, properties.getMaxOutputBytes());
      return documents.catalog(
          cyclonedx,
          verified.sha256(),
          string(ready.details().get("catalogEngineVersion"), ready.engineVersion()),
          capabilities().capabilityDigest(),
          Map.of(
              "inputBytes", verified.size(),
              "archiveEntries", inspection.entries(),
              "expandedBytes", inspection.expandedBytes(),
              "nestedArchives", inspection.nestedArchives()));
    } catch (IOException e) {
      throw new ScannerRequestException(
          "CATALOG_IO", "Unable to read scanner output", 503, true, e);
    } finally {
      TempDirectories.deleteRecursively(workspace);
    }
  }

  public MatchResponse match(
      InputStream input,
      String expectedSha256,
      ResourceLimits limits) {
    Path workspace = workspace("match-");
    try {
      Path sbom = workspace.resolve("sbom.cdx.json");
      ScannerInput.Verified verified = scannerInput.copy(
          input, sbom, expectedSha256, null, effective(limits));
      if (verified.size() > properties.getMaxOutputBytes()) {
        throw new ScannerRequestException(
            "SBOM_TOO_LARGE", "CycloneDX input exceeded the output limit", 413, false);
      }
      return matchFile(sbom, effective(limits), workspace);
    } finally {
      TempDirectories.deleteRecursively(workspace);
    }
  }

  public OciScanResponse scanOci(OciScanRequest request) {
    validateOci(request);
    Path workspace = workspace("oci-");
    try {
      Readiness ready = requireReady();
      URI registry = URI.create(request.registryUrl());
      String authority = registry.getRawAuthority();
      String prefix = registry.getPath() == null ? "" : registry.getPath();
      if (!prefix.isBlank() && !prefix.endsWith("/")) prefix += "/";
      String image = authority + "/" + prefix.replaceFirst("^/", "")
          + request.repository() + "@" + request.manifestDigest();
      Map<String, String> environment = new LinkedHashMap<>();
      environment.put("SYFT_LOG_QUIET", "true");
      environment.put("SYFT_REGISTRY_AUTH_AUTHORITY", authority);
      environment.put("SYFT_REGISTRY_AUTH_TOKEN", request.scopedBearerToken());
      if ("http".equalsIgnoreCase(registry.getScheme())) {
        environment.put("SYFT_REGISTRY_INSECURE_USE_HTTP", "true");
      }

      List<String> required = request.requiredPlatforms().isEmpty()
          ? List.of("linux/amd64") : request.requiredPlatforms();
      List<PlatformSbom> platformSboms = new ArrayList<>();
      List<String> scanned = new ArrayList<>();
      List<String> missing = new ArrayList<>();
      for (int index = 0; index < required.size(); index++) {
        String platform = required.get(index);
        Path output = workspace.resolve("platform-" + index + ".cdx.json");
        try {
          processes.run(
              List.of(
                  properties.getSyftExecutable(),
                  "scan",
                  "registry:" + image,
                  "--platform",
                  platform,
                  "--output",
                  "cyclonedx-json=" + output),
              workspace,
              workspace.resolve("syft-" + index + ".stdout"),
              Duration.ofSeconds(effective(request.limits()).timeoutSeconds()),
              environment);
          byte[] value =
              BoundedProcessRunner.readBounded(output, properties.getMaxOutputBytes());
          platformSboms.add(new PlatformSbom(platform, value));
          scanned.add(platform);
        } catch (ScannerRequestException e) {
          if ("SCANNER_PROCESS_FAILED".equals(e.code())) {
            missing.add(platform);
          } else {
            throw e;
          }
        }
      }
      if (platformSboms.isEmpty()) {
        throw new ScannerRequestException(
            "OCI_SCAN_FAILED", "No requested OCI platform could be scanned", 422, false);
      }
      byte[] merged = documents.mergeCycloneDx(platformSboms);
      Path mergedPath = workspace.resolve("merged.cdx.json");
      Files.write(mergedPath, merged);
      CatalogResponse catalog = documents.catalog(
          merged,
          request.manifestDigest().substring("sha256:".length()),
          string(ready.details().get("catalogEngineVersion"), ready.engineVersion()),
          capabilities().capabilityDigest(),
          Map.of("scannedPlatforms", scanned, "missingPlatforms", missing));
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
      MatchResponse match = matchFile(mergedPath, effective(request.limits()), workspace);
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

  private MatchResponse matchFile(Path sbom, ResourceLimits limits, Path workspace) {
    try {
      Readiness ready = requireReady();
      Path report = workspace.resolve("grype-report.json");
      processes.run(
          List.of(
              properties.getGrypeExecutable(),
              "sbom:" + sbom,
              "--output",
              "json"),
          workspace,
          report,
          Duration.ofSeconds(limits.timeoutSeconds()),
          Map.of());
      byte[] reportJson =
          BoundedProcessRunner.readBounded(report, properties.getMaxOutputBytes());
      DatabaseProvenance database = new DatabaseProvenance(
          ready.vulnerabilityDatabaseRevision(),
          ready.vulnerabilityDatabaseUpdatedAt());
      return documents.match(
          reportJson,
          string(ready.details().get("matcherEngineVersion"), ready.engineVersion()),
          database,
          capabilities().capabilityDigest());
    } catch (IOException e) {
      throw new ScannerRequestException(
          "MATCH_IO", "Unable to read matcher output", 503, true, e);
    }
  }

  private Readiness inspectReadiness(Instant observedAt) {
    try {
      EngineVersion syft = documents.engineVersion(
          processes.versionOutput(
              properties.getSyftExecutable(), List.of("version", "--output", "json")),
          "syft");
      EngineVersion grype = documents.engineVersion(
          processes.versionOutput(
              properties.getGrypeExecutable(), List.of("version", "--output", "json")),
          "grype");
      DatabaseProvenance database = documents.database(
          processes.versionOutput(
              properties.getGrypeExecutable(), List.of("db", "status", "--output", "json")));
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
      return new Readiness(
          false,
          "DEGRADED",
          "syft-grype",
          "unavailable",
          "unavailable",
          Instant.EPOCH,
          observedAt,
          Map.of("reasonCode", e.code()));
    }
  }

  private Readiness requireReady() {
    Readiness readiness = readiness();
    if (!readiness.ready()) {
      throw new ScannerRequestException(
          "SCANNER_NOT_READY", "Scanner engine or vulnerability database is unavailable", 503, true);
    }
    return readiness;
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
        Math.min(requested.timeoutSeconds(), 3600));
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

  private record CachedReadiness(Readiness value, Instant expiresAt) {}
}
