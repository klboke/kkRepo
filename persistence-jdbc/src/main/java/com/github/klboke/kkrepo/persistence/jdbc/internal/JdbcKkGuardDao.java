package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcKkGuardDao implements KkGuardDao {
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final String CLAIMABLE_STATUSES = "status IN ('PENDING', 'SCANNING')";
  private final JdbcTemplate jdbc;
  private final JsonColumns json;

  public JdbcKkGuardDao(JdbcTemplate jdbc, JsonColumns json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private final RowMapper<Organization> organizations = (rs, n) -> new Organization(
      rs.getLong("id"), rs.getString("external_id"), rs.getString("name"),
      rs.getString("description"), instant(rs, "created_at"), instant(rs, "updated_at"));

  private final RowMapper<Application> applications = (rs, n) -> new Application(
      rs.getLong("id"), rs.getLong("organization_id"), trimmed(rs, "external_id"),
      rs.getString("public_id"), rs.getString("name"), nullableLong(rs, "repository_id"),
      rs.getString("contact_user_name"), trimmed(rs, "dt_project_uuid"),
      rs.getString("dt_project_name"), instant(rs, "created_at"), instant(rs, "updated_at"));

  private final RowMapper<Scan> scans = (rs, n) -> new Scan(
      rs.getLong("id"), rs.getLong("application_id"), rs.getString("scan_id"),
      rs.getString("status"), rs.getString("stage"), rs.getString("source_id"),
      rs.getString("sbom_sha256"), rs.getString("report_sha256"),
      rs.getInt("component_count"), rs.getInt("violation_count"), rs.getString("error"),
      trimmed(rs, "dt_project_uuid"), trimmed(rs, "dt_bom_token"), rs.getInt("attempts"),
      rs.getString("lease_owner"), instant(rs, "lease_expires_at"),
      instant(rs, "submitted_at"), instant(rs, "last_polled_at"),
      instant(rs, "created_at"), instant(rs, "started_at"), instant(rs, "completed_at"));

  private final RowMapper<ScanComponent> components = (rs, n) -> new ScanComponent(
      rs.getLong("scan_id"), rs.getString("component_ref"), rs.getString("purl"),
      rs.getString("name"), rs.getString("version"), rs.getString("license"),
      rs.getString("highest_severity"));

  private final RowMapper<Finding> findings = (rs, n) -> new Finding(
      rs.getLong("scan_id"), rs.getString("component_ref"), rs.getString("vulnerability_id"),
      rs.getString("severity"), rs.getBigDecimal("cvss_score"), rs.getString("source"),
      rs.getString("description"), rs.getString("url"));

  private final RowMapper<Policy> policies = (rs, n) -> new Policy(
      rs.getLong("id"), rs.getLong("organization_id"), rs.getString("external_id"),
      rs.getString("public_id"), rs.getString("name"), rs.getInt("threat_level"),
      readMap(rs.getString("constraints_json")), instant(rs, "created_at"),
      instant(rs, "updated_at"));

  private final RowMapper<Violation> violations = (rs, n) -> new Violation(
      rs.getLong("scan_id"), rs.getLong("policy_id"), rs.getString("component_ref"),
      rs.getInt("threat_level"), readMap(rs.getString("constraint_json")));

  @Override
  public List<Organization> listOrganizations() {
    return jdbc.query("SELECT * FROM kkg_organization ORDER BY name, id", organizations);
  }

  @Override
  public Optional<Organization> findOrganization(long id) {
    return jdbc.query("SELECT * FROM kkg_organization WHERE id = ?", organizations, id)
        .stream().findFirst();
  }

  @Override
  public Optional<Organization> findOrganizationByExternalId(String externalId) {
    return jdbc.query("SELECT * FROM kkg_organization WHERE external_id = ?", organizations,
        externalId).stream().findFirst();
  }

  @Override
  public long insertOrganization(String externalId, String name, String description, Instant now) {
    return JdbcInserts.insert(jdbc, """
        INSERT INTO kkg_organization (external_id, name, description, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?)
        """, ps -> {
      ps.setString(1, externalId);
      ps.setString(2, name);
      ps.setString(3, description);
      ps.setTimestamp(4, Timestamp.from(now));
      ps.setTimestamp(5, Timestamp.from(now));
    });
  }

  @Override
  public boolean updateOrganization(long id, String name, String description, Instant now) {
    return jdbc.update("""
        UPDATE kkg_organization SET name = ?, description = ?, updated_at = ? WHERE id = ?
        """, name, description, Timestamp.from(now), id) > 0;
  }

  @Override
  public boolean deleteOrganization(long id) {
    return jdbc.update("DELETE FROM kkg_organization WHERE id = ?", id) > 0;
  }

  @Override
  public List<Application> listApplications() {
    return jdbc.query("SELECT * FROM kkg_application ORDER BY name, id", applications);
  }

  @Override
  public List<Application> listApplicationsByOrganization(long organizationId) {
    return jdbc.query(
        "SELECT * FROM kkg_application WHERE organization_id = ? ORDER BY name, id",
        applications, organizationId);
  }

  @Override
  public Optional<Application> findApplication(long id) {
    return jdbc.query("SELECT * FROM kkg_application WHERE id = ?", applications, id)
        .stream().findFirst();
  }

  @Override
  public Optional<Application> findApplicationByExternalId(String externalId) {
    return jdbc.query("SELECT * FROM kkg_application WHERE external_id = ?", applications,
        externalId).stream().findFirst();
  }

  @Override
  public Optional<Application> findApplicationByPublicId(String publicId) {
    return jdbc.query("SELECT * FROM kkg_application WHERE public_id = ?", applications, publicId)
        .stream().findFirst();
  }

  @Override
  public long insertApplication(
      long organizationId, String externalId, String publicId, String name, Long repositoryId,
      String contactUserName, Instant now) {
    return JdbcInserts.insert(jdbc, """
        INSERT INTO kkg_application
          (organization_id, external_id, public_id, name, repository_id, contact_user_name,
           created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setLong(1, organizationId);
      ps.setString(2, externalId);
      ps.setString(3, publicId);
      ps.setString(4, name);
      if (repositoryId == null) ps.setNull(5, java.sql.Types.BIGINT);
      else ps.setLong(5, repositoryId);
      ps.setString(6, contactUserName);
      ps.setTimestamp(7, Timestamp.from(now));
      ps.setTimestamp(8, Timestamp.from(now));
    });
  }

  @Override
  public boolean updateApplication(
      long id, long organizationId, String externalId, String publicId, String name,
      Long repositoryId, String contactUserName, Instant now) {
    return jdbc.update("""
        UPDATE kkg_application
        SET organization_id = ?, external_id = ?, public_id = ?, name = ?, repository_id = ?,
          contact_user_name = ?, updated_at = ? WHERE id = ?
        """, organizationId, externalId, publicId, name, repositoryId, contactUserName,
        Timestamp.from(now), id) > 0;
  }

  @Override
  public boolean deleteApplication(long id) {
    return jdbc.update("DELETE FROM kkg_application WHERE id = ?", id) > 0;
  }

  @Override
  public boolean updateApplicationDependencyTrackProject(
      long id, String dtProjectUuid, String dtProjectName, Instant now) {
    return jdbc.update("""
        UPDATE kkg_application SET dt_project_uuid = ?, dt_project_name = ?, updated_at = ?
        WHERE id = ?
        """, dtProjectUuid, dtProjectName, Timestamp.from(now), id) > 0;
  }

  @Override
  public List<ApplicationTag> listApplicationTags(long applicationId) {
    return jdbc.query("""
        SELECT application_id, name, description FROM kkg_application_tag
        WHERE application_id = ? ORDER BY name
        """, (rs, n) -> new ApplicationTag(
        rs.getLong("application_id"), rs.getString("name"), rs.getString("description")),
        applicationId);
  }

  @Override
  public void replaceApplicationTags(long applicationId, List<ApplicationTag> tags) {
    jdbc.update("DELETE FROM kkg_application_tag WHERE application_id = ?", applicationId);
    for (ApplicationTag tag : tags) {
      jdbc.update("""
          INSERT INTO kkg_application_tag (application_id, name, description)
          VALUES (?, ?, ?)
          """, applicationId, tag.name(), tag.description());
    }
  }

  @Override
  public long insertScan(Scan scan) {
    return JdbcInserts.insert(jdbc, """
        INSERT INTO kkg_scan
          (application_id, scan_id, status, stage, source_id, sbom_sha256, report_sha256,
           component_count, violation_count, error, dt_project_uuid, dt_bom_token, attempts,
           submitted_at, last_polled_at, created_at, started_at, completed_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setLong(1, scan.applicationId());
      ps.setString(2, scan.scanId());
      ps.setString(3, scan.status());
      ps.setString(4, scan.stage());
      ps.setString(5, scan.sourceId());
      ps.setString(6, scan.sbomSha256());
      ps.setString(7, scan.reportSha256());
      ps.setInt(8, scan.componentCount());
      ps.setInt(9, scan.violationCount());
      ps.setString(10, scan.error());
      ps.setString(11, scan.dtProjectUuid());
      ps.setString(12, scan.dtBomToken());
      ps.setInt(13, scan.attempts());
      bindInstant(ps, 14, scan.submittedAt());
      bindInstant(ps, 15, scan.lastPolledAt());
      ps.setTimestamp(16, Timestamp.from(
          scan.createdAt() == null ? Instant.now() : scan.createdAt()));
      bindInstant(ps, 17, scan.startedAt());
      bindInstant(ps, 18, scan.completedAt());
    });
  }

  @Override
  public Optional<Scan> findScan(long id) {
    return jdbc.query("SELECT * FROM kkg_scan WHERE id = ?", scans, id).stream().findFirst();
  }

  @Override
  public Optional<Scan> findScanByScanId(String scanId) {
    return jdbc.query("SELECT * FROM kkg_scan WHERE scan_id = ?", scans, scanId)
        .stream().findFirst();
  }

  @Override
  public Optional<Scan> lockScanByScanId(String scanId) {
    return jdbc.query("SELECT * FROM kkg_scan WHERE scan_id = ? FOR UPDATE", scans, scanId)
        .stream().findFirst();
  }

  @Override
  public Optional<Scan> findCompletedScanBySbom(
      long applicationId, String sourceId, String sbomSha256) {
    return jdbc.query("""
        SELECT * FROM kkg_scan
        WHERE application_id = ? AND COALESCE(source_id, '') = COALESCE(?, '')
          AND sbom_sha256 = ? AND status = 'COMPLETE'
        ORDER BY id DESC LIMIT 1
        """, scans, applicationId, sourceId, sbomSha256).stream().findFirst();
  }

  @Override
  public Optional<Scan> findLatestScanByApplicationAndSource(
      long applicationId, String sourceId) {
    return jdbc.query("""
        SELECT * FROM kkg_scan
        WHERE application_id = ? AND COALESCE(source_id, '') = COALESCE(?, '')
        ORDER BY id DESC LIMIT 1
        """, scans, applicationId, sourceId).stream().findFirst();
  }

  @Override
  public boolean attachScanSbom(
      long scanId, long blobStoreId, String blobRef, String sha256, long size) {
    return jdbc.update("""
        UPDATE kkg_scan SET sbom_blob_store_id = ?, sbom_blob_ref = ?, sbom_sha256 = ?,
          sbom_size = ?
        WHERE id = ?
        """, blobStoreId, blobRef, sha256, size, scanId) > 0;
  }

  @Override
  public Optional<ScanSbom> findScanSbom(long scanId) {
    return jdbc.query("""
        SELECT sbom_blob_store_id, sbom_blob_ref, sbom_sha256, sbom_size FROM kkg_scan
        WHERE id = ? AND sbom_blob_ref IS NOT NULL
        """, (rs, n) -> new ScanSbom(
        rs.getLong("sbom_blob_store_id"), rs.getString("sbom_blob_ref"),
        rs.getString("sbom_sha256"), rs.getLong("sbom_size")), scanId).stream().findFirst();
  }

  @Override
  public List<Long> listClaimableScanIds(Instant now, int limit) {
    return jdbc.queryForList("""
        SELECT id FROM kkg_scan
        WHERE %s
          AND (lease_owner IS NULL OR lease_expires_at IS NULL OR lease_expires_at < ?)
        ORDER BY id LIMIT ?
        """.formatted(CLAIMABLE_STATUSES), Long.class, Timestamp.from(now),
        Math.max(1, limit));
  }

  @Override
  public boolean claimScan(long id, String owner, Instant now, Instant leaseExpiresAt) {
    return jdbc.update("""
        UPDATE kkg_scan SET lease_owner = ?, lease_expires_at = ?
        WHERE id = ? AND %s
          AND (lease_owner IS NULL OR lease_expires_at IS NULL OR lease_expires_at < ?)
        """.formatted(CLAIMABLE_STATUSES), owner, Timestamp.from(leaseExpiresAt), id,
        Timestamp.from(now)) > 0;
  }

  @Override
  public boolean releaseScan(long id, String owner) {
    return jdbc.update("""
        UPDATE kkg_scan SET lease_owner = NULL, lease_expires_at = NULL
        WHERE id = ? AND lease_owner = ?
        """, id, owner) > 0;
  }

  @Override
  public boolean markScanSubmitted(
      long id, String owner, String dtProjectUuid, String dtBomToken, Instant submittedAt) {
    return jdbc.update("""
        UPDATE kkg_scan
        SET status = 'SCANNING', dt_project_uuid = ?, dt_bom_token = ?, submitted_at = ?,
          lease_owner = NULL, lease_expires_at = NULL, error = NULL
        WHERE id = ? AND lease_owner = ?
        """, dtProjectUuid, dtBomToken, Timestamp.from(submittedAt), id, owner) > 0;
  }

  @Override
  public boolean markScanPolled(long id, String owner, int attempts, Instant polledAt) {
    return jdbc.update("""
        UPDATE kkg_scan SET attempts = ?, last_polled_at = ? WHERE id = ? AND lease_owner = ?
        """, attempts, Timestamp.from(polledAt), id, owner) > 0;
  }

  @Override
  public boolean finishScan(
      long id, String owner, String status, String error, int componentCount,
      int violationCount, Instant completedAt) {
    return jdbc.update("""
        UPDATE kkg_scan
        SET status = ?, error = ?, component_count = ?, violation_count = ?, completed_at = ?,
          lease_owner = NULL, lease_expires_at = NULL
        WHERE id = ? AND lease_owner = ?
        """, status, error, componentCount, violationCount, Timestamp.from(completedAt), id,
        owner) > 0;
  }

  @Override
  public boolean replaceScanComponents(long scanId, String owner, List<ScanComponent> components) {
    if (!lockOwnedScan(scanId, owner)) {
      return false;
    }
    jdbc.update("DELETE FROM kkg_scan_component WHERE scan_id = ?", scanId);
    for (ScanComponent component : components) {
      jdbc.update("""
          INSERT INTO kkg_scan_component
            (scan_id, component_ref, purl, name, version, license, highest_severity)
          VALUES (?, ?, ?, ?, ?, ?, ?)
          """, scanId, component.ref(), component.purl(), component.name(), component.version(),
          component.license(), component.highestSeverity());
    }
    return true;
  }

  @Override
  public boolean replaceScanFindings(long scanId, String owner, List<Finding> findings) {
    if (!lockOwnedScan(scanId, owner)) {
      return false;
    }
    jdbc.update("DELETE FROM kkg_scan_finding WHERE scan_id = ?", scanId);
    for (Finding finding : findings) {
      jdbc.update("""
          INSERT INTO kkg_scan_finding
            (scan_id, component_ref, vulnerability_id, severity, cvss_score, source, description, url)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?)
          """, scanId, finding.componentRef(), finding.vulnerabilityId(), finding.severity(),
          finding.cvssScore(), finding.source(), finding.description(), finding.url());
    }
    return true;
  }

  /**
   * Takes the row lock on the scan only while {@code owner} still holds the lease. Callers must run
   * inside a transaction so the lock survives until the snapshot writes commit; a replica that lost
   * its lease therefore cannot overwrite a snapshot produced by the new owner.
   */
  private boolean lockOwnedScan(long scanId, String owner) {
    return !jdbc.queryForList(
        "SELECT id FROM kkg_scan WHERE id = ? AND lease_owner = ? FOR UPDATE",
        Long.class, scanId, owner).isEmpty();
  }

  @Override
  public List<ScanComponent> listScanComponents(long scanId) {
    return jdbc.query(
        "SELECT * FROM kkg_scan_component WHERE scan_id = ? ORDER BY component_ref",
        components, scanId);
  }

  @Override
  public List<Finding> listScanFindings(long scanId) {
    return jdbc.query("""
        SELECT * FROM kkg_scan_finding WHERE scan_id = ?
        ORDER BY component_ref, vulnerability_id
        """, findings, scanId);
  }

  @Override
  public List<Policy> listPolicies(long organizationId) {
    return jdbc.query("SELECT * FROM kkg_policy WHERE organization_id = ? ORDER BY name, id",
        policies, organizationId);
  }

  @Override
  public Optional<Policy> findPolicy(long id) {
    return jdbc.query("SELECT * FROM kkg_policy WHERE id = ?", policies, id).stream().findFirst();
  }

  @Override
  public Optional<Policy> findPolicyByPublicId(long organizationId, String publicId) {
    return jdbc.query(
        "SELECT * FROM kkg_policy WHERE organization_id = ? AND public_id = ?",
        policies, organizationId, publicId).stream().findFirst();
  }

  @Override
  public long insertPolicy(
      long organizationId, String externalId, String publicId, String name, int threatLevel,
      Map<String, Object> constraints, Instant now) {
    return JdbcInserts.insert(jdbc, """
        INSERT INTO kkg_policy
          (organization_id, external_id, public_id, name, threat_level, constraints_json,
           created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setLong(1, organizationId);
      ps.setString(2, externalId);
      ps.setString(3, publicId);
      ps.setString(4, name);
      ps.setInt(5, threatLevel);
      json.bindSerialized(ps, 6, json.writeValue(constraints));
      ps.setTimestamp(7, Timestamp.from(now));
      ps.setTimestamp(8, Timestamp.from(now));
    });
  }

  @Override
  public boolean updatePolicy(
      long id, String externalId, String publicId, String name, int threatLevel,
      Map<String, Object> constraints, Instant now) {
    return jdbc.update("""
        UPDATE kkg_policy SET external_id = ?, public_id = ?, name = ?, threat_level = ?,
          constraints_json = ?, updated_at = ? WHERE id = ?
        """, externalId, publicId, name, threatLevel,
        json.serializedParameter(json.writeValue(constraints)), Timestamp.from(now), id) > 0;
  }

  @Override
  public boolean deletePolicy(long id) {
    return jdbc.update("DELETE FROM kkg_policy WHERE id = ?", id) > 0;
  }

  @Override
  public boolean replaceViolations(long scanId, String owner, List<Violation> violations) {
    if (!lockOwnedScan(scanId, owner)) {
      return false;
    }
    jdbc.update("DELETE FROM kkg_violation WHERE scan_id = ?", scanId);
    for (Violation violation : violations) {
      jdbc.update("""
          INSERT INTO kkg_violation
            (scan_id, policy_id, component_ref, threat_level, constraint_json)
          VALUES (?, ?, ?, ?, ?)
          """, scanId, violation.policyId(), violation.componentRef(), violation.threatLevel(),
          json.serializedParameter(json.writeValue(violation.constraint())));
    }
    return true;
  }

  @Override
  public List<Violation> listViolations(long scanId) {
    return jdbc.query(
        "SELECT * FROM kkg_violation WHERE scan_id = ? ORDER BY threat_level DESC, id",
        violations, scanId);
  }

  private Map<String, Object> readMap(String value) {
    return value == null || value.isBlank() ? Map.of() : json.readValue(value, MAP);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  /** CHAR columns are right-padded by PostgreSQL; callers need the logical value. */
  private static String trimmed(ResultSet rs, String column) throws SQLException {
    String value = rs.getString(column);
    return value == null ? null : value.stripTrailing();
  }

  private static void bindInstant(java.sql.PreparedStatement ps, int index, Instant value)
      throws SQLException {
    if (value == null) ps.setNull(index, java.sql.Types.TIMESTAMP);
    else ps.setTimestamp(index, Timestamp.from(value));
  }
}
