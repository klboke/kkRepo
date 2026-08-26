package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class AdminUiThemeContractTest {
  @Test
  void loadsTheSharedThemeBeforeComponentStyles() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");

    int theme = index.indexOf("id=\"kkrepo-theme-stylesheet\"");
    int bootstrap = index.indexOf("/login/assets/ui-theme.js");
    int components = index.indexOf("./assets/admin.css");
    assertTrue(theme >= 0 && bootstrap > theme && components > bootstrap);
    assertTrue(index.contains("/browse/assets/themes/default.css"));
    assertFalse(index.contains("/browse/assets/tokens.css"));
  }

  @Test
  void exposesDefaultThemeSelectionInUiSettings() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String javascript = resource("/META-INF/resources/admin/assets/admin.js");
    String i18n = resource("/META-INF/resources/login/assets/ui-i18n.js");

    assertTrue(index.contains("id=\"ui-default-theme\" required"));
    assertTrue(index.contains("value=\"default\" selected>Default</option>"));
    assertTrue(index.contains("id=\"ui-theme-preview-status\" role=\"status\" aria-live=\"polite\" hidden"));
    assertTrue(javascript.contains("saveSettings(languageSelect.value, themeSelect.value)"));
    assertTrue(javascript.contains("window.kkrepoTheme.applyTheme("));
    assertTrue(javascript.contains("addEventListener(\"change\", previewUiTheme)"));
    assertTrue(javascript.contains("settings.supportedDefaultThemes"));
    assertTrue(javascript.contains("if (theme === \"jfrog\") return \"JFrog\""));
    assertTrue(i18n.contains("正在预览所选主题。保存界面设置后，它才会成为默认主题。"));
    assertTrue(index.contains("./assets/admin.css?v=20260826-ui-theme-preview-1"));
    assertTrue(index.contains("/login/assets/ui-i18n.js?v=20260826-ui-theme-preview-1"));
    assertTrue(index.contains("./assets/admin.js?v=20260826-ui-theme-preview-1"));
    assertFalse(index.contains("id=\"ui-settings-status\""));
    assertFalse(javascript.contains("Default language: ${"));
    assertFalse(javascript.contains("setDefaultTheme(themeSelect.value)"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
