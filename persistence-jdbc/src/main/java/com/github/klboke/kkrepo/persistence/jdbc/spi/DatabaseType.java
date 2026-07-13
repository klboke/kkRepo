package com.github.klboke.kkrepo.persistence.jdbc.spi;

import java.util.Locale;

/** Stable relational database identifiers used by persistence bootstrap code. */
public enum DatabaseType {
  MYSQL("mysql"),
  POSTGRESQL("postgresql");

  private final String id;

  DatabaseType(String id) {
    this.id = id;
  }

  public String id() {
    return id;
  }

  public static DatabaseType fromId(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Database type is required");
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    for (DatabaseType type : values()) {
      if (type.id.equals(normalized)) {
        return type;
      }
    }
    throw new IllegalArgumentException("Unsupported database type: " + value);
  }
}
