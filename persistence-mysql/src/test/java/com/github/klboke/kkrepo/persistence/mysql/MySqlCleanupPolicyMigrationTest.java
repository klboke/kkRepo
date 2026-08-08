package com.github.klboke.kkrepo.persistence.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MySqlCleanupPolicyMigrationTest extends MySqlIntegrationTestSupport {

  @Test
  void activePolicyNameUsesAnIndexedVirtualGeneratedColumn() {
    String extra = jdbc().queryForObject("""
        SELECT extra
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'cleanup_policy'
          AND column_name = 'active_name'
        """, String.class);

    assertTrue(extra != null && extra.contains("VIRTUAL GENERATED"));
    assertEquals(0, jdbc().queryForObject("""
        SELECT MIN(non_unique)
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'cleanup_policy'
          AND index_name = 'uk_cleanup_policy_active_name'
        """, Integer.class));
    assertEquals(0, jdbc().queryForObject("""
        SELECT COUNT(*)
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'cleanup_policy'
          AND index_name = 'uk_cleanup_policy_name'
        """, Integer.class));
  }

  @Test
  void cleanupPolicyEvolutionRequiresExplicitOnlineAlgorithms() throws IOException {
    String migration = resource("db/migration/mysql/V38__cleanup_policy_runtime.sql");

    assertFalse(migration.contains("STORED"));
    assertTrue(migration.contains("ALGORITHM=INSTANT"));
    assertTrue(migration.contains("ALGORITHM=INPLACE"));
    assertTrue(migration.contains("LOCK=NONE"));
    assertTrue(migration.contains("information_schema.columns"));
    assertTrue(migration.contains("information_schema.statistics"));
    assertTrue(
        migration.indexOf("ADD UNIQUE KEY uk_cleanup_policy_active_name")
            < migration.indexOf("DROP INDEX uk_cleanup_policy_name"));
  }

  private static String resource(String name) throws IOException {
    try (var input = MySqlCleanupPolicyMigrationTest.class
        .getClassLoader()
        .getResourceAsStream(name)) {
      if (input == null) throw new IOException("Missing test resource: " + name);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
