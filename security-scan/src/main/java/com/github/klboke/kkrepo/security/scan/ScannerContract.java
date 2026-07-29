package com.github.klboke.kkrepo.security.scan;

import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/** Engine-neutral, versioned contract implemented by an HTTP scanner client. */
public final class ScannerContract {
  public static final String API_VERSION = "v1";
  /** Maximum component projection carried beside the immutable CycloneDX document. */
  public static final int MAX_COMPONENT_PROJECTION_COUNT = 4_096;
  /** Maximum finding projection carried beside the immutable vulnerability report. */
  public static final int MAX_FINDING_PROJECTION_COUNT = 2_048;
  /** Maximum end-to-end execution time accepted by the scanner adapter. */
  public static final int MAX_REQUEST_TIMEOUT_SECONDS = 3_600;

  private ScannerContract() {}

  /** Canonical precision shared by the HTTP contract and TIMESTAMP(3) persistence columns. */
  public static Instant canonicalDatabaseTimestamp(Instant value) {
    return value == null ? null : value.truncatedTo(ChronoUnit.MILLIS);
  }

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
      vulnerabilityDatabaseUpdatedAt =
          canonicalDatabaseTimestamp(vulnerabilityDatabaseUpdatedAt);
      details = details == null ? Map.of() : Map.copyOf(details);
    }
  }

  /**
   * Capabilities and readiness observed from one scanner replica.
   *
   * <p>Keeping the two documents in one value prevents a rolling deployment from producing a
   * synthetic snapshot assembled from different adapter replicas.
   */
  public record Observation(Capabilities capabilities, Readiness readiness) {
    public Observation {
      java.util.Objects.requireNonNull(capabilities, "capabilities");
      java.util.Objects.requireNonNull(readiness, "readiness");
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
      ResourceLimits limits,
      SnapshotExpectation expectedSnapshot) {
    public MatchRequest(
        String apiVersion,
        String runId,
        String idempotencyKey,
        String sbomSha256,
        String profileConfigurationDigest,
        ResourceLimits limits) {
      this(
          apiVersion,
          runId,
          idempotencyKey,
          sbomSha256,
          profileConfigurationDigest,
          limits,
          null);
    }
  }

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
      vulnerabilityDatabaseUpdatedAt =
          canonicalDatabaseTimestamp(vulnerabilityDatabaseUpdatedAt);
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
      ResourceLimits limits,
      SnapshotExpectation expectedSnapshot) {
    public OciScanRequest(
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
      this(
          apiVersion,
          runId,
          idempotencyKey,
          registryUrl,
          repository,
          manifestDigest,
          requiredPlatforms,
          scopedBearerToken,
          profileConfigurationDigest,
          limits,
          null);
    }

    public OciScanRequest {
      requiredPlatforms = requiredPlatforms == null ? List.of() : List.copyOf(requiredPlatforms);
    }
  }

  /**
   * Deployment-independent identity of the vulnerability matcher state required by a task.
   *
   * <p>The database snapshot ID belongs to one kkRepo installation and therefore is not sent to
   * scanner replicas. These fields are sufficient to reject a response produced by a different
   * rolling-update/database build while allowing the HTTP client to fail over to a matching
   * replica. The database update time is part of the build identity because schema revisions can
   * remain unchanged across vulnerability-data updates.
   */
  public record SnapshotExpectation(
      String adapterName,
      String engineName,
      String engineVersion,
      String vulnerabilityDatabaseRevision,
      Instant vulnerabilityDatabaseUpdatedAt,
      String capabilityDigest) {
    public SnapshotExpectation {
      vulnerabilityDatabaseUpdatedAt =
          canonicalDatabaseTimestamp(vulnerabilityDatabaseUpdatedAt);
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

  public record CancellationResponse(String runId, boolean cancelled) {}

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
    Observation observation();

    Capabilities capabilities();

    Readiness readiness();

    CatalogResponse catalog(CatalogRequest request, InputStreamSource input) throws IOException;

    MatchResponse match(MatchRequest request, InputStreamSource sbom) throws IOException;

    OciScanResponse scanOci(OciScanRequest request) throws IOException;

    CancellationResponse cancel(String runId);
  }
}
