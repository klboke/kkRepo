package com.github.klboke.kkrepo.persistence.mysql.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class UiSettingsDaoTest {
  @Test
  void normalizesSupportedDefaultLanguages() {
    assertEquals("en", UiSettingsDao.normalizeDefaultLanguage(null));
    assertEquals("en", UiSettingsDao.normalizeDefaultLanguage(" "));
    assertEquals("browser", UiSettingsDao.normalizeDefaultLanguage("BROWSER"));
    assertEquals("zh-CN", UiSettingsDao.normalizeDefaultLanguage("zh"));
    assertEquals("zh-CN", UiSettingsDao.normalizeDefaultLanguage("zh_cn"));
    assertEquals("zh-CN", UiSettingsDao.normalizeDefaultLanguage("zh-CN"));
    assertEquals("en", UiSettingsDao.normalizeDefaultLanguage("en-US"));
    assertEquals("en", UiSettingsDao.normalizeDefaultLanguage("EN"));
  }

  @Test
  void rejectsUnsupportedDefaultLanguage() {
    assertThrows(IllegalArgumentException.class, () -> UiSettingsDao.normalizeDefaultLanguage("fr"));
  }
}
