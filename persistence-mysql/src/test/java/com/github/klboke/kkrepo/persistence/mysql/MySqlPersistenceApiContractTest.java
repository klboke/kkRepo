package com.github.klboke.kkrepo.persistence.mysql;

import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceStores;
import com.github.klboke.kkrepo.persistence.jdbc.contract.PersistenceApiContract;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

/** Runs the reusable public persistence contract against a real MySQL 8 database. */
class MySqlPersistenceApiContractTest extends PersistenceApiContract {
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

    private int seedHelmProxyLegacyCacheFence(long repositoryId, long assetId) {
      jdbc().update("""
          UPDATE asset AS legacy_asset
          JOIN repository AS active_repository
            ON active_repository.id = legacy_asset.repository_id
          SET legacy_asset.last_updated_at =
            TIMESTAMPADD(SECOND, 1, active_repository.updated_at)
          WHERE legacy_asset.id = ? AND active_repository.id = ?
          """, assetId, repositoryId);
      return jdbc().update("""
          INSERT INTO helm_proxy_legacy_cache_fence
            (repository_id, configuration_updated_at, activated_at)
          SELECT id, updated_at, TIMESTAMPADD(SECOND, 2, updated_at)
          FROM repository
          WHERE id = ? AND updated_at = created_at
          """, repositoryId);
    }
  }
}
