package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.util.List;

public interface OutboundProxySettingsDao {
  Settings read();

  boolean replace(Settings settings);

  record Settings(long revision, boolean enabled, String type, String host, Integer port,
                  String username, String password, List<String> bypassHosts) {}
}
