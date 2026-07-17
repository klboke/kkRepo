package com.github.klboke.kkrepo.browse.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class BrowseRepositoryFormatIconsContractTest {
  private static final List<String> FORMATS = List.of(
      "maven2", "npm", "pypi", "cargo", "pub", "composer", "terraform", "swift", "go",
      "helm", "docker", "nuget", "rubygems", "yum", "raw");
  private static final List<String> SEARCH_FORMATS = List.of(
      "cargo", "go", "helm", "maven2", "nuget",
      "pub", "pypi", "rubygems", "swift", "yum", "npm");

  @Test
  void repositoryFormatColumnUsesSharedBrandIcons() throws IOException {
    String index = resource("/META-INF/resources/browse/index.html");
    String javascript = resource("/META-INF/resources/browse/assets/browse.js");
    String stylesheet = resource("/META-INF/resources/browse/assets/format-icons.css");

    assertTrue(index.contains("/browse/assets/format-icons.css"));
    assertTrue(javascript.contains("<td>${formatBadge(repo.format)}</td>"));
    assertTrue(javascript.contains("${repositoryFormatIcon()}"));
    assertTrue(javascript.contains("format-logo crumb-format-logo format-logo-${formatIconName(repo?.format)}"));
    assertTrue(stylesheet.contains(".format-cell"));
    String browseStylesheet = resource("/META-INF/resources/browse/assets/browse.css");
    assertTrue(browseStylesheet.contains(".crumb-format-logo"));
    assertTrue(browseStylesheet.contains(".side-subitem .format-logo"));
    for (String format : SEARCH_FORMATS) {
      assertTrue(index.contains(
          "data-search-format=\"" + format + "\" type=\"button\"><span class=\"format-logo format-logo-"
              + iconName(format) + "\""), format);
    }
    for (String format : FORMATS) {
      assertTrue(javascript.contains(format + ": \"" + iconName(format) + "\""), format);
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
