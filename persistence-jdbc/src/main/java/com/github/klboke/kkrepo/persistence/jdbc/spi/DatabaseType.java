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

  public static DatabaseType fromProductName(String productName) {
    if (productName == null || productName.isBlank()) {
      throw new IllegalArgumentException("Database product name is required");
    }
    return switch (productName.trim().toLowerCase(Locale.ROOT)) {
      case "mysql" -> MYSQL;
      case "postgresql" -> POSTGRESQL;
      default -> throw new IllegalArgumentException(
          "Unsupported database product: " + productName);
    };
  }
}
