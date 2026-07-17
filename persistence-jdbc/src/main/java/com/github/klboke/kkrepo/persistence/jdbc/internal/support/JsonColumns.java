package com.github.klboke.kkrepo.persistence.jdbc.internal.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.JsonPersistenceDialect;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class JsonColumns {
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
  };

  private final ObjectMapper objectMapper;
  private final JsonPersistenceDialect dialect;

  @Autowired
  public JsonColumns(ObjectMapper objectMapper, DatabaseDialect dialect) {
    this(objectMapper, dialect.json());
  }

  public JsonColumns(ObjectMapper objectMapper, JsonPersistenceDialect dialect) {
    this.objectMapper = objectMapper;
    this.dialect = dialect;
  }

  public String write(Map<String, Object> value) {
    return writeValue(value == null ? Map.of() : value);
  }

  public String writeValue(Object value) {
    try {
      return objectMapper.writeValueAsString(value == null ? Map.of() : value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Failed to serialize JSON column", e);
    }
  }

  public Object parameter(Map<String, Object> value) {
    return serializedParameter(write(value));
  }

  public Object serializedParameter(String value) {
    return dialect.jdbcValue(value);
  }

  public void bind(PreparedStatement statement, int index, Map<String, Object> value)
      throws SQLException {
    bindSerialized(statement, index, write(value));
  }

  public void bindSerialized(PreparedStatement statement, int index, String value)
      throws SQLException {
    dialect.bind(statement, index, value);
  }

  public String extractText(String column, String... path) {
    return dialect.extractText(column, path);
  }

  public String setBoolean(String column, boolean value, String... path) {
    return dialect.setBoolean(column, value, path);
  }

  public Map<String, Object> read(String value) {
    Map<String, Object> result = readValue(value, MAP_TYPE);
    return result == null ? Map.of() : result;
  }

  public <T> T readValue(String value, TypeReference<T> type) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(value, type);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Failed to deserialize JSON column", e);
    }
  }
}
