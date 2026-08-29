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

  @Override
  protected int seedHelmProxyLegacyCacheFence(long repositoryId, long assetId) {
    return backend.seedHelmProxyLegacyCacheFence(repositoryId, assetId);
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

    private int seedHelmProxyLegacyCacheFence(long repositoryId, long assetId) {
      jdbc().update("""
          UPDATE asset AS legacy_asset
          SET last_updated_at = active_repository.updated_at + INTERVAL '1 second'
          FROM repository AS active_repository
          WHERE legacy_asset.repository_id = active_repository.id
            AND legacy_asset.id = ? AND active_repository.id = ?
          """, assetId, repositoryId);
      return jdbc().update("""
          INSERT INTO helm_proxy_legacy_cache_fence
            (repository_id, configuration_updated_at, activated_at)
          SELECT id, updated_at, updated_at + INTERVAL '2 seconds'
          FROM repository
          WHERE id = ? AND updated_at = created_at
          """, repositoryId);
    }
  }
}
