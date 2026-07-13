package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.core.security.SecretCipher;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import com.github.klboke.kkrepo.persistence.mysql.MySqlDatabaseDialect;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RepositoryDaoTest {
  @Test
  void proxyRemotePasswordIsEncryptedWhenAttributesAreWritten() {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    JsonColumns jsonColumns = new JsonColumns(new ObjectMapper(), new MySqlDatabaseDialect());
    RepositoryDao dao = new JdbcRepositoryDao(jdbcTemplate, jsonColumns);
    Map<String, Object> proxy = new LinkedHashMap<>();
    proxy.put("remoteUrl", "https://registry.example.com");
    proxy.put("remoteUsername", "robot");
    proxy.put("remotePassword", "top-secret");
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("proxy", proxy);

    dao.update(new RepositoryRecord(
        10L,
        "docker-proxy",
        RepositoryFormat.DOCKER,
        RepositoryType.PROXY,
        "docker-proxy",
        true,
        1L,
        null,
        "https://registry.example.com",
        null,
        null,
        null,
        true,
        attributes));

    String storedJson = jdbcTemplate.attributesJson;
    assertFalse(storedJson.contains("top-secret"));
    @SuppressWarnings("unchecked")
    Map<String, Object> storedProxy = (Map<String, Object>) jsonColumns.read(storedJson).get("proxy");
    assertTrue(SecretCipher.isEncrypted(storedProxy.get("remotePassword").toString()));
  }

  private static final class RecordingJdbcTemplate extends JdbcTemplate {
    private String attributesJson;

    @Override
    public int update(String sql, Object... args) {
      attributesJson = args[8].toString();
      return 1;
    }
  }
}
