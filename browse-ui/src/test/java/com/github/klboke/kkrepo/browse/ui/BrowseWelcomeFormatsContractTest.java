package com.github.klboke.kkrepo.browse.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class BrowseWelcomeFormatsContractTest {
  private static final List<String> FORMATS = List.of(
      "maven2", "npm", "pypi", "cargo", "pub", "go",
      "helm", "docker", "nuget", "rubygems", "yum", "raw");

  @Test
  void welcomePageShowsEverySupportedFormatWithAnIcon() throws IOException {
    String index = resource("/META-INF/resources/browse/index.html");
    String stylesheet = resource("/META-INF/resources/browse/assets/browse.css");

    assertTrue(index.contains("id=\"supported-formats-title\""));
    assertTrue(stylesheet.contains(".format-grid {"));
    assertTrue(stylesheet.contains("gap: 1px;"));
    assertTrue(stylesheet.contains("overflow: hidden;"));
    assertTrue(stylesheet.contains(".format-item {"));
    assertTrue(stylesheet.contains("min-height: 72px;"));
    assertTrue(stylesheet.contains("cursor: default;"));
    assertTrue(
        index.indexOf("id=\"admin-bootstrap-panel\"")
            < index.indexOf("class=\"format-showcase\""));
    for (String format : FORMATS) {
      assertTrue(index.contains("data-format=\"" + format + "\""), format);
      try (InputStream icon = getClass().getResourceAsStream(
          "/META-INF/resources/browse/assets/formats/" + iconName(format) + ".svg")) {
        assertNotNull(icon, format);
      }
    }
  }

  private static String iconName(String format) {
    return "maven2".equals(format) ? "maven" : format;
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
