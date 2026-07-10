package com.github.klboke.kkrepo.browse.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class BrowseAdminBootstrapContractTest {

  @Test
  void administratorSetupIncludesAnonymousAccessChoice() throws IOException {
    String index = resource("/META-INF/resources/browse/index.html");
    String javascript = resource("/META-INF/resources/browse/assets/browse.js");

    assertTrue(index.contains(
        "<input id=\"admin-bootstrap-anonymous-enabled\" type=\"checkbox\">"));
    assertTrue(index.contains(
        "You can change this later in Administration &gt; Security &gt; Anonymous Access."));
    assertTrue(javascript.contains(
        "anonymousInput.checked = Boolean(adminBootstrapStatus.anonymousAccessEnabled)"));
    assertTrue(javascript.contains(
        "anonymousAccessEnabled: Boolean(anonymousInput && anonymousInput.checked)"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
