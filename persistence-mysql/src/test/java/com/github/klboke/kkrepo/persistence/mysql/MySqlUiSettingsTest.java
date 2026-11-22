package com.github.klboke.kkrepo.persistence.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import org.junit.jupiter.api.Test;

class MySqlUiSettingsTest extends MySqlIntegrationTestSupport {
  @Test
  void persistsTheSharedDefaultTheme() {
    var defaults = stores().uiSettings().read();
    assertEquals("en", defaults.defaultLanguage());
    assertEquals("default", defaults.defaultTheme());

    var saved = stores().uiSettings().save("zh-CN", "default");
    assertEquals("zh-CN", saved.defaultLanguage());
    assertEquals("default", saved.defaultTheme());
    assertEquals("default", jdbc().queryForObject(
        "SELECT default_theme FROM ui_settings WHERE id = 1", String.class));
  }

  @Test
  void databaseConstraintAcceptsEveryUiLanguage() {
    try {
      for (String language : java.util.stream.Stream.concat(
          java.util.stream.Stream.of(com.github.klboke.kkrepo.persistence.jdbc.api.UiSettingsDao.LANGUAGE_BROWSER),
          com.github.klboke.kkrepo.persistence.jdbc.api.UiSettingsDao.LANGUAGES.stream()).toList()) {
        stores().uiSettings().save(language, "default");
        assertEquals(language, jdbc().queryForObject(
            "SELECT default_language FROM ui_settings WHERE id = 1", String.class));
      }
    } finally {
      // Other tests read the shared row and expect the default language.
      stores().uiSettings().save("en", "default");
    }
  }
}
