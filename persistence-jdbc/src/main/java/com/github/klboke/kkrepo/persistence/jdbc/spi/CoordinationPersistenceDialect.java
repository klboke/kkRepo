package com.github.klboke.kkrepo.persistence.jdbc.spi;

import org.springframework.jdbc.core.JdbcOperations;

/** Shared-database coordination primitives that require vendor-specific atomic SQL. */
public interface CoordinationPersistenceDialect {
  /** Create an identity lock without duplicate-insert shared-lock upgrades on MySQL. */
  void ensureMavenSnapshotFamily(JdbcOperations jdbc, long repositoryId, byte[] pathHash,
      String path, String groupId, String artifactId, String baseVersion);

  long bumpCacheVersion(JdbcOperations jdbc, String name);

  String oldestBacklogAgeSecondsExpression(String timestampColumn);
}
