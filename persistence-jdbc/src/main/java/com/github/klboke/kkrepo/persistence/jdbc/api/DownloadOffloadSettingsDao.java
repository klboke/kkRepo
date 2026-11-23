package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.util.List;

public interface DownloadOffloadSettingsDao {
  Settings read();

  boolean replace(Settings settings);

  record Settings(long revision, boolean enabled, String method, String customHeader,
                  String customTarget, List<Long> storeIds) {
    public Settings {
      storeIds = List.copyOf(storeIds);
    }
  }
}
