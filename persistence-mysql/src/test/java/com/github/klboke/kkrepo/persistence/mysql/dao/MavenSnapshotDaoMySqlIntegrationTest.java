package com.github.klboke.kkrepo.persistence.mysql.dao;

import com.github.klboke.kkrepo.persistence.jdbc.contract.MavenSnapshotDaoContract;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import org.junit.jupiter.api.Test;

class MavenSnapshotDaoMySqlIntegrationTest extends MySqlIntegrationTestSupport {
  @Test void retentionProjectionAndConcurrentReplicas() throws Exception {
    MavenSnapshotDaoContract.verify(jdbc(), stores(), dialect());
  }
}
