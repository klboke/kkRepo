package com.github.klboke.kkrepo.persistence.mysql;

import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceStores;
import com.github.klboke.kkrepo.persistence.jdbc.contract.PersistenceApiContract;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import java.util.Set;
import java.util.function.Supplier;
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

  @Override
  protected <T> T inTransaction(Supplier<T> action) {
    return backend.transaction(action);
  }

  @Override
  protected Set<String> databaseTables() {
    return backend.databaseTablesForContract();
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

    private <T> T transaction(Supplier<T> action) {
      return inTransaction(action);
    }

    private Set<String> databaseTablesForContract() {
      return databaseTables();
    }
  }
}
