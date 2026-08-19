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
  private static final String PROJECT_URL = "https://github.com/klboke/kkRepo";
  private static final String RELEASES_URL = PROJECT_URL + "/releases";
  private static final String EXTERNAL_LINK_ATTRIBUTES = "target=\"_blank\" rel=\"noopener noreferrer\"";
  private static final Pattern VERSION_BADGE = Pattern.compile(
      "<a class=\"product-version\" data-current-version=\"([^\"@]+)\" href=\""
          + Pattern.quote(RELEASES_URL) + "\" " + EXTERNAL_LINK_ATTRIBUTES + ">v\\1</a>");

  @Test
  void headerLinksBrandAndFilteredVersion() throws IOException {
    String index = resource("/META-INF/resources/browse/index.html");

    assertTrue(VERSION_BADGE.matcher(index).find());
    assertTrue(index.contains("class=\"logo-mark product-home-link\" href=\"" + PROJECT_URL
        + "\" " + EXTERNAL_LINK_ATTRIBUTES));
    assertTrue(index.contains("class=\"product-link\" href=\"" + PROJECT_URL + "\" "
        + EXTERNAL_LINK_ATTRIBUTES + ">kkRepo</a>"));
    assertTrue(index.contains("class=\"product-link\" href=\"" + PROJECT_URL + "\" "
        + EXTERNAL_LINK_ATTRIBUTES + ">Repository Manager</a>"));
    assertTrue(index.contains("class=\"product-update-link\" data-product-update hidden "
        + EXTERNAL_LINK_ATTRIBUTES));
    assertTrue(index.contains("class=\"lucide-icon icon-cloud-download product-update-icon\""));
    assertTrue(index.contains("data-product-update-tooltip role=\"tooltip\""));
    assertTrue(index.contains("/login/assets/product-update.js?v=20260819-version-update-1"));
    assertFalse(index.contains("/releases/tag/v0.9.0"));
    assertFalse(index.contains("@project.version@"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
