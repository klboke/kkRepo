package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class AdminCondaRepositoryContractTest {

  @Test
  void configuresCondaDefaultRemoteAndNestedGroups() throws IOException {
    String javascript = resource("/META-INF/resources/admin/assets/admin.js");

    assertTrue(javascript.contains("conda: \"Conda\""));
    assertTrue(javascript.contains("conda: \"https://repo.anaconda.com/pkgs/main/\""));
    assertTrue(javascript.contains("format === \"conda\""));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
