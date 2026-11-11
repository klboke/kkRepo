package com.github.klboke.kkrepo.plugin.api;

import java.util.Locale;

/** Open repository format identifier. Built-in formats and plugins share the same namespace. */
public record FormatId(String value) {

  public FormatId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Format id is required");
    }
    value = value.trim().toLowerCase(Locale.ROOT);
    if (!value.matches("[a-z0-9][a-z0-9._-]*")) {
      throw new IllegalArgumentException("Invalid format id: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
