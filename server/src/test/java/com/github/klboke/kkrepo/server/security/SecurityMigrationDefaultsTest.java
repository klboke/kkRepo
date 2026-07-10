package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class SecurityMigrationDefaultsTest {

  @Test
  void freshDatabaseSeedsAnonymousAccessDisabled() throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(
        "/db/migration/V7__nexus_builtin_security_seed.sql")) {
      String sql = new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8)
          .replaceAll("\\s+", " ");

      assertTrue(sql.contains(
          "VALUES (1, 0, 'default', 'anonymous', 'NexusAuthorizingRealm')"));
    }
  }
}
