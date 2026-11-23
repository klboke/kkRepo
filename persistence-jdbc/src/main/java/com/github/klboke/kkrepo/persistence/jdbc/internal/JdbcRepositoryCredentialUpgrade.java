package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.security.SecretCipher;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Rewraps proxy credentials written before the configured key was available in 1.4.0. */
public final class JdbcRepositoryCredentialUpgrade {
  private static final String TASK = "repository_credential_key_upgrade_v1";
  // Freeze the key used by the affected release. The regular credential readers never use it.
  private static final String LEGACY_CREDENTIAL_SECRET = "kkrepo-development-credential-secret";

  private final JdbcTemplate jdbc;
  private final JsonColumns json;
  private final TransactionTemplate transactions;
  private final SecretCipher configuredCipher;

  public JdbcRepositoryCredentialUpgrade(DataSource dataSource, DatabaseDialect dialect, SecretCipher configuredCipher) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.json = new JsonColumns(new ObjectMapper(), dialect);
    this.transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    this.configuredCipher = configuredCipher;
  }

  public void run() {
    transactions.executeWithoutResult(transaction -> {
      // The Flyway seed is shared by all replicas. Lock it before touching any credentials.
      long complete = jdbc.queryForObject("""
          SELECT last_seen_id FROM maintenance_cursor WHERE task_name = ? FOR UPDATE
          """, Long.class, TASK);
      if (complete != 0) {
        return;
      }
      SecretCipher legacyCipher = new SecretCipher(LEGACY_CREDENTIAL_SECRET);
      var repositories = jdbc.query("""
          SELECT id, name, %s AS proxy_settings FROM repository WHERE type = 'proxy' ORDER BY id FOR UPDATE
          """.formatted(json.extractJson("attributes_json", "proxy")),
          (rs, row) -> new ProxyRow(rs.getLong("id"), rs.getString("name"),
              json.readObject(rs.getString("proxy_settings"))));
      for (ProxyRow repository : repositories) {
        upgradeRepository(legacyCipher, repository);
      }
      jdbc.update("""
          UPDATE maintenance_cursor SET last_seen_id = 1, updated_at = CURRENT_TIMESTAMP WHERE task_name = ?
          """, TASK);
    });
  }

  private void upgradeRepository(SecretCipher legacyCipher, ProxyRow repository) {
    for (String field : JdbcRepositoryDao.SENSITIVE_PROXY_ATTRIBUTES) {
      if (repository.proxySettings().get(field) instanceof String value && SecretCipher.isEncrypted(value)) {
        String replacement = rewrap(legacyCipher, repository.name(), field, value);
        if (!value.equals(replacement)) {
          jdbc.update("UPDATE repository SET attributes_json = %s WHERE id = ?"
              .formatted(json.setText("attributes_json", "proxy", field)), replacement, repository.id());
        }
      }
    }
  }

  private String rewrap(SecretCipher legacyCipher, String repository, String field, String value) {
    try {
      configuredCipher.decrypt(value);
      return value;
    } catch (IllegalStateException unreadableWithConfiguredKey) {
      try {
        String plaintext = legacyCipher.decrypt(value);
        if (!plaintext.isEmpty() && !SecretCipher.isEncrypted(plaintext)) {
          return configuredCipher.encrypt(plaintext);
        }
      } catch (IllegalStateException unreadableWithLegacyKey) {
        // Report only the location of the value. No ciphertext or key material belongs in startup logs.
      }
    }
    throw new IllegalStateException(
        "Cannot upgrade encrypted proxy credential " + field + " for repository " + repository
            + ". Verify the configured credential secret and existing repository credentials.");
  }

  private record ProxyRow(long id, String name, Map<String, Object> proxySettings) {
  }
}
