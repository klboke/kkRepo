package com.github.klboke.kkrepo.plugin.api;

import java.util.Set;

/** Metadata describing a repository format contributed by a plugin. */
public record RepositoryFormatDescriptor(
    FormatId id,
    String name,
    String description,
    Set<String> capabilities) {

  public RepositoryFormatDescriptor {
    if (id == null) {
      throw new IllegalArgumentException("Repository format descriptor id is required");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Repository format descriptor name is required");
    }
    name = name.trim();
    description = description == null ? "" : description.trim();
    capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
  }
}
