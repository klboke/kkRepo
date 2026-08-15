package com.github.klboke.kkrepo.browse.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class BrowseAlpineRepositoryContractTest {
  @Test
  void exposesSearchBrowseUsageAndHostedUpload() throws IOException {
    String index = resource("/META-INF/resources/browse/index.html");
    String javascript = resource("/META-INF/resources/browse/assets/browse.js");
    String stylesheet = resource("/META-INF/resources/browse/assets/format-icons.css");

    assertTrue(index.contains("data-search-format=\"alpine\""));
    assertTrue(index.contains("data-format=\"alpine\""));
    assertTrue(index.contains("Alpine / APK"));
    assertTrue(javascript.contains("function alpineUsageDetail"));
    assertTrue(javascript.contains("sudo apk update"));
    assertTrue(javascript.contains("apk fetch --repository"));
    assertTrue(javascript.contains("renderAttributeGroup(\"Alpine\", detail.alpine)"));
    assertTrue(javascript.contains("form.append(\"alpine.asset\""));
    assertTrue(stylesheet.contains(".format-logo-alpine"));
    assertTrue(stylesheet.contains("/browse/assets/formats/alpine.svg"));
    assertNotNull(getClass().getResource(
        "/META-INF/resources/browse/assets/formats/alpine.svg"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
