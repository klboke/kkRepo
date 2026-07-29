package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.TaskDraft;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import com.github.klboke.kkrepo.security.scan.ScanEnums.RequestReason;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanStage;
import com.github.klboke.kkrepo.security.scan.ScanEnums.SubjectKind;
import com.github.klboke.kkrepo.security.scan.ScanTaskPriorities;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcSecurityScanDaoSchedulingTest {
  private static final Instant NOW = Instant.parse("2026-07-29T16:00:00Z");

  @Test
  void buildsOneBoundedIndexRangePerPriorityAndStatus() {
    RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate(false);
    JdbcSecurityScanDao dao = dao(jdbc);

    assertTrue(dao.claimTasks("worker", NOW, NOW.plusSeconds(30), 4).isEmpty());

    assertEquals(3, jdbc.claimQueries.size());
    assertClaimQuery(jdbc.claimQueries.get(0), "PENDING", "next_attempt_at <= ?");
    assertClaimQuery(jdbc.claimQueries.get(1), "RETRY_WAIT", "next_attempt_at <= ?");
    assertClaimQuery(jdbc.claimQueries.get(2), "RUNNING", "lease_until < ?");
  }

  @Test
  void validatesSchedulerInputsBeforeQuerying() {
    JdbcSecurityScanDao dao = dao(new RecordingJdbcTemplate(false));

    assertThrows(
        IllegalArgumentException.class,
        () -> dao.claimTasks(" ", NOW, NOW.plusSeconds(30), 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> dao.claimTasks("worker", NOW, NOW, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> dao.createTask(new TaskDraft(
            1,
            null,
            SubjectKind.ASSET_BLOB,
            "sha256:" + "a".repeat(64),
            1,
            1,
            1,
            null,
            ScanStage.CATALOG_AND_MATCH,
            RequestReason.MANUAL,
            50,
            1,
            "test",
            null,
            null,
            NOW)));
  }

  @Test
  void locksTheCandidateBeforeTheAdministrativeTaskRow() {
    RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate(true);
    JdbcSecurityScanDao dao = dao(jdbc);

    assertTrue(dao.findTaskForUpdate(9).isEmpty());

    assertEquals(
        List.of("read-task-asset", "lock-candidate", "lock-task"),
        jdbc.lockEvents);
  }

  private static void assertClaimQuery(
      RecordedQuery query, String status, String eligibilityPredicate) {
    assertEquals(4, occurrences(query.sql(), "attempts_remaining = TRUE"));
    assertEquals(3, occurrences(query.sql(), "UNION ALL"));
    assertEquals(4, occurrences(query.sql(), eligibilityPredicate));
    assertEquals(17, query.arguments().size());
    assertEquals(
        List.of(
            ScanTaskPriorities.MANUAL,
            ScanTaskPriorities.VULNERABILITY_DATABASE,
            ScanTaskPriorities.POLICY,
            ScanTaskPriorities.CONTENT),
        List.of(
            query.arguments().get(1),
            query.arguments().get(5),
            query.arguments().get(9),
            query.arguments().get(13)));
    assertEquals(status, query.arguments().get(0));
    assertEquals(status, query.arguments().get(4));
    assertEquals(status, query.arguments().get(8));
    assertEquals(status, query.arguments().get(12));
    assertEquals(4, query.arguments().get(16));
  }

  private static int occurrences(String value, String needle) {
    return (value.length() - value.replace(needle, "").length()) / needle.length();
  }

  private static JdbcSecurityScanDao dao(JdbcTemplate jdbc) {
    DatabaseDialect dialect = (DatabaseDialect) Proxy.newProxyInstance(
        DatabaseDialect.class.getClassLoader(),
        new Class<?>[] {DatabaseDialect.class},
        (proxy, method, arguments) -> null);
    return new JdbcSecurityScanDao(jdbc, null, dialect);
  }

  private record RecordedQuery(String sql, List<Object> arguments) {}

  private static final class RecordingJdbcTemplate extends JdbcTemplate {
    private final boolean returnTaskAsset;
    private final List<RecordedQuery> claimQueries = new ArrayList<>();
    private final List<String> lockEvents = new ArrayList<>();

    private RecordingJdbcTemplate(boolean returnTaskAsset) {
      this.returnTaskAsset = returnTaskAsset;
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
      String normalized = sql.stripLeading();
      if (normalized.startsWith(
          "SELECT id, priority, requested_at, eligible_at FROM")) {
        claimQueries.add(new RecordedQuery(sql, Arrays.asList(args.clone())));
      } else if (normalized.startsWith("SELECT * FROM security_scan_task")) {
        lockEvents.add("lock-task");
      }
      return List.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
      String normalized = sql.stripLeading();
      if (normalized.startsWith("SELECT asset_id FROM security_scan_task")) {
        lockEvents.add("read-task-asset");
        return returnTaskAsset ? (List<T>) List.of(17L) : List.of();
      }
      if (normalized.startsWith("SELECT asset_id")
          && normalized.contains("FROM security_scan_candidate")) {
        lockEvents.add("lock-candidate");
      }
      return List.of();
    }
  }
}
