package com.github.klboke.kkrepo.browse.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
      "maven2", "npm", "ohpm", "pypi", "cargo", "pub", "composer", "terraform", "swift",
      "ansiblegalaxy", "conda", "conan", "apt", "alpine", "gitlfs", "huggingface", "go",
      "r", "helm", "docker", "nuget", "rubygems", "yum", "arch", "raw");

  @Test
  void welcomePageShowsEverySupportedFormatWithAnIcon() throws IOException {
    String index = resource("/META-INF/resources/browse/index.html");
    String stylesheet = resource("/META-INF/resources/browse/assets/browse.css");

    assertTrue(index.contains("id=\"supported-formats-title\""));
    assertTrue(index.contains("class=\"format-showcase-count\">" + FORMATS.size() + " formats"));
    assertTrue(stylesheet.contains(".format-grid {"));
    assertTrue(stylesheet.contains("gap: 0;"));
    assertTrue(stylesheet.contains("overflow: hidden;"));
    assertTrue(stylesheet.contains(".format-item {"));
    assertTrue(stylesheet.contains("min-height: 72px;"));
    assertTrue(stylesheet.contains("border-right: 1px solid var(--line);"));
    assertTrue(stylesheet.contains("border-bottom: 1px solid var(--line);"));
    assertTrue(stylesheet.contains("cursor: default;"));
    assertTrue(
        index.indexOf("id=\"admin-bootstrap-panel\"")
            < index.indexOf("class=\"format-showcase\""));
    for (String format : FORMATS) {
      assertTrue(index.contains("data-format=\"" + format + "\""), format);
      try (InputStream icon = getClass().getResourceAsStream(
          "/META-INF/resources/browse/assets/formats/" + iconName(format)
              + ("ohpm".equals(format) ? ".png" : ".svg"))) {
        assertNotNull(icon, format);
      }
    }
  }

  @Test
  void welcomeFormatsAreSortedAndSplitToolFromEcosystem() throws IOException {
    String index = resource("/META-INF/resources/browse/index.html");
    java.util.regex.Matcher names = java.util.regex.Pattern.compile(
        "<span class=\"format-name\"><strong>([^<]+)</strong>(?:<small>([^<]+)</small>)?</span>")
        .matcher(index);
    List<String> displayed = new java.util.ArrayList<>();
    while (names.find()) {
      assertTrue(!names.group(1).contains("/"), "tool and ecosystem must be split: " + names.group(1));
      displayed.add(names.group(2) == null ? names.group(1) : names.group(1) + " / " + names.group(2));
    }
    assertEquals(FORMATS.size(), displayed.size());
    List<String> sorted = new java.util.ArrayList<>(displayed);
    sorted.sort(String.CASE_INSENSITIVE_ORDER);
    assertEquals(sorted, displayed, "welcome formats must stay in alphabetical order");
    assertTrue(displayed.contains("APK / Alpine"));
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
