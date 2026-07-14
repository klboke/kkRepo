package com.github.klboke.kkrepo.persistence.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcUpserts;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

class MySqlJdbcUpsertsTransactionTest extends MySqlIntegrationTestSupport {
  @Test
  void insertInsideOuterTransactionDoesNotUsePostgreSqlSavepoint() {
    JdbcTemplate noSavepointJdbc = new SavepointRejectingJdbcTemplate(jdbc().getDataSource());

    inTransaction(() -> JdbcUpserts.updateThenInsert(
        noSavepointJdbc,
        "UPDATE ui_settings SET default_language = ? WHERE id = 1",
        new Object[]{"zh-CN"},
        "INSERT INTO ui_settings (id, default_language) VALUES (1, ?)",
        new Object[]{"zh-CN"}));

    assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM ui_settings", Integer.class));
    assertEquals("zh-CN", jdbc().queryForObject(
        "SELECT default_language FROM ui_settings WHERE id = 1", String.class));
  }

  @Test
  void duplicateInsertInsideOuterTransactionDoesNotUsePostgreSqlSavepoint() {
    JdbcTemplate noSavepointJdbc = new SavepointRejectingJdbcTemplate(jdbc().getDataSource());

    inTransaction(() -> {
      assertTrue(JdbcInserts.tryInsert(
          noSavepointJdbc,
          "INSERT INTO blob_store (name, type, attributes_json) "
              + "VALUES (?, 'S3', JSON_OBJECT())",
          statement -> statement.setString(1, "shared-store")).isPresent());
      assertTrue(JdbcInserts.tryInsert(
          noSavepointJdbc,
          "INSERT INTO blob_store (name, type, attributes_json) "
              + "VALUES (?, 'S3', JSON_OBJECT())",
          statement -> statement.setString(1, "shared-store")).isEmpty());
      assertEquals("S3", noSavepointJdbc.queryForObject(
          "SELECT type FROM blob_store WHERE name = 'shared-store'", String.class));
    });
  }

  private static final class SavepointRejectingJdbcTemplate extends JdbcTemplate {
    private SavepointRejectingJdbcTemplate(DataSource dataSource) {
      super(dataSource);
    }

    @Override
    public <T> T execute(ConnectionCallback<T> action) throws DataAccessException {
      return super.execute((ConnectionCallback<T>) connection -> {
        Connection noSavepointConnection = (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
              if (method.getName().equals("setSavepoint")) {
                throw new AssertionError("MySQL conflict handling must not create PostgreSQL savepoints");
              }
              try {
                return method.invoke(connection, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
        try {
          return action.doInConnection(noSavepointConnection);
        } finally {
          noSavepointConnection.close();
        }
      });
    }
  }
}
