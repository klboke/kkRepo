package com.github.klboke.kkrepo.protocol.conda;

import java.util.regex.Pattern;

/** CEP 26 validation for distributable Conda package identifiers. */
public final class CondaPackageIdentifiers {
  public static final int MAX_NAME_LENGTH = 64;
  public static final int MAX_VERSION_LENGTH = 64;
  public static final int MAX_BUILD_LENGTH = 64;
  public static final int MAX_FILENAME_LENGTH = 211;

  private static final Pattern NAME = Pattern.compile("[a-z0-9_][a-z0-9._-]*");
  private static final Pattern CONSECUTIVE_SEPARATORS = Pattern.compile("[._-]{2}");
  private static final Pattern BUILD = Pattern.compile("[A-Za-z0-9_.+]+");
  private static final String LEGACY_DEFAULTS_PACKAGE = "__anaconda_core_depends";

  private CondaPackageIdentifiers() {
  }

  public static boolean isName(String value) {
    return value != null
        && !value.isEmpty()
        && value.length() <= MAX_NAME_LENGTH
        && NAME.matcher(value).matches()
        && !value.startsWith("__")
        && !CONSECUTIVE_SEPARATORS.matcher(value).find();
  }

  /**
   * Accepts names that may legitimately occur in remote repodata. CEP 26 documents one
   * historical defaults-channel exception; keep new hosted uploads on the stricter {@link
   * #isName(String)} contract.
   */
  public static boolean isUpstreamName(String value) {
    return isName(value) || LEGACY_DEFAULTS_PACKAGE.equals(value);
  }

  public static boolean isBuild(String value) {
    return value != null
        && !value.isEmpty()
        && value.length() <= MAX_BUILD_LENGTH
        && BUILD.matcher(value).matches();
  }

  public static boolean isFilename(String value) {
    return value != null && value.length() <= MAX_FILENAME_LENGTH;
  }
}
