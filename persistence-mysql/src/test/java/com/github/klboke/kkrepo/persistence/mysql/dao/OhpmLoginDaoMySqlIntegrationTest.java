package com.github.klboke.kkrepo.persistence.mysql.dao;

import com.github.klboke.kkrepo.persistence.jdbc.contract.OhpmLoginDaoContract;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import org.junit.jupiter.api.Test;

class OhpmLoginDaoMySqlIntegrationTest extends MySqlIntegrationTestSupport {
  @Test
  void signingKeysAndReplayProtectionRemainConsistentAcrossTransactions() throws Exception {
    OhpmLoginDaoContract.verify(jdbc(), stores());
  }
}
