package com.github.klboke.kkrepo.persistence.mysql.dao;

import com.github.klboke.kkrepo.persistence.jdbc.contract.DockerRetentionDaoContract;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import org.junit.jupiter.api.Test;

class DockerRetentionDaoMySqlIntegrationTest extends MySqlIntegrationTestSupport {
  @Test void retentionAndConcurrentReplicas() throws Exception {
    DockerRetentionDaoContract.verify(jdbc(), stores());
  }
}
