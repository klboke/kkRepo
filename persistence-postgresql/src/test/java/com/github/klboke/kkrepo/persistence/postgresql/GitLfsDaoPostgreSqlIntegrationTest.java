package com.github.klboke.kkrepo.persistence.postgresql;

import com.github.klboke.kkrepo.persistence.jdbc.contract.GitLfsDaoContract;
import com.github.klboke.kkrepo.persistence.postgresql.support.PostgreSqlIntegrationTestSupport;
import org.junit.jupiter.api.Test;

class GitLfsDaoPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {
  @Test void uploadFencingDeletionAndRecovery() throws Exception {
    GitLfsDaoContract.verify(jdbc(), stores());
  }
}
