package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class AdminUiScrollbarContractTest {

  @Test
  void loadsSharedScrollbarStylesAfterTheThemeAndBeforeComponentStyles() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String admin = resource("/META-INF/resources/admin/assets/admin.css");

    int theme = index.indexOf("id=\"kkrepo-theme-stylesheet\"");
    int scrollbars = index.indexOf("/browse/assets/ui-scrollbars.css");
    int components = index.indexOf("./assets/admin.css");

    assertTrue(theme >= 0 && scrollbars > theme && components > scrollbars);
    assertTrue(index.contains(
        "/browse/assets/ui-scrollbars.css?v=20260826-shared-scrollbars-1"));
    assertFalse(admin.contains("::-webkit-scrollbar"));
    assertFalse(admin.contains("scrollbar-color:"));
    assertFalse(admin.contains("scrollbar-width:"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
