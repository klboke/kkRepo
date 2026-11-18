package com.github.klboke.kkrepo.persistence.postgresql;

import com.github.klboke.kkrepo.persistence.jdbc.contract.OhpmLoginDaoContract;
import com.github.klboke.kkrepo.persistence.postgresql.support.PostgreSqlIntegrationTestSupport;
import org.junit.jupiter.api.Test;

class OhpmLoginDaoPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {
  @Test
  void signingKeysAndReplayProtectionRemainConsistentAcrossTransactions() throws Exception {
    OhpmLoginDaoContract.verify(jdbc(), stores());
  }
}
