package com.github.klboke.kkrepo.persistence.jdbc.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PersistenceStoreFactoriesTest {
  @Test
  void discoversTheSharedJdbcImplementationWithoutOpeningAConnection() {
    try (PersistenceStores stores = PersistenceStoreFactories.connect(
        new DatabaseConnectionSettings("jdbc:test:kkrepo", "user", "password"))) {
      assertAll(
          () -> assertNotNull(stores.assets()),
          () -> assertNotNull(stores.authTickets()),
          () -> assertNotNull(stores.blobStores()),
          () -> assertNotNull(stores.browseNodes()),
          () -> assertNotNull(stores.cacheVersions()),
          () -> assertNotNull(stores.components()),
          () -> assertNotNull(stores.dockerAuthTokens()),
          () -> assertNotNull(stores.dockerRegistry()),
          () -> assertNotNull(stores.dockerUploads()),
          () -> assertNotNull(stores.maintenanceCursors()),
          () -> assertNotNull(stores.metadataRebuild()),
          () -> assertNotNull(stores.migrationCheckpoints()),
          () -> assertNotNull(stores.migrationJobs()),
          () -> assertNotNull(stores.proxyStates()),
          () -> assertNotNull(stores.pubUploadSessions()),
          () -> assertNotNull(stores.repositories()),
          () -> assertNotNull(stores.repositoryDataMigrations()),
          () -> assertNotNull(stores.repositoryIndexRebuild()),
          () -> assertNotNull(stores.securityAudit()),
          () -> assertNotNull(stores.security()),
          () -> assertNotNull(stores.uiSettings()));
    }
  }

  @Test
  void rejectsMissingDatabaseUrlsAtTheApiBoundary() {
    assertThrows(IllegalArgumentException.class,
        () -> new DatabaseConnectionSettings(" ", "user", "password"));
  }
}
