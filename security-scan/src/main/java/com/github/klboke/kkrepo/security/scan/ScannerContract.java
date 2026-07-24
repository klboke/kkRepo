package com.github.klboke.kkrepo.security.scan;

import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Engine-neutral, versioned contract implemented by an HTTP scanner client. */
public final class ScannerContract {
  public static final String API_VERSION = "v1";

  private ScannerContract() {}

  @FunctionalInterface
  public interface InputStreamSource {
    InputStream open() throws IOException;
  }

  public record Capabilities(
      String apiVersion,
      String adapterName,
      String adapterVersion,
      List<String> operations,
      List<String> targetClassifications,
      long maxInputBytes,
      long maxOutputBytes,
      String capabilityDigest) {
    public Capabilities {
      operations = operations == null ? List.of() : List.copyOf(operations);
      targetClassifications =
          targetClassifications == null ? List.of() : List.copyOf(targetClassifications);
    }
  }

  public record Readiness(
      boolean ready,
      String status,
      String engineName,
      String engineVersion,
      String vulnerabilityDatabaseRevision,
      Instant vulnerabilityDatabaseUpdatedAt,
      Instant observedAt,
      Map<String, Object> details) {
    public Readiness {
      details = details == null ? Map.of() : Map.copyOf(details);
    }
  }

  public record ResourceLimits(
      long maxInputBytes,
      int maxArchiveEntries,
      long maxUncompressedBytes,
      long maxSingleFileBytes,
      int maxNestedDepth,
      int timeoutSeconds) {
    public ResourceLimits {
      if (maxInputBytes <= 0 || maxArchiveEntries <= 0 || maxUncompressedBytes <= 0
          || maxSingleFileBytes <= 0 || maxNestedDepth < 0 || timeoutSeconds <= 0) {
        throw new IllegalArgumentException("Scanner resource limits must be positive");
      }
    }
  }

  public record CatalogRequest(
      String apiVersion,
      String runId,
      String idempotencyKey,
      ScanSubject subject,
      String profileConfigurationDigest,
      ResourceLimits limits) {}

  public record CatalogResponse(
      String adapterName,
      String adapterVersion,
      String engineName,
      String engineVersion,
      String capabilityDigest,
      String actualInputSha256,
      ScanCompleteness completeness,
      String specName,
      String specVersion,
      int componentCount,
      int dependencyCount,
      byte[] cyclonedxJson,
      List<Component> components,
      Map<String, Object> summary) {
    public CatalogResponse {
      cyclonedxJson = cyclonedxJson == null ? new byte[0] : cyclonedxJson.clone();
      components = components == null ? List.of() : List.copyOf(components);
      summary = summary == null ? Map.of() : Map.copyOf(summary);
    }
  }

  public record MatchRequest(
      String apiVersion,
      String runId,
      String idempotencyKey,
      String sbomSha256,
      String profileConfigurationDigest,
      ResourceLimits limits) {}

  public record MatchResponse(
      String adapterName,
      String adapterVersion,
      String engineName,
      String engineVersion,
      String vulnerabilityDatabaseRevision,
      Instant vulnerabilityDatabaseUpdatedAt,
      String capabilityDigest,
      ScanCompleteness completeness,
      byte[] reportJson,
      List<Finding> findings,
      Map<String, Object> summary) {
    public MatchResponse {
      reportJson = reportJson == null ? new byte[0] : reportJson.clone();
      findings = findings == null ? List.of() : List.copyOf(findings);
      summary = summary == null ? Map.of() : Map.copyOf(summary);
    }
  }

  public record OciScanRequest(
      String apiVersion,
      String runId,
      String idempotencyKey,
      String registryUrl,
      String repository,
      String manifestDigest,
      List<String> requiredPlatforms,
      String scopedBearerToken,
      String profileConfigurationDigest,
      ResourceLimits limits) {
    public OciScanRequest {
      requiredPlatforms = requiredPlatforms == null ? List.of() : List.copyOf(requiredPlatforms);
    }
  }

  public record OciScanResponse(
      CatalogResponse catalog,
      MatchResponse match,
      List<String> scannedPlatforms,
      List<String> missingPlatforms) {
    public OciScanResponse {
      scannedPlatforms = scannedPlatforms == null ? List.of() : List.copyOf(scannedPlatforms);
      missingPlatforms = missingPlatforms == null ? List.of() : List.copyOf(missingPlatforms);
    }
  }

  public record Component(
      String componentRef,
      String packageUrl,
      String type,
      String namespace,
      String name,
      String version,
      String directness,
      List<String> locations,
      List<String> licenses,
      Map<String, Object> properties) {
    public Component {
      locations = locations == null ? List.of() : List.copyOf(locations);
      licenses = licenses == null ? List.of() : List.copyOf(licenses);
      properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
  }

  public record Finding(
      String findingKey,
      String advisoryId,
      List<String> aliases,
      String dataSource,
      String packageUrl,
      String packageName,
      String installedVersion,
      List<String> fixedVersions,
      Severity severity,
      String severitySource,
      String cvssVector,
      Double cvssScore,
      String title,
      String description,
      String primaryUrl,
      List<String> locations,
      String sourceStatus) {
    public Finding {
      aliases = aliases == null ? List.of() : List.copyOf(aliases);
      fixedVersions = fixedVersions == null ? List.of() : List.copyOf(fixedVersions);
      locations = locations == null ? List.of() : List.copyOf(locations);
      severity = severity == null ? Severity.UNKNOWN : severity;
    }

    public boolean fixable() {
      return !fixedVersions.isEmpty();
    }
  }

  public interface Adapter {
    Capabilities capabilities();

    Readiness readiness();

    CatalogResponse catalog(CatalogRequest request, InputStreamSource input) throws IOException;

    MatchResponse match(MatchRequest request, InputStreamSource sbom) throws IOException;

    OciScanResponse scanOci(OciScanRequest request) throws IOException;
  }
}
