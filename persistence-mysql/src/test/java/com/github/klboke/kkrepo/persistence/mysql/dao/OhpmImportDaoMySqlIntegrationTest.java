package com.github.klboke.kkrepo.persistence.mysql.dao;

import com.github.klboke.kkrepo.persistence.jdbc.contract.OhpmImportDaoContract;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import org.junit.jupiter.api.Test;

class OhpmImportDaoMySqlIntegrationTest extends MySqlIntegrationTestSupport {
  @Test
  void plansAndResultsRemainConsistentAcrossTransactions() throws Exception {
    OhpmImportDaoContract.verify(jdbc(), stores());
  }
}
