package com.github.klboke.kkrepo.persistence.postgresql;

import com.github.klboke.kkrepo.persistence.jdbc.contract.DockerRetentionDaoContract;
import com.github.klboke.kkrepo.persistence.postgresql.support.PostgreSqlIntegrationTestSupport;
import org.junit.jupiter.api.Test;

class DockerRetentionDaoPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {
  @Test void retentionAndConcurrentReplicas() throws Exception {
    DockerRetentionDaoContract.verify(jdbc(), stores());
  }
}
