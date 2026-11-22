package com.github.klboke.kkrepo.plugin.loader;

import com.github.klboke.kkrepo.core.blobstore.BlobStoreProvider;
import com.github.klboke.kkrepo.plugin.api.PluginContext;
import com.github.klboke.kkrepo.plugin.api.RemoteTransportProvider;
import com.github.klboke.kkrepo.plugin.api.RepositoryFormatDescriptor;
import com.github.klboke.kkrepo.plugin.api.RepositoryFormatProvider;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolHandler;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolRoute;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Registration collector used while a plugin contributes its ports. */
final class DefaultPluginContext implements PluginContext {
  private final Map<String, RemoteTransportProvider> remoteTransports = new LinkedHashMap<>();
  private final Map<String, RepositoryFormatProvider> repositoryFormats = new LinkedHashMap<>();
  private final List<RepositoryProtocolHandler> repositoryProtocolHandlers = new ArrayList<>();
  private final Map<RouteKey, RepositoryProtocolHandler> routeOwners = new LinkedHashMap<>();
  private final Map<String, BlobStoreProvider> blobStoreProviders = new LinkedHashMap<>();

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

  @Override
  public void registerRepositoryProtocolHandler(RepositoryProtocolHandler handler) {
    if (handler == null) {
      throw new IllegalArgumentException("Repository protocol handler is required");
    }
    List<RepositoryProtocolRoute> routes = handler.routes() == null
        ? List.of()
        : new ArrayList<>(handler.routes());
    if (routes.isEmpty() || routes.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalArgumentException(
          "Repository protocol handler must register at least one non-null route: "
              + handler.getClass().getName());
    }
    // A route is owned by exactly one handler whatever its order: precedence must never let one
    // plugin silently replace another (or a built-in) implementation of the same route.
    Map<RouteKey, RepositoryProtocolHandler> claimed = new LinkedHashMap<>();
    for (RepositoryProtocolRoute route : routes) {
      RouteKey key = new RouteKey(route.format().value(), route.type().name(), route.method(),
          route.pathPattern());
      RepositoryProtocolHandler owner = routeOwners.get(key);
      if (owner != null || claimed.putIfAbsent(key, handler) != null) {
        throw new IllegalArgumentException("Duplicate repository protocol route " + key.describe()
            + ": " + (owner == null ? handler : owner).getClass().getName() + " and "
            + handler.getClass().getName());
      }
    }
    routeOwners.putAll(claimed);
    repositoryProtocolHandlers.add(handler);
  }

  @Override
  public void registerBlobStoreProvider(BlobStoreProvider provider) {
    if (provider == null || provider.type() == null || provider.type().isBlank()) {
      throw new IllegalArgumentException("Blob store provider and type are required");
    }
    String key = provider.type().trim().toLowerCase(Locale.ROOT);
    if (blobStoreProviders.putIfAbsent(key, provider) != null) {
      throw new IllegalArgumentException("Duplicate blob store provider type: " + provider.type());
    }
  }

  private record RouteKey(String format, String type, String method, String pathPattern) {
    String describe() {
      return format + "/" + type + " " + method + " " + pathPattern;
    }
  }

  List<RemoteTransportProvider> remoteTransports() {
    return List.copyOf(remoteTransports.values());
  }

  List<RepositoryFormatProvider> repositoryFormats() {
    return List.copyOf(repositoryFormats.values());
  }

  List<RepositoryProtocolHandler> repositoryProtocolHandlers() {
    return List.copyOf(repositoryProtocolHandlers);
  }

  List<BlobStoreProvider> blobStoreProviders() {
    return List.copyOf(blobStoreProviders.values());
  }
}
