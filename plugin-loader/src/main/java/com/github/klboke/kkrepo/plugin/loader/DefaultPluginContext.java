package com.github.klboke.kkrepo.plugin.loader;

import com.github.klboke.kkrepo.plugin.api.PluginContext;
import com.github.klboke.kkrepo.plugin.api.RemoteTransportProvider;
import com.github.klboke.kkrepo.plugin.api.RepositoryFormatDescriptor;
import com.github.klboke.kkrepo.plugin.api.RepositoryFormatProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Registration collector used while a plugin contributes its ports. */
final class DefaultPluginContext implements PluginContext {
  private final Map<String, RemoteTransportProvider> remoteTransports = new LinkedHashMap<>();
  private final Map<String, RepositoryFormatProvider> repositoryFormats = new LinkedHashMap<>();

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

  @Override
  public void registerRepositoryFormat(RepositoryFormatProvider provider) {
    if (provider == null || provider.id() == null) {
      throw new IllegalArgumentException("Repository format provider and id are required");
    }
    RepositoryFormatDescriptor descriptor = provider.formatDescriptor();
    if (descriptor == null || !provider.id().equals(descriptor.id())) {
      throw new IllegalArgumentException(
          "Repository format descriptor id must match provider id: " + provider.id());
    }
    String key = provider.id().value();
    if (repositoryFormats.putIfAbsent(key, provider) != null) {
      throw new IllegalArgumentException("Duplicate repository format id: " + key);
    }
  }

  List<RemoteTransportProvider> remoteTransports() {
    return List.copyOf(remoteTransports.values());
  }

  List<RepositoryFormatProvider> repositoryFormats() {
    return List.copyOf(repositoryFormats.values());
  }
}
