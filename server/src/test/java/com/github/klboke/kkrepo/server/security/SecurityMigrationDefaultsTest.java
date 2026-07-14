package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class SecurityMigrationDefaultsTest {
  private static final String V7_SHA256 =
      "42ae6033fba7a889041728bc5202e6b35a063293bb096d46b607bc11e03ff19d";

  @Test
  void appliedV7MigrationRemainsByteForByteStable()
      throws IOException, NoSuchAlgorithmException {
    byte[] sql = resourceBytes("/db/migration/mysql/V7__nexus_builtin_security_seed.sql");

    assertEquals(V7_SHA256, HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(sql)));
  }

  @Test
  void newMigrationDisablesAnonymousAccessOnlyBeforeInitialization() throws IOException {
    String sql = new String(
        resourceBytes("/db/migration/mysql/V29__disable_anonymous_for_uninitialized_installations.sql"),
        StandardCharsets.UTF_8).replaceAll("\\s+", " ");

    assertTrue(sql.contains("UPDATE security_anonymous_config SET enabled = 0"));
    assertTrue(sql.contains("AND enabled = 1"));
    assertTrue(sql.contains("AND user_source = 'Local'"));
    assertTrue(sql.contains("AND user_id = 'anonymous'"));
    assertTrue(sql.contains("AND realm_name = 'NexusAuthorizingRealm'"));
    assertTrue(sql.contains(
        "WHERE NOT (source = 'Local' AND user_id = 'anonymous')"));
  }

  private byte[] resourceBytes(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return Objects.requireNonNull(stream).readAllBytes();
    }
  }
}
