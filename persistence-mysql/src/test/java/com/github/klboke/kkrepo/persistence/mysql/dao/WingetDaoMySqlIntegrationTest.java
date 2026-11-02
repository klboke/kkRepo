package com.github.klboke.kkrepo.persistence.mysql.dao;

import com.github.klboke.kkrepo.persistence.jdbc.contract.WingetDaoContract;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import org.junit.jupiter.api.Test;

class WingetDaoMySqlIntegrationTest extends MySqlIntegrationTestSupport {
  @Test
  void manifestLifecycleAndSearch() {
    WingetDaoContract.verify(jdbc(), stores());
  }
}
