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
        "CREATE INDEX idx_security_scan_candidate_queue ON public.security_scan_candidate "
            + "USING btree (pending, changed_at, asset_id)",
        indexDefinition("idx_security_scan_candidate_queue"));
    assertEquals(
        "CREATE INDEX idx_security_scan_task_repository_status ON public.security_scan_task "
            + "USING btree (repository_id, status, id)",
        indexDefinition("idx_security_scan_task_repository_status"));
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
