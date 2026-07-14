package com.github.klboke.kkrepo.persistence.jdbc.internal.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.util.OptionalLong;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

public final class JdbcInserts {
  private JdbcInserts() {
  }

  public static long insert(JdbcTemplate jdbcTemplate, String sql, PreparedStatementSetter setter) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      // Request only the identity column. PostgreSQL otherwise returns the complete inserted row,
      // while MySQL returns a single key; naming the column gives both drivers the same contract.
      var statement = connection.prepareStatement(sql, new String[]{"id"});
      setter.setValues(statement);
      return statement;
    }, keyHolder);
    Number key = keyHolder.getKey();
    if (key == null) {
      throw new IllegalStateException("Insert did not return a generated key");
    }
    return key.longValue();
  }

  /**
   * Attempts an insert that returns an identity key. A duplicate key is represented as an empty
   * result while leaving an existing PostgreSQL transaction usable for a follow-up lookup.
   */
  public static OptionalLong tryInsert(
      JdbcTemplate jdbcTemplate, String sql, PreparedStatementSetter setter) {
    return jdbcTemplate.execute((ConnectionCallback<OptionalLong>) connection -> {
      Savepoint savepoint = JdbcDuplicateRecovery.createSavepointIfRequired(connection);
      try {
        return OptionalLong.of(insert(connection, sql, setter));
      } catch (SQLException insertFailure) {
        if (savepoint != null) {
          connection.rollback(savepoint);
        }
        DataAccessException translated = jdbcTemplate.getExceptionTranslator()
            .translate("try insert", sql, insertFailure);
        if (translated instanceof DuplicateKeyException) {
          return OptionalLong.empty();
        }
        throw insertFailure;
      } finally {
        if (savepoint != null) {
          connection.releaseSavepoint(savepoint);
        }
      }
    });
  }

  private static long insert(
      Connection connection, String sql, PreparedStatementSetter setter) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"})) {
      setter.setValues(statement);
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        if (!keys.next()) {
          throw new IllegalStateException("Insert did not return a generated key");
        }
        Number key = (Number) keys.getObject(1);
        if (key == null) {
          throw new IllegalStateException("Insert did not return a generated key");
        }
        return key.longValue();
      }
    }
  }
}
