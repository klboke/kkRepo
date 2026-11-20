package com.github.klboke.kkrepo.plugin.api;

import java.util.Locale;

/** Repository kind without a dependency on the server enum. */
public enum RepositoryKind {
  HOSTED,
  PROXY,
  GROUP;

  public static RepositoryKind from(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Repository kind is required");
    }
    return RepositoryKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
  }
}
