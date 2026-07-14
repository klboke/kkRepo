package com.github.klboke.kkrepo.persistence.jdbc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PersistenceStoreFactoriesTest {
  @Test
  void reportsDatabaseDetectionFailuresAtTheApiBoundary() {
    IllegalStateException thrown = assertThrows(
        IllegalStateException.class,
        () -> PersistenceStoreFactories.connect(
            new DatabaseConnectionSettings("jdbc:test:kkrepo", "user", "password")));

    assertEquals("Cannot detect database type from JDBC metadata", thrown.getMessage());
  }

  @Test
  void rejectsMissingDatabaseUrlsAtTheApiBoundary() {
    assertThrows(IllegalArgumentException.class,
        () -> new DatabaseConnectionSettings(" ", "user", "password"));
  }
}
