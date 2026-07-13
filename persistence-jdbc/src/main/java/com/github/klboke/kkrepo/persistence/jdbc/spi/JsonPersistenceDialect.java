package com.github.klboke.kkrepo.persistence.jdbc.spi;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/** JSON JDBC binding and semantic JSON SQL expressions. */
public interface JsonPersistenceDialect {
  Object jdbcValue(String json);

  void bind(PreparedStatement statement, int index, String json) throws SQLException;

  String extractText(String column, String... path);

  String setBoolean(String column, boolean value, String... path);
}
