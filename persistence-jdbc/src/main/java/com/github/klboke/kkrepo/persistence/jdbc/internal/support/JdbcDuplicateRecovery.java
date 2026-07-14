package com.github.klboke.kkrepo.persistence.jdbc.internal.support;

import java.sql.Connection;
import java.sql.Savepoint;
import java.sql.SQLException;

/** Database-specific savepoint policy for duplicate-key recovery inside an open transaction. */
final class JdbcDuplicateRecovery {
  private JdbcDuplicateRecovery() {
  }

  static Savepoint createSavepointIfRequired(Connection connection) throws SQLException {
    // PostgreSQL aborts the transaction after a constraint violation. MySQL keeps the transaction
    // usable and can invalidate a JDBC savepoint while the portable update/insert path is running.
    return !connection.getAutoCommit()
        && "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())
        ? connection.setSavepoint()
        : null;
  }
}
