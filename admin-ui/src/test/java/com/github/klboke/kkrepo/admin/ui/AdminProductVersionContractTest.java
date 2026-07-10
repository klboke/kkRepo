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
  private static final String PROJECT_URL = "https://github.com/klboke/kkRepo";
  private static final String RELEASES_URL = PROJECT_URL + "/releases";
  private static final String EXTERNAL_LINK_ATTRIBUTES = "target=\"_blank\" rel=\"noopener noreferrer\"";
  private static final Pattern VERSION_BADGE = Pattern.compile(
      "<a class=\"product-version\" href=\"" + Pattern.quote(RELEASES_URL)
          + "\" " + EXTERNAL_LINK_ATTRIBUTES + ">v[^<@]+</a>");

  @Test
  void headerLinksBrandAndFilteredVersion() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");

    assertTrue(VERSION_BADGE.matcher(index).find());
    assertTrue(index.contains("class=\"logo-mark product-home-link\" href=\"" + PROJECT_URL
        + "\" " + EXTERNAL_LINK_ATTRIBUTES));
    assertTrue(index.contains("class=\"product-link\" href=\"" + PROJECT_URL + "\" "
        + EXTERNAL_LINK_ATTRIBUTES + ">kkRepo</a>"));
    assertTrue(index.contains("class=\"product-link\" href=\"" + PROJECT_URL + "\" "
        + EXTERNAL_LINK_ATTRIBUTES + ">Repository Manager</a>"));
    assertFalse(index.contains("@project.version@"));
    assertTrue(index.contains("value=\"${dn}\""));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
