package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import org.junit.jupiter.api.Test;

class UiSettingsDaoTest {
  @Test
  void normalizesSupportedDefaultLanguages() {
    assertEquals("en", JdbcUiSettingsDao.normalizeDefaultLanguage(null));
    assertEquals("en", JdbcUiSettingsDao.normalizeDefaultLanguage(" "));
    assertEquals("browser", JdbcUiSettingsDao.normalizeDefaultLanguage("BROWSER"));
    assertEquals("zh-CN", JdbcUiSettingsDao.normalizeDefaultLanguage("zh"));
    assertEquals("zh-CN", JdbcUiSettingsDao.normalizeDefaultLanguage("zh_cn"));
    assertEquals("zh-CN", JdbcUiSettingsDao.normalizeDefaultLanguage("zh-CN"));
    assertEquals("en", JdbcUiSettingsDao.normalizeDefaultLanguage("en-US"));
    assertEquals("en", JdbcUiSettingsDao.normalizeDefaultLanguage("EN"));
  }

  @Test
  void rejectsUnsupportedDefaultLanguage() {
    assertThrows(IllegalArgumentException.class, () -> JdbcUiSettingsDao.normalizeDefaultLanguage("fr"));
  }
}
