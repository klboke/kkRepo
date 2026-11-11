package com.github.klboke.kkrepo.plugin.api;

/** A plugin loaded by the runtime. */
public interface KkRepoPlugin {

  /** Metadata declared by the plugin. */
  PluginDescriptor descriptor();

  /**
   * Registers the plugin contributions. Called once after the JAR signature and descriptor have
   * been validated.
   */
  void contribute(PluginContext context);
}
