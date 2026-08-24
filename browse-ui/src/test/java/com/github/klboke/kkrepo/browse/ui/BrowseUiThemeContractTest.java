package com.github.klboke.kkrepo.browse.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class BrowseUiThemeContractTest {
  @Test
  void loadsTheSharedThemeBeforeComponentStyles() throws IOException {
    String index = resource("/META-INF/resources/browse/index.html");

    int theme = index.indexOf("id=\"kkrepo-theme-stylesheet\"");
    int bootstrap = index.indexOf("/login/assets/ui-theme.js");
    int components = index.indexOf("/browse/assets/browse.css");
    assertTrue(theme >= 0 && bootstrap > theme && components > bootstrap);
    assertTrue(index.contains("/browse/assets/themes/default.css"));
    assertFalse(index.contains("/browse/assets/tokens.css"));
  }

  @Test
  void currentVisualTokensAreTheDefaultThemeTemplate() throws IOException {
    String theme = resource("/META-INF/resources/browse/assets/themes/default.css");
    String legacyAlias = resource("/META-INF/resources/browse/assets/tokens.css");

    assertTrue(theme.contains(":root {"));
    for (String token : new String[]{
        "--brand:", "--canvas:", "--font-sans:", "--shadow-modal:", "--active:"
    }) {
      assertTrue(theme.contains(token), token);
    }
    assertTrue(legacyAlias.contains("./themes/default.css"));
  }

  @Test
  void indigoThemeImplementsTheCompleteSharedTokenContract() throws IOException {
    String defaultTheme = resource("/META-INF/resources/browse/assets/themes/default.css");
    String indigoTheme = resource("/META-INF/resources/browse/assets/themes/indigo.css");

    assertTrue(indigoTheme.contains("color-scheme: light"));
    assertTrue(indigoTheme.contains("--brand: #4f46e5"));
    assertTrue(indigoTheme.contains("--accent: #c2410c"));
    assertTrue(indigoTheme.contains("--canvas: #f6f8fc"));
    assertTrue(indigoTheme.contains("--topbar: #111827"));
    assertEquals(customPropertyNames(defaultTheme), customPropertyNames(indigoTheme));
  }

  private Set<String> customPropertyNames(String css) {
    Set<String> names = new HashSet<>();
    Matcher matcher = Pattern.compile("(--[a-z0-9-]+)\\s*:").matcher(css);
    while (matcher.find()) {
      names.add(matcher.group(1));
    }
    return names;
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
