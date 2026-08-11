package com.github.klboke.kkrepo.protocol.conan;

/**
 * Pure, write-time projection of a Conan revision file into the Nexus-compatible Browse tree.
 *
 * <p>The projection is intentionally one-way. Runtime Browse requests read persisted
 * {@code browse_node} rows and never reverse-map an asset storage path.
 */
public final class ConanBrowsePathProjector {
  public String project(ConanReference reference, String filePath) {
    return ConanPaths.browsePath(reference, filePath);
  }
}
