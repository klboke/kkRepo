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
    assertTrue(indigoTheme.contains(
        "--account-avatar-background: linear-gradient(145deg, #6366f1, #3730a3)"));
    assertTrue(indigoTheme.contains("--account-avatar-presence: #34d399"));
    assertEquals(customPropertyNames(defaultTheme), customPropertyNames(indigoTheme));
  }

  @Test
  void oceanThemeImplementsTheCompleteSharedTokenContract() throws IOException {
    String defaultTheme = resource("/META-INF/resources/browse/assets/themes/default.css");
    String oceanTheme = resource("/META-INF/resources/browse/assets/themes/ocean.css");

    assertTrue(oceanTheme.contains("color-scheme: light"));
    assertTrue(oceanTheme.contains("--brand: #0369a1"));
    assertTrue(oceanTheme.contains("--accent: #0f766e"));
    assertTrue(oceanTheme.contains("--canvas: #f3f8fb"));
    assertTrue(oceanTheme.contains("--topbar: #082f49"));
    assertTrue(oceanTheme.contains(
        "--account-avatar-background: linear-gradient(145deg, #0ea5e9, #0369a1)"));
    assertEquals(customPropertyNames(defaultTheme), customPropertyNames(oceanTheme));
  }

  @Test
  void sunsetThemeImplementsTheCompleteSharedTokenContract() throws IOException {
    String defaultTheme = resource("/META-INF/resources/browse/assets/themes/default.css");
    String sunsetTheme = resource("/META-INF/resources/browse/assets/themes/sunset.css");

    assertTrue(sunsetTheme.contains("color-scheme: light"));
    assertTrue(sunsetTheme.contains("--brand: #be123c"));
    assertTrue(sunsetTheme.contains("--accent: #c4320a"));
    assertTrue(sunsetTheme.contains("--canvas: #fdf8f6"));
    assertTrue(sunsetTheme.contains("--topbar: #2a151b"));
    assertTrue(sunsetTheme.contains(
        "--account-avatar-background: linear-gradient(145deg, #fb7185, #be123c)"));
    assertEquals(customPropertyNames(defaultTheme), customPropertyNames(sunsetTheme));
  }

  @Test
  void jfrogThemeImplementsTheCompleteSharedTokenContract() throws IOException {
    String defaultTheme = resource("/META-INF/resources/browse/assets/themes/default.css");
    String jfrogTheme = resource("/META-INF/resources/browse/assets/themes/jfrog.css");

    assertTrue(jfrogTheme.contains("color-scheme: light"));
    assertTrue(jfrogTheme.contains("--brand: #16883b"));
    assertTrue(jfrogTheme.contains("--brand-bright: #40be46"));
    assertTrue(jfrogTheme.contains("--accent: #184ea0"));
    assertTrue(jfrogTheme.contains("--canvas: #f4f7f9"));
    assertTrue(jfrogTheme.contains("--topbar: #061121"));
    assertTrue(jfrogTheme.contains(
        "--account-avatar-background: linear-gradient(145deg, #184ea0, #0c1d37)"));
    assertEquals(customPropertyNames(defaultTheme), customPropertyNames(jfrogTheme));
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
