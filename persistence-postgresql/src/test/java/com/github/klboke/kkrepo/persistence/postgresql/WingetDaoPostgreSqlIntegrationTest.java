package com.github.klboke.kkrepo.persistence.postgresql;

import com.github.klboke.kkrepo.persistence.jdbc.contract.WingetDaoContract;
import com.github.klboke.kkrepo.persistence.postgresql.support.PostgreSqlIntegrationTestSupport;
import org.junit.jupiter.api.Test;

class WingetDaoPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {
  @Test
  void manifestLifecycleAndSearch() {
    WingetDaoContract.verify(jdbc(), stores());
  }
}
