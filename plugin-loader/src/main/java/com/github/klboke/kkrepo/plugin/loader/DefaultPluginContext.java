package com.github.klboke.kkrepo.plugin.loader;

import com.github.klboke.kkrepo.plugin.api.PluginContext;
import com.github.klboke.kkrepo.plugin.api.RemoteTransportProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Registration collector used while a plugin contributes its ports. */
final class DefaultPluginContext implements PluginContext {
  private final Map<String, RemoteTransportProvider> remoteTransports = new LinkedHashMap<>();

  @Override
  public void registerRemoteTransport(RemoteTransportProvider provider) {
    if (provider == null) {
      throw new IllegalArgumentException("Remote transport provider is required");
    }
    String type = provider.type();
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("Remote transport provider type is required");
    }
    String key = type.trim().toLowerCase(Locale.ROOT);
    if (remoteTransports.putIfAbsent(key, provider) != null) {
      throw new IllegalArgumentException("Duplicate remote transport type: " + type);
    }
  }

  List<RemoteTransportProvider> remoteTransports() {
    return List.copyOf(remoteTransports.values());
  }
}
