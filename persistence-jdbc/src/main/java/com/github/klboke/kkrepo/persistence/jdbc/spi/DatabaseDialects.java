package com.github.klboke.kkrepo.persistence.jdbc.spi;

import java.sql.SQLException;
import java.util.List;
import java.util.ServiceLoader;
import javax.sql.DataSource;

/** Loads exactly one backend provider for an explicitly selected database type. */
public final class DatabaseDialects {
  private DatabaseDialects() {
  }

  public static DatabaseDialect load(DatabaseType type) {
    return load(type, Thread.currentThread().getContextClassLoader());
  }

  /** Detects the JDBC product for standalone tools and loads its backend provider. */
  public static DatabaseDialect detect(DataSource dataSource) {
    try (var connection = dataSource.getConnection()) {
      DatabaseType type = DatabaseType.fromProductName(
          connection.getMetaData().getDatabaseProductName());
      return load(type);
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot detect database type from JDBC metadata", e);
    }
  }

  static DatabaseDialect load(DatabaseType type, ClassLoader classLoader) {
    if (type == null) {
      throw new IllegalArgumentException("Database type is required");
    }
    List<DatabaseDialect> matches = ServiceLoader.load(DatabaseDialect.class, classLoader).stream()
        .map(ServiceLoader.Provider::get)
        .filter(dialect -> dialect.type() == type)
        .toList();
    if (matches.size() != 1) {
      throw new IllegalStateException(
          "Expected exactly one " + type.id() + " DatabaseDialect provider, found "
              + matches.size());
    }
    return matches.getFirst();
  }
}
