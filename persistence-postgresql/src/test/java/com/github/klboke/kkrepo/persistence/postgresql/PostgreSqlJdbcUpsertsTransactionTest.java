package com.github.klboke.kkrepo.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcUpserts;
import com.github.klboke.kkrepo.persistence.postgresql.support.PostgreSqlIntegrationTestSupport;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PostgreSqlJdbcUpsertsTransactionTest extends PostgreSqlIntegrationTestSupport {
  @Test
  void duplicateInsertRaceCanRetryInsideOuterTransaction() throws Exception {
    CyclicBarrier afterInitialUpdates = new CyclicBarrier(2);
    JdbcTemplate racingJdbc = new InitialUpdateBarrierJdbcTemplate(
        jdbc().getDataSource(), afterInitialUpdates);

    Callable<String> english = writer(racingJdbc, "en");
    Callable<String> chinese = writer(racingJdbc, "zh-CN");
    List<String> results;
    try (var executor = Executors.newFixedThreadPool(2)) {
      results = executor.invokeAll(List.of(english, chinese)).stream().map(future -> {
        try {
          return future.get();
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      }).toList();
    }

    assertEquals(2, results.size());
    assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM ui_settings", Integer.class));
    assertTrue(List.of("en", "zh-CN").contains(jdbc().queryForObject(
        "SELECT default_language FROM ui_settings WHERE id = 1", String.class)));
  }

  private Callable<String> writer(JdbcTemplate racingJdbc, String language) {
    return () -> inTransaction(() -> {
      JdbcUpserts.updateThenInsert(
          racingJdbc,
          "UPDATE ui_settings SET default_language = ? WHERE id = 1",
          new Object[]{language},
          "INSERT INTO ui_settings (id, default_language) VALUES (1, ?)",
          new Object[]{language});
      return language;
    });
  }

  private static final class InitialUpdateBarrierJdbcTemplate extends JdbcTemplate {
    private final CyclicBarrier barrier;

    private InitialUpdateBarrierJdbcTemplate(DataSource dataSource, CyclicBarrier barrier) {
      super(dataSource);
      this.barrier = barrier;
    }

    @Override
    public int update(String sql, Object... args) {
      int updated = super.update(sql, args);
      if (sql.startsWith("UPDATE ui_settings")) {
        try {
          barrier.await();
        } catch (Exception e) {
          throw new IllegalStateException("Failed to coordinate concurrent upsert test", e);
        }
      }
      return updated;
    }
  }
}
