package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableLong;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableTimestamp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.EnumColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import com.github.klboke.kkrepo.security.scan.ScanEnums.BackfillStatus;
import com.github.klboke.kkrepo.security.scan.ScanEnums.EnforcementMode;
import com.github.klboke.kkrepo.security.scan.ScanEnums.OciPlatformPolicy;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyAction;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyDecision;
import com.github.klboke.kkrepo.security.scan.ScanEnums.RequestReason;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanStage;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanState;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
import com.github.klboke.kkrepo.security.scan.ScanEnums.SubjectKind;
import com.github.klboke.kkrepo.security.scan.ScanEnums.TaskStatus;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Database-neutral JDBC implementation using row locks, leases, and fencing tokens. */
@Repository
public class JdbcSecurityScanDao implements SecurityScanDao {
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
  private static final TypeReference<Map<String, Integer>> INTEGER_MAP = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  // Row mappers are constructed before the constructor body and dereference this field only when
  // a query executes, after construction has completed.
  private JsonColumns json;

  private final RowMapper<ScanProfile> profileMapper = (rs, rowNum) -> new ScanProfile(
      rs.getLong("id"),
      rs.getString("name"),
      rs.getBoolean("enabled"),
      rs.getString("catalog_engine"),
      rs.getString("matcher_engine"),
      list(rs.getString("scanner_types_json")),
      json.read(rs.getString("target_rules_json")),
      rs.getLong("max_input_bytes"),
      rs.getInt("max_archive_entries"),
      rs.getLong("max_uncompressed_bytes"),
      rs.getLong("max_single_file_bytes"),
      rs.getInt("max_nested_depth"),
      rs.getInt("timeout_seconds"),
      enumValue(OciPlatformPolicy.class, rs.getString("oci_platform_policy")),
      list(rs.getString("required_platforms_json")),
      rs.getString("configuration_digest"),
      rs.getLong("revision"),
      nullableInstant(rs, "created_at"),
      nullableInstant(rs, "updated_at"));

  private final RowMapper<RepositoryScanConfig> configMapper =
      (rs, rowNum) -> new RepositoryScanConfig(
          rs.getLong("repository_id"),
          rs.getBoolean("enabled"),
          rs.getLong("profile_id"),
          rs.getBoolean("scan_hosted_content"),
          rs.getBoolean("scan_proxy_content"),
          enumValue(EnforcementMode.class, rs.getString("enforcement_mode")),
          enumValue(PolicyAction.class, rs.getString("pending_action")),
          enumValue(PolicyAction.class, rs.getString("failure_action")),
          enumValue(PolicyAction.class, rs.getString("partial_action")),
          nullableLong(rs, "max_result_age_seconds"),
          nullableLong(rs, "policy_id"),
          rs.getLong("config_revision"),
          nullableInstant(rs, "created_at"),
          nullableInstant(rs, "updated_at"));

  private final RowMapper<ScanCandidate> candidateMapper = (rs, rowNum) -> new ScanCandidate(
      rs.getLong("asset_id"),
      nullableLong(rs, "asset_blob_id"),
      rs.getLong("content_generation"),
      rs.getLong("enqueued_generation"),
      nullableInstant(rs, "changed_at"),
      nullableInstant(rs, "updated_at"));

  private final RowMapper<ScanTask> taskMapper = (rs, rowNum) -> new ScanTask(
      rs.getLong("id"),
      rs.getLong("repository_id"),
      nullableLong(rs, "asset_id"),
      enumValue(SubjectKind.class, rs.getString("subject_kind")),
      rs.getString("subject_key"),
      rs.getBytes("subject_key_hash"),
      rs.getLong("content_generation"),
      rs.getLong("profile_id"),
      rs.getLong("profile_revision"),
      nullableLong(rs, "requested_scanner_snapshot_id"),
      enumValue(ScanStage.class, rs.getString("stage")),
      enumValue(RequestReason.class, rs.getString("request_reason")),
      rs.getInt("priority"),
      enumValue(TaskStatus.class, rs.getString("status")),
      rs.getInt("attempts"),
      rs.getInt("max_attempts"),
      nullableInstant(rs, "next_attempt_at"),
      rs.getString("claimed_by"),
      rs.getString("lease_token"),
      nullableInstant(rs, "lease_until"),
      nullableInstant(rs, "last_heartbeat_at"),
      rs.getString("last_error_code"),
      rs.getString("last_error_summary"),
      rs.getString("requested_by"),
      rs.getString("request_uuid"),
      nullableInstant(rs, "requested_at"),
      nullableInstant(rs, "started_at"),
      nullableInstant(rs, "finished_at"),
      nullableInstant(rs, "created_at"),
      nullableInstant(rs, "updated_at"));

  private final RowMapper<ScannerSnapshot> snapshotMapper =
      (rs, rowNum) -> new ScannerSnapshot(
          rs.getLong("id"),
          rs.getString("adapter_name"),
          rs.getString("adapter_api_version"),
          rs.getString("engine_name"),
          rs.getString("engine_version"),
          rs.getString("vulnerability_database_revision"),
          nullableInstant(rs, "vulnerability_database_updated_at"),
          rs.getString("capability_digest"),
          rs.getString("snapshot_fingerprint"),
          nullableInstant(rs, "observed_at"),
          rs.getBoolean("ready"),
          json.read(rs.getString("details_json")));

  private final RowMapper<Sbom> sbomMapper = (rs, rowNum) -> new Sbom(
      rs.getLong("id"),
      enumValue(SubjectKind.class, rs.getString("subject_kind")),
      rs.getString("subject_identity"),
      rs.getBytes("subject_identity_hash"),
      rs.getString("catalog_engine"),
      rs.getString("catalog_engine_version"),
      rs.getString("catalog_configuration_digest"),
      rs.getString("catalog_fingerprint"),
      rs.getLong("document_blob_id"),
      rs.getString("document_sha256"),
      rs.getString("spec_name"),
      rs.getString("spec_version"),
      rs.getInt("component_count"),
      rs.getInt("dependency_count"),
      rs.getBoolean("inventory_complete"),
      nullableInstant(rs, "created_at"));

  private final RowMapper<SbomComponent> componentMapper = (rs, rowNum) -> new SbomComponent(
      rs.getLong("id"),
      rs.getLong("sbom_id"),
      rs.getString("component_ref"),
      rs.getBytes("component_ref_hash"),
      rs.getString("package_url"),
      rs.getBytes("package_url_hash"),
      rs.getString("component_type"),
      rs.getString("namespace"),
      rs.getString("name"),
      rs.getString("version"),
      rs.getString("directness"),
      list(rs.getString("locations_json")),
      list(rs.getString("licenses_json")),
      json.read(rs.getString("properties_json")));

  private final RowMapper<ScanRun> runMapper = (rs, rowNum) -> new ScanRun(
      rs.getLong("id"),
      nullableLong(rs, "task_id"),
      rs.getLong("sbom_id"),
      rs.getLong("scanner_snapshot_id"),
      rs.getString("match_configuration_digest"),
      rs.getString("match_fingerprint"),
      enumValue(ScanState.class, rs.getString("status")),
      enumValue(ScanCompleteness.class, rs.getString("scan_completeness")),
      rs.getLong("raw_report_blob_id"),
      rs.getString("raw_report_sha256"),
      rs.getInt("finding_count"),
      rs.getInt("fixable_finding_count"),
      rs.getInt("critical_count"),
      rs.getInt("high_count"),
      rs.getInt("medium_count"),
      rs.getInt("low_count"),
      rs.getInt("unknown_count"),
      enumValue(Severity.class, rs.getString("max_severity")),
      nullableInstant(rs, "started_at"),
      nullableInstant(rs, "completed_at"),
      nullableInstant(rs, "created_at"));

  private final RowMapper<ScanFinding> findingMapper = (rs, rowNum) -> {
    Number score = (Number) rs.getObject("cvss_score");
    return new ScanFinding(
        rs.getLong("id"),
        rs.getLong("scan_run_id"),
        rs.getString("finding_key"),
        rs.getBytes("finding_key_hash"),
        rs.getString("advisory_id"),
        list(rs.getString("aliases_json")),
        rs.getString("data_source"),
        rs.getString("package_url"),
        rs.getString("package_name"),
        rs.getString("installed_version"),
        list(rs.getString("fixed_versions_json")),
        enumValue(Severity.class, rs.getString("severity")),
        rs.getString("severity_source"),
        rs.getString("cvss_vector"),
        score == null ? null : score.doubleValue(),
        rs.getString("title"),
        rs.getString("description"),
        rs.getString("primary_url"),
        list(rs.getString("locations_json")),
        rs.getString("source_status"),
        nullableInstant(rs, "created_at"));
  };

  private final RowMapper<AssetSecurityState> stateMapper =
      (rs, rowNum) -> new AssetSecurityState(
          rs.getLong("asset_id"),
          rs.getLong("profile_id"),
          rs.getLong("content_generation"),
          rs.getBytes("subject_identity_hash"),
          nullableLong(rs, "latest_scan_run_id"),
          enumValue(ScanState.class, rs.getString("scan_state")),
          enumValue(ScanCompleteness.class, rs.getString("scan_completeness")),
          rs.getBoolean("inventory_complete"),
          enumValue(Severity.class, rs.getString("max_severity")),
          integerMap(rs.getString("finding_counts_json")),
          nullableLong(rs, "policy_id"),
          nullableLong(rs, "policy_revision"),
          enumValue(PolicyDecision.class, rs.getString("policy_decision")),
          rs.getString("policy_reason_code"),
          nullableInstant(rs, "stale_at"),
          nullableInstant(rs, "last_evaluated_at"),
          rs.getLong("version"));

  private final RowMapper<AssetPolicyState> policyStateMapper =
      (rs, rowNum) -> new AssetPolicyState(
          rs.getLong("asset_id"),
          rs.getLong("profile_id"),
          rs.getLong("repository_id"),
          rs.getLong("content_generation"),
          nullableLong(rs, "latest_scan_run_id"),
          nullableLong(rs, "policy_id"),
          nullableLong(rs, "policy_revision"),
          rs.getLong("config_revision"),
          enumValue(PolicyDecision.class, rs.getString("policy_decision")),
          rs.getString("policy_reason_code"),
          rs.getInt("waived_findings"),
          nullableInstant(rs, "stale_at"),
          nullableInstant(rs, "next_waiver_expiry"),
          nullableInstant(rs, "last_evaluated_at"),
          rs.getLong("version"));

  private final RowMapper<ScanPolicy> policyMapper = (rs, rowNum) -> new ScanPolicy(
      rs.getLong("id"),
      rs.getString("name"),
      rs.getBoolean("enabled"),
      enumValue(Severity.class, rs.getString("block_severity")),
      rs.getBoolean("only_fixable"),
      rs.getBoolean("block_unknown_severity"),
      rs.getBoolean("require_complete_inventory"),
      nullableLong(rs, "max_result_age_seconds"),
      list(rs.getString("required_platforms_json")),
      rs.getLong("revision"),
      rs.getString("created_by"),
      nullableInstant(rs, "created_at"),
      nullableInstant(rs, "updated_at"));

  private final RowMapper<ScanWaiver> waiverMapper = (rs, rowNum) -> new ScanWaiver(
      rs.getLong("id"),
      rs.getString("scope_type"),
      nullableLong(rs, "repository_id"),
      nullableLong(rs, "asset_id"),
      nullableLong(rs, "finding_id"),
      rs.getString("advisory_selector"),
      rs.getString("package_selector"),
      json.read(rs.getString("selector_json")),
      rs.getString("reason"),
      nullableLong(rs, "policy_id"),
      nullableLong(rs, "policy_revision"),
      rs.getString("created_by"),
      rs.getString("approved_by"),
      nullableInstant(rs, "expires_at"),
      nullableInstant(rs, "created_at"),
      nullableInstant(rs, "updated_at"));

  private final RowMapper<BackfillJob> backfillMapper = (rs, rowNum) -> new BackfillJob(
      rs.getLong("id"),
      rs.getLong("repository_id"),
      enumValue(BackfillStatus.class, rs.getString("status")),
      rs.getLong("cursor_asset_id"),
      rs.getLong("scanned_assets"),
      rs.getLong("marked_assets"),
      rs.getInt("attempts"),
      rs.getString("claimed_by"),
      rs.getString("lease_token"),
      nullableInstant(rs, "lease_until"),
      rs.getString("last_error_summary"),
      rs.getString("created_by"),
      nullableInstant(rs, "created_at"),
      nullableInstant(rs, "updated_at"),
      nullableInstant(rs, "completed_at"));

  public JdbcSecurityScanDao(JdbcTemplate jdbc, JsonColumns json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public Optional<ScanProfile> findProfile(long profileId) {
    return jdbc.query("SELECT * FROM security_scan_profile WHERE id = ?", profileMapper, profileId)
        .stream().findFirst();
  }

  @Override
  public List<ScanProfile> listProfiles() {
    return jdbc.query("SELECT * FROM security_scan_profile ORDER BY name, revision, id", profileMapper);
  }

  @Override
  public ScanProfile createProfile(ScanProfile profile) {
    long id = JdbcInserts.insert(jdbc, """
        INSERT INTO security_scan_profile
          (name, enabled, catalog_engine, matcher_engine, scanner_types_json, target_rules_json,
           max_input_bytes, max_archive_entries, max_uncompressed_bytes, max_single_file_bytes,
           max_nested_depth, timeout_seconds, oci_platform_policy, required_platforms_json,
           configuration_digest, revision, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setString(1, profile.name());
      ps.setBoolean(2, profile.enabled());
      ps.setString(3, profile.catalogEngine());
      ps.setString(4, profile.matcherEngine());
      json.bindSerialized(ps, 5, json.writeValue(profile.scannerTypes()));
      json.bind(ps, 6, profile.targetRules());
      ps.setLong(7, profile.maxInputBytes());
      ps.setInt(8, profile.maxArchiveEntries());
      ps.setLong(9, profile.maxUncompressedBytes());
      ps.setLong(10, profile.maxSingleFileBytes());
      ps.setInt(11, profile.maxNestedDepth());
      ps.setInt(12, profile.timeoutSeconds());
      ps.setString(13, profile.ociPlatformPolicy().name());
      json.bindSerialized(ps, 14, json.writeValue(profile.requiredPlatforms()));
      ps.setString(15, profile.configurationDigest());
      ps.setLong(16, Math.max(1, profile.revision()));
      ps.setTimestamp(17, nullableTimestamp(profile.createdAt()));
      ps.setTimestamp(18, nullableTimestamp(profile.updatedAt()));
    });
    return findProfile(id).orElseThrow();
  }

  @Override
  public Optional<RepositoryScanConfig> findRepositoryConfig(long repositoryId) {
    return jdbc.query(
        "SELECT * FROM repository_security_scan_config WHERE repository_id = ?",
        configMapper,
        repositoryId).stream().findFirst();
  }

  @Override
  @Transactional
  public RepositoryScanConfig upsertRepositoryConfig(RepositoryScanConfig config) {
    Instant now = requiredNow(config.updatedAt());
    int updated = jdbc.update("""
        UPDATE repository_security_scan_config
        SET enabled = ?, profile_id = ?, scan_hosted_content = ?, scan_proxy_content = ?,
            enforcement_mode = ?, pending_action = ?, failure_action = ?, partial_action = ?,
            max_result_age_seconds = ?, policy_id = ?, config_revision = config_revision + 1,
            updated_at = ?
        WHERE repository_id = ?
        """,
        config.enabled(),
        config.profileId(),
        config.scanHostedContent(),
        config.scanProxyContent(),
        config.enforcementMode().name(),
        config.pendingAction().name(),
        config.failureAction().name(),
        config.partialAction().name(),
        config.maxResultAgeSeconds(),
        config.policyId(),
        nullableTimestamp(now),
        config.repositoryId());
    if (updated == 0) {
      insertRepositoryConfig(config, now);
    }
    return findRepositoryConfig(config.repositoryId()).orElseThrow();
  }

  @Override
  public int bumpAllRepositoryConfigRevisions(Instant updatedAt) {
    return jdbc.update("""
        UPDATE repository_security_scan_config
        SET config_revision = config_revision + 1, updated_at = ?
        WHERE enabled = TRUE
        """, nullableTimestamp(requiredNow(updatedAt)));
  }

  private void insertRepositoryConfig(RepositoryScanConfig config, Instant now) {
    boolean inserted = JdbcInserts.tryUpdate(jdbc, """
        INSERT INTO repository_security_scan_config
          (repository_id, enabled, profile_id, scan_hosted_content, scan_proxy_content,
           enforcement_mode, pending_action, failure_action, partial_action,
           max_result_age_seconds, policy_id, config_revision, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setLong(1, config.repositoryId());
      ps.setBoolean(2, config.enabled());
      ps.setLong(3, config.profileId());
      ps.setBoolean(4, config.scanHostedContent());
      ps.setBoolean(5, config.scanProxyContent());
      ps.setString(6, config.enforcementMode().name());
      ps.setString(7, config.pendingAction().name());
      ps.setString(8, config.failureAction().name());
      ps.setString(9, config.partialAction().name());
      setNullableLong(ps, 10, config.maxResultAgeSeconds());
      setNullableLong(ps, 11, config.policyId());
      ps.setLong(12, Math.max(1, config.configRevision()));
      ps.setTimestamp(13, nullableTimestamp(now));
      ps.setTimestamp(14, nullableTimestamp(now));
    });
    if (!inserted) {
      jdbc.update("""
          UPDATE repository_security_scan_config
          SET enabled = ?, profile_id = ?, scan_hosted_content = ?, scan_proxy_content = ?,
              enforcement_mode = ?, pending_action = ?, failure_action = ?, partial_action = ?,
              max_result_age_seconds = ?, policy_id = ?, config_revision = config_revision + 1,
              updated_at = ?
          WHERE repository_id = ?
          """,
          config.enabled(), config.profileId(), config.scanHostedContent(), config.scanProxyContent(),
          config.enforcementMode().name(), config.pendingAction().name(),
          config.failureAction().name(), config.partialAction().name(),
          config.maxResultAgeSeconds(), config.policyId(), nullableTimestamp(now),
          config.repositoryId());
    }
  }

  @Override
  public Optional<ScanCandidate> findCandidate(long assetId) {
    return jdbc.query(
        "SELECT * FROM security_scan_candidate WHERE asset_id = ?",
        candidateMapper,
        assetId).stream().findFirst();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public List<ScanCandidate> claimCandidates(int maxItems) {
    return jdbc.query("""
        SELECT *
        FROM security_scan_candidate
        WHERE content_generation > enqueued_generation
        ORDER BY changed_at, asset_id
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """, candidateMapper, safeLimit(maxItems));
  }

  @Override
  public boolean markCandidateEnqueued(long assetId, long expectedGeneration) {
    return jdbc.update("""
        UPDATE security_scan_candidate
        SET enqueued_generation = ?, updated_at = CURRENT_TIMESTAMP
        WHERE asset_id = ?
          AND content_generation = ?
          AND enqueued_generation < ?
        """, expectedGeneration, assetId, expectedGeneration, expectedGeneration) == 1;
  }

  @Override
  @Transactional
  public BackfillPage markRepositoryAssetsForBackfill(
      long repositoryId, long afterAssetId, int maxItems) {
    int limit = safeLimit(maxItems);
    List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT id, asset_blob_id
        FROM asset
        WHERE repository_id = ?
          AND id > ?
          AND asset_blob_id IS NOT NULL
        ORDER BY id
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """, repositoryId, Math.max(0, afterAssetId), limit);
    int marked = 0;
    long nextAssetId = Math.max(0, afterAssetId);
    for (Map<String, Object> row : rows) {
      long assetId = ((Number) row.get("id")).longValue();
      long blobId = ((Number) row.get("asset_blob_id")).longValue();
      marked += ensureCandidate(assetId, blobId);
      nextAssetId = assetId;
    }
    return new BackfillPage(rows.size(), marked, nextAssetId, rows.size() < limit);
  }

  private int ensureCandidate(long assetId, long assetBlobId) {
    int updated = jdbc.update("""
        UPDATE security_scan_candidate
        SET asset_blob_id = ?, content_generation = content_generation + 1,
            changed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
        WHERE asset_id = ?
          AND (asset_blob_id IS NULL OR asset_blob_id <> ?)
        """, assetBlobId, assetId, assetBlobId);
    if (updated == 1) return 1;
    boolean inserted = JdbcInserts.tryUpdate(jdbc, """
        INSERT INTO security_scan_candidate
          (asset_id, asset_blob_id, content_generation, enqueued_generation, changed_at, updated_at)
        VALUES (?, ?, 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, ps -> {
      ps.setLong(1, assetId);
      ps.setLong(2, assetBlobId);
    });
    if (inserted) return 1;
    return jdbc.update("""
        UPDATE security_scan_candidate
        SET asset_blob_id = ?, content_generation = content_generation + 1,
            changed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
        WHERE asset_id = ?
          AND (asset_blob_id IS NULL OR asset_blob_id <> ?)
        """, assetBlobId, assetId, assetBlobId);
  }

  @Override
  @Transactional
  public long createTask(TaskDraft task) {
    Instant requestedAt = requiredNow(task.requestedAt());
    byte[] subjectHash = PersistenceHashes.sha256(task.subjectKey());
    String snapshot = task.requestedScannerSnapshotId() == null
        ? "" : task.requestedScannerSnapshotId().toString();
    byte[] dedupeKey = PersistenceHashes.sha256(
        task.subjectKind().name(),
        task.subjectKey(),
        Long.toString(task.contentGeneration()),
        Long.toString(task.profileId()),
        Long.toString(task.profileRevision()),
        task.stage().name(),
        snapshot,
        task.requestUuid());
    byte[] idempotencyHash = blank(task.idempotencyKey())
        ? null : PersistenceHashes.sha256(task.idempotencyKey());

    OptionalLong inserted = JdbcInserts.tryInsert(jdbc, """
        INSERT INTO security_scan_task
          (repository_id, asset_id, subject_kind, subject_key, subject_key_hash,
           content_generation, profile_id, profile_revision, requested_scanner_snapshot_id,
           stage, request_reason, priority, status, attempts, max_attempts, next_attempt_at,
           requested_by, request_uuid, idempotency_key_hash, requested_at, created_at, updated_at,
           dedupe_key)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      int index = 1;
      ps.setLong(index++, task.repositoryId());
      setNullableLong(ps, index++, task.assetId());
      ps.setString(index++, task.subjectKind().name());
      ps.setString(index++, task.subjectKey());
      ps.setBytes(index++, subjectHash);
      ps.setLong(index++, task.contentGeneration());
      ps.setLong(index++, task.profileId());
      ps.setLong(index++, task.profileRevision());
      setNullableLong(ps, index++, task.requestedScannerSnapshotId());
      ps.setString(index++, task.stage().name());
      ps.setString(index++, task.requestReason().name());
      ps.setInt(index++, task.priority());
      ps.setString(index++, TaskStatus.PENDING.name());
      ps.setInt(index++, Math.max(1, task.maxAttempts()));
      ps.setTimestamp(index++, nullableTimestamp(requestedAt));
      ps.setString(index++, task.requestedBy());
      ps.setString(index++, task.requestUuid());
      ps.setBytes(index++, idempotencyHash);
      ps.setTimestamp(index++, nullableTimestamp(requestedAt));
      ps.setTimestamp(index++, nullableTimestamp(requestedAt));
      ps.setTimestamp(index++, nullableTimestamp(requestedAt));
      ps.setBytes(index, dedupeKey);
    });
    if (inserted.isPresent()) {
      return inserted.getAsLong();
    }
    if (idempotencyHash != null) {
      List<Long> ids = jdbc.queryForList("""
          SELECT id FROM security_scan_task
          WHERE repository_id = ? AND idempotency_key_hash = ?
          """, Long.class, task.repositoryId(), idempotencyHash);
      if (!ids.isEmpty()) return ids.getFirst();
    }
    return jdbc.queryForObject(
        "SELECT id FROM security_scan_task WHERE dedupe_key = ?",
        Long.class,
        dedupeKey);
  }

  @Override
  public Optional<ScanTask> findTask(long taskId) {
    return jdbc.query("SELECT * FROM security_scan_task WHERE id = ?", taskMapper, taskId)
        .stream().findFirst();
  }

  @Override
  public List<ScanTask> listTasks(
      Long repositoryId, TaskStatus status, long afterId, int maxItems) {
    List<Object> args = new ArrayList<>();
    StringBuilder sql = new StringBuilder("SELECT * FROM security_scan_task WHERE id > ?");
    args.add(Math.max(0, afterId));
    if (repositoryId != null) {
      sql.append(" AND repository_id = ?");
      args.add(repositoryId);
    }
    if (status != null) {
      sql.append(" AND status = ?");
      args.add(status.name());
    }
    sql.append(" ORDER BY id LIMIT ?");
    args.add(safeLimit(maxItems));
    return jdbc.query(sql.toString(), taskMapper, args.toArray());
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public List<ScanTask> claimTasks(
      String workerId, Instant now, Instant leaseUntil, int maxItems) {
    if (blank(workerId)) throw new IllegalArgumentException("workerId is required");
    if (leaseUntil == null || now == null || !leaseUntil.isAfter(now)) {
      throw new IllegalArgumentException("leaseUntil must be after now");
    }
    List<Long> ids = jdbc.queryForList("""
        SELECT id
        FROM security_scan_task
        WHERE attempts < max_attempts
          AND (
            (status IN ('PENDING', 'RETRY_WAIT') AND next_attempt_at <= ?)
            OR (status = 'RUNNING' AND lease_until < ?)
          )
        ORDER BY priority DESC, requested_at, id
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """, Long.class, nullableTimestamp(now), nullableTimestamp(now), safeLimit(maxItems));
    List<ScanTask> claimed = new ArrayList<>(ids.size());
    for (Long id : ids) {
      String token = UUID.randomUUID().toString();
      int updated = jdbc.update("""
          UPDATE security_scan_task
          SET status = 'RUNNING', attempts = attempts + 1, claimed_by = ?, lease_token = ?,
              lease_until = ?, last_heartbeat_at = ?, started_at = COALESCE(started_at, ?),
              updated_at = ?
          WHERE id = ?
          """,
          workerId,
          token,
          nullableTimestamp(leaseUntil),
          nullableTimestamp(now),
          nullableTimestamp(now),
          nullableTimestamp(now),
          id);
      if (updated == 1) {
        claimed.add(findTask(id).orElseThrow());
      }
    }
    return List.copyOf(claimed);
  }

  @Override
  public boolean heartbeatTask(
      long taskId, String leaseToken, Instant leaseUntil, Instant heartbeatAt) {
    return jdbc.update("""
        UPDATE security_scan_task
        SET lease_until = ?, last_heartbeat_at = ?, updated_at = ?
        WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
        """,
        nullableTimestamp(leaseUntil),
        nullableTimestamp(heartbeatAt),
        nullableTimestamp(heartbeatAt),
        taskId,
        leaseToken) == 1;
  }

  @Override
  public boolean completeTask(long taskId, String leaseToken, Instant completedAt) {
    return terminalTask(
        taskId, leaseToken, TaskStatus.SUCCEEDED, null, null, completedAt);
  }

  @Override
  public boolean retryTask(
      long taskId,
      String leaseToken,
      Instant nextAttemptAt,
      String errorCode,
      String errorSummary,
      Instant updatedAt) {
    return jdbc.update("""
        UPDATE security_scan_task
        SET status = 'RETRY_WAIT', next_attempt_at = ?, claimed_by = NULL, lease_token = NULL,
            lease_until = NULL, last_heartbeat_at = NULL, last_error_code = ?,
            last_error_summary = ?, updated_at = ?
        WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
        """,
        nullableTimestamp(nextAttemptAt),
        errorCode,
        truncate(errorSummary, 2048),
        nullableTimestamp(updatedAt),
        taskId,
        leaseToken) == 1;
  }

  @Override
  public boolean failTask(
      long taskId,
      String leaseToken,
      String errorCode,
      String errorSummary,
      Instant completedAt) {
    return terminalTask(
        taskId, leaseToken, TaskStatus.FAILED, errorCode, errorSummary, completedAt);
  }

  private boolean terminalTask(
      long taskId,
      String leaseToken,
      TaskStatus status,
      String errorCode,
      String errorSummary,
      Instant completedAt) {
    return jdbc.update("""
        UPDATE security_scan_task
        SET status = ?, claimed_by = NULL, lease_token = NULL, lease_until = NULL,
            last_heartbeat_at = NULL, last_error_code = ?, last_error_summary = ?,
            finished_at = ?, updated_at = ?
        WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
        """,
        status.name(),
        errorCode,
        truncate(errorSummary, 2048),
        nullableTimestamp(completedAt),
        nullableTimestamp(completedAt),
        taskId,
        leaseToken) == 1;
  }

  @Override
  public boolean cancelTask(long taskId, Instant cancelledAt) {
    return jdbc.update("""
        UPDATE security_scan_task
        SET status = 'CANCELLED', claimed_by = NULL, lease_token = NULL, lease_until = NULL,
            last_heartbeat_at = NULL, finished_at = ?, updated_at = ?
        WHERE id = ? AND status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')
        """, nullableTimestamp(cancelledAt), nullableTimestamp(cancelledAt), taskId) == 1;
  }

  @Override
  public boolean cancelClaimedTask(long taskId, String leaseToken, Instant cancelledAt) {
    return jdbc.update("""
        UPDATE security_scan_task
        SET status = 'CANCELLED', claimed_by = NULL, lease_token = NULL, lease_until = NULL,
            last_heartbeat_at = NULL, finished_at = ?, updated_at = ?
        WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
        """,
        nullableTimestamp(cancelledAt),
        nullableTimestamp(cancelledAt),
        taskId,
        leaseToken) == 1;
  }

  @Override
  public boolean requeueTask(long taskId, Instant requestedAt, String requestedBy) {
    return jdbc.update("""
        UPDATE security_scan_task
        SET status = 'PENDING', attempts = 0, next_attempt_at = ?, claimed_by = NULL,
            lease_token = NULL, lease_until = NULL, last_heartbeat_at = NULL,
            last_error_code = NULL, last_error_summary = NULL, requested_by = ?,
            requested_at = ?, started_at = NULL, finished_at = NULL, updated_at = ?
        WHERE id = ? AND status IN ('FAILED', 'CANCELLED')
        """,
        nullableTimestamp(requestedAt),
        requestedBy,
        nullableTimestamp(requestedAt),
        nullableTimestamp(requestedAt),
        taskId) == 1;
  }

  @Override
  @Transactional
  public ScannerSnapshot insertSnapshotOrFindExisting(ScannerSnapshot snapshot) {
    OptionalLong inserted = JdbcInserts.tryInsert(jdbc, """
        INSERT INTO security_scanner_snapshot
          (adapter_name, adapter_api_version, engine_name, engine_version,
           vulnerability_database_revision, vulnerability_database_updated_at,
           capability_digest, snapshot_fingerprint, observed_at, ready, details_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setString(1, snapshot.adapterName());
      ps.setString(2, snapshot.adapterApiVersion());
      ps.setString(3, snapshot.engineName());
      ps.setString(4, snapshot.engineVersion());
      ps.setString(5, snapshot.vulnerabilityDatabaseRevision());
      ps.setTimestamp(6, nullableTimestamp(snapshot.vulnerabilityDatabaseUpdatedAt()));
      ps.setString(7, snapshot.capabilityDigest());
      ps.setString(8, snapshot.snapshotFingerprint());
      ps.setTimestamp(9, nullableTimestamp(snapshot.observedAt()));
      ps.setBoolean(10, snapshot.ready());
      json.bind(ps, 11, snapshot.details());
    });
    if (inserted.isPresent()) {
      return snapshotWithId(inserted.getAsLong());
    }
    jdbc.update("""
        UPDATE security_scanner_snapshot
        SET observed_at = ?, vulnerability_database_updated_at = ?, ready = ?, details_json = ?
        WHERE snapshot_fingerprint = ?
        """,
        nullableTimestamp(snapshot.observedAt()),
        nullableTimestamp(snapshot.vulnerabilityDatabaseUpdatedAt()),
        snapshot.ready(),
        json.serializedParameter(json.writeValue(snapshot.details())),
        snapshot.snapshotFingerprint());
    return jdbc.query("""
        SELECT * FROM security_scanner_snapshot WHERE snapshot_fingerprint = ?
        """, snapshotMapper, snapshot.snapshotFingerprint()).stream().findFirst().orElseThrow();
  }

  private ScannerSnapshot snapshotWithId(long id) {
    return jdbc.query(
        "SELECT * FROM security_scanner_snapshot WHERE id = ?",
        snapshotMapper,
        id).stream().findFirst().orElseThrow();
  }

  @Override
  public Optional<ScannerSnapshot> findScannerSnapshot(long snapshotId) {
    return jdbc.query(
        "SELECT * FROM security_scanner_snapshot WHERE id = ?",
        snapshotMapper,
        snapshotId).stream().findFirst();
  }

  @Override
  public Optional<ScannerSnapshot> latestScannerSnapshot() {
    return jdbc.query("""
        SELECT * FROM security_scanner_snapshot ORDER BY observed_at DESC, id DESC LIMIT 1
        """, snapshotMapper).stream().findFirst();
  }

  @Override
  @Transactional
  public Sbom insertSbomOrFindExisting(Sbom sbom) {
    byte[] subjectHash = sbom.subjectIdentityHash() == null
        ? PersistenceHashes.sha256(sbom.subjectIdentity()) : sbom.subjectIdentityHash();
    OptionalLong inserted = JdbcInserts.tryInsert(jdbc, """
        INSERT INTO security_sbom
          (subject_kind, subject_identity, subject_identity_hash, catalog_engine,
           catalog_engine_version, catalog_configuration_digest, catalog_fingerprint,
           document_blob_id, document_sha256, spec_name, spec_version, component_count,
           dependency_count, inventory_complete, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setString(1, sbom.subjectKind().name());
      ps.setString(2, sbom.subjectIdentity());
      ps.setBytes(3, subjectHash);
      ps.setString(4, sbom.catalogEngine());
      ps.setString(5, sbom.catalogEngineVersion());
      ps.setString(6, sbom.catalogConfigurationDigest());
      ps.setString(7, sbom.catalogFingerprint());
      ps.setLong(8, sbom.documentBlobId());
      ps.setString(9, sbom.documentSha256());
      ps.setString(10, sbom.specName());
      ps.setString(11, sbom.specVersion());
      ps.setInt(12, sbom.componentCount());
      ps.setInt(13, sbom.dependencyCount());
      ps.setBoolean(14, sbom.inventoryComplete());
      ps.setTimestamp(15, nullableTimestamp(sbom.createdAt()));
    });
    if (inserted.isPresent()) return findSbom(inserted.getAsLong()).orElseThrow();
    return findSbomByCatalogFingerprint(sbom.catalogFingerprint()).orElseThrow();
  }

  @Override
  public Optional<Sbom> findSbom(long sbomId) {
    return jdbc.query("SELECT * FROM security_sbom WHERE id = ?", sbomMapper, sbomId)
        .stream().findFirst();
  }

  @Override
  public Optional<Sbom> findSbomByCatalogFingerprint(String catalogFingerprint) {
    return jdbc.query("""
        SELECT * FROM security_sbom WHERE catalog_fingerprint = ?
        """, sbomMapper, catalogFingerprint).stream().findFirst();
  }

  @Override
  public Optional<Sbom> findReusableSbom(
      SubjectKind subjectKind,
      byte[] subjectIdentityHash,
      String catalogEngine,
      String catalogEngineVersion,
      String catalogConfigurationDigest) {
    return jdbc.query("""
        SELECT *
        FROM security_sbom
        WHERE subject_kind = ?
          AND subject_identity_hash = ?
          AND catalog_engine = ?
          AND catalog_engine_version = ?
          AND catalog_configuration_digest = ?
        ORDER BY created_at DESC, id DESC
        LIMIT 1
        """,
        sbomMapper,
        subjectKind.name(),
        subjectIdentityHash,
        catalogEngine,
        catalogEngineVersion,
        catalogConfigurationDigest).stream().findFirst();
  }

  @Override
  @Transactional
  public int insertSbomComponents(long sbomId, List<SbomComponent> components) {
    int inserted = 0;
    for (SbomComponent component : components == null ? List.<SbomComponent>of() : components) {
      byte[] refHash = component.componentRefHash() == null
          ? PersistenceHashes.sha256(component.componentRef()) : component.componentRefHash();
      byte[] purlHash = blank(component.packageUrl())
          ? null : (component.packageUrlHash() == null
              ? PersistenceHashes.sha256(component.packageUrl()) : component.packageUrlHash());
      OptionalLong row = JdbcInserts.tryInsert(jdbc, """
          INSERT INTO security_sbom_component
            (sbom_id, component_ref, component_ref_hash, package_url, package_url_hash,
             component_type, namespace, name, version, directness, locations_json,
             licenses_json, properties_json)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """, ps -> {
        ps.setLong(1, sbomId);
        ps.setString(2, component.componentRef());
        ps.setBytes(3, refHash);
        ps.setString(4, component.packageUrl());
        ps.setBytes(5, purlHash);
        ps.setString(6, component.type());
        ps.setString(7, component.namespace());
        ps.setString(8, component.name());
        ps.setString(9, component.version());
        ps.setString(10, component.directness());
        json.bindSerialized(ps, 11, json.writeValue(component.locations()));
        json.bindSerialized(ps, 12, json.writeValue(component.licenses()));
        json.bind(ps, 13, component.properties());
      });
      if (row.isPresent()) inserted++;
    }
    return inserted;
  }

  @Override
  public List<SbomComponent> listSbomComponents(long sbomId, long afterId, int maxItems) {
    return jdbc.query("""
        SELECT * FROM security_sbom_component
        WHERE sbom_id = ? AND id > ?
        ORDER BY id
        LIMIT ?
        """, componentMapper, sbomId, Math.max(0, afterId), safeLimit(maxItems));
  }

  @Override
  @Transactional
  public ScanRun insertRunOrFindExisting(ScanRun run) {
    OptionalLong inserted = JdbcInserts.tryInsert(jdbc, """
        INSERT INTO security_scan_run
          (task_id, sbom_id, scanner_snapshot_id, match_configuration_digest,
           match_fingerprint, status, scan_completeness, raw_report_blob_id,
           raw_report_sha256, finding_count, fixable_finding_count, critical_count,
           high_count, medium_count, low_count, unknown_count, max_severity,
           started_at, completed_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      setNullableLong(ps, 1, run.taskId());
      ps.setLong(2, run.sbomId());
      ps.setLong(3, run.scannerSnapshotId());
      ps.setString(4, run.matchConfigurationDigest());
      ps.setString(5, run.matchFingerprint());
      ps.setString(6, run.status().name());
      ps.setString(7, run.scanCompleteness().name());
      ps.setLong(8, run.rawReportBlobId());
      ps.setString(9, run.rawReportSha256());
      ps.setInt(10, run.findingCount());
      ps.setInt(11, run.fixableFindingCount());
      ps.setInt(12, run.criticalCount());
      ps.setInt(13, run.highCount());
      ps.setInt(14, run.mediumCount());
      ps.setInt(15, run.lowCount());
      ps.setInt(16, run.unknownCount());
      ps.setString(17, run.maxSeverity().name());
      ps.setTimestamp(18, nullableTimestamp(run.startedAt()));
      ps.setTimestamp(19, nullableTimestamp(run.completedAt()));
      ps.setTimestamp(20, nullableTimestamp(run.createdAt()));
    });
    if (inserted.isPresent()) return findRun(inserted.getAsLong()).orElseThrow();
    return findRunByMatchFingerprint(run.matchFingerprint()).orElseThrow();
  }

  @Override
  public Optional<ScanRun> findRun(long runId) {
    return jdbc.query("SELECT * FROM security_scan_run WHERE id = ?", runMapper, runId)
        .stream().findFirst();
  }

  @Override
  public Optional<ScanRun> findRunByMatchFingerprint(String matchFingerprint) {
    return jdbc.query("""
        SELECT * FROM security_scan_run WHERE match_fingerprint = ?
        """, runMapper, matchFingerprint).stream().findFirst();
  }

  @Override
  public List<ScanRun> listRuns(Long repositoryId, long afterId, int maxItems) {
    StringBuilder sql = new StringBuilder("""
        SELECT sr.*
        FROM security_scan_run sr
        WHERE sr.id > ?
        """);
    List<Object> args = new ArrayList<>();
    args.add(Math.max(0, afterId));
    if (repositoryId != null) {
      sql.append("""
           AND EXISTS (
             SELECT 1 FROM security_scan_run_subject s
             WHERE s.scan_run_id = sr.id AND s.repository_id = ?
           )
          """);
      args.add(repositoryId);
    }
    sql.append(" ORDER BY sr.id LIMIT ?");
    args.add(safeLimit(maxItems));
    return jdbc.query(sql.toString(), runMapper, args.toArray());
  }

  @Override
  public void associateRun(
      long scanRunId,
      long repositoryId,
      long assetId,
      long profileId,
      long contentGeneration,
      Instant associatedAt) {
    JdbcInserts.tryUpdate(jdbc, """
        INSERT INTO security_scan_run_subject
          (scan_run_id, repository_id, asset_id, profile_id, content_generation, associated_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setLong(1, scanRunId);
      ps.setLong(2, repositoryId);
      ps.setLong(3, assetId);
      ps.setLong(4, profileId);
      ps.setLong(5, contentGeneration);
      ps.setTimestamp(6, nullableTimestamp(requiredNow(associatedAt)));
    });
  }

  @Override
  public List<Long> listRepositoryIdsForRun(long scanRunId) {
    return jdbc.queryForList("""
        SELECT DISTINCT repository_id
        FROM security_scan_run_subject
        WHERE scan_run_id = ?
        ORDER BY repository_id
        """, Long.class, scanRunId);
  }

  @Override
  public List<Long> listRepositoryIdsForSbom(long sbomId) {
    return jdbc.queryForList("""
        SELECT DISTINCT s.repository_id
        FROM security_scan_run_subject s
        JOIN security_scan_run sr ON sr.id = s.scan_run_id
        WHERE sr.sbom_id = ?
        ORDER BY s.repository_id
        """, Long.class, sbomId);
  }

  @Override
  @Transactional
  public int insertFindings(long scanRunId, List<ScanFinding> findings) {
    int inserted = 0;
    for (ScanFinding finding : findings == null ? List.<ScanFinding>of() : findings) {
      byte[] keyHash = finding.findingKeyHash() == null
          ? PersistenceHashes.sha256(finding.findingKey()) : finding.findingKeyHash();
      OptionalLong row = JdbcInserts.tryInsert(jdbc, """
          INSERT INTO security_scan_finding
            (scan_run_id, finding_key, finding_key_hash, advisory_id, aliases_json,
             data_source, package_url, package_name, installed_version, fixed_versions_json,
             severity, severity_source, cvss_vector, cvss_score, title, description,
             primary_url, locations_json, source_status, created_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """, ps -> {
        ps.setLong(1, scanRunId);
        ps.setString(2, finding.findingKey());
        ps.setBytes(3, keyHash);
        ps.setString(4, finding.advisoryId());
        json.bindSerialized(ps, 5, json.writeValue(finding.aliases()));
        ps.setString(6, finding.dataSource());
        ps.setString(7, finding.packageUrl());
        ps.setString(8, finding.packageName());
        ps.setString(9, finding.installedVersion());
        json.bindSerialized(ps, 10, json.writeValue(finding.fixedVersions()));
        ps.setString(11, finding.severity().name());
        ps.setString(12, finding.severitySource());
        ps.setString(13, finding.cvssVector());
        if (finding.cvssScore() == null) {
          ps.setNull(14, java.sql.Types.NUMERIC);
        } else {
          ps.setDouble(14, finding.cvssScore());
        }
        ps.setString(15, truncate(finding.title(), 1024));
        ps.setString(16, truncate(finding.description(), 65535));
        ps.setString(17, safeUrl(finding.primaryUrl()));
        json.bindSerialized(ps, 18, json.writeValue(finding.locations()));
        ps.setString(19, finding.sourceStatus());
        ps.setTimestamp(20, nullableTimestamp(finding.createdAt()));
      });
      if (row.isPresent()) inserted++;
    }
    return inserted;
  }

  @Override
  public List<ScanFinding> listFindings(
      Long repositoryId, Long scanRunId, Severity severity, long afterId, int maxItems) {
    StringBuilder sql = new StringBuilder("""
        SELECT f.*
        FROM security_scan_finding f
        WHERE f.id > ?
        """);
    List<Object> args = new ArrayList<>();
    args.add(Math.max(0, afterId));
    if (repositoryId != null) {
      sql.append("""
           AND EXISTS (
             SELECT 1 FROM security_scan_run_subject s
             WHERE s.scan_run_id = f.scan_run_id AND s.repository_id = ?
           )
          """);
      args.add(repositoryId);
    }
    if (scanRunId != null) {
      sql.append(" AND f.scan_run_id = ?");
      args.add(scanRunId);
    }
    if (severity != null) {
      sql.append(" AND f.severity = ?");
      args.add(severity.name());
    }
    sql.append(" ORDER BY f.id LIMIT ?");
    args.add(safeLimit(maxItems));
    return jdbc.query(sql.toString(), findingMapper, args.toArray());
  }

  @Override
  public Optional<AssetSecurityState> findAssetState(long assetId, long profileId) {
    return jdbc.query("""
        SELECT * FROM asset_security_state WHERE asset_id = ? AND profile_id = ?
        """, stateMapper, assetId, profileId).stream().findFirst();
  }

  @Override
  public List<AssetSecurityState> listAssetStates(long assetId) {
    return jdbc.query("""
        SELECT * FROM asset_security_state
        WHERE asset_id = ?
        ORDER BY profile_id
        """, stateMapper, assetId);
  }

  @Override
  public List<AssetSecurityState> listAssetStatesNeedingSnapshot(
      long profileId, long scannerSnapshotId, long afterAssetId, int maxItems) {
    return jdbc.query("""
        SELECT s.*
        FROM asset_security_state s
        JOIN asset a ON a.id = s.asset_id
        JOIN repository_security_scan_config c
          ON c.repository_id = a.repository_id
         AND c.profile_id = s.profile_id
         AND c.enabled = TRUE
        JOIN security_scan_candidate candidate
          ON candidate.asset_id = s.asset_id
         AND candidate.content_generation = s.content_generation
        LEFT JOIN security_scan_run run ON run.id = s.latest_scan_run_id
        WHERE s.profile_id = ?
          AND s.asset_id > ?
          AND s.latest_scan_run_id IS NOT NULL
          AND (run.scanner_snapshot_id IS NULL OR run.scanner_snapshot_id <> ?)
        ORDER BY s.asset_id
        LIMIT ?
        """, stateMapper, profileId, Math.max(0, afterAssetId), scannerSnapshotId,
        safeLimit(maxItems));
  }

  @Override
  @Transactional
  public AssetSecurityState upsertAssetStateIfCurrent(AssetSecurityState state) {
    int updated = updateAssetState(state);
    if (updated == 0 && candidateGenerationMatches(state.assetId(), state.contentGeneration())) {
      boolean inserted = JdbcInserts.tryUpdate(jdbc, """
          INSERT INTO asset_security_state
            (asset_id, profile_id, content_generation, subject_identity_hash,
             latest_scan_run_id, scan_state, scan_completeness, inventory_complete,
             max_severity, finding_counts_json, policy_id, policy_revision,
             policy_decision, policy_reason_code, stale_at, last_evaluated_at, version)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
          """, ps -> {
        ps.setLong(1, state.assetId());
        ps.setLong(2, state.profileId());
        ps.setLong(3, state.contentGeneration());
        ps.setBytes(4, state.subjectIdentityHash());
        setNullableLong(ps, 5, state.latestScanRunId());
        ps.setString(6, state.scanState().name());
        ps.setString(7, state.scanCompleteness().name());
        ps.setBoolean(8, state.inventoryComplete());
        ps.setString(9, state.maxSeverity().name());
        json.bindSerialized(ps, 10, json.writeValue(state.findingCounts()));
        setNullableLong(ps, 11, state.policyId());
        setNullableLong(ps, 12, state.policyRevision());
        ps.setString(13, state.policyDecision().name());
        ps.setString(14, state.policyReasonCode());
        ps.setTimestamp(15, nullableTimestamp(state.staleAt()));
        ps.setTimestamp(16, nullableTimestamp(state.lastEvaluatedAt()));
      });
      if (!inserted) {
        updateAssetState(state);
      }
    }
    return findAssetState(state.assetId(), state.profileId()).orElseThrow(
        () -> new IllegalStateException("Asset content generation changed before scan finalization"));
  }

  private int updateAssetState(AssetSecurityState state) {
    return jdbc.update("""
        UPDATE asset_security_state s
        SET content_generation = ?, subject_identity_hash = ?, latest_scan_run_id = ?,
            scan_state = ?, scan_completeness = ?, inventory_complete = ?, max_severity = ?,
            finding_counts_json = ?, policy_id = ?, policy_revision = ?, policy_decision = ?,
            policy_reason_code = ?, stale_at = ?, last_evaluated_at = ?, version = version + 1
        WHERE s.asset_id = ? AND s.profile_id = ?
          AND s.content_generation <= ?
          AND EXISTS (
            SELECT 1 FROM security_scan_candidate c
            WHERE c.asset_id = s.asset_id AND c.content_generation = ?
          )
        """,
        state.contentGeneration(),
        state.subjectIdentityHash(),
        state.latestScanRunId(),
        state.scanState().name(),
        state.scanCompleteness().name(),
        state.inventoryComplete(),
        state.maxSeverity().name(),
        json.serializedParameter(json.writeValue(state.findingCounts())),
        state.policyId(),
        state.policyRevision(),
        state.policyDecision().name(),
        state.policyReasonCode(),
        nullableTimestamp(state.staleAt()),
        nullableTimestamp(state.lastEvaluatedAt()),
        state.assetId(),
        state.profileId(),
        state.contentGeneration(),
        state.contentGeneration());
  }

  @Override
  public Optional<AssetPolicyState> findAssetPolicyState(
      long assetId, long profileId, long repositoryId) {
    return jdbc.query("""
        SELECT * FROM asset_security_policy_state
        WHERE asset_id = ? AND profile_id = ? AND repository_id = ?
        """, policyStateMapper, assetId, profileId, repositoryId).stream().findFirst();
  }

  @Override
  @Transactional
  public AssetPolicyState upsertAssetPolicyStateIfCurrent(AssetPolicyState state) {
    int updated = updateAssetPolicyState(state);
    if (updated == 0 && candidateGenerationMatches(state.assetId(), state.contentGeneration())) {
      boolean inserted = JdbcInserts.tryUpdate(jdbc, """
          INSERT INTO asset_security_policy_state
            (asset_id, profile_id, repository_id, content_generation, latest_scan_run_id,
             policy_id, policy_revision, config_revision, policy_decision, policy_reason_code,
             waived_findings, stale_at, next_waiver_expiry, last_evaluated_at, version)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
          """, ps -> {
        ps.setLong(1, state.assetId());
        ps.setLong(2, state.profileId());
        ps.setLong(3, state.repositoryId());
        ps.setLong(4, state.contentGeneration());
        setNullableLong(ps, 5, state.latestScanRunId());
        setNullableLong(ps, 6, state.policyId());
        setNullableLong(ps, 7, state.policyRevision());
        ps.setLong(8, state.configRevision());
        ps.setString(9, state.policyDecision().name());
        ps.setString(10, state.policyReasonCode());
        ps.setInt(11, Math.max(0, state.waivedFindings()));
        ps.setTimestamp(12, nullableTimestamp(state.staleAt()));
        ps.setTimestamp(13, nullableTimestamp(state.nextWaiverExpiry()));
        ps.setTimestamp(14, nullableTimestamp(state.lastEvaluatedAt()));
      });
      if (!inserted) updateAssetPolicyState(state);
    }
    return findAssetPolicyState(state.assetId(), state.profileId(), state.repositoryId())
        .orElseThrow(() ->
            new IllegalStateException("Asset content generation changed before policy evaluation"));
  }

  private int updateAssetPolicyState(AssetPolicyState state) {
    return jdbc.update("""
        UPDATE asset_security_policy_state s
        SET content_generation = ?, latest_scan_run_id = ?, policy_id = ?,
            policy_revision = ?, config_revision = ?, policy_decision = ?,
            policy_reason_code = ?, waived_findings = ?, stale_at = ?,
            next_waiver_expiry = ?, last_evaluated_at = ?, version = version + 1
        WHERE s.asset_id = ? AND s.profile_id = ? AND s.repository_id = ?
          AND EXISTS (
            SELECT 1 FROM security_scan_candidate c
            WHERE c.asset_id = s.asset_id AND c.content_generation = ?
          )
        """,
        state.contentGeneration(),
        state.latestScanRunId(),
        state.policyId(),
        state.policyRevision(),
        state.configRevision(),
        state.policyDecision().name(),
        state.policyReasonCode(),
        Math.max(0, state.waivedFindings()),
        nullableTimestamp(state.staleAt()),
        nullableTimestamp(state.nextWaiverExpiry()),
        nullableTimestamp(state.lastEvaluatedAt()),
        state.assetId(),
        state.profileId(),
        state.repositoryId(),
        state.contentGeneration());
  }

  @Override
  public List<PolicyEvaluationTarget> listPolicyEvaluationTargets(
      long sourceRepositoryId,
      long contextRepositoryId,
      long profileId,
      long configRevision,
      Long policyId,
      Long policyRevision,
      long afterAssetId,
      Instant evaluatedAt,
      int maxItems) {
    StringBuilder sql = new StringBuilder("""
        SELECT a.id AS asset_id,
               a.repository_id AS source_repository_id,
               COALESCE(c.content_generation, 0) AS content_generation,
               s.content_generation AS state_content_generation,
               s.latest_scan_run_id,
               s.scan_state,
               COALESCE(ps.version, 0) AS policy_state_version,
               ps.next_waiver_expiry
        FROM asset a
        LEFT JOIN security_scan_candidate c ON c.asset_id = a.id
        LEFT JOIN asset_security_state s
          ON s.asset_id = a.id AND s.profile_id = ?
        LEFT JOIN asset_security_policy_state ps
          ON ps.asset_id = a.id
         AND ps.profile_id = ?
         AND ps.repository_id = ?
        WHERE a.repository_id = ?
          AND a.id > ?
          AND (
            c.asset_id IS NULL
            OR s.asset_id IS NULL
            OR s.content_generation <> c.content_generation
            OR (
              s.scan_state = 'COMPLETE'
              AND (
                ps.asset_id IS NULL
                OR ps.content_generation <> c.content_generation
                OR ps.latest_scan_run_id IS NULL
                OR ps.latest_scan_run_id <> s.latest_scan_run_id
                OR ps.config_revision <> ?
        """);
    List<Object> args = new ArrayList<>();
    args.add(profileId);
    args.add(profileId);
    args.add(contextRepositoryId);
    args.add(sourceRepositoryId);
    args.add(Math.max(0, afterAssetId));
    args.add(configRevision);
    if (policyId == null) {
      sql.append("""
                OR ps.policy_id IS NOT NULL
                OR ps.policy_revision IS NOT NULL
          """);
    } else {
      sql.append("""
                OR ps.policy_id IS NULL
                OR ps.policy_id <> ?
                OR ps.policy_revision IS NULL
                OR ps.policy_revision <> ?
          """);
      args.add(policyId);
      args.add(policyRevision == null ? 0 : policyRevision);
    }
    sql.append("""
                OR (ps.next_waiver_expiry IS NOT NULL AND ps.next_waiver_expiry <= ?)
              )
            )
          )
        ORDER BY a.id
        LIMIT ?
        """);
    args.add(nullableTimestamp(requiredNow(evaluatedAt)));
    args.add(safeLimit(maxItems));
    return jdbc.query(sql.toString(), (rs, rowNum) -> new PolicyEvaluationTarget(
        rs.getLong("asset_id"),
        rs.getLong("source_repository_id"),
        rs.getLong("content_generation"),
        nullableLong(rs, "state_content_generation"),
        nullableLong(rs, "latest_scan_run_id"),
        rs.getString("scan_state") == null
            ? null : enumValue(ScanState.class, rs.getString("scan_state")),
        rs.getLong("policy_state_version"),
        nullableInstant(rs, "next_waiver_expiry")), args.toArray());
  }

  private boolean candidateGenerationMatches(long assetId, long generation) {
    Long count = jdbc.queryForObject("""
        SELECT COUNT(*) FROM security_scan_candidate
        WHERE asset_id = ? AND content_generation = ?
        """, Long.class, assetId, generation);
    return count != null && count == 1;
  }

  @Override
  public boolean markAssetStateStale(
      long assetId,
      long profileId,
      long expectedScanRunId,
      Instant staleAt) {
    return jdbc.update("""
        UPDATE asset_security_state
        SET scan_state = 'STALE', stale_at = ?, version = version + 1
        WHERE asset_id = ? AND profile_id = ? AND latest_scan_run_id = ?
        """, nullableTimestamp(staleAt), assetId, profileId, expectedScanRunId) == 1;
  }

  @Override
  public int markStatesStaleForSnapshot(long profileId, Instant staleAt, int maxItems) {
    List<Long> assetIds = jdbc.queryForList("""
        SELECT asset_id FROM asset_security_state
        WHERE profile_id = ? AND scan_state <> 'STALE'
        ORDER BY asset_id
        LIMIT ?
        """, Long.class, profileId, safeLimit(maxItems));
    int changed = 0;
    for (Long assetId : assetIds) {
      changed += jdbc.update("""
          UPDATE asset_security_state
          SET scan_state = 'STALE', stale_at = ?, version = version + 1
          WHERE asset_id = ? AND profile_id = ? AND scan_state <> 'STALE'
          """, nullableTimestamp(staleAt), assetId, profileId);
    }
    return changed;
  }

  @Override
  public List<ScanPolicy> listPolicies() {
    return jdbc.query(
        "SELECT * FROM security_scan_policy ORDER BY name, revision DESC, id DESC",
        policyMapper);
  }

  @Override
  public Optional<ScanPolicy> findPolicy(long policyId) {
    return jdbc.query(
        "SELECT * FROM security_scan_policy WHERE id = ?",
        policyMapper,
        policyId).stream().findFirst();
  }

  @Override
  public ScanPolicy createPolicy(ScanPolicy policy) {
    long id = JdbcInserts.insert(jdbc, """
        INSERT INTO security_scan_policy
          (name, enabled, block_severity, only_fixable, block_unknown_severity,
           require_complete_inventory, max_result_age_seconds, required_platforms_json,
           revision, created_by, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setString(1, policy.name());
      ps.setBoolean(2, policy.enabled());
      ps.setString(3, policy.blockSeverity().name());
      ps.setBoolean(4, policy.onlyFixable());
      ps.setBoolean(5, policy.blockUnknownSeverity());
      ps.setBoolean(6, policy.requireCompleteInventory());
      setNullableLong(ps, 7, policy.maxResultAgeSeconds());
      json.bindSerialized(ps, 8, json.writeValue(policy.requiredPlatforms()));
      ps.setLong(9, Math.max(1, policy.revision()));
      ps.setString(10, policy.createdBy());
      ps.setTimestamp(11, nullableTimestamp(policy.createdAt()));
      ps.setTimestamp(12, nullableTimestamp(policy.updatedAt()));
    });
    return findPolicy(id).orElseThrow();
  }

  @Override
  public ScanWaiver createWaiver(ScanWaiver waiver) {
    long id = JdbcInserts.insert(jdbc, """
        INSERT INTO security_scan_waiver
          (scope_type, repository_id, asset_id, finding_id, advisory_selector,
           package_selector, selector_json, reason, policy_id, policy_revision,
           created_by, approved_by, expires_at, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setString(1, waiver.scopeType());
      setNullableLong(ps, 2, waiver.repositoryId());
      setNullableLong(ps, 3, waiver.assetId());
      setNullableLong(ps, 4, waiver.findingId());
      ps.setString(5, waiver.advisorySelector());
      ps.setString(6, waiver.packageSelector());
      json.bind(ps, 7, waiver.selector());
      ps.setString(8, truncate(waiver.reason(), 2048));
      setNullableLong(ps, 9, waiver.policyId());
      setNullableLong(ps, 10, waiver.policyRevision());
      ps.setString(11, waiver.createdBy());
      ps.setString(12, waiver.approvedBy());
      ps.setTimestamp(13, nullableTimestamp(waiver.expiresAt()));
      ps.setTimestamp(14, nullableTimestamp(waiver.createdAt()));
      ps.setTimestamp(15, nullableTimestamp(waiver.updatedAt()));
    });
    return jdbc.query(
        "SELECT * FROM security_scan_waiver WHERE id = ?",
        waiverMapper,
        id).stream().findFirst().orElseThrow();
  }

  @Override
  public Optional<ScanWaiver> findWaiver(long waiverId) {
    return jdbc.query(
        "SELECT * FROM security_scan_waiver WHERE id = ?",
        waiverMapper,
        waiverId).stream().findFirst();
  }

  @Override
  public List<ScanWaiver> listWaivers(Long repositoryId, long afterId, int maxItems) {
    StringBuilder sql = new StringBuilder(
        "SELECT * FROM security_scan_waiver WHERE id > ?");
    List<Object> args = new ArrayList<>();
    args.add(Math.max(0, afterId));
    if (repositoryId != null) {
      sql.append(" AND (repository_id IS NULL OR repository_id = ?)");
      args.add(repositoryId);
    }
    sql.append(" ORDER BY id LIMIT ?");
    args.add(safeLimit(maxItems));
    return jdbc.query(sql.toString(), waiverMapper, args.toArray());
  }

  @Override
  public List<ScanWaiver> listActiveWaivers(
      long repositoryId, Long assetId, Instant evaluatedAt, int maxItems) {
    return jdbc.query("""
        SELECT *
        FROM security_scan_waiver
        WHERE (repository_id IS NULL OR repository_id = ?)
          AND (asset_id IS NULL OR asset_id = ?)
          AND (expires_at IS NULL OR expires_at > ?)
        ORDER BY id
        LIMIT ?
        """,
        waiverMapper,
        repositoryId,
        assetId,
        nullableTimestamp(evaluatedAt),
        safeLimit(maxItems));
  }

  @Override
  public boolean deleteWaiver(long waiverId) {
    return jdbc.update("DELETE FROM security_scan_waiver WHERE id = ?", waiverId) == 1;
  }

  @Override
  public BackfillJob createBackfillJob(long repositoryId, String createdBy, Instant now) {
    long id = JdbcInserts.insert(jdbc, """
        INSERT INTO security_scan_backfill_job
          (repository_id, status, cursor_asset_id, scanned_assets, marked_assets, attempts,
           created_by, created_at, updated_at)
        VALUES (?, 'PENDING', 0, 0, 0, 0, ?, ?, ?)
        """, ps -> {
      ps.setLong(1, repositoryId);
      ps.setString(2, createdBy);
      ps.setTimestamp(3, nullableTimestamp(now));
      ps.setTimestamp(4, nullableTimestamp(now));
    });
    return findBackfill(id).orElseThrow();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public List<BackfillJob> claimBackfillJobs(
      String workerId, Instant now, Instant leaseUntil, int maxItems) {
    List<Long> ids = jdbc.queryForList("""
        SELECT id
        FROM security_scan_backfill_job
        WHERE status IN ('PENDING', 'RUNNING')
          AND (status = 'PENDING' OR lease_until < ?)
        ORDER BY created_at, id
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """, Long.class, nullableTimestamp(now), safeLimit(maxItems));
    List<BackfillJob> jobs = new ArrayList<>();
    for (Long id : ids) {
      String token = UUID.randomUUID().toString();
      jdbc.update("""
          UPDATE security_scan_backfill_job
          SET status = 'RUNNING', attempts = attempts + 1, claimed_by = ?, lease_token = ?,
              lease_until = ?, updated_at = ?
          WHERE id = ?
          """, workerId, token, nullableTimestamp(leaseUntil), nullableTimestamp(now), id);
      jobs.add(findBackfill(id).orElseThrow());
    }
    return List.copyOf(jobs);
  }

  private Optional<BackfillJob> findBackfill(long id) {
    return jdbc.query(
        "SELECT * FROM security_scan_backfill_job WHERE id = ?",
        backfillMapper,
        id).stream().findFirst();
  }

  @Override
  public boolean updateBackfillProgress(
      long jobId,
      String leaseToken,
      long cursorAssetId,
      long scannedAssets,
      long markedAssets,
      BackfillStatus status,
      String errorSummary,
      Instant updatedAt) {
    boolean terminal = status == BackfillStatus.SUCCEEDED
        || status == BackfillStatus.FAILED
        || status == BackfillStatus.CANCELLED;
    return jdbc.update("""
        UPDATE security_scan_backfill_job
        SET cursor_asset_id = ?, scanned_assets = ?, marked_assets = ?, status = ?,
            last_error_summary = ?, claimed_by = ?, lease_token = ?, lease_until = ?,
            completed_at = ?, updated_at = ?
        WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
        """,
        cursorAssetId,
        scannedAssets,
        markedAssets,
        status.name(),
        truncate(errorSummary, 2048),
        terminal ? null : findBackfill(jobId).map(BackfillJob::claimedBy).orElse(null),
        terminal ? null : leaseToken,
        terminal ? null : nullableTimestamp(updatedAt.plusSeconds(300)),
        terminal ? nullableTimestamp(updatedAt) : null,
        nullableTimestamp(updatedAt),
        jobId,
        leaseToken) == 1;
  }

  @Override
  public ScanSummary summary() {
    return new ScanSummary(
        count("""
            SELECT COUNT(*) FROM security_scan_candidate
            WHERE content_generation > enqueued_generation
            """),
        count("SELECT COUNT(*) FROM security_scan_task WHERE status IN ('PENDING','RETRY_WAIT')"),
        count("SELECT COUNT(*) FROM security_scan_task WHERE status = 'RUNNING'"),
        count("SELECT COUNT(*) FROM security_scan_task WHERE status = 'FAILED'"),
        count("SELECT COUNT(*) FROM asset_security_state WHERE scan_state = 'COMPLETE'"),
        count("SELECT COUNT(*) FROM asset_security_state WHERE scan_state = 'PARTIAL'"),
        count("SELECT COUNT(*) FROM asset_security_state WHERE scan_state = 'STALE'"),
        count("SELECT COUNT(*) FROM asset_security_state WHERE policy_decision <> 'ALLOW'"),
        count("SELECT COUNT(*) FROM security_scan_finding WHERE severity = 'CRITICAL'"),
        count("SELECT COUNT(*) FROM security_scan_finding WHERE severity = 'HIGH'"));
  }

  @Override
  public ScanSummary summary(long repositoryId) {
    return new ScanSummary(
        count("""
            SELECT COUNT(*)
            FROM security_scan_candidate c
            JOIN asset a ON a.id = c.asset_id
            WHERE a.repository_id = ? AND c.content_generation > c.enqueued_generation
            """, repositoryId),
        count("""
            SELECT COUNT(*) FROM security_scan_task
            WHERE repository_id = ? AND status IN ('PENDING','RETRY_WAIT')
            """, repositoryId),
        count("""
            SELECT COUNT(*) FROM security_scan_task
            WHERE repository_id = ? AND status = 'RUNNING'
            """, repositoryId),
        count("""
            SELECT COUNT(*) FROM security_scan_task
            WHERE repository_id = ? AND status = 'FAILED'
            """, repositoryId),
        countState(repositoryId, "COMPLETE", null),
        countState(repositoryId, "PARTIAL", null),
        countState(repositoryId, "STALE", null),
        countState(repositoryId, null, "ALLOW"),
        count("""
            SELECT COUNT(DISTINCT f.id)
            FROM security_scan_finding f
            JOIN security_scan_run_subject s ON s.scan_run_id = f.scan_run_id
            WHERE s.repository_id = ? AND f.severity = 'CRITICAL'
            """, repositoryId),
        count("""
            SELECT COUNT(DISTINCT f.id)
            FROM security_scan_finding f
            JOIN security_scan_run_subject s ON s.scan_run_id = f.scan_run_id
            WHERE s.repository_id = ? AND f.severity = 'HIGH'
            """, repositoryId));
  }

  @Override
  public Optional<Instant> oldestPendingTaskCreatedAt() {
    List<Instant> values = jdbc.query("""
        SELECT MIN(created_at) AS oldest_created_at
        FROM security_scan_task
        WHERE status IN ('PENDING','RETRY_WAIT')
        """, (rs, rowNum) -> nullableInstant(rs, "oldest_created_at"));
    return values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.getFirst());
  }

  private long countState(long repositoryId, String state, String excludedDecision) {
    StringBuilder sql = new StringBuilder("""
        SELECT COUNT(*)
        FROM asset_security_state s
        JOIN asset a ON a.id = s.asset_id
        WHERE a.repository_id = ?
        """);
    List<Object> args = new ArrayList<>();
    args.add(repositoryId);
    if (state != null) {
      sql.append(" AND s.scan_state = ?");
      args.add(state);
    }
    if (excludedDecision != null) {
      sql.append(" AND s.policy_decision <> ?");
      args.add(excludedDecision);
    }
    return count(sql.toString(), args.toArray());
  }

  private long count(String sql) {
    Long value = jdbc.queryForObject(sql, Long.class);
    return value == null ? 0 : value;
  }

  private long count(String sql, Object... args) {
    Long value = jdbc.queryForObject(sql, Long.class, args);
    return value == null ? 0 : value;
  }

  private List<String> list(String value) {
    List<String> result = json.readValue(value, STRING_LIST);
    return result == null ? List.of() : result;
  }

  private Map<String, Integer> integerMap(String value) {
    Map<String, Integer> result = json.readValue(value, INTEGER_MAP);
    return result == null ? Map.of() : result;
  }

  private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
    return EnumColumns.read(type, value);
  }

  private static void setNullableLong(PreparedStatement ps, int index, Long value)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, java.sql.Types.BIGINT);
    } else {
      ps.setLong(index, value);
    }
  }

  private static int safeLimit(int maxItems) {
    return Math.max(1, Math.min(maxItems, 1000));
  }

  private static Instant requiredNow(Instant value) {
    return value == null ? Instant.now() : value;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) return value;
    return value.substring(0, maxLength);
  }

  private static String safeUrl(String value) {
    if (blank(value)) return value;
    String lower = value.trim().toLowerCase(java.util.Locale.ROOT);
    if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
      return null;
    }
    return truncate(value.trim(), 2048);
  }
}
