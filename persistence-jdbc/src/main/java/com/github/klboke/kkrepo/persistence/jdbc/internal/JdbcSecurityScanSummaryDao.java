package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableTimestamp;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanMetricSummary;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanSummary;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only aggregate and metric queries for security-scan observability. */
final class JdbcSecurityScanSummaryDao {
  private final JdbcTemplate jdbc;
  private final JdbcSecurityScanRepositoryScope scope;

  JdbcSecurityScanSummaryDao(
      JdbcTemplate jdbc, JdbcSecurityScanRepositoryScope scope) {
    this.jdbc = jdbc;
    this.scope = scope;
  }

  ScanSummary summary() {
    return summary(jdbc.queryForList("SELECT id FROM repository", Long.class));
  }

  ScanSummary summary(long repositoryId) {
    return summary(List.of(repositoryId));
  }

  ScanSummary summary(List<Long> repositoryIds) {
    List<Long> ids = scope.distinctLongs(repositoryIds);
    if (ids.isEmpty()) {
      return new ScanSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
    Object repositoryScope = scope.parameter(ids);
    long candidates = count(scope.taskRepositoryScopeCte() + """
        SELECT COUNT(*)
        FROM security_scan_candidate candidate
        JOIN asset asset ON asset.id = candidate.asset_id
        WHERE candidate.pending = TRUE
          AND EXISTS (
            SELECT 1
            FROM task_repository_scope scope
            JOIN repository source_repository
              ON source_repository.id = scope.source_repository_id
            WHERE scope.source_repository_id = asset.repository_id
              AND (
                scope.directly_visible = TRUE
                OR (
                  (
                    source_repository.type = 'hosted'
                    AND scope.scan_hosted_content = TRUE
                  )
                  OR (
                    source_repository.type = 'proxy'
                    AND scope.scan_proxy_content = TRUE
                  )
                )
              )
          )
        """, repositoryScope);
    Map<String, Object> tasks = jdbc.queryForMap(scope.taskRepositoryScopeCte() + """
        SELECT
          COALESCE(SUM(CASE
            WHEN task.status IN ('PENDING', 'RETRY_WAIT') THEN 1 ELSE 0 END), 0)
            AS pending_tasks,
          COALESCE(SUM(CASE
            WHEN task.status = 'RUNNING' THEN 1 ELSE 0 END), 0)
            AS running_tasks,
          COALESCE(SUM(CASE
            WHEN task.status = 'FAILED' THEN 1 ELSE 0 END), 0)
            AS failed_tasks
        FROM security_scan_task task
        WHERE task.status IN ('PENDING', 'RETRY_WAIT', 'RUNNING', 'FAILED')
          AND EXISTS (
            SELECT 1
            FROM task_repository_scope scope
            JOIN repository source_repository
              ON source_repository.id = scope.source_repository_id
            WHERE scope.source_repository_id = task.repository_id
              AND (
                scope.directly_visible = TRUE
                OR (
                  scope.profile_id = task.profile_id
                  AND (
                    (
                      source_repository.type = 'hosted'
                      AND scope.scan_hosted_content = TRUE
                    )
                    OR (
                      source_repository.type = 'proxy'
                      AND scope.scan_proxy_content = TRUE
                    )
                  )
                )
              )
          )
        """, repositoryScope);
    Instant summaryAt = Instant.now();
    Map<String, Object> states = jdbc.queryForMap(
        scope.recursiveRepositoryScopeCte() + """
        ,
        policy_context_source(context_repository_id, source_repository_id) AS (
          SELECT config.repository_id, config.repository_id
          FROM repository_security_scan_config config
          JOIN visible_repository visible
            ON visible.repository_id = config.repository_id
          WHERE config.enabled = TRUE
          UNION
          SELECT context.context_repository_id, member.member_repository_id
          FROM policy_context_source context
          JOIN repository_member member
            ON member.repository_id = context.source_repository_id
        ),
        policy_subject(context_repository_id, source_repository_id, asset_id) AS (
          SELECT context.context_repository_id, state.repository_id, state.asset_id
          FROM policy_context_source context
          JOIN asset_security_state state
            ON state.repository_id = context.source_repository_id
          UNION
          SELECT context.context_repository_id, asset.repository_id, candidate.asset_id
          FROM policy_context_source context
          JOIN asset asset
            ON asset.repository_id = context.source_repository_id
          JOIN security_scan_candidate candidate
            ON candidate.asset_id = asset.id
        ),
        policy_snapshot AS (
          SELECT
            subject.asset_id,
            state.asset_id AS state_asset_id,
            candidate.asset_id AS candidate_asset_id,
            candidate.content_generation AS candidate_content_generation,
            state.content_generation AS state_content_generation,
            state.scan_state,
            profile.id AS profile_id,
            profile.enabled AS profile_enabled,
            config.pending_action,
            config.failure_action,
            policy_state.policy_decision,
            CASE
              WHEN policy_state.asset_id IS NOT NULL
               AND candidate.asset_id IS NOT NULL
               AND state.asset_id IS NOT NULL
               AND state.scan_state IN ('PARTIAL', 'COMPLETE')
               AND state.content_generation = candidate.content_generation
               AND policy_state.content_generation = candidate.content_generation
               AND (
                 policy_state.latest_scan_run_id = state.latest_scan_run_id
                 OR (
                   policy_state.latest_scan_run_id IS NULL
                   AND state.latest_scan_run_id IS NULL
                 )
               )
               AND policy_state.config_revision = config.config_revision
               AND policy_state.waiver_revision
                   >= waiver_revision.global_invalidation_revision
               AND (
                 (
                   config.policy_id IS NULL
                   AND policy_state.policy_id IS NULL
                   AND policy_state.policy_revision IS NULL
                 )
                 OR (
                   config.policy_id IS NOT NULL
                   AND current_policy.id IS NOT NULL
                   AND policy_state.policy_id = current_policy.id
                   AND policy_state.policy_revision = current_policy.revision
                 )
               )
               AND (
                 policy_state.stale_at IS NULL
                 OR policy_state.stale_at > ?
               )
               AND (
                 policy_state.next_waiver_expiry IS NULL
                 OR policy_state.next_waiver_expiry > ?
               )
              THEN TRUE
              ELSE FALSE
            END AS policy_authoritative
          FROM policy_subject subject
          JOIN repository_security_scan_config config
            ON config.repository_id = subject.context_repository_id
           AND config.enabled = TRUE
          JOIN repository source_repository
            ON source_repository.id = subject.source_repository_id
          JOIN security_scan_waiver_revision waiver_revision
            ON waiver_revision.singleton_id = 1
          LEFT JOIN security_scan_candidate candidate
            ON candidate.asset_id = subject.asset_id
          LEFT JOIN asset_security_state state
            ON state.asset_id = subject.asset_id
           AND state.profile_id = config.profile_id
          LEFT JOIN security_scan_profile profile
            ON profile.id = config.profile_id
          LEFT JOIN security_scan_policy current_policy
            ON current_policy.id = config.policy_id
          LEFT JOIN asset_security_policy_state policy_state
            ON policy_state.asset_id = subject.asset_id
           AND policy_state.profile_id = config.profile_id
           AND policy_state.repository_id = config.repository_id
          WHERE (
            (source_repository.type = 'hosted' AND config.scan_hosted_content = TRUE)
            OR (source_repository.type = 'proxy' AND config.scan_proxy_content = TRUE)
          )
        )
        SELECT
          COUNT(DISTINCT CASE
            WHEN scan_state = 'COMPLETE'
             AND state_content_generation = candidate_content_generation
              THEN asset_id END)
            AS complete_assets,
          COUNT(DISTINCT CASE
            WHEN scan_state = 'PARTIAL'
             AND state_content_generation = candidate_content_generation
              THEN asset_id END)
            AS partial_assets,
          COUNT(DISTINCT CASE
            WHEN scan_state = 'STALE'
             AND state_content_generation = candidate_content_generation
              THEN asset_id END)
            AS stale_assets,
          COUNT(DISTINCT CASE
            WHEN profile_id IS NULL OR profile_enabled = FALSE
              THEN CASE WHEN failure_action = 'BLOCK' THEN asset_id END
            WHEN state_asset_id IS NULL
              OR candidate_asset_id IS NULL
              OR state_content_generation <> candidate_content_generation
              THEN CASE WHEN pending_action = 'BLOCK' THEN asset_id END
            WHEN scan_state = 'NOT_APPLICABLE' THEN NULL
            WHEN scan_state IN ('PENDING', 'RUNNING', 'STALE')
              THEN CASE WHEN pending_action = 'BLOCK' THEN asset_id END
            WHEN scan_state IN ('FAILED', 'CANCELLED')
              THEN CASE WHEN failure_action = 'BLOCK' THEN asset_id END
            WHEN scan_state IN ('PARTIAL', 'COMPLETE')
              AND policy_authoritative = TRUE
              AND policy_decision <> 'ALLOW'
              THEN asset_id
            WHEN scan_state IN ('PARTIAL', 'COMPLETE')
              AND policy_authoritative = FALSE
              THEN CASE WHEN pending_action = 'BLOCK' THEN asset_id END
            ELSE NULL
          END)
            AS blocked_assets
        FROM policy_snapshot
        """,
        repositoryScope,
        nullableTimestamp(summaryAt),
        nullableTimestamp(summaryAt));
    Map<String, Object> findings = jdbc.queryForMap(scope.repositoryScopeCte() + """
        , current_run AS (
          SELECT DISTINCT state.latest_scan_run_id AS scan_run_id
          FROM asset_security_state state
          JOIN security_scan_candidate candidate
            ON candidate.asset_id = state.asset_id
           AND candidate.content_generation = state.content_generation
          JOIN security_scan_run_subject subject
            ON subject.scan_run_id = state.latest_scan_run_id
           AND subject.asset_id = state.asset_id
           AND subject.profile_id = state.profile_id
           AND subject.content_generation = state.content_generation
          JOIN visible_repository visible
            ON visible.repository_id = subject.repository_id
          WHERE state.latest_scan_run_id IS NOT NULL
        )
        SELECT
          COALESCE(SUM(CASE WHEN finding.severity = 'CRITICAL' THEN 1 ELSE 0 END), 0)
            AS critical_findings,
          COALESCE(SUM(CASE WHEN finding.severity = 'HIGH' THEN 1 ELSE 0 END), 0)
            AS high_findings
        FROM current_run
        JOIN security_scan_finding finding
          ON finding.scan_run_id = current_run.scan_run_id
        WHERE finding.severity IN ('CRITICAL', 'HIGH')
        """, repositoryScope);
    return new ScanSummary(
        candidates,
        number(tasks, "pending_tasks"),
        number(tasks, "running_tasks"),
        number(tasks, "failed_tasks"),
        number(states, "complete_assets"),
        number(states, "partial_assets"),
        number(states, "stale_assets"),
        number(states, "blocked_assets"),
        number(findings, "critical_findings"),
        number(findings, "high_findings"));
  }

  ScanMetricSummary metricSummary(int maxCount) {
    int limit = Math.max(1, Math.min(1_000_000, maxCount));
    return new ScanMetricSummary(
        boundedCount("""
            SELECT id FROM security_scan_task
            WHERE status IN ('PENDING', 'RETRY_WAIT')
            """, limit),
        boundedCount("""
            SELECT id FROM security_scan_task
            WHERE status = 'RUNNING'
            """, limit),
        boundedCount("""
            SELECT id FROM security_scan_task
            WHERE status = 'FAILED'
            """, limit),
        boundedCount("""
            SELECT state.asset_id
            FROM asset_security_state state
            JOIN security_scan_candidate candidate
              ON candidate.asset_id = state.asset_id
             AND candidate.content_generation = state.content_generation
            WHERE state.scan_state = 'PARTIAL'
            """, limit),
        boundedCount("""
            SELECT finding.id
            FROM (
              SELECT DISTINCT state.latest_scan_run_id AS scan_run_id
              FROM asset_security_state state
              JOIN security_scan_candidate candidate
                ON candidate.asset_id = state.asset_id
               AND candidate.content_generation = state.content_generation
              WHERE state.latest_scan_run_id IS NOT NULL
            ) current_run
            JOIN security_scan_finding finding
              ON finding.scan_run_id = current_run.scan_run_id
             AND finding.severity IN ('CRITICAL', 'HIGH')
            """, limit));
  }

  Optional<Instant> oldestPendingTaskCreatedAt() {
    List<Instant> values = jdbc.query("""
        SELECT created_at AS oldest_created_at
        FROM security_scan_task
        WHERE status IN ('PENDING','RETRY_WAIT')
        ORDER BY created_at, id
        LIMIT 1
        """, (rs, rowNum) -> nullableInstant(rs, "oldest_created_at"));
    return values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.getFirst());
  }

  private long count(String sql, Object... args) {
    Long value = jdbc.queryForObject(sql, Long.class, args);
    return value == null ? 0 : value;
  }

  private long boundedCount(String selectionSql, int limit) {
    return count(
        "SELECT COUNT(*) FROM (" + selectionSql + " LIMIT ?) bounded_count",
        limit);
  }

  private static long number(Map<String, Object> row, String column) {
    return ((Number) row.get(column)).longValue();
  }
}
