package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class AdminStagingUiContractTest {
  @Test
  void stagingPageUsesTheInternalApiAndIsLinkedFromAdmin() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String page = resource("/META-INF/resources/admin/staging.html");

    assertTrue(index.contains("/admin/staging.html"));
    assertTrue(page.contains("/internal/staging"));
    assertTrue(page.contains("/metrics"));
    assertTrue(page.contains("X-KkRepo-Staging"));
    assertTrue(page.contains("preflight"));
    assertTrue(page.contains("promote"));
    assertTrue(page.contains("compensate"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
