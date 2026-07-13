package com.github.klboke.kkrepo.persistence.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                throw new AssertionError("MySQL upserts must not create PostgreSQL savepoints");
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
