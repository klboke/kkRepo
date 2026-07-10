package com.github.klboke.kkrepo.browse.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class BrowseProductVersionContractTest {
  private static final Pattern VERSION_BADGE = Pattern.compile(
      "kkRepo <span class=\"product-version\">v[^<@]+</span><br>Repository Manager");

  @Test
  void headerIncludesFilteredProjectVersion() throws IOException {
    String index = resource("/META-INF/resources/browse/index.html");

    assertTrue(VERSION_BADGE.matcher(index).find());
    assertFalse(index.contains("@project.version@"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
