package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.security.scan.ScanEnums.TaskStatus;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.CompletionOrder;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Explains the actual DAO queries against migrated databases with a nontrivial scan history. */
public final class ScanCompletionQueryPlanAssertions {
  private ScanCompletionQueryPlanAssertions() {}

  public static void verify(
      JdbcTemplate jdbc, DatabaseDialect dialect, boolean postgres,
      long repositoryId, long taskId, long runId) {
    seedHistory(jdbc, "security_scan_task", taskId, "finished_at", "dedupe_key", true);
    seedHistory(jdbc, "security_scan_run", runId, "completed_at", "match_fingerprint", false);
    jdbc.update("""
        INSERT INTO security_scan_run_subject
          (scan_run_id, repository_id, asset_id, profile_id, content_generation, associated_at)
        SELECT r.id, s.repository_id, s.asset_id, s.profile_id, s.content_generation, s.associated_at
        FROM security_scan_run r CROSS JOIN security_scan_run_subject s
        WHERE s.scan_run_id = ? AND r.match_fingerprint LIKE 'completion-plan-%'
        """, runId);
    for (String table : List.of("security_scan_task", "security_scan_run", "security_scan_run_subject")) {
      jdbc.execute((postgres ? "ANALYZE " : "ANALYZE TABLE ") + table);
    }
    long pendingBoundaryId = jdbc.queryForObject(
        "SELECT id FROM security_scan_task WHERE dedupe_key = ?", Long.class,
        PersistenceHashes.sha256("completion-plan-4000"));
    RecordingJdbcTemplate recording = new RecordingJdbcTemplate(jdbc);
    var dao = new JdbcSecurityScanDao(recording, new JsonColumns(new ObjectMapper(), dialect), dialect);
    for (boolean ascending : List.of(true, false)) {
      for (boolean scoped : List.of(true, false)) {
        for (TaskStatus status : java.util.Arrays.asList(null, TaskStatus.FAILED)) {
          for (CompletionOrder order : List.of(
              new CompletionOrder(ascending, 0, null),
              new CompletionOrder(ascending, taskId, Instant.parse("2026-09-23T01:00:00Z")),
              new CompletionOrder(ascending, pendingBoundaryId, null))) {
            recording.queries.clear();
            if (scoped) dao.listTasksByRepositories(List.of(repositoryId), status, null, 0, 10, order);
            else dao.listTasks(repositoryId, status, null, 0, 10, order);
            assertPlans(jdbc, recording, postgres, "idx_security_scan_task_");
          }
        }
        for (CompletionOrder order : List.of(
            new CompletionOrder(ascending, 0, null),
            new CompletionOrder(ascending, runId, Instant.parse("2026-09-23T01:00:00Z")))) {
          recording.queries.clear();
          if (scoped) dao.listRunsByRepositories(List.of(repositoryId), null, 0, 10, order);
          else dao.listRuns(repositoryId, null, 0, 10, order);
          assertPlans(jdbc, recording, postgres, "idx_security_scan_run_completion");
        }
      }
    }
  }

  private static void seedHistory(
      JdbcTemplate jdbc, String table, long templateId, String timeColumn, String keyColumn,
      boolean task) {
    List<String> columns = jdbc.queryForMap("SELECT * FROM " + table + " WHERE id = ?", templateId)
        .keySet().stream().filter(column -> !column.equals("id") && !column.equals("attempts_remaining"))
        .toList();
    String projection = String.join(", ", columns.stream()
        .map(column -> column.equals(timeColumn) || column.equals(keyColumn)
            || (task && column.equals("status")) ? "?" : column).toList());
    String sql = "INSERT INTO " + table + " (" + String.join(", ", columns) + ") SELECT "
        + projection + " FROM " + table + " WHERE id = ?";
    List<Object[]> batch = new ArrayList<>();
    for (int i = 0; i < 8000; i++) {
      String key = "completion-plan-" + i;
      Map<String, Object> values = new java.util.HashMap<>();
      values.put(keyColumn, task ? PersistenceHashes.sha256(key) : key);
      if (task) values.put("status", i % 101 == 0 ? "FAILED" : "SUCCEEDED");
      values.put(timeColumn, task && i % 4 == 0 ? null
          : Timestamp.from(Instant.parse("2026-09-23T00:00:00Z").plusSeconds(i)));
      List<Object> args = new ArrayList<>();
      for (String column : columns) if (values.containsKey(column)) args.add(values.get(column));
      args.add(templateId);
      batch.add(args.toArray());
      if (batch.size() == 500) {
        jdbc.batchUpdate(sql, batch);
        batch.clear();
      }
    }
  }

  private static void assertPlans(
      JdbcTemplate jdbc, RecordingJdbcTemplate recording, boolean postgres, String index) {
    assertFalse(recording.queries.isEmpty());
    for (Query query : recording.queries) {
      String plan = jdbc.queryForObject(
          (postgres ? "EXPLAIN (FORMAT JSON) " : "EXPLAIN FORMAT=JSON ") + query.sql(),
          String.class, query.args());
      try {
        var tree = new ObjectMapper().readTree(plan);
        boolean statusFiltered = query.sql().contains(" AND t.status = ?");
        assertTrue(tree.findValues(postgres ? "Index Name" : "key").stream()
            .map(JsonNode::asText)
            .anyMatch(name -> name.startsWith(index) && name.endsWith("completion")
                && (!statusFiltered || name.contains("status_completion"))),
            query.sql() + "\n" + plan);
        assertFalse(postgres ? sortsResultRows(tree.get(0).path("Plan"))
            : tree.path("query_block").path("ordering_operation").path("using_filesort").asBoolean(),
            query.sql() + "\n" + plan);
      } catch (java.io.IOException exception) {
        throw new AssertionError("Invalid EXPLAIN JSON", exception);
      }
    }
  }

  private static boolean sortsResultRows(JsonNode node) {
    if (node.path("Node Type").asText().equals("Sort")) return true;
    for (var child : node.path("Plans")) {
      // Sorting the small repository permission relation is unrelated to sorting scan history.
      String relationship = child.path("Parent Relationship").asText();
      if (!relationship.equals("InitPlan") && !relationship.equals("SubPlan")
          && sortsResultRows(child)) return true;
    }
    return false;
  }

  private record Query(String sql, Object[] args) {}

  private static final class RecordingJdbcTemplate extends JdbcTemplate {
    private final List<Query> queries = new ArrayList<>();

    private RecordingJdbcTemplate(JdbcTemplate jdbc) {
      super(jdbc.getDataSource());
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
      queries.add(new Query(sql, args.clone()));
      return super.query(sql, mapper, args);
    }
  }
}
