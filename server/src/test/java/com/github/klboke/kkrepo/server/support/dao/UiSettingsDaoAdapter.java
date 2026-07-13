package com.github.klboke.kkrepo.server.support.dao;

import com.github.klboke.kkrepo.persistence.jdbc.api.UiSettingsDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.UiSettingsRecord;

/** Test-only base class for focused UiSettingsDao fakes. */
public class UiSettingsDaoAdapter implements UiSettingsDao {
  public UiSettingsDaoAdapter() {
  }

  public UiSettingsDaoAdapter(Object ignored) {
  }

  public UiSettingsDaoAdapter(Object firstIgnored, Object secondIgnored) {
  }

  @Override
  public UiSettingsRecord read() {
    throw new UnsupportedOperationException();
  }

  @Override
  public UiSettingsRecord saveDefaultLanguage(String arg0) {
    throw new UnsupportedOperationException();
  }
}
