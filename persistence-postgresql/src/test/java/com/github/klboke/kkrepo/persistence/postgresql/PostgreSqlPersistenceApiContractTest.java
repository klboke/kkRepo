package com.github.klboke.kkrepo.persistence.postgresql;

import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceStores;
import com.github.klboke.kkrepo.persistence.jdbc.contract.PersistenceApiContract;
import com.github.klboke.kkrepo.persistence.postgresql.support.PostgreSqlIntegrationTestSupport;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

/** Runs the reusable public persistence contract against the minimum supported PostgreSQL 12. */
class PostgreSqlPersistenceApiContractTest extends PersistenceApiContract {
  private static final Set<String> STATE_SENSITIVE_CONTRACTS = Set.of(
      "cleanupPoliciesPersistTargetsSchedulesAndBoundedRunResults",
      "markerClaimsPartitionConcurrentWorkersAndFailuresReenqueue",
      "securityScanningIsIdempotentFencedAndProtectsImmutableDocuments",
      "securityScanningKeepsNewestScannerSnapshotWhenRematchesCompleteOutOfOrder",
      "securityScanningReconcilesWritesThatMissedAnAlreadyAdvancedEventCursor",
      "securityScanningRecoversFinalAttemptsAndCleansUnreferencedHistory");

  private final Backend backend = new Backend();

  @BeforeAll
  static void startBackend() {
    Backend.start();
  }

  @BeforeEach
  void isolateStateSensitiveContract(TestInfo testInfo) {
    if (STATE_SENSITIVE_CONTRACTS.contains(testInfo.getTestMethod().orElseThrow().getName())) {
      backend.truncate();
    }
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
