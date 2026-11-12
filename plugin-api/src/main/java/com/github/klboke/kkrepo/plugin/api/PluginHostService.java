package com.github.klboke.kkrepo.plugin.api;

/**
 * Marker for a host service that a plugin may request through {@link RepositoryContext#service}.
 * The host owns the lifecycle and transaction boundaries; plugins only call the narrow interface.
 */
public interface PluginHostService {
}
