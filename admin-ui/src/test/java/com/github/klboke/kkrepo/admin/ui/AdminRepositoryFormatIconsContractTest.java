package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class AdminRepositoryFormatIconsContractTest {
  private static final List<String> FORMATS = List.of(
      "maven2", "npm", "pypi", "cargo", "pub", "composer", "terraform", "swift", "go",
      "helm", "docker", "nuget", "rubygems", "yum", "raw");

  @Test
  void repositoryFormatColumnUsesSharedBrandIcons() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String javascript = resource("/META-INF/resources/admin/assets/admin.js");

    assertTrue(index.contains("/browse/assets/format-icons.css"));
    assertTrue(javascript.contains("<td>${formatBadge(repo.format)}</td>"));
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
