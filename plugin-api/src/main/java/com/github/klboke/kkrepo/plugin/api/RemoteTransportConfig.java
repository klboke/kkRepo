package com.github.klboke.kkrepo.plugin.api;

import java.util.Map;

/** Provider-specific transport settings. */
public record RemoteTransportConfig(Map<String, Object> settings) {

  public RemoteTransportConfig {
    settings = settings == null ? Map.of() : Map.copyOf(settings);
  }
}
