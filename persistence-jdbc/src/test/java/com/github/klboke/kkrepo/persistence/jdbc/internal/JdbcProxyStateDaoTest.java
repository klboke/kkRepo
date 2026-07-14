package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao.ProxyRemoteState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcProxyStateDaoTest {
  @Test
  void recordSuccessRetriesClearWhenConcurrentFailureWinsInitialInsert() {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate(null, true);
    JdbcProxyStateDao dao = new JdbcProxyStateDao(jdbcTemplate, 30);

    dao.recordSuccess(17L, Instant.parse("2026-07-13T12:00:00Z"));

    assertEquals(List.of("load", "insert", "clear"), jdbcTemplate.events);
    assertEquals(1, jdbcTemplate.clearAttempts);
    assertEquals(1, jdbcTemplate.insertAttempts);
  }

  @Test
  void recordSuccessSkipsWritesForHealthyStateInsideThrottleWindow() {
    Instant now = Instant.parse("2026-07-13T12:00:00Z");
    ProxyRemoteState healthy = new ProxyRemoteState(
        17L, null, 0, now.minusSeconds(10), null, null);
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate(healthy, false);
    JdbcProxyStateDao dao = new JdbcProxyStateDao(jdbcTemplate, 30);

    dao.recordSuccess(17L, now);

    assertEquals(List.of("load"), jdbcTemplate.events);
    assertEquals(0, jdbcTemplate.clearAttempts);
    assertEquals(0, jdbcTemplate.insertAttempts);
  }

  @Test
  void recordSuccessImmediatelyClearsLoadedFailureState() {
    Instant now = Instant.parse("2026-07-13T12:00:00Z");
    ProxyRemoteState failed = new ProxyRemoteState(
        17L, now.plusSeconds(60), 2, now.minusSeconds(10), now.minusSeconds(1), "upstream failed");
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate(failed, false);
    JdbcProxyStateDao dao = new JdbcProxyStateDao(jdbcTemplate, 30);

    dao.recordSuccess(17L, now);

    assertEquals(List.of("load", "clear"), jdbcTemplate.events);
    assertEquals(1, jdbcTemplate.clearAttempts);
    assertEquals(0, jdbcTemplate.insertAttempts);
  }

  private static final class RecordingJdbcTemplate extends JdbcTemplate {
    private final ProxyRemoteState loadedState;
    private final boolean duplicateOnInsert;
    private int clearAttempts;
    private int insertAttempts;
    private final List<String> events = new ArrayList<>();

    private RecordingJdbcTemplate(ProxyRemoteState loadedState, boolean duplicateOnInsert) {
      this.loadedState = loadedState;
      this.duplicateOnInsert = duplicateOnInsert;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> query(
        String sql, RowMapper<T> rowMapper, Object... args) {
      events.add("load");
      return loadedState == null ? List.of() : List.of((T) loadedState);
    }

    @Override
    public int update(String sql, Object... args) {
      String normalized = sql.stripLeading();
      if (normalized.startsWith("UPDATE proxy_remote_state")) {
        events.add("clear");
        clearAttempts++;
        return 1;
      }
      if (normalized.startsWith("INSERT INTO proxy_remote_state")) {
        events.add("insert");
        insertAttempts++;
        if (duplicateOnInsert) {
          throw new DuplicateKeyException("concurrent failure inserted the state row");
        }
        return 1;
      }
      throw new AssertionError("Unexpected SQL: " + sql);
    }
  }
}
