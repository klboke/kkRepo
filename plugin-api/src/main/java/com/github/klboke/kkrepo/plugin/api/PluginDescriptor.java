package com.github.klboke.kkrepo.plugin.api;

import java.util.List;

/**
 * Metadata declared in {@code META-INF/kkrepo-plugin.json}.
 *
 * @param id stable plugin identifier, unique in the runtime
 * @param version plugin version, independent from the API version
 * @param apiVersion plugin API version the plugin was built against
 * @param mainClass class implementing {@link KkRepoPlugin}
 * @param capabilities capabilities requested by the plugin
 */
public record PluginDescriptor(
    String id,
    String version,
    String apiVersion,
    String mainClass,
    List<String> capabilities) {

  public PluginDescriptor {
    id = requireText(id, "id");
    version = requireText(version, "version");
    apiVersion = requireText(apiVersion, "apiVersion");
    mainClass = requireText(mainClass, "mainClass");
    capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Plugin descriptor field '" + name + "' is required");
    }
    return value.trim();
  }
}
