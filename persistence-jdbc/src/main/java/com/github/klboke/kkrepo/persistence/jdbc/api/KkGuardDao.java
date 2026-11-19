package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Durable state for the kkGuard application scanning API. */
public interface KkGuardDao {
  record Organization(
      long id, String externalId, String name, String description,
      Instant createdAt, Instant updatedAt) {}

  record Application(
      long id, long organizationId, String externalId, String publicId, String name,
      Long repositoryId, String contactUserName, String dtProjectUuid, String dtProjectName,
      Instant createdAt, Instant updatedAt) {}

  record ApplicationTag(long applicationId, String name, String description) {}

  /** Immutable SBOM location for a scan; the blob is addressed by digest inside one store. */
  record ScanSbom(long blobStoreId, String blobRef, String sha256, long size) {}

  record Scan(
      long id, long applicationId, String scanId, String status, String stage, String sourceId,
      String sbomSha256, String reportSha256, int componentCount, int violationCount,
      String error, String dtProjectUuid, String dtBomToken, int attempts, String leaseOwner,
      Instant leaseExpiresAt, Instant submittedAt, Instant lastPolledAt,
      Instant createdAt, Instant startedAt, Instant completedAt) {}

  record ScanComponent(
      long scanId, String ref, String purl, String name, String version,
      String license, String highestSeverity) {}

  record Finding(
      long scanId, String componentRef, String vulnerabilityId, String severity,
      BigDecimal cvssScore, String source, String description, String url) {}

  record Policy(
      long id, long organizationId, String externalId, String publicId, String name,
      int threatLevel, Map<String, Object> constraints, Instant createdAt, Instant updatedAt) {}

  record Violation(
      long scanId, long policyId, String componentRef, int threatLevel,
      Map<String, Object> constraint) {}

  List<Organization> listOrganizations();

  Optional<Organization> findOrganization(long id);

  Optional<Organization> findOrganizationByExternalId(String externalId);

  long insertOrganization(String externalId, String name, String description, Instant now);

  boolean updateOrganization(long id, String name, String description, Instant now);

  boolean deleteOrganization(long id);

  List<Application> listApplications();

  List<Application> listApplicationsByOrganization(long organizationId);

  Optional<Application> findApplication(long id);

  Optional<Application> findApplicationByExternalId(String externalId);

  Optional<Application> findApplicationByPublicId(String publicId);

  long insertApplication(
      long organizationId, String externalId, String publicId, String name, Long repositoryId,
      String contactUserName, Instant now);

  boolean updateApplication(
      long id, long organizationId, String externalId, String publicId, String name,
      Long repositoryId, String contactUserName, Instant now);

  boolean deleteApplication(long id);

  /** Records the Dependency-Track project bound to an application. */
  boolean updateApplicationDependencyTrackProject(
      long id, String dtProjectUuid, String dtProjectName, Instant now);

  List<ApplicationTag> listApplicationTags(long applicationId);

  /** Replaces every tag of an application; callers hold the application row lock when needed. */
  void replaceApplicationTags(long applicationId, List<ApplicationTag> tags);

  long insertScan(Scan scan);

  Optional<Scan> findScan(long id);

  Optional<Scan> findScanByScanId(String scanId);

  Optional<Scan> lockScanByScanId(String scanId);

  /** Latest completed scan for the same SBOM and source, used to deduplicate submissions. */
  Optional<Scan> findCompletedScanBySbom(long applicationId, String sourceId, String sbomSha256);

  /** Most recent scan of an application source, used by the status endpoint. */
  Optional<Scan> findLatestScanByApplicationAndSource(long applicationId, String sourceId);

  boolean attachScanSbom(long scanId, long blobStoreId, String blobRef, String sha256, long size);

  Optional<ScanSbom> findScanSbom(long scanId);

  /** Scan ids eligible for a worker pass: due submission or an expired/absent lease. */
  List<Long> listClaimableScanIds(Instant now, int limit);

  /** Fenced lease acquisition; returns false when another replica owns the scan. */
  boolean claimScan(long id, String owner, Instant now, Instant leaseExpiresAt);

  /** Releases a lease owned by {@code owner}; a lost lease leaves the row untouched. */
  boolean releaseScan(long id, String owner);

  boolean markScanSubmitted(
      long id, String owner, String dtProjectUuid, String dtBomToken, Instant submittedAt);

  /** Records one poll attempt on a scan still owned by {@code owner}. */
  boolean markScanPolled(long id, String owner, int attempts, Instant polledAt);

  /** Terminal transition fenced by the lease owner. */
  boolean finishScan(
      long id, String owner, String status, String error, int componentCount,
      int violationCount, Instant completedAt);

  /**
   * Replaces the component snapshot of a scan still owned by {@code owner}. Returns {@code false}
   * when the lease was lost, in which case nothing is written.
   */
  boolean replaceScanComponents(long scanId, String owner, List<ScanComponent> components);

  /** Replaces the finding snapshot of a scan still owned by {@code owner}. */
  boolean replaceScanFindings(long scanId, String owner, List<Finding> findings);

  List<ScanComponent> listScanComponents(long scanId);

  List<Finding> listScanFindings(long scanId);

  List<Policy> listPolicies(long organizationId);

  Optional<Policy> findPolicy(long id);

  Optional<Policy> findPolicyByPublicId(long organizationId, String publicId);

  long insertPolicy(
      long organizationId, String externalId, String publicId, String name, int threatLevel,
      Map<String, Object> constraints, Instant now);

  boolean updatePolicy(
      long id, String externalId, String publicId, String name, int threatLevel,
      Map<String, Object> constraints, Instant now);

  boolean deletePolicy(long id);

  /** Replaces the violation snapshot of a scan still owned by {@code owner}. */
  boolean replaceViolations(long scanId, String owner, List<Violation> violations);

  List<Violation> listViolations(long scanId);
}
