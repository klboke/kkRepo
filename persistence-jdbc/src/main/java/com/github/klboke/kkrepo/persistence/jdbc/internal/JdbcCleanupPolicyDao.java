package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableTimestamp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupPolicy;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupCursorCompletion;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupHistoryPruneResult;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupOperationalSummary;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupProtection;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupProtectionLookup;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupRun;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupRunItem;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupRunRepository;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupScanCursor;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupSchedule;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupUsage;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupUsageWriteOutcome;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.ClaimedRunRepository;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.TargetRepository;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.UsageTrackingRepository;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.EnumColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcUpserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import com.github.klboke.kkrepo.persistence.jdbc.spi.CoordinationPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcCleanupPolicyDao
    implements com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao {
  private static final int IN_QUERY_BATCH_SIZE = 500;
  private static final String USAGE_TRACKING_REVISION = "cleanup-usage-tracking";
  private static final TypeReference<List<Map<String, Object>>> REPOSITORY_SNAPSHOT_TYPE =
      new TypeReference<>() {
      };
  private static final Comparator<CleanupProtection> PROTECTION_PRIORITY = Comparator
      .comparingInt((CleanupProtection protection) -> protectionScopeRank(protection.scope()))
      .thenComparing(CleanupProtection::id);

  private final JdbcTemplate jdbc;
  private final JsonColumns json;
  private final CoordinationPersistenceDialect coordination;
  private final String orderedRunItemTable;
  private final RowMapper<CleanupPolicy> policyMapper;
  private final RowMapper<CleanupSchedule> scheduleMapper;
  private final RowMapper<CleanupRun> runMapper;
  private final RowMapper<CleanupRunRepository> runRepositoryMapper;
  private final RowMapper<CleanupRunItem> runItemMapper;

  @Override
  public Instant currentTime() {
    java.sql.Timestamp timestamp = jdbc.queryForObject(
        "SELECT CURRENT_TIMESTAMP(3)", java.sql.Timestamp.class);
    if (timestamp == null) {
      throw new IllegalStateException("database did not return its current timestamp");
    }
    return timestamp.toInstant();
  }

  public JdbcCleanupPolicyDao(
      JdbcTemplate jdbc, JsonColumns json, DatabaseDialect databaseDialect) {
    this.jdbc = jdbc;
    this.json = json;
    this.coordination = databaseDialect.coordination();
    this.orderedRunItemTable = databaseDialect.tableReferenceWithPreferredIndex(
        "cleanup_run_item", "idx_cleanup_run_item_repository");
    this.policyMapper = (rs, rowNum) -> new CleanupPolicy(
        rs.getLong("id"),
        rs.getString("name"),
        EnumColumns.read(RepositoryFormat.class, rs.getString("format")),
        rs.getString("notes"),
        json.read(rs.getString("criteria_json")),
        rs.getLong("revision"),
        rs.getString("state"),
        rs.getInt("scan_limit_per_repository"),
        rs.getInt("delete_limit_per_repository"),
        nullableInstant(rs, "created_at"),
        nullableInstant(rs, "updated_at"));
    this.scheduleMapper = (rs, rowNum) -> new CleanupSchedule(
        rs.getLong("policy_id"),
        rs.getString("cron_expression"),
        rs.getString("time_zone"),
        rs.getBoolean("enabled"),
        null,
        nullableInstant(rs, "created_at"),
        nullableInstant(rs, "updated_at"));
    this.runMapper = (rs, rowNum) -> new CleanupRun(
        rs.getLong("id"),
        rs.getLong("policy_id"),
        rs.getLong("policy_revision"),
        rs.getString("mode"),
        rs.getString("trigger_kind"),
        rs.getString("state"),
        rs.getBoolean("cancel_requested"),
        rs.getString("requested_by"),
        nullableInstant(rs, "scheduled_for"),
        rs.getInt("scan_limit_per_repository"),
        rs.getInt("delete_limit_per_repository"),
        json.read(rs.getString("criteria_snapshot_json")),
        snapshot(rs.getString("repository_snapshot_json")),
        rs.getLong("scanned_subjects"),
        rs.getLong("matched_subjects"),
        rs.getLong("would_delete_subjects"),
        rs.getLong("deleted_subjects"),
        rs.getLong("failed_subjects"),
        rs.getInt("truncated_repositories"),
        rs.getString("error_summary"),
        nullableInstant(rs, "started_at"),
        nullableInstant(rs, "completed_at"),
        nullableInstant(rs, "cancelled_at"),
        nullableInstant(rs, "created_at"),
        nullableInstant(rs, "updated_at"));
    this.runRepositoryMapper = (rs, rowNum) -> new CleanupRunRepository(
        rs.getLong("id"),
        rs.getLong("run_id"),
        rs.getLong("repository_id"),
        rs.getString("repository_name"),
        EnumColumns.read(RepositoryFormat.class, rs.getString("format")),
        EnumColumns.read(RepositoryType.class, rs.getString("repository_type")),
        rs.getString("state"),
        rs.getObject("scan_budget") == null ? null : rs.getInt("scan_budget"),
        rs.getInt("attempt_count"),
        rs.getInt("max_attempts"),
        nullableInstant(rs, "next_attempt_at"),
        rs.getString("lease_owner"),
        nullableInstant(rs, "lease_until"),
        nullableInstant(rs, "last_heartbeat_at"),
        rs.getLong("fencing_token"),
        rs.getString("last_error_code"),
        rs.getLong("scanned_subjects"),
        rs.getLong("matched_subjects"),
        rs.getLong("would_delete_subjects"),
        rs.getLong("deleted_subjects"),
        rs.getLong("failed_subjects"),
        rs.getBoolean("truncated"),
        rs.getString("error_summary"),
        nullableInstant(rs, "started_at"),
        nullableInstant(rs, "completed_at"),
        nullableInstant(rs, "created_at"),
        nullableInstant(rs, "updated_at"));
    this.runItemMapper = (rs, rowNum) -> new CleanupRunItem(
        rs.getLong("id"),
        rs.getLong("run_repository_id"),
        rs.getString("subject_kind"),
        rs.getString("subject_key"),
        rs.getBytes("subject_key_hash"),
        rs.getString("family_key"),
        rs.getString("display_name"),
        rs.getString("version"),
        rs.getString("delete_path"),
        nullableInstant(rs, "last_downloaded_at"),
        nullableInstant(rs, "published_at"),
        rs.getInt("asset_count"),
        rs.getLong("estimated_bytes"),
        rs.getString("expected_content_token"),
        rs.getLong("expected_usage_revision"),
        rs.getObject("protection_id") == null ? null : rs.getLong("protection_id"),
        nullableInstant(rs, "evaluated_at"),
        rs.getString("decision"),
        json.read(rs.getString("reason_json")),
        rs.getString("error_summary"),
        nullableInstant(rs, "created_at"),
        nullableInstant(rs, "updated_at"));
  }

  @Override
  public List<CleanupPolicy> listPolicies() {
    return jdbc.query("""
        SELECT * FROM cleanup_policy
        WHERE mode = 'NATIVE' AND state <> 'DELETED'
        ORDER BY name, id
        """, policyMapper);
  }

  @Override
  public List<CleanupPolicy> listPolicies(long afterId, int maxItems) {
    return jdbc.query("""
        SELECT * FROM cleanup_policy
        WHERE mode = 'NATIVE' AND state <> 'DELETED' AND id > ?
        ORDER BY id
        LIMIT ?
        """, policyMapper, Math.max(0, afterId), Math.max(1, maxItems));
  }

  @Override
  public Map<Long, CleanupPolicy> findPolicies(Collection<Long> policyIds) {
    List<Long> ids = distinctIds(policyIds);
    if (ids.isEmpty()) return Map.of();
    Map<Long, CleanupPolicy> result = new LinkedHashMap<>();
    for (List<Long> batch : batches(ids, IN_QUERY_BATCH_SIZE)) {
      jdbc.query("""
          SELECT * FROM cleanup_policy
          WHERE mode = 'NATIVE' AND state <> 'DELETED' AND id IN ("""
              + placeholders(batch.size()) + ")",
          policyMapper,
          batch.toArray()).forEach(policy -> result.put(policy.id(), policy));
    }
    return Map.copyOf(result);
  }

  @Override
  public Optional<CleanupPolicy> findPolicy(long policyId) {
    return jdbc.query("""
        SELECT * FROM cleanup_policy
        WHERE id = ? AND mode = 'NATIVE' AND state <> 'DELETED'
        """, policyMapper, policyId).stream().findFirst();
  }

  @Override
  public long createPolicy(CleanupPolicy policy) {
    return JdbcInserts.insert(jdbc, """
        INSERT INTO cleanup_policy
          (name, format, mode, notes, criteria_json, revision, state,
           scan_limit_per_repository, delete_limit_per_repository)
        VALUES (?, ?, 'NATIVE', ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setString(1, policy.name());
      ps.setString(2, EnumColumns.write(policy.format()));
      ps.setString(3, policy.notes());
      json.bind(ps, 4, policy.criteria());
      ps.setLong(5, policy.revision());
      ps.setString(6, policy.state());
      ps.setInt(7, policy.scanLimitPerRepository());
      ps.setInt(8, policy.deleteLimitPerRepository());
    });
  }

  @Override
  public boolean updatePolicy(CleanupPolicy policy, long expectedRevision) {
    return jdbc.update("""
        UPDATE cleanup_policy
        SET name = ?, format = ?, notes = ?, criteria_json = ?, revision = ?, state = ?,
            scan_limit_per_repository = ?, delete_limit_per_repository = ?,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = ? AND mode = 'NATIVE' AND state <> 'DELETED' AND revision = ?
        """,
        policy.name(),
        EnumColumns.write(policy.format()),
        policy.notes(),
        json.parameter(policy.criteria()),
        policy.revision(),
        policy.state(),
        policy.scanLimitPerRepository(),
        policy.deleteLimitPerRepository(),
        policy.id(),
        expectedRevision) == 1;
  }

  @Override
  public boolean markPolicyDeleted(long policyId, long expectedRevision, Instant updatedAt) {
    boolean deleted = jdbc.update("""
        UPDATE cleanup_policy
        SET state = 'DELETED', revision = revision + 1, updated_at = ?
        WHERE id = ? AND mode = 'NATIVE' AND state <> 'DELETED' AND revision = ?
        """, nullableTimestamp(updatedAt), policyId, expectedRevision) == 1;
    if (deleted) {
      jdbc.update(
          "DELETE FROM cleanup_policy_repository_cursor WHERE policy_id = ?", policyId);
    }
    return deleted;
  }

  @Override
  public List<TargetRepository> listTargets(long policyId) {
    return jdbc.query("""
        SELECT r.id, r.name, r.format, r.type, r.online
        FROM repository_cleanup_policy rp
        JOIN repository r ON r.id = rp.repository_id
        WHERE rp.cleanup_policy_id = ?
        ORDER BY r.name, r.id
        """, (rs, rowNum) -> new TargetRepository(
        rs.getLong("id"),
        rs.getString("name"),
        EnumColumns.read(RepositoryFormat.class, rs.getString("format")),
        EnumColumns.read(RepositoryType.class, rs.getString("type")),
        rs.getBoolean("online")), policyId);
  }

  @Override
  public Map<Long, List<TargetRepository>> listTargets(Collection<Long> policyIds) {
    List<Long> ids = distinctIds(policyIds);
    if (ids.isEmpty()) return Map.of();
    Map<Long, List<TargetRepository>> result = new LinkedHashMap<>();
    ids.forEach(id -> result.put(id, new ArrayList<>()));
    for (List<Long> batch : batches(ids, IN_QUERY_BATCH_SIZE)) {
      jdbc.query("""
          SELECT rp.cleanup_policy_id, r.id, r.name, r.format, r.type, r.online
          FROM repository_cleanup_policy rp
          JOIN repository r ON r.id = rp.repository_id
          WHERE rp.cleanup_policy_id IN (""" + placeholders(batch.size()) + """
          )
          ORDER BY rp.cleanup_policy_id, r.name, r.id
          """, rs -> {
        while (rs.next()) {
          result.get(rs.getLong("cleanup_policy_id")).add(new TargetRepository(
              rs.getLong("id"),
              rs.getString("name"),
              EnumColumns.read(RepositoryFormat.class, rs.getString("format")),
              EnumColumns.read(RepositoryType.class, rs.getString("type")),
              rs.getBoolean("online")));
        }
        return null;
      }, batch.toArray());
    }
    result.replaceAll((ignored, targets) -> List.copyOf(targets));
    return Map.copyOf(result);
  }

  @Override
  public boolean isPolicyTarget(long policyId, long repositoryId) {
    return !jdbc.queryForList("""
        SELECT repository_id
        FROM repository_cleanup_policy
        WHERE cleanup_policy_id = ? AND repository_id = ?
        """, Long.class, policyId, repositoryId).isEmpty();
  }

  @Override
  public boolean hasRepositoryReferences(long repositoryId) {
    Long references = jdbc.queryForObject("""
        SELECT COUNT(*) FROM (
          SELECT repository_id
          FROM repository_cleanup_policy
          WHERE repository_id = ?
          UNION ALL
          SELECT repository_id
          FROM cleanup_run_repository
          WHERE repository_id = ?
            AND state IN ('PENDING', 'RUNNING', 'RETRY_WAIT')
        ) cleanup_repository_reference
        """, Long.class, repositoryId, repositoryId);
    return references != null && references > 0;
  }

  @Override
  public void replaceTargets(long policyId, List<Long> repositoryIds) {
    jdbc.update("DELETE FROM repository_cleanup_policy WHERE cleanup_policy_id = ?", policyId);
    List<Long> ids = repositoryIds == null ? List.of() : repositoryIds.stream().distinct().toList();
    if (!ids.isEmpty()) {
      jdbc.batchUpdate("""
          INSERT INTO repository_cleanup_policy (repository_id, cleanup_policy_id)
          VALUES (?, ?)
          """, new BatchPreparedStatementSetter() {
        @Override
        public void setValues(PreparedStatement ps, int index) throws SQLException {
          ps.setLong(1, ids.get(index));
          ps.setLong(2, policyId);
        }

        @Override
        public int getBatchSize() {
          return ids.size();
        }
      });
    }
    jdbc.update("""
        DELETE FROM cleanup_policy_repository_cursor
        WHERE policy_id = ?
          AND NOT EXISTS (
            SELECT 1 FROM repository_cleanup_policy target
            WHERE target.cleanup_policy_id = cleanup_policy_repository_cursor.policy_id
              AND target.repository_id = cleanup_policy_repository_cursor.repository_id
          )
        """, policyId);
  }

  @Override
  public Optional<CleanupSchedule> findSchedule(long policyId) {
    return jdbc.query(
        "SELECT * FROM cleanup_policy_schedule WHERE policy_id = ?",
        scheduleMapper,
        policyId).stream().findFirst();
  }

  @Override
  public List<CleanupSchedule> listSchedules() {
    return jdbc.query(
        "SELECT * FROM cleanup_policy_schedule ORDER BY policy_id",
        scheduleMapper);
  }

  @Override
  public Map<Long, CleanupSchedule> findSchedules(Collection<Long> policyIds) {
    List<Long> ids = distinctIds(policyIds);
    if (ids.isEmpty()) return Map.of();
    Map<Long, CleanupSchedule> result = new LinkedHashMap<>();
    for (List<Long> batch : batches(ids, IN_QUERY_BATCH_SIZE)) {
      jdbc.query("""
          SELECT * FROM cleanup_policy_schedule
          WHERE policy_id IN (""" + placeholders(batch.size()) + ")",
          scheduleMapper,
          batch.toArray()).forEach(schedule -> result.put(schedule.policyId(), schedule));
    }
    return Map.copyOf(result);
  }

  @Override
  public void upsertSchedule(CleanupSchedule schedule) {
    int updated = jdbc.update("""
        UPDATE cleanup_policy_schedule
        SET cron_expression = ?, time_zone = ?, enabled = ?, updated_at = CURRENT_TIMESTAMP
        WHERE policy_id = ?
        """,
        schedule.cronExpression(),
        schedule.timeZone(),
        schedule.enabled(),
        schedule.policyId());
    if (updated == 1) {
      return;
    }
    try {
      jdbc.update("""
          INSERT INTO cleanup_policy_schedule
            (policy_id, cron_expression, time_zone, enabled)
          VALUES (?, ?, ?, ?)
          """,
          schedule.policyId(),
          schedule.cronExpression(),
          schedule.timeZone(),
          schedule.enabled());
    } catch (DuplicateKeyException e) {
      upsertSchedule(schedule);
    }
  }

  @Override
  public void deleteSchedule(long policyId) {
    jdbc.update("DELETE FROM cleanup_policy_schedule WHERE policy_id = ?", policyId);
  }

  @Override
  public long createRun(CleanupRun run) {
    return JdbcInserts.insert(jdbc, """
        INSERT INTO cleanup_run
          (policy_id, policy_revision, mode, trigger_kind, state, requested_by,
           scheduled_for, scan_limit_per_repository, delete_limit_per_repository,
           criteria_snapshot_json, repository_snapshot_json, started_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> bindRun(ps, run));
  }

  @Override
  public OptionalLong tryCreateRun(CleanupRun run) {
    return JdbcInserts.tryInsert(jdbc, """
        INSERT INTO cleanup_run
          (policy_id, policy_revision, mode, trigger_kind, state, requested_by,
           scheduled_for, scan_limit_per_repository, delete_limit_per_repository,
           criteria_snapshot_json, repository_snapshot_json, started_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> bindRun(ps, run));
  }

  private void bindRun(PreparedStatement ps, CleanupRun run) throws SQLException {
    ps.setLong(1, run.policyId());
    ps.setLong(2, run.policyRevision());
    ps.setString(3, run.mode());
    ps.setString(4, run.triggerKind());
    ps.setString(5, run.state());
    ps.setString(6, run.requestedBy());
    ps.setObject(7, nullableTimestamp(run.scheduledFor()));
    ps.setInt(8, run.scanLimitPerRepository());
    ps.setInt(9, run.deleteLimitPerRepository());
    json.bind(ps, 10, run.criteriaSnapshot());
    json.bindSerialized(ps, 11, json.writeValue(run.repositorySnapshot()));
    ps.setObject(12, nullableTimestamp(run.startedAt()));
  }

  @Override
  public Optional<CleanupRun> findRun(long runId) {
    return jdbc.query("SELECT * FROM cleanup_run WHERE id = ?", runMapper, runId)
        .stream().findFirst();
  }

  @Override
  public Optional<CleanupRun> findScheduledRun(long policyId, Instant scheduledFor) {
    return jdbc.query("""
        SELECT * FROM cleanup_run WHERE policy_id = ? AND scheduled_for = ?
        """, runMapper, policyId, nullableTimestamp(scheduledFor)).stream().findFirst();
  }

  @Override
  public List<CleanupRun> listRuns(Long policyId, long afterId, int maxItems) {
    int limit = Math.max(1, maxItems);
    if (policyId == null) {
      return jdbc.query("""
          SELECT * FROM cleanup_run
          WHERE id > ? ORDER BY id LIMIT ?
          """, runMapper, Math.max(0, afterId), limit);
    }
    return jdbc.query("""
        SELECT * FROM cleanup_run
        WHERE policy_id = ? AND id > ? ORDER BY id LIMIT ?
        """, runMapper, policyId, Math.max(0, afterId), limit);
  }

  @Override
  public boolean completeRun(
      long runId,
      String state,
      long scannedSubjects,
      long matchedSubjects,
      long wouldDeleteSubjects,
      long deletedSubjects,
      long failedSubjects,
      int truncatedRepositories,
      String errorSummary,
      Instant completedAt) {
    return jdbc.update("""
        UPDATE cleanup_run
        SET state = ?, scanned_subjects = ?, matched_subjects = ?, would_delete_subjects = ?,
            deleted_subjects = ?, failed_subjects = ?, truncated_repositories = ?, error_summary = ?,
            completed_at = ?, updated_at = CURRENT_TIMESTAMP
        WHERE id = ? AND state IN ('PENDING', 'RUNNING', 'CANCELLING')
        """,
        state,
        scannedSubjects,
        matchedSubjects,
        wouldDeleteSubjects,
        deletedSubjects,
        failedSubjects,
        truncatedRepositories,
        errorSummary,
        nullableTimestamp(completedAt),
        runId) == 1;
  }

  @Override
  public long createRunRepository(CleanupRunRepository runRepository) {
    ensureRepositoryLease(runRepository.repositoryId());
    return JdbcInserts.insert(jdbc, """
        INSERT INTO cleanup_run_repository
          (run_id, repository_id, repository_name, format, repository_type, state, started_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setLong(1, runRepository.runId());
      ps.setLong(2, runRepository.repositoryId());
      ps.setString(3, runRepository.repositoryName());
      ps.setString(4, EnumColumns.write(runRepository.format()));
      ps.setString(5, EnumColumns.write(runRepository.repositoryType()));
      ps.setString(6, runRepository.state());
      ps.setObject(7, nullableTimestamp(runRepository.startedAt()));
    });
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void createRunRepositories(List<CleanupRunRepository> runRepositories) {
    if (runRepositories == null || runRepositories.isEmpty()) return;
    List<CleanupRunRepository> repositories = runRepositories.stream()
        .filter(Objects::nonNull)
        .toList();
    if (repositories.isEmpty()) return;
    List<Long> repositoryIds = repositories.stream()
        .map(CleanupRunRepository::repositoryId)
        .distinct()
        .sorted()
        .toList();

    // Repository rows are locked in a deterministic order. This makes the following portable
    // batch insert duplicate-safe even when two policy runs first target the same repository.
    List<Long> lockedIds = jdbc.queryForList(
        "SELECT id FROM repository WHERE id IN (" + placeholders(repositoryIds.size())
            + ") ORDER BY id FOR UPDATE",
        Long.class,
        repositoryIds.toArray());
    if (lockedIds.size() != repositoryIds.size()) {
      throw new IllegalStateException("cleanup run target repository disappeared");
    }
    List<Long> existingLeaseIds = jdbc.queryForList(
        "SELECT repository_id FROM cleanup_repository_lease WHERE repository_id IN ("
            + placeholders(repositoryIds.size()) + ")",
        Long.class,
        repositoryIds.toArray());
    java.util.Set<Long> existingLeases = java.util.Set.copyOf(existingLeaseIds);
    List<Long> missingLeases = repositoryIds.stream()
        .filter(repositoryId -> !existingLeases.contains(repositoryId))
        .toList();
    if (!missingLeases.isEmpty()) {
      jdbc.batchUpdate(
          "INSERT INTO cleanup_repository_lease (repository_id) VALUES (?)",
          missingLeases,
          missingLeases.size(),
          (ps, repositoryId) -> ps.setLong(1, repositoryId));
    }
    jdbc.batchUpdate("""
        INSERT INTO cleanup_run_repository
          (run_id, repository_id, repository_name, format, repository_type, state, started_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """, repositories, repositories.size(), (ps, repository) -> {
      ps.setLong(1, repository.runId());
      ps.setLong(2, repository.repositoryId());
      ps.setString(3, repository.repositoryName());
      ps.setString(4, EnumColumns.write(repository.format()));
      ps.setString(5, EnumColumns.write(repository.repositoryType()));
      ps.setString(6, repository.state());
      ps.setObject(7, nullableTimestamp(repository.startedAt()));
    });
  }

  @Override
  public List<CleanupRunRepository> listRunRepositories(long runId) {
    return jdbc.query("""
        SELECT * FROM cleanup_run_repository WHERE run_id = ? ORDER BY id
        """, runRepositoryMapper, runId);
  }

  @Override
  public Optional<CleanupRunRepository> findRunRepository(
      long runId, long runRepositoryId) {
    return jdbc.query("""
        SELECT * FROM cleanup_run_repository
        WHERE run_id = ? AND id = ?
        """, runRepositoryMapper, runId, runRepositoryId).stream().findFirst();
  }

  @Override
  public void ensureRepositoryLease(long repositoryId) {
    JdbcUpserts.updateThenInsert(
        jdbc,
        "UPDATE cleanup_repository_lease SET repository_id = repository_id WHERE repository_id = ?",
        new Object[] {repositoryId},
        "INSERT INTO cleanup_repository_lease (repository_id) VALUES (?)",
        new Object[] {repositoryId});
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public List<ClaimedRunRepository> claimRunRepositories(
      String workerId, Instant now, Instant leaseUntil, int maxItems) {
    if (workerId == null || workerId.isBlank()) {
      throw new IllegalArgumentException("workerId is required");
    }
    if (now == null || leaseUntil == null || !leaseUntil.isAfter(now)) {
      throw new IllegalArgumentException("leaseUntil must be after now");
    }
    int limit = Math.min(100, Math.max(1, maxItems));
    List<Long> candidateIds = jdbc.queryForList("""
        SELECT rr.id
        FROM cleanup_run_repository rr
        JOIN cleanup_run r ON r.id = rr.run_id
        JOIN cleanup_repository_lease lease ON lease.repository_id = rr.repository_id
        WHERE (
            (r.cancel_requested = FALSE
              AND rr.state IN ('PENDING', 'RETRY_WAIT')
              AND rr.attempt_count < rr.max_attempts
              AND rr.next_attempt_at <= ?)
            OR (rr.state = 'RUNNING' AND rr.lease_until < ?)
          )
          AND (
            lease.run_repository_id IS NULL
            OR lease.run_repository_id = rr.id
            OR lease.expires_at IS NULL
            OR lease.expires_at < ?
          )
          AND NOT EXISTS (
            SELECT 1
            FROM cleanup_run_repository earlier
            JOIN cleanup_run earlier_run ON earlier_run.id = earlier.run_id
            WHERE earlier.repository_id = rr.repository_id
              AND earlier.id < rr.id
              AND (
                (earlier_run.cancel_requested = FALSE
                  AND earlier.state IN ('PENDING', 'RETRY_WAIT')
                  AND earlier.attempt_count < earlier.max_attempts
                  AND earlier.next_attempt_at <= ?)
                OR (earlier.state = 'RUNNING' AND earlier.lease_until < ?)
              )
          )
        ORDER BY rr.next_attempt_at, rr.id
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """,
        Long.class,
        nullableTimestamp(now),
        nullableTimestamp(now),
        nullableTimestamp(now),
        nullableTimestamp(now),
        nullableTimestamp(now),
        limit * 3);
    List<ClaimedRunRepository> claimed = new ArrayList<>(limit);
    for (Long id : candidateIds) {
      if (claimed.size() >= limit) break;
      ClaimCandidate candidate = jdbc.query("""
          SELECT rr.id, rr.run_id, rr.repository_id, rr.repository_name, rr.format,
                 rr.repository_type, rr.state, rr.attempt_count, rr.max_attempts,
                 lease.fencing_token, lease.run_repository_id, lease.expires_at
          FROM cleanup_run_repository rr
          JOIN cleanup_repository_lease lease ON lease.repository_id = rr.repository_id
          WHERE rr.id = ?
          FOR UPDATE SKIP LOCKED
          """, (rs, rowNum) -> new ClaimCandidate(
          rs.getLong("id"),
          rs.getLong("run_id"),
          rs.getLong("repository_id"),
          rs.getString("repository_name"),
          EnumColumns.read(RepositoryFormat.class, rs.getString("format")),
          EnumColumns.read(RepositoryType.class, rs.getString("repository_type")),
          rs.getString("state"),
          rs.getInt("attempt_count"),
          rs.getInt("max_attempts"),
          rs.getLong("fencing_token"),
          rs.getObject("run_repository_id") == null ? null : rs.getLong("run_repository_id"),
          nullableInstant(rs, "expires_at")), id).stream().findFirst().orElse(null);
      if (candidate == null) continue;
      if (candidate.leaseRunRepositoryId() != null
          && candidate.leaseRunRepositoryId() != id
          && candidate.leaseExpiresAt() != null
          && !candidate.leaseExpiresAt().isBefore(now)) {
        continue;
      }
      String leaseToken = UUID.randomUUID().toString();
      long fencingToken = candidate.fencingToken() + 1;
      jdbc.update("""
          UPDATE cleanup_repository_lease
          SET run_repository_id = ?, lease_owner = ?, lease_token = ?, fencing_token = ?,
              expires_at = ?, updated_at = ?
          WHERE repository_id = ?
          """,
          id,
          workerId,
          leaseToken,
          fencingToken,
          nullableTimestamp(leaseUntil),
          nullableTimestamp(now),
          candidate.repositoryId());
      int attemptCount = candidate.attemptCount() + 1;
      int updated = jdbc.update("""
          UPDATE cleanup_run_repository
          SET state = 'RUNNING', attempt_count = ?, lease_owner = ?, lease_token = ?,
              lease_until = ?, last_heartbeat_at = ?, fencing_token = ?,
              started_at = COALESCE(started_at, ?), last_error_code = NULL,
              updated_at = ?
          WHERE id = ?
          """,
          attemptCount,
          workerId,
          leaseToken,
          nullableTimestamp(leaseUntil),
          nullableTimestamp(now),
          fencingToken,
          nullableTimestamp(now),
          nullableTimestamp(now),
          id);
      if (updated != 1) {
        throw new IllegalStateException("cleanup shard disappeared while claiming: " + id);
      }
      jdbc.update("""
          UPDATE cleanup_run SET state = 'RUNNING', started_at = COALESCE(started_at, ?),
              updated_at = ?
          WHERE id = ? AND state IN ('PENDING', 'RUNNING', 'CANCELLING')
          """, nullableTimestamp(now), nullableTimestamp(now), candidate.runId());
      claimed.add(new ClaimedRunRepository(
          id,
          candidate.runId(),
          candidate.repositoryId(),
          candidate.repositoryName(),
          candidate.format(),
          candidate.repositoryType(),
          workerId,
          leaseToken,
          fencingToken,
          attemptCount,
          candidate.maxAttempts(),
          leaseUntil,
          "RUNNING".equals(candidate.state())));
    }
    return List.copyOf(claimed);
  }

  @Override
  @Transactional
  public boolean heartbeatRunRepository(
      long runRepositoryId,
      String leaseToken,
      long fencingToken,
      Instant leaseUntil,
      Instant heartbeatAt) {
    int shard = jdbc.update("""
        UPDATE cleanup_run_repository
        SET lease_until = ?, last_heartbeat_at = ?, updated_at = ?
        WHERE id = ? AND state = 'RUNNING' AND lease_token = ? AND fencing_token = ?
        """,
        nullableTimestamp(leaseUntil),
        nullableTimestamp(heartbeatAt),
        nullableTimestamp(heartbeatAt),
        runRepositoryId,
        leaseToken,
        fencingToken);
    if (shard != 1) return false;
    int repository = jdbc.update("""
        UPDATE cleanup_repository_lease
        SET expires_at = ?, updated_at = ?
        WHERE run_repository_id = ? AND lease_token = ? AND fencing_token = ?
        """,
        nullableTimestamp(leaseUntil),
        nullableTimestamp(heartbeatAt),
        runRepositoryId,
        leaseToken,
        fencingToken);
    if (repository != 1) {
      throw new IllegalStateException("cleanup repository fence was lost during heartbeat");
    }
    return true;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean lockCurrentRunRepositoryLease(
      long runRepositoryId, String leaseToken, long fencingToken, Instant now) {
    return !jdbc.queryForList("""
        SELECT rr.id
        FROM cleanup_run_repository rr
        JOIN cleanup_repository_lease lease
          ON lease.repository_id = rr.repository_id
         AND lease.run_repository_id = rr.id
        JOIN cleanup_run run ON run.id = rr.run_id
        WHERE rr.id = ?
          AND rr.state = 'RUNNING'
          AND rr.lease_token = ?
          AND rr.fencing_token = ?
          AND rr.lease_until >= ?
          AND lease.lease_token = ?
          AND lease.fencing_token = ?
          AND lease.expires_at >= ?
        FOR UPDATE
        """,
        Long.class,
        runRepositoryId,
        leaseToken,
        fencingToken,
        nullableTimestamp(now),
        leaseToken,
        fencingToken,
        nullableTimestamp(now)).isEmpty();
  }

  @Override
  @Transactional
  public boolean completeClaimedRunRepository(
      long runRepositoryId,
      String leaseToken,
      long fencingToken,
      String state,
      long scannedSubjects,
      long matchedSubjects,
      long wouldDeleteSubjects,
      long deletedSubjects,
      long failedSubjects,
      boolean truncated,
      String errorSummary,
      Instant completedAt) {
    int updated = jdbc.update("""
        UPDATE cleanup_run_repository
        SET state = ?, scanned_subjects = ?, matched_subjects = ?, would_delete_subjects = ?,
            deleted_subjects = ?, failed_subjects = ?, truncated = ?, error_summary = ?, completed_at = ?,
            lease_owner = NULL, lease_token = NULL, lease_until = NULL,
            last_heartbeat_at = NULL, updated_at = ?
        WHERE id = ? AND state = 'RUNNING' AND lease_token = ? AND fencing_token = ?
        """,
        state,
        scannedSubjects,
        matchedSubjects,
        wouldDeleteSubjects,
        deletedSubjects,
        failedSubjects,
        truncated,
        errorSummary,
        nullableTimestamp(completedAt),
        nullableTimestamp(completedAt),
        runRepositoryId,
        leaseToken,
        fencingToken);
    if (updated != 1) return false;
    releaseRepositoryLease(runRepositoryId, leaseToken, fencingToken, completedAt);
    return true;
  }

  @Override
  @Transactional
  public CleanupScanCursor acquireRunRepositoryScanCursor(
      long runRepositoryId,
      String leaseToken,
      long fencingToken,
      String initialPhase,
      Instant now) {
    if (!"COMPONENT".equals(initialPhase) && !"DOCKER".equals(initialPhase)) {
      throw new IllegalArgumentException("unsupported cleanup cursor phase: " + initialPhase);
    }
    RunCursorSnapshot shard = jdbc.query("""
        SELECT run.policy_id, rr.repository_id,
               rr.scan_cursor_phase, rr.scan_cursor_component_namespace,
               rr.scan_cursor_component_name, rr.scan_cursor_component_kind,
               rr.scan_cursor_subject_id, rr.scan_cursor_revision,
               rr.scan_cursor_wrapped_count
        FROM cleanup_run_repository rr
        JOIN cleanup_run run ON run.id = rr.run_id
        JOIN cleanup_repository_lease lease
          ON lease.repository_id = rr.repository_id
         AND lease.run_repository_id = rr.id
        WHERE rr.id = ?
          AND rr.state = 'RUNNING'
          AND rr.lease_token = ?
          AND rr.fencing_token = ?
          AND rr.lease_until >= ?
          AND lease.lease_token = ?
          AND lease.fencing_token = ?
          AND lease.expires_at >= ?
        FOR UPDATE
        """, (rs, rowNum) -> new RunCursorSnapshot(
        rs.getLong("policy_id"),
        rs.getLong("repository_id"),
        rs.getString("scan_cursor_phase"),
        rs.getString("scan_cursor_component_namespace"),
        rs.getString("scan_cursor_component_name"),
        rs.getString("scan_cursor_component_kind"),
        rs.getObject("scan_cursor_subject_id") == null
            ? 0 : rs.getLong("scan_cursor_subject_id"),
        rs.getObject("scan_cursor_revision") == null
            ? 0 : rs.getLong("scan_cursor_revision"),
        rs.getObject("scan_cursor_wrapped_count") == null
            ? 0 : rs.getLong("scan_cursor_wrapped_count")),
        runRepositoryId,
        leaseToken,
        fencingToken,
        nullableTimestamp(now),
        leaseToken,
        fencingToken,
        nullableTimestamp(now)).stream().findFirst().orElseThrow(() ->
        new IllegalStateException("cleanup shard lost its scan cursor fence"));
    if (shard.phase() != null) {
      return shard.toCursor();
    }

    JdbcUpserts.updateThenInsert(
        jdbc,
        """
          UPDATE cleanup_policy_repository_cursor
          SET policy_id = policy_id
          WHERE policy_id = ? AND repository_id = ?
          """,
        new Object[] {shard.policyId(), shard.repositoryId()},
        """
          INSERT INTO cleanup_policy_repository_cursor
            (policy_id, repository_id, phase, subject_id, revision, wrapped_count)
          VALUES (?, ?, ?, 0, 0, 0)
          """,
        new Object[] {shard.policyId(), shard.repositoryId(), initialPhase});
    CleanupScanCursor cursor = jdbc.query("""
        SELECT policy_id, repository_id, phase, component_namespace, component_name,
               component_kind, subject_id, revision, wrapped_count
        FROM cleanup_policy_repository_cursor
        WHERE policy_id = ? AND repository_id = ?
        FOR UPDATE
        """, (rs, rowNum) -> mapScanCursor(rs), shard.policyId(), shard.repositoryId())
        .stream().findFirst().orElseThrow(() ->
            new IllegalStateException("cleanup scan cursor disappeared"));
    boolean incompatible = "DOCKER".equals(initialPhase)
        ? !"DOCKER".equals(cursor.phase())
        : "DOCKER".equals(cursor.phase());
    if (incompatible) {
      jdbc.update("""
          UPDATE cleanup_policy_repository_cursor
          SET phase = ?, component_namespace = NULL, component_name = NULL,
              component_kind = NULL, subject_id = 0, revision = revision + 1,
              wrapped_count = 0, updated_at = ?
          WHERE policy_id = ? AND repository_id = ?
          """,
          initialPhase,
          nullableTimestamp(now),
          shard.policyId(),
          shard.repositoryId());
      cursor = new CleanupScanCursor(
          shard.policyId(), shard.repositoryId(), initialPhase,
          null, null, null, 0, cursor.revision() + 1, 0);
    }
    int snapshotted = jdbc.update("""
        UPDATE cleanup_run_repository
        SET scan_cursor_phase = ?, scan_cursor_component_namespace = ?,
            scan_cursor_component_name = ?, scan_cursor_component_kind = ?,
            scan_cursor_subject_id = ?, scan_cursor_revision = ?,
            scan_cursor_wrapped_count = ?, updated_at = ?
        WHERE id = ? AND state = 'RUNNING' AND lease_token = ? AND fencing_token = ?
          AND scan_cursor_phase IS NULL
        """,
        cursor.phase(),
        cursor.componentNamespace(),
        cursor.componentName(),
        cursor.componentKind(),
        cursor.subjectId(),
        cursor.revision(),
        cursor.wrappedCount(),
        nullableTimestamp(now),
        runRepositoryId,
        leaseToken,
        fencingToken);
    if (snapshotted != 1) {
      throw new IllegalStateException("cleanup shard cursor snapshot was lost");
    }
    return cursor;
  }

  @Override
  @Transactional
  public CleanupCursorCompletion completeClaimedRunRepositoryAndAdvanceCursor(
      long runRepositoryId,
      String leaseToken,
      long fencingToken,
      String state,
      long scannedSubjects,
      long matchedSubjects,
      long wouldDeleteSubjects,
      long deletedSubjects,
      long failedSubjects,
      boolean truncated,
      String errorSummary,
      Instant completedAt,
      CleanupScanCursor expectedCursor,
      CleanupScanCursor nextCursor) {
    if (expectedCursor == null || nextCursor == null
        || expectedCursor.policyId() != nextCursor.policyId()
        || expectedCursor.repositoryId() != nextCursor.repositoryId()
        || expectedCursor.revision() != nextCursor.revision()) {
      throw new IllegalArgumentException("cleanup cursor transition is inconsistent");
    }
    RunCursorSnapshot snapshot = jdbc.query("""
        SELECT run.policy_id, rr.repository_id,
               rr.scan_cursor_phase, rr.scan_cursor_component_namespace,
               rr.scan_cursor_component_name, rr.scan_cursor_component_kind,
               rr.scan_cursor_subject_id, rr.scan_cursor_revision,
               rr.scan_cursor_wrapped_count
        FROM cleanup_run_repository rr
        JOIN cleanup_run run ON run.id = rr.run_id
        WHERE rr.id = ? AND rr.state = 'RUNNING'
          AND rr.lease_token = ? AND rr.fencing_token = ?
        FOR UPDATE
        """, (rs, rowNum) -> new RunCursorSnapshot(
        rs.getLong("policy_id"),
        rs.getLong("repository_id"),
        rs.getString("scan_cursor_phase"),
        rs.getString("scan_cursor_component_namespace"),
        rs.getString("scan_cursor_component_name"),
        rs.getString("scan_cursor_component_kind"),
        rs.getObject("scan_cursor_subject_id") == null
            ? 0 : rs.getLong("scan_cursor_subject_id"),
        rs.getObject("scan_cursor_revision") == null
            ? 0 : rs.getLong("scan_cursor_revision"),
        rs.getObject("scan_cursor_wrapped_count") == null
            ? 0 : rs.getLong("scan_cursor_wrapped_count")),
        runRepositoryId,
        leaseToken,
        fencingToken).stream().findFirst().orElse(null);
    if (snapshot == null || !sameCursor(snapshot.toCursor(), expectedCursor)) {
      return new CleanupCursorCompletion(false, false);
    }
    boolean completed = completeClaimedRunRepository(
        runRepositoryId,
        leaseToken,
        fencingToken,
        state,
        scannedSubjects,
        matchedSubjects,
        wouldDeleteSubjects,
        deletedSubjects,
        failedSubjects,
        truncated,
        errorSummary,
        completedAt);
    if (!completed) return new CleanupCursorCompletion(false, false);
    int advanced = jdbc.update("""
        UPDATE cleanup_policy_repository_cursor
        SET phase = ?, component_namespace = ?, component_name = ?, component_kind = ?,
            subject_id = ?, revision = revision + 1, wrapped_count = ?, updated_at = ?
        WHERE policy_id = ? AND repository_id = ? AND revision = ?
        """,
        nextCursor.phase(),
        nextCursor.componentNamespace(),
        nextCursor.componentName(),
        nextCursor.componentKind(),
        Math.max(0, nextCursor.subjectId()),
        Math.max(0, nextCursor.wrappedCount()),
        nullableTimestamp(completedAt),
        expectedCursor.policyId(),
        expectedCursor.repositoryId(),
        expectedCursor.revision());
    return new CleanupCursorCompletion(true, advanced == 1);
  }

  @Override
  @Transactional
  public boolean retryClaimedRunRepository(
      long runRepositoryId,
      String leaseToken,
      long fencingToken,
      Instant nextAttemptAt,
      String errorCode,
      String errorSummary,
      Instant updatedAt) {
    Boolean cancellationRequested = jdbc.query("""
        SELECT r.cancel_requested
        FROM cleanup_run_repository rr
        JOIN cleanup_run r ON r.id = rr.run_id
        WHERE rr.id = ?
        FOR UPDATE
        """, rs -> rs.next() && rs.getBoolean(1), runRepositoryId);
    if (Boolean.TRUE.equals(cancellationRequested)) {
      return completeClaimedRunRepository(
          runRepositoryId,
          leaseToken,
          fencingToken,
          "CANCELLED",
          0,
          0,
          0,
          0,
          0,
          false,
          "cleanup run cancellation requested",
          updatedAt);
    }
    int updated = jdbc.update("""
        UPDATE cleanup_run_repository
        SET state = 'RETRY_WAIT', next_attempt_at = ?, last_error_code = ?,
            error_summary = ?, lease_owner = NULL, lease_token = NULL, lease_until = NULL,
            last_heartbeat_at = NULL, updated_at = ?
        WHERE id = ? AND state = 'RUNNING' AND lease_token = ? AND fencing_token = ?
        """,
        nullableTimestamp(nextAttemptAt),
        errorCode,
        errorSummary,
        nullableTimestamp(updatedAt),
        runRepositoryId,
        leaseToken,
        fencingToken);
    if (updated != 1) return false;
    releaseRepositoryLease(runRepositoryId, leaseToken, fencingToken, updatedAt);
    return true;
  }

  private void releaseRepositoryLease(
      long runRepositoryId, String leaseToken, long fencingToken, Instant updatedAt) {
    int released = jdbc.update("""
        UPDATE cleanup_repository_lease
        SET run_repository_id = NULL, lease_owner = NULL, lease_token = NULL,
            expires_at = NULL, updated_at = ?
        WHERE run_repository_id = ? AND lease_token = ? AND fencing_token = ?
        """,
        nullableTimestamp(updatedAt),
        runRepositoryId,
        leaseToken,
        fencingToken);
    if (released != 1) {
      throw new IllegalStateException("cleanup repository fence was lost before release");
    }
  }

  @Override
  @Transactional
  public boolean requestRunCancellation(long runId, Instant cancelledAt) {
    int updated = jdbc.update("""
        UPDATE cleanup_run
        SET cancel_requested = TRUE,
            state = CASE WHEN state IN ('PENDING', 'RUNNING') THEN 'CANCELLING' ELSE state END,
            cancelled_at = COALESCE(cancelled_at, ?), updated_at = ?
        WHERE id = ? AND state IN ('PENDING', 'RUNNING', 'CANCELLING')
        """, nullableTimestamp(cancelledAt), nullableTimestamp(cancelledAt), runId);
    if (updated == 0) return false;
    jdbc.update("""
        UPDATE cleanup_run_repository
        SET state = 'CANCELLED', completed_at = ?, updated_at = ?
        WHERE run_id = ? AND state IN ('PENDING', 'RETRY_WAIT')
        """, nullableTimestamp(cancelledAt), nullableTimestamp(cancelledAt), runId);
    return true;
  }

  @Override
  public boolean isRunCancellationRequested(long runId) {
    Boolean result = jdbc.queryForObject(
        "SELECT cancel_requested FROM cleanup_run WHERE id = ?", Boolean.class, runId);
    return Boolean.TRUE.equals(result);
  }

  @Override
  @Transactional
  public int reserveTryRunScanBudget(
      long runId, long runRepositoryId, int requestedSubjects, int totalSubjectLimit) {
    if (requestedSubjects < 0 || totalSubjectLimit < 1) {
      throw new IllegalArgumentException("invalid Try Run scan budget");
    }
    Long lockedRun = jdbc.queryForList(
        "SELECT id FROM cleanup_run WHERE id = ? FOR UPDATE", Long.class, runId)
        .stream().findFirst().orElse(null);
    if (lockedRun == null) return 0;
    Integer existing = jdbc.query("""
        SELECT scan_budget FROM cleanup_run_repository
        WHERE id = ? AND run_id = ?
        """, rs -> {
          if (!rs.next()) return null;
          Number value = (Number) rs.getObject("scan_budget");
          return value == null ? null : Math.toIntExact(value.longValue());
        },
        runRepositoryId, runId);
    if (existing != null) return existing;
    Long reserved = jdbc.queryForObject("""
        SELECT COALESCE(SUM(scan_budget), 0)
        FROM cleanup_run_repository WHERE run_id = ?
        """, Long.class, runId);
    int remaining = (int) Math.max(
        0, Math.min(Integer.MAX_VALUE, totalSubjectLimit - (reserved == null ? 0 : reserved)));
    int granted = Math.min(requestedSubjects, remaining);
    int updated = jdbc.update("""
        UPDATE cleanup_run_repository SET scan_budget = ?, updated_at = CURRENT_TIMESTAMP
        WHERE id = ? AND run_id = ? AND scan_budget IS NULL
        """, granted, runRepositoryId, runId);
    if (updated != 1) {
      Integer winner = jdbc.query("""
          SELECT scan_budget FROM cleanup_run_repository WHERE id = ? AND run_id = ?
          """, rs -> {
            if (!rs.next()) return null;
            Number value = (Number) rs.getObject("scan_budget");
            return value == null ? null : Math.toIntExact(value.longValue());
          }, runRepositoryId, runId);
      return winner == null ? 0 : winner;
    }
    return granted;
  }

  @Override
  public void completeRunRepository(
      long runRepositoryId,
      String state,
      long scannedSubjects,
      long matchedSubjects,
      long wouldDeleteSubjects,
      long deletedSubjects,
      long failedSubjects,
      boolean truncated,
      String errorSummary,
      Instant completedAt) {
    jdbc.update("""
        UPDATE cleanup_run_repository
        SET state = ?, scanned_subjects = ?, matched_subjects = ?, would_delete_subjects = ?,
            deleted_subjects = ?, failed_subjects = ?, truncated = ?, error_summary = ?, completed_at = ?,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = ?
        """,
        state,
        scannedSubjects,
        matchedSubjects,
        wouldDeleteSubjects,
        deletedSubjects,
        failedSubjects,
        truncated,
        errorSummary,
        nullableTimestamp(completedAt),
        runRepositoryId);
  }

  @Override
  public void insertRunItems(List<CleanupRunItem> items) {
    if (items == null || items.isEmpty()) {
      return;
    }
    jdbc.batchUpdate("""
        INSERT INTO cleanup_run_item
          (run_repository_id, subject_kind, subject_key, subject_key_hash, family_key,
           display_name, version, delete_path, last_downloaded_at, published_at,
           asset_count, estimated_bytes, expected_content_token, expected_usage_revision,
           protection_id, evaluated_at, decision, reason_json, error_summary)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, new BatchPreparedStatementSetter() {
      @Override
      public void setValues(PreparedStatement ps, int index) throws SQLException {
        CleanupRunItem item = items.get(index);
        ps.setLong(1, item.runRepositoryId());
        ps.setString(2, item.subjectKind());
        ps.setString(3, item.subjectKey());
        ps.setBytes(4, item.subjectKeyHash());
        ps.setString(5, item.familyKey());
        ps.setString(6, item.displayName());
        ps.setString(7, item.version());
        ps.setString(8, item.deletePath());
        ps.setObject(9, nullableTimestamp(item.lastDownloadedAt()));
        ps.setObject(10, nullableTimestamp(item.publishedAt()));
        ps.setInt(11, item.assetCount());
        ps.setLong(12, item.estimatedBytes());
        ps.setString(13, item.expectedContentToken());
        ps.setLong(14, item.expectedUsageRevision());
        if (item.protectionId() == null) ps.setObject(15, null);
        else ps.setLong(15, item.protectionId());
        ps.setObject(16, nullableTimestamp(item.evaluatedAt()));
        ps.setString(17, item.decision());
        json.bind(ps, 18, item.reason());
        ps.setString(19, item.errorSummary());
      }

      @Override
      public int getBatchSize() {
        return items.size();
      }
    });
  }

  @Override
  public void upsertRunItem(CleanupRunItem item) {
    String updateSql = """
        UPDATE cleanup_run_item
        SET subject_key = ?, family_key = ?, display_name = ?, version = ?, delete_path = ?,
            last_downloaded_at = ?, published_at = ?, asset_count = ?, estimated_bytes = ?,
            expected_content_token = ?, expected_usage_revision = ?, protection_id = ?,
            evaluated_at = ?, decision = ?, reason_json = ?, error_summary = ?,
            updated_at = CURRENT_TIMESTAMP
        WHERE run_repository_id = ? AND subject_kind = ? AND subject_key_hash = ?
          AND subject_key = ?
        """;
    Object[] updateArguments = {
        item.subjectKey(),
        item.familyKey(),
        item.displayName(),
        item.version(),
        item.deletePath(),
        nullableTimestamp(item.lastDownloadedAt()),
        nullableTimestamp(item.publishedAt()),
        item.assetCount(),
        item.estimatedBytes(),
        item.expectedContentToken(),
        item.expectedUsageRevision(),
        item.protectionId(),
        nullableTimestamp(item.evaluatedAt()),
        item.decision(),
        json.parameter(item.reason()),
        item.errorSummary(),
        item.runRepositoryId(),
        item.subjectKind(),
        item.subjectKeyHash(),
        item.subjectKey()
    };
    String insertSql = """
        INSERT INTO cleanup_run_item
          (run_repository_id, subject_kind, subject_key, subject_key_hash, family_key,
           display_name, version, delete_path, last_downloaded_at, published_at,
           asset_count, estimated_bytes, expected_content_token, expected_usage_revision,
           protection_id, evaluated_at, decision, reason_json, error_summary)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    Object[] insertArguments = {
        item.runRepositoryId(),
        item.subjectKind(),
        item.subjectKey(),
        item.subjectKeyHash(),
        item.familyKey(),
        item.displayName(),
        item.version(),
        item.deletePath(),
        nullableTimestamp(item.lastDownloadedAt()),
        nullableTimestamp(item.publishedAt()),
        item.assetCount(),
        item.estimatedBytes(),
        item.expectedContentToken(),
        item.expectedUsageRevision(),
        item.protectionId(),
        nullableTimestamp(item.evaluatedAt()),
        item.decision(),
        json.parameter(item.reason()),
        item.errorSummary()
    };
    JdbcUpserts.updateThenInsert(
        jdbc, updateSql, updateArguments, insertSql, insertArguments);
  }

  @Override
  public void upsertRunItems(List<CleanupRunItem> items) {
    if (items == null || items.isEmpty()) return;
    int[] updated = jdbc.batchUpdate("""
        UPDATE cleanup_run_item
        SET subject_key = ?, family_key = ?, display_name = ?, version = ?, delete_path = ?,
            last_downloaded_at = ?, published_at = ?, asset_count = ?, estimated_bytes = ?,
            expected_content_token = ?, expected_usage_revision = ?, protection_id = ?,
            evaluated_at = ?, decision = ?, reason_json = ?, error_summary = ?,
            updated_at = CURRENT_TIMESTAMP
        WHERE run_repository_id = ? AND subject_kind = ? AND subject_key_hash = ?
          AND subject_key = ?
        """, new BatchPreparedStatementSetter() {
      @Override
      public void setValues(PreparedStatement ps, int index) throws SQLException {
        CleanupRunItem item = items.get(index);
        ps.setString(1, item.subjectKey());
        ps.setString(2, item.familyKey());
        ps.setString(3, item.displayName());
        ps.setString(4, item.version());
        ps.setString(5, item.deletePath());
        ps.setObject(6, nullableTimestamp(item.lastDownloadedAt()));
        ps.setObject(7, nullableTimestamp(item.publishedAt()));
        ps.setInt(8, item.assetCount());
        ps.setLong(9, item.estimatedBytes());
        ps.setString(10, item.expectedContentToken());
        ps.setLong(11, item.expectedUsageRevision());
        if (item.protectionId() == null) ps.setObject(12, null);
        else ps.setLong(12, item.protectionId());
        ps.setObject(13, nullableTimestamp(item.evaluatedAt()));
        ps.setString(14, item.decision());
        json.bind(ps, 15, item.reason());
        ps.setString(16, item.errorSummary());
        ps.setLong(17, item.runRepositoryId());
        ps.setString(18, item.subjectKind());
        ps.setBytes(19, item.subjectKeyHash());
        ps.setString(20, item.subjectKey());
      }

      @Override
      public int getBatchSize() {
        return items.size();
      }
    });
    List<CleanupRunItem> missing = new ArrayList<>();
    for (int index = 0; index < updated.length; index++) {
      if (updated[index] == 0) missing.add(items.get(index));
    }
    insertRunItems(missing);
  }

  @Override
  public List<CleanupRunItem> listRunItems(long runRepositoryId, long afterId, int maxItems) {
    return jdbc.query("""
        SELECT * FROM cleanup_run_item
        WHERE run_repository_id = ? AND id > ?
        ORDER BY id LIMIT ?
        """, runItemMapper, runRepositoryId, Math.max(0, afterId), Math.max(1, maxItems));
  }

  @Override
  public Map<Long, List<CleanupRunItem>> listRunItems(
      Collection<Long> runRepositoryIds, int maxItemsPerRepository) {
    List<Long> repositoryIds = distinctIds(runRepositoryIds);
    if (repositoryIds.isEmpty()) return Map.of();
    int limit = Math.max(1, Math.min(200, maxItemsPerRepository));
    Map<Long, List<CleanupRunItem>> result = new LinkedHashMap<>();
    for (List<Long> batch : batches(repositoryIds, 50)) {
      String shardQueries = batch.stream()
          .map(ignored -> "(SELECT * FROM " + orderedRunItemTable
              + " WHERE run_repository_id = ? ORDER BY id LIMIT ?)")
          .collect(java.util.stream.Collectors.joining(" UNION ALL "));
      List<Object> arguments = new ArrayList<>(batch.size() * 2);
      for (Long repositoryId : batch) {
        arguments.add(repositoryId);
        arguments.add(limit);
      }
      jdbc.query(
          "SELECT bounded.* FROM (" + shardQueries + ") bounded "
              + "ORDER BY bounded.run_repository_id, bounded.id",
          rs -> {
            int rowNum = 0;
            while (rs.next()) {
              CleanupRunItem item = runItemMapper.mapRow(rs, rowNum++);
              result.computeIfAbsent(item.runRepositoryId(), ignored -> new ArrayList<>()).add(item);
            }
            return null;
          },
          arguments.toArray());
    }
    result.replaceAll((ignored, items) -> List.copyOf(items));
    return Map.copyOf(result);
  }

  @Override
  @Transactional
  public int deleteTerminalRunsBefore(
      Instant completedBefore, int maxItems, int minimumRunsPerPolicy) {
    return pruneTerminalRunHistory(
        completedBefore, maxItems, minimumRunsPerPolicy, 5_000).deletedRuns();
  }

  @Override
  @Transactional
  public CleanupHistoryPruneResult pruneTerminalRunHistory(
      Instant completedBefore,
      int maxRuns,
      int minimumRunsPerPolicy,
      int maxRunItems) {
    if (completedBefore == null) {
      throw new IllegalArgumentException("completedBefore is required");
    }
    int limit = Math.max(1, Math.min(1000, maxRuns));
    int retained = Math.max(1, Math.min(1000, minimumRunsPerPolicy));
    int itemLimit = Math.max(1, Math.min(50_000, maxRunItems));
    List<Long> candidates = jdbc.queryForList("""
        SELECT cleanup.id
        FROM cleanup_run cleanup
        JOIN (
          SELECT policy_id, id AS retained_floor
          FROM (
            SELECT policy_id,
                   id,
                   ROW_NUMBER() OVER (
                     PARTITION BY policy_id ORDER BY id DESC
                   ) AS retention_rank
            FROM cleanup_run
          ) ranked
          WHERE retention_rank = ?
        ) retention
          ON retention.policy_id = cleanup.policy_id
         AND cleanup.id < retention.retained_floor
        WHERE cleanup.state IN (
            'SUCCEEDED', 'SUCCEEDED_TRUNCATED', 'PARTIAL_LIMIT_REACHED',
            'PARTIAL', 'FAILED', 'CANCELLED')
          AND cleanup.completed_at < ?
        ORDER BY cleanup.completed_at, cleanup.id
        LIMIT ?
        """,
        Long.class,
        retained,
        nullableTimestamp(completedBefore),
        limit);
    List<Long> ids = new ArrayList<>(candidates.size());
    for (List<Long> batch : batches(candidates, IN_QUERY_BATCH_SIZE)) {
      ids.addAll(jdbc.queryForList(
          "SELECT id FROM cleanup_run WHERE id IN (" + placeholders(batch.size()) + ") "
              + "ORDER BY id FOR UPDATE SKIP LOCKED",
          Long.class,
          batch.toArray()));
    }
    if (ids.isEmpty()) return new CleanupHistoryPruneResult(0, 0);
    List<Long> itemIds = new ArrayList<>(Math.min(itemLimit, 5_000));
    for (List<Long> batch : batches(ids, IN_QUERY_BATCH_SIZE)) {
      int remaining = itemLimit - itemIds.size();
      if (remaining <= 0) break;
      itemIds.addAll(jdbc.queryForList("""
          SELECT item.id
          FROM cleanup_run_item item
          JOIN cleanup_run_repository repository
            ON repository.id = item.run_repository_id
          WHERE repository.run_id IN (""" + placeholders(batch.size()) + """
          )
          ORDER BY item.id
          LIMIT ?
          FOR UPDATE SKIP LOCKED
          """, Long.class, append(batch, remaining)));
    }
    int deletedItems = 0;
    for (List<Long> batch : batches(itemIds, IN_QUERY_BATCH_SIZE)) {
      deletedItems += jdbc.update(
          "DELETE FROM cleanup_run_item WHERE id IN (" + placeholders(batch.size()) + ")",
          batch.toArray());
    }
    int deletedRuns = 0;
    for (List<Long> batch : batches(ids, IN_QUERY_BATCH_SIZE)) {
      deletedRuns += jdbc.update("""
          DELETE FROM cleanup_run
          WHERE id IN (""" + placeholders(batch.size()) + """
          )
            AND NOT EXISTS (
              SELECT 1
              FROM cleanup_run_repository repository
              JOIN cleanup_run_item item ON item.run_repository_id = repository.id
              WHERE repository.run_id = cleanup_run.id
            )
          """, batch.toArray());
    }
    return new CleanupHistoryPruneResult(deletedRuns, deletedItems);
  }

  @Override
  public CleanupOperationalSummary operationalSummary() {
    return jdbc.query("""
        SELECT
          COALESCE(SUM(CASE WHEN state = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending_shards,
          COALESCE(SUM(CASE WHEN state = 'RETRY_WAIT' THEN 1 ELSE 0 END), 0)
            AS retry_waiting_shards,
          COALESCE(SUM(CASE WHEN state = 'RUNNING' THEN 1 ELSE 0 END), 0)
            AS running_shards,
          COALESCE(SUM(CASE
            WHEN state = 'RUNNING' AND lease_until < CURRENT_TIMESTAMP THEN 1 ELSE 0 END), 0)
            AS expired_running_leases,
          MIN(CASE WHEN state IN ('PENDING', 'RETRY_WAIT', 'RUNNING')
            THEN created_at ELSE NULL END) AS oldest_outstanding_created_at
        FROM cleanup_run_repository
        WHERE state IN ('PENDING', 'RETRY_WAIT', 'RUNNING')
        """, rs -> {
      if (!rs.next()) return new CleanupOperationalSummary(0, 0, 0, 0, null);
      return new CleanupOperationalSummary(
          rs.getLong("pending_shards"),
          rs.getLong("retry_waiting_shards"),
          rs.getLong("running_shards"),
          rs.getLong("expired_running_leases"),
          nullableInstant(rs, "oldest_outstanding_created_at"));
    });
  }

  @Override
  @Transactional
  public boolean synchronizeUsageTracking(
      Map<Long, Instant> repositoryTrackingStartedAt, Instant now) {
    lockUsageTrackingRevision();
    Map<Long, Instant> required = repositoryTrackingStartedAt == null
        ? Map.of()
        : Map.copyOf(repositoryTrackingStartedAt);
    Map<Long, Instant> existing = new LinkedHashMap<>();
    jdbc.query("""
        SELECT repository_id, tracking_started_at
        FROM cleanup_usage_tracking_repository
        ORDER BY repository_id
        FOR UPDATE
        """, rs -> {
      while (rs.next()) {
        existing.put(rs.getLong("repository_id"), nullableInstant(rs, "tracking_started_at"));
      }
      return null;
    });
    List<Long> removed = existing.keySet().stream()
        .filter(repositoryId -> !required.containsKey(repositoryId))
        .toList();
    List<Map.Entry<Long, Instant>> added = required.entrySet().stream()
        .filter(entry -> !existing.containsKey(entry.getKey()))
        .toList();
    List<Map.Entry<Long, Instant>> movedEarlier = required.entrySet().stream()
        .filter(entry -> existing.containsKey(entry.getKey()))
        .filter(entry -> entry.getValue().isBefore(existing.get(entry.getKey())))
        .toList();
    if (removed.isEmpty() && added.isEmpty() && movedEarlier.isEmpty()) return false;

    for (List<Long> batch : batches(removed, IN_QUERY_BATCH_SIZE)) {
      jdbc.update(
          "DELETE FROM cleanup_usage_tracking_repository WHERE repository_id IN ("
              + placeholders(batch.size()) + ")",
          batch.toArray());
    }
    if (!movedEarlier.isEmpty()) {
      jdbc.batchUpdate("""
          UPDATE cleanup_usage_tracking_repository
          SET tracking_started_at = ?, updated_at = ?
          WHERE repository_id = ?
          """, movedEarlier, movedEarlier.size(), (ps, entry) -> {
        ps.setObject(1, nullableTimestamp(entry.getValue()));
        ps.setObject(2, nullableTimestamp(now));
        ps.setLong(3, entry.getKey());
      });
    }
    if (!added.isEmpty()) {
      jdbc.batchUpdate("""
          INSERT INTO cleanup_usage_tracking_repository
            (repository_id, tracking_started_at, updated_at)
          VALUES (?, ?, ?)
          """, added, added.size(), (ps, entry) -> {
        ps.setLong(1, entry.getKey());
        ps.setObject(2, nullableTimestamp(entry.getValue()));
        ps.setObject(3, nullableTimestamp(now));
      });
    }
    coordination.bumpCacheVersion(jdbc, USAGE_TRACKING_REVISION);
    return true;
  }

  @Override
  public long usageTrackingRevision() {
    List<Long> values = jdbc.query("""
        SELECT version FROM cache_version WHERE name = ?
        """, (rs, rowNum) -> rs.getLong("version"), USAGE_TRACKING_REVISION);
    return values.isEmpty() ? 0 : values.get(0);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockUsageTrackingProjection() {
    lockUsageTrackingRevision();
  }

  private void lockUsageTrackingRevision() {
    List<Long> revision = jdbc.queryForList(
        "SELECT version FROM cache_version WHERE name = ? FOR UPDATE",
        Long.class,
        USAGE_TRACKING_REVISION);
    if (!revision.isEmpty()) return;

    // Bootstrap is the only path allowed to bump without a projection change. Concurrent first
    // reconcilers may both reach this point; the dialect upsert serializes them on the same row.
    coordination.bumpCacheVersion(jdbc, USAGE_TRACKING_REVISION);
    if (jdbc.queryForList(
        "SELECT version FROM cache_version WHERE name = ? FOR UPDATE",
        Long.class,
        USAGE_TRACKING_REVISION).isEmpty()) {
      throw new IllegalStateException("cleanup usage revision row disappeared");
    }
  }

  @Override
  public List<UsageTrackingRepository> listUsageTrackingRepositories() {
    return jdbc.query("""
        SELECT repository_id, tracking_started_at
        FROM cleanup_usage_tracking_repository
        ORDER BY repository_id
        """, (rs, rowNum) -> new UsageTrackingRepository(
        rs.getLong("repository_id"),
        nullableInstant(rs, "tracking_started_at")));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<CleanupUsage> upsertAssetUsage(long assetId, Instant observedAt) {
    Long repositoryId = jdbc.query("""
        SELECT repository_id FROM asset WHERE id = ? FOR UPDATE
        """,
        (rs, rowNum) -> rs.getLong(1), assetId).stream().findFirst().orElse(null);
    if (repositoryId == null) {
      throw new IllegalStateException(
          "cleanup usage asset disappeared before its download watermark was stored: " + assetId);
    }
    boolean tracked = !jdbc.queryForList("""
        SELECT repository_id FROM cleanup_usage_tracking_repository
        WHERE repository_id = ?
        """, Long.class, repositoryId).isEmpty();
    if (!tracked) return Optional.empty();
    String updateSql = """
        UPDATE cleanup_usage
        SET usage_revision = CASE
              WHEN last_downloaded_at < ? THEN usage_revision + 1 ELSE usage_revision END,
            first_downloaded_at = CASE
              WHEN first_downloaded_at > ? THEN ? ELSE first_downloaded_at END,
            last_downloaded_at = CASE
              WHEN last_downloaded_at < ? THEN ? ELSE last_downloaded_at END,
            updated_at = CURRENT_TIMESTAMP
        WHERE asset_id = ?
        """;
    Object[] updateArguments = {
        nullableTimestamp(observedAt),
        nullableTimestamp(observedAt),
        nullableTimestamp(observedAt),
        nullableTimestamp(observedAt),
        nullableTimestamp(observedAt),
        assetId
    };
    JdbcUpserts.updateThenInsert(
        jdbc,
        updateSql,
        updateArguments,
        """
            INSERT INTO cleanup_usage
              (asset_id, repository_id, first_downloaded_at, last_downloaded_at,
               usage_revision)
            VALUES (?, ?, ?, ?, 1)
            """,
        new Object[] {
            assetId,
            repositoryId,
            nullableTimestamp(observedAt),
            nullableTimestamp(observedAt)
        });
    jdbc.update("""
        UPDATE asset
        SET last_downloaded_at = CASE
          WHEN last_downloaded_at IS NULL OR last_downloaded_at < ? THEN ?
          ELSE last_downloaded_at END
        WHERE id = ?
        """, nullableTimestamp(observedAt), nullableTimestamp(observedAt), assetId);
    return findAssetUsage(List.of(assetId)).values().stream().findFirst();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public CleanupUsageWriteOutcome recordAssetUsage(
      long assetId, long sourceRepositoryId, Duration coalescingTtl) {
    UsageWriteContext context = jdbc.query("""
        SELECT a.repository_id,
               a.last_downloaded_at AS asset_last_downloaded_at,
               (SELECT u.last_downloaded_at
                  FROM cleanup_usage u
                 WHERE u.asset_id = a.id) AS usage_last_downloaded_at,
               CURRENT_TIMESTAMP AS database_now
        FROM asset a
        JOIN cleanup_usage_tracking_repository tracking
          ON tracking.repository_id = a.repository_id
        WHERE a.id = ? AND a.repository_id = ?
        FOR UPDATE
        """, (rs, rowNum) -> new UsageWriteContext(
        rs.getLong("repository_id"),
        nullableInstant(rs, "asset_last_downloaded_at"),
        nullableInstant(rs, "usage_last_downloaded_at"),
        nullableInstant(rs, "database_now")), assetId, sourceRepositoryId)
        .stream()
        .findFirst()
        .orElse(null);
    if (context == null) {
      Long actualRepositoryId = jdbc.queryForList(
              "SELECT repository_id FROM asset WHERE id = ?", Long.class, assetId)
          .stream()
          .findFirst()
          .orElse(null);
      if (actualRepositoryId == null) {
        throw new IllegalStateException(
            "cleanup usage asset disappeared before its download watermark was stored: " + assetId);
      }
      if (actualRepositoryId != sourceRepositoryId) {
        throw new IllegalStateException(
            "cleanup usage source repository does not own asset " + assetId);
      }
      return CleanupUsageWriteOutcome.NOT_TRACKED;
    }
    Instant databaseNow = Objects.requireNonNull(context.databaseNow(), "databaseNow");
    Instant latest = latest(context.assetLastDownloadedAt(), context.usageLastDownloadedAt());
    Duration ttl = coalescingTtl == null || coalescingTtl.isNegative()
        ? Duration.ZERO
        : coalescingTtl;
    if (!ttl.isZero() && latest != null && !latest.isBefore(databaseNow.minus(ttl))) {
      return CleanupUsageWriteOutcome.COALESCED;
    }

    JdbcUpserts.updateThenInsert(
        jdbc,
        """
            UPDATE cleanup_usage
            SET usage_revision = CASE
                  WHEN last_downloaded_at < ? THEN usage_revision + 1
                  ELSE usage_revision END,
                first_downloaded_at = CASE
                  WHEN first_downloaded_at > ? THEN ? ELSE first_downloaded_at END,
                last_downloaded_at = CASE
                  WHEN last_downloaded_at < ? THEN ? ELSE last_downloaded_at END,
                updated_at = CURRENT_TIMESTAMP
            WHERE asset_id = ?
            """,
        new Object[] {
            nullableTimestamp(databaseNow),
            nullableTimestamp(databaseNow),
            nullableTimestamp(databaseNow),
            nullableTimestamp(databaseNow),
            nullableTimestamp(databaseNow),
            assetId
        },
        """
            INSERT INTO cleanup_usage
              (asset_id, repository_id, first_downloaded_at, last_downloaded_at,
               usage_revision)
            VALUES (?, ?, ?, ?, 1)
            """,
        new Object[] {
            assetId,
            context.repositoryId(),
            nullableTimestamp(databaseNow),
            nullableTimestamp(databaseNow)
        });
    jdbc.update("""
        UPDATE asset
        SET last_downloaded_at = CASE
          WHEN last_downloaded_at IS NULL OR last_downloaded_at < ? THEN ?
          ELSE last_downloaded_at END
        WHERE id = ?
        """, nullableTimestamp(databaseNow), nullableTimestamp(databaseNow), assetId);
    return CleanupUsageWriteOutcome.WRITTEN;
  }

  private static Instant latest(Instant left, Instant right) {
    if (left == null) return right;
    if (right == null) return left;
    return left.isAfter(right) ? left : right;
  }

  @Override
  public Map<Long, CleanupUsage> findAssetUsage(Collection<Long> assetIds) {
    List<Long> ids = assetIds == null
        ? List.of()
        : assetIds.stream().filter(java.util.Objects::nonNull).filter(id -> id > 0).distinct().toList();
    if (ids.isEmpty()) return Map.of();
    Map<Long, CleanupUsage> result = new HashMap<>();
    for (int offset = 0; offset < ids.size(); offset += 500) {
      List<Long> batch = ids.subList(offset, Math.min(ids.size(), offset + 500));
      String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
      jdbc.query("""
          SELECT asset_id, repository_id, first_downloaded_at, last_downloaded_at,
                 usage_revision, updated_at
          FROM cleanup_usage WHERE asset_id IN (
          """ + placeholders + ")", rs -> {
        CleanupUsage usage = new CleanupUsage(
            rs.getLong("asset_id"),
            rs.getLong("repository_id"),
            nullableInstant(rs, "first_downloaded_at"),
            nullableInstant(rs, "last_downloaded_at"),
            rs.getLong("usage_revision"),
            nullableInstant(rs, "updated_at"));
        result.put(usage.assetId(), usage);
      }, batch.toArray());
    }
    return Map.copyOf(result);
  }

  private record UsageWriteContext(
      long repositoryId,
      Instant assetLastDownloadedAt,
      Instant usageLastDownloadedAt,
      Instant databaseNow) {
  }

  @Override
  public long createProtection(CleanupProtection protection) {
    return JdbcInserts.insert(jdbc, """
        INSERT INTO cleanup_protection
          (scope, repository_id, subject_kind, subject_key, subject_key_hash, source,
           external_id, reason, enabled, expires_at, freshness_at, created_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> bindProtection(ps, protection, false));
  }

  @Override
  public Optional<CleanupProtection> findProtection(long protectionId) {
    return jdbc.query(
        "SELECT * FROM cleanup_protection WHERE id = ?",
        this::mapProtection,
        protectionId).stream().findFirst();
  }

  @Override
  public List<CleanupProtection> listProtections(
      long afterId, int maxItems, Instant activeAt) {
    if (activeAt == null) {
      return jdbc.query("""
          SELECT * FROM cleanup_protection WHERE id > ? ORDER BY id LIMIT ?
          """, this::mapProtection, Math.max(0, afterId), Math.max(1, maxItems));
    }
    return jdbc.query("""
        SELECT * FROM cleanup_protection
        WHERE id > ? AND enabled = TRUE
          AND (expires_at IS NULL OR expires_at > ?)
          AND (freshness_at IS NULL OR freshness_at >= ?)
        ORDER BY id LIMIT ?
        """,
        this::mapProtection,
        Math.max(0, afterId),
        nullableTimestamp(activeAt),
        nullableTimestamp(activeAt),
        Math.max(1, maxItems));
  }

  @Override
  public boolean updateProtection(CleanupProtection protection, Instant expectedUpdatedAt) {
    return jdbc.update("""
        UPDATE cleanup_protection
        SET scope = ?, repository_id = ?, subject_kind = ?, subject_key = ?,
            subject_key_hash = ?, source = ?, external_id = ?, reason = ?, enabled = ?,
            expires_at = ?, freshness_at = ?, updated_at = ?
        WHERE id = ? AND updated_at = ?
        """,
        protection.scope(),
        protection.repositoryId(),
        protection.subjectKind(),
        protection.subjectKey(),
        protection.subjectKeyHash(),
        protection.source(),
        protection.externalId(),
        protection.reason(),
        protection.enabled(),
        nullableTimestamp(protection.expiresAt()),
        nullableTimestamp(protection.freshnessAt()),
        nullableTimestamp(protection.updatedAt()),
        protection.id(),
        nullableTimestamp(expectedUpdatedAt)) == 1;
  }

  @Override
  public boolean deleteProtection(long protectionId, Instant expectedUpdatedAt) {
    return jdbc.update(
        "DELETE FROM cleanup_protection WHERE id = ? AND updated_at = ?",
        protectionId,
        nullableTimestamp(expectedUpdatedAt)) == 1;
  }

  @Override
  public Optional<CleanupProtection> findActiveProtection(
      long repositoryId,
      String subjectKind,
      String subjectKey,
      byte[] subjectKeyHash,
      Instant activeAt) {
    return jdbc.query("""
        SELECT * FROM cleanup_protection
        WHERE enabled = TRUE
          AND (scope = 'GLOBAL' OR repository_id = ?)
          AND (subject_kind IS NULL OR subject_kind = ?)
          AND (subject_key_hash IS NULL OR subject_key_hash = ?)
          AND (subject_key IS NULL OR subject_key = ?)
          AND (expires_at IS NULL OR expires_at > ?)
          AND (freshness_at IS NULL OR freshness_at >= ?)
        ORDER BY
          CASE scope WHEN 'SUBJECT' THEN 0 WHEN 'REPOSITORY' THEN 1 ELSE 2 END,
          id
        LIMIT 1
        """,
        this::mapProtection,
        repositoryId,
        subjectKind,
        subjectKeyHash,
        subjectKey,
        nullableTimestamp(activeAt),
        nullableTimestamp(activeAt)).stream().findFirst();
  }

  @Override
  public Map<String, CleanupProtection> findActiveProtections(
      long repositoryId,
      Collection<CleanupProtectionLookup> requestedLookups,
      Instant activeAt) {
    List<CleanupProtectionLookup> lookups = requestedLookups == null
        ? List.of()
        : requestedLookups.stream()
            .filter(Objects::nonNull)
            .filter(lookup -> lookup.lookupId() != null && lookup.subjectKeyHash() != null)
            .toList();
    if (lookups.isEmpty()) return Map.of();

    List<CleanupProtection> protections = new ArrayList<>(jdbc.query("""
        SELECT * FROM cleanup_protection
        WHERE enabled = TRUE
          AND scope = 'GLOBAL'
          AND repository_id IS NULL
          AND subject_key_hash IS NULL
          AND (expires_at IS NULL OR expires_at > ?)
          AND (freshness_at IS NULL OR freshness_at >= ?)
        """,
        this::mapProtection,
        nullableTimestamp(activeAt),
        nullableTimestamp(activeAt)));
    protections.addAll(jdbc.query("""
        SELECT * FROM cleanup_protection
        WHERE enabled = TRUE
          AND scope = 'REPOSITORY'
          AND repository_id = ?
          AND subject_key_hash IS NULL
          AND (expires_at IS NULL OR expires_at > ?)
          AND (freshness_at IS NULL OR freshness_at >= ?)
        """,
        this::mapProtection,
        repositoryId,
        nullableTimestamp(activeAt),
        nullableTimestamp(activeAt)));

    Map<String, byte[]> uniqueHashes = new LinkedHashMap<>();
    lookups.forEach(lookup -> uniqueHashes.putIfAbsent(
        protectionHashKey(lookup.subjectKeyHash()), lookup.subjectKeyHash()));
    List<byte[]> hashes = List.copyOf(uniqueHashes.values());
    for (int offset = 0; offset < hashes.size(); offset += 400) {
      List<byte[]> batch = hashes.subList(offset, Math.min(hashes.size(), offset + 400));
      List<Object> arguments = new ArrayList<>(batch.size() + 3);
      arguments.add(repositoryId);
      arguments.addAll(batch);
      arguments.add(nullableTimestamp(activeAt));
      arguments.add(nullableTimestamp(activeAt));
      protections.addAll(jdbc.query("""
          SELECT * FROM cleanup_protection
          WHERE enabled = TRUE
            AND scope = 'SUBJECT'
            AND repository_id = ?
            AND subject_key_hash IN ("""
              + String.join(",", java.util.Collections.nCopies(batch.size(), "?")) + """
            )
            AND (expires_at IS NULL OR expires_at > ?)
            AND (freshness_at IS NULL OR freshness_at >= ?)
          """, this::mapProtection, arguments.toArray()));
    }

    List<CleanupProtection> broadProtections = new ArrayList<>();
    Map<String, List<CleanupProtection>> protectionsByHash = new HashMap<>();
    for (CleanupProtection protection : protections) {
      if (protection.subjectKeyHash() == null) {
        broadProtections.add(protection);
      } else {
        protectionsByHash.computeIfAbsent(
            protectionHashKey(protection.subjectKeyHash()), ignored -> new ArrayList<>())
            .add(protection);
      }
    }
    broadProtections.sort(PROTECTION_PRIORITY);
    protectionsByHash.values().forEach(values -> values.sort(PROTECTION_PRIORITY));
    Map<String, CleanupProtection> result = new LinkedHashMap<>();
    for (CleanupProtectionLookup lookup : lookups) {
      CleanupProtection specific = firstMatchingProtection(
          lookup,
          protectionsByHash.getOrDefault(
              protectionHashKey(lookup.subjectKeyHash()), List.of()));
      CleanupProtection broad = firstMatchingProtection(lookup, broadProtections);
      CleanupProtection selected = specific == null
          ? broad
          : broad == null || PROTECTION_PRIORITY.compare(specific, broad) <= 0
              ? specific
              : broad;
      if (selected != null) result.put(lookup.lookupId(), selected);
    }
    return Map.copyOf(result);
  }

  private static CleanupProtection firstMatchingProtection(
      CleanupProtectionLookup lookup, List<CleanupProtection> protections) {
    for (CleanupProtection protection : protections) {
      if (protectionMatches(lookup, protection)) return protection;
    }
    return null;
  }

  private static String protectionHashKey(byte[] hash) {
    return HexFormat.of().formatHex(hash);
  }

  private static boolean protectionMatches(
      CleanupProtectionLookup lookup, CleanupProtection protection) {
    return (protection.subjectKind() == null
            || protection.subjectKind().equals(lookup.subjectKind()))
        && (protection.subjectKeyHash() == null
            || Arrays.equals(protection.subjectKeyHash(), lookup.subjectKeyHash()))
        && (protection.subjectKey() == null
            || protection.subjectKey().equals(lookup.subjectKey()));
  }

  private static int protectionScopeRank(String scope) {
    return switch (scope) {
      case "SUBJECT" -> 0;
      case "REPOSITORY" -> 1;
      default -> 2;
    };
  }

  private void bindProtection(
      PreparedStatement ps, CleanupProtection protection, boolean includeId) throws SQLException {
    ps.setString(1, protection.scope());
    if (protection.repositoryId() == null) ps.setObject(2, null);
    else ps.setLong(2, protection.repositoryId());
    ps.setString(3, protection.subjectKind());
    ps.setString(4, protection.subjectKey());
    ps.setBytes(5, protection.subjectKeyHash());
    ps.setString(6, protection.source());
    ps.setString(7, protection.externalId());
    ps.setString(8, protection.reason());
    ps.setBoolean(9, protection.enabled());
    ps.setObject(10, nullableTimestamp(protection.expiresAt()));
    ps.setObject(11, nullableTimestamp(protection.freshnessAt()));
    ps.setString(12, protection.createdBy());
    if (includeId) ps.setLong(13, protection.id());
  }

  private CleanupProtection mapProtection(java.sql.ResultSet rs, int rowNum) throws SQLException {
    return new CleanupProtection(
        rs.getLong("id"),
        rs.getString("scope"),
        rs.getObject("repository_id") == null ? null : rs.getLong("repository_id"),
        rs.getString("subject_kind"),
        rs.getString("subject_key"),
        rs.getBytes("subject_key_hash"),
        rs.getString("source"),
        rs.getString("external_id"),
        rs.getString("reason"),
        rs.getBoolean("enabled"),
        nullableInstant(rs, "expires_at"),
        nullableInstant(rs, "freshness_at"),
        rs.getString("created_by"),
        nullableInstant(rs, "created_at"),
        nullableInstant(rs, "updated_at"));
  }

  private static CleanupScanCursor mapScanCursor(java.sql.ResultSet rs) throws SQLException {
    return new CleanupScanCursor(
        rs.getLong("policy_id"),
        rs.getLong("repository_id"),
        rs.getString("phase"),
        rs.getString("component_namespace"),
        rs.getString("component_name"),
        rs.getString("component_kind"),
        rs.getLong("subject_id"),
        rs.getLong("revision"),
        rs.getLong("wrapped_count"));
  }

  private static boolean sameCursor(CleanupScanCursor left, CleanupScanCursor right) {
    return left.policyId() == right.policyId()
        && left.repositoryId() == right.repositoryId()
        && left.subjectId() == right.subjectId()
        && left.revision() == right.revision()
        && left.wrappedCount() == right.wrappedCount()
        && Objects.equals(left.phase(), right.phase())
        && Objects.equals(left.componentNamespace(), right.componentNamespace())
        && Objects.equals(left.componentName(), right.componentName())
        && Objects.equals(left.componentKind(), right.componentKind());
  }

  private List<Map<String, Object>> snapshot(String value) {
    List<Map<String, Object>> result = json.readValue(value, REPOSITORY_SNAPSHOT_TYPE);
    return result == null ? List.of() : result;
  }

  private static List<Long> distinctIds(Collection<Long> values) {
    if (values == null || values.isEmpty()) return List.of();
    return values.stream().filter(Objects::nonNull).distinct().toList();
  }

  private static <T> List<List<T>> batches(List<T> values, int batchSize) {
    if (values == null || values.isEmpty()) return List.of();
    List<List<T>> result = new ArrayList<>();
    for (int start = 0; start < values.size(); start += batchSize) {
      result.add(values.subList(start, Math.min(values.size(), start + batchSize)));
    }
    return result;
  }

  private static String placeholders(int count) {
    return String.join(",", java.util.Collections.nCopies(Math.max(1, count), "?"));
  }

  private static Object[] append(List<Long> values, Object tail) {
    Object[] arguments = new Object[values.size() + 1];
    for (int index = 0; index < values.size(); index++) arguments[index] = values.get(index);
    arguments[values.size()] = tail;
    return arguments;
  }

  private record ClaimCandidate(
      long id,
      long runId,
      long repositoryId,
      String repositoryName,
      RepositoryFormat format,
      RepositoryType repositoryType,
      String state,
      int attemptCount,
      int maxAttempts,
      long fencingToken,
      Long leaseRunRepositoryId,
      Instant leaseExpiresAt) {
  }

  private record RunCursorSnapshot(
      long policyId,
      long repositoryId,
      String phase,
      String componentNamespace,
      String componentName,
      String componentKind,
      long subjectId,
      long revision,
      long wrappedCount) {
    CleanupScanCursor toCursor() {
      return new CleanupScanCursor(
          policyId,
          repositoryId,
          phase,
          componentNamespace,
          componentName,
          componentKind,
          subjectId,
          revision,
          wrappedCount);
    }
  }
}
