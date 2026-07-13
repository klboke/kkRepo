package com.github.klboke.kkrepo.persistence.jdbc.spi;

import org.springframework.jdbc.core.JdbcOperations;

/** Shared-database coordination primitives that require vendor-specific atomic SQL. */
public interface CoordinationPersistenceDialect {
  long bumpCacheVersion(JdbcOperations jdbc, String name);

  String oldestBacklogAgeSecondsExpression(String timestampColumn);
}
