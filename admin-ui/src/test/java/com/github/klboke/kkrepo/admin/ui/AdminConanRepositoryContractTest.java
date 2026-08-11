package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class AdminConanRepositoryContractTest {
  @Test
  void exposesConanRecipesRemoteDefaultAndDurableStatus() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String javascript = resource("/META-INF/resources/admin/assets/admin.js");

    assertTrue(index.contains("id=\"repository-conan-fields\""));
    assertTrue(index.contains("conanmanifest.txt"));
    assertTrue(javascript.contains("conan: \"Conan 2\""));
    assertTrue(javascript.contains("conan: \"https://center2.conan.io/\""));
    assertTrue(javascript.contains("function loadConanStatus"));
    assertTrue(javascript.contains("/conan/status"));
    assertTrue(javascript.contains("open upload sessions"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
