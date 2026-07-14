package com.github.klboke.kkrepo.persistence.jdbc.internal.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Savepoint;
import java.sql.SQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** Portable update-first upserts for operations that do not need a returned database value. */
public final class JdbcUpserts {
  private JdbcUpserts() {
  }

  public static void updateThenInsert(
      JdbcTemplate jdbc,
      String updateSql,
      Object[] updateArguments,
      String insertSql,
      Object[] insertArguments) {
    if (jdbc.update(updateSql, updateArguments) > 0) {
      return;
    }
    jdbc.execute((ConnectionCallback<Void>) connection -> {
      Savepoint savepoint = requiresDuplicateRecoverySavepoint(connection)
          ? connection.setSavepoint()
          : null;
      try {
        executeUpdate(connection, insertSql, insertArguments);
      } catch (SQLException insertFailure) {
        if (savepoint != null) {
          connection.rollback(savepoint);
        }
        DataAccessException translated = jdbc.getExceptionTranslator()
            .translate("upsert insert", insertSql, insertFailure);
        if (!(translated instanceof DuplicateKeyException)
            || executeUpdate(connection, updateSql, updateArguments) == 0) {
          throw insertFailure;
        }
      } finally {
        if (savepoint != null) {
          connection.releaseSavepoint(savepoint);
        }
      }
      return null;
    });
  }

  private static boolean requiresDuplicateRecoverySavepoint(Connection connection)
      throws SQLException {
    // PostgreSQL aborts the transaction after a constraint violation. MySQL keeps the transaction
    // usable and can invalidate a JDBC savepoint while the portable update/insert path is running.
    return !connection.getAutoCommit()
        && "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
  }

  private static int executeUpdate(Connection connection, String sql, Object[] arguments)
      throws SQLException {
    ArgumentPreparedStatementSetter setter = new ArgumentPreparedStatementSetter(arguments);
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      setter.setValues(statement);
      return statement.executeUpdate();
    } finally {
      setter.cleanupParameters();
    }
  }
}
