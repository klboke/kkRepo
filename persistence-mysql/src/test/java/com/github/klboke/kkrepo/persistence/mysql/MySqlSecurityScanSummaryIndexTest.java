package com.github.klboke.kkrepo.persistence.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
        List.of("repository_id", "digest_hash", "manifest_id"),
        indexColumns(
            "docker_manifest_reference", "idx_docker_reference_policy_lookup"));
    assertEquals(
        List.of("pending", "changed_at", "asset_id"),
        indexColumns("security_scan_candidate", "idx_security_scan_candidate_queue"));
    assertEquals(
        List.of(
            "status",
            "attempts_remaining",
            "priority",
            "next_attempt_at",
            "requested_at",
            "id"),
        indexColumns("security_scan_task", "idx_security_scan_task_claim_ready"));
    assertEquals(
        List.of(
            "status",
            "attempts_remaining",
            "priority",
            "lease_until",
            "requested_at",
            "id"),
        indexColumns("security_scan_task", "idx_security_scan_task_claim_running"));
    assertEquals(
        List.of("status", "attempts_remaining", "lease_until", "id"),
        indexColumns("security_scan_task", "idx_security_scan_task_claim_exhausted"));
    assertEquals(
        "D",
        indexDirection(
            "security_scan_task", "idx_security_scan_task_claim_ready", "priority"));
    assertEquals(
        "D",
        indexDirection(
            "security_scan_task", "idx_security_scan_task_claim_running", "priority"));
    assertEquals(
        List.of("repository_id", "status", "id"),
        indexColumns("security_scan_task", "idx_security_scan_task_repository_status"));
    assertEquals(
        List.of("repository_id", "profile_id", "id"),
        indexColumns("security_scan_task", "idx_security_scan_task_repository_profile"));
    assertEquals(
        List.of("asset_id", "profile_id", "content_generation", "id"),
        indexColumns("security_scan_task", "idx_security_scan_task_projection"));
    assertEquals(
        List.of("ready", "vulnerability_database_updated_at", "id"),
        indexColumns("security_scanner_snapshot", "idx_security_scanner_snapshot_ready"));
    assertEquals(
        List.of("repository_id", "scan_state", "policy_decision"),
        indexColumns(
            "asset_security_state", "idx_asset_security_state_repository_summary"));
    assertEquals(
        List.of("severity", "scan_run_id", "id"),
        indexColumns("security_scan_finding", "idx_security_scan_finding_severity"));
    assertEquals(
        List.of("scan_run_id", "severity", "id"),
        indexColumns("security_scan_finding", "idx_security_scan_finding_run_severity"));
    assertEquals(
        List.of("name_normalized", "revision"),
        indexColumns(
            "security_scan_policy", "uk_security_scan_policy_name_revision"));
    assertEquals(
        0,
        jdbc().queryForObject("""
            SELECT MIN(non_unique)
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'security_scan_policy'
              AND index_name = 'uk_security_scan_policy_name_revision'
            """, Integer.class));

    String generated = jdbc().queryForObject("""
        SELECT extra
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'security_scan_candidate'
          AND column_name = 'pending'
        """, String.class);
    assertTrue(generated != null && generated.contains("STORED GENERATED"));
    String attemptsRemaining = jdbc().queryForObject("""
        SELECT extra
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'security_scan_task'
          AND column_name = 'attempts_remaining'
        """, String.class);
    assertTrue(
        attemptsRemaining != null && attemptsRemaining.contains("STORED GENERATED"));
  }

  @Test
  void existingTableIndexesUseExplicitOnlineDdl() throws IOException {
    String migration =
        resource("db/migration/mysql/V37__artifact_security_scanning_online_indexes.sql");

    assertEquals(2, occurrences(migration, "ALGORITHM=INPLACE"));
    assertEquals(2, occurrences(migration, "LOCK=NONE"));
  }

  private static String resource(String name) throws IOException {
    try (var input = MySqlSecurityScanSummaryIndexTest.class
        .getClassLoader()
        .getResourceAsStream(name)) {
      if (input == null) throw new IOException("Missing test resource: " + name);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static int occurrences(String value, String needle) {
    return (value.length() - value.replace(needle, "").length()) / needle.length();
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

  private String indexDirection(String table, String index, String column) {
    return jdbc().queryForObject("""
        SELECT collation
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = ?
          AND index_name = ?
          AND column_name = ?
        """, String.class, table, index, column);
  }
}
