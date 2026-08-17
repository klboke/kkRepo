package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao.ComponentSearchRow;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.HashColumns;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComponentSearchMySqlIntegrationTest extends MySqlIntegrationTestSupport {

  @Test
  void searchFindsHyphenatedMavenNameWithShortPrefix() {
    long repositoryId = insertRepository("maven-releases", "maven2");
    ComponentDao components = new JdbcComponentDao(jdbc(), jsonColumns(), dialect());
    String namespace = "com.qunhe.mdw";
    String name = "qh-agent-spec-agentscope";
    String version = "0.0.1";
    components.insert(new ComponentRecord(
        null,
        repositoryId,
        RepositoryFormat.MAVEN2,
        namespace,
        name,
        version,
        "release",
        HashColumns.componentCoordinateHash(namespace, name, version),
        Map.of(),
        Instant.parse("2026-08-17T00:00:00Z")));

    assertEquals(
        List.of(name),
        components.search(name, RepositoryFormat.MAVEN2, 20).stream()
            .map(ComponentSearchRow::name)
            .toList());
  }
}
