package com.github.klboke.kkrepo.browse.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class BrowseUiScrollbarContractTest {

  @Test
  void loadsSharedScrollbarStylesAfterTheThemeAndBeforeComponentStyles() throws IOException {
    String index = resource("/META-INF/resources/browse/index.html");

    int theme = index.indexOf("id=\"kkrepo-theme-stylesheet\"");
    int scrollbars = index.indexOf("/browse/assets/ui-scrollbars.css");
    int components = index.indexOf("/browse/assets/browse.css");

    assertTrue(theme >= 0 && scrollbars > theme && components > scrollbars);
    assertTrue(index.contains(
        "/browse/assets/ui-scrollbars.css?v=20260826-shared-scrollbars-3"));
  }

  @Test
  void appliesOneThinTransparentThemeAwareStyleToEveryScrollContainer() throws IOException {
    String shared = resource("/META-INF/resources/browse/assets/ui-scrollbars.css");
    String browse = resource("/META-INF/resources/browse/assets/browse.css");

    assertTrue(shared.contains("--ui-scrollbar-size: 6px"));
    assertTrue(shared.contains("--ui-scrollbar-thumb: rgba(36, 50, 46, 0.54)"));
    assertTrue(shared.contains("--ui-scrollbar-thumb-hover: rgba(36, 50, 46, 0.7)"));
    assertTrue(shared.contains("@supports (color: color-mix(in srgb, black, transparent))"));
    assertTrue(shared.contains("color-mix(in srgb, var(--ink-2) 54%, transparent)"));
    assertTrue(shared.contains("color-mix(in srgb, var(--ink-2) 70%, transparent)"));
    assertTrue(shared.contains("*::-webkit-scrollbar"));
    assertTrue(shared.contains("height: var(--ui-scrollbar-size)"));
    assertTrue(shared.contains("*::-webkit-scrollbar-thumb:hover"));
    assertTrue(shared.contains("@supports not selector(::-webkit-scrollbar)"));
    assertTrue(shared.contains("scrollbar-color: var(--ui-scrollbar-thumb) transparent"));
    assertTrue(shared.contains("scrollbar-width: thin"));
    assertFalse(browse.contains("::-webkit-scrollbar"));
    assertFalse(browse.contains("scrollbar-color:"));
    assertFalse(browse.contains("scrollbar-width:"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
