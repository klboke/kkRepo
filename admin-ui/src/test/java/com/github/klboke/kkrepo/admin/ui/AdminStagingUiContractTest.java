package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class AdminStagingUiContractTest {
  @Test
  void stagingIsAnAdminViewInsideTheSharedShell() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String script = resource("/META-INF/resources/admin/assets/staging.js");

    // The view is part of the admin SPA: it reuses the shared navigation, account menu and theme.
    assertTrue(index.contains("data-view=\"staging\""));
    assertTrue(index.contains("id=\"staging-view\""));
    assertTrue(index.contains("./assets/staging.js"));
    assertTrue(index.contains("id=\"staging-session-table\""));
    assertTrue(index.contains("id=\"staging-repository-table\""));
    assertTrue(index.contains("id=\"staging-item-table\""));
    assertTrue(index.contains("id=\"staging-report\""));
    // Raw payloads are demoted to a collapsed advanced section.
    assertTrue(index.contains("<details class=\"staging-advanced\">"));
    assertTrue(index.contains("id=\"staging-raw\""));

    assertTrue(script.contains("/internal/staging"));
    assertTrue(script.contains("/metrics"));
    assertTrue(script.contains("X-KkRepo-Staging-Token"));
    assertTrue(script.contains("preflight"));
    assertTrue(script.contains("promote"));
    assertTrue(script.contains("compensate"));
  }

  @Test
  void theLegacyStandalonePageRedirectsIntoTheShell() throws IOException {
    String page = resource("/META-INF/resources/admin/staging.html");

    assertTrue(page.contains("/admin/#admin/repository/staging"));
    // No second top bar, sidebar or hard-coded palette: the shell owns the chrome.
    assertFalse(page.contains("<header"));
    assertFalse(page.contains("nx-topbar"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
