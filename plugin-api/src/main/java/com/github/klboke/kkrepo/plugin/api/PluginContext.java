package com.github.klboke.kkrepo.plugin.api;

import com.github.klboke.kkrepo.core.blobstore.BlobStoreProvider;

/**
 * Registration surface available to a plugin. The runtime only exposes the ports that the plugin
 * declared in its descriptor.
 */
public interface PluginContext {

  /**
   * Registers a network transport provider.
   *
   * @throws IllegalArgumentException if the provider type is already registered
   */
  void registerRemoteTransport(RemoteTransportProvider provider);

  /**
   * Registers a repository format.
   *
   * @throws IllegalArgumentException if the format id is already registered or the descriptor does
   *     not match the provider id
   */
  void registerRepositoryFormat(RepositoryFormatProvider provider);

  /**
   * Registers a framework-agnostic repository protocol handler.
   *
   * @throws IllegalArgumentException if the handler is null, has no route, or registers a route
   *     that cannot be mapped to a runtime repository format
   */
  void registerRepositoryProtocolHandler(RepositoryProtocolHandler handler);

  /**
   * Registers a storage backend. The provider type must be unique across built-in and plugin
   * providers.
   */
  void registerBlobStoreProvider(BlobStoreProvider provider);

  /**
   * Registers a read-only cleanup rule. The runtime owns candidate resolution, policy, locks and
   * deletion.
   */
  void registerCleanupRule(CleanupRule rule);

  /** Registers a scheduled maintenance task. The runtime owns dry-run, quotas and audit. */
  void registerMaintenanceTask(MaintenanceTask task);
}
