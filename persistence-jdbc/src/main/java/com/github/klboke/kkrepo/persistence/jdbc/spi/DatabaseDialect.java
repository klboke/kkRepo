package com.github.klboke.kkrepo.persistence.jdbc.spi;

/** Semantic database capabilities consumed by the shared JDBC implementation. */
public interface DatabaseDialect {
  DatabaseType type();

  /** Returns a backend-specific table reference that prefers the named production index. */
  default String tableReferenceWithPreferredIndex(String tableName, String indexName) {
    return tableName;
  }

  /** Optional keyword that prevents a bounded CTE from being merged into an outer fetch. */
  default String materializedCteModifier() {
    return "";
  }

  /** Repository key used by the unbound-asset cleanup index and its matching predicate. */
  default String unboundAssetRepositoryExpression() {
    return "repository_id";
  }

  ComponentPersistenceDialect components();

  CoordinationPersistenceDialect coordination();

  JsonPersistenceDialect json();

  SearchPersistenceDialect search();

  SecurityPersistenceDialect security();

  MigrationPersistenceDialect migrations();
}
