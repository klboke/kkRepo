package com.github.klboke.kkrepo.plugin.loader;

import com.github.klboke.kkrepo.core.blobstore.BlobStoreProvider;
import com.github.klboke.kkrepo.plugin.api.CleanupRule;
import com.github.klboke.kkrepo.plugin.api.KkRepoPlugin;
import com.github.klboke.kkrepo.plugin.api.MaintenanceTask;
import com.github.klboke.kkrepo.plugin.api.RemoteTransportProvider;
import com.github.klboke.kkrepo.plugin.api.RepositoryFormatProvider;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolHandler;
import java.io.IOException;
import java.util.List;

/** Result of one plugin scan; owns the classloaders created for the loaded plugins. */
public record LoadedPlugins(
    List<KkRepoPlugin> plugins,
    List<RemoteTransportProvider> remoteTransports,
    List<RepositoryFormatProvider> repositoryFormats,
    List<RepositoryProtocolHandler> repositoryProtocolHandlers,
    List<BlobStoreProvider> blobStoreProviders,
    List<CleanupRule> cleanupRules,
    List<MaintenanceTask> maintenanceTasks,
    List<PluginClassLoader> classLoaders) implements AutoCloseable {

  public LoadedPlugins {
    plugins = plugins == null ? List.of() : List.copyOf(plugins);
    remoteTransports = remoteTransports == null ? List.of() : List.copyOf(remoteTransports);
    repositoryFormats = repositoryFormats == null ? List.of() : List.copyOf(repositoryFormats);
    repositoryProtocolHandlers = repositoryProtocolHandlers == null
        ? List.of()
        : List.copyOf(repositoryProtocolHandlers);
    blobStoreProviders = blobStoreProviders == null ? List.of() : List.copyOf(blobStoreProviders);
    cleanupRules = cleanupRules == null ? List.of() : List.copyOf(cleanupRules);
    maintenanceTasks = maintenanceTasks == null ? List.of() : List.copyOf(maintenanceTasks);
    classLoaders = classLoaders == null ? List.of() : List.copyOf(classLoaders);
  }

  /** Compatibility constructor for callers that predate storage and maintenance ports. */
  public LoadedPlugins(
      List<KkRepoPlugin> plugins,
      List<RemoteTransportProvider> remoteTransports,
      List<RepositoryFormatProvider> repositoryFormats,
      List<RepositoryProtocolHandler> repositoryProtocolHandlers,
      List<PluginClassLoader> classLoaders) {
    this(
        plugins,
        remoteTransports,
        repositoryFormats,
        repositoryProtocolHandlers,
        List.of(),
        List.of(),
        List.of(),
        classLoaders);
  }

  /** Compatibility constructor for callers that predate protocol handlers. */
  public LoadedPlugins(
      List<KkRepoPlugin> plugins,
      List<RemoteTransportProvider> remoteTransports,
      List<RepositoryFormatProvider> repositoryFormats,
      List<PluginClassLoader> classLoaders) {
    this(plugins, remoteTransports, repositoryFormats, List.of(), classLoaders);
  }

  @Override
  public void close() {
    for (PluginClassLoader classLoader : classLoaders) {
      try {
        classLoader.close();
      } catch (IOException ignored) {
        // A classloader close failure must not hide the original plugin operation failure.
      }
    }
  }
}
