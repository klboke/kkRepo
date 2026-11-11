package com.github.klboke.kkrepo.server.plugin;

import com.github.klboke.kkrepo.plugin.api.RemoteTransportProvider;
import com.github.klboke.kkrepo.plugin.api.RepositoryFormatProvider;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolHandler;
import com.github.klboke.kkrepo.plugin.loader.LoadedPlugins;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Runtime view of the loaded plugins. It owns the plugin classloaders and exposes the registered
 * ports by their stable identifiers.
 */
public final class PluginRuntime implements AutoCloseable {
  private final LoadedPlugins loaded;
  private final Map<String, RemoteTransportProvider> remoteTransports;
  private final Map<String, RepositoryFormatProvider> repositoryFormats;
  private final List<RepositoryProtocolHandler> repositoryProtocolHandlers;

  public PluginRuntime(LoadedPlugins loaded) {
    this.loaded = Objects.requireNonNull(loaded, "loaded");
    Map<String, RemoteTransportProvider> transports = new LinkedHashMap<>();
    for (RemoteTransportProvider provider : loaded.remoteTransports()) {
      String key = normalize(provider.type(), "remote transport type");
      if (transports.putIfAbsent(key, provider) != null) {
        throw new IllegalStateException("Duplicate remote transport type: " + key);
      }
    }
    this.remoteTransports = Map.copyOf(transports);
    Map<String, RepositoryFormatProvider> formats = new LinkedHashMap<>();
    for (RepositoryFormatProvider provider : loaded.repositoryFormats()) {
      String key = normalize(provider.id().value(), "repository format id");
      if (formats.putIfAbsent(key, provider) != null) {
        throw new IllegalStateException("Duplicate repository format id: " + key);
      }
    }
    this.repositoryFormats = Map.copyOf(formats);
    this.repositoryProtocolHandlers = List.copyOf(loaded.repositoryProtocolHandlers());
  }

  public List<RemoteTransportProvider> remoteTransports() {
    return List.copyOf(remoteTransports.values());
  }

  public Optional<RemoteTransportProvider> remoteTransport(String type) {
    return type == null || type.isBlank()
        ? Optional.empty()
        : Optional.ofNullable(remoteTransports.get(type.trim().toLowerCase(Locale.ROOT)));
  }

  public List<RepositoryFormatProvider> repositoryFormats() {
    return List.copyOf(repositoryFormats.values());
  }

  public List<RepositoryProtocolHandler> repositoryProtocolHandlers() {
    return repositoryProtocolHandlers;
  }

  public Optional<RepositoryFormatProvider> repositoryFormat(String id) {
    return id == null || id.isBlank()
        ? Optional.empty()
        : Optional.ofNullable(repositoryFormats.get(id.trim().toLowerCase(Locale.ROOT)));
  }

  public Set<String> repositoryFormatIds() {
    return repositoryFormats.keySet();
  }

  public LoadedPlugins loaded() {
    return loaded;
  }

  @Override
  public void close() {
    loaded.close();
  }

  private static String normalize(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(label + " is required");
    }
    return value.trim().toLowerCase(Locale.ROOT);
  }
}
