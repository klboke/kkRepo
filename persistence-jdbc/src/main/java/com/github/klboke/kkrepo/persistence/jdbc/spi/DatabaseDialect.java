package com.github.klboke.kkrepo.persistence.jdbc.spi;

/** Semantic database capabilities consumed by the shared JDBC implementation. */
public interface DatabaseDialect {
  DatabaseType type();

  /** Invalid datetime format or overflow after conversion in the JDBC connection timezone. */
  default boolean isInvalidDatetime(java.sql.SQLException exception) {
    return "22007".equals(exception.getSQLState()) || "22008".equals(exception.getSQLState());
  }

  /** Keeps fixed-NULL index columns in ORDER BY where needed to preserve index ordering. */
  default boolean orderByNullEqualityColumns() {
    return true;
  }

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

  AlpinePersistenceDialect alpine();

  RPersistenceDialect r();

  CondaPersistenceDialect conda();

  ConanPersistenceDialect conan();

  HuggingFacePersistenceDialect huggingFace();

  /** Forward-only cursor fetch size suitable for this JDBC backend. */
  default int streamingFetchSize() {
    return conda().streamingFetchSize();
  }

  CoordinationPersistenceDialect coordination();

  JsonPersistenceDialect json();

  SearchPersistenceDialect search();

  SecurityPersistenceDialect security();

  MigrationPersistenceDialect migrations();
}
