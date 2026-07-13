package com.github.klboke.kkrepo.persistence.postgresql;

import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceStores;
import com.github.klboke.kkrepo.persistence.jdbc.contract.PersistenceApiContract;
import com.github.klboke.kkrepo.persistence.postgresql.support.PostgreSqlIntegrationTestSupport;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

/** Runs the reusable public persistence contract against the minimum supported PostgreSQL 12. */
class PostgreSqlPersistenceApiContractTest extends PersistenceApiContract {
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

  private static final class Backend extends PostgreSqlIntegrationTestSupport {
    private static void start() {
      startPostgreSql();
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
