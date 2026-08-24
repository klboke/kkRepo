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
}
