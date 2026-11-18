package com.github.klboke.kkrepo.persistence.postgresql;

import com.github.klboke.kkrepo.persistence.jdbc.contract.MavenSnapshotDaoContract;
import com.github.klboke.kkrepo.persistence.postgresql.support.PostgreSqlIntegrationTestSupport;
import org.junit.jupiter.api.Test;

class MavenSnapshotDaoPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {
  @Test void retentionProjectionAndConcurrentReplicas() throws Exception {
    MavenSnapshotDaoContract.verify(jdbc(), stores(), dialect());
  }
}
