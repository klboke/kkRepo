package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class AdminProductVersionContractTest {
  private static final Pattern VERSION_BADGE = Pattern.compile(
      "Repository Manager <span class=\"product-version\">v[^<@]+</span>");

  @Test
  void headerIncludesFilteredProjectVersion() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");

    assertTrue(VERSION_BADGE.matcher(index).find());
    assertFalse(index.contains("@project.version@"));
    assertTrue(index.contains("value=\"${dn}\""));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
