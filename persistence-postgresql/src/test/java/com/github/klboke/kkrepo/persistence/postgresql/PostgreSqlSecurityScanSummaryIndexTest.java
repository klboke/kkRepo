package com.github.klboke.kkrepo.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.persistence.postgresql.support.PostgreSqlIntegrationTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PostgreSqlSecurityScanSummaryIndexTest extends PostgreSqlIntegrationTestSupport {

  @BeforeAll
  static void startBackend() {
    startPostgreSql();
  }

  @Test
  void summaryFactsHaveSelectiveCoveringIndexes() {
    assertEquals(
        "CREATE INDEX idx_asset_repository_id ON public.asset "
            + "USING btree (repository_id, id)",
        indexDefinition("idx_asset_repository_id"));
    assertEquals(
        "CREATE INDEX idx_docker_reference_policy_lookup "
            + "ON public.docker_manifest_reference "
            + "USING btree (repository_id, digest_hash, manifest_id)",
        indexDefinition("idx_docker_reference_policy_lookup"));
    assertEquals(
        "CREATE INDEX idx_security_scan_candidate_queue ON public.security_scan_candidate "
            + "USING btree (pending, changed_at, asset_id)",
        indexDefinition("idx_security_scan_candidate_queue"));
    assertEquals(
        "CREATE INDEX idx_security_scan_task_repository_status ON public.security_scan_task "
            + "USING btree (repository_id, status, id)",
        indexDefinition("idx_security_scan_task_repository_status"));
    assertEquals(
        "CREATE INDEX idx_security_scan_task_repository_profile ON public.security_scan_task "
            + "USING btree (repository_id, profile_id, id)",
        indexDefinition("idx_security_scan_task_repository_profile"));
    assertEquals(
        "CREATE INDEX idx_security_scan_task_projection ON public.security_scan_task "
            + "USING btree (asset_id, profile_id, content_generation, id)",
        indexDefinition("idx_security_scan_task_projection"));
    assertEquals(
        "CREATE INDEX idx_security_scanner_snapshot_ready "
            + "ON public.security_scanner_snapshot "
            + "USING btree (ready, vulnerability_database_updated_at, id)",
        indexDefinition("idx_security_scanner_snapshot_ready"));
    assertEquals(
        "CREATE INDEX idx_asset_security_state_repository_summary "
            + "ON public.asset_security_state "
            + "USING btree (repository_id, scan_state, policy_decision)",
        indexDefinition("idx_asset_security_state_repository_summary"));
    assertEquals(
        "CREATE INDEX idx_security_scan_finding_severity ON public.security_scan_finding "
            + "USING btree (severity, scan_run_id, id)",
        indexDefinition("idx_security_scan_finding_severity"));
    assertEquals(
        "CREATE INDEX idx_security_scan_finding_run_severity "
            + "ON public.security_scan_finding "
            + "USING btree (scan_run_id, severity, id)",
        indexDefinition("idx_security_scan_finding_run_severity"));
    assertEquals(
        "CREATE UNIQUE INDEX uk_security_scan_policy_name_revision "
            + "ON public.security_scan_policy "
            + "USING btree (name_normalized, revision)",
        indexDefinition("uk_security_scan_policy_name_revision"));
    assertEquals(
        "ALWAYS",
        jdbc().queryForObject("""
            SELECT is_generated
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'security_scan_candidate'
              AND column_name = 'pending'
            """, String.class));
  }

  private String indexDefinition(String index) {
    return jdbc().queryForObject("""
        SELECT indexdef
        FROM pg_indexes
        WHERE schemaname = current_schema()
          AND indexname = ?
        """, String.class, index);
  }
}
