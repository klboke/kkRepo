package com.github.klboke.kkrepo.server.scripts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Descriptor read from a script module's {@code module.json}. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ScriptModuleDescriptor(
    String id,
    String language,
    String entrypoint,
    List<String> capabilities,
    List<Route> routes) {

  public ScriptModuleDescriptor {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("Script module id is required");
    }
    id = id.trim();
    if (entrypoint == null || entrypoint.isBlank()) {
      throw new IllegalArgumentException("Script entrypoint is required: " + id);
    }
    entrypoint = entrypoint.trim();
    capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    if (capabilities.isEmpty()) {
      throw new IllegalArgumentException("Script module declares no capability: " + id);
    }
    routes = routes == null ? List.of() : List.copyOf(routes);
  }

  /** One repository protocol route declared by a script module. */
  @JsonIgnoreProperties(ignoreUnknown = false)
  public record Route(
      String format,
      String type,
      String method,
      String pathPattern,
      int order) {
  }
}
