package com.github.klboke.kkrepo.persistence.jdbc.spi;

import java.util.List;

/** Backend-specific R query shapes used by the shared JDBC implementation. */
public interface RPersistenceDialect {
  /** SQL predicate and bind values for the package-name/id keyset cursor. */
  record KeysetCursor(String predicate, List<Object> arguments) {}

  KeysetCursor packageNameIdCursor(String packageName, long packageId);

  /** Bounded worker query tuned for the backend's optimizer and R worker index. */
  String pendingSuitesSql();

  /** Bounded snapshot-retention query tuned for the backend's join planner. */
  String snapshotCleanupCandidatesSql();
}
