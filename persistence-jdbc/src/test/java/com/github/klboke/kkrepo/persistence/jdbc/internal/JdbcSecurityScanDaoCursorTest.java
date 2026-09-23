package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.klboke.kkrepo.persistence.jdbc.api.InvalidScanCompletionCursorException;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.CompletionOrder;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcSecurityScanDaoCursorTest {
  @Test
  void mapsOnlyDatetimeErrorsOnTimestampCursors() {
    for (String state : List.of("22007", "22008", "42S02", "HY000")) {
      var failure = new UncategorizedSQLException("test", "SELECT", new SQLException("test", state));
      var jdbc = new JdbcTemplate() {
        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
          throw failure;
        }
      };
      var dialect = (DatabaseDialect) java.lang.reflect.Proxy.newProxyInstance(
          DatabaseDialect.class.getClassLoader(), new Class<?>[]{DatabaseDialect.class},
          (proxy, method, args) -> method.isDefault()
              ? java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args) : null);
      var dao = new JdbcSecurityScanDao(jdbc, null, dialect);
      var cursor = new CompletionOrder(false, 1, Instant.EPOCH);
      if (state.startsWith("22")) {
        var error = assertThrows(InvalidScanCompletionCursorException.class,
            () -> dao.listTasks(null, null, null, 0, 10, cursor));
        assertSame(failure, error.getCause());
      } else {
        assertSame(failure, assertThrows(DataAccessException.class,
            () -> dao.listTasks(null, null, null, 0, 10, cursor)));
      }
      assertSame(failure, assertThrows(DataAccessException.class,
          () -> dao.listTasks(null, null, null, 0, 10, null)));
      assertSame(failure, assertThrows(DataAccessException.class,
          () -> dao.listTasks(null, null, null, 0, 10, new CompletionOrder(false, 1, null))));
    }
  }
}
