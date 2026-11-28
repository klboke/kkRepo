package com.github.klboke.kkrepo.plugin.api;

/** Portable view of the repository write/version/layout policies. */
public record RepositoryPolicy(
    String writePolicy,
    String versionPolicy,
    String layoutPolicy) {

  public RepositoryPolicy {
    writePolicy = normalize(writePolicy);
    versionPolicy = normalize(versionPolicy);
    layoutPolicy = normalize(layoutPolicy);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
