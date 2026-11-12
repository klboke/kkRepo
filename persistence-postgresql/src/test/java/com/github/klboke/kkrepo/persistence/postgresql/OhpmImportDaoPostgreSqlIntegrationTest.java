package com.github.klboke.kkrepo.persistence.postgresql;

import com.github.klboke.kkrepo.persistence.jdbc.contract.OhpmImportDaoContract;
import com.github.klboke.kkrepo.persistence.postgresql.support.PostgreSqlIntegrationTestSupport;
import org.junit.jupiter.api.Test;

class OhpmImportDaoPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {
  @Test
  void plansAndResultsRemainConsistentAcrossTransactions() throws Exception {
    OhpmImportDaoContract.verify(jdbc(), stores());
  }
}
