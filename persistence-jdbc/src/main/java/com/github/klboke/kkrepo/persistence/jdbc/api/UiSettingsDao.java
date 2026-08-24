package com.github.klboke.kkrepo.persistence.jdbc.api;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.UiSettingsRecord;

public interface UiSettingsDao {
  String LANGUAGE_BROWSER = "browser";
  String LANGUAGE_ZH_CN = "zh-CN";
  String LANGUAGE_EN = "en";
  String DEFAULT_LANGUAGE = LANGUAGE_EN;
  String THEME_DEFAULT = "default";
  String THEME_INDIGO = "indigo";
  String THEME_OCEAN = "ocean";
  String THEME_SUNSET = "sunset";
  String DEFAULT_THEME = THEME_DEFAULT;

  static String normalizeDefaultLanguage(String defaultLanguage) {
    if (defaultLanguage == null || defaultLanguage.isBlank()) {
      return DEFAULT_LANGUAGE;
    }
    String normalized = defaultLanguage.trim();
    if (LANGUAGE_BROWSER.equalsIgnoreCase(normalized)) {
      return LANGUAGE_BROWSER;
    }
    if ("zh".equalsIgnoreCase(normalized)
        || "zh-cn".equalsIgnoreCase(normalized)
        || "zh_CN".equalsIgnoreCase(normalized)) {
      return LANGUAGE_ZH_CN;
    }
    if ("en".equalsIgnoreCase(normalized) || "en-US".equalsIgnoreCase(normalized)) {
      return LANGUAGE_EN;
    }
    throw new IllegalArgumentException("Unsupported UI default language: " + defaultLanguage);
  }

  static String normalizeDefaultTheme(String defaultTheme) {
    if (defaultTheme == null || defaultTheme.isBlank()) {
      return DEFAULT_THEME;
    }
    String normalized = defaultTheme.trim().toLowerCase(java.util.Locale.ROOT);
    if (!normalized.matches("[a-z0-9][a-z0-9-]{0,63}")) {
      throw new IllegalArgumentException("Invalid UI default theme: " + defaultTheme);
    }
    return normalized;
  }

  UiSettingsRecord read();

  UiSettingsRecord save(String defaultLanguage, String defaultTheme);
}
