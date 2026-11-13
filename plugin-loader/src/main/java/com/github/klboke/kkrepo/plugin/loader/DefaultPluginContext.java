package com.github.klboke.kkrepo.plugin.loader;

import com.github.klboke.kkrepo.core.blobstore.BlobStoreProvider;
import com.github.klboke.kkrepo.plugin.api.CleanupRule;
import com.github.klboke.kkrepo.plugin.api.MaintenanceTask;
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
  private final Map<String, BlobStoreProvider> blobStoreProviders = new LinkedHashMap<>();
  private final Map<String, CleanupRule> cleanupRules = new LinkedHashMap<>();
  private final Map<String, MaintenanceTask> maintenanceTasks = new LinkedHashMap<>();

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
        : List.copyOf(handler.routes());
    if (routes.isEmpty() || routes.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalArgumentException(
          "Repository protocol handler must register at least one non-null route: "
              + handler.getClass().getName());
    }
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

  @Override
  public void registerCleanupRule(CleanupRule rule) {
    if (rule == null || rule.id() == null || rule.id().isBlank()) {
      throw new IllegalArgumentException("Cleanup rule and id are required");
    }
    String key = rule.id().trim();
    if (cleanupRules.putIfAbsent(key, rule) != null) {
      throw new IllegalArgumentException("Duplicate cleanup rule id: " + key);
    }
  }

  @Override
  public void registerMaintenanceTask(MaintenanceTask task) {
    if (task == null || task.id() == null || task.id().isBlank()) {
      throw new IllegalArgumentException("Maintenance task and id are required");
    }
    String key = task.id().trim();
    if (maintenanceTasks.putIfAbsent(key, task) != null) {
      throw new IllegalArgumentException("Duplicate maintenance task id: " + key);
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

  List<CleanupRule> cleanupRules() {
    return List.copyOf(cleanupRules.values());
  }

  List<MaintenanceTask> maintenanceTasks() {
    return List.copyOf(maintenanceTasks.values());
  }
}
