package com.github.klboke.kkrepo.persistence.jdbc.internal.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SQLStateSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLExceptionTranslator;

class JdbcInsertsTest {
  private static final String SQL = "INSERT INTO sample (name) VALUES (?)";

  @Test
  void nonDuplicateSqlFailureIsNotTreatedAsAConflict() {
    SQLException failure = new SQLException("invalid insert", "42000");
    JdbcTemplate jdbc = new DirectConnectionJdbcTemplate(connectionThatThrows(failure));

    assertThrows(BadSqlGrammarException.class,
        () -> JdbcInserts.tryInsert(jdbc, SQL, statement -> {
        }));
  }

  @Test
  void missingGeneratedKeyFailsClearly() {
    JdbcTemplate jdbc = new DirectConnectionJdbcTemplate(connectionWithGeneratedKey(false, null));

    IllegalStateException failure = assertThrows(IllegalStateException.class,
        () -> JdbcInserts.tryInsert(jdbc, SQL, statement -> {
        }));

    assertEquals("Insert did not return a generated key", failure.getMessage());
  }

  @Test
  void nullGeneratedKeyFailsClearly() {
    JdbcTemplate jdbc = new DirectConnectionJdbcTemplate(connectionWithGeneratedKey(true, null));

    IllegalStateException failure = assertThrows(IllegalStateException.class,
        () -> JdbcInserts.tryInsert(jdbc, SQL, statement -> {
        }));

    assertEquals("Insert did not return a generated key", failure.getMessage());
  }

  private static Connection connectionThatThrows(SQLException failure) {
    return proxy(Connection.class, (ignored, method, arguments) -> switch (method.getName()) {
      case "getAutoCommit" -> true;
      case "prepareStatement" -> throw failure;
      default -> defaultValue(method.getReturnType());
    });
  }

  private static Connection connectionWithGeneratedKey(boolean present, Object key) {
    ResultSet keys = proxy(ResultSet.class, (ignored, method, arguments) -> switch (method.getName()) {
      case "next" -> present;
      case "getObject" -> key;
      default -> defaultValue(method.getReturnType());
    });
    PreparedStatement statement = proxy(
        PreparedStatement.class,
        (ignored, method, arguments) -> switch (method.getName()) {
          case "executeUpdate" -> 1;
          case "getGeneratedKeys" -> keys;
          default -> defaultValue(method.getReturnType());
        });
    return proxy(Connection.class, (ignored, method, arguments) -> switch (method.getName()) {
      case "getAutoCommit" -> true;
      case "prepareStatement" -> statement;
      default -> defaultValue(method.getReturnType());
    });
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive() || type == void.class) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == char.class) {
      return '\0';
    }
    if (type == byte.class) {
      return (byte) 0;
    }
    if (type == short.class) {
      return (short) 0;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == float.class) {
      return 0F;
    }
    return 0D;
  }

  private static final class DirectConnectionJdbcTemplate extends JdbcTemplate {
    private final Connection connection;

    private DirectConnectionJdbcTemplate(Connection connection) {
      this.connection = connection;
      setExceptionTranslator(new SQLStateSQLExceptionTranslator());
    }

    @Override
    public <T> T execute(ConnectionCallback<T> action) throws DataAccessException {
      try {
        return action.doInConnection(connection);
      } catch (SQLException failure) {
        SQLExceptionTranslator translator = getExceptionTranslator();
        DataAccessException translated = translator.translate("execute", SQL, failure);
        if (translated != null) {
          throw translated;
        }
        throw new UncategorizedSQLException("execute", SQL, failure);
      }
    }
  }
}
