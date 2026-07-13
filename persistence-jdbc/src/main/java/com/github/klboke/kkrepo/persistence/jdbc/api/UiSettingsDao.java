package com.github.klboke.kkrepo.persistence.jdbc.api;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.UiSettingsRecord;

public interface UiSettingsDao {
  String LANGUAGE_BROWSER = "browser";
  String LANGUAGE_ZH_CN = "zh-CN";
  String LANGUAGE_EN = "en";
  String DEFAULT_LANGUAGE = LANGUAGE_EN;

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

  UiSettingsRecord read();

  UiSettingsRecord saveDefaultLanguage(String defaultLanguage);
}
