package com.github.klboke.kkrepo.persistence.jdbc.spi;

/** Semantic database capabilities consumed by the shared JDBC implementation. */
public interface DatabaseDialect {
  DatabaseType type();

  ComponentPersistenceDialect components();

  CoordinationPersistenceDialect coordination();

  JsonPersistenceDialect json();

  SearchPersistenceDialect search();

  SecurityPersistenceDialect security();

  MigrationPersistenceDialect migrations();
}
