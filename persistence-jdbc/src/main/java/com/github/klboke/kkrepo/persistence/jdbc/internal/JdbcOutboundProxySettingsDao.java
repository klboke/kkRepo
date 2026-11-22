package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.core.security.EncryptionSecrets;
import com.github.klboke.kkrepo.core.security.SecretCipher;
import com.github.klboke.kkrepo.persistence.jdbc.api.OutboundProxySettingsDao;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOutboundProxySettingsDao implements OutboundProxySettingsDao {
  private final JdbcTemplate jdbc;
  private volatile SecretCipher cipher;

  public JdbcOutboundProxySettingsDao(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Settings read() {
    return jdbc.queryForObject("""
        SELECT revision, enabled, proxy_type, proxy_host, proxy_port, proxy_username,
               proxy_password, bypass_hosts
        FROM outbound_proxy_settings
        WHERE id = 1
        """, (rs, row) -> {
          String password = rs.getString("proxy_password");
          String hosts = rs.getString("bypass_hosts");
          return new Settings(rs.getLong("revision"), rs.getBoolean("enabled"),
              rs.getString("proxy_type"), rs.getString("proxy_host"),
              rs.getObject("proxy_port", Integer.class), rs.getString("proxy_username"),
              password == null ? null : cipher().decrypt(password),
              hosts.isEmpty() ? List.of() : List.of(hosts.split("\n")));
        });
  }

  @Override
  public boolean replace(Settings settings) {
    return jdbc.update("""
        UPDATE outbound_proxy_settings
        SET revision = revision + 1, enabled = ?, proxy_type = ?, proxy_host = ?, proxy_port = ?,
            proxy_username = ?, proxy_password = ?, bypass_hosts = ?
        WHERE id = 1 AND revision = ?
        """, settings.enabled(), settings.type(), settings.host(), settings.port(), settings.username(),
        settings.password() == null ? null : cipher().encrypt(settings.password()),
        String.join("\n", settings.bypassHosts()), settings.revision()) == 1;
  }

  private SecretCipher cipher() {
    SecretCipher current = cipher;
    if (current == null) {
      current = new SecretCipher(EncryptionSecrets.credentialSecret());
      cipher = current;
    }
    return current;
  }
}
