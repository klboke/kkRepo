package com.github.klboke.kkrepo.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.postgresql.support.PostgreSqlIntegrationTestSupport;
import org.junit.jupiter.api.Test;

/** Proves the PostgreSQL baseline validates and remains idempotent on repeated startup. */
class PostgreSqlMigrationCompatibilityTest extends PostgreSqlIntegrationTestSupport {
  @Test
  void baselineValidatesAndSecondMigrateHasNoPendingWork() {
    assertTrue(flyway().validateWithResult().validationSuccessful);
    var result = flyway().migrate();
    assertEquals(0, result.migrationsExecuted);
    assertEquals("33", flyway().info().current().getVersion().getVersion());
  }
}
