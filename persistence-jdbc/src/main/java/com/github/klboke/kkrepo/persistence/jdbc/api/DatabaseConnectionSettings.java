package com.github.klboke.kkrepo.persistence.jdbc.api;

/** Connection settings used by standalone tools that consume persistence contracts. */
public record DatabaseConnectionSettings(String url, String username, String password) {
  public DatabaseConnectionSettings {
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("Database URL is required");
    }
  }
}
