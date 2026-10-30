package com.github.klboke.kkrepo.protocol.arch;

import java.util.Locale;
import java.util.Set;

/**
 * Canonical pacman package filename identity.
 *
 * <p>The name is parsed from the right because Arch package names may contain hyphens while
 * {@code pkgver} and {@code pkgrel} do not. The version exposed to clients is
 * {@code <pkgver>-<pkgrel>}, matching the {@code %VERSION%} field of a repository database.
 */
public record ArchPackageFilename(
    String fileName,
    String name,
    String version,
    String pkgver,
    String pkgrel,
    String architecture,
    String compression) {

  private static final Set<String> COMPRESSIONS = Set.of(
      "tar", "tar.zst", "tar.xz", "tar.gz", "tar.bz2", "tar.lrz", "tar.lz", "tar.lz4",
      "tar.lzo", "tar.Z");

  public static ArchPackageFilename parse(String fileName) {
    String value = fileName == null ? "" : fileName;
    for (String compression : COMPRESSIONS) {
      String suffix = ".pkg." + compression;
      if (!value.endsWith(suffix)) continue;
      String base = value.substring(0, value.length() - suffix.length());
      String[] parts = base.split("-");
      if (parts.length < 4) return null;
      String architecture = parts[parts.length - 1];
      String pkgrel = parts[parts.length - 2];
      String pkgver = parts[parts.length - 3];
      String name = String.join("-", java.util.Arrays.copyOf(parts, parts.length - 3));
      if (name.isBlank() || pkgver.isBlank() || pkgrel.isBlank() || architecture.isBlank()) {
        return null;
      }
      return new ArchPackageFilename(value, name, pkgver + "-" + pkgrel, pkgver, pkgrel,
          architecture, compression);
    }
    return null;
  }

  public static boolean looksLikePackage(String fileName) {
    return parse(fileName) != null;
  }

  public String extension() {
    return compression == null ? "" : compression.toLowerCase(Locale.ROOT);
  }

  public boolean zstd() {
    return "tar.zst".equals(compression);
  }
}
