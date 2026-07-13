package com.github.klboke.kkrepo.persistence.mysql;

import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceStores;
import com.github.klboke.kkrepo.persistence.jdbc.contract.PersistenceApiContract;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

/** Runs the reusable public persistence contract against a real MySQL 8 database. */
class MySqlPersistenceApiContractTest extends PersistenceApiContract {
  private final Backend backend = new Backend();

  @BeforeAll
  static void startBackend() {
    Backend.start();
  }

  @BeforeEach
  void resetBackend() {
    backend.truncate();
  }

  @Override
  protected PersistenceStores stores() {
    return backend.storesForContract();
  }

  private static final class Backend extends MySqlIntegrationTestSupport {
    private static void start() {
      startMySql();
    }

    private void truncate() {
      truncateDatabase();
    }

    private PersistenceStores storesForContract() {
      return stores();
    }
  }
}
