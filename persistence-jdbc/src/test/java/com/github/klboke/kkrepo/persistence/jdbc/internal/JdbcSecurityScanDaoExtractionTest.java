package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.RetentionResult;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanMetricSummary;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanSummary;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.JsonPersistenceDialect;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcSecurityScanDaoExtractionTest {
  private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");

  @Test
  void facadeKeepsSummaryAndMetricQueriesBehindDedicatedCollaborators() {
    JdbcSecurityScanDao dao = dao(new RecordingJdbcTemplate());

    ScanSummary summary = dao.summary(Arrays.asList(9L, null, 9L));
    assertEquals(7, summary.candidateBacklog());
    assertEquals(1, summary.pendingTasks());
    assertEquals(2, summary.runningTasks());
    assertEquals(3, summary.failedTasks());
    assertEquals(4, summary.completeAssets());
    assertEquals(5, summary.partialAssets());
    assertEquals(6, summary.staleAssets());
    assertEquals(7, summary.blockedAssets());
    assertEquals(8, summary.criticalFindings());
    assertEquals(9, summary.highFindings());
    assertEquals(summary, dao.summary(9));
    assertEquals(summary, dao.summary());
    assertEquals(new ScanSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), dao.summary(List.of()));
    assertEquals(
        new ScanSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        dao.summary((List<Long>) null));

    ScanMetricSummary metrics = dao.metricSummary(0);
    assertEquals(new ScanMetricSummary(7, 7, 7, 7, 7), metrics);
    assertEquals(Optional.of(NOW), dao.oldestPendingTaskCreatedAt());
  }

  @Test
  void facadeKeepsAllRetentionStagesInsideTheExistingTransactionEntryPoint() {
    RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
    JdbcSecurityScanDao dao = dao(jdbc);

    RetentionResult result = dao.cleanupRetainedData(
        NOW.minusSeconds(3_600), NOW.minusSeconds(7_200), 5_000);

    assertEquals(new RetentionResult(1, 1, 1, 1, 1, 1), result);
    assertEquals(2, jdbc.blobReferenceDeletes);
    assertTrue(jdbc.limits.stream().allMatch(limit -> limit == 1_000));
  }

  private static JdbcSecurityScanDao dao(JdbcTemplate jdbc) {
    JsonPersistenceDialect jsonDialect = (JsonPersistenceDialect) Proxy.newProxyInstance(
        JsonPersistenceDialect.class.getClassLoader(),
        new Class<?>[] {JsonPersistenceDialect.class},
        (proxy, method, arguments) -> switch (method.getName()) {
          case "jdbcValue" -> arguments[0];
          case "selectLongsFromArray" -> "SELECT 1 AS " + arguments[0];
          default -> null;
        });
    DatabaseDialect databaseDialect = (DatabaseDialect) Proxy.newProxyInstance(
        DatabaseDialect.class.getClassLoader(),
        new Class<?>[] {DatabaseDialect.class},
        (proxy, method, arguments) -> "json".equals(method.getName()) ? jsonDialect : null);
    return new JdbcSecurityScanDao(
        jdbc, new JsonColumns(new ObjectMapper(), jsonDialect), databaseDialect);
  }

  private static final class RecordingJdbcTemplate extends JdbcTemplate {
    private final List<Integer> limits = new java.util.ArrayList<>();
    private int blobReferenceDeletes;

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
      if (args.length > 0 && args[args.length - 1] instanceof Integer limit) {
        limits.add(limit);
      }
      if (sql.contains("FROM security_scan_task")
          && sql.contains("finished_at <")) return (List<T>) List.of(101L);
      if (sql.contains("FROM security_scan_backfill_job")) return (List<T>) List.of(102L);
      if (sql.contains("FROM security_scanner_snapshot")) return (List<T>) List.of(103L);
      if (sql.contains("FROM asset_blob")) return (List<T>) List.of(501L);
      return List.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> queryForList(String sql, Class<T> elementType) {
      if (sql.equals("SELECT id FROM repository")) return (List<T>) List.of(9L);
      return List.of();
    }

    @Override
    public List<Map<String, Object>> queryForList(String sql, Object... args) {
      if (args.length > 0 && args[args.length - 1] instanceof Integer limit) {
        limits.add(limit);
      }
      String normalized = sql.stripLeading();
      if (normalized.startsWith("SELECT subject.scan_run_id")) {
        return List.of(Map.of(
            "scan_run_id", 201L,
            "repository_id", 9L,
            "asset_id", 301L,
            "profile_id", 401L,
            "content_generation", 1L));
      }
      if (normalized.startsWith("SELECT run.id")) {
        return List.of(Map.of("id", 201L, "raw_report_blob_id", 501L));
      }
      if (normalized.startsWith("SELECT sbom.id")) {
        return List.of(Map.of("id", 202L, "document_blob_id", 502L));
      }
      return List.of();
    }

    @Override
    public Map<String, Object> queryForMap(String sql, Object... args) {
      if (sql.contains("pending_tasks")) {
        return Map.of("pending_tasks", 1L, "running_tasks", 2L, "failed_tasks", 3L);
      }
      if (sql.contains("blocked_assets")) {
        return Map.of(
            "complete_assets", 4L,
            "partial_assets", 5L,
            "stale_assets", 6L,
            "blocked_assets", 7L);
      }
      return Map.of("critical_findings", 8L, "high_findings", 9L);
    }

    @Override
    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
      return requiredType.cast(7L);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
      if (sql.contains("oldest_created_at")) return (List<T>) List.of(NOW);
      return List.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
      if (sql.contains("oldest_created_at")) return (List<T>) List.of(NOW);
      return List.of();
    }

    @Override
    public int update(String sql, Object... args) {
      if (sql.contains("DELETE FROM blob_reference")) blobReferenceDeletes++;
      return 1;
    }
  }
}
