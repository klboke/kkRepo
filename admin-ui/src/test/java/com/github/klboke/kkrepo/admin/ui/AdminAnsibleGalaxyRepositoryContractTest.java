package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class AdminAnsibleGalaxyRepositoryContractTest {

  @Test
  void configuresDefaultRemoteAndNestedGroups() throws IOException {
    String javascript = resource("/META-INF/resources/admin/assets/admin.js");

    assertTrue(javascript.contains("ansiblegalaxy: \"Ansible Galaxy\""));
    assertTrue(javascript.contains("ansiblegalaxy: \"https://galaxy.ansible.com/\""));
    assertTrue(javascript.contains("format === \"ansiblegalaxy\""));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
