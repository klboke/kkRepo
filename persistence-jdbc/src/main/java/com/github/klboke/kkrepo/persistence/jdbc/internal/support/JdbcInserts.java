package com.github.klboke.kkrepo.persistence.jdbc.internal.support;

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
}
