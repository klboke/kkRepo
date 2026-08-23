package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableTimestamp;

import com.github.klboke.kkrepo.persistence.jdbc.api.BlobReferenceDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.RetentionResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Retention and blob-reference cleanup collaborator for {@link JdbcSecurityScanDao}. */
final class JdbcSecurityScanRetentionDao {
  private static final String SBOM_BLOB_OWNER = "security-sbom";
  private static final String SCAN_REPORT_BLOB_OWNER = "security-scan-report";

  private final JdbcTemplate jdbc;
  private final BlobReferenceDao blobReferences;

  JdbcSecurityScanRetentionDao(JdbcTemplate jdbc, BlobReferenceDao blobReferences) {
    this.jdbc = jdbc;
    this.blobReferences = blobReferences;
  }

  RetentionResult cleanupRetainedData(
      Instant terminalTaskCutoff, Instant resultCutoff, int maxItems) {
    int limit = safeLimit(maxItems);
    int tasks = deleteTerminalTasks(terminalTaskCutoff, limit);
    int backfills = deleteTerminalBackfills(terminalTaskCutoff, limit);
    int subjects = deleteHistoricalRunSubjects(resultCutoff, limit);
    int runs = deleteUnreferencedRuns(resultCutoff, limit);
    int sboms = deleteUnreferencedSboms(resultCutoff, limit);
    int snapshots = deleteUnreferencedSnapshots(resultCutoff, limit);
    return new RetentionResult(tasks, backfills, subjects, runs, sboms, snapshots);
  }

  private int deleteTerminalTasks(Instant cutoff, int limit) {
    List<Long> ids = jdbc.queryForList("""
        SELECT id
        FROM security_scan_task
        WHERE status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
          AND finished_at < ?
        ORDER BY finished_at, id
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """, Long.class, nullableTimestamp(cutoff), limit);
    int deleted = 0;
    for (Long id : ids) {
      deleted += jdbc.update("""
          DELETE FROM security_scan_task
          WHERE id = ?
            AND status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
            AND finished_at < ?
          """, id, nullableTimestamp(cutoff));
    }
    return deleted;
  }

  private int deleteTerminalBackfills(Instant cutoff, int limit) {
    List<Long> ids = jdbc.queryForList("""
        SELECT id
        FROM security_scan_backfill_job
        WHERE status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
          AND completed_at < ?
        ORDER BY completed_at, id
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """, Long.class, nullableTimestamp(cutoff), limit);
    int deleted = 0;
    for (Long id : ids) {
      deleted += jdbc.update("""
          DELETE FROM security_scan_backfill_job
          WHERE id = ?
            AND status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
            AND completed_at < ?
          """, id, nullableTimestamp(cutoff));
    }
    return deleted;
  }

  private int deleteHistoricalRunSubjects(Instant cutoff, int limit) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT subject.scan_run_id, subject.repository_id, subject.asset_id,
               subject.profile_id, subject.content_generation
        FROM security_scan_run_subject subject
        JOIN security_scan_run run ON run.id = subject.scan_run_id
        WHERE subject.associated_at < ?
          AND run.last_accessed_at < ?
          AND NOT EXISTS (
            SELECT 1
            FROM asset_security_state asset_state
            WHERE asset_state.latest_scan_run_id = subject.scan_run_id
              AND asset_state.asset_id = subject.asset_id
              AND asset_state.profile_id = subject.profile_id
          )
          AND NOT EXISTS (
            SELECT 1
            FROM asset_security_policy_state policy_state
            WHERE policy_state.latest_scan_run_id = subject.scan_run_id
              AND policy_state.asset_id = subject.asset_id
              AND policy_state.profile_id = subject.profile_id
              AND policy_state.repository_id = subject.repository_id
          )
          AND NOT EXISTS (
            SELECT 1
            FROM security_scan_waiver waiver
            JOIN security_scan_finding finding ON finding.id = waiver.finding_id
            WHERE finding.scan_run_id = subject.scan_run_id
          )
        ORDER BY subject.associated_at, subject.scan_run_id
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """, nullableTimestamp(cutoff), nullableTimestamp(cutoff), limit);
    int deleted = 0;
    for (Map<String, Object> row : rows) {
      deleted += jdbc.update("""
          DELETE FROM security_scan_run_subject
          WHERE scan_run_id = ? AND repository_id = ? AND asset_id = ?
            AND profile_id = ? AND content_generation = ?
          """,
          number(row, "scan_run_id"),
          number(row, "repository_id"),
          number(row, "asset_id"),
          number(row, "profile_id"),
          number(row, "content_generation"));
    }
    return deleted;
  }

  private int deleteUnreferencedRuns(Instant cutoff, int limit) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT run.id, run.raw_report_blob_id
        FROM security_scan_run run
        WHERE run.completed_at < ?
          AND run.last_accessed_at < ?
          AND NOT EXISTS (
            SELECT 1 FROM security_scan_run_subject subject
            WHERE subject.scan_run_id = run.id
          )
          AND NOT EXISTS (
            SELECT 1 FROM asset_security_state asset_state
            WHERE asset_state.latest_scan_run_id = run.id
          )
          AND NOT EXISTS (
            SELECT 1 FROM asset_security_policy_state policy_state
            WHERE policy_state.latest_scan_run_id = run.id
          )
          AND NOT EXISTS (
            SELECT 1
            FROM security_scan_waiver waiver
            JOIN security_scan_finding finding ON finding.id = waiver.finding_id
            WHERE finding.scan_run_id = run.id
          )
        ORDER BY run.last_accessed_at, run.id
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """, nullableTimestamp(cutoff), nullableTimestamp(cutoff), limit);
    int deleted = 0;
    for (Map<String, Object> row : rows) {
      long runId = number(row, "id");
      long blobId = number(row, "raw_report_blob_id");
      blobReferences.release(SCAN_REPORT_BLOB_OWNER, runId, blobId);
      deleted += jdbc.update("DELETE FROM security_scan_run WHERE id = ?", runId);
    }
    return deleted;
  }

  private int deleteUnreferencedSboms(Instant cutoff, int limit) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT sbom.id, sbom.document_blob_id
        FROM security_sbom sbom
        WHERE sbom.created_at < ?
          AND sbom.last_accessed_at < ?
          AND NOT EXISTS (
            SELECT 1 FROM security_scan_run run WHERE run.sbom_id = sbom.id
          )
        ORDER BY sbom.last_accessed_at, sbom.id
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """, nullableTimestamp(cutoff), nullableTimestamp(cutoff), limit);
    int deleted = 0;
    for (Map<String, Object> row : rows) {
      long sbomId = number(row, "id");
      long blobId = number(row, "document_blob_id");
      blobReferences.release(SBOM_BLOB_OWNER, sbomId, blobId);
      deleted += jdbc.update("DELETE FROM security_sbom WHERE id = ?", sbomId);
    }
    return deleted;
  }

  private int deleteUnreferencedSnapshots(Instant cutoff, int limit) {
    List<Long> ids = jdbc.queryForList("""
        SELECT snapshot.id
        FROM security_scanner_snapshot snapshot
        WHERE snapshot.observed_at < ?
          AND NOT EXISTS (
            SELECT 1 FROM security_scan_run run
            WHERE run.scanner_snapshot_id = snapshot.id
          )
          AND NOT EXISTS (
            SELECT 1 FROM security_scan_task task
            WHERE task.requested_scanner_snapshot_id = snapshot.id
          )
        ORDER BY snapshot.observed_at, snapshot.id
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """, Long.class, nullableTimestamp(cutoff), limit);
    int deleted = 0;
    for (Long id : ids) {
      deleted += jdbc.update(
          "DELETE FROM security_scanner_snapshot WHERE id = ?", id);
    }
    return deleted;
  }

  private static long number(Map<String, Object> row, String column) {
    return ((Number) row.get(column)).longValue();
  }

  private static int safeLimit(int maxItems) {
    return Math.max(1, Math.min(maxItems, 1000));
  }
}
