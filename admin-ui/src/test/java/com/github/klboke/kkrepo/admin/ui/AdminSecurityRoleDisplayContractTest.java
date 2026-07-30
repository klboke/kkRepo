package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class AdminSecurityRoleDisplayContractTest {

  @Test
  void roleTransferDisplaysRoleNameWhileKeepingRoleIdAsIdentity() throws IOException {
    String javascript = resource("/META-INF/resources/admin/assets/admin.js");

    assertTrue(javascript.contains("id: role.roleId"));
    assertTrue(javascript.contains("label: role.name || role.roleId"));
    assertTrue(javascript.contains("role.name && role.name !== role.roleId"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}