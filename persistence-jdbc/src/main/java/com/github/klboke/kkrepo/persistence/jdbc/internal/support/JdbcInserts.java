package com.github.klboke.kkrepo.persistence.jdbc.internal.support;

import java.sql.Statement;
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
      var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
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
