package com.github.klboke.kkrepo.protocol.conan;

/** Parsed path below one Nexus-style Conan repository base URL. */
public record ConanPath(
    Kind kind,
    String rawPath,
    ConanReference reference,
    String filePath) {
  public enum Kind {
    PING,
    AUTHENTICATE,
    CHECK_CREDENTIALS,
    RECIPE_SEARCH,
    PACKAGE_SEARCH,
    RECIPE,
    RECIPE_LATEST,
    RECIPE_REVISIONS,
    RECIPE_REVISION,
    RECIPE_FILES,
    RECIPE_FILE,
    PACKAGES,
    PACKAGE,
    PACKAGE_LATEST,
    PACKAGE_REVISIONS,
    PACKAGE_REVISION,
    PACKAGE_FILES,
    PACKAGE_FILE,
    UNKNOWN
  }

  public boolean fileResource() {
    return kind == Kind.RECIPE_FILE || kind == Kind.PACKAGE_FILE;
  }

  public boolean discoveryResource() {
    return switch (kind) {
      case RECIPE_SEARCH, PACKAGE_SEARCH, RECIPE_LATEST, RECIPE_REVISIONS,
          RECIPE_FILES, PACKAGE_LATEST, PACKAGE_REVISIONS, PACKAGE_FILES -> true;
      default -> false;
    };
  }

  public boolean packageResource() {
    return switch (kind) {
      case PACKAGE_SEARCH, PACKAGES, PACKAGE, PACKAGE_LATEST, PACKAGE_REVISIONS,
          PACKAGE_REVISION, PACKAGE_FILES, PACKAGE_FILE -> true;
      default -> false;
    };
  }
}
