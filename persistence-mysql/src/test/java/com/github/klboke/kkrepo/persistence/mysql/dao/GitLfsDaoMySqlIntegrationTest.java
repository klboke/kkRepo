package com.github.klboke.kkrepo.persistence.mysql.dao;

import com.github.klboke.kkrepo.persistence.jdbc.contract.GitLfsDaoContract;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import org.junit.jupiter.api.Test;

class GitLfsDaoMySqlIntegrationTest extends MySqlIntegrationTestSupport {
  @Test void uploadFencingDeletionAndRecovery() throws Exception {
    GitLfsDaoContract.verify(jdbc(), stores());
  }
}
