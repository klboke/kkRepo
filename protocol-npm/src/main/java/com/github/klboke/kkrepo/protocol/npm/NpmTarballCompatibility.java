package com.github.klboke.kkrepo.protocol.npm;

import java.util.Collection;
import java.util.Objects;

/** Historical lockfile rules, kept separate from declared tarball URL resolution. */
public final class NpmTarballCompatibility {
  private NpmTarballCompatibility() {}

  /** Call only after exact matching. A legacy basename must identify one complete path. */
  public static String legacyBasenameAlias(Collection<String> identities, String requested) {
    // Request identities are already decoded. Never decode literal percent sequences again.
    if (requested == null || requested.contains("/")) return null;
    var matches = identities.stream()
        .filter(Objects::nonNull)
        .filter(identity -> requested.equals(NpmMetadata.extractTarballName(identity)))
        .distinct().toList();
    return matches.size() == 1 ? matches.getFirst() : null;
  }

  public enum Fallback {
    REQUEST_PATH,
    UNDECLARED_PATH,
    AMBIGUOUS_BASENAME
  }

  /**
   * Preserve direct registry-path downloads when metadata is unavailable or has no matching name.
   * A known basename must never be rebound to an invented directory or an ambiguous release.
   * The caller retains the raw request encoding when constructing the fallback URL.
   */
  public static Fallback fallback(Collection<String> identities, String requested) {
    String basename = NpmMetadata.extractTarballName(requested);
    boolean knownBasename = identities.stream().filter(Objects::nonNull)
        .anyMatch(identity -> Objects.equals(basename, NpmMetadata.extractTarballName(identity)));
    if (!knownBasename) return Fallback.REQUEST_PATH;
    return requested != null && requested.contains("/")
        ? Fallback.UNDECLARED_PATH : Fallback.AMBIGUOUS_BASENAME;
  }
}
