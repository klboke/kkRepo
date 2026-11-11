package com.github.klboke.kkrepo.plugin.api;

import java.util.Map;

/** Portable repository configuration exposed to a plugin handler. */
public record RepositoryDescriptor(
    long id,
    String name,
    FormatId format,
    RepositoryKind type,
    String recipeName,
    boolean online,
    Long blobStoreId,
    String writePolicy,
    String versionPolicy,
    String layoutPolicy,
    String proxyRemoteUrl,
    Map<String, Object> attributes) {

  public RepositoryDescriptor {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Repository name is required");
    }
    if (format == null || type == null) {
      throw new IllegalArgumentException("Repository format and type are required");
    }
    name = name.trim();
    recipeName = recipeName == null ? "" : recipeName.trim();
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }
}
