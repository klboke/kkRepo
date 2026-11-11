package com.github.klboke.kkrepo.plugin.api;

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
}
