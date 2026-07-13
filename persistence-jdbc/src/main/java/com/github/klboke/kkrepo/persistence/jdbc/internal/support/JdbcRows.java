package com.github.klboke.kkrepo.persistence.jdbc.internal.support;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

public final class JdbcRows {
  private JdbcRows() {
  }

  public static Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  public static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  public static Timestamp nullableTimestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }
}
