package com.github.klboke.kkrepo.persistence.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MySqlSecurityScanSummaryIndexTest extends MySqlIntegrationTestSupport {

  @BeforeAll
  static void startBackend() {
    startMySql();
  }

  @Test
  void summaryFactsHaveSelectiveCoveringIndexes() {
    assertEquals(
        List.of("repository_id", "id"),
        indexColumns("asset", "idx_asset_repository_id"));
    assertEquals(
        List.of("pending", "changed_at", "asset_id"),
        indexColumns("security_scan_candidate", "idx_security_scan_candidate_queue"));
    assertEquals(
        List.of("repository_id", "status", "id"),
        indexColumns("security_scan_task", "idx_security_scan_task_repository_status"));
    assertEquals(
        List.of("repository_id", "profile_id", "id"),
        indexColumns("security_scan_task", "idx_security_scan_task_repository_profile"));
    assertEquals(
        List.of("repository_id", "scan_state", "policy_decision"),
        indexColumns(
            "asset_security_state", "idx_asset_security_state_repository_summary"));
    assertEquals(
        List.of("severity", "scan_run_id", "id"),
        indexColumns("security_scan_finding", "idx_security_scan_finding_severity"));

    String generated = jdbc().queryForObject("""
        SELECT extra
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'security_scan_candidate'
          AND column_name = 'pending'
        """, String.class);
    assertTrue(generated != null && generated.contains("STORED GENERATED"));
  }

  private List<String> indexColumns(String table, String index) {
    return jdbc().queryForList("""
        SELECT column_name
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = ?
          AND index_name = ?
        ORDER BY seq_in_index
        """, String.class, table, index);
  }
}
