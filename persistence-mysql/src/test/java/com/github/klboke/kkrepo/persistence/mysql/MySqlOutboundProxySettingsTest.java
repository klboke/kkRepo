package com.github.klboke.kkrepo.persistence.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.OutboundProxySettingsDao.Settings;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

class MySqlOutboundProxySettingsTest extends MySqlIntegrationTestSupport {
  @Test
  void persistsOnlyOneRevisionAndEncryptsCredentials() {
    // Integration test isolation truncates singleton tables after running Flyway.
    jdbc().update("""
        INSERT INTO outbound_proxy_settings (id, revision, enabled, bypass_hosts)
        VALUES (1, 0, FALSE, '')
        """);
    var dao = stores().outboundProxySettings();
    var before = dao.read();
    assertFalse(before.enabled());
    var command = new Settings(before.revision(), true, "HTTP", "egress.example.org", 3128,
        "service", "test-password", List.of("*.corp.example", "localhost"));
    try {
      assertTrue(dao.replace(command));
      assertFalse(dao.replace(command));
      var saved = dao.read();
      assertEquals(before.revision() + 1, saved.revision());
      assertEquals(command.bypassHosts(), saved.bypassHosts());
      assertEquals(command.password(), saved.password());
      assertNotEquals(command.password(), jdbc().queryForObject(
          "SELECT proxy_password FROM outbound_proxy_settings WHERE id = 1", String.class));
    } finally {
      var current = dao.read();
      dao.replace(new Settings(current.revision(), false, null, null, null, null, null, List.of()));
    }
  }
}
