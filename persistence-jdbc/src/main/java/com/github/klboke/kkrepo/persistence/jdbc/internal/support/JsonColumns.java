package com.github.klboke.kkrepo.persistence.jdbc.internal.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class JsonColumns {
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
  };

  private final ObjectMapper objectMapper;

  public JsonColumns(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String write(Map<String, Object> value) {
    try {
      return objectMapper.writeValueAsString(value == null ? Map.of() : value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Failed to serialize JSON column", e);
    }
  }

  public Map<String, Object> read(String value) {
    if (value == null || value.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(value, MAP_TYPE);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Failed to deserialize JSON column", e);
    }
  }
}
